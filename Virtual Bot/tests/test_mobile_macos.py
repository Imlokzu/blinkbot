"""The desktop client shares pairing and capability contracts with phones."""
from __future__ import annotations

import os

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import pytest

from mobile_api import router
from mobile_store import MobileStore


@pytest.fixture
def macos_client(tmp_path, monkeypatch):
    # Prove desktop metadata cannot accidentally inherit a published phone build.
    for key in list(os.environ):
        if key.startswith("MOBILE_UPDATE_"):
            monkeypatch.delenv(key)
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION", "9.9.9")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_VERSION_CODE", "999")
    monkeypatch.setenv("MOBILE_UPDATE_ANDROID_URL", "https://releases.example.test/app.apk")
    monkeypatch.setenv("MOBILE_UPDATE_IOS_URL", "https://testflight.apple.com/join/phone")

    async def owner(request: Request):
        if request.headers.get("x-fixture-owner") != "alice":
            raise HTTPException(401)
        return "alice"

    async def operator(_request):
        raise HTTPException(403)

    async def unused_runner(_job):
        raise AssertionError("Pairing and capabilities never require model work")
        yield

    app = FastAPI()
    store = MobileStore(tmp_path / "mobile.db")
    app.include_router(router(owner, operator, unused_runner, store=store))
    with TestClient(app, headers={"x-fixture-owner": "alice"}) as client:
        yield client, store


def test_macos_pairing_exchange_persists_platform_metadata(macos_client):
    client, store = macos_client
    pairing = store.create_pairing("alice")
    response = client.post("/api/mobile/pair/exchange", json={
        "code": pairing["code"], "device_name": "Mac desktop", "platform": "macos",
    })
    assert response.status_code == 200
    device = store.devices("alice")[0]
    assert device["device_name"] == "Mac desktop"
    assert device["platform"] == "macos"
    assert store.authenticate_token(response.json()["token"]) == "alice"


def test_macos_capabilities_are_valid_but_updates_stay_unavailable(macos_client):
    client, _store = macos_client
    response = client.get("/api/mobile/capabilities?platform=macos&channel=beta&version_code=42")
    assert response.status_code == 200
    body = response.json()
    for field in ("update", "update_stable", "update_beta"):
        metadata = body[field]
        assert metadata["available"] is False
        assert metadata["version_name"] == ""
        assert metadata["version_code"] == 0
        assert metadata["url"] is None
        assert metadata["ios_url"] is None
        assert metadata["sha256"] is None
        assert metadata["changelog"] == []
    assert body["update"]["channel"] == "beta"


def test_unknown_platforms_remain_rejected(macos_client):
    client, _store = macos_client
    assert client.get("/api/mobile/capabilities?platform=windows").status_code == 422
    response = client.post("/api/mobile/pair/exchange", json={
        "code": "unused", "device_name": "Desktop", "platform": "windows",
    })
    assert response.status_code == 422
