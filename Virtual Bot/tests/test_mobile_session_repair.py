"""A web owner can repair a phone without replacing its identity or drafts."""
from __future__ import annotations

from contextlib import contextmanager
import json
import sqlite3

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from mobile_api import router
from mobile_store import AUTH_FAILURE_THROTTLE, DEVICE_SESSION_TTL, MobileStore, StoreError
from test_mobile_api import immediate, operator, owner


def paired(store, user="alice", **options):
    return store.exchange(store.create_pairing(user)["code"], "Recognized phone", "android", **options)


def app_for(store):
    app = FastAPI()
    app.include_router(router(owner, operator, immediate, store=store))
    return app


@pytest.mark.parametrize("user", ["", "alice"])
def test_expired_session_can_retry_same_credential_after_web_extension(tmp_path, user):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    phone = paired(store, user, ttl=10)
    now[0] += 10
    with pytest.raises(StoreError, match="invalid_mobile_token"):
        store.authenticate_token(phone["token"])
    failed = store.devices(user)[0]
    assert failed["status"] == "expired"
    assert failed["last_auth_failure"] == "expired"
    assert failed["last_auth_failure_at"] == now[0]
    assert store.devices("another-owner") == []
    with TestClient(app_for(store)) as client:
        result = client.post(f'/api/mobile/devices/{phone["device_id"]}/renew', json={},
                             headers={"x-owner": user, "x-operator": "yes"})
    assert result.status_code == 200
    repaired = result.json()["device"]
    assert repaired["device_id"] == phone["device_id"]
    assert repaired["created_at"] == failed["created_at"]
    assert repaired["expires_at"] == now[0] + DEVICE_SESSION_TTL
    assert repaired["status"] == "active"
    assert repaired["last_auth_failure"] is None
    assert repaired["last_auth_failure_at"] is None
    assert store.authenticate_token(phone["token"]) == user
    assert "token" not in json.dumps(repaired)


def test_revoked_repair_requires_explicit_owner_confirmation_and_preserves_other_devices(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    phone, other = paired(store), paired(store)
    store.revoke("alice", phone["device_id"])
    store.revoke("alice", other["device_id"])
    with pytest.raises(StoreError):
        store.authenticate_token(phone["token"])
    with TestClient(app_for(store)) as client:
        url = f'/api/mobile/devices/{phone["device_id"]}/renew'
        denied = client.post(url, json={}, headers={"x-owner": "alice"})
        assert denied.status_code == 409
        assert denied.json()["detail"]["code"] == "device_revoked"
        assert client.post(url, json={"restore_revoked": True}, headers={"x-owner": "bob"}).status_code == 404
        result = client.post(url, json={"restore_revoked": True}, headers={"x-owner": "alice"})
    assert result.status_code == 200
    assert result.json()["device"]["revoked_at"] is None
    assert store.authenticate_token(phone["token"]) == "alice"
    with pytest.raises(StoreError):
        store.authenticate_token(other["token"])


def test_extension_never_shortens_or_accumulates_active_lifetime(tmp_path):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    phone = paired(store, ttl=DEVICE_SESSION_TTL * 2)
    assert store.renew("alice", phone["device_id"])["expires_at"] == phone["expires_at"]
    now[0] += DEVICE_SESSION_TTL + 10
    extended = store.renew("alice", phone["device_id"])
    assert extended["expires_at"] == now[0] + DEVICE_SESSION_TTL
    assert store.renew("alice", phone["device_id"])["expires_at"] == extended["expires_at"]


@pytest.mark.parametrize("credential", ["authorization", "x-clerk-token", "query"])
def test_phone_credentials_cannot_renew_even_with_operator_headers(tmp_path, credential):
    store = MobileStore(tmp_path / "mobile.db")
    phone = paired(store)
    with TestClient(app_for(store)) as client:
        url = f'/api/mobile/devices/{phone["device_id"]}/renew'
        headers = {"x-owner": "alice", "x-operator": "yes"}
        params = {}
        if credential == "query":
            params["token"] = phone["token"]
        else:
            headers[credential] = "Bearer " + phone["token"] if credential == "authorization" else phone["token"]
        assert client.post(url, json={}, headers=headers, params=params).status_code == 403


def test_local_owner_requires_operator_and_restore_flag_is_strict(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    phone = paired(store, "")
    with TestClient(app_for(store)) as client:
        url = f'/api/mobile/devices/{phone["device_id"]}/renew'
        assert client.post(url, json={}).status_code == 403
        for value in ["true", 1, None]:
            assert client.post(url, json={"restore_revoked": value}, headers={"x-operator": "yes"}).status_code == 422
        assert client.post(url, json={"ttl": 10}, headers={"x-operator": "yes"}).status_code == 422


def test_failures_survive_restart_are_rate_bounded_and_do_not_record_unknown_tokens(tmp_path):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    phone = paired(store)
    store.revoke("alice", phone["device_id"])
    for _ in range(2):
        with pytest.raises(StoreError):
            store.authenticate_token(phone["token"])
        now[0] += 10
    restarted = MobileStore(store.path, clock=lambda: now[0])
    assert restarted.devices("alice")[0]["last_auth_failure_at"] == 1000.0
    with pytest.raises(StoreError):
        restarted.authenticate_token("cbm_unknown")
    with sqlite3.connect(store.path) as db:
        assert db.execute("SELECT COUNT(*) FROM device_auth_failures").fetchone()[0] == 1
    now[0] += 60
    with pytest.raises(StoreError):
        restarted.authenticate_token(phone["token"])
    assert restarted.devices("alice")[0]["last_auth_failure_at"] == now[0]


def test_existing_device_database_gets_additive_failure_tracking(tmp_path):
    path = tmp_path / "mobile.db"
    store = MobileStore(path)
    phone = paired(store)
    with sqlite3.connect(path) as db:
        db.execute("DROP TABLE device_auth_failures")
    migrated = MobileStore(path)
    assert migrated.authenticate_token(phone["token"]) == "alice"
    assert migrated.devices("alice")[0]["last_auth_failure"] is None


@pytest.mark.parametrize("headers", [
    {"origin": "https://foreign.example"},
    {"x-forwarded-for": "203.0.113.9"},
    {"forwarded": "for=203.0.113.9;proto=https"},
    {"host": "klodbot.waveio.me"},
])
def test_real_local_owner_gate_rejects_cross_origin_and_forwarded_repairs(tmp_path, monkeypatch, headers):
    import main
    import mobile_api

    store = MobileStore(tmp_path / "mobile.db")
    phone = paired(store, "")
    monkeypatch.setattr(mobile_api, "_default_store", store)
    monkeypatch.setattr(main.auth_clerk, "is_auth_disabled", lambda: True)
    app = FastAPI()
    app.include_router(router(main._require_user, main._require_openclaw_operator, immediate, store=store))
    with TestClient(app, base_url="http://127.0.0.1", client=("127.0.0.1", 1234)) as client:
        path = f'/api/mobile/devices/{phone["device_id"]}/renew'
        assert client.post(path, json={}, headers=headers).status_code == 403
        assert client.post(path, json={}).status_code == 200


def test_throttled_replay_of_a_dead_credential_never_waits_for_the_write_lock(tmp_path):
    """A revoked token replayed in a loop must not queue behind every writer.

    The first failure is recorded under the write lock. Replays inside the
    throttle window are answered from the plain read: with the database's
    write lock held by someone else, the replay still gets its 401 instead of
    a locked-database error, and the warning keeps its first timestamp.
    """
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    phone = paired(store)
    store.revoke("alice", phone["device_id"])
    with pytest.raises(StoreError, match="invalid_mobile_token"):
        store.authenticate_token(phone["token"])
    now[0] += AUTH_FAILURE_THROTTLE / 2
    blocker = sqlite3.connect(store.path, isolation_level=None)
    blocker.execute("BEGIN IMMEDIATE")
    try:
        with pytest.raises(StoreError, match="invalid_mobile_token"):
            store.authenticate_token(phone["token"])
    finally:
        blocker.execute("ROLLBACK")
        blocker.close()
    assert store.devices("alice")[0]["last_auth_failure_at"] == 1000.0


def test_repair_landing_while_the_phone_waits_for_the_lock_is_accepted_without_a_stale_warning(tmp_path, monkeypatch):
    """The phone read an expired row, then the owner's repair committed first.

    Rechecking under the write lock must accept the request and must not
    insert a warning the repair has just cleared.
    """
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    phone = paired(store, ttl=10)
    now[0] += 10
    web = MobileStore(store.path, clock=lambda: now[0])
    original_write = store._write

    @contextmanager
    def write_after_web_repair():
        web.renew("alice", phone["device_id"])
        with original_write() as db:
            yield db

    monkeypatch.setattr(store, "_write", write_after_web_repair)
    assert store.authenticate_token(phone["token"]) == "alice"
    device = store.devices("alice")[0]
    assert device["status"] == "active"
    assert device["last_auth_failure"] is None


def test_renew_receipt_is_the_committed_row_not_a_second_read(tmp_path, monkeypatch):
    """The dashboard trusts a receipt only when it shows an active row.

    So the receipt must be the row this transaction committed: a read after
    the commit can be blocked by another writer or show a revoke that landed
    later, turning a successful repair into a reported failure.
    """
    store = MobileStore(tmp_path / "mobile.db")
    phone = paired(store)
    store.revoke("alice", phone["device_id"])
    monkeypatch.setattr(store, "devices", lambda user_id: pytest.fail("receipt read outside the repair transaction"))
    receipt = store.renew("alice", phone["device_id"], restore_revoked=True)
    assert receipt["device_id"] == phone["device_id"]
    assert receipt["status"] == "active"
    assert receipt["revoked_at"] is None
    assert receipt["last_auth_failure"] is None
