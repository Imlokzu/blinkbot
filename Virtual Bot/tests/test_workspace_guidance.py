"""Both brain paths must teach the agent how document links open in the app."""

import brains
import workspace_mcp
from tools.workspace_tools import SCHEMAS
from workspace_guidance import DOCUMENT_GUIDANCE, SHOW_DESCRIPTION


def test_dashboard_channel_explains_document_delivery():
    assert DOCUMENT_GUIDANCE in brains._CHANNEL_RULES["chat"]
    assert "[title](session/file.md)" in DOCUMENT_GUIDANCE
    assert "workspace_show" in DOCUMENT_GUIDANCE
    assert "![Diagram](session/diagram.excalidraw)" in DOCUMENT_GUIDANCE
    assert DOCUMENT_GUIDANCE not in brains._CHANNEL_RULES["screen"]


def test_local_and_mcp_tools_share_delivery_rules():
    local = {entry["function"]["name"]: entry["function"]["description"] for entry in SCHEMAS}
    mcp = {entry["name"]: entry["description"] for entry in workspace_mcp.TOOLS}
    assert local["workspace_show"] == mcp["workspace_show"] == SHOW_DESCRIPTION
    assert DOCUMENT_GUIDANCE in local["workspace_write"]
    assert DOCUMENT_GUIDANCE in mcp["workspace_write"]
