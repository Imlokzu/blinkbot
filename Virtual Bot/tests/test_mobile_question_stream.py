"""The mobile popup consumes real tool arguments before the turn completes."""

import asyncio
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
import httpx

import brains
import main
import mobile_api
import mobile_bridge
import workspace
from openclaw_activity import GatewayActivity
from test_mobile_streaming import frames, isolated_turn, local_server


def test_question_reaches_mobile_socket_before_provider_completion(isolated_turn, monkeypatch, tmp_path):
    async def check():
        release = asyncio.Event()
        args = {"question": "Which format?", "options": ["PDF", "Markdown"], "allow_custom": True}

        async def fake_brain(*unused, emit, **kwargs):
            observer = GatewayActivity(emit)
            await observer.handle({"type": "event", "event": "agent", "payload": {
                "sessionKey": observer.session_key, "runId": "question-run", "stream": "tool", "seq": 1,
                "data": {"phase": "start", "name": "tools__ask_question", "toolCallId": "ask", "args": args},
            }})
            await asyncio.wait_for(release.wait(), 5)
            return "Waiting for your choice.", "idle", "test", []

        async def owner(request):
            return ""

        monkeypatch.setattr(brains, "chat", fake_brain)
        monkeypatch.setattr(main, "_require_user", owner)
        monkeypatch.setattr(workspace, "WORKSPACE_DIR", tmp_path / "workspace")
        app = FastAPI()
        app.add_api_route("/api/workspace/file", main.api_workspace_file, methods=["GET"])
        app.mount("/static", StaticFiles(directory=Path(main.__file__).parent / "static"))
        routes = mobile_api.router(owner, owner, mobile_bridge.runner(main.ChatRequest, main.chat_turn),
                                   store=isolated_turn, scan_interval=0.01)
        app.include_router(routes)
        observed = []
        async with local_server(app) as origin:
            async with httpx.AsyncClient(base_url=origin, timeout=10, trust_env=False) as client:
                assert (await client.get("/api/workspace/file", params={"path": "../outside.md"})).status_code == 400
                assert (await client.get("/static/screen/i18n.js")).status_code == 200
                response = await client.post("/api/mobile/messages", json={
                    "client_id": "question-client", "session_id": "question-chat", "message": "Ask for a format",
                })
                assert response.status_code == 200
                job = response.json()
                try:
                    async with client.stream("GET", f"/api/mobile/messages/{job['id']}/events") as stream:
                        assert stream.status_code == 200
                        async for sequence, name, data in frames(stream):
                            observed.append(name)
                            if name == "tool_start":
                                assert data["step"]["input"] == args
                                assert data["step"]["label"] == "tools__ask_question"
                                assert data["step"]["id"] == "question-run:ask"
                                assert "done" not in observed
                                assert isolated_turn.get("", job["id"])["state"] == "running"
                                release.set()
                finally:
                    release.set()
        assert "tool_start" in observed and "done" in observed
        assert not routes.runtime.tasks

    asyncio.run(check())
