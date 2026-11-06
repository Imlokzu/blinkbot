"""Effort menus must reflect the selected runtime rather than the config schema."""
import asyncio
import json
from unittest.mock import AsyncMock

from fastapi.testclient import TestClient
import pytest

import main
import openclaw_models
import mobile_api
import mobile_routing


@pytest.fixture(autouse=True)
def isolated_profiles(monkeypatch):
    monkeypatch.setattr(openclaw_models, "_effort_profiles", None)
    monkeypatch.setattr(openclaw_models, "_effort_profiles_at", 0.0)


def test_gateway_profiles_never_borrow_levels_from_another_model(monkeypatch):
    payload = {"models": [
        {"provider": "openai", "id": "one", "thinkingLevels": [{"id": level} for level in ["low", "medium", "high", "max"]], "thinkingDefault": "medium"},
        {"provider": "openai", "id": "two", "thinkingLevels": [{"id": "off"}], "thinkingDefault": "off"},
        {"provider": "regolo", "id": "unknown"},
    ]}
    run = AsyncMock(return_value=(0, json.dumps(payload), ""))
    monkeypatch.setattr(openclaw_models, "_run_cli", run)
    monkeypatch.setattr(openclaw_models, "get_thinking", AsyncMock(return_value="adaptive"))
    models = asyncio.run(openclaw_models.with_effort_profiles([
        {"id": "openai/one"}, {"id": "openai/two"}, {"id": "regolo/unknown"},
    ]))
    assert models[0]["efforts"] == ["low", "medium", "high", "max"]
    assert models[0]["effort_default"] == "medium"
    assert models[1]["efforts"] == models[2]["efforts"] == []
    assert models[1]["effort_default"] == models[2]["effort_default"] == ""
    assert run.await_args.args[:4] == ("gateway", "call", "models.list", "--json")


def test_failed_profile_refresh_removes_stale_selectable_options(monkeypatch):
    monkeypatch.setattr(openclaw_models, "_effort_profiles", {"openai/one": {"efforts": ["high"]}})
    monkeypatch.setattr(openclaw_models, "_run_cli", AsyncMock(return_value=(1, "", "offline")))
    assert asyncio.run(openclaw_models.effort_profiles(force=True)) == {}


def test_web_rejects_unsupported_or_stale_model_effort_before_writing(monkeypatch):
    monkeypatch.setattr(openclaw_models, "catalog", AsyncMock(return_value=[{"id": "openai/one", "is_default": True}]))
    monkeypatch.setattr(openclaw_models, "get_selected", lambda: "")
    monkeypatch.setattr(openclaw_models, "model_effort_profile", AsyncMock(return_value={"efforts": ["low", "medium", "high"]}))
    write = AsyncMock(return_value=True)
    monkeypatch.setattr(openclaw_models, "set_thinking", write)
    monkeypatch.setattr(openclaw_models, "get_thinking", AsyncMock(return_value="high"))
    with TestClient(main.app) as client:
        assert client.post("/api/brain/thinking", json={"model": "openai/one", "level": "adaptive"}).status_code == 400
        assert client.post("/api/brain/thinking", json={"model": "openai/other", "level": "high"}).status_code == 409
        write.assert_not_awaited()
        assert client.post("/api/brain/thinking", json={"model": "openai/one", "level": "high"}).status_code == 200
    write.assert_awaited_once_with("high")


def test_mobile_refuses_incompatible_effort_before_gateway_or_fallback(monkeypatch):
    monkeypatch.setattr(openclaw_models, "catalog", AsyncMock(return_value=[{"id": "openai/one", "provider": "openai", "available": True}]))
    monkeypatch.setattr(openclaw_models, "model_effort_profile", AsyncMock(return_value={"efforts": ["low", "medium", "high"]}))
    patch = AsyncMock()
    monkeypatch.setattr(mobile_routing.openclaw_control, "_rpc", patch)
    import brains
    chat = AsyncMock()
    monkeypatch.setattr(brains, "chat_openclaw", chat)
    handle = mobile_api._turn_options.set({"model": "openai/one", "reasoning_effort": "adaptive"})
    try:
        with pytest.raises(mobile_routing.RoutingError):
            asyncio.run(mobile_routing.chat_gateway("Synthetic question", "", [], session_key="effort-fixture"))
    finally:
        mobile_api._turn_options.reset(handle)
    chat.assert_not_awaited()
    patch.assert_not_awaited()


def test_web_model_change_waits_until_effort_validation_and_write_finish(monkeypatch):
    async def check():
        selected = "openai/one"
        started, release = asyncio.Event(), asyncio.Event()
        writes = []
        monkeypatch.setattr(main, "_brain_choice_lock", asyncio.Lock())
        monkeypatch.setattr(main, "_require_user", AsyncMock(return_value=""))
        monkeypatch.setattr(openclaw_models, "get_selected", lambda: selected)
        monkeypatch.setattr(openclaw_models, "catalog", AsyncMock(return_value=[{"id": "openai/one"}, {"id": "openai/two"}]))

        async def profile(model):
            assert model == "openai/one"
            started.set()
            await release.wait()
            return {"efforts": ["high"]}

        async def thinking(level):
            writes.append((selected, level))
            return True

        async def model(_path, value):
            nonlocal selected
            selected = value
            return True

        monkeypatch.setattr(openclaw_models, "model_effort_profile", profile)
        monkeypatch.setattr(openclaw_models, "set_thinking", thinking)
        monkeypatch.setattr(openclaw_models, "get_thinking", AsyncMock(return_value="high"))
        monkeypatch.setattr(main.openclaw_settings, "apply", model)
        effort_task = asyncio.create_task(main.api_brain_thinking(main.BrainThinkingRequest(model=selected, level="high"), None))
        await started.wait()
        model_task = asyncio.create_task(main.api_brain_model_select(main.BrainModelRequest(model="openai/two"), None))
        await asyncio.sleep(0)
        assert not model_task.done()
        release.set()
        await asyncio.gather(effort_task, model_task)
        assert writes == [("openai/one", "high")]
        assert selected == "openai/two"
    asyncio.run(check())
