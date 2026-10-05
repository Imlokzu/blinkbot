"""Static previews keep the existing owner/session boundary without a server."""

import json
import os
from types import SimpleNamespace

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import pytest

import brain_context
import mobile_api
from mobile_store import MobileStore
import mobile_web_preview
import mobile_workspace
import workspace


@pytest.fixture
def host(tmp_path, monkeypatch):
    monkeypatch.setattr(workspace, "WORKSPACE_DIR", tmp_path / "workspace")
    monkeypatch.setattr(workspace, "USER_DATA_DIR", tmp_path / "users")
    store = MobileStore(tmp_path / "mobile.sqlite3")
    devices = {}
    for owner in ("alice", "bob", ""):
        pairing = store.create_pairing(owner)
        devices[owner] = store.exchange(pairing["code"], "Fixture phone", "android")
    calls = []

    async def reject(*_args):
        raise HTTPException(401, {"code": "unauthorized"})

    async def unused_turn(_job):
        raise AssertionError("Preview reads must never submit agent jobs")
        yield  # Keep the real runner's async iterator shape.

    mobile = mobile_api.router(reject, reject, unused_turn, store=store)

    def resolve(path, *, must_exist):
        calls.append((brain_context.get_active_clerk_user(), workspace.active_session_slug()))
        return workspace._resolve(path, must_exist=must_exist)

    app = FastAPI()
    app.include_router(mobile_web_preview.router(mobile.identity, resolve_file=resolve))

    def seed(content=b"<html>app</html>", path="session/app/index.html", owner="alice", session="chat_1"):
        with brain_context.set_clerk_user(owner), workspace.set_session(session):
            target = workspace._resolve(path)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            return target

    with TestClient(app) as client:
        def get(route, params, owner="alice", session="chat_1", headers=None):
            if headers is None:
                headers = {"Authorization": "Bearer " + devices[owner]["token"]}
            return client.get("/api/mobile/workspace/" + route,
                              params={**params, "session_id": session}, headers=headers)

        yield SimpleNamespace(client=client, seed=seed, get=get, calls=calls, tmp=tmp_path,
                              devices=devices, store=store)


def preview(host, path="session/app", **kwargs):
    return host.get("web-preview", {"path": path}, **kwargs)


def resource(host, path="index.html", root="sessions/chat_1/app", entry="index.html", **kwargs):
    return host.get("web-resource", {"root": root, "path": path, "entry": entry}, **kwargs)


def package(host, manifest=None, **kwargs):
    manifest = manifest if manifest is not None else {"devDependencies": {"vite": "*"}, "dependencies": {"react": "*"}}
    return host.seed(json.dumps(manifest).encode(), path="session/app/package.json", **kwargs)


@pytest.mark.parametrize("path", ["session/app", "session/app/", "session/app/index.html", "./session//app/./index.html"])
def test_canonical_descriptor_and_original_html(host, path):
    html = b"\xef\xbb\xbf<html>\r\n<script>window.fixture = true</script></html>"
    host.seed(html)
    response = preview(host, path)
    assert response.status_code == 200
    assert response.json() == {"ready": True, "root": "sessions/chat_1/app", "entry": "index.html",
                               "project_path": "sessions/chat_1/app", "kind": "web", "buildable": False}
    assert resource(host).content == html


def test_named_standalone_html_and_encoded_unicode_paths(host):
    host.seed(b"standalone", "session/my app/caf\u00e9 + game.htm")
    response = preview(host, "session/my app/caf\u00e9 + game.htm")
    assert response.status_code == 200
    assert response.json()["entry"] == "caf\u00e9 + game.htm"
    response = resource(host, root="sessions/chat_1/my app", path="caf\u00e9 + game.htm", entry="caf\u00e9 + game.htm")
    assert response.content == b"standalone"
    assert response.headers["content-type"].startswith("text/html")


@pytest.mark.parametrize("manifest", [
    {"devDependencies": {"vite": "*"}}, {"dependencies": {"react": "*"}},
    {"peerDependencies": {"react-dom": "*"}}, {"scripts": {"build": "npx vite build"}},
    {"scripts": {"start": "react-scripts start"}},
])
def test_unbuilt_projects_are_described_but_never_served(host, manifest):
    package(host, manifest)
    host.seed(b'<script type="module" src="/src/main.tsx"></script>')
    host.seed(b"export default () => <div />", "session/app/src/main.tsx")
    for path in ("session/app", "session/app/index.html"):
        response = preview(host, path)
        assert response.status_code == 200
        assert response.json() == {"ready": False, "reason": "build_required",
                                   "project_path": "sessions/chat_1/app", "root": "sessions/chat_1/app",
                                   "kind": "web", "buildable": True}
    assert resource(host).status_code == 409
    assert resource(host, path="src/main.tsx").status_code == 403


@pytest.mark.parametrize("build_dir", ["dist", "build"])
def test_built_project_replaces_source_entry_and_preserves_project(host, build_dir):
    package(host)
    host.seed(b"unbuilt source")
    host.seed(b"built html", f"session/app/{build_dir}/index.html")
    host.seed(b"export default 1;", f"session/app/{build_dir}/assets/main.js")
    for path in ("session/app", "session/app/index.html", f"session/app/{build_dir}", f"session/app/{build_dir}/index.html"):
        response = preview(host, path)
        assert response.status_code == 200
        assert response.json()["root"] == f"sessions/chat_1/app/{build_dir}"
        assert response.json()["project_path"] == "sessions/chat_1/app"
        assert response.json()["buildable"] is True
    assert resource(host, root=f"sessions/chat_1/app/{build_dir}").content == b"built html"
    assert resource(host, root=f"sessions/chat_1/app/{build_dir}", path="assets/main.js").content == b"export default 1;"
    assert resource(host).status_code == 400


def test_dist_precedes_build_and_plain_folder_supports_build_output(host):
    host.seed(b"plain")
    host.seed(b"build", "session/app/build/index.html")
    host.seed(b"dist", "session/app/dist/index.html")
    assert preview(host).json()["root"] == "sessions/chat_1/app/dist"
    package(host)
    assert preview(host).json()["root"] == "sessions/chat_1/app/dist"


def test_directory_indexes_and_spa_routes_do_not_mask_missing_assets(host):
    host.seed(b"spa")
    host.seed(b"directory", "session/app/docs/index.html")
    host.seed(b"legacy directory", "session/app/legacy/index.htm")
    host.seed(b"never reveal an extensionless file", "session/app/dashboard")
    for path in ("", "dashboard", "settings/profile", "settings/profile/"):
        response = resource(host, path=path)
        assert response.status_code == 200
        assert response.content == b"spa"
        assert response.headers["content-type"].startswith("text/html")
    assert resource(host, path="docs/").content == b"directory"
    assert resource(host, path="legacy").content == b"legacy directory"
    for path in ("missing.js", "missing.css", "assets/missing.json", "missing.html"):
        assert resource(host, path=path).status_code == 404


@pytest.mark.parametrize("name,mime", [
    ("main.js", "text/javascript"), ("main.mjs", "text/javascript"),
    ("main.css", "text/css"), ("data.json", "application/json"),
    ("app.webmanifest", "application/manifest+json"),
    ("a.png", "image/png"), ("a.jpg", "image/jpeg"), ("a.jpeg", "image/jpeg"),
    ("a.gif", "image/gif"), ("a.webp", "image/webp"), ("a.avif", "image/avif"),
    ("a.svg", "image/svg+xml"), ("a.ico", "image/x-icon"), ("a.bmp", "image/bmp"),
    ("a.woff", "font/woff"), ("a.woff2", "font/woff2"), ("a.ttf", "font/ttf"),
    ("a.otf", "font/otf"), ("a.eot", "application/vnd.ms-fontobject"),
    ("a.wasm", "application/wasm"),
])
def test_asset_mime_types_preserve_original_bytes(host, name, mime):
    host.seed()
    payload = b"\x00\xff\r\noriginal bytes"
    host.seed(payload, "session/app/assets/" + name)
    response = resource(host, "assets/" + name)
    assert response.status_code == 200
    assert response.content == payload
    assert response.headers["content-length"] == str(len(payload))
    assert response.headers["content-type"].split(";")[0] == mime


def test_owner_session_and_local_owner_use_existing_namespace(host):
    host.seed(b"alice")
    host.seed(b"bob", owner="bob")
    host.seed(b"local owner", owner="")
    host.seed(b"second chat", session="chat_2")
    assert resource(host).content == b"alice"
    assert resource(host, owner="bob").content == b"bob"
    assert resource(host, owner="").content == b"local owner"
    assert resource(host, root="session/app", session="chat_2").content == b"second chat"
    assert preview(host, session="chat_2").json()["root"] == "sessions/chat_2/app"
    assert preview(host, session="missing").status_code == 404
    assert {(owner, session) for owner, session in host.calls} >= {
        ("alice", "chat_1"), ("alice", "chat_2"), ("bob", "chat_1"), ("", "chat_1"),
    }
    assert brain_context.get_active_clerk_user() is None
    assert workspace.active_session_slug() == "default"


@pytest.mark.parametrize("session", ["config", "src", "source", "sources", "private", "secrets", "node_modules", "CONFIG"])
@pytest.mark.parametrize("built", [False, True])
def test_reserved_session_slugs_round_trip_metadata_and_resources(host, session, built):
    host.seed(b"<html>source</html>", session=session)
    output = "session/app/dist" if built else "session/app"
    if built:
        package(host, session=session)
    html = b"<html>preview</html>"
    host.seed(html, output + "/index.html", session=session)
    host.seed(b"export default 1;\r\n", output + "/assets/app.js", session=session)
    host.seed(b"body {}\r\n", output + "/assets/app.css", session=session)
    root = f"sessions/{session}/app" + ("/dist" if built else "")
    for path in ("session/app", f"sessions/{session}/app"):
        response = preview(host, path, session=session)
        assert response.status_code == 200
        assert response.json() == {
            "ready": True, "root": root, "entry": "index.html",
            "project_path": f"sessions/{session}/app", "kind": "web", "buildable": built,
        }
    for path, content in (("index.html", html), ("assets/app.js", b"export default 1;\r\n"),
                          ("assets/app.css", b"body {}\r\n")):
        response = resource(host, path, root=root, session=session)
        assert response.status_code == 200
        assert response.content == content
    assert resource(host, root=root, session=session, owner="bob").status_code == 404
    # Canonical paths already select a session in the owner's workspace; they
    # need no matching session alias, just as the existing resolver specifies.
    assert resource(host, root=root, session="another_chat").content == html


@pytest.mark.parametrize("blocked", ["config", "src", "source", "sources", "private", "secrets", "node_modules"])
def test_session_exemption_never_applies_to_project_or_asset_directories(host, blocked):
    host.seed(session="config")
    root = "sessions/config/app"
    for path in (f"session/{blocked}", f"sessions/config/{blocked}", f"sessions/config/app/{blocked}"):
        assert preview(host, path, session="config").status_code == 403
        assert resource(host, root=path, session="config").status_code == 403
    for prefix in (blocked, f"sessions/{blocked}", f"assets/sessions/{blocked}"):
        host.seed(b"private", "session/app/" + prefix + "/data.json", session="config")
        assert resource(host, prefix + "/data.json", root=root, session="config").status_code == 403
        assert resource(host, root=root, entry=prefix + "/index.html", session="config").status_code == 403
    # A project-owned sessions/ directory is not the workspace namespace.
    assert preview(host, f"session/app/sessions/{blocked}", session="config").status_code == 403


def test_missing_and_revoked_credentials_fail_before_workspace_access(host):
    host.seed()
    for headers in ({}, {"Authorization": "Bearer cbm_invalid"}):
        assert preview(host, headers=headers).status_code == 401
        assert resource(host, headers=headers).status_code == 401
    host.store.revoke("alice", host.devices["alice"]["device_id"])
    assert preview(host).status_code == 401
    assert resource(host).status_code == 401
    assert host.calls == []


@pytest.mark.parametrize("value", [
    "../secret", "app/../../secret", "/etc/passwd", "//outside.example/a",
    "https://outside.example/a", "file:///a", "C:/a", "a\\b", "a\x00b",
    "a\nb", " a", "a ", "a\tb", "%2e%2e/secret", "%252e%252e/secret", "a%2fb",
    "a?token=x", "a#fragment", ".git/config", "a/.env", "a/.hidden/b",
])
def test_unsafe_paths_rejected_for_every_input(host, value):
    host.seed()
    assert preview(host, value).status_code == 400
    assert resource(host, root=value).status_code == 400
    assert resource(host, path=value).status_code == 400
    assert resource(host, entry=value).status_code == 400


def test_asgi_decodes_encoded_traversal_and_separators_once(host):
    host.seed()
    headers = {"Authorization": "Bearer " + host.devices["alice"]["token"]}
    for encoded in ("%2e%2e%2fsecret", "%252e%252e%252fsecret", "session%5capp", "%2fetc%2fpasswd"):
        response = host.client.get("/api/mobile/workspace/web-preview?path=" + encoded, headers=headers)
        assert response.status_code == 400


@pytest.mark.parametrize("session", ["../chat", "chat/other", "chat space", "chat\n", "x" * 65])
def test_invalid_session_is_not_silently_hashed(host, session):
    assert preview(host, session=session).status_code == 422
    assert resource(host, session=session).status_code == 422
    assert host.calls == []


@pytest.mark.parametrize("path", [
    "package.json", "package-lock.json", "tsconfig.app.json", "vite.config.js",
    "config.json", "credentials.json", "secrets.json", "service-account.json",
    "src/main.js", "source/app.css", "node_modules/lib/index.js", "private/data.json",
    "config/settings.js", "app.ts", "app.tsx", "app.jsx", "main.js.map", "notes.md", "secret.pem",
])
def test_sources_and_secret_files_are_not_public_assets(host, path):
    host.seed()
    assert resource(host, path).status_code == 403


def test_workspace_root_page_can_only_read_itself_and_assets(host):
    host.seed(b"home", "home.html")
    host.seed(b"asset", "assets/main.js")
    host.seed(b"private json", "notes/data.json")
    host.seed(b"another page", "other.html")
    descriptor = preview(host, "home.html").json()
    assert descriptor["root"] == descriptor["project_path"] == ""
    assert resource(host, root="", entry="home.html", path="").content == b"home"
    assert resource(host, root="", entry="home.html", path="home.html").content == b"home"
    assert resource(host, root="", entry="home.html", path="assets/main.js").content == b"asset"
    for path in ("notes/data.json", "other.html", "settings", "assets/../other.html"):
        assert resource(host, root="", entry="home.html", path=path).status_code in {400, 403}
    assert resource(host, root="", entry="notes/private.html").status_code == 403


@pytest.mark.parametrize("outside", [False, True])
@pytest.mark.parametrize("component", ["entry", "root", "asset"])
def test_symlinks_are_rejected_even_when_they_stay_in_workspace(host, outside, component):
    index = host.seed()
    other = host.tmp / "external" / "index.html" if outside else host.seed(b"other", "session/other/index.html")
    other.parent.mkdir(parents=True, exist_ok=True)
    other.write_bytes(b"other")
    if component == "entry":
        index.unlink()
        index.symlink_to(other)
        assert preview(host).status_code == 400
        assert resource(host).status_code == 400
    elif component == "root":
        index.unlink()
        index.parent.rmdir()
        index.parent.symlink_to(other.parent, target_is_directory=True)
        assert preview(host).status_code == 400
        assert resource(host).status_code == 400
    else:
        (index.parent / "app.js").symlink_to(other)
        assert resource(host, path="app.js").status_code == 400


@pytest.mark.parametrize("ancestor", [False, True])
def test_symlink_swaps_after_resolution_cannot_escape_safe_reader(host, monkeypatch, ancestor):
    host.seed()
    asset = host.seed(b"inside", "session/app/assets/main.js")
    outside = host.tmp / "outside"
    outside.mkdir()
    (outside / "main.js").write_bytes(b"outside")
    original = mobile_workspace._read_original

    def swap(target, root):
        if target == asset:
            if ancestor:
                asset.parent.rename(asset.parent.with_name("original"))
                asset.parent.symlink_to(outside, target_is_directory=True)
            else:
                asset.unlink()
                asset.symlink_to(outside / "main.js")
        return original(target, root)

    monkeypatch.setattr(mobile_workspace, "_read_original", swap)
    assert resource(host, path="assets/main.js").status_code == 400


def test_twenty_mib_boundary_uses_the_existing_bounded_reader(host):
    host.seed()
    asset = host.seed(b"", "session/app/assets/large.wasm")
    limit = 20 * 1024 * 1024
    assert mobile_workspace.MAX_DOWNLOAD_BYTES == limit
    with asset.open("wb") as output:
        output.truncate(limit)
    response = resource(host, path="assets/large.wasm")
    assert response.status_code == 200
    assert len(response.content) == limit
    with asset.open("ab") as output:
        output.write(b"x")
    response = resource(host, path="assets/large.wasm")
    assert response.status_code == 413
    assert response.json()["detail"]["code"] == "workspace_file_too_large"


def test_oversized_entry_and_manifest_are_also_bounded(host, monkeypatch):
    monkeypatch.setattr(mobile_workspace, "MAX_DOWNLOAD_BYTES", 32)
    host.seed(b"x" * 33)
    assert preview(host).status_code == 413
    host.seed(b"entry")
    host.seed(b"x" * 33, "session/app/package.json")
    assert preview(host).status_code == 413


def test_growth_after_fstat_is_still_bounded(host, monkeypatch):
    host.seed(b"entry")
    asset = host.seed(b"12345678", "session/app/assets/growing.js")
    identity = (asset.stat().st_dev, asset.stat().st_ino)
    monkeypatch.setattr(mobile_workspace, "MAX_DOWNLOAD_BYTES", 8)
    original = os.fstat

    def grow_after_stat(descriptor):
        info = original(descriptor)
        if (info.st_dev, info.st_ino) == identity:
            with asset.open("ab") as stream:
                stream.write(b"9")
        return info

    monkeypatch.setattr(os, "fstat", grow_after_stat)
    assert resource(host, path="assets/growing.js").status_code == 413


def test_named_pipe_is_rejected_without_blocking(host):
    index = host.seed()
    os.mkfifo(index.parent / "pipe.js")
    assert resource(host, path="pipe.js").status_code == 400
    os.mkfifo(index.parent / "README")
    assert preview(host, "session/app/README").status_code == 400


@pytest.mark.parametrize("name", ["README", "notes.txt", "main.tsx", "data.bin"])
@pytest.mark.parametrize("at_root", [False, True])
def test_regular_nonhtml_metadata_falls_back_to_native_file_reader(host, monkeypatch, name, at_root):
    parent = "" if at_root else "session/app/"
    host.seed(b"native content", parent + name)
    # Metadata must not read file contents or misclassify a README as an app
    # merely because it sits beside a project manifest, even an invalid one.
    host.seed(b"invalid package", parent + "package.json")

    def no_content_reads(*_args):
        raise AssertionError("Native-file metadata must not read contents")

    monkeypatch.setattr(mobile_workspace, "_read_original", no_content_reads)
    response = preview(host, parent + name)
    expected_parent = "" if at_root else "sessions/chat_1/app"
    assert response.status_code == 200
    assert response.json() == {
        "kind": "file", "ready": False, "root": expected_parent, "entry": name,
        "project_path": expected_parent, "buildable": False,
    }


@pytest.mark.parametrize("built", [False, True])
def test_workspace_root_package_does_not_offer_an_empty_build_target(host, built):
    host.seed(b'{"devDependencies":{"vite":"*"}}', "package.json")
    host.seed(b"source", "index.html")
    if built:
        host.seed(b"built", "dist/index.html")
    response = preview(host, "index.html")
    assert response.status_code == 200
    descriptor = response.json()
    assert descriptor["kind"] == "web"
    assert descriptor["project_path"] == ""
    assert descriptor["buildable"] is False
    assert descriptor["ready"] is built
    if not built:
        assert descriptor["reason"] == "build_required"
    else:
        assert resource(host, root="dist").content == b"built"


def test_missing_invalid_and_nonregular_entries(host):
    assert preview(host).status_code == 404
    host.seed(b"source", "session/app/main.tsx")
    assert preview(host).status_code == 404
    assert preview(host, "session/app/main.tsx").json()["kind"] == "file"
    host.seed()
    host.seed(b"invalid json", "session/app/package.json")
    assert preview(host).status_code == 400


def test_security_headers_apply_to_success_auth_validation_and_missing_responses(host):
    host.seed()
    responses = [preview(host), resource(host), resource(host, path="missing.js"),
                 resource(host, path="../secret"), preview(host, headers={}), preview(host, session="../bad")]
    assert [response.status_code for response in responses] == [200, 200, 404, 400, 401, 422]
    for response in responses:
        for key, value in mobile_web_preview.HEADERS.items():
            assert response.headers[key] == value
        assert "access-control-allow-credentials" not in response.headers
    csp = resource(host).headers["content-security-policy"]
    for directive in ("script-src 'self' 'unsafe-inline'", "style-src 'self' 'unsafe-inline'",
                      "img-src 'self' data: blob:", "font-src 'self' data: blob:", "connect-src 'self'",
                      "object-src 'none'", "worker-src 'none'", "frame-src 'none'", "form-action 'none'"):
        assert directive in csp


def test_preview_routes_are_get_only(host):
    headers = {"Authorization": "Bearer " + host.devices["alice"]["token"]}
    for route in ("web-preview", "web-resource"):
        assert host.client.post("/api/mobile/workspace/" + route, headers=headers).status_code == 405
    assert host.calls == []
