"""Streaming cleanup and replay rules with isolated gateway callbacks."""

import asyncio

from fastapi.responses import StreamingResponse
import pytest

import brains
import main
import mobile_bridge
import mobile_routing


def test_mobile_runner_waits_for_underlying_stream_cleanup_before_returning():
    async def check():
        release_cleanup, cleaned = asyncio.Event(), asyncio.Event()

        async def body():
            try:
                yield 'event: delta\ndata: {"chunk":"Working"}\n\n'
                await asyncio.Event().wait()
            finally:
                await release_cleanup.wait()
                cleaned.set()

        async def turn(*args):
            return StreamingResponse(body())

        run = mobile_bridge.runner(main.ChatRequest, turn)
        iterator = run({"message": "Isolated request", "session_id": "cleanup", "user_id": "", "attachments": []})
        assert await anext(iterator) == ("delta", {"chunk": "Working"})
        closing = asyncio.create_task(iterator.aclose())
        try:
            await asyncio.sleep(0)
            assert not closing.done(), "Queue completion must wait for the response's cleanup"
            release_cleanup.set()
            await asyncio.wait_for(closing, 5)
            assert cleaned.is_set()
        finally:
            release_cleanup.set()
            await closing
    asyncio.run(check())


@pytest.mark.parametrize("event", [{"type": "note", "id": "pre", "text": "Checking", "done": False},
                                   {"type": "delta", "chunk": "Partial reply"},
                                   {"type": "reply_snapshot", "id": "answer", "text": "Partial snapshot"},
                                   {"type": "tool_start", "tool": "test"}])
@pytest.mark.parametrize("error_type", [RuntimeError, brains._NeedsTools])
def test_gateway_does_not_replay_a_stream_after_visible_or_tool_work(monkeypatch, event, error_type):
    """The inner gateway retry must obey the same rule as mobile model fallback."""
    fallback_calls = []
    monkeypatch.setattr(brains.cfg, "get_openclaw_token", lambda: "isolated-test-token")
    monkeypatch.setattr(mobile_routing, "model_override", lambda: "test/model")

    class Activity:
        def __init__(self, emit, session_key=None):
            self.session_key = session_key
        async def __aenter__(self):
            return self
        async def __aexit__(self, *args):
            return False

    async def stream(*args, emit, **kwargs):
        await emit(event)
        raise error_type("Isolated transport interruption")

    async def fallback(*args, **kwargs):
        fallback_calls.append(True)
        return "Replayed answer", []

    async def emit(event):
        pass

    monkeypatch.setattr(brains, "GatewayActivity", Activity)
    monkeypatch.setattr(brains, "_stream_openai_compatible", stream)
    monkeypatch.setattr(brains, "_call_openai_compatible_with_tools", fallback)
    with pytest.raises(error_type):
        asyncio.run(brains.chat_openclaw("Isolated request", "", [], emit=emit, session_key="isolated"))
    assert not fallback_calls
