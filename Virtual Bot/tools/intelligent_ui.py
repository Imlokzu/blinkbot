"""Persist generated source separately from bounded chat activity receipts."""

from __future__ import annotations

import hashlib
import json
import uuid

import workspace
from intelligent_ui_schema import SCHEMA
from tool_activity import bounded_ui_text
from tools.locales import t

MAX_SOURCE = 64000


async def render_interactive_ui(title: str, summary: str, html: str,
                                css: str = "", javascript: str = "", height: int = 360) -> dict:
    fields = (title, summary, html, css, javascript)
    if (not all(isinstance(field, str) for field in fields)
            or not title.strip() or not summary.strip() or not html.strip()
            or len(title) > 120 or len(summary) > 600
            or sum(len(field) for field in fields) > MAX_SOURCE
            or isinstance(height, bool) or not isinstance(height, int) or not 180 <= height <= 720):
        return {"error": t("ui.invalid_artifact"), "code": "invalid_artifact"}
    artifact_id = uuid.uuid4().hex
    document = {"version": 1, "html": html, "css": css, "javascript": javascript}
    source = json.dumps(document, ensure_ascii=True, separators=(",", ":"))
    try:
        # Existing workspace resolution enforces owner isolation and symlink guards.
        saved = workspace.write_file(f"session/interactive/{artifact_id}.json", source)
    except (ValueError, OSError):
        return {"error": t("ui.save_failed"), "code": "artifact_save_failed"}
    return {"ok": True, "ui": {
        "version": 1, "kind": "intelligent_ui", "id": artifact_id,
        "data": {"title": bounded_ui_text(title, 120), "summary": bounded_ui_text(summary, 600),
                 "path": saved["path"], "sha256": hashlib.sha256(source.encode()).hexdigest(),
                 "height": height},
    }}


SCHEMAS = [{"type": "function", "function": {
    "name": SCHEMA["name"], "description": SCHEMA["description"], "parameters": SCHEMA["inputSchema"],
}}]
HANDLERS = {"render_interactive_ui": render_interactive_ui}
