"""Exercise the adapter with an isolated FastAPI app and fake agent only."""
from __future__ import annotations

import asyncio
import json
import os
import threading
import time
from types import SimpleNamespace
from urllib.parse import parse_qs, urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import httpx
import pytest

from mobile_api import authenticate_token, current_turn_options, iter_chat_events, materialize_fork, MobileRuntime, router
from mobile_store import MobileStore


async def owner(request: Request):
    # Deliberately permits the local empty owner to test fail-closed tokens.
    return request.headers.get("x-owner", "")


async def operator(request: Request):
    if request.headers.get("x-operator") != "yes":
        raise HTTPException(403, "operator_required")


async def immediate(job):
    yield "session", {"session_id": job["session_id"]}
    yield "delta", {"type": "delta", "chunk": job["message"]}
    yield "done", {"reply": job["message"], "model": job["model"], "session_id": job["session_id"]}


def make_app(store, runner=immediate, **kwargs):
    app = FastAPI()
    app.include_router(router(owner, operator, runner, store=store, scan_interval=0.01, **kwargs))
    return app


def request_body(client_id="one", **changes):
    return {"client_id": client_id, "session_id": "shared", "message": "Hello", **changes}


def wait_state(store, user_id, job_id, expected, timeout=2):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state = store.get(user_id, job_id)["state"]
        if state == expected:
            return
        time.sleep(0.005)
    pytest.fail(f"Expected {expected}, got {state}")


def test_pairing_operator_gate_qr_exchange_auth_and_revoke(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store, server_origin="https://api.example.test")) as client:
        assert client.post("/api/mobile/pairings", json={}).status_code == 403
        result = client.post("/api/mobile/pairings", json={}, headers={"x-operator": "yes"})
        assert result.status_code == 200
        pairing = result.json()
        uri = urlsplit(pairing["qr_payload"])
        assert (uri.scheme, uri.netloc) == ("claudebot", "pair")
        assert parse_qs(uri.query) == {"server": ["https://api.example.test"], "code": [pairing["code"]]}
        exchanged = client.post("/api/mobile/pair/exchange", json={"code": pairing["code"], "device_name": "Phone", "platform": "ios"})
        assert exchanged.status_code == 200
        device = exchanged.json()
        auth = {"authorization": "Bearer " + device["token"]}
        assert authenticate_token(device["token"], store=store) == ""
        assert authenticate_token("other-token", store=store) is None
        assert client.get("/api/mobile/devices", headers=auth).json()["devices"][0]["device_id"] == device["device_id"]
        assert client.post("/api/mobile/pair/exchange", json={"code": pairing["code"], "device_name": "Again", "platform": "ios"}).status_code == 401
        assert client.delete("/api/mobile/devices/" + device["device_id"], headers={"x-owner": "other"}).status_code == 404
        assert client.delete("/api/mobile/devices/" + device["device_id"], headers=auth).status_code == 200
        assert client.get("/api/mobile/capabilities", headers=auth).status_code == 401
        assert client.get("/api/mobile/capabilities", headers={"authorization": "Bearer cbm_invalid"}).status_code == 401
        with pytest.raises(HTTPException) as invalid:
            authenticate_token("cbm_invalid", store=store)
        assert invalid.value.status_code == 401


def test_pairing_rejects_unapproved_or_non_https_origins(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store, server_origin="https://api.example.test")) as client:
        for server in ["http://api.example.test", "https://other.test", "https://api.example.test/path", "https://name@api.example.test"]:
            assert client.post("/api/mobile/pairings", json={"server": server}, headers={"x-operator": "yes"}).status_code == 422


def test_capabilities_publishes_version_gated_update_metadata(tmp_path, monkeypatch):
    monkeypatch.delenv("MOBILE_UPDATE_ANDROID_SHA256", raising=False)
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION", "0.4.2")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION_CODE", "9")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_CHANGELOG", "Better image previews\nNative Excalidraw viewer")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_URL", "https://api.example.test/mobile/0.4.2.apk")
    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store)) as client:
        newer = client.get("/api/mobile/capabilities?platform=android&version_code=8").json()["update"]
        current = client.get("/api/mobile/capabilities?platform=android&version_code=9").json()["update"]
    assert newer == {
        "available": True,
        "channel": "stable",
        "version_name": "0.4.2",
        "version_code": 9,
        "changelog": ["Better image previews", "Native Excalidraw viewer"],
        "url": "https://api.example.test/mobile/0.4.2.apk",
        "ios_url": None,
        "sha256": None,
        "mandatory": False,
    }
    assert current["available"] is False


def test_capabilities_beta_channel_uses_beta_metadata_and_stable_fallback(tmp_path, monkeypatch):
    for key in list(os.environ):
        if key.startswith("MOBILE_UPDATE"):
            monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION", "0.4.2")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION_CODE", "9")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_FILE", str(tmp_path / "stable.apk"))
    apk = tmp_path / "beta.apk"
    apk.write_bytes(b"beta-bytes")
    monkeypatch.setenv("MOBILE_UPDATE_BETA_ANDROID_VERSION", "0.4.3")
    monkeypatch.setenv("MOBILE_UPDATE_BETA_ANDROID_VERSION_CODE", "10")
    monkeypatch.setenv("MOBILE_UPDATE_BETA_ANDROID_FILE", str(apk))
    monkeypatch.setenv("MOBILE_UPDATE_BETA_ANDROID_SHA256", "abc")
    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store)) as client:
        auth = {"authorization": "Bearer " + _device_token(store)}
        beta = client.get("/api/mobile/capabilities?platform=android&version_code=0&channel=beta", headers=auth).json()
        assert beta["update"]["channel"] == "beta"
        assert beta["update"]["version_code"] == 10
        assert beta["update"]["url"].endswith("/api/mobile/update/download?channel=beta")
        assert beta["update_stable"]["version_code"] == 9
        assert beta["update_beta"]["version_code"] == 10
        download = client.get("/api/mobile/update/download?channel=beta", headers=auth)
        assert download.status_code == 200
        assert download.content == b"beta-bytes"
        assert client.get("/api/mobile/update/download?channel=nope", headers=auth).status_code in (200, 404)


def _device_token(store):
    result = store.exchange(store.create_pairing("")["code"], "Phone", "android")
    return result["token"]


def test_capabilities_builds_same_origin_android_download_and_serves_it_authenticated(tmp_path, monkeypatch):
    for key in list(os.environ):
        if key.startswith("MOBILE_UPDATE"):
            monkeypatch.delenv(key, raising=False)
    apk = tmp_path / "ClaudeBot.apk"
    apk.write_bytes(b"synthetic-apk")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_FILE", str(apk))
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION", "0.4.2")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION_CODE", "9")
    monkeypatch.setenv("MOBILE_API_ORIGIN", "https://api.example.test")
    store = MobileStore(tmp_path / "mobile.db")
    pairing = store.create_pairing("")
    token = store.exchange(pairing["code"], "Fixture", "android")["token"]
    with TestClient(make_app(store, server_origin="https://api.example.test")) as client:
        auth = {"authorization": f"Bearer {token}"}
        update = client.get("/api/mobile/capabilities?platform=android&version_code=8", headers=auth).json()["update"]
        assert update["url"] == "https://api.example.test/api/mobile/update/download"
        assert client.get("/api/mobile/update/download", headers=auth).content == b"synthetic-apk"
        assert client.get("/api/mobile/update/download", headers={"x-owner": "other"}).status_code == 200


def test_message_callback_receives_original_identity_explicit_model_and_generated_session(tmp_path):
    seen = []

    async def runner(job):
        seen.append(job)
        async for event in immediate(job):
            yield event

    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store, runner)) as client:
        body = request_body(session_id="", model="openai/model", reasoning_effort="xhigh", attachments=[{"url": "/uploads/example.txt", "name": "example.txt"}])
        first = client.post("/api/mobile/messages", json=body, headers={"x-owner": "alice"}).json()
        assert first["message"] == "Hello"
        assert first["model"] == "openai/model"
        assert first["scheduled_at"] is None
        duplicate = client.post("/api/mobile/messages", json=body, headers={"x-owner": "alice"}).json()
        assert duplicate["id"] == first["id"]
        assert duplicate["session_id"] == first["session_id"]
        wait_state(store, "alice", first["id"], "completed")
        assert len(seen) == 1
        assert seen[0]["user_id"] == "alice"
        assert seen[0]["session_id"] == first["session_id"]
        assert seen[0]["model"] == "openai/model"
        assert seen[0]["reasoning_effort"] == "xhigh"
        assert seen[0]["attachments"] == body["attachments"]
        assert client.post("/api/mobile/messages", json={**body, "message": "Changed"}, headers={"x-owner": "alice"}).status_code == 409
        listed = client.get("/api/mobile/messages", headers={"x-owner": "alice"}).json()["messages"]
        assert len(listed) == 1 and listed[0]["state"] == "completed"
        assert client.get("/api/mobile/messages", headers={"x-owner": "bob"}).json() == {"messages": []}
        assert client.get(f"/api/mobile/messages/{first['id']}/events", headers={"x-owner": "bob"}).status_code == 404


def test_original_sse_payload_replay_and_last_event_id(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store)) as client:
        job = client.post("/api/mobile/messages", json=request_body()).json()
        wait_state(store, "", job["id"], "completed")
        response = client.get(f"/api/mobile/messages/{job['id']}/events")
        assert response.headers["cache-control"] == "no-store, no-transform"
        blocks = [block for block in response.text.split("\n\n") if block]
        ids = [int(block.splitlines()[0].split(": ")[1]) for block in blocks]
        assert ids == list(range(1, len(ids) + 1))
        delta = next(block for block in blocks if "event: delta\n" in block)
        assert json.loads(delta.split("data: ")[1]) == {"type": "delta", "chunk": "Hello"}
        tail = client.get(f"/api/mobile/messages/{job['id']}/events", headers={"last-event-id": str(ids[-2])})
        assert tail.text.startswith(f"id: {ids[-1]}\n")
        assert client.get(f"/api/mobile/messages/{job['id']}/events?after={ids[-1]}").text == ""
        assert client.get(f"/api/mobile/messages/{job['id']}/events", headers={"last-event-id": "invalid"}).status_code == 422


def test_attachment_only_submission_preserves_empty_user_message(tmp_path):
    seen = []

    async def runner(job):
        seen.append(job)
        yield "done", {"reply": "Read the attachment"}

    store = MobileStore(tmp_path / "mobile.db")
    attachment = {"url": "/uploads/note.txt", "name": "note.txt"}
    with TestClient(make_app(store, runner)) as client:
        response = client.post("/api/mobile/messages", json=request_body(message="", attachments=[attachment]))
        assert response.status_code == 200
        job = response.json()
        wait_state(store, "", job["id"], "completed")
        assert seen[0]["message"] == ""
        assert seen[0]["attachments"] == [attachment]
        assert client.post("/api/mobile/messages", json=request_body("empty", message=" ")).status_code == 422


def test_stop_cancels_runner_and_does_not_launch_next_until_resume(tmp_path):
    starts = []
    release = threading.Event()
    cleaned = threading.Event()

    async def runner(job):
        starts.append(job["client_id"])
        try:
            yield "delta", {"chunk": "working"}
            if job["client_id"] == "first":
                while not release.is_set():
                    await asyncio.sleep(0.005)
            yield "done", {"reply": "finished"}
        finally:
            cleaned.set()

    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store, runner)) as client:
        first = client.post("/api/mobile/messages", json=request_body("first")).json()
        wait_state(store, "", first["id"], "running")
        second = client.post("/api/mobile/messages", json=request_body("second")).json()
        stopped = client.post(f"/api/mobile/messages/{first['id']}/stop")
        assert stopped.json()["state"] == "stopped"
        assert cleaned.wait(1)
        time.sleep(0.04)
        assert starts == ["first"]
        assert store.get("", second["id"])["state"] == "queued"
        assert client.get("/api/mobile/messages?session_id=shared").json()["messages"][1]["conversation_paused"] is True
        assert client.post("/api/mobile/sessions/shared/resume").status_code == 200
        wait_state(store, "", second["id"], "completed")
        assert starts == ["first", "second"]


def test_normal_completion_advances_queue(tmp_path):
    starts = []

    async def runner(job):
        starts.append(job["client_id"])
        await asyncio.sleep(0.02)
        yield "done", {"reply": "finished"}

    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store, runner)) as client:
        first = client.post("/api/mobile/messages", json=request_body("first")).json()
        second = client.post("/api/mobile/messages", json=request_body("second")).json()
        wait_state(store, "", second["id"], "completed")
        assert store.get("", first["id"])["state"] == "completed"
        assert starts == ["first", "second"]


def test_schedule_survives_app_restart_and_no_timezone_is_rejected(tmp_path):
    now = [100.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    starts = []

    async def runner(job):
        starts.append(job["id"])
        yield "done", {"reply": "scheduled"}

    with TestClient(make_app(store, runner)) as client:
        job = client.post("/api/mobile/messages", json=request_body(scheduled_at="1970-01-01T00:03:20Z")).json()
        assert job["state"] == "scheduled"
        assert not starts
        assert client.post("/api/mobile/messages", json=request_body("bad", scheduled_at="2026-10-03T12:00:00")).status_code == 422
    now[0] = 200
    with TestClient(make_app(MobileStore(store.path, clock=lambda: now[0]), runner)):
        wait_state(store, "", job["id"], "completed")
    with TestClient(make_app(store, runner)):
        assert starts == [job["id"]]


def test_failed_runner_pauses_queue_and_does_not_expose_exception(tmp_path):
    async def runner(job):
        if job["client_id"] == "first":
            raise RuntimeError("private provider diagnostics")
        yield "done", {"reply": "second"}

    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store, runner)) as client:
        first = client.post("/api/mobile/messages", json=request_body("first")).json()
        second = client.post("/api/mobile/messages", json=request_body("second")).json()
        wait_state(store, "", first["id"], "failed")
        time.sleep(0.03)
        assert store.get("", second["id"])["state"] == "queued"
        assert "private provider diagnostics" not in client.get(f"/api/mobile/messages/{first['id']}/events").text


def test_steer_reports_unsupported_without_calling_runner(tmp_path):
    called = []

    async def runner(job):
        called.append(job)
        yield "done", {}

    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store, runner)) as client:
        assert client.get("/api/mobile/capabilities").json()["steer"] is False
        response = client.post("/api/mobile/messages", json=request_body(delivery="steer"))
        assert response.status_code == 501
        assert response.json()["state"] == "unsupported"
        assert response.json()["error"] == "steer_unsupported"
        assert client.post("/api/mobile/messages", json=request_body(delivery="steer")).json()["id"] == response.json()["id"]
        assert not called


def test_disconnecting_sse_listener_does_not_cancel_host_owned_turn(tmp_path):
    async def scenario():
        release = asyncio.Event()
        started = asyncio.Event()
        cleaned = asyncio.Event()

        async def runner(job):
            try:
                yield "delta", {"type": "delta", "chunk": "still running"}
                started.set()
                await release.wait()
                yield "done", {"reply": "finished"}
            finally:
                cleaned.set()

        store = MobileStore(tmp_path / "mobile.db")
        app = make_app(store, runner)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                job = (await client.post("/api/mobile/messages", json=request_body())).json()
                await asyncio.wait_for(started.wait(), 1)
                disconnected = asyncio.Event()
                bodies = []
                sent_request = False

                async def receive():
                    nonlocal sent_request
                    if not sent_request:
                        sent_request = True
                        return {"type": "http.request", "body": b"", "more_body": False}
                    await disconnected.wait()
                    return {"type": "http.disconnect"}

                async def send(message):
                    if message["type"] == "http.response.body":
                        bodies.append(message.get("body", b""))
                        if b"event: delta" in message.get("body", b""):
                            disconnected.set()

                path = f"/api/mobile/messages/{job['id']}/events"
                scope = {"type": "http", "asgi": {"version": "3.0", "spec_version": "2.3"},
                         "http_version": "1.1", "method": "GET", "scheme": "http", "path": path,
                         "raw_path": path.encode(), "query_string": b"", "headers": [],
                         "client": ("127.0.0.1", 1), "server": ("test", 80), "root_path": ""}
                await asyncio.wait_for(app(scope, receive, send), 1)
                assert any(b"event: delta" in body for body in bodies)
                assert store.get("", job["id"])["state"] == "running"
                assert not cleaned.is_set()
                release.set()
                await asyncio.wait_for(cleaned.wait(), 1)
                for _ in range(100):
                    if store.get("", job["id"])["state"] == "completed":
                        break
                    await asyncio.sleep(0.005)
                assert store.get("", job["id"])["state"] == "completed"
                replay = await client.get(path)
                assert "event: done" in replay.text

    asyncio.run(scenario())


def test_runtime_shutdown_interrupts_and_retains_queue(tmp_path):
    async def scenario():
        store = MobileStore(tmp_path / "mobile.db")
        started = asyncio.Event()

        async def runner(job):
            started.set()
            await asyncio.Event().wait()
            yield "done", {}

        first = store.submit("", {**request_body("first"), "attachments": [], "model": "", "reasoning_effort": "none", "delivery": "queue", "scheduled_at": None})
        second = store.submit("", {**request_body("second"), "attachments": [], "model": "", "reasoning_effort": "none", "delivery": "queue", "scheduled_at": None})
        runtime = MobileRuntime(store, runner, scan_interval=0.01)
        await runtime.start()
        await asyncio.wait_for(started.wait(), 1)
        await runtime.close()
        assert store.get("", first["id"])["state"] == "interrupted"
        assert store.get("", second["id"])["state"] == "queued"
        assert store.claim_next() is None
        assert not runtime.tasks

    asyncio.run(scenario())


def test_queue_waits_for_iterator_cleanup_and_cleanup_failure_pauses(tmp_path):
    async def scenario():
        store = MobileStore(tmp_path / "mobile.db")
        cleanup_started = asyncio.Event()
        release_cleanup = asyncio.Event()
        starts = []

        class Iterator:
            def __init__(self, job):
                self.job = job
                self.sent = False
                starts.append(job["client_id"])

            def __aiter__(self):
                return self

            async def __anext__(self):
                if self.sent:
                    raise StopAsyncIteration
                self.sent = True
                return "done", {"reply": "done"}

            async def aclose(self):
                cleanup_started.set()
                await release_cleanup.wait()
                raise RuntimeError("cleanup failed")

        common = {"attachments": [], "model": "", "reasoning_effort": "none", "delivery": "queue", "scheduled_at": None}
        first = store.submit("", {**request_body("first"), **common})
        second = store.submit("", {**request_body("second"), **common})
        runtime = MobileRuntime(store, Iterator, scan_interval=0.005)
        await runtime.start()
        try:
            await asyncio.wait_for(cleanup_started.wait(), 1)
            await asyncio.sleep(0.025)
            assert starts == ["first"]
            assert store.get("", first["id"])["state"] == "running"
            release_cleanup.set()
            for _ in range(100):
                if store.get("", first["id"])["state"] == "failed":
                    break
                await asyncio.sleep(0.005)
            assert store.get("", first["id"])["error"] == "turn_cleanup_failed"
            assert store.get("", second["id"])["state"] == "queued"
            assert store.claim_next() is None
        finally:
            release_cleanup.set()
            await runtime.close()

    asyncio.run(scenario())


def test_task_local_model_effort_options_do_not_leak_between_conversations(tmp_path):
    async def scenario():
        both_started = asyncio.Event()
        observed = []

        async def runner(job):
            options = current_turn_options()
            assert options["model"] == job["model"]
            observed.append(options)
            options["model"] = "cannot mutate task context"
            assert current_turn_options()["model"] == job["model"]
            if len(observed) == 2:
                both_started.set()
            await both_started.wait()
            assert current_turn_options()["model"] == job["model"]
            assert current_turn_options()["reasoning_effort"] == job["reasoning_effort"]
            yield "done", {"reply": "done"}

        store = MobileStore(tmp_path / "mobile.db")
        common = {"attachments": [], "delivery": "queue", "scheduled_at": None}
        jobs = [store.submit("owner", {**request_body(str(i), session_id=f"chat-{i}"), **common,
                "model": f"provider/model-{i}", "reasoning_effort": effort}) for i, effort in enumerate(["high", "xhigh"])]
        runtime = MobileRuntime(store, runner, scan_interval=0.005)
        await runtime.start()
        try:
            await asyncio.wait_for(both_started.wait(), 1)
            for _ in range(100):
                if all(store.get("owner", job["id"])["state"] == "completed" for job in jobs):
                    break
                await asyncio.sleep(0.005)
            assert all(store.get("owner", job["id"])["state"] == "completed" for job in jobs)
            assert current_turn_options() is None
        finally:
            await runtime.close()

    asyncio.run(scenario())


def test_edit_and_regenerate_fork_shared_history_without_changing_source(tmp_path, monkeypatch):
    import brain_context
    import chat_store

    # Authenticated owners use a different root than CHATS_DIR; isolate both.
    monkeypatch.setattr(chat_store, "USER_DATA_DIR", tmp_path / "users")
    monkeypatch.setattr(brain_context, "USER_DATA_DIR", tmp_path / "users")
    with brain_context.set_clerk_user("alice"):
        first_ids = chat_store.append("source", "First question", "First answer")
        target_ids = chat_store.append("source", "Old question", "Old answer", attachments=[{"url": "/uploads/document.txt", "name": "document.txt"}])
        chat_store.append("source", "Later question", "Later answer")
        source_path = chat_store._path("source")
        original = source_path.read_bytes()
    seen = []

    async def runner(job):
        seen.append(job)
        assert current_turn_options()["seed_history"] is True
        assert job["history"] == [{"role": "user", "content": "First question"}, {"role": "assistant", "content": "First answer"}]
        # Use the real shared history store but a fake answer; no provider calls.
        with brain_context.set_clerk_user(job["user_id"]):
            chat_store.append(job["session_id"], job["message"], "New answer", attachments=job["attachments"])
        yield "done", {"reply": "New answer", "session_id": job["session_id"]}

    store = MobileStore(tmp_path / "mobile.db")
    headers = {"x-owner": "alice"}
    with TestClient(make_app(store, runner)) as client:
        edited = client.post("/api/mobile/sessions/source/fork", json={"client_id": "edited", "message_id": target_ids[0],
                             "action": "edit", "message": "Edited question", "model": "openai/example"}, headers=headers)
        assert edited.status_code == 200
        edit = edited.json()
        assert edit["session_id"] != "source"
        assert edit["message"] == "Edited question"
        assert edit["fork"] == {"source_session_id": "source", "message_id": target_ids[0], "action": "edit"}
        wait_state(store, "alice", edit["id"], "completed")
        regenerated = client.post("/api/mobile/sessions/source/fork", json={"client_id": "regenerated", "message_id": target_ids[1],
                                  "action": "regenerate"}, headers=headers).json()
        wait_state(store, "alice", regenerated["id"], "completed")
        with brain_context.set_clerk_user("alice"):
            edited_history = chat_store.load(edit["session_id"])
            regenerated_history = chat_store.load(regenerated["session_id"])
            assert [message["content"] for message in edited_history["messages"]] == ["First question", "First answer", "Edited question", "New answer"]
            assert [message["content"] for message in regenerated_history["messages"]] == ["First question", "First answer", "Old question", "New answer"]
            assert edited_history["messages"][0]["id"] == first_ids[0]
            assert regenerated_history["messages"][2]["attachments"] == [{"url": "/uploads/document.txt", "name": "document.txt"}]
            assert source_path.read_bytes() == original
        assert seen[0]["model"] == "openai/example"
        assert client.get("/api/mobile/capabilities", headers=headers).json()["history_fork"] is True


def test_fork_retries_survive_source_changes_or_deletion_and_are_owner_scoped(tmp_path, monkeypatch):
    import brain_context
    import chat_store

    monkeypatch.setattr(chat_store, "USER_DATA_DIR", tmp_path / "users")
    monkeypatch.setattr(brain_context, "USER_DATA_DIR", tmp_path / "users")
    with brain_context.set_clerk_user("alice"):
        ids = chat_store.append("source", "Question", "Answer")
    store = MobileStore(tmp_path / "mobile.db")
    headers = {"x-owner": "alice"}
    body = {"client_id": "fork-once", "message_id": ids[1], "action": "regenerate"}
    with TestClient(make_app(store)) as client:
        assert client.post("/api/mobile/sessions/source/fork", json=body, headers={"x-owner": "bob"}).status_code == 404
        first = client.post("/api/mobile/sessions/source/fork", json=body, headers=headers).json()
        wait_state(store, "alice", first["id"], "completed")
        with brain_context.set_clerk_user("alice"):
            chat_store.append("source", "Later", "Response")
        retry = client.post("/api/mobile/sessions/source/fork", json=body, headers=headers).json()
        assert retry["id"] == first["id"]
        with brain_context.set_clerk_user("alice"):
            chat_store.delete("source")
        assert client.post("/api/mobile/sessions/source/fork", json=body, headers=headers).json()["id"] == first["id"]
        assert client.post("/api/mobile/sessions/source/fork", json={**body, "model": "different"}, headers=headers).status_code == 409
        assert len(store.messages("alice")) == 1


def test_fork_target_roles_and_regeneration_overrides_are_checked(tmp_path):
    import chat_store

    ids = chat_store.append("source", "Question", "Answer")
    store = MobileStore(tmp_path / "mobile.db")
    with TestClient(make_app(store)) as client:
        for body in [
            {"client_id": "wrong-role", "message_id": ids[1], "action": "edit", "message": "Edited"},
            {"client_id": "other-role", "message_id": ids[0], "action": "regenerate"},
            {"client_id": "no-text", "message_id": ids[0], "action": "edit"},
            {"client_id": "override", "message_id": ids[1], "action": "regenerate", "message": "Replacement"},
        ]:
            assert client.post("/api/mobile/sessions/source/fork", json=body).status_code == 422
        assert store.messages("") == []


def test_fork_materialization_never_overwrites_existing_destination(tmp_path):
    import chat_store

    chat_store.append("destination", "Existing question", "Existing answer")
    original = chat_store._path("destination").read_bytes()
    job = {"id": "new-job", "user_id": "", "session_id": "destination", "fork": {
        "source_session_id": "source", "message_id": "target", "action": "edit", "title": "", "project": "",
        "messages": [], "participants": [], "participant_name": ""}}
    from mobile_store import StoreError
    with pytest.raises(StoreError, match="fork_destination_exists"):
        materialize_fork(job, created_at=1)
    assert chat_store._path("destination").read_bytes() == original


def test_existing_chat_response_parser_preserves_fragmented_unicode_payload_and_closes():
    async def scenario():
        closed = []
        original = {"type": "delta", "chunk": "Unicode ✓ and newline\n"}
        raw = ("event: delta\r\ndata: " + json.dumps(original, ensure_ascii=False) + "\r\n\r\n"
               + "event: done\ndata: {\"reply\":\"done\"}\n\n" + "event: error\ndata: {\"partial\":true}").encode()

        async def chunks():
            try:
                for value in raw:
                    yield bytes([value])
            finally:
                closed.append(True)

        frames = [event async for event in iter_chat_events(SimpleNamespace(body_iterator=chunks()))]
        assert frames == [("delta", original), ("done", {"reply": "done"})]
        assert closed == [True]

    asyncio.run(scenario())


def test_existing_chat_response_parser_closes_underlying_iterator_on_cancel():
    async def scenario():
        closed = asyncio.Event()
        running = asyncio.Event()

        async def chunks():
            try:
                yield 'event: delta\ndata: {"chunk":"working"}\n\n'
                running.set()
                await asyncio.Event().wait()
            finally:
                closed.set()

        async def consume():
            async for _ in iter_chat_events(SimpleNamespace(body_iterator=chunks())):
                pass

        task = asyncio.create_task(consume())
        await asyncio.wait_for(running.wait(), 1)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        assert closed.is_set()

    asyncio.run(scenario())


def test_public_router_resources_share_store_runtime_auth_and_job_queue(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    mobile_routes = router(owner, operator, immediate, store=store, scan_interval=0.01)
    assert mobile_routes.store is store
    assert mobile_routes.runtime.store is store
    app = FastAPI()
    app.include_router(mobile_routes)

    @app.get("/sibling/owner")
    async def sibling_owner(request: Request):
        return {"user_id": await mobile_routes.identity(request)}

    device = store.exchange(store.create_pairing("alice")["code"], "Phone", "android")
    with TestClient(app) as client:
        assert client.get("/sibling/owner", headers={"authorization": "Bearer " + device["token"]}).json() == {"user_id": "alice"}
        assert client.get("/sibling/owner", headers={"authorization": "Bearer cbm_invalid"}).status_code == 401
        body = {**request_body("sibling"), "attachments": [], "model": "", "reasoning_effort": "none", "delivery": "queue", "scheduled_at": None}
        # Equivalent to a sibling endpoint committing through the public API.
        job = client.portal.call(lambda: mobile_routes.runtime.submit("alice", body))
        wait_state(store, "alice", job["id"], "completed")
        listed = client.get("/api/mobile/messages", headers={"x-owner": "alice"}).json()["messages"]
        assert len(listed) == 1 and listed[0]["id"] == job["id"]
    assert mobile_routes.runtime.scheduler is None


def test_fork_public_schema_matches_documented_client_contract(tmp_path):
    app = make_app(MobileStore(tmp_path / "mobile.db"))
    schema = app.openapi()
    assert "/api/mobile/sessions/{source_id}/fork" in schema["paths"]
    fields = schema["components"]["schemas"]["ForkRequest"]
    assert set(fields["required"]) == {"client_id", "message_id", "action"}
    assert set(fields["properties"]) == {"client_id", "message_id", "action", "message", "attachments", "model", "reasoning_effort"}
    assert fields["properties"]["action"]["enum"] == ["edit", "regenerate"]
    assert fields["additionalProperties"] is False
