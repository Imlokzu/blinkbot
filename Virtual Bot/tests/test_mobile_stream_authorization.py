"""An already-open mobile listener must obey device revocation and expiry."""
from __future__ import annotations

import asyncio
import hashlib
import socket

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import pytest
import httpx
from starlette.requests import Request
import uvicorn

from mobile_api import MessageRequest, router
from mobile_store import MobileStore


async def require_user(request):
    if request.headers.get("authorization") == "Bearer clerk-fixture":
        return "alice"
    raise HTTPException(401)


async def require_operator(_request):
    raise HTTPException(403)


async def unused_runner(_job):
    raise AssertionError("The stream fixture must not invoke a model")
    yield


def add_device(store, owner, device_id, *, expires_at=2000):
    """Deterministic credentials exist only inside the temporary fixture database."""
    token = "cbm_stream_fixture_" + device_id
    with store._write() as db:
        db.execute("INSERT INTO devices VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                   (device_id, owner, "Fixture", "android", hashlib.sha256(token.encode()).hexdigest(),
                    1000, expires_at))
    return token


def stream_request(job, token, *, after=None):
    query = b"" if after is None else f"after={after}".encode()
    return Request({"type": "http", "method": "GET",
                    "path": f"/api/mobile/messages/{job['id']}/events", "query_string": query,
                    "headers": [(b"authorization", ("Bearer " + token).encode())]},
                   receive=connected)


async def connected():
    return {"type": "http.request", "body": b"", "more_body": False}


def make_routes(tmp_path, owner):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    routes = router(require_user, require_operator, unused_runner, store=store, scan_interval=0.01)
    job = store.submit(owner, MessageRequest(client_id="stream-fixture", session_id="shared",
                                            message="Fixture question").model_dump())
    store.claim_next()
    endpoint = next(route.endpoint for route in routes.routes
                    if route.path == "/api/mobile/messages/{job_id}/events")
    return store, now, routes, job, endpoint


async def open_stream(routes, endpoint, job, token, *, after=0):
    request = stream_request(job, token, after=after)
    owner = await routes.identity(request)
    response = await endpoint(job["id"], request, after=after, user_id=owner)
    return response.body_iterator


@pytest.mark.parametrize("owner", ["", "alice"])
def test_revoked_listener_closes_but_other_owner_device_and_job_continue(tmp_path, owner):
    async def scenario():
        store, _now, routes, job, endpoint = make_routes(tmp_path, owner)
        removed = add_device(store, owner, "removed")
        retained = add_device(store, owner, "retained")
        revoked_stream = await open_stream(routes, endpoint, job, removed)
        retained_stream = await open_stream(routes, endpoint, job, retained)
        try:
            for _ in store.events(owner, job["id"]):
                await anext(revoked_stream)
                await anext(retained_stream)
            # Revoke through the real route, with the second device's identity.
            app = FastAPI()
            app.include_router(routes)
            # Do not start the scheduler: this test owns the synthetic running job.
            client = TestClient(app)
            try:
                response = client.delete("/api/mobile/devices/removed", headers={"Authorization": "Bearer " + retained})
                assert response.status_code == 200
                assert client.get(f"/api/mobile/messages/{job['id']}/events",
                                  headers={"Authorization": "Bearer " + removed}).status_code == 401
            finally:
                client.close()
            store.record(job["id"], "delta", {"chunk": "After revocation"})
            with pytest.raises(StopAsyncIteration):
                await asyncio.wait_for(anext(revoked_stream), 1)
            assert "After revocation" in await anext(retained_stream)
            assert store.get(owner, job["id"])["state"] == "running"
        finally:
            await revoked_stream.aclose()
            await retained_stream.aclose()

    asyncio.run(scenario())


@pytest.mark.parametrize("invalidate", ["revoke", "expire"])
def test_revocation_or_expiry_stops_delivery_inside_an_already_loaded_batch(tmp_path, invalidate):
    async def scenario():
        store, now, routes, job, endpoint = make_routes(tmp_path, "alice")
        token = add_device(store, "alice", "batch", expires_at=1001)
        cursor = store.events("alice", job["id"])[-1]["seq"]
        store.record(job["id"], "delta", {"chunk": "First"})
        store.record(job["id"], "delta", {"chunk": "Must not escape"})
        iterator = await open_stream(routes, endpoint, job, token, after=cursor)
        try:
            assert "First" in await anext(iterator)
            if invalidate == "revoke":
                store.revoke("alice", "batch")
            else:
                now[0] = 1001
            with pytest.raises(StopAsyncIteration):
                await asyncio.wait_for(anext(iterator), 1)
        finally:
            await iterator.aclose()

    asyncio.run(scenario())


def test_idle_listener_rechecks_before_another_keepalive(tmp_path):
    async def scenario():
        store, _now, routes, job, endpoint = make_routes(tmp_path, "alice")
        token = add_device(store, "alice", "idle")
        cursor = store.events("alice", job["id"])[-1]["seq"]
        iterator = await open_stream(routes, endpoint, job, token, after=cursor)
        try:
            assert await anext(iterator) == ": keepalive\n\n"
            store.revoke("alice", "idle")
            with pytest.raises(StopAsyncIteration):
                await asyncio.wait_for(anext(iterator), 1)
        finally:
            await iterator.aclose()

    asyncio.run(scenario())


def test_clerk_listener_and_valid_device_terminal_replay_are_preserved(tmp_path):
    async def scenario():
        store, _now, routes, job, endpoint = make_routes(tmp_path, "alice")
        token = add_device(store, "alice", "valid")
        store.record(job["id"], "done", {"reply": "Complete answer"})
        store.finish(job["id"], "completed")
        expected = [entry["seq"] for entry in store.events("alice", job["id"])]
        for credential in (token, "clerk-fixture"):
            iterator = await open_stream(routes, endpoint, job, credential)
            try:
                frames = [frame async for frame in iterator]
            finally:
                await iterator.aclose()
            assert [int(frame.splitlines()[0].split(": ")[1]) for frame in frames] == expected
            assert any("Complete answer" in frame for frame in frames)

    asyncio.run(scenario())


def test_revocation_closes_real_http_stream_without_stopping_other_device(tmp_path):
    """Exercise EOF and surviving delivery through the actual StreamingResponse."""
    async def scenario():
        store, _now, routes, job, _endpoint = make_routes(tmp_path, "")
        removed = add_device(store, "", "http-removed")
        retained = add_device(store, "", "http-retained")
        cursor = store.events("", job["id"])[-1]["seq"]
        app = FastAPI()
        app.include_router(routes)
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen()
        origin = f"http://127.0.0.1:{listener.getsockname()[1]}"
        server = uvicorn.Server(uvicorn.Config(app, lifespan="off", access_log=False, log_level="error",
                                             timeout_graceful_shutdown=1))
        task = asyncio.create_task(server.serve(sockets=[listener]))
        try:
            async def wait_started():
                while not server.started:
                    if task.done():
                        await task
                        raise AssertionError("Fixture server did not start")
                    await asyncio.sleep(0.01)
            await asyncio.wait_for(wait_started(), 5)
            async with httpx.AsyncClient(base_url=origin, timeout=3, trust_env=False) as client:
                url = f"/api/mobile/messages/{job['id']}/events?after={cursor}"
                async with client.stream("GET", url, headers={"Authorization": "Bearer " + removed}) as first, \
                           client.stream("GET", url, headers={"Authorization": "Bearer " + retained}) as second:
                    assert first.status_code == second.status_code == 200
                    first_lines, second_lines = first.aiter_lines(), second.aiter_lines()
                    assert await anext(first_lines) == ": keepalive"
                    assert await anext(first_lines) == ""
                    assert await anext(second_lines) == ": keepalive"
                    assert await anext(second_lines) == ""
                    response = await client.delete("/api/mobile/devices/http-removed",
                                                   headers={"Authorization": "Bearer " + retained})
                    assert response.status_code == 200
                    store.record(job["id"], "delta", {"chunk": "Surviving device receives this"})
                    # Keepalive bytes already sent before revocation may remain
                    # buffered; no later event data may reach the removed phone.
                    remaining = [line async for line in first_lines]
                    assert not any(line.startswith("data:") for line in remaining)
                    async def surviving_data():
                        async for line in second_lines:
                            if line.startswith("data:"):
                                return line
                        raise AssertionError("The surviving listener closed")
                    assert "Surviving device receives this" in await asyncio.wait_for(surviving_data(), 2)
                rejected = await client.get(url, headers={"Authorization": "Bearer " + removed})
                assert rejected.status_code == 401
                assert store.get("", job["id"])["state"] == "running"
        finally:
            server.should_exit = True
            await asyncio.wait_for(task, 3)
            listener.close()

    asyncio.run(scenario())
