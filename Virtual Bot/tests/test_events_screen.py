"""The device screen has no Clerk token and must still hear the bot."""

from __future__ import annotations

import asyncio

import pytest
from fastapi import HTTPException
from starlette.requests import Request

import events
import main


def _request(query: bytes = b"") -> Request:
    return Request({"type": "http", "method": "GET", "path": "/api/events", "headers": [],
                    "query_string": query, "client": ("127.0.0.1", 5000)})


def _first_events(audience, publish):
    async def run():
        stream = events.sse_stream(audience=audience)
        assert await stream.__anext__() == ": ping\n\n"
        for payload, target in publish:
            events.publish(payload, audience=target)
        got = []
        try:
            while True:
                got.append(await asyncio.wait_for(stream.__anext__(), timeout=0.2))
        except asyncio.TimeoutError:
            pass
        await stream.aclose()
        return got
    return asyncio.run(run())


def test_screen_without_a_token_gets_the_stream(monkeypatch):
    # With Clerk on, the screen got 401 here: no music, no timers, no face.
    monkeypatch.delenv("CLERK_DISABLED", raising=False)
    response = asyncio.run(main.api_events(_request()))
    assert response.media_type == "text/event-stream"


def test_a_bad_token_is_still_refused(monkeypatch):
    monkeypatch.delenv("CLERK_DISABLED", raising=False)
    with pytest.raises(HTTPException) as err:
        asyncio.run(main.api_events(_request(b"token=not-a-jwt")))
    assert err.value.status_code == 401


def test_without_a_token_only_events_for_everyone_arrive():
    got = _first_events(None, [({"type": "music", "action": "stop"}, None),
                               ({"type": "ui", "kind": "todo"}, "user-a")])
    assert len(got) == 1 and '"music"' in got[0]
