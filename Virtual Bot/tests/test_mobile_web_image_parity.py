"""Mobile image turns reuse web dispatch, without assuming model capabilities."""

import asyncio
import base64
import json

import httpx
import pytest

import brains
import mobile_api
import mobile_routing


SOL = "openai/gpt-6-sol"
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNIqegBAAKsAWn9Yi+6AAAAAElFTkSuQmCC"
)
IMAGES = [{"mime": "image/png", "data": base64.b64encode(PNG).decode("ascii")}]


@pytest.fixture
def image_route(monkeypatch):
    """Keep session preferences and all provider/config access synthetic."""
    patches = []

    async def rpc(method, params):
        patches.append((method, dict(params)))
        return {}

    async def reject_text_route(*args, **kwargs):
        raise AssertionError("Image dispatch must not select a text model")

    monkeypatch.setattr(mobile_routing.openclaw_control, "_rpc", rpc)
    monkeypatch.setattr(brains.cfg, "get_openclaw_token", lambda: "synthetic-image-token")
    monkeypatch.setattr(brains.cfg, "OPENCLAW_BASE_URL", "https://image-gateway.fixture.invalid")
    monkeypatch.setattr(brains.cfg, "OPENCLAW_AGENT", "synthetic-agent")
    monkeypatch.setattr(brains.openclaw_config, "image_model", lambda: SOL)
    monkeypatch.setattr(brains.openclaw_models, "chat_route", reject_text_route)
    monkeypatch.setattr(mobile_routing.jev_router, "route", reject_text_route)
    monkeypatch.setattr(brains.tool_registry, "list_tools", lambda: [])
    return patches


@pytest.mark.parametrize("selection, entries, image_model, effort, session_key", [
    pytest.param(SOL, [{"id": SOL, "provider": "openai"}], SOL, "high", "image-session",
                 id="explicit-sol-without-vision-metadata"),
    pytest.param(SOL, [{"id": SOL, "provider": "openai", "vision": False}], SOL,
                 "xhigh", "image-session", id="misleading-vision-false"),
    pytest.param(SOL, [{"id": "text/default", "provider": "text"}], SOL,
                 "none", "image-session", id="sol-absent-from-filtered-catalog"),
    pytest.param("", [], SOL, "off", "image-session", id="empty-catalog"),
    pytest.param("text/only", [{"id": "text/only", "provider": "text", "vision": False}],
                 "images/configured", "adaptive", "image-session",
                 id="text-selection-cannot-block-other-image-provider"),
    pytest.param(SOL, [], "", "none", "image-session", id="unset-image-model"),
    pytest.param("auto", [], "", "high", None, id="gateway-default-without-session"),
])
def test_images_delegate_once_to_shared_web_route(image_route, monkeypatch, selection,
                                                 entries, image_model, effort, session_key):
    """Catalog flags and text choices cannot gate the already shared web route."""
    catalog_calls, calls, emitted = [], [], []
    history = [{"role": "assistant", "content": "Synthetic prior turn"}]
    shared_event = {"type": "model", "provider": "gateway-provider", "model": "reported"}
    shared_result = ("Synthetic image answer", [], "gateway-provider/reported")

    async def catalog():
        catalog_calls.append(True)
        return entries

    async def emit(event):
        emitted.append(event)

    async def shared_chat(message, system_prompt, passed_history, **kwargs):
        calls.append((message, system_prompt, passed_history, kwargs))
        assert mobile_routing.model_override() is None
        if kwargs["emit"]:
            await kwargs["emit"](shared_event)
        return shared_result

    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", catalog)
    monkeypatch.setattr(brains.openclaw_config, "image_model", lambda: image_model)
    monkeypatch.setattr(brains, "chat_openclaw", shared_chat)

    async def check():
        options = mobile_api._turn_options.set({"model": selection, "reasoning_effort": effort})
        stale = mobile_routing._model.set("text/stale-request-pin")
        try:
            result = await mobile_routing.chat_gateway(
                "Describe the attachment", "Synthetic system", history,
                emit=emit, images=IMAGES, session_key=session_key,
            )
            assert result is shared_result
            assert mobile_routing.model_override() == "text/stale-request-pin"
        finally:
            mobile_routing._model.reset(stale)
            mobile_api._turn_options.reset(options)

    asyncio.run(check())
    assert catalog_calls == []
    assert len(calls) == 1
    message, system, passed_history, kwargs = calls[0]
    assert (message, system) == ("Describe the attachment", "Synthetic system")
    assert passed_history is history
    assert kwargs == {"emit": emit, "images": IMAGES, "session_key": session_key}
    assert kwargs["images"] is IMAGES
    assert emitted == [shared_event, {
        "type": "model", "provider": "gateway-provider", "model": "reported",
    }]
    assert emitted[0] is shared_event
    assert image_route == ([] if session_key is None else [
        ("sessions.patch", {"key": session_key, "thinkingLevel": None if effort == "none" else effort}),
    ])


@pytest.mark.parametrize("after_work", [False, True])
def test_image_gateway_failure_is_not_retried_or_reclassified(image_route, monkeypatch, after_work):
    """The shared gateway owns failures; mobile must not replay an image turn."""
    calls, emitted = [], []
    failure = RuntimeError("Synthetic gateway failure")

    async def reject_catalog():
        raise AssertionError("Image dispatch must not fetch the catalog")

    async def emit(event):
        emitted.append(event)

    async def shared_chat(*args, emit=None, **kwargs):
        calls.append(True)
        assert mobile_routing.model_override() is None
        if after_work:
            await emit({"type": "tool_start", "tool": "synthetic-tool"})
        raise failure

    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", reject_catalog)
    monkeypatch.setattr(brains, "chat_openclaw", shared_chat)

    async def check():
        options = mobile_api._turn_options.set({"model": SOL, "reasoning_effort": "none"})
        stale = mobile_routing._model.set("text/stale-request-pin")
        try:
            with pytest.raises(RuntimeError) as caught:
                await mobile_routing.chat_gateway("Describe", "", [], emit=emit, images=IMAGES)
            assert caught.value is failure
            assert mobile_routing.model_override() == "text/stale-request-pin"
        finally:
            mobile_routing._model.reset(stale)
            mobile_api._turn_options.reset(options)

    asyncio.run(check())
    assert calls == [True]
    assert emitted == ([{"type": "tool_start", "tool": "synthetic-tool"}] if after_work else [])
    assert image_route == []


@pytest.mark.parametrize("image_model", [SOL, "images/configured", ""])
@pytest.mark.parametrize("streaming", [False, True])
def test_real_brains_image_payload_matches_web(image_route, monkeypatch, image_model, streaming):
    """Compare actual HTTP envelopes with in-memory transport, never a provider."""
    requests, config_reads = [], []
    client_class = httpx.AsyncClient

    def configured_image():
        config_reads.append(True)
        return image_model

    def gateway(request):
        assert request.url == "https://image-gateway.fixture.invalid/v1/chat/completions"
        assert request.headers["authorization"] == "Bearer synthetic-image-token"
        payload = json.loads(request.content)
        requests.append((dict(request.headers), payload))
        if payload["stream"]:
            content = 'data: {"choices":[{"delta":{"content":"Synthetic image reply"}}]}\n\ndata: [DONE]\n\n'
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=content)
        return httpx.Response(200, json={"choices": [{"message": {"content": "Synthetic image reply"}}]})

    def client(*args, **kwargs):
        return client_class(*args, **kwargs, transport=httpx.MockTransport(gateway))

    class Activity:
        def __init__(self, emit, session_key=None, **kwargs):
            self.session_key = session_key

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

    async def reject_catalog():
        raise AssertionError("Image dispatch must not fetch the catalog")

    monkeypatch.setattr(brains.openclaw_config, "image_model", configured_image)
    monkeypatch.setattr(mobile_routing.openclaw_models, "catalog", reject_catalog)
    monkeypatch.setattr(brains.httpx, "AsyncClient", client)
    monkeypatch.setattr(brains, "GatewayActivity", Activity)

    async def check():
        web_events, mobile_events = [], []

        async def web_emit(event):
            web_events.append(event)

        async def mobile_emit(event):
            mobile_events.append(event)

        history = [{"role": "assistant", "content": "Already in gateway history"}]
        stale = mobile_routing._model.set("text/stale-request-pin")
        try:
            web_result = await brains.chat_openclaw(
                "Describe the attachment", "Synthetic system", history,
                emit=web_emit if streaming else None, images=IMAGES, session_key="image-session",
            )
            options = mobile_api._turn_options.set({"model": "text/only", "reasoning_effort": "high"})
            try:
                mobile_result = await mobile_routing.chat_gateway(
                    "Describe the attachment", "Synthetic system", history,
                    emit=mobile_emit if streaming else None, images=IMAGES, session_key="image-session",
                )
            finally:
                mobile_api._turn_options.reset(options)
            assert mobile_routing.model_override() == "text/stale-request-pin"
        finally:
            mobile_routing._model.reset(stale)
        assert web_result == mobile_result == ("Synthetic image reply", [], image_model)
        receipt = []
        if streaming and image_model:
            provider, model = image_model.split("/", 1)
            receipt = [{"type": "model", "provider": provider, "model": model}]
        assert mobile_events == web_events + receipt

    asyncio.run(check())
    assert len(requests) == 2
    assert config_reads == [True, True], "Each dispatch must read the shared image config only once"
    assert requests[0] == requests[1]
    headers, payload = requests[1]
    if image_model:
        assert headers["x-openclaw-model"] == image_model
    else:
        assert "x-openclaw-model" not in headers
    assert headers["x-openclaw-session-key"] == "image-session"
    assert payload["model"] == "synthetic-agent"
    assert payload["stream"] is streaming
    assert payload["messages"] == [
        {"role": "system", "content": "Synthetic system"},
        {"role": "user", "content": [
            {"type": "text", "text": "Describe the attachment"},
            {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{IMAGES[0]['data']}"}},
        ]},
    ]
    image_url = payload["messages"][-1]["content"][1]["image_url"]["url"]
    assert base64.b64decode(image_url.split(",", 1)[1]) == PNG
    assert image_route == [("sessions.patch", {"key": "image-session", "thinkingLevel": "high"})]
