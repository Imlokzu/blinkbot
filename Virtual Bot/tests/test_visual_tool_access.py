"""Declaring a visual MCP tool is insufficient under the minimal gateway profile."""

import json

import pytest
import tool_access


@pytest.mark.parametrize("qualified", ["tools__render_interactive_ui", "tools__show_map"])
def test_visual_tool_needs_explicit_enablement_and_preserves_existing_policy(tmp_path, monkeypatch, qualified):
    config = tmp_path / "openclaw.json"
    original = {"tools": {"profile": "minimal", "alsoAllow": ["tools__web_search"]}, "other": {"keep": True}}
    config.write_text(json.dumps(original))
    monkeypatch.setattr(tool_access, "CONFIG_PATH", config)
    monkeypatch.setattr(tool_access, "reload_gateway", lambda: True)
    def entry():
        return next(item for group in tool_access.catalog()["groups"] for item in group["tools"] if item["qualified"] == qualified)
    assert entry()["enabled"] is False
    assert tool_access.set_enabled(qualified, True)["ok"]
    assert entry()["enabled"] is True
    saved = json.loads(config.read_text())
    assert saved["tools"] == {"profile": "minimal", "alsoAllow": ["tools__web_search", qualified]}
    assert saved["other"] == original["other"]
    assert tool_access.set_enabled(qualified, True)["changed"] is False
    assert tool_access.set_enabled(qualified, False)["ok"]
    assert entry()["enabled"] is False
    assert json.loads(config.read_text()) == original
