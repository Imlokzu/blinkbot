"""Exercise the real mobile SSE path over TCP without external providers."""

import asyncio
from contextlib import asynccontextmanager
import json
import socket

from fastapi import FastAPI, Request
from fastapi.responses import StreamingResponse
import httpx
import pytest
import uvicorn

import brain_context
import brains
import chat_store
import main
import mobile_api
import mobile_bridge
from mobile_store import MobileStore


@asynccontextmanager
async def local_server(app):
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, access_log=False, log_level="error"))
    task = asyncio.create_task(server.serve(sockets=[listener]))
    try:
        async def ready():
            while not server.started:
                if task.done():
                    await task
                    raise AssertionError("Test server exited before startup")
                await asyncio.sleep(0.01)
        await asyncio.wait_for(ready(), 5)
        yield f"http://127.0.0.1:{port}"
    finally:
        server.should_exit = True
        try:
            await asyncio.wait_for(task, 5)
        finally:
            listener.close()


async def frames(response):
    event, data, sequence = "message", [], None
    async for line in response.aiter_lines():
        if not line:
            if data:
                yield sequence, event, json.loads("\n".join(data))
            event, data = "message", []
        elif line.startswith("event: "):
            event = line[7:]
        elif line.startswith("data: "):
            data.append(line[6:])
        elif line.startswith("id: "):
            sequence = int(line[4:])


@pytest.fixture
def isolated_turn(tmp_path, monkeypatch):
    # Importing the real host must not start its integrations or touch history.
    monkeypatch.setattr(chat_store, "CHATS_DIR", tmp_path / "chats")
    monkeypatch.setattr(chat_store, "USER_DATA_DIR", tmp_path / "users")
    monkeypatch.setattr(brain_context, "USER_DATA_DIR", tmp_path / "users")
    monkeypatch.setattr(brain_context, "BRAIN_RUNTIME_DIR", tmp_path / "users")
    monkeypatch.setattr(main, "_sessions", {})
    monkeypatch.setattr(main, "_extract_and_save_facts", lambda *a: None)
    monkeypatch.setattr(main.vision_watcher, "note_interaction", lambda: None)
    monkeypatch.setattr(main.display_bridge, "send_chat_exchange_bg", lambda *a, **k: None)
    monkeypatch.setattr(main.memory, "append_chat_log", lambda *a, **k: None)

    async def no_title(*args):
        return None
    monkeypatch.setattr(main, "_autoname_chat", no_title)
    return MobileStore(tmp_path / "mobile.sqlite3")


@pytest.mark.parametrize("first", ["First chunk 🌊 ", "A large real provider chunk. " * 20])
def test_provider_chunk_arrives_over_mobile_socket_before_provider_finishes(isolated_turn, monkeypatch, first):
    """A gate prevents completion until a remote reader receives the first chunk.

    TestClient collects complete bodies, so it cannot detect a buffering bug.
    Both provider and mobile reader here use real loopback HTTP connections.
    """
    async def check():
        release_final, provider_finished = asyncio.Event(), asyncio.Event()
        provider_calls = []
        store = isolated_turn
        app = FastAPI()
        provider_origin = ""

        @app.post("/fake-provider/v1/chat/completions")
        async def provider(request: Request):
            payload = await request.json()
            assert payload["stream"] is True
            provider_calls.append(payload)

            async def output():
                await asyncio.sleep(0.05)
                yield "data: " + json.dumps({"choices": [{"delta": {"content": first}}]}) + "\n\n"
                await release_final.wait()
                yield 'data: {"choices":[{"delta":{"content":"Finished."}}]}\n\n'
                yield "data: [DONE]\n\n"
                provider_finished.set()
            return StreamingResponse(output(), media_type="text/event-stream")

        async def fake_brain(message, history, emit=None, **kwargs):
            # Preambles are cumulative snapshots, not reply deltas.
            await emit({"type": "note", "id": "preamble", "text": "Checking", "done": False})
            await emit({"type": "note", "id": "preamble", "text": "Checking provider", "done": True})
            text = await brains._stream_openai_compatible(
                provider_origin + "/fake-provider/v1/chat/completions", {},
                {"messages": [{"role": "user", "content": message}]}, 5, False, emit=emit,
            )
            return text, "idle", "test", []

        async def owner(request):
            return ""

        monkeypatch.setattr(brains, "chat", fake_brain)
        routes = mobile_api.router(owner, owner, mobile_bridge.runner(main.ChatRequest, main.chat_turn),
                                   store=store, scan_interval=0.01)
        app.include_router(routes)
        observed = []
        async with local_server(app) as origin:
            provider_origin = origin
            async with httpx.AsyncClient(base_url=origin, timeout=5, trust_env=False) as client:
                response = await client.post("/api/mobile/messages", json={
                    "client_id": "socket-stream", "session_id": "socket-stream",
                    "message": "An isolated test message",
                })
                assert response.status_code == 200
                job = response.json()
                try:
                    async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                        assert stream.status_code == 200
                        assert stream.headers["content-type"].startswith("text/event-stream")
                        assert stream.headers["cache-control"] == "no-store, no-transform"
                        assert stream.headers["x-accel-buffering"] == "no"
                        assert "content-length" not in stream.headers
                        assert "content-encoding" not in stream.headers
                        async for sequence, event, data in frames(stream):
                            observed.append((sequence, event, data))
                            if event == "delta" and not release_final.is_set():
                                assert data["chunk"] == first
                                assert not provider_finished.is_set()
                                assert store.get("", job["id"])["state"] == "running"
                                assert not any(name == "done" for _, name, _ in observed)
                                release_final.set()
                finally:
                    release_final.set()
        assert provider_finished.is_set()
        assert len(provider_calls) == 1
        assert [seq for seq, _, _ in observed] == list(range(1, len(observed) + 1))
        assert "".join(data["chunk"] for _, event, data in observed if event == "delta") == first + "Finished."
        notes = [data for _, event, data in observed if event == "note"]
        assert notes == [{"type": "note", "id": "preamble", "bubbles": ["Checking"]},
                         {"type": "note", "id": "preamble", "bubbles": ["Checking provider"]}]
        done = next(data for _, event, data in observed if event == "done")
        assert done["reply"] == first + "Finished."
        assert done["parts"][0] == {"type": "text", "text": "Checking provider", "note": True}
        assert observed[-1][1:] == ("mobile_state", {"id": job["id"], "state": "completed"})
        assert not routes.runtime.tasks
    asyncio.run(check())


def test_completed_mobile_reply_is_not_retyped_as_simulated_deltas(isolated_turn, monkeypatch):
    async def check():
        async def fake_brain(*args, **kwargs):
            return "A completed response without provider deltas.", "idle", "test", []
        monkeypatch.setattr(brains, "chat", fake_brain)
        handle = mobile_api._turn_options.set({"user_id": "", "session_id": "plain-completion"})
        try:
            response = await main.chat_turn(main.ChatRequest(message="Isolated request", stream=True,
                                                            session_id="plain-completion"), "")
            observed = [event async for event in mobile_api.iter_chat_events(response)]
        finally:
            mobile_api._turn_options.reset(handle)
        assert not any(event == "delta" for event, _ in observed)
        assert next(data["reply"] for event, data in observed if event == "done") == "A completed response without provider deltas."
    asyncio.run(check())

