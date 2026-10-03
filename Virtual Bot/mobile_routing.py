"""Request-local model selection for the existing Gateway chat implementation."""

from __future__ import annotations

import asyncio
from contextvars import ContextVar
from typing import Any

import mobile_api
import jev_router
import openclaw_control
import openclaw_models

_model: ContextVar[str | None] = ContextVar("mobile_effective_model", default=None)


def model_override() -> str | None:
    return _model.get()


def seed_history() -> bool:
    options = mobile_api.current_turn_options()
    return bool(options and options.get("seed_history"))


class RoutingError(RuntimeError):
    """Only stable codes may travel into a user-visible streaming error."""


async def chat_gateway(message: str, system_prompt: str, history: list,
                       emit=None, images=None, session_key: str | None = None):
    import brains

    options = mobile_api.current_turn_options() or {}
    selected = str(options.get("model") or "")
    catalog = await openclaw_models.catalog()
    by_id = {str(entry.get("id")): entry for entry in catalog if entry.get("id")}
    if selected in {"auto", "jev", jev_router.JEV_ID}:
        _, selected, _, _ = await jev_router.route(message)
    elif not selected:
        selected = openclaw_models.default_model(catalog)
    entry = by_id.get(selected)
    if entry is None:
        raise RoutingError("mobile_model_unavailable")
    provider = str(entry.get("provider") or "")
    if not provider:
        raise RoutingError("mobile_model_provider_unknown")
    candidates = [entry] + [item for item in catalog if item.get("id") != selected and item.get("provider") == provider]
    candidates = [item for item in candidates if item.get("available") is not False]
    if images:
        candidates = [item for item in candidates if item.get("vision") is True]
    if not candidates:
        raise RoutingError("mobile_model_unavailable")

    observed_work = False
    observed_model = ""

    def effective_model(model: str, reported_provider: str = provider) -> str:
        nonlocal observed_work
        model = model if "/" in model else f"{reported_provider}/{model}"
        if reported_provider != provider or not model.startswith(provider + "/"):
            # A gateway-owned fallback already ran outside this provider.
            # Refuse success or replay rather than mislabeling that response.
            observed_work = True
            raise RoutingError("mobile_model_provider_mismatch")
        return model

    async def tracked(event: dict[str, Any]):
        nonlocal observed_work, observed_model
        if event.get("type") in {"delta", "note"} or str(event.get("type", "")).startswith("tool_"):
            observed_work = True
        if event.get("type") == "model" and event.get("model"):
            observed_model = effective_model(str(event["model"]), str(event.get("provider") or provider))
            event = {**event, "fallback": observed_model != selected}
        if emit is not None:
            await emit(event)

    for candidate in candidates[:4]:
        model = str(candidate["id"])
        observed_model = model
        handle = _model.set(model)
        try:
            # Session-scoped preferences are read by the HTTP agent-command
            # path. Never rewrite agents.defaults or the PC's global picker.
            if session_key:
                effort = str(options.get("reasoning_effort") or "none")
                await openclaw_control._rpc("sessions.patch", {
                    "key": session_key, "model": model,
                    "thinkingLevel": None if effort == "none" else effort,
                })
            if emit is not None:
                await emit({"type": "model", "provider": provider,
                            "model": model.removeprefix(provider + "/"),
                            "fallback": model != selected})
            result = await brains.chat_openclaw(message, system_prompt, history,
                                               emit=tracked if emit else None,
                                               images=images, session_key=session_key)
            if brains._looks_like_gateway_error(result[0]):
                raise RoutingError("mobile_model_unavailable")
            answered = effective_model(str(result[2])) if len(result) > 2 and result[2] else observed_model
            if emit is not None and answered != observed_model:
                await emit({"type": "model", "provider": provider,
                            "model": answered.removeprefix(provider + "/"),
                            "fallback": answered != selected})
            return result[0], result[1], answered
        except asyncio.CancelledError:
            raise
        except Exception:
            # Replaying after even narration/partial work could duplicate a
            # tool side effect. Provider changes are never an automatic retry.
            if observed_work:
                raise RoutingError("mobile_turn_interrupted") from None
        finally:
            _model.reset(handle)
    raise RoutingError("mobile_model_unavailable")
