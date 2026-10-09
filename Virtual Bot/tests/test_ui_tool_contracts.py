"""Valid questions must offer an actual answer control in every consumer."""
import asyncio
import pytest
from tools import ui_tools


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
