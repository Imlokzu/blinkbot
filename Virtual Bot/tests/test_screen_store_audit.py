"""Package regressions from the October 8 audit use only synthetic installs."""
import io
import json
import shutil
from pathlib import Path
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


def test_retired_builtin_cannot_be_replaced_by_imported_scripts(shelf):
    source = shelf / "packages/fixture"
    source.mkdir(parents=True)
    source.joinpath("package.json").write_text(json.dumps({"id": "fixture", "type": "app"}))
    source.joinpath("index.html").write_text("<p>Trusted</p>")
    store.install("fixture")
    store._remove_installed("fixture", "app")
    shutil.rmtree(source)
    with pytest.raises(store.StoreError) as error:
        store.import_archive(bundle())
    assert error.value.code == "id_taken"
    assert not shelf.joinpath("installed/apps/fixture").exists()


def test_newline_manifest_id_cannot_alias_a_trusted_install(shelf):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("package.json", json.dumps({"id": "fixture\n", "type": "app"}))
        archive.writestr("index.html", "<p>Untrusted</p>")
    with pytest.raises(store.StoreError) as error:
        store.import_archive(buffer.getvalue(), install_now=True)
    assert error.value.code == "bad_manifest"
    # A leftover noncanonical directory from an older installation is inert.
    orphan = shelf / "installed/apps/fixture\n"
    orphan.mkdir(parents=True)
    (orphan / "index.html").write_text("<p>Untrusted</p>")
    from main import app
    assert TestClient(app).get("/store-apps/fixture%0A/index.html").status_code == 404
    with pytest.raises(store.StoreError) as invalid:
        store.install("fixture\n")
    assert invalid.value.code == "invalid_id"
    assert store.shared_app_csp("fixture\n/index.html") == store.SHARED_APP_CSP


def test_removal_cleans_both_runtime_kinds(shelf):
    store.import_archive(bundle(), install_now=True)
    stale = shelf / "installed/skins/fixture.json"
    stale.parent.mkdir(parents=True)
    stale.write_text("{}")
    store.remove_shared("fixture")
    assert not stale.exists()
    assert not shelf.joinpath("installed/apps/fixture").exists()


def test_failed_source_promotion_restores_previous_bytes(shelf, monkeypatch):
    store.import_archive(bundle(index="unused"), install_now=True)
    before = store.pack("fixture")[1]
    rename = Path.rename
    def fail_promotion(path, target):
        if path.name.startswith(".fixture-") and path.name != ".fixture-old" and Path(target).name == "fixture":
            raise OSError("fixture promotion failure")
        return rename(path, target)
    with monkeypatch.context() as patch:
        patch.setattr(Path, "rename", fail_promotion)
        with pytest.raises(store.StoreError) as error:
            store.import_archive(bundle(version="2"))
    assert error.value.code == "io_error"
    assert store.pack("fixture")[1] == before
    assert not shelf.joinpath("shared/.fixture-old").exists()
    assert store.is_installed("fixture")


def test_failed_rollback_keeps_recoverable_old_source(shelf, monkeypatch):
    store.import_archive(bundle())
    before = store.pack("fixture")[1]
    rename = Path.rename
    def fail_restore(path, target):
        if Path(target).name == "fixture":
            raise OSError("fixture rename failure")
        return rename(path, target)
    with monkeypatch.context() as patch:
        patch.setattr(Path, "rename", fail_restore)
        with pytest.raises(store.StoreError) as error:
            store.import_archive(bundle(version="2"))
        assert error.value.code == "io_error"
        assert shelf.joinpath("shared/.fixture-old/index.html").is_file()
        assert shelf.joinpath("shared/.fixture-import.json").is_file()
    # A new lookup is the restart recovery boundary, using only disk state.
    assert store.pack("fixture")[1] == before


@pytest.mark.parametrize("phase", ["staged", "backed_up", "promoted"])
def test_catalog_recovers_interrupted_import_boundaries(shelf, phase):
    store.import_archive(bundle())
    base = shelf / "shared"
    staging = base / ".fixture-staging"
    staging.mkdir()
    (staging / "package.json").write_text(json.dumps({"id": "fixture", "type": "app", "version": "2"}))
    (staging / "index.html").write_text("<p>New generation</p>")
    (base / ".fixture-import.json").write_text(json.dumps({"id": "fixture", "staging": staging.name}))
    if phase != "staged":
        (base / "fixture").rename(base / ".fixture-old")
    if phase == "promoted":
        staging.rename(base / "fixture")
    manifest = next(item for item in store.catalog()["packages"] if item["id"] == "fixture")
    assert manifest["version"] == ("2" if phase == "promoted" else "1")
    assert not (base / ".fixture-import.json").exists()
    assert not (base / ".fixture-old").exists()


def test_unrecorded_recovery_bytes_are_not_deleted(shelf):
    store.import_archive(bundle())
    backup = shelf / "shared/.fixture-old"
    backup.mkdir()
    (backup / "recovery.txt").write_text("Preserve me")
    with pytest.raises(store.StoreError) as error:
        store.import_archive(bundle(version="2"))
    assert error.value.code == "io_error"
    assert (backup / "recovery.txt").read_text() == "Preserve me"
