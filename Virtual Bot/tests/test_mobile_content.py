"""Mobile autosave must never overwrite a newer workspace revision."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import os
from types import SimpleNamespace

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import httpx
import pytest

import brain_context
import mobile_content
import workspace


@pytest.fixture
def host(tmp_path, monkeypatch):
    # Patch both namespaces before using workspace, including the local owner.
    monkeypatch.setattr(workspace, "WORKSPACE_DIR", tmp_path / "workspace")
    monkeypatch.setattr(workspace, "USER_DATA_DIR", tmp_path / "users")
    calls = []

    async def require_user(request: Request):
        if request.headers.get("x-denied"):
            raise HTTPException(401, {"code": "unauthorized"})
        return request.headers.get("x-owner", "")

    def operation(fn, *args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except FileNotFoundError:
            raise HTTPException(404, {"code": "file_not_found"}) from None
        except (ValueError, IsADirectoryError):
            raise HTTPException(400, {"code": "invalid_file"}) from None

    async def read_file(request, *, path, session_id):
        calls.append(("read", brain_context.get_active_clerk_user(), workspace.active_session_slug()))
        return {**operation(workspace.read_file, path), "callback_field": "read"}

    async def save_file(req, request):
        calls.append(("save", brain_context.get_active_clerk_user(), workspace.active_session_slug()))
        return {**operation(workspace.write_file, req.path, req.content, append=req.append),
                "callback_field": "save"}

    def app(*, read=None, save=None, guard=None):
        result = FastAPI()
        result.include_router(mobile_content.router(
            require_user, read_file=read or read_file,
            save_file=save or save_file, write_guard=guard,
        ))
        return result

    def seed(content="original", *, owner="", session_id="", path="notes/draft.txt"):
        with brain_context.set_clerk_user(owner), workspace.set_session(session_id):
            workspace.write_file(path, content)
            return workspace._resolve(path)

    return SimpleNamespace(app=app, seed=seed, calls=calls,
                           read_file=read_file, save_file=save_file)


def read(client, *, path="notes/draft.txt", session_id="", owner=""):
    return client.get("/api/mobile/workspace/file", params={"path": path, "session_id": session_id},
                      headers={"x-owner": owner})


def save(client, revision, *, content="updated", path="notes/draft.txt", session_id="", owner="", append=False):
    return client.post("/api/mobile/workspace/file", json={
        "path": path, "content": content, "session_id": session_id,
        "append": append, "revision": revision,
    }, headers={"x-owner": owner})


def test_read_save_roundtrip_and_stale_autosave_rejection(host):
    target = host.seed()
    with TestClient(host.app()) as client:
        first = read(client)
        assert first.status_code == 200
        assert first.headers["cache-control"] == "no-store"
        payload = first.json()
        assert payload["content"] == "original"
        assert payload["path"] == "notes/draft.txt"
        assert payload["size"] == 8 and payload["binary"] is False
        assert payload["callback_field"] == "read"
        assert read(client).json()["revision"] == payload["revision"]

        written = save(client, payload["revision"])
        assert written.status_code == 200
        assert written.headers["cache-control"] == "no-store"
        assert written.json()["ok"] is True
        assert written.json()["callback_field"] == "save"
        assert written.json()["size"] == len(b"updated")
        assert written.json()["revision"] != payload["revision"]
        assert read(client).json()["revision"] == written.json()["revision"]

        stale = save(client, payload["revision"], content="lost update")
        assert stale.status_code == 409
        assert stale.json()["detail"] == {
            "code": "workspace_revision_conflict", "revision": written.json()["revision"],
        }
        assert target.read_text() == "updated"
        assert sum(call[0] == "save" for call in host.calls) == 1


def test_external_same_size_write_with_restored_mtime_invalidates_token(host):
    target = host.seed("first")
    with TestClient(host.app()) as client:
        token = read(client).json()["revision"]
        stat = target.stat()
        target.write_text("other")
        os.utime(target, ns=(stat.st_atime_ns, stat.st_mtime_ns))
        assert save(client, token).status_code == 409
        assert target.read_text() == "other"
        assert not any(call[0] == "save" for call in host.calls)


def test_absent_creation_append_and_deleted_file_conflict(host):
    with TestClient(host.app()) as client:
        assert read(client).status_code == 404
        created = save(client, "missing", content="one")
        assert created.status_code == 200
        assert save(client, "missing").status_code == 409
        appended = save(client, created.json()["revision"], content="two", append=True)
        assert appended.status_code == 200
        assert read(client).json()["content"] == "onetwo"
        assert save(client, created.json()["revision"], content="two", append=True).status_code == 409
        target = host.seed("onetwo")
        token = read(client).json()["revision"]
        target.unlink()
        deleted = save(client, token)
        assert deleted.status_code == 409
        assert deleted.json()["detail"]["revision"] == "missing"
        assert not target.exists()


def test_authenticated_owner_and_session_context_match_existing_workspace(host):
    first = host.seed("alice", owner="alice", session_id="s1", path="session/draft.txt")
    second = host.seed("bob", owner="bob", session_id="s1", path="session/draft.txt")
    with TestClient(host.app()) as client:
        alice = read(client, owner="alice", session_id="s1", path="session/draft.txt").json()
        bob = read(client, owner="bob", session_id="s1", path="session/draft.txt").json()
        assert alice["content"] == "alice" and bob["content"] == "bob"
        assert alice["path"] == bob["path"] == "sessions/s1/draft.txt"
        assert alice["revision"] != bob["revision"]
        assert save(client, alice["revision"], owner="alice", session_id="s1", path="session/draft.txt").status_code == 200
        assert first.read_text() == "updated" and second.read_text() == "bob"
        assert read(client, owner="alice", session_id="s2", path="session/draft.txt").status_code == 404
        assert ("save", "alice", "s1") in host.calls


def test_authentication_refusal_never_calls_workspace_callbacks(host):
    host.seed()
    with TestClient(host.app()) as client:
        assert client.get("/api/mobile/workspace/file?path=notes/draft.txt", headers={"x-denied": "yes"}).status_code == 401
        assert client.post("/api/mobile/workspace/file", headers={"x-denied": "yes"}, json={
            "path": "notes/draft.txt", "content": "no", "revision": "missing",
        }).status_code == 401
    assert host.calls == []


@pytest.mark.parametrize("patch", [{"revision": None}, {"revision": ""}, {"revision": 3}, {"path": ""}])
def test_invalid_write_request_never_calls_save(host, patch):
    with TestClient(host.app()) as client:
        response = client.post("/api/mobile/workspace/file", json={
            "path": "notes/draft.txt", "revision": "missing", **patch,
        })
        assert response.status_code == 422
    assert host.calls == []


def test_revision_is_required_for_every_save(host):
    target = host.seed()
    with TestClient(host.app()) as client:
        response = client.post("/api/mobile/workspace/file", json={"path": "notes/draft.txt", "content": "no"})
        assert response.status_code == 422
    assert target.read_text() == "original"


def test_existing_read_and_write_errors_are_preserved(host):
    target = host.seed()

    async def denied_save(req, request):
        raise HTTPException(409, {"code": "conversation_active"})

    with TestClient(host.app(save=denied_save)) as client:
        token = read(client).json()["revision"]
        assert read(client, path="notes/absent.txt").status_code == 404
        response = save(client, token)
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == "conversation_active"
    assert target.read_text() == "original"


def test_writer_guard_reserves_revision_check_and_entire_callback(host):
    target = host.seed()
    seen = []

    @asynccontextmanager
    async def guard(user_id, session_id, path):
        seen.append(("enter", user_id, session_id, path))
        try:
            yield
        finally:
            seen.append(("exit",))

    async def guarded_save(req, request):
        assert seen[-1][0] == "enter"
        result = await host.save_file(req, request)
        seen.append(("saved",))
        return result

    with TestClient(host.app(save=guarded_save, guard=guard)) as client:
        token = read(client).json()["revision"]
        assert save(client, token).status_code == 200
        assert seen == [("enter", "", "", "notes/draft.txt"), ("saved",), ("exit",)]
        assert save(client, token).status_code == 409
        assert seen[-2:] == [("enter", "", "", "notes/draft.txt"), ("exit",)]
    assert target.read_text() == "updated"


def test_busy_guard_refuses_write_before_save_callback(host):
    target = host.seed()

    @asynccontextmanager
    async def busy(user_id, session_id, path):
        raise HTTPException(409, {"code": "conversation_active"})
        yield  # The generator shape is the async context-manager contract.

    with TestClient(host.app(guard=busy)) as client:
        token = read(client).json()["revision"]
        response = save(client, token)
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == "conversation_active"
    assert target.read_text() == "original"
    assert not any(call[0] == "save" for call in host.calls)


def test_change_while_acquiring_writer_reservation_is_checked(host):
    target = host.seed()

    @asynccontextmanager
    async def changed(user_id, session_id, path):
        target.write_text("newer")
        yield

    with TestClient(host.app(guard=changed)) as client:
        token = read(client).json()["revision"]
        assert save(client, token).status_code == 409
    assert target.read_text() == "newer"
    assert not any(call[0] == "save" for call in host.calls)


def test_changed_read_never_pairs_old_content_with_new_token(host):
    target = host.seed()

    async def racing_read(request, *, path, session_id):
        result = await host.read_file(request, path=path, session_id=session_id)
        target.write_text("changed")
        return result

    with TestClient(host.app(read=racing_read)) as client:
        response = read(client)
        assert response.status_code == 409
        assert response.json()["detail"]["code"] == "workspace_revision_conflict"


@pytest.mark.parametrize("kind", ["binary", "large"])
def test_binary_and_oversized_read_payloads_keep_workspace_behavior(host, monkeypatch, kind):
    target = host.seed()
    if kind == "binary":
        target.write_bytes(b"\xff\xfe")
    else:
        monkeypatch.setattr(workspace, "MAX_READ_BYTES", 4)
    with TestClient(host.app()) as client:
        response = read(client)
        assert response.status_code == 200
        payload = response.json()
        assert payload["content"] == ""
        assert payload["binary"] is (kind == "binary")
        assert payload.get("too_large", False) is (kind == "large")
        assert payload["revision"].startswith("v1:")


def test_concurrent_autosaves_to_session_alias_share_one_lock(host):
    target = host.seed(session_id="s1", path="session/draft.txt")

    async def slow_save(req, request):
        # Release the loop between compare and write: a missing lock would
        # allow both saves with the same base revision to succeed.
        await asyncio.sleep(0.02)
        return await host.save_file(req, request)

    async def scenario():
        transport = httpx.ASGITransport(app=host.app(save=slow_save))
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            first = await client.get("/api/mobile/workspace/file", params={"path": "session/draft.txt", "session_id": "s1"})
            token = first.json()["revision"]

            async def write(path, content):
                return await client.post("/api/mobile/workspace/file", json={
                    "path": path, "content": content, "session_id": "s1", "revision": token,
                })

            results = await asyncio.gather(write("session/draft.txt", "first"),
                                           write("sessions/s1/draft.txt", "second"))
            assert sorted(response.status_code for response in results) == [200, 409]

    asyncio.run(scenario())
    assert target.read_text() in {"first", "second"}
    assert sum(call[0] == "save" for call in host.calls) == 1
