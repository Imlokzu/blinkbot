"""Update channels must use the selected platform's release metadata."""
from __future__ import annotations

import os

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import pytest

from mobile_api import router
from mobile_store import MobileStore


@pytest.fixture
def updates_client(tmp_path, monkeypatch):
    # Runtime updater settings are not evidence for this synthetic release matrix.
    for key in list(os.environ):
        if key.startswith("MOBILE_UPDATE_"):
            monkeypatch.delenv(key)
    monkeypatch.setenv("MOBILE_API_ORIGIN", "https://api.example.test")

    async def owner(request: Request):
        if request.headers.get("x-fixture-owner") != "alice":
            raise HTTPException(401)
        return "alice"

    async def operator(_request):
        raise HTTPException(403)

    async def unused_runner(_job):
        raise AssertionError("Update metadata never requires model work")
        yield

    app = FastAPI()
    app.include_router(router(owner, operator, unused_runner, store=MobileStore(tmp_path / "mobile.db")))
    with TestClient(app, headers={"x-fixture-owner": "alice"}) as client:
        yield client


def publish(monkeypatch, platform, channel, code):
    prefix = f"MOBILE_UPDATE_{channel.upper()}_{platform.upper()}"
    values = {
        "VERSION": f"{platform}-{channel}-{code}",
        "VERSION_CODE": str(code),
        "CHANGELOG": f"{platform} {channel} notes\n- Another change",
        "URL": f"https://releases.example.test/{platform}/{channel}",
        "SHA256": ("a" if platform == "android" else "b") * 64,
        "MANDATORY": "true" if platform == "ios" else "false",
    }
    for key, value in values.items():
        monkeypatch.setenv(f"{prefix}_{key}", value)
    return values


@pytest.mark.parametrize("platform", ["android", "ios"])
@pytest.mark.parametrize("channel", ["stable", "beta"])
def test_capabilities_keep_versions_links_notes_and_digests_platform_scoped(updates_client, monkeypatch, platform, channel):
    releases = {(kind, track): publish(monkeypatch, kind, track, code)
                for kind, track, code in [("android", "stable", 21), ("android", "beta", 22),
                                          ("ios", "stable", 8), ("ios", "beta", 9)]}
    code = int(releases[platform, channel]["VERSION_CODE"])
    response = updates_client.get(f"/api/mobile/capabilities?platform={platform}&channel={channel}&version_code={code - 1}")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    body = response.json()
    for field, track in [("update", channel), ("update_stable", "stable"), ("update_beta", "beta")]:
        release = releases[platform, track]
        metadata = body[field]
        assert metadata["version_name"] == release["VERSION"]
        assert metadata["version_code"] == int(release["VERSION_CODE"])
        assert metadata["url"] == release["URL"]
        assert metadata["changelog"] == [f"{platform} {track} notes", "Another change"]
        assert metadata["sha256"] == release["SHA256"]
        assert metadata["mandatory"] == (platform == "ios")
        assert metadata["channel"] == track
        assert metadata["ios_url"] == releases["ios", track]["URL"]
    assert body["update"]["available"] is True
    # Informational beta metadata retains its existing non-installing contract.
    assert body["update_beta"]["available"] is False
    current = updates_client.get(f"/api/mobile/capabilities?platform={platform}&channel={channel}&version_code={code}").json()
    assert current["update"]["available"] is False


@pytest.mark.parametrize("channel", ["stable", "beta"])
def test_unpublished_ios_never_borrows_an_android_release(updates_client, monkeypatch, channel):
    publish(monkeypatch, "android", "stable", 21)
    publish(monkeypatch, "android", "beta", 22)
    result = updates_client.get(f"/api/mobile/capabilities?platform=ios&channel={channel}").json()
    for field in ["update", "update_stable", "update_beta"]:
        metadata = result[field]
        assert metadata["available"] is False
        assert metadata["version_name"] == ""
        assert metadata["version_code"] == 0
        assert metadata["url"] is None
        assert metadata["ios_url"] is None
        assert metadata["sha256"] is None
        assert metadata["changelog"] == []


@pytest.mark.parametrize("channel", ["stable", "beta"])
def test_ios_legacy_settings_and_channel_overrides_remain_compatible(updates_client, monkeypatch, channel):
    monkeypatch.setenv("MOBILE_UPDATE_IOS_VERSION", "1.2.3")
    monkeypatch.setenv("MOBILE_UPDATE_IOS_VERSION_CODE", "7")
    monkeypatch.setenv("MOBILE_UPDATE_IOS_URL", "https://testflight.apple.com/join/fixture")
    publish(monkeypatch, "android", "stable", 21)
    metadata = updates_client.get(f"/api/mobile/capabilities?platform=ios&channel={channel}&version_code=6").json()["update"]
    assert metadata["version_name"] == "1.2.3"
    assert metadata["version_code"] == 7
    assert metadata["url"] == metadata["ios_url"] == "https://testflight.apple.com/join/fixture"
    assert metadata["available"] is True
    # An explicit empty platform override disables availability rather than
    # resurrecting a legacy value or another platform's version.
    monkeypatch.setenv(f"MOBILE_UPDATE_{channel.upper()}_IOS_VERSION", "")
    cleared = updates_client.get(f"/api/mobile/capabilities?platform=ios&channel={channel}").json()["update"]
    assert cleared["available"] is False
    assert cleared["version_name"] == ""


def test_android_download_links_and_unknown_channel_fallback_keep_existing_routes(updates_client, monkeypatch):
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION", "1.0")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION_CODE", "20")
    for channel in ["stable", "beta", "unknown"]:
        track = "beta" if channel == "beta" else "stable"
        metadata = updates_client.get(f"/api/mobile/capabilities?platform=android&channel={channel}").json()["update"]
        suffix = "?channel=beta" if track == "beta" else ""
        assert metadata["url"] == "https://api.example.test/api/mobile/update/download" + suffix
        assert metadata["channel"] == track
    assert updates_client.get("/api/mobile/capabilities?platform=windows").status_code == 422
    assert updates_client.get("/api/mobile/capabilities", headers={"x-fixture-owner": ""}).status_code == 401
