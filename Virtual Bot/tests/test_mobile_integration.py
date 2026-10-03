"""Functional mobile integration with isolated storage and no provider calls."""

import asyncio
import json
from contextlib import nullcontext

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from starlette.requests import Request
import pytest

import brain_context
import brains
import chat_store
import main
import mobile_api
import mobile_bridge
import mobile_routing
from mobile_store import MobileStore


def request(host="api-bot.waveio.me", token=None):
    headers = [(b"host", host.encode())]
    if token:
        headers.append((b"authorization", f"Bearer {token}".encode()))
    return Request({"type": "http", "method": "GET", "path": "/api/sessions", "query_string": b"",
                    "scheme": "https", "server": (host, 443), "client": ("127.0.0.1", 1), "headers": headers})


def credential(store, user=""):
    code = store.create_pairing(user)["code"]
    return store.exchange(code, "Test phone", "android")["token"]


def test_device_access_uses_existing_local_owner(tmp_path, monkeypatch):
    store = MobileStore(tmp_path / "mobile.db")
    monkeypatch.setattr(mobile_api, "_default_store", store)
    monkeypatch.setattr(main.auth_clerk, "is_auth_disabled", lambda: True)
    req = request(token=credential(store))
    assert asyncio.run(main._require_user(req)) == ""
    assert main._clerk_user_or_none(req) == ""
    with pytest.raises(HTTPException) as failed:
        asyncio.run(main._require_user(request()))
    assert failed.value.status_code == 401


def test_reserved_api_host_keeps_real_api_route(monkeypatch):
    monkeypatch.setenv("MOBILE_API_ORIGIN", "https://api-bot.waveio.me")
    seen = []

    async def downstream(req):
        seen.append(req.scope["path"])
        return "ok"

    assert asyncio.run(main.rewrite_share_host(request(), downstream)) == "ok"
    assert seen == ["/api/sessions"]


@pytest.mark.parametrize("user_id", ["", "alice"])
@pytest.mark.parametrize("message", ["A user message", ""])
def test_mobile_turn_saves_into_shared_history(tmp_path, monkeypatch, user_id, message):
    store = MobileStore(tmp_path / "mobile.db")
    monkeypatch.setattr(mobile_api, "_default_store", store)
    monkeypatch.setattr(main.auth_clerk, "is_auth_disabled", lambda: True)
    monkeypatch.setattr(chat_store, "CHATS_DIR", tmp_path / "chats")
    monkeypatch.setattr(chat_store, "USER_DATA_DIR", tmp_path / "users")
    monkeypatch.setattr(brain_context, "USER_DATA_DIR", tmp_path / "users")
    monkeypatch.setattr(brain_context, "BRAIN_RUNTIME_DIR", tmp_path / "users")
    monkeypatch.setattr(main, "_extract_and_save_facts", lambda message: None)
    monkeypatch.setattr(main, "_sessions", {})
    monkeypatch.setattr(brains, "get_last_model", lambda: "other/concurrent-title")
    monkeypatch.setattr(main.display_bridge, "send_chat_exchange_bg", lambda *a, **k: None)
    monkeypatch.setattr(main.memory, "append_chat_log", lambda *a, **k: None)

    async def no_title(*args):
        return None

    async def chat(message, history, emit=None, **kwargs):
        assert mobile_api.current_turn_options()["model"] == "provider/requested"
        await emit({"type": "model", "provider": "provider", "model": "effective"})
        await emit({"type": "delta", "chunk": "A mobile reply"})
        return "A mobile reply", "happy", "test", []

    monkeypatch.setattr(main, "_autoname_chat", no_title)
    monkeypatch.setattr(brains, "chat", chat)
    app = FastAPI()
    app.include_router(mobile_api.router(main._require_user, main._require_openclaw_operator,
                       mobile_bridge.runner(main.ChatRequest, main.chat_turn), store=store, scan_interval=0.01))
    token = credential(store, user_id)
    attachments = []
    if not message:
        uploads = tmp_path / "uploads"
        uploads.mkdir()
        filename = main.chat_attachments.owner_prefix(user_id) + "note.txt"
        (uploads / filename).write_text("A normal attached document", encoding="utf-8")
        monkeypatch.setattr(main.cfg, "UPLOADS_DIR", uploads)
        attachments = [{"url": "/uploads/" + filename, "name": "note.txt"}]
    with TestClient(app) as client:
        client.headers["Authorization"] = f"Bearer {token}"
        submitted = client.post("/api/mobile/messages", json={"client_id": "integration-one", "session_id": "mobile-shared", "message": message, "attachments": attachments, "model": "provider/requested"})
        assert submitted.status_code == 200
        job = submitted.json()
        events = client.get(f"/api/mobile/messages/{job['id']}/events")
        assert events.status_code == 200
        assert '"state":"completed"' in events.text
        assert '"model":"provider/effective"' in events.text
    with brain_context.set_clerk_user(user_id):
        history = chat_store.load("mobile-shared")["messages"]
    assert [item["content"] for item in history] == [message, "A mobile reply"]
    # Reopening the shared chat must retain the same answering model and
    # fallback disclosure as the live turn, even if a title finished elsewhere.
    assert history[-1]["model"] == "provider/effective"
    assert history[-1]["requested_model"] == "provider/requested"
    assert history[-1]["fallback"] is True


def test_same_provider_fallback_is_request_local(monkeypatch):
    calls, patches, emitted = [], [], []
    catalog = [{"id": "a/one", "provider": "a", "available": True},
               {"id": "b/other", "provider": "b", "available": True},
               {"id": "a/two", "provider": "a", "available": True}]

    async def models(): return catalog
    async def patch(method, params): patches.append((method, params)); return {}
    async def emit(event): emitted.append(event)
    async def gateway(*args, **kwargs):
        calls.append(mobile_routing.model_override())
        if calls[-1] == "a/one": raise RuntimeError("unavailable")
        await kwargs["emit"]({"type": "model", "provider": "a", "model": "two"})
        return "Answer", [], "a/two"

    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", models)
    monkeypatch.setattr(mobile_routing.openclaw_control, "_rpc", patch)
    monkeypatch.setattr(brains, "chat_openclaw", gateway)
    handle = mobile_api._turn_options.set({"model": "a/one", "reasoning_effort": "high"})
    try:
        result = asyncio.run(mobile_routing.chat_gateway("Question", "", [], emit=emit, session_key="test-session"))
    finally:
        mobile_api._turn_options.reset(handle)
    assert result[2] == "a/two"
    assert calls == ["a/one", "a/two"]
    assert patches[-1] == ("sessions.patch", {"key": "test-session", "model": "a/two", "thinkingLevel": "high"})
    assert emitted[-1]["fallback"] is True
    assert mobile_routing.model_override() is None


@pytest.mark.parametrize("selected, expected", [("", "a/default"), ("jev/auto", "a/jev")])
def test_default_and_explicit_jev_do_not_use_the_pc_picker(monkeypatch, selected, expected):
    calls = []

    async def models():
        return [{"id": "a/default", "provider": "a", "is_default": True},
                {"id": "a/jev", "provider": "a"},
                {"id": "b/pc", "provider": "b"}]

    async def pc_route(message):
        return {"x-openclaw-model": "b/pc"}, message, "", ""

    async def jev_route(message):
        return "smart", "a/jev", message, "test"

    async def gateway(*args, **kwargs):
        calls.append(mobile_routing.model_override())
        return "Answer", [], calls[-1]

    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", models)
    monkeypatch.setattr(mobile_routing.openclaw_models, "chat_route", pc_route)
    monkeypatch.setattr(mobile_routing.openclaw_models.jev_router, "route", jev_route)
    monkeypatch.setattr(brains, "chat_openclaw", gateway)
    handle = mobile_api._turn_options.set({"model": selected})
    try:
        result = asyncio.run(mobile_routing.chat_gateway("Question", "", []))
    finally:
        mobile_api._turn_options.reset(handle)
    assert calls == [expected]
    assert result[2] == expected


@pytest.mark.parametrize("transport", ["x-clerk-token", "query"])
def test_shared_routes_accept_valid_mobile_credentials_in_legacy_transports(tmp_path, monkeypatch, transport):
    store = MobileStore(tmp_path / "mobile.db")
    monkeypatch.setattr(mobile_api, "_default_store", store)
    token = credential(store)
    req = request()
    if transport == "query":
        req.scope["query_string"] = f"token={token}".encode()
    else:
        req.scope["headers"].append((b"x-clerk-token", token.encode()))
    assert asyncio.run(main._require_user(req)) == ""
    assert main._clerk_user_or_none(req) == ""


def test_no_replay_after_observed_work(monkeypatch):
    calls = []
    async def models(): return [{"id": "a/one", "provider": "a"}, {"id": "a/two", "provider": "a"}]
    async def emit(event): pass
    async def gateway(*args, **kwargs):
        calls.append(mobile_routing.model_override())
        await kwargs["emit"]({"type": "tool_start", "tool": "write_file"})
        raise RuntimeError("connection lost")
    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", models)
    monkeypatch.setattr(brains, "chat_openclaw", gateway)
    handle = mobile_api._turn_options.set({"model": "a/one"})
    try:
        with pytest.raises(mobile_routing.RoutingError, match="mobile_turn_interrupted"):
            asyncio.run(mobile_routing.chat_gateway("Question", "", [], emit=emit))
    finally:
        mobile_api._turn_options.reset(handle)
    assert calls == ["a/one"]


def test_gateway_reported_model_is_the_effective_answering_model(monkeypatch):
    emitted = []

    async def models():
        return [{"id": "a/one", "provider": "a"}, {"id": "a/two", "provider": "a"}]

    async def emit(event):
        emitted.append(event)

    async def gateway(*args, **kwargs):
        return "Answer", [], "a/two"

    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", models)
    monkeypatch.setattr(brains, "chat_openclaw", gateway)
    handle = mobile_api._turn_options.set({"model": "a/one"})
    try:
        result = asyncio.run(mobile_routing.chat_gateway("Question", "", [], emit=emit))
    finally:
        mobile_api._turn_options.reset(handle)
    assert result[2] == "a/two"
    assert emitted[-1] == {"type": "model", "provider": "a", "model": "two", "fallback": True}


def test_mobile_history_reads_disk_and_invalidates_the_pc_memory_cache(tmp_path, monkeypatch):
    monkeypatch.setattr(chat_store, "CHATS_DIR", tmp_path / "chats")
    monkeypatch.setattr(main, "_sessions", {"shared": ([{"role": "user", "content": "old cache"}], 0)})
    with brain_context.set_clerk_user(""):
        chat_store.append("shared", "saved question", "saved answer")
        handle = mobile_api._turn_options.set({"model": "a/one", "user_id": "", "session_id": "shared"})
        try:
            history = main._get_history("shared", [])
            assert [item["content"] for item in history] == ["saved question", "saved answer"]
            main._save_history("shared", history, "phone question", "phone answer")
        finally:
            mobile_api._turn_options.reset(handle)
        assert "shared" not in main._sessions
        assert main._get_history("shared", [])[-1]["content"] == "phone answer"


@pytest.mark.parametrize("effort, expected", [("none", None), ("off", "off")])
def test_session_patch_distinguishes_clearing_effort_from_explicit_off(monkeypatch, effort, expected):
    patches = []

    async def models():
        return [{"id": "a/one", "provider": "a"}]

    async def patch(method, params):
        patches.append((method, params))

    async def gateway(*args, **kwargs):
        return "Answer", [], "a/one"

    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", models)
    monkeypatch.setattr(mobile_routing.openclaw_control, "_rpc", patch)
    monkeypatch.setattr(brains, "chat_openclaw", gateway)
    handle = mobile_api._turn_options.set({"model": "a/one", "reasoning_effort": effort})
    try:
        asyncio.run(mobile_routing.chat_gateway("Question", "", [], session_key="shared"))
    finally:
        mobile_api._turn_options.reset(handle)
    assert patches == [("sessions.patch", {"key": "shared", "model": "a/one", "thinkingLevel": expected})]


def test_housekeeping_does_not_inherit_turn_options():
    async def check():
        handle = mobile_api._turn_options.set({"model": "a/expensive"})
        async def background(): return mobile_api.current_turn_options()
        try:
            assert await mobile_api.create_background_task(background()) is None
            assert mobile_api.current_turn_options()["model"] == "a/expensive"
        finally:
            mobile_api._turn_options.reset(handle)
    asyncio.run(check())
