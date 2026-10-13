"""Maps use native bounded receipts, not image-search screenshots or arbitrary URLs."""

import asyncio
import json

import pytest
from fastapi.testclient import TestClient

import brains
import main
import mobile_api
import tools_mcp
from map_schema import CHAT_GUIDANCE, SCHEMA
from tool_activity import safe_result_payload
from tools import map_tools, registry
from test_mobile_streaming import isolated_turn


def show(**overrides):
    return asyncio.run(map_tools.show_map(**{"title": "Kharkiv", "latitude": 49.9935, "longitude": 36.2304, "zoom": 6, **overrides}))


def test_map_receipt_is_replayable_through_native_mcp_wrappers():
    result = show()
    assert result["ui"]["data"] == {"title": "Kharkiv", "latitude": 49.9935, "longitude": 36.2304, "zoom": 6}
    assert result["ui"]["kind"] == "map"
    wrapped = {"content": [{"type": "text", "text": json.dumps(result)}]}
    assert safe_result_payload(wrapped) == result
    assert len(json.dumps(result)) < 8000


@pytest.mark.parametrize("overrides", [
    {"title": ""}, {"title": "x" * 121}, {"latitude": True}, {"latitude": 86},
    {"latitude": float("nan")}, {"longitude": float("inf")}, {"longitude": "36.2"},
    {"longitude": 181}, {"latitude": 10**1000}, {"zoom": False}, {"zoom": 1}, {"zoom": 19}, {"zoom": 5.5},
])
def test_invalid_map_requests_do_not_report_success(overrides):
    assert show(**overrides)["code"] == "invalid_map"


def test_local_and_mcp_agents_have_the_same_map_contract():
    local = next(tool["function"] for tool in registry.list_tools() if tool["function"]["name"] == SCHEMA["name"])
    remote = next(tool for tool in tools_mcp.TOOLS if tool["name"] == SCHEMA["name"])
    assert local["parameters"] == remote["inputSchema"] == SCHEMA["inputSchema"]
    assert asyncio.run(registry.execute_tool("show_map", {"title": "Kyiv", "latitude": 50.4501, "longitude": 30.5234}))["ok"]


def test_visual_routing_is_sent_only_to_supported_desktop_chat(monkeypatch, tmp_path):
    monkeypatch.setattr(brains.profile_store, "load", lambda: {})
    monkeypatch.setattr(brains.brain_context, "init_user_brain", lambda *a: tmp_path)
    monkeypatch.setattr(brains, "load_user_profile", lambda *a: "")
    monkeypatch.setattr(brains, "find_relevant_notes", lambda *a, **k: [])
    monkeypatch.setattr(mobile_api, "current_turn_options", lambda: None)
    assert dict(brains.system_prompt_parts("Show a city on a map", channel="chat"))["channel"].endswith(CHAT_GUIDANCE)
    for channel in ["screen", "telegram", "discord", None]:
        assert CHAT_GUIDANCE not in brains.build_system_prompt("Show a city on a map", channel=channel)
    monkeypatch.setattr(mobile_api, "current_turn_options", lambda: {})
    assert CHAT_GUIDANCE not in brains.build_system_prompt("Show a city on a map", channel="chat")


def test_map_survives_streamed_reply_and_saved_history(isolated_turn, monkeypatch):
    async def fake_brain(message, history, emit=None, **kwargs):
        await emit({"type": "tool_start", "tool": "tools__show_map", "call_id": "map-call"})
        result = await map_tools.show_map("Kharkiv", 49.9935, 36.2304, 6)
        await emit({"type": "tool_done", "tool": "tools__show_map", "call_id": "map-call", "result": {"content": [{"type": "text", "text": json.dumps(result)}]}})
        return "Kharkiv is in northeastern Ukraine.", "idle", "demo", []
    monkeypatch.setattr(main.brains, "chat", fake_brain)
    client = TestClient(main.app)
    response = client.post("/api/chat", json={"message": "Show Kharkiv on a map", "session_id": "map-replay", "stream": True})
    assert response.status_code == 200
    assert '"kind": "map"' in response.text
    messages = client.get("/api/sessions/map-replay").json()["messages"]
    step = next(message for message in messages if message["role"] == "assistant")["steps"][0]
    assert step["status"] == "done"
    assert step["result"]["ui"]["data"]["latitude"] == 49.9935
