"""Native downloads preserve original bytes and the existing owner boundary."""

from types import SimpleNamespace

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import pytest

import brain_context
import mobile_workspace
import workspace


@pytest.fixture
def host(tmp_path, monkeypatch):
    monkeypatch.setattr(workspace, "WORKSPACE_DIR", tmp_path / "workspace")
    monkeypatch.setattr(workspace, "USER_DATA_DIR", tmp_path / "users")
    calls = []

    async def require_user(request: Request):
        owner = {"Bearer alice-device": "alice", "Bearer bob-device": "bob"}.get(request.headers.get("authorization"))
        if owner is None:
            raise HTTPException(401, {"code": "unauthorized"})
        return owner

    def resolve(path, *, must_exist):
        calls.append((brain_context.get_active_clerk_user(), workspace.active_session_slug()))
        return workspace._resolve(path, must_exist=must_exist)

    app = FastAPI()
    app.include_router(mobile_workspace.router(require_user, resolve_file=resolve))

    def seed(content, path="session/result.png", owner="alice", session="chat_1"):
        with brain_context.set_clerk_user(owner), workspace.set_session(session):
            target = workspace._resolve(path)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            return target

    return SimpleNamespace(app=app, seed=seed, calls=calls, tmp=tmp_path)


def download(client, path="session/result.png", owner="alice", session="chat_1"):
    return client.get("/api/mobile/workspace/download", params={"path": path, "session_id": session},
                      headers={"authorization": f"Bearer {owner}-device"})


@pytest.mark.parametrize("path,content,mime", [
    ("session/result.png", b"\x89PNG\r\n\x1a\n\x00\xff", "image/png"),
    ("session/result.pdf", b"%PDF-1.7\n\x00\xff", "application/pdf"),
    ("session/result.txt", b"\xef\xbb\xbfline one\r\nline two\r\n\xff", "text/plain"),
    ("session/empty.bin", b"", "application/octet-stream"),
])
def test_original_bytes_are_never_text_decoded(host, path, content, mime):
    host.seed(content, path=path)
    with TestClient(host.app) as client:
        response = download(client, path)
    assert response.status_code == 200
    assert response.content == content
    assert response.headers["content-type"].split(";")[0] == mime
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-content-type-options"] == "nosniff"
    assert response.headers["content-disposition"].startswith("attachment;")
    assert response.headers["content-length"] == str(len(content))


def test_owner_and_session_resolution_are_identical_to_workspace(host):
    host.seed(b"alice first")
    host.seed(b"bob first", owner="bob")
    host.seed(b"alice second", session="chat_2")
    with TestClient(host.app) as client:
        assert download(client).content == b"alice first"
        assert download(client, owner="bob").content == b"bob first"
        assert download(client, session="chat_2").content == b"alice second"
        assert download(client, session="missing").status_code == 404
    assert ("alice", "chat_1") in host.calls and ("bob", "chat_1") in host.calls
    assert brain_context.get_active_clerk_user() is None


def test_missing_or_revoked_credentials_never_resolve_files(host):
    host.seed(b"private")
    with TestClient(host.app) as client:
        assert client.get("/api/mobile/workspace/download?path=session/result.png").status_code == 401
        assert download(client, owner="revoked").status_code == 401
    assert host.calls == []


@pytest.mark.parametrize("path", ["../private.txt", "session/../../private.txt", "/etc/passwd", "https://outside.example/file", "session\\result.png", "session/result.png\n"])
def test_traversal_and_nonworkspace_destinations_are_rejected(host, path):
    with TestClient(host.app) as client:
        assert download(client, path).status_code == 400


def test_outside_symlink_and_directory_cannot_be_exported(host):
    outside = host.tmp / "outside.bin"
    outside.write_bytes(b"outside")
    target = host.seed(b"inside")
    target.unlink()
    target.symlink_to(outside)
    with TestClient(host.app) as client:
        assert download(client).status_code == 400
        assert download(client, "session").status_code == 400


def test_size_limit_accepts_exact_boundary_and_rejects_larger(host, monkeypatch):
    monkeypatch.setattr(mobile_workspace, "MAX_DOWNLOAD_BYTES", 8)
    target = host.seed(b"12345678")
    with TestClient(host.app) as client:
        assert download(client).content == b"12345678"
        target.write_bytes(b"123456789")
        response = download(client)
        assert response.status_code == 413
        assert response.json()["detail"]["code"] == "workspace_file_too_large"


def test_changed_target_symlink_is_not_followed(host, monkeypatch):
    target = host.seed(b"inside")
    outside = host.tmp / "outside.bin"
    outside.write_bytes(b"outside")
    read = mobile_workspace._read_original

    def replace_before_open(path, root):
        target.unlink()
        target.symlink_to(outside)
        return read(path, root)

    monkeypatch.setattr(mobile_workspace, "_read_original", replace_before_open)
    with TestClient(host.app) as client:
        assert download(client).status_code == 400


def test_changed_ancestor_symlink_is_not_followed(host, monkeypatch):
    target = host.seed(b"inside", path="notes/nested/result.bin")
    outside = host.tmp / "outside"
    outside.mkdir()
    (outside / "result.bin").write_bytes(b"outside")
    read = mobile_workspace._read_original

    def replace_ancestor_before_open(path, root):
        target.parent.rename(target.parent.with_name("original"))
        target.parent.symlink_to(outside, target_is_directory=True)
        return read(path, root)

    monkeypatch.setattr(mobile_workspace, "_read_original", replace_ancestor_before_open)
    with TestClient(host.app) as client:
        assert download(client, "notes/nested/result.bin").status_code == 400
