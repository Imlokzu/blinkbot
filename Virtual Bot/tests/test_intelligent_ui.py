"""Generated answers must survive chat replay without exposing another owner's files."""

import asyncio
import hashlib
import json

import pytest
from fastapi.testclient import TestClient

import brain_context
import main
import tools_mcp
import workspace
from intelligent_ui_schema import SCHEMA
from tool_activity import ActivityLog, safe_result_payload
from tools import intelligent_ui, registry
from test_mobile_streaming import isolated_turn


@pytest.fixture
def isolated_workspace(tmp_path, monkeypatch):
    monkeypatch.setattr(workspace, "WORKSPACE_DIR", tmp_path / "workspace")
    monkeypatch.setattr(workspace, "USER_DATA_DIR", tmp_path / "users")
    return tmp_path


def generate(**overrides):
    args = {"title": "Bill splitter", "summary": "Divide a bill among friends.",
            "html": '<label>People <input id="people" type="number" value="2"></label><output id="total"></output>',
            "javascript": "total.textContent = 120 / Number(people.value);"}
    return asyncio.run(intelligent_ui.render_interactive_ui(**{**args, **overrides}))


def test_large_answer_source_survives_bounded_activity_and_transport_wrappers(isolated_workspace):
    javascript = "/*" + "x" * 12000 + "*/\n" + "document.querySelector('output').textContent = 60;"
    with workspace.set_session("ui-replay"):
        result = generate(javascript=javascript)
    receipt = result["ui"]["data"]
    assert receipt["path"].startswith("sessions/ui-replay/interactive/")
    source = workspace.read_file(receipt["path"])["content"]
    assert json.loads(source)["javascript"] == javascript
    assert hashlib.sha256(source.encode()).hexdigest() == receipt["sha256"]
    for wrapped in [result, {"result": result}, {"structuredContent": result},
                    {"content": [{"type": "text", "text": json.dumps({"result": result})}]}]:
        log = ActivityLog()
        log.record({"type": "tool_done", "tool": "tools__render_interactive_ui", "call_id": "saved-ui", "result": wrapped})
        step = log.finish()[0]
        assert step["status"] == "done"
        assert step["result"]["ui"] == result["ui"]
        assert len(json.dumps(step)) < 8000


def test_failed_wrappers_never_become_successful_artifacts(isolated_workspace):
    result = generate()
    for wrapped in [{"isError": True, "result": result}, {"structuredContent": {**result, "ok": False}},
                    {"result": {**result, "success": False}},
                    {"content": [{"type": "text", "text": json.dumps({"result": {**result, "ok": False}})}]}]:
        log = ActivityLog()
        log.record({"type": "tool_done", "tool": SCHEMA["name"], "call_id": "failed-ui", "result": wrapped})
        step = log.finish()[0]
        assert step["status"] == "failed"
        assert step["result"]["ok"] is False


@pytest.mark.parametrize("overrides", [
    {"title": ""}, {"summary": " "}, {"html": ""}, {"html": 5}, {"css": None},
    {"javascript": "x" * 64000}, {"height": True}, {"height": 179}, {"height": 721}, {"height": 360.5},
])
def test_invalid_artifact_is_rejected_before_writing(isolated_workspace, overrides):
    assert generate(**overrides)["code"] == "invalid_artifact"
    assert not list(isolated_workspace.rglob("interactive/*.json"))


def test_artifact_owner_and_session_are_preserved(isolated_workspace):
    with brain_context.set_clerk_user("owner-a"), workspace.set_session("conversation-a"):
        first = generate()
        second = generate()
        assert first["ui"]["id"] != second["ui"]["id"]
        path = first["ui"]["data"]["path"]
        assert workspace.read_file(path)["content"]
    with brain_context.set_clerk_user("owner-b"):
        with pytest.raises(FileNotFoundError):
            workspace.read_file(path)


def test_symlink_cannot_redirect_generated_files_outside_workspace(isolated_workspace):
    outside = isolated_workspace / "outside"
    outside.mkdir()
    with workspace.set_session("symlink"):
        (workspace.session_dir() / "interactive").symlink_to(outside, target_is_directory=True)
        assert generate()["code"] == "artifact_save_failed"
    assert not list(outside.iterdir())


def test_mcp_and_local_models_receive_the_same_contract(isolated_workspace):
    remote = next(tool for tool in tools_mcp.TOOLS if tool["name"] == SCHEMA["name"])
    local = next(tool["function"] for tool in registry.list_tools() if tool["function"]["name"] == SCHEMA["name"])
    assert remote["inputSchema"] == local["parameters"] == SCHEMA["inputSchema"]
    assert SCHEMA["name"] in registry.tool_names()


def test_real_tool_and_workspace_routes_deliver_reopenable_source(isolated_workspace):
    client = TestClient(main.app)
    response = client.post("/api/tools/call", json={"name": SCHEMA["name"], "session_id": "api-artifact",
        "args": {"title": "Working calculator", "summary": "Change the value to explore.", "html": "<output>42</output>"}})
    assert response.status_code == 200
    result = response.json()["result"]
    data = result["ui"]["data"]
    fetched = client.get("/api/workspace/file", params={"path": data["path"], "session_id": "api-artifact"})
    assert fetched.status_code == 200
    assert json.loads(fetched.json()["content"])["html"] == "<output>42</output>"
    assert client.get("/api/workspace/file", params={"path": "../outside", "session_id": "api-artifact"}).status_code == 400
    assert safe_result_payload(response.json())["ui"] == result["ui"]


def test_stream_and_saved_conversation_keep_the_same_artifact(isolated_workspace, isolated_turn, monkeypatch):
    async def fake_brain(message, history, emit=None, **kwargs):
        args = {"title": "A working answer", "summary": "Adjust the controls.", "html": "<output>42</output>"}
        await emit({"type": "tool_start", "tool": SCHEMA["name"], "call_id": "artifact-call", "input": args})
        result = await registry.execute_tool(SCHEMA["name"], args)
        await emit({"type": "tool_done", "tool": SCHEMA["name"], "call_id": "artifact-call", "result": result})
        return "Adjust the interactive answer above.", "idle", "demo", []

    monkeypatch.setattr(main.brains, "chat", fake_brain)
    client = TestClient(main.app)
    streamed = client.post("/api/chat", json={"message": "Build a calculator", "stream": True, "session_id": "saved-artifact"})
    assert streamed.status_code == 200
    assert '"kind": "intelligent_ui"' in streamed.text
    saved = client.get("/api/sessions/saved-artifact").json()
    assistant = next(message for message in saved["messages"] if message["role"] == "assistant")
    step = next(step for step in assistant["steps"] if step["id"] == "artifact-call")
    assert step["status"] == "done"
    assert step["result"]["ui"]["kind"] == "intelligent_ui"
    path = step["result"]["ui"]["data"]["path"]
    assert client.get("/api/workspace/file", params={"path": path}).status_code == 200
