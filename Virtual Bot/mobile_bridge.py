"""Small integration helpers; the existing application remains the backend."""

from __future__ import annotations

import asyncio
import json
import os
import tempfile
import uuid
import weakref
from contextvars import ContextVar
from weakref import WeakValueDictionary
from urllib.parse import urlsplit

from fastapi import Request

import mobile_api

_turn_locks: WeakValueDictionary[tuple[str, str], asyncio.Lock] = WeakValueDictionary()
_held_turns: ContextVar[frozenset[tuple[str, str]]] = ContextVar("shared_chat_turns", default=frozenset())


async def serialized_turn(request, user_id: str, source: str, run, on_note=None):
    """PC, phone and messenger turns share one lease per owner/conversation."""
    from fastapi import HTTPException

    if not request.session_id:
        request.session_id = uuid.uuid4().hex[:16]
    key = (user_id, request.session_id)
    if key in _held_turns.get():
        raise HTTPException(409, {"code": "conversation_busy"})
    lock = _turn_locks.get(key)
    if lock is None:
        lock = asyncio.Lock()
        _turn_locks[key] = lock
    await lock.acquire()
    released = False
    loop = asyncio.get_running_loop()

    def release():
        nonlocal released
        if not released:
            released = True
            lock.release()

    handle = _held_turns.set(_held_turns.get() | {key})
    try:
        response = await run(request, user_id, source, on_note=on_note)
    except BaseException:
        release()
        raise
    finally:
        _held_turns.reset(handle)
    if not hasattr(response, "body_iterator"):
        release()
        return response
    iterator = response.body_iterator

    async def body():
        context = _held_turns.set(_held_turns.get() | {key})
        try:
            async for chunk in iterator:
                yield chunk
        finally:
            try:
                if hasattr(iterator, "aclose"):
                    await iterator.aclose()
            finally:
                _held_turns.reset(context)
                release()

    response.body_iterator = body()
    # If ASGI cancels before it ever starts the iterator, its response must
    # not strand a conversation lease. Normal iteration releases it earlier.
    def abandoned():
        if not loop.is_closed():
            loop.call_soon_threadsafe(release)
    weakref.finalize(response, abandoned)
    return response


def is_api_host(host: str) -> bool:
    configured = os.environ.get("MOBILE_API_ORIGIN", "https://api-bot.waveio.me")
    return bool(host and host.casefold() == (urlsplit(configured).hostname or "").casefold())


def request_mobile_user(request: Request) -> str | None:
    return mobile_api.authenticate_token(mobile_api._token(request))


def model_metadata(event: dict) -> dict:
    """Capture only task-local model disclosure for the shared history file."""
    options = mobile_api.current_turn_options()
    if options is None or not event.get("provider") or not event.get("model"):
        return {}
    provider, model = str(event["provider"]), str(event["model"])
    model = model if model.startswith(provider + "/") else f"{provider}/{model}"
    requested = str(options.get("model") or "")
    explicit = requested and requested not in {"auto", "jev", "jev/auto"}
    return {"provider": provider, "model": model, "requested_model": requested,
            "fallback": bool(event.get("fallback") or (explicit and requested != model))}


def persist_model(session_id: str, message_id: str, metadata: dict) -> None:
    """Fill the existing assistant record under the parent's shared turn lease.

    chat_store has no metadata-update primitive; keep its private path seam
    here, like mobile fork materialization, without another history namespace.
    The caller must run this immediately after append, before any await.
    """
    import chat_store

    data = chat_store.load(session_id)
    message = next((item for item in data["messages"] if item.get("id") == message_id), None)
    if message is None or message.get("role") != "assistant":
        return
    message.update(metadata)
    path = chat_store._path(session_id)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".mobile-model-", delete=False) as stream:
            temporary = stream.name
            json.dump(data, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None and os.path.exists(temporary):
            os.unlink(temporary)


def mobile_path_allowed(path: str) -> bool:
    return (
        path.startswith(("/api/mobile/", "/api/sessions/", "/uploads/"))
        or path in {
            "/api/sessions", "/api/brain/models", "/api/setup", "/api/status",
            "/api/workspace/list", "/api/workspace/file", "/api/chat/upload",
            "/api/chat/attachment-preview", "/api/asr", "/api/asr/partial",
            "/api/openclaw/settings",
        }
    )


def runner(chat_request_type, chat_turn):
    async def run(job: dict):
        # Attachment-only turns keep the legacy request's nonempty contract.
        # The visible user content is their actual attachment manifest.
        message = job["message"] or "[attachments]"
        request = chat_request_type(
            message=message, stream=True, session_id=job["session_id"],
            attachments=job["attachments"], history=job.get("history", []),
            participant_name=job.get("participant_name", ""), reasoning_effort="none",
        )
        # The mobile schema already validated that empty text has attachments.
        # Keep the original visible content after satisfying the legacy schema.
        request.message = job["message"]
        response = await chat_turn(request, job["user_id"], "chat")
        effective_model = ""
        async for name, data in mobile_api.iter_chat_events(response):
            if name == "model":
                provider = str(data.get("provider") or "")
                model = str(data.get("model") or "")
                effective_model = model if model.startswith(provider + "/") else f"{provider}/{model}"
            if name == "done" and effective_model:
                data = {**data, "model": effective_model}
            if name == "error":
                # Only the host diagnostics retain provider exception detail.
                data = {"error": "mobile_turn_failed", "steps": data.get("steps", [])}
            yield name, data
    return run
