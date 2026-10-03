"""A lost response or host restart must not duplicate an agent's side effects."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import json

import pytest

from mobile_store import MobileStore, StoreError


def payload(client_id="client-1", session_id="shared", **changes):
    return {"client_id": client_id, "session_id": session_id, "message": "Hello",
            "attachments": [], "model": "openai/example", "reasoning_effort": "high",
            "delivery": "queue", "scheduled_at": None, **changes}


def test_pairing_is_one_time_hashed_and_preserves_local_owner(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    pairing = store.create_pairing("")
    result = store.exchange(pairing["code"], "Phone", "ios")
    assert result["token"].startswith("cbm_")
    assert store.authenticate_token(result["token"]) == ""
    assert store.authenticate_token("clerk-token") is None
    with pytest.raises(StoreError, match="invalid_pairing"):
        store.exchange(pairing["code"], "Other phone", "android")
    raw = store.path.read_bytes()
    assert pairing["code"].encode() not in raw
    assert result["token"].encode() not in raw
    assert "token" not in json.dumps(store.devices(""))
    assert store.path.stat().st_mode & 0o777 == 0o600


def test_expiry_and_revocation_are_fail_closed(tmp_path):
    now = [1000.0]
    store = MobileStore(tmp_path / "mobile.db", clock=lambda: now[0])
    code = store.create_pairing("alice", ttl=1)["code"]
    now[0] += 1
    with pytest.raises(StoreError, match="invalid_pairing"):
        store.exchange(code, "Phone", "android")
    result = store.exchange(store.create_pairing("alice")["code"], "Phone", "android", ttl=1)
    assert store.authenticate_token(result["token"]) == "alice"
    now[0] += 1
    with pytest.raises(StoreError, match="invalid_mobile_token"):
        store.authenticate_token(result["token"])
    result = store.exchange(store.create_pairing("alice")["code"], "Phone", "android")
    with pytest.raises(StoreError, match="device_not_found"):
        store.revoke("bob", result["device_id"])
    store.revoke("alice", result["device_id"])
    store.revoke("alice", result["device_id"])
    with pytest.raises(StoreError, match="invalid_mobile_token"):
        store.authenticate_token(result["token"])
    with pytest.raises(StoreError, match="invalid_mobile_token"):
        store.authenticate_token("cbm_invalid")


def test_concurrent_pair_exchange_has_exactly_one_winner(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    code = store.create_pairing("alice")["code"]

    def exchange(_index):
        try:
            store.exchange(code, "Phone", "android")
            return True
        except StoreError:
            return False

    with ThreadPoolExecutor(max_workers=4) as pool:
        assert sum(pool.map(exchange, range(8))) == 1
    assert len(store.devices("alice")) == 1


def test_idempotency_generates_session_once_and_detects_changed_requests(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    request = payload(session_id="")
    with ThreadPoolExecutor(max_workers=4) as pool:
        jobs = list(pool.map(lambda _: store.submit("alice", request), range(8)))
    assert len({job["id"] for job in jobs}) == 1
    assert len({job["session_id"] for job in jobs}) == 1
    assert jobs[0]["session_id"]
    assert len(store.messages("alice")) == 1
    with pytest.raises(StoreError, match="idempotency_conflict"):
        store.submit("alice", payload(session_id="", message="Different"))
    assert store.submit("bob", request)["id"] != jobs[0]["id"]


def test_claims_are_sequential_per_owner_conversation(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    first = store.submit("alice", payload("first"))
    second = store.submit("alice", payload("second"))
    other_owner = store.submit("bob", payload("first"))
    assert store.claim_next()["id"] == first["id"]
    assert store.claim_next()["id"] == other_owner["id"]
    assert store.claim_next() is None
    store.finish(first["id"], "completed")
    assert store.claim_next()["id"] == second["id"]


def test_original_events_are_replayed_with_monotonic_ids_and_owner_scope(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    job = store.submit("alice", payload())
    store.claim_next()
    original = {"type": "delta", "chunk": "A\nB", "custom": {"untouched": True}}
    store.record(job["id"], "delta", original)
    store.record(job["id"], "done", {"session_id": job["session_id"], "reply": "A\nB"})
    store.finish(job["id"], "completed")
    events = store.events("alice", job["id"])
    assert [e["seq"] for e in events] == list(range(1, len(events) + 1))
    delta = next(e for e in events if e["event"] == "delta")
    assert delta["data"] == original
    assert all(e["seq"] > delta["seq"] for e in store.events("alice", job["id"], delta["seq"]))
    with pytest.raises(StoreError, match="message_not_found"):
        store.events("bob", job["id"])


def test_stop_keeps_queue_paused_even_when_completion_races(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    first = store.submit("alice", payload("first"))
    second = store.submit("alice", payload("second"))
    store.claim_next()
    assert store.stop("alice", first["id"])["state"] == "stopping"
    assert not store.record(first["id"], "done", {"reply": "too late"})
    with pytest.raises(StoreError, match="conversation_active"):
        store.resume("alice", "shared")
    store.finish(first["id"], "completed")
    assert store.get("alice", first["id"])["state"] == "stopped"
    assert store.get("alice", second["id"])["state"] == "queued"
    assert store.claim_next() is None
    store.resume("alice", "shared")
    assert store.claim_next()["id"] == second["id"]


def test_restart_interrupts_inflight_and_never_automatically_retries(tmp_path):
    path = tmp_path / "mobile.db"
    original = MobileStore(path)
    first = original.submit("alice", payload("first"))
    second = original.submit("alice", payload("second"))
    original.claim_next()
    original.record(first["id"], "delta", {"chunk": "saved before crash"})
    restarted = MobileStore(path)
    restarted.recover()
    assert restarted.get("alice", first["id"])["state"] == "interrupted"
    assert restarted.claim_next() is None
    assert any(e["data"].get("chunk") == "saved before crash" for e in restarted.events("alice", first["id"]))
    restarted.resume("alice", "shared")
    assert restarted.claim_next()["id"] == second["id"]
    assert restarted.submit("alice", payload("first"))["state"] == "interrupted"


def test_scheduled_submission_survives_restart_and_is_claimed_once(tmp_path):
    now = [100.0]
    path = tmp_path / "mobile.db"
    store = MobileStore(path, clock=lambda: now[0])
    job = store.submit("alice", payload(scheduled_at=200.0))
    assert job["state"] == "scheduled"
    assert store.claim_next() is None
    restarted = MobileStore(path, clock=lambda: now[0])
    restarted.recover()
    now[0] = 200
    assert restarted.claim_next()["id"] == job["id"]
    assert restarted.claim_next() is None


def test_runtime_lock_prevents_a_second_worker_recovering_live_turns(tmp_path):
    first = MobileStore(tmp_path / "mobile.db")
    second = MobileStore(first.path)
    with first.runner_lock():
        with pytest.raises(StoreError, match="mobile_runner_already_active"):
            second.runner_lock()
    with second.runner_lock():
        pass


def test_steer_is_a_durable_unsupported_submission_and_never_claimed(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    job = store.submit("alice", payload(delivery="steer"))
    assert job["state"] == "unsupported"
    assert job["error"] == "steer_unsupported"
    assert store.claim_next() is None
    assert store.submit("alice", payload(delivery="steer"))["id"] == job["id"]


def test_atomic_claims_across_connections_do_not_start_same_conversation_twice(tmp_path):
    store = MobileStore(tmp_path / "mobile.db")
    first = store.submit("alice", payload("first"))
    store.submit("alice", payload("second"))
    with ThreadPoolExecutor(max_workers=4) as pool:
        claims = list(pool.map(lambda _: MobileStore(store.path).claim_next(), range(8)))
    assert [job["id"] for job in claims if job is not None] == [first["id"]]
