"""Valid questions must offer an actual answer control in every consumer."""
import asyncio
import pytest
from tools import ui_tools
from tool_activity import ActivityLog


@pytest.mark.parametrize("options", [None, []])
def test_free_text_question_is_valid(monkeypatch, options):
    sent = []
    monkeypatch.setattr(ui_tools.events, "publish_ui", lambda kind, data, **_: sent.append(data))
    result = asyncio.run(ui_tools.ask_question("Name the fixture?", options=options))
    assert result["ok"] is True
    assert sent[0]["options"] == [] and sent[0]["allow_custom"] is True


def test_question_without_any_answer_control_is_rejected(monkeypatch):
    sent = []
    monkeypatch.setattr(ui_tools.events, "publish_ui", lambda *args, **_: sent.append(args))
    result = asyncio.run(ui_tools.ask_question("Name?", options=[" "], allow_custom=False))
    assert result["code"] == "no_answers"
    assert not sent


@pytest.mark.parametrize("tool,args", [
    ("ask_question", {"question": "Choose", "options": ["  A\nB  "]}),
    ("show_choice", {"title": "Choose", "options": [{"label": "a" * 100, "description": "description"}]}),
    ("todo_list", {"title": "Checklist", "items": ["b" * 180]}),
])
def test_canonical_receipt_matches_published_and_persisted_payload(monkeypatch, tool, args):
    published = []
    monkeypatch.setattr(ui_tools.events, "publish_ui", lambda kind, data, **_: published.append((kind, data)))
    result = asyncio.run(ui_tools.HANDLERS[tool](**args))
    ui = result["ui"]
    assert ui["version"] == 1
    assert (ui["kind"], ui["data"]) == published[0]
    log = ActivityLog()
    log.record({"type": "tool_start", "tool": tool, "call_id": "fixture-call", "input": args})
    log.record({"type": "tool_done", "tool": tool, "call_id": "fixture-call", "result": result})
    assert log.finish()[0]["result"]["ui"] == ui


@pytest.mark.parametrize("transport", ["native", "mcp"])
def test_transport_wrappers_preserve_canonical_receipts(monkeypatch, transport):
    import json
    monkeypatch.setattr(ui_tools.events, "publish_ui", lambda *args, **kwargs: None)
    result = asyncio.run(ui_tools.todo_list("Checklist", ["🙂" * 200] * 20))
    wrapped = {"result": result} if transport == "native" else {"content": [{"type": "text", "text": json.dumps(result, ensure_ascii=False)}]}
    log = ActivityLog()
    log.record({"type": "tool_done", "tool": "todo_list", "call_id": "fixture-call", "result": wrapped})
    assert log.finish()[0]["result"]["ui"] == result["ui"]


@pytest.mark.parametrize("tool,args,field,limit", [
    ("ask_question", {"question": "Choose?", "options": ["a" * 112 + " token=x"]}, "options", 120),
    ("show_choice", {"title": "Choose", "options": [{"label": "a" * 72 + " token=x"}]}, "label", 80),
    ("todo_list", {"items": ["a" * 192 + " token=x"]}, "text", 200),
])
def test_redaction_at_field_limits_is_identical_live_and_saved(monkeypatch, tool, args, field, limit):
    published = []
    monkeypatch.setattr(ui_tools.events, "publish_ui", lambda kind, data, **_: published.append(data))
    result = asyncio.run(ui_tools.HANDLERS[tool](**args))
    log = ActivityLog()
    log.record({"type": "tool_done", "tool": tool, "call_id": "fixture-call", "result": {"result": result}})
    saved = log.finish()[0]["result"]["ui"]["data"]
    assert saved == published[0] == result["ui"]["data"]
    value = saved["options"][0] if field == "options" else saved["options"][0]["label"] if field == "label" else saved["items"][0]["text"]
    assert len(value) <= limit and "token=x" not in value


def test_sensitive_ui_text_is_redacted_before_publication(monkeypatch):
    published = []
    monkeypatch.setattr(ui_tools.events, "publish_ui", lambda kind, data, **_: published.append(data))
    result = asyncio.run(ui_tools.show_choice("Choose", [{"label": "cookie=fixture"}]))
    assert published[0]["options"][0]["label"] == "cookie=[redacted]"
    log = ActivityLog()
    log.record({"type": "tool_done", "tool": "show_choice", "call_id": "fixture", "result": result})
    assert log.finish()[0]["result"]["ui"] == result["ui"]
