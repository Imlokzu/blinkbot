"""Pairing fixtures stay on temporary databases and an isolated ASGI app."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import hashlib
import sqlite3
import threading
from urllib.parse import parse_qs, urlsplit

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import pytest

from mobile_api import router
from mobile_store import (
    MobileStore, PAIRING_ALPHABET, PAIRING_ATTEMPT_LIMIT,
    PAIRING_ATTEMPT_WINDOW, StoreError,
)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def outcome(store, code):
    try:
        store.exchange(code, "Fixture phone", "android")
        return "accepted"
    except StoreError as exc:
        return exc.code


def app_for(store):
    async def owner(request: Request):
        return request.headers.get("x-owner", "")

    async def operator(request: Request):
        if request.headers.get("x-operator") != "yes":
            raise HTTPException(403, "operator_required")

    async def unused_runner(job):
        yield "done", {}

    app = FastAPI()
    app.include_router(router(owner, operator, unused_runner, store=store,
                              server_origin="https://pairing.example.test"))
    return app


def test_issuance_contract_uses_40_bits_and_stores_only_hashes(tmp_path):
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: 1000)
    pairing = store.create_pairing("alice")
    short = pairing["pairing_code"]
    assert len(set(PAIRING_ALPHABET)) == 32
    assert len(short) == 9 and short[4] == "-"
    assert all(ch in PAIRING_ALPHABET for ch in short.replace("-", ""))
    assert pairing["expires_at"] == 1300
    with store._db() as db:
        alias = db.execute("SELECT * FROM pairing_aliases").fetchone()
        assert alias["code_hash"] == digest(short.replace("-", ""))
        assert alias["pairing_hash"] == digest(pairing["code"])
    device = store.exchange(short, "Fixture phone", "android")
    raw = store.path.read_bytes()
    assert all(value.encode() not in raw for value in (
        short, short.replace("-", ""), pairing["code"], device["token"],
    ))


@pytest.mark.parametrize("format_code", [
    lambda code: code,
    lambda code: code.lower(),
    lambda code: code.replace("-", ""),
    lambda code: code.lower().replace("-", " "),
    lambda code: " \t" + " ".join(code.lower()) + "\n",
])
def test_short_code_normalization(format_code, tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    pairing = store.create_pairing("alice")
    device = store.exchange(format_code(pairing["pairing_code"]), "Fixture phone", "ios")
    assert store.authenticate_token(device["token"]) == "alice"


@pytest.mark.parametrize("owner", ["", "alice"])
@pytest.mark.parametrize("first", ["code", "pairing_code"])
def test_either_credential_consumes_both_and_preserves_owner(tmp_path, owner, first):
    store = MobileStore(tmp_path / "mobile.db")
    pairing = store.create_pairing(owner)
    device = store.exchange(pairing[first], "Fixture phone", "ios")
    assert store.authenticate_token(device["token"]) == owner
    assert len(store.devices(owner)) == 1
    assert store.devices("other-owner") == []
    assert outcome(store, pairing["code"]) == "invalid_pairing"
    assert outcome(store, pairing["pairing_code"]) == "invalid_pairing"


@pytest.mark.parametrize("first", ["code", "pairing_code"])
def test_pending_alias_and_consumption_survive_restarts(tmp_path, first):
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: 1000)
    pairing = store.create_pairing("alice")
    restarted = MobileStore(store.path, clock=lambda: 1001)
    device = restarted.exchange(pairing[first], "Fixture phone", "ios")
    restarted = MobileStore(store.path, clock=lambda: 1002)
    assert restarted.authenticate_token(device["token"]) == "alice"
    assert outcome(restarted, pairing["code"]) == "invalid_pairing"
    assert outcome(restarted, pairing["pairing_code"]) == "invalid_pairing"
    assert len(restarted.devices("alice")) == 1


@pytest.mark.parametrize("field", ["code", "pairing_code"])
def test_both_credentials_expire_at_the_same_boundary(tmp_path, field):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    pairing = store.create_pairing("alice", ttl=1)
    now[0] = pairing["expires_at"]
    assert outcome(store, pairing[field]) == "invalid_pairing"
    assert store.devices("alice") == []


def test_alias_and_qr_race_across_connections_has_one_winner(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    pairing = store.create_pairing("alice")
    stores = [MobileStore(store.path) for _ in range(8)]
    barrier = threading.Barrier(len(stores))

    def exchange(index):
        barrier.wait(timeout=5)
        field = "code" if index % 2 else "pairing_code"
        return outcome(stores[index], pairing[field])

    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(exchange, range(8)))
    assert results.count("accepted") == 1
    assert results.count("invalid_pairing") == 7
    assert len(store.devices("alice")) == 1


def test_device_insert_failure_rolls_back_shared_consumption(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    pairing = store.create_pairing("alice")
    with store._db() as db:
        db.execute("CREATE TRIGGER fail_device BEFORE INSERT ON devices "
                   "BEGIN SELECT RAISE(ABORT, 'fixture_failure'); END")
    with pytest.raises(sqlite3.IntegrityError, match="fixture_failure"):
        store.exchange(pairing["pairing_code"], "Fixture phone", "ios")
    with store._db() as db:
        assert db.execute("SELECT consumed_at FROM pairings").fetchone()[0] is None
        db.execute("DROP TRIGGER fail_device")
    assert outcome(store, pairing["code"]) == "accepted"


def test_expiry_is_checked_after_waiting_for_write_lock(tmp_path, monkeypatch):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    pairing = store.create_pairing("alice", ttl=1)
    waiting = threading.Event()
    original_write = store._write

    @contextmanager
    def waiting_write():
        waiting.set()
        with original_write() as db:
            yield db

    monkeypatch.setattr(store, "_write", waiting_write)
    with store._db() as db, ThreadPoolExecutor(max_workers=1) as pool:
        db.execute("BEGIN IMMEDIATE")
        future = pool.submit(outcome, store, pairing["pairing_code"])
        try:
            assert waiting.wait(timeout=2)
            now[0] = pairing["expires_at"]
        finally:
            db.commit()
        assert future.result(timeout=5) == "invalid_pairing"


def test_alias_collision_retries_without_changing_existing_owner(tmp_path, monkeypatch):
    store = MobileStore(tmp_path / "mobile.db")
    monkeypatch.setattr("mobile_store.secrets.choice", lambda alphabet: "A")
    first = store.create_pairing("alice")
    assert outcome(store, first["code"]) == "accepted"
    # Even a consumed alias stays reserved until its original expiry.
    choices = iter("A" * 8 + "B" * 8)
    monkeypatch.setattr("mobile_store.secrets.choice", lambda alphabet: next(choices))
    second = store.create_pairing("bob")
    device = store.exchange(second["pairing_code"], "Fixture phone", "ios")
    assert store.authenticate_token(device["token"]) == "bob"
    assert outcome(store, first["pairing_code"]) == "invalid_pairing"


def test_alias_collision_exhaustion_is_bounded_and_atomic(tmp_path, monkeypatch):
    store = MobileStore(tmp_path / "mobile.db")
    monkeypatch.setattr("mobile_store.secrets.choice", lambda alphabet: "A")
    store.create_pairing("alice")
    calls = []

    def same_character(alphabet):
        calls.append(None)
        return "A"

    monkeypatch.setattr("mobile_store.secrets.choice", same_character)
    with pytest.raises(StoreError, match="pairing_unavailable") as error:
        store.create_pairing("bob")
    assert error.value.status == 503
    assert len(calls) == 16 * 8
    with store._db() as db:
        assert db.execute("SELECT COUNT(*) FROM pairings").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM pairing_aliases").fetchone()[0] == 1


def test_expired_alias_cleanup_cascades_and_allows_reissue(tmp_path, monkeypatch):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    monkeypatch.setattr("mobile_store.secrets.choice", lambda alphabet: "A")
    old = store.create_pairing("alice", ttl=1)
    now[0] += 1
    new = store.create_pairing("bob")
    assert outcome(store, old["code"]) == "invalid_pairing"
    device = store.exchange(new["pairing_code"], "Fixture phone", "ios")
    assert store.authenticate_token(device["token"]) == "bob"
    with store._db() as db:
        assert db.execute("SELECT COUNT(*) FROM pairing_aliases").fetchone()[0] == 1
        assert db.execute("PRAGMA foreign_key_check").fetchall() == []


def test_existing_database_qr_tokens_and_devices_survive_additive_migration(tmp_path):
    path = tmp_path / "legacy.db"
    # Synthetic legacy values deliberately include mixed case and URL symbols.
    legacy_code = "AbCdEfGhIjKlMnOpQrStUvWxYz0123-_"
    legacy_token = "cbm_fixture_preexisting"
    with sqlite3.connect(path) as db:
        db.executescript("""
            CREATE TABLE pairings (
                code_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL,
                expires_at REAL NOT NULL, consumed_at REAL
            );
            CREATE TABLE devices (
                id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
                name TEXT NOT NULL, platform TEXT NOT NULL,
                token_hash TEXT NOT NULL UNIQUE, created_at REAL NOT NULL,
                expires_at REAL NOT NULL, revoked_at REAL
            );
        """)
        db.execute("INSERT INTO pairings VALUES (?, '', 2000, NULL)", (digest(legacy_code),))
        db.execute("INSERT INTO devices VALUES ('existing', 'alice', 'Fixture', 'ios', ?, 900, 2000, NULL)",
                   (digest(legacy_token),))
    store = MobileStore(path, clock=lambda: 1000)
    store = MobileStore(path, clock=lambda: 1000)  # Migration is repeatable.
    assert store.authenticate_token(legacy_token) == "alice"
    assert outcome(store, legacy_code.lower()) == "invalid_pairing"
    assert outcome(store, " " + legacy_code) == "invalid_pairing"
    device = store.exchange(legacy_code, "Fixture phone", "android")
    assert store.authenticate_token(device["token"]) == ""
    pairing = store.create_pairing("bob")
    assert outcome(store, pairing["pairing_code"]) == "accepted"


def test_attempt_budget_survives_restart_target_changes_and_issuance(tmp_path):
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: 1000)
    # Bounded synthetic misses verify accounting, with no network traffic.
    for index in range(PAIRING_ATTEMPT_LIMIT):
        assert outcome(store, "2222222" + PAIRING_ALPHABET[index]) == "invalid_pairing"
        store = MobileStore(store.path, clock=lambda: 1000)
    pairing = store.create_pairing("bob")
    with pytest.raises(StoreError, match="pairing_rate_limited") as error:
        store.exchange(pairing["pairing_code"], "Fixture phone", "android")
    assert error.value.status == 429
    assert error.value.retry_after == PAIRING_ATTEMPT_WINDOW
    # Throttling never consumes a valid pairing or disables its QR credential.
    assert outcome(store, pairing["code"]) == "accepted"
    with store._db() as db:
        assert db.execute("SELECT COUNT(*) FROM pairing_attempts").fetchone()[0] == PAIRING_ATTEMPT_LIMIT


def test_rolling_window_releases_only_the_oldest_attempt(tmp_path):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    for index in range(PAIRING_ATTEMPT_LIMIT):
        now[0] = 1000.0 + index
        assert outcome(store, "22222222") == "invalid_pairing"
    now[0] = 1000.0 + PAIRING_ATTEMPT_WINDOW - 0.25
    with pytest.raises(StoreError, match="pairing_rate_limited") as error:
        store.exchange("22222222", "Fixture phone", "ios")
    assert error.value.retry_after == 1
    now[0] += 0.25
    assert outcome(store, "22222222") == "invalid_pairing"
    assert outcome(store, "33333333") == "pairing_rate_limited"


def test_concurrent_short_exchanges_cannot_overrun_budget(tmp_path):
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: 1000)
    stores = [MobileStore(store.path, clock=lambda: 1000) for _ in range(4)]
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda index: outcome(stores[index % 4], "22222222"),
                                range(PAIRING_ATTEMPT_LIMIT + 4)))
    assert results.count("invalid_pairing") == PAIRING_ATTEMPT_LIMIT
    assert results.count("pairing_rate_limited") == 4


def test_successful_short_exchange_also_counts_toward_budget(tmp_path):
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: 1000)
    pairing = store.create_pairing("alice")
    assert outcome(store, pairing["pairing_code"]) == "accepted"
    for _ in range(PAIRING_ATTEMPT_LIMIT - 1):
        assert outcome(store, pairing["pairing_code"]) == "invalid_pairing"
    assert outcome(store, pairing["pairing_code"]) == "pairing_rate_limited"


@pytest.mark.parametrize("owner", ["", "alice"])
def test_api_adds_short_code_keeps_qr_and_preserves_owner(tmp_path, owner):
    store = MobileStore(tmp_path / "mobile.db")
    # No lifespan is entered: these pairing requests need no scheduler/server.
    client = TestClient(app_for(store))
    try:
        assert client.post("/api/mobile/pairings", json={}).status_code == 403
        response = client.post("/api/mobile/pairings", json={},
                               headers={"x-operator": "yes", "x-owner": owner})
        assert response.status_code == 200
        assert response.headers["cache-control"] == "no-store"
        pairing = response.json()
        assert {"code", "pairing_code", "expires_at", "qr_payload", "qr_svg"} <= pairing.keys()
        qr_code = parse_qs(urlsplit(pairing["qr_payload"]).query)["code"][0]
        assert qr_code == pairing["code"]
        device = client.post("/api/mobile/pair/exchange", json={
            "code": pairing["pairing_code"].lower().replace("-", " "),
            "device_name": "Fixture phone", "platform": "android",
        }, headers={"x-owner": "different-owner"})
        assert device.status_code == 200
        assert store.authenticate_token(device.json()["token"]) == owner
        assert outcome(store, qr_code) == "invalid_pairing"
    finally:
        client.close()


def test_api_throttle_has_stable_code_retry_after_and_ignores_forwarded_ip(tmp_path):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    client = TestClient(app_for(store))
    try:
        for _ in range(PAIRING_ATTEMPT_LIMIT):
            response = client.post("/api/mobile/pair/exchange", json={
                "code": "2222-2222", "device_name": "Fixture", "platform": "ios",
            })
            assert response.status_code == 401
            assert response.json() == {"detail": {"code": "invalid_pairing"}}
        for suffix in (1, 2):
            response = client.post("/api/mobile/pair/exchange", json={
                "code": "3333 3333", "device_name": "Fixture", "platform": "ios",
            }, headers={"x-forwarded-for": f"192.0.2.{suffix}", "cf-connecting-ip": f"192.0.2.{suffix}"})
            assert response.status_code == 429
            assert response.json() == {"detail": {"code": "pairing_rate_limited"}}
            assert response.headers["retry-after"] == str(PAIRING_ATTEMPT_WINDOW)
        now[0] += PAIRING_ATTEMPT_WINDOW
        pairing = client.post("/api/mobile/pairings", json={},
                              headers={"x-operator": "yes"}).json()
        response = client.post("/api/mobile/pair/exchange", json={
            "code": pairing["pairing_code"], "device_name": "Fixture", "platform": "ios",
        })
        assert response.status_code == 200
        assert store.authenticate_token(response.json()["token"]) == ""
        assert "retry-after" not in response.headers
    finally:
        client.close()


@pytest.mark.parametrize("field", ["code", "pairing_code"])
@pytest.mark.parametrize("elapsed", [299.999, 300])
def test_api_five_minute_expiry_boundary(tmp_path, field, elapsed):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    client = TestClient(app_for(store))
    try:
        response = client.post("/api/mobile/pairings", json={},
                               headers={"x-operator": "yes", "x-owner": "alice"})
        assert response.status_code == 200
        pairing = response.json()
        assert pairing["expires_at"] == 1300
        now[0] += elapsed
        response = client.post("/api/mobile/pair/exchange", json={
            "code": pairing[field], "device_name": "Fixture", "platform": "ios",
        })
        if elapsed < 300:
            assert response.status_code == 200
            assert store.authenticate_token(response.json()["token"]) == "alice"
        else:
            assert response.status_code == 401
            assert response.json() == {"detail": {"code": "invalid_pairing"}}
            assert store.devices("alice") == []
    finally:
        client.close()


def test_api_issuance_collision_returns_stable_unavailable_error(tmp_path, monkeypatch):
    store = MobileStore(tmp_path / "mobile.db")
    monkeypatch.setattr("mobile_store.secrets.choice", lambda alphabet: "A")
    existing = store.create_pairing("alice")
    client = TestClient(app_for(store))
    try:
        response = client.post("/api/mobile/pairings", json={},
                               headers={"x-operator": "yes", "x-owner": "bob"})
        assert response.status_code == 503
        assert response.json() == {"detail": {"code": "pairing_unavailable"}}
        assert "retry-after" not in response.headers
        assert outcome(store, existing["pairing_code"]) == "accepted"
        assert store.devices("bob") == []
    finally:
        client.close()
