"""Real routes and concurrent state writes protect transcript-owned controls."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient

import brain_context
import chat_store
import chat_interactions as actions
from tools import ui_tools
from tool_activity import ActivityLog


@pytest.fixture
def context(tmp_path, monkeypatch):
    monkeypatch.setattr(chat_store, "CHATS_DIR", tmp_path / "chats")
    monkeypatch.setattr(chat_store, "USER_DATA_DIR", tmp_path / "users")
    monkeypatch.setattr(ui_tools.events, "publish_ui", lambda *args, **kwargs: None)
    import main
    monkeypatch.setattr(main.brains, "chat", AsyncMock(return_value=("Fixture reply", "idle", "demo", [])))
    monkeypatch.setattr(main, "_extract_and_save_facts", lambda *args: None)
    monkeypatch.setattr(main, "_autoname_chat", AsyncMock())
    monkeypatch.setattr(main.memory, "append_chat_log", lambda *args: None)
    monkeypatch.setattr(main.display_bridge, "send_chat_exchange_bg", lambda *args: None)
    return TestClient(main.app)


def record(tool="todo_list", call="fixture-call", **args):
    args = {**({"items": ["Task"]} if tool == "todo_list" else {"question": "Choose?", "options": ["Yes"]}), **args}
    result = asyncio.run(ui_tools.HANDLERS[tool](**args))
    log = ActivityLog()
    log.record({"type": "tool_start", "call_id": call, "tool": tool, "input": args})
    log.record({"type": "tool_done", "call_id": call, "tool": tool, "result": result})
    chat_store.append("fixture", "Plan", "", steps=log.finish())
    return result


def test_real_routes_persist_ticks_without_rewriting_tool_input(context):
    record()
    before = chat_store.load("fixture")["messages"]
    path = "/api/sessions/fixture/interactions/fixture-call"
    assert context.get(path).json() == {"revision": 0, "done": {}, "answer": None}
    result = context.post(path, json={"action": "toggle", "item_id": "item-0", "done": True, "expected_revision": 0})
    assert result.status_code == 200, result.text
    assert result.json()["done"] == {"item-0": True}
    assert actions.read("fixture", "fixture-call")["revision"] == 1
    assert chat_store.load("fixture")["messages"] == before
    assert context.post(path, json={"action": "toggle", "item_id": "item-0", "done": False, "expected_revision": 0}).status_code == 409
    assert context.get(path).json()["done"]["item-0"] is True


def test_concurrent_updates_have_one_winner(context):
    record()
    def change(value):
        try:
            return actions.update("fixture", "fixture-call", actions.Action(action="toggle", item_id="item-0", done=value, expected_revision=0))["revision"]
        except actions.InteractionError as error:
            return error.code
    with ThreadPoolExecutor(2) as pool:
        results = list(pool.map(change, [True, False]))
    assert sorted(map(str, results)) == ["1", "interaction_conflict"]


def test_owner_and_conversation_kind_are_isolated(context):
    with brain_context.set_clerk_user("fixture-owner-a"):
        record()
        assert actions.read("fixture", "fixture-call")["revision"] == 0
        with chat_store.set_kind(chat_store.KIND_CODE):
            with pytest.raises(actions.InteractionError) as error:
                actions.read("fixture", "fixture-call")
            assert error.value.status == 404
    with brain_context.set_clerk_user("fixture-owner-b"):
        with pytest.raises(actions.InteractionError) as error:
            actions.update("fixture", "fixture-call", actions.Action(action="toggle", item_id="item-0", done=True, expected_revision=0))
        assert error.value.status == 404


def test_answer_receipt_is_saved_atomically_with_the_real_turn(context):
    record("ask_question")
    request = {"message": "Yes", "session_id": "fixture", "tool_answer": {
        "call_id": "fixture-call", "option_id": "option-0", "value": "Yes", "expected_revision": 0}}
    response = context.post("/api/chat", json=request)
    assert response.status_code == 200, response.text
    message_id = response.json()["user_message_id"]
    state = context.get("/api/sessions/fixture/interactions/fixture-call").json()
    assert state["answer"] == {"message_id": message_id, "option_id": "option-0", "value": "Yes"}
    # Retrying after a lost browser acknowledgment cannot create another turn.
    before = len(chat_store.load("fixture")["messages"])
    assert context.post("/api/chat", json=request).status_code == 409
    assert len(chat_store.load("fixture")["messages"]) == before


def test_custom_and_canonical_answers_require_matching_value_and_owner(context):
    record("ask_question", options=[])
    request = {"message": "Custom", "session_id": "fixture", "tool_answer": {
        "call_id": "fixture-call", "option_id": "option-0", "value": "Custom", "expected_revision": 0}}
    assert context.post("/api/chat", json=request).status_code == 400
    request["tool_answer"]["option_id"] = ""
    assert context.post("/api/chat", json=request).status_code == 200


def test_failed_save_is_explicit_and_leaves_old_tick(context, monkeypatch):
    record()
    def failed_database():
        import sqlite3
        raise sqlite3.OperationalError("fixture failure")
    monkeypatch.setattr(actions, "_database", failed_database)
    response = context.post("/api/sessions/fixture/interactions/fixture-call", json={"action": "toggle", "item_id": "item-0", "done": True, "expected_revision": 0})
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "interaction_unavailable"


def test_unconfirmed_answer_cannot_fabricate_a_receipt(context):
    record("ask_question")
    response = context.post("/api/sessions/fixture/interactions/fixture-call", json={"action": "answer", "option_id": "option-0", "value": "Yes", "message_id": "missing", "expected_revision": 0})
    assert response.status_code == 409
    assert actions.read("fixture", "fixture-call")["answer"] is None


def test_real_api_cannot_read_or_modify_another_owners_call(context, monkeypatch):
    with brain_context.set_clerk_user("fixture-owner-a"):
        record()
    import main
    monkeypatch.setattr(main.mobile_bridge, "request_mobile_user", lambda request: request.headers.get("x-fixture-owner"))
    path = "/api/sessions/fixture/interactions/fixture-call"
    assert context.get(path, headers={"x-fixture-owner": "fixture-owner-b"}).status_code == 404
    change = {"owner_id": "fixture-owner-b", "action": "toggle", "item_id": "item-0", "done": True, "expected_revision": 0}
    assert context.post(path, json=change, headers={"x-fixture-owner": "fixture-owner-b"}).status_code == 404
    change["owner_id"] = "fixture-owner-a"
    assert context.post(path, json=change, headers={"x-fixture-owner": "fixture-owner-a"}).status_code == 200


def test_question_receipt_survives_a_streamed_turn(context):
    record("ask_question")
    response = context.post("/api/chat", json={"message": "Yes", "stream": True, "session_id": "fixture", "tool_answer": {
        "call_id": "fixture-call", "option_id": "option-0", "value": "Yes", "expected_revision": 0}})
    assert response.status_code == 200
    assert "event: done" in response.text
    assert context.get("/api/sessions/fixture/interactions/fixture-call").json()["answer"]["value"] == "Yes"


def test_action_records_are_bounded(context, monkeypatch):
    record(call="first")
    record(call="second")
    monkeypatch.setattr(actions, "MAX_RECORDS", 1)
    action = actions.Action(action="toggle", item_id="item-0", done=True, expected_revision=0)
    actions.update("fixture", "first", action)
    with pytest.raises(actions.InteractionError) as error:
        actions.update("fixture", "second", action)
    assert error.value.code == "interaction_limit"


def test_whitespace_alias_cannot_bypass_revision_or_turn_identity(context):
    record("ask_question")
    assert context.get("/api/sessions/fixture%0A/interactions/fixture-call").status_code == 400
    assert context.post("/api/chat", json={"message": "Yes", "session_id": "fixture\n", "tool_answer": {
        "call_id": "fixture-call", "option_id": "option-0", "value": "Yes", "expected_revision": 0}}).status_code == 400


@pytest.mark.parametrize("prefix", ["tools__", "workspace__", "emotions__"])
def test_allowlisted_tool_namespaces_have_the_same_interaction_contract(context, prefix):
    record("ask_question")
    path = chat_store._path("fixture")
    import json
    transcript = chat_store.load("fixture")
    transcript["messages"][-1]["steps"][0]["label"] = prefix + "ask_question"
    path.write_text(json.dumps(transcript))
    assert context.get("/api/sessions/fixture/interactions/fixture-call").status_code == 200


def test_concurrent_answer_retries_run_the_model_once(context, monkeypatch):
    import httpx
    import main
    record("ask_question")
    calls = []
    async def slow_reply(*args, **kwargs):
        calls.append(True)
        await asyncio.sleep(0.02)
        return "Fixture reply", "idle", "demo", []
    monkeypatch.setattr(main.brains, "chat", slow_reply)
    request = {"message": "Yes", "session_id": "fixture", "tool_answer": {
        "call_id": "fixture-call", "option_id": "option-0", "value": "Yes", "expected_revision": 0}}
    async def retry_twice():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://testserver") as client:
            return await asyncio.gather(client.post("/api/chat", json=request), client.post("/api/chat", json=request))
    responses = asyncio.run(retry_twice())
    assert sorted(response.status_code for response in responses) == [200, 409]
    assert len(calls) == 1


def test_replacement_account_cannot_accept_a_stale_card_answer(context, monkeypatch):
    with brain_context.set_clerk_user("fixture-owner-b"):
        record("ask_question")
    import main
    monkeypatch.setattr(main.mobile_bridge, "request_mobile_user", lambda request: "fixture-owner-b")
    response = context.post("/api/chat", json={"message": "Yes", "session_id": "fixture", "tool_answer": {
        "owner_id": "fixture-owner-a", "call_id": "fixture-call", "option_id": "option-0", "value": "Yes", "expected_revision": 0}})
    assert response.status_code == 403
    main.brains.chat.assert_not_awaited()
