"""Real local HTTP smoke; never use a provider or the operator's workspace."""
import asyncio
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles
import httpx

import main
import workspace
from test_mobile_streaming import isolated_turn, local_server


def test_calculation_api_and_static_workspace_smoke(isolated_turn, monkeypatch, tmp_path):
    async def owner(request):
        return ""
    monkeypatch.setattr(main, "_require_user", owner)
    monkeypatch.setattr(workspace, "WORKSPACE_DIR", tmp_path / "workspace")
    app = FastAPI()
    app.add_api_route("/api/tools/call", main.api_tools_call, methods=["POST"])
    app.add_api_route("/api/workspace/file", main.api_workspace_file, methods=["GET"])
    app.mount("/static", StaticFiles(directory=Path(main.__file__).parent / "static"))

    async def check():
        async with local_server(app) as origin:
            async with httpx.AsyncClient(base_url=origin, timeout=10, trust_env=False) as client:
                reply = await client.post("/api/tools/call", json={"name": "python_calculate", "args": {
                    "code": "import math\nprint(math.sqrt(144))\nsum(i*i for i in range(10))"}, "session_id": "calculation-smoke"})
                assert reply.status_code == 200
                assert reply.json()["result"] == {"ok": True, "stdout": "12.0\n", "result": "285"}
                assert (await client.get("/api/workspace/file", params={"path": "../outside.md"})).status_code == 400
                assert (await client.get("/static/screen/i18n.js")).status_code == 200
    asyncio.run(check())
