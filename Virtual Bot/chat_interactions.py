"""Owner-scoped, revision-checked actions without rewriting tool provenance."""
from __future__ import annotations

import asyncio
import json
import re
import sqlite3
from typing import Literal

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field

import brain_context
import chat_store

MAX_RECORDS = 2000


class InteractionError(ValueError):
    def __init__(self, code: str, status: int = 400):
        self.code, self.status = code, status
        super().__init__(code)


def _record(value):
    return value if isinstance(value, dict) else {}


def _receipt(result):
    result = _record(result)
    if "ui" in result:
        return _record(result["ui"])
    for value in (result.get("result"), result.get("structuredContent")):
        if "ui" in _record(value):
            return _record(value["ui"])
    for block in result.get("content", []) if isinstance(result.get("content"), list) else []:
        if _record(block).get("type") != "text":
            continue
        try:
            value = json.loads(str(block.get("text", ""))[:8000])
        except ValueError:
            continue
        for payload in (_record(value), _record(_record(value).get("result"))):
            if "ui" in payload:
                return _record(payload["ui"])
    return None


def _clean(value, limit):
    return " ".join(str(value or "").split())[:limit]


def _source(session_id: str, call_id: str):
    if session_id != session_id.strip() or not chat_store.is_valid_id(session_id) or not call_id or len(call_id) > 200:
        raise InteractionError("interaction_invalid")
    data = chat_store.load(session_id)
    steps = [step for message in data.get("messages", []) if message.get("role") == "assistant"
             for step in message.get("steps", []) if isinstance(step, dict) and step.get("id") == call_id]
    if len(steps) != 1 or steps[0].get("status") != "done":
        raise InteractionError("interaction_not_found", 404)
    step = steps[0]
    name = re.sub(r"^(?:tools|workspace|emotions)__", "", str(step.get("label", "")))
    if name not in {"ask_question", "show_choice", "todo_list"}:
        raise InteractionError("interaction_not_found", 404)
    receipt = _receipt(step.get("result"))
    if receipt is not None:
        expected = {"ask_question": "question", "show_choice": "choice", "todo_list": "todo"}[name]
        if receipt.get("version") != 1 or receipt.get("kind") != expected:
            raise InteractionError("interaction_invalid")
        payload = _record(receipt.get("data"))
    else:
        # Explicit legacy v0 reconstruction; original arguments stay immutable.
        args = _record(step.get("input"))
        payload = {"allow_custom": args.get("allow_custom") is not False}
        values = args.get("items" if name == "todo_list" else "options", [])
        values = values if isinstance(values, list) else []
        if name == "todo_list":
            items = []
            for value in values[:20]:
                text = _clean(_record(value).get("text") if isinstance(value, dict) else value, 200)
                if text:
                    items.append({"id": f"item-{len(items)}", "text": text, "done": bool(_record(value).get("done"))})
            payload["items"] = items
        else:
            options = []
            for value in (values if name == "ask_question" else values[:6]):
                label = _clean((_record(value).get("label") or _record(value).get("title")) if isinstance(value, dict) else value, 120 if name == "ask_question" else 80)
                if label:
                    options.append({"id": f"option-{len(options)}", "label": label})
            payload["options"] = options[:6]
    return name, payload, data


def _database():
    folder = chat_store._active_chats_dir()
    folder.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(folder / ".interactions.sqlite3", timeout=1)
    connection.execute("CREATE TABLE IF NOT EXISTS actions (session_id TEXT, call_id TEXT, revision INTEGER NOT NULL, state TEXT NOT NULL, PRIMARY KEY(session_id, call_id))")
    return connection


def _state(connection, session_id, call_id):
    row = connection.execute("SELECT revision,state FROM actions WHERE session_id=? AND call_id=?", (session_id, call_id)).fetchone()
    return {"revision": row[0], **json.loads(row[1])} if row else {"revision": 0, "done": {}, "answer": None}


def read(session_id: str, call_id: str):
    _, _, transcript = _source(session_id, call_id)
    connection = _database()
    try:
        state = _state(connection, session_id, call_id)
        state["answer"] = _answer_receipt(transcript, call_id) or state["answer"]
        return state
    finally:
        connection.close()


class Action(BaseModel):
    model_config = {"extra": "forbid"}
    owner_id: str = Field(default="", max_length=200)
    action: Literal["toggle", "answer"]
    expected_revision: int = Field(ge=0)
    item_id: str = Field(default="", max_length=40)
    done: bool | None = None
    option_id: str = Field(default="", max_length=40)
    value: str = Field(default="", max_length=4000)
    message_id: str = Field(default="", max_length=100)


class AnswerRequest(BaseModel):
    model_config = {"extra": "forbid"}
    owner_id: str = Field(default="", max_length=200)
    call_id: str = Field(min_length=1, max_length=200)
    option_id: str = Field(default="", max_length=40)
    value: str = Field(min_length=1, max_length=4000)
    expected_revision: int = Field(ge=0)


def _answer_receipt(transcript, call_id):
    for message in transcript.get("messages", []):
        answer = _record(message.get("tool_answer"))
        if message.get("role") == "user" and answer.get("call_id") == call_id:
            return {"message_id": message["id"], "option_id": answer.get("option_id", ""), "value": answer["value"]}
    return None


def validate_answer(session_id: str, answer: AnswerRequest, message: str):
    name, payload, transcript = _source(session_id, answer.call_id)
    state = read(session_id, answer.call_id)
    if state["answer"] is not None:
        raise InteractionError("interaction_answered", 409)
    if state["revision"] != answer.expected_revision:
        raise InteractionError("interaction_conflict", 409)
    if not answer.value.strip() or message != answer.value.strip() or name == "todo_list":
        raise InteractionError("interaction_invalid")
    options, ids = payload.get("options", []), payload.get("option_ids", [])
    expected = {value.get("id"): value.get("label") for value in options if isinstance(value, dict)}
    expected.update({ids[index]: value for index, value in enumerate(options) if isinstance(value, str) and index < len(ids)})
    if answer.option_id:
        if expected.get(answer.option_id) != answer.value:
            raise InteractionError("interaction_invalid")
    elif name != "ask_question" or payload.get("allow_custom") is False:
        raise InteractionError("interaction_invalid")


def update(session_id: str, call_id: str, action: Action):
    name, payload, transcript = _source(session_id, call_id)
    connection = _database()
    try:
        connection.execute("BEGIN IMMEDIATE")
        current = _state(connection, session_id, call_id)
        accepted = _answer_receipt(transcript, call_id)
        if accepted:
            current["answer"] = accepted
            if action.action == "answer" and accepted == {"message_id": action.message_id, "option_id": action.option_id, "value": action.value}:
                return current
        if current["revision"] != action.expected_revision:
            raise InteractionError("interaction_conflict", 409)
        if action.action == "toggle":
            items = payload.get("items", [])
            if name != "todo_list" or not isinstance(items, list) or action.item_id not in {item.get("id") for item in items if isinstance(item, dict)} or action.done is None:
                raise InteractionError("interaction_invalid")
            current["done"][action.item_id] = action.done
        else:
            # A message ID alone cannot prove an answer to this call. The
            # shared chat turn stores that relationship atomically in history.
            raise InteractionError("interaction_unconfirmed", 409)
        current["revision"] += 1
        # Pruned/deleted conversations must not accumulate orphan action rows.
        live = [path.stem for path in chat_store._active_chats_dir().glob("*.json") if chat_store.is_valid_id(path.stem)]
        placeholders = ",".join("?" for _ in live) or "NULL"
        connection.execute(f"DELETE FROM actions WHERE session_id NOT IN ({placeholders})", live)
        count = connection.execute("SELECT COUNT(*) FROM actions WHERE session_id=?", (session_id,)).fetchone()[0]
        if count >= MAX_RECORDS and current["revision"] == 1:
            raise InteractionError("interaction_limit", 409)
        connection.execute("INSERT INTO actions VALUES (?,?,?,?) ON CONFLICT(session_id,call_id) DO UPDATE SET revision=excluded.revision,state=excluded.state",
            (session_id, call_id, current["revision"], json.dumps({"done": current["done"], "answer": current["answer"]})))
        connection.commit()
        return current
    finally:
        connection.close()


def router(require_user):
    routes = APIRouter()

    async def run(request, session_id, call_id, kind, action=None):
        owner = await require_user(request)
        if action is not None and action.owner_id != owner:
            raise HTTPException(403, detail={"code": "interaction_owner_changed"})
        selected_kind = chat_store.KIND_CODE if kind == chat_store.KIND_CODE else chat_store.KIND_CHAT
        with brain_context.set_clerk_user(owner), chat_store.set_kind(selected_kind):
            try:
                return await asyncio.to_thread(read if action is None else update, session_id, call_id, *([] if action is None else [action]))
            except InteractionError as error:
                raise HTTPException(error.status, detail={"code": error.code}) from error
            except (sqlite3.Error, OSError) as error:
                raise HTTPException(503, detail={"code": "interaction_unavailable"}) from error

    @routes.get("/api/sessions/{session_id}/interactions/{call_id}")
    async def get_state(session_id: str, call_id: str, request: Request, kind: str = ""):
        return await run(request, session_id, call_id, kind)

    @routes.post("/api/sessions/{session_id}/interactions/{call_id}")
    async def change_state(session_id: str, call_id: str, request: Request, action: Action, kind: str = ""):
        return await run(request, session_id, call_id, kind, action)

    return routes
