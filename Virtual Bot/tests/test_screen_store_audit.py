"""Package regressions from the October 8 audit use only synthetic installs."""
import io
import json
import shutil
import zipfile

import pytest
from fastapi.testclient import TestClient

import app_config
import screen_store as store


@pytest.fixture
def shelf(tmp_path, monkeypatch):
    monkeypatch.setattr(app_config, "STORE_DIR", tmp_path)
    return tmp_path


def bundle(kind="app", version="1", **files):
    manifest = {"id": "fixture", "type": kind, "version": version}
    if kind == "skin":
        manifest["vars"] = {"--bg": "#112233"}
    else:
        files.setdefault("index.html", "<p>Fixture</p>")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("package.json", json.dumps(manifest))
        for name, content in files.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def test_missing_source_keeps_direct_html_sandboxed(shelf):
    store.import_archive(bundle(), install_now=True)
    shutil.rmtree(shelf / "shared/fixture")
    from main import app
    response = TestClient(app).get("/store-apps/fixture/index.html")
    assert response.status_code == 200
    assert response.headers["content-security-policy"] == store.SHARED_APP_CSP


@pytest.mark.parametrize("first,second", [("app", "skin"), ("skin", "app")])
def test_import_cannot_change_existing_package_type(shelf, first, second):
    store.import_archive(bundle(first), install_now=True)
    with pytest.raises(store.StoreError) as error:
        store.import_archive(bundle(second))
    assert error.value.code == "bad_manifest"
    assert store.load_manifest("fixture")["type"] == first
    assert store.is_installed("fixture")


def test_builtin_trust_is_bound_to_installed_generation(shelf):
    source = shelf / "packages/fixture"
    source.mkdir(parents=True)
    source.joinpath("package.json").write_text(json.dumps({"id": "fixture", "type": "app"}))
    source.joinpath("index.html").write_text("<p>Trusted</p>")
    source.joinpath("app.js").write_text("/* trusted */")
    store.install("fixture")
    assert store.shared_app_csp("fixture/index.html") is None
    source.joinpath("index.html").write_text("<p>New source</p>")
    assert store.shared_app_csp("fixture/index.html") is None
    shelf.joinpath("installed/apps/fixture/app.js").write_text("/* changed */")
    assert store.shared_app_csp("fixture/index.html") == store.SHARED_APP_CSP
    store.install("fixture")
    assert store.shared_app_csp("fixture/index.html") is None
    shelf.joinpath("installed/receipts/fixture.json").unlink()
    assert store.shared_app_csp("fixture/index.html") == store.SHARED_APP_CSP


def test_manifest_cannot_claim_trusted_provenance(shelf):
    store.import_archive(bundle(), install_now=True)
    installed = shelf / "installed/apps/fixture/package.json"
    installed.write_text(json.dumps({"id": "fixture", "type": "app", "source": "builtin"}))
    assert store.shared_app_csp("fixture/index.html") == store.SHARED_APP_CSP


def test_removal_cleans_both_runtime_kinds(shelf):
    store.import_archive(bundle(), install_now=True)
    stale = shelf / "installed/skins/fixture.json"
    stale.parent.mkdir(parents=True)
    stale.write_text("{}")
    store.remove_shared("fixture")
    assert not stale.exists()
    assert not shelf.joinpath("installed/apps/fixture").exists()
