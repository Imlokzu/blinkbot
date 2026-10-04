"""Real phone transport contracts, using only temporary data and loopback providers."""

import asyncio
import base64
import json
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, WebSocket
from fastapi.responses import StreamingResponse
from fastapi.testclient import TestClient
import httpx
import pytest

import brain_context
import brains
import chat_store
import main
from main import _autoname_chat as original_autoname_chat
import mobile_api
import mobile_bridge
import mobile_routing
from mobile_store import MobileStore
from test_mobile_streaming import frames, isolated_turn, local_server


# A complete 1x1 PNG, not merely a signature accepted by the upload guard.
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNIqegBAAKsAWn9Yi+6AAAAAElFTkSuQmCC"
)
JPEG = base64.b64decode(
    "/9j/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF"
    "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RF"
    "RkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLD"
    "xMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL"
    "/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRom"
    "JygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmq"
    "srO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9sAQwACAgICAgIDAgIDBQMDAwUGBQUF"
    "BQYIBgYGBgYICggICAgICAoKCgoKCgoKDAwMDAwMDg4ODg4PDw8PDw8PDw8P/9sAQwECAgIEBAQHBAQHEAsJCxAQ"
    "EBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ/90ABAAB/9oADAMBAAIRAxEAPwDj6KKK+0Pjz//Z"
)


@pytest.fixture
def phone_transport(isolated_turn, tmp_path, monkeypatch):
    store = isolated_turn
    monkeypatch.setattr(mobile_api, "_default_store", store)
    monkeypatch.setattr(main.cfg, "UPLOADS_DIR", tmp_path / "uploads")
    monkeypatch.setenv("MOBILE_API_ORIGIN", "https://phone.fixture.example")
    monkeypatch.setattr(brains.cfg, "get_openclaw_token", lambda: "synthetic-provider-token")
    monkeypatch.setattr(mobile_routing, "model_override", lambda: "fixture/vision")
    monkeypatch.setattr(brains.openclaw_config, "image_model", lambda: "fixture/vision")
    user = "fixture-owner"
    pairing = store.create_pairing(user)
    token = store.exchange(pairing["code"], "Synthetic phone", "android")["token"]
    app = FastAPI()
    # Reuse the real route functions without starting main's integrations.
    app.router.routes.extend(route for route in main.app.routes if getattr(route, "path", "") in {
        "/api/chat/upload", "/uploads/{file_path:path}", "/api/sessions/{session_id}",
        "/api/sessions/{session_id}/reactions", "/api/sessions/{session_id}/rename",
        "/api/brain/models",
    })
    routes = mobile_api.router(main._require_user, main._require_openclaw_operator,
                              mobile_bridge.runner(main.ChatRequest, main.chat_turn),
                              store=store, scan_interval=0.01)
    app.include_router(routes)
    app.middleware("http")(main.mobile_host_access)
    app.middleware("http")(main.rewrite_share_host)
    return app, store, user, {"Authorization": f"Bearer {token}", "Host": "phone.fixture.example"}


@asynccontextmanager
async def fake_gateway(app, monkeypatch, *, snapshots=(), release=None, via_routing=False):
    """Use the real WS handshake/subscription and the real HTTP brain reader."""
    subscribed = asyncio.Event()
    captured = []
    final_seen = asyncio.Event()
    closed = asyncio.Event()
    finished = asyncio.Event()
    session = {"key": ""}

    @app.websocket("/fixture-gateway")
    async def websocket(ws: WebSocket):
        await ws.accept()
        try:
            await ws.send_json({"type": "event", "event": "connect.challenge", "payload": {"nonce": "fixture"}})
            for expected in ("connect", "sessions.subscribe", "sessions.messages.subscribe"):
                req = await ws.receive_json()
                assert req["method"] == expected
                if expected == "connect":
                    assert req["params"]["auth"] == {"token": "synthetic-provider-token"}
                payload = {}
                if expected == "sessions.messages.subscribe":
                    session["key"] = req["params"]["key"]
                    payload = {"subscribed": True, "key": session["key"]}
                await ws.send_json({"type": "res", "id": req["id"], "ok": True, "payload": payload})
            subscribed.set()
            await final_seen.wait()
            for sequence, data in enumerate(snapshots, start=1):
                await ws.send_json({"type": "event", "event": "agent", "payload": {
                    "sessionKey": session["key"], "runId": "fixture-run", "seq": sequence,
                    "stream": "assistant", "data": data,
                }})
                await asyncio.sleep(0.02)
            if release is not None:
                await release.wait()
            await ws.send_json({"type": "event", "event": "agent", "payload": {
                "sessionKey": session["key"], "runId": "fixture-run", "seq": len(snapshots) + 1,
                "stream": "lifecycle", "data": {"phase": "end"},
            }})
            await ws.receive()
        finally:
            closed.set()

    @app.post("/fixture-gateway/v1/chat/completions")
    async def provider(request: Request):
        await subscribed.wait()
        assert request.headers["authorization"] == "Bearer synthetic-provider-token"
        assert request.headers["x-openclaw-model"] == "fixture/vision"
        assert request.headers["x-openclaw-session-key"] == session["key"]
        payload = await request.json()
        assert payload["stream"] is True
        captured.append(payload)
        final_seen.set()

        async def output():
            if release is not None:
                await release.wait()
            text = snapshots[-1]["text"] + "Complete." if snapshots else "I received the image."
            yield "data: " + json.dumps({"choices": [{"delta": {"content": text}}]}) + "\n\n"
            yield "data: [DONE]\n\n"
            finished.set()
        return StreamingResponse(output(), media_type="text/event-stream")

    async def chat(message, history, emit=None, images=None, session_key=None, **kwargs):
        gateway = mobile_routing.chat_gateway if via_routing else brains.chat_openclaw
        text, tools, model = await gateway(message, "Synthetic system prompt", history,
                                          emit=emit, images=images, session_key=session_key)
        return text, "idle", "fixture", tools

    monkeypatch.setattr(brains, "chat", chat)
    async with local_server(app) as origin:
        monkeypatch.setattr(brains.cfg, "OPENCLAW_BASE_URL", origin + "/fixture-gateway")
        try:
            yield origin, captured, finished
        finally:
            if release is not None:
                release.set()


@pytest.mark.parametrize("message", ["Describe this image.", ""])
def test_authenticated_multipart_upload_then_mobile_send_keeps_image_bytes(phone_transport, monkeypatch, message):
    """The phone sends the returned manifest, with numeric size and no inline image data."""
    async def check():
        app, store, owner, headers = phone_transport
        async with fake_gateway(app, monkeypatch) as (origin, captured, finished):
            async with httpx.AsyncClient(base_url=origin, headers=headers, timeout=5, trust_env=False) as client:
                uploaded = await client.post("/api/chat/upload", files={"file": ("phone.png", PNG, "image/png")})
                assert uploaded.status_code == 200, uploaded.text
                manifest = {key: uploaded.json()[key] for key in ("url", "name", "type", "size")}
                assert manifest["size"] == len(PNG)
                assert manifest["type"] == "image/png"
                assert (await client.get(manifest["url"])).content == PNG
                submitted = await client.post("/api/mobile/messages", json={
                    "client_id": "phone-upload", "session_id": "phone-upload", "message": message,
                    "attachments": [manifest],
                })
                assert submitted.status_code == 200, submitted.text
                job = submitted.json()
                async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                    observed = [(event, data) async for _, event, data in frames(stream)]
                assert store.get(owner, job["id"])["state"] == "completed", observed
        assert finished.is_set()
        assert len(captured) == 1
        content = captured[0]["messages"][-1]["content"]
        assert content[0]["type"] == "text"
        assert '"name": "phone.png"' in content[0]["text"]
        image = content[1]
        assert image["type"] == "image_url"
        prefix, encoded = image["image_url"]["url"].split(",", 1)
        assert prefix == "data:image/png;base64"
        assert base64.b64decode(encoded) == PNG
        with brain_context.set_clerk_user(owner):
            saved = chat_store.load("phone-upload")["messages"]
        assert saved[0]["content"] == message
        assert saved[0]["attachments"][0] == manifest
    asyncio.run(check())


def image_catalog(monkeypatch):
    async def catalog():
        return [{"id": "text/default", "provider": "text", "is_default": True, "available": True},
                {"id": "fixture/text", "provider": "fixture", "available": True, "vision": False},
                {"id": "fixture/vision", "provider": "fixture", "available": True, "vision": True}]

    async def session_patch(method, params):
        assert method == "sessions.patch"
        if "model" in params:
            assert isinstance(params["model"], str) and params["model"]
        assert params["thinkingLevel"] is None
        return {}

    monkeypatch.setattr(mobile_routing, "model_override", lambda: mobile_routing._model.get())
    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", catalog)
    monkeypatch.setattr(brains.openclaw_config, "image_model", lambda: "fixture/vision")
    monkeypatch.setattr(mobile_routing.openclaw_control, "_rpc", session_patch)


@pytest.mark.parametrize("selection", ["", "auto", "jev"])
def test_automatic_mobile_image_uses_configured_image_model_not_text_default(phone_transport, monkeypatch, selection):
    async def check():
        app, store, owner, headers = phone_transport
        image_catalog(monkeypatch)
        async with fake_gateway(app, monkeypatch, via_routing=True) as (origin, captured, finished):
            async with httpx.AsyncClient(base_url=origin, headers=headers, timeout=5, trust_env=False) as client:
                uploaded = await client.post("/api/chat/upload", files={"file": ("phone.png", PNG, "image/png")})
                assert uploaded.status_code == 200
                submitted = await client.post("/api/mobile/messages", json={
                    "client_id": "image-default", "session_id": "image-default", "message": "Describe the attached image.",
                    "attachments": [uploaded.json()], "model": selection,
                })
                assert submitted.status_code == 200
                job = submitted.json()
                async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                    observed = [(event, data) async for _, event, data in frames(stream)]
        assert len(captured) == 1
        assert finished.is_set()
        assert store.get(owner, job["id"])["state"] == "completed", observed
        assert next(data["model"] for event, data in observed if event == "done") == "fixture/vision"
        image_url = captured[0]["messages"][-1]["content"][1]["image_url"]["url"]
        assert base64.b64decode(image_url.split(",", 1)[1]) == PNG
    asyncio.run(check())


@pytest.mark.parametrize("selection", ["fixture/vision"])
def test_explicit_mobile_image_model_is_sent_to_the_shared_web_route(phone_transport, monkeypatch, selection):
    async def check():
        app, store, owner, headers = phone_transport
        image_catalog(monkeypatch)
        async with fake_gateway(app, monkeypatch, via_routing=True) as (origin, captured, finished):
            async with httpx.AsyncClient(base_url=origin, headers=headers, timeout=5, trust_env=False) as client:
                uploaded = await client.post("/api/chat/upload", files={"file": ("phone.png", PNG, "image/png")})
                assert uploaded.status_code == 200
                submitted = await client.post("/api/mobile/messages", json={
                    "client_id": "image-unsupported", "session_id": "image-unsupported", "message": "Describe the attached image.",
                    "attachments": [uploaded.json()], "model": selection,
                })
                assert submitted.status_code == 200
                job = submitted.json()
                async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                    observed = [(event, data) async for _, event, data in frames(stream)]
                listed = (await client.get("/api/mobile/messages", params={"session_id": job["session_id"]})).json()["messages"]
        assert len(captured) == 1
        assert finished.is_set()
        assert listed[0]["state"] == "completed"
        assert store.get(owner, job["id"])["state"] == "completed"
        assert not any(event == "error" for event, _ in observed)
        assert next(data["model"] for event, data in observed if event == "done") == "fixture/vision"
        image_url = captured[0]["messages"][-1]["content"][1]["image_url"]["url"]
        assert base64.b64decode(image_url.split(",", 1)[1]) == PNG
    asyncio.run(check())


def test_image_fallback_belongs_to_the_shared_gateway_not_the_phone_catalog(monkeypatch):
    async def check():
        attempts, patches, events = [], [], []

        async def catalog():
            raise AssertionError("Images must not be gated by the phone catalog")

        async def rpc(method, params):
            patches.append(params)
            return {}

        async def gateway(*args, **kwargs):
            attempts.append(mobile_routing.model_override())
            return "A real gateway answer", [], "other/vision"

        async def emit(event):
            events.append(event)

        monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", catalog)
        monkeypatch.setattr(mobile_routing.openclaw_control, "_rpc", rpc)
        monkeypatch.setattr(brains, "chat_openclaw", gateway)
        context = mobile_api._turn_options.set({"model": "openai/sol", "reasoning_effort": "high"})
        try:
            result = await mobile_routing.chat_gateway("Describe.", "", [], emit=emit,
                images=[{"mime": "image/png", "data": "fixture"}], session_key="synthetic-session")
        finally:
            mobile_api._turn_options.reset(context)
        assert attempts == ["openai/sol"]
        assert patches == [{"key": "synthetic-session", "thinkingLevel": "high", "model": "openai/sol"}]
        assert result[2] == "other/vision"
        assert events[-1] == {"type": "model", "provider": "other", "model": "vision"}
        assert mobile_routing.model_override() is None
    asyncio.run(check())


@pytest.mark.parametrize("kind", ["chat", "code"])
def test_phone_rename_reaction_remove_and_delete_follow_existing_owner_contract(phone_transport, kind):
    app, store, owner, headers = phone_transport
    with brain_context.set_clerk_user(owner), chat_store.set_kind(kind):
        ids = chat_store.append("saved-chat", "A question", "The answer", parts=[
            {"type": "text", "text": "Checking", "note": True},
            {"type": "steps", "ids": ["step-1"]}, {"type": "text", "text": "The answer"},
        ], reaction="👍")
    with TestClient(app, headers=headers) as client:
        renamed = client.post("/api/sessions/saved-chat/rename", params={"kind": kind}, json={"title": "  My   conversation  "})
        assert renamed.status_code == 200
        assert renamed.json() == {"ok": True, "title": "My conversation"}
        for title in ("", "  \n  ", "x" * (chat_store.TITLE_LIMIT + 1)):
            assert client.post("/api/sessions/saved-chat/rename", params={"kind": kind}, json={"title": title}).status_code == 422
        assert client.post("/api/sessions/unknown/rename", params={"kind": kind}, json={"title": "Unknown"}).status_code == 404
        added = client.post("/api/sessions/saved-chat/reactions", params={"kind": kind}, json={
            "message_id": ids[1], "bubble": 1, "emoji": "❤️",
        })
        assert added.status_code == 200
        assert added.json() == {"ok": True, "reactions": {"1": "❤️"}}
        history = client.get("/api/sessions/saved-chat", params={"kind": kind}).json()
        assert history["title"] == "My conversation"
        assert history["messages"][0]["reaction"] == "👍"
        assert history["messages"][-1]["reactions"] == {"1": "❤️"}
        removed = client.post("/api/sessions/saved-chat/reactions", params={"kind": kind}, json={
            "message_id": ids[1], "bubble": 1, "emoji": None,
        })
        assert removed.json() == {"ok": True, "reactions": {}}
        assert client.delete("/api/sessions/saved-chat", params={"kind": kind}).json() == {"ok": True}
    with brain_context.set_clerk_user(owner), chat_store.set_kind(kind):
        assert chat_store.load("saved-chat")["messages"] == []


def test_rename_does_not_claim_success_when_existing_storage_write_fails(phone_transport, monkeypatch):
    app, store, owner, headers = phone_transport
    with brain_context.set_clerk_user(owner):
        chat_store.append("saved-chat", "A question", "An answer")
    monkeypatch.setattr(chat_store, "set_title", lambda *args: None)
    with TestClient(app, headers=headers) as client:
        response = client.post("/api/sessions/saved-chat/rename", json={"title": "A replacement"})
        assert response.status_code == 500
        assert response.json() == {"detail": {"code": "session_rename_failed"}}


def test_phone_catalog_discloses_configured_image_model_without_inventing_capabilities(phone_transport, monkeypatch):
    app, store, owner, headers = phone_transport
    image_catalog(monkeypatch)
    monkeypatch.setattr(main.openclaw_models, "reachable", lambda: True)
    monkeypatch.setattr(main.openclaw_models, "get_selected", lambda: "")

    async def catalog(force=False):
        return [{"id": "text/default", "provider": "text", "is_default": True},
                {"id": "fixture/vision", "provider": "fixture", "vision": True}]

    async def thinking():
        return "off"

    monkeypatch.setattr(main.openclaw_models, "catalog", catalog)
    monkeypatch.setattr(main.openclaw_models, "get_thinking", thinking)
    with TestClient(app, headers=headers) as client:
        response = client.get("/api/brain/models")
    assert response.status_code == 200
    assert response.json()["image_model"] == "fixture/vision"
    assert response.json()["default"] == "text/default"
    default = next(model for model in response.json()["models"] if model["id"] == "text/default")
    assert "vision" not in default


@pytest.mark.parametrize("size", [8 * 1024 * 1024, 10 * 1024 * 1024, 10 * 1024 * 1024 + 1, 20 * 1024 * 1024])
def test_large_phone_jpeg_has_explicit_upload_limit_and_accepted_bytes_reach_provider(phone_transport, monkeypatch, size):
    """A valid small JPEG with trailing padding exercises the wire's actual photo sizes."""
    photo = JPEG + b"\0" * (size - len(JPEG))

    async def check():
        app, store, owner, headers = phone_transport
        async with fake_gateway(app, monkeypatch) as (origin, captured, finished):
            async with httpx.AsyncClient(base_url=origin, headers=headers, timeout=10, trust_env=False) as client:
                response = await client.post("/api/chat/upload", files={"file": ("phone.jpg", photo, "image/jpeg")})
                if size > main._VISION_MAX_BYTES:
                    assert response.status_code == 413
                    assert response.json() == {"detail": "image_too_large"}
                    assert captured == []
                    assert list(main.cfg.UPLOADS_DIR.iterdir()) == []
                    return
                assert response.status_code == 200, response.text
                manifest = response.json()
                assert manifest["size"] == size
                assert manifest["type"] == "image/jpeg"
                submitted = await client.post("/api/mobile/messages", json={
                    "client_id": "large-photo", "session_id": "large-photo", "message": "Describe the photo.",
                    "attachments": [manifest],
                })
                assert submitted.status_code == 200
                job = submitted.json()
                async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                    observed = [(event, data) async for _, event, data in frames(stream)]
                assert store.get(owner, job["id"])["state"] == "completed", observed
        assert len(captured) == 1
        url = captured[0]["messages"][-1]["content"][1]["image_url"]["url"]
        assert url.startswith("data:image/jpeg;base64,")
        assert base64.b64decode(url.split(",", 1)[1]) == photo
    asyncio.run(check())


@pytest.mark.parametrize("replaceable", [None, True])
def test_gateway_assistant_snapshot_arrives_on_phone_before_http_completion(phone_transport, monkeypatch, replaceable):
    """Native assistant frames can omit replaceable; withholding HTTP detects silent drops."""
    async def check():
        app, store, owner, headers = phone_transport
        release = asyncio.Event()
        snapshots = [{"itemId": "answer", "text": text, "delta": text, "replace": True}
                     for text in ("Early answer 🌊 ", "Corrected answer ")]
        if replaceable is not None:
            snapshots = [{**snapshot, "replaceable": replaceable} for snapshot in snapshots]
        received = []
        async with fake_gateway(app, monkeypatch, snapshots=snapshots, release=release) as (origin, captured, finished):
            async with httpx.AsyncClient(base_url=origin, headers=headers, timeout=2, trust_env=False) as client:
                submitted = await client.post("/api/mobile/messages", json={
                    "client_id": "stream-contract", "session_id": "stream-contract", "message": "A synthetic question",
                })
                assert submitted.status_code == 200
                job = submitted.json()
                try:
                    async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                        async with asyncio.timeout(2):
                            async for _, event, data in frames(stream):
                                received.append((event, data))
                                if event == "reply_snapshot" and data["text"] == "Corrected answer ":
                                    assert not finished.is_set()
                                    assert store.get(owner, job["id"])["state"] == "running"
                                    release.set()
                finally:
                    release.set()
        assert len(captured) == 1
        assert [data["text"] for event, data in received if event == "reply_snapshot"] == [
            "Early answer 🌊 ", "Corrected answer ",
        ]
        assert [data["chunk"] for event, data in received if event == "delta"] == ["Complete."]
        assert next(data["reply"] for event, data in received if event == "done") == "Corrected answer Complete."
        assert store.get(owner, job["id"])["state"] == "completed"
    asyncio.run(check())


def test_http_delta_before_gateway_snapshot_does_not_lose_visible_prefix(phone_transport, monkeypatch):
    """Ordinary native snapshots and HTTP deltas can arrive in either order."""
    from openclaw_activity import GatewayActivity

    async def check():
        app, store, owner, headers = phone_transport

        async def chat(message, history, emit=None, session_key=None, **kwargs):
            observer = GatewayActivity(emit, session_key=session_key, preview_assistant=True)
            await emit({"type": "delta", "chunk": "First "})
            await observer.handle({"type": "event", "event": "agent", "payload": {
                "sessionKey": session_key, "runId": "ordered", "seq": 1, "stream": "assistant",
                "data": {"itemId": "answer", "text": "First ", "delta": "First "},
            }})
            await emit({"type": "delta", "chunk": "second "})
            await observer.handle({"type": "event", "event": "agent", "payload": {
                "sessionKey": session_key, "runId": "ordered", "seq": 2, "stream": "assistant",
                "data": {"itemId": "answer", "text": "First second ", "delta": "second "},
            }})
            await emit({"type": "delta", "chunk": "finished."})
            return "First second finished.", "idle", "fixture", []

        monkeypatch.setattr(brains, "chat", chat)
        async with local_server(app) as origin:
            async with httpx.AsyncClient(base_url=origin, headers=headers, timeout=5, trust_env=False) as client:
                response = await client.post("/api/mobile/messages", json={
                    "client_id": "stream-order", "session_id": "stream-order", "message": "A synthetic question",
                })
                job = response.json()
                async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                    observed = [(event, data) async for _, event, data in frames(stream)]
        snapshots = [data["text"] for event, data in observed if event == "reply_snapshot"]
        assert snapshots == ["First ", "First second "]
        assert [data["chunk"] for event, data in observed if event == "delta"] == ["First ", "second ", "finished."]
        assert next(data["reply"] for event, data in observed if event == "done") == "First second finished."
    asyncio.run(check())


@pytest.mark.parametrize("title", ["My manual name", "A question"])
def test_manual_phone_rename_survives_an_inflight_automatic_title(phone_transport, monkeypatch, title):
    """Renaming to the existing title must also fence the pending provider result."""
    async def check():
        app, store, owner, headers = phone_transport
        started, finish = asyncio.Event(), asyncio.Event()

        async def generated_title(*args, **kwargs):
            started.set()
            await finish.wait()
            return "An automatic title", "idle", "fixture", []

        monkeypatch.setattr(brains, "chat", generated_title)
        with brain_context.set_clerk_user(owner):
            chat_store.append("rename-pending", "A question", "An answer")
            assert chat_store.needs_title("rename-pending")
            async with local_server(app) as origin:
                task = asyncio.create_task(original_autoname_chat("rename-pending", "A question", "An answer"))
                await asyncio.wait_for(started.wait(), 2)
                try:
                    async with httpx.AsyncClient(base_url=origin, headers=headers, timeout=5, trust_env=False) as client:
                        response = await client.post("/api/sessions/rename-pending/rename", json={"title": title})
                        assert response.status_code == 200, response.text
                        assert response.json() == {"ok": True, "title": title}
                    assert not chat_store.needs_title("rename-pending")
                finally:
                    finish.set()
                    await asyncio.wait_for(task, 2)
            assert chat_store.load("rename-pending")["title"] == title
    asyncio.run(check())


def test_shared_image_gateway_failures_are_service_failures_not_capability_errors(phone_transport, monkeypatch):
    async def check():
        app, store, owner, headers = phone_transport
        image_catalog(monkeypatch)
        attempts = []

        async def catalog():
            return [{"id": "fixture/vision", "provider": "fixture", "available": True, "vision": True},
                    {"id": "fixture/backup", "provider": "fixture", "available": True, "vision": True},
                    {"id": "other/vision", "provider": "other", "available": True, "vision": True}]

        async def patch_session(method, params):
            assert method == "sessions.patch"
            assert "model" not in params
            return {}

        async def fail(*args, **kwargs):
            attempts.append(mobile_routing.model_override())
            raise TimeoutError("Synthetic provider timeout")

        monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", catalog)
        monkeypatch.setattr(mobile_routing.openclaw_control, "_rpc", patch_session)
        monkeypatch.setattr(brains, "chat_openclaw", fail)
        async with fake_gateway(app, monkeypatch, via_routing=True) as (origin, captured, finished):
            async with httpx.AsyncClient(base_url=origin, headers=headers, timeout=5, trust_env=False) as client:
                uploaded = await client.post("/api/chat/upload", files={"file": ("phone.png", PNG, "image/png")})
                assert uploaded.status_code == 200
                submitted = await client.post("/api/mobile/messages", json={
                    "client_id": "image-transport-failure", "session_id": "image-transport-failure",
                    "message": "Describe the image.", "attachments": [uploaded.json()],
                })
                assert submitted.status_code == 200
                job = submitted.json()
                async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                    observed = [(event, data) async for _, event, data in frames(stream)]
        assert attempts == [None]
        assert captured == []
        assert next(data["error"] for event, data in observed if event == "error") == "mobile_turn_failed"
        assert store.get(owner, job["id"])["error"] == "turn_failed"
        assert "mobile_image_model_unavailable" not in json.dumps(observed)
        assert "Synthetic provider timeout" not in json.dumps(observed)
    asyncio.run(check())
