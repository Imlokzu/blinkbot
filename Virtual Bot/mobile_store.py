"""Durable mobile identities, submissions, queue state, and replayable events.

This database stores delivery metadata, not a second conversation history. The
turn callback still writes messages to the existing owner-scoped chat store.
"""
from __future__ import annotations

from contextlib import contextmanager
import hashlib
import json
import math
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time
from typing import Any, Callable, Iterator
import uuid

TOKEN_PREFIX = "cbm_"
PAIRING_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
PAIRING_ATTEMPT_LIMIT = 10
PAIRING_ATTEMPT_WINDOW = 300
TERMINAL = frozenset({"completed", "failed", "stopped", "interrupted", "unsupported"})
_EVENT_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_-]{0,63}$")
_SESSION_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


class StoreError(Exception):
    """An API error code safe to expose without runtime exception text."""

    def __init__(self, code: str, status: int = 400, *, retry_after: int | None = None):
        super().__init__(code)
        self.code, self.status = code, status
        self.retry_after = retry_after


def _json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)


def _hash(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class MobileStore:
    """Short transactions and atomic claims also serialize concurrent submitters."""

    def __init__(self, path: str | Path, *, clock: Callable[[], float] = time.time):
        self.path = Path(path)
        self.clock = clock
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # No token or pairing code is ever written in cleartext, including logs.
        fd = os.open(self.path, os.O_CREAT | os.O_WRONLY, 0o600)
        os.close(fd)
        self.path.chmod(0o600)
        with self._db() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS pairings (
                    code_hash TEXT PRIMARY KEY, user_id TEXT NOT NULL,
                    expires_at REAL NOT NULL, consumed_at REAL
                );
                CREATE TABLE IF NOT EXISTS pairing_aliases (
                    code_hash TEXT PRIMARY KEY,
                    pairing_hash TEXT NOT NULL UNIQUE
                        REFERENCES pairings(code_hash) ON DELETE CASCADE
                );
                CREATE TABLE IF NOT EXISTS pairing_attempts (
                    attempted_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS devices (
                    id TEXT PRIMARY KEY, user_id TEXT NOT NULL,
                    name TEXT NOT NULL, platform TEXT NOT NULL,
                    token_hash TEXT NOT NULL UNIQUE, created_at REAL NOT NULL,
                    expires_at REAL NOT NULL, revoked_at REAL
                );
                CREATE TABLE IF NOT EXISTS conversations (
                    user_id TEXT NOT NULL, session_id TEXT NOT NULL,
                    paused INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (user_id, session_id)
                );
                CREATE TABLE IF NOT EXISTS jobs (
                    ordinal INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT NOT NULL UNIQUE, user_id TEXT NOT NULL,
                    client_id TEXT NOT NULL, session_id TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, payload TEXT NOT NULL,
                    delivery TEXT NOT NULL, state TEXT NOT NULL,
                    scheduled_at REAL, created_at REAL NOT NULL,
                    updated_at REAL NOT NULL, error TEXT,
                    UNIQUE (user_id, client_id)
                );
                CREATE INDEX IF NOT EXISTS mobile_pending
                    ON jobs (state, scheduled_at, ordinal);
                CREATE INDEX IF NOT EXISTS mobile_conversation
                    ON jobs (user_id, session_id, state);
                CREATE TABLE IF NOT EXISTS events (
                    job_id TEXT NOT NULL REFERENCES jobs(id), seq INTEGER NOT NULL,
                    event TEXT NOT NULL, data TEXT NOT NULL,
                    PRIMARY KEY (job_id, seq)
                );
            """)

    @contextmanager
    def _db(self) -> Iterator[sqlite3.Connection]:
        db = sqlite3.connect(self.path, timeout=10, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        try:
            yield db
        finally:
            db.close()

    @contextmanager
    def _write(self) -> Iterator[sqlite3.Connection]:
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                yield db
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def runner_lock(self):
        """One live scheduler per DB; a second worker must never recover live jobs.

        The host backend runs on macOS/Linux. An OS lock is released on process
        death, unlike a time-based lease that could duplicate a slow agent turn.
        """
        import fcntl

        lock_path = self.path.with_name(self.path.name + ".runner.lock")
        fd = os.open(lock_path, os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BaseException:
            os.close(fd)
            raise StoreError("mobile_runner_already_active", 503) from None
        return os.fdopen(fd, "w")

    def create_pairing(self, user_id: str, *, ttl: int = 300) -> dict:
        with self._write() as db:
            now = self.clock()
            expires = now + ttl
            db.execute("DELETE FROM pairings WHERE expires_at <= ?", (now,))
            # Keep consumed aliases reserved until expiry, so a retry cannot
            # accidentally consume a different owner's newly issued pairing.
            for _ in range(16):
                code = secrets.token_urlsafe(24)
                short = "".join(secrets.choice(PAIRING_ALPHABET) for _ in range(8))
                code_hash, short_hash = _hash(code), _hash(short)
                if (db.execute("SELECT 1 FROM pairings WHERE code_hash = ?", (code_hash,)).fetchone()
                        or db.execute("SELECT 1 FROM pairing_aliases WHERE code_hash = ?", (short_hash,)).fetchone()):
                    continue
                db.execute("INSERT INTO pairings VALUES (?, ?, ?, NULL)", (code_hash, user_id, expires))
                db.execute("INSERT INTO pairing_aliases VALUES (?, ?)", (short_hash, code_hash))
                break
            else:
                raise StoreError("pairing_unavailable", 503)
        return {"code": code, "pairing_code": short[:4] + "-" + short[4:], "expires_at": expires}

    def exchange(self, code: str, device_name: str, platform: str, *, ttl: int = 90 * 86400) -> dict:
        normalized = "".join(ch for ch in code if ch != "-" and not ch.isspace()).upper()
        short = len(normalized) <= 8
        error = None
        with self._write() as db:
            # Read the clock after acquiring the lock: a waiting exchange must
            # not accept a pairing that expired while another writer held it.
            now = self.clock()
            if short:
                # One host-wide rolling budget, independent of guessed codes,
                # owners and untrusted proxy headers. At most ten rows survive.
                db.execute("DELETE FROM pairing_attempts WHERE attempted_at <= ?", (now - PAIRING_ATTEMPT_WINDOW,))
                attempts = db.execute("SELECT COUNT(*), MIN(attempted_at) FROM pairing_attempts").fetchone()
                if attempts[0] >= PAIRING_ATTEMPT_LIMIT:
                    raise StoreError("pairing_rate_limited", 429,
                                     retry_after=max(1, math.ceil(attempts[1] + PAIRING_ATTEMPT_WINDOW - now)))
                db.execute("INSERT INTO pairing_attempts VALUES (?)", (now,))
                pairing = db.execute(
                    "SELECT p.* FROM pairings p JOIN pairing_aliases a ON a.pairing_hash = p.code_hash "
                    "WHERE a.code_hash = ?", (_hash(normalized),),
                ).fetchone()
            else:
                # QR tokens are case-sensitive and must never be normalized.
                pairing = db.execute("SELECT * FROM pairings WHERE code_hash = ?", (_hash(code),)).fetchone()
            if pairing is None or pairing["consumed_at"] is not None or pairing["expires_at"] <= now:
                error = StoreError("invalid_pairing", 401)
            else:
                token, device_id = TOKEN_PREFIX + secrets.token_urlsafe(32), uuid.uuid4().hex
                db.execute("UPDATE pairings SET consumed_at = ? WHERE code_hash = ?", (now, pairing["code_hash"]))
                db.execute("INSERT INTO devices VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                           (device_id, pairing["user_id"], device_name, platform, _hash(token), now, now + ttl))
        # An invalid guess must commit its attempt before returning an error.
        if error is not None:
            raise error
        return {"token": token, "device_id": device_id, "expires_at": now + ttl}

    def authenticate_token(self, token: str) -> str | None:
        """None means nonmobile. Empty string is a VALID local-owner identity.

        A recognized but invalid token always raises: it must never reach the
        parent's disabled-auth fallback, or revocation would be meaningless.
        """
        if not token.startswith(TOKEN_PREFIX):
            return None
        with self._db() as db:
            row = db.execute("SELECT user_id, expires_at, revoked_at FROM devices WHERE token_hash = ?",
                             (_hash(token),)).fetchone()
        if row is None or row["revoked_at"] is not None or row["expires_at"] <= self.clock():
            raise StoreError("invalid_mobile_token", 401)
        return row["user_id"]

    def devices(self, user_id: str) -> list[dict]:
        with self._db() as db:
            rows = db.execute("SELECT id AS device_id, name AS device_name, platform, created_at, expires_at, revoked_at "
                              "FROM devices WHERE user_id = ? ORDER BY created_at, id", (user_id,)).fetchall()
        return [dict(row) for row in rows]

    def revoke(self, user_id: str, device_id: str) -> None:
        with self._write() as db:
            result = db.execute("UPDATE devices SET revoked_at = COALESCE(revoked_at, ?) WHERE user_id = ? AND id = ?",
                                (self.clock(), user_id, device_id))
            if not result.rowcount:
                raise StoreError("device_not_found", 404)

    @staticmethod
    def _job(row: sqlite3.Row) -> dict:
        result = dict(row)
        result["payload"] = json.loads(result["payload"])
        return result

    @staticmethod
    def _event(db: sqlite3.Connection, job_id: str, event: str, data: Any) -> int:
        seq = db.execute("SELECT COALESCE(MAX(seq), 0) + 1 FROM events WHERE job_id = ?", (job_id,)).fetchone()[0]
        db.execute("INSERT INTO events VALUES (?, ?, ?, ?)", (job_id, seq, event, _json(data)))
        return seq

    def _state(self, db: sqlite3.Connection, job_id: str, state: str, *, error: str | None = None) -> None:
        db.execute("UPDATE jobs SET state = ?, updated_at = ?, error = ? WHERE id = ?",
                   (state, self.clock(), error, job_id))
        self._event(db, job_id, "mobile_state", {"id": job_id, "state": state, **({"error": error} if error else {})})

    def submit(self, user_id: str, payload: dict) -> dict:
        return self.submit_request(user_id, payload)

    def find_request(self, user_id: str, client_id: str, request: dict) -> dict | None:
        """Check a fork retry before reading a source that may since be deleted."""
        with self._db() as db:
            row = db.execute("SELECT * FROM jobs WHERE user_id=? AND client_id=?", (user_id, client_id)).fetchone()
        if row is None:
            return None
        if row["fingerprint"] != _hash(_json(request)):
            raise StoreError("idempotency_conflict", 409)
        return self._job(row)

    def submit_request(self, user_id: str, payload: dict, *, request: dict | None = None) -> dict:
        """Idempotency includes the original blank session ID, before generation."""
        # Fork content is frozen separately from the original API request. A
        # retry must not conflict merely because the source acquired new turns.
        fingerprint = _hash(_json(payload if request is None else request))
        requested_session = payload.get("session_id") or ""
        if requested_session and not _SESSION_ID.fullmatch(requested_session):
            raise StoreError("invalid_session_id")
        now = self.clock()
        with self._write() as db:
            previous = db.execute("SELECT * FROM jobs WHERE user_id = ? AND client_id = ?",
                                  (user_id, payload["client_id"])).fetchone()
            if previous:
                if previous["fingerprint"] != fingerprint:
                    raise StoreError("idempotency_conflict", 409)
                return self._job(previous)
            session_id, job_id = requested_session or uuid.uuid4().hex[:16], uuid.uuid4().hex
            actual = {**payload, "session_id": session_id}
            scheduled = payload.get("scheduled_at")
            state = "scheduled" if scheduled is not None and scheduled > now else "queued"
            error = None
            if payload["delivery"] == "steer":
                state, error = "unsupported", "steer_unsupported"
            db.execute("INSERT OR IGNORE INTO conversations (user_id, session_id) VALUES (?, ?)", (user_id, session_id))
            db.execute("INSERT INTO jobs (id,user_id,client_id,session_id,fingerprint,payload,delivery,state,scheduled_at,created_at,updated_at,error) "
                       "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (job_id, user_id, payload["client_id"], session_id,
                       fingerprint, _json(actual), payload["delivery"], state, scheduled, now, now, error))
            self._event(db, job_id, "mobile_state", {"id": job_id, "session_id": session_id, "state": state,
                        **({"error": error} if error else {})})
            return self._job(db.execute("SELECT * FROM jobs WHERE id = ?", (job_id,)).fetchone())

    def get(self, user_id: str, job_id: str) -> dict:
        with self._db() as db:
            row = db.execute("SELECT * FROM jobs WHERE user_id = ? AND id = ?", (user_id, job_id)).fetchone()
        if row is None:
            raise StoreError("message_not_found", 404)
        return self._job(row)

    def messages(self, user_id: str, session_id: str = "") -> list[dict]:
        with self._db() as db:
            rows = db.execute("SELECT j.*, c.paused AS conversation_paused FROM jobs j JOIN conversations c "
                              "ON c.user_id=j.user_id AND c.session_id=j.session_id "
                              "WHERE j.user_id=? AND (?='' OR j.session_id=?) ORDER BY j.ordinal",
                              (user_id, session_id, session_id)).fetchall()
        return [self._job(row) for row in rows]

    def events(self, user_id: str, job_id: str, after: int = 0) -> list[dict]:
        self.get(user_id, job_id)
        with self._db() as db:
            rows = db.execute("SELECT seq,event,data FROM events WHERE job_id=? AND seq>? ORDER BY seq LIMIT 256",
                              (job_id, after)).fetchall()
        return [{"seq": row["seq"], "event": row["event"], "data": json.loads(row["data"])} for row in rows]

    def recover(self) -> None:
        """Never retry a turn that may already have produced external side effects."""
        with self._write() as db:
            rows = db.execute("SELECT id,user_id,session_id FROM jobs WHERE state IN ('running','stopping')").fetchall()
            for row in rows:
                db.execute("UPDATE conversations SET paused=1 WHERE user_id=? AND session_id=?",
                           (row["user_id"], row["session_id"]))
                self._state(db, row["id"], "interrupted", error="host_interrupted")

    def claim_next(self) -> dict | None:
        with self._write() as db:
            row = db.execute("""
                SELECT j.* FROM jobs j JOIN conversations c
                  ON c.user_id=j.user_id AND c.session_id=j.session_id
                WHERE c.paused=0 AND j.delivery='queue'
                  AND (j.state='queued' OR (j.state='scheduled' AND j.scheduled_at<=?))
                  AND NOT EXISTS (SELECT 1 FROM jobs active
                    WHERE active.user_id=j.user_id AND active.session_id=j.session_id
                      AND active.state IN ('running','stopping'))
                ORDER BY j.ordinal LIMIT 1
            """, (self.clock(),)).fetchone()
            if row is None:
                return None
            self._state(db, row["id"], "running")
            return self._job(db.execute("SELECT * FROM jobs WHERE id=?", (row["id"],)).fetchone())

    def record(self, job_id: str, event: str, data: dict) -> bool:
        if not _EVENT_NAME.fullmatch(event) or not isinstance(data, dict):
            raise StoreError("invalid_runner_event", 500)
        if event == "mobile_state":
            raise StoreError("reserved_runner_event", 500)
        with self._write() as db:
            row = db.execute("SELECT state FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None or row["state"] != "running":
                return False
            self._event(db, job_id, event, data)
        return True

    def finish(self, job_id: str, state: str, *, error: str | None = None, pause: bool = False) -> None:
        if state not in TERMINAL:
            raise ValueError("invalid_terminal_state")
        with self._write() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None or row["state"] in TERMINAL:
                return
            # A stop racing the final callback must not be relabeled completed.
            if row["state"] == "stopping" and state != "interrupted":
                state = "stopped"
            self._state(db, job_id, state, error=error)
            if pause:
                db.execute("UPDATE conversations SET paused=1 WHERE user_id=? AND session_id=?",
                           (row["user_id"], row["session_id"]))

    def stop(self, user_id: str, job_id: str) -> dict:
        with self._write() as db:
            row = db.execute("SELECT * FROM jobs WHERE user_id=? AND id=?", (user_id, job_id)).fetchone()
            if row is None:
                raise StoreError("message_not_found", 404)
            if row["state"] not in TERMINAL:
                db.execute("UPDATE conversations SET paused=1 WHERE user_id=? AND session_id=?",
                           (user_id, row["session_id"]))
                self._state(db, job_id, "stopping" if row["state"] in {"running", "stopping"} else "stopped")
            return self._job(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def resume(self, user_id: str, session_id: str) -> None:
        with self._write() as db:
            active = db.execute("SELECT 1 FROM jobs WHERE user_id=? AND session_id=? AND state IN ('running','stopping')",
                                (user_id, session_id)).fetchone()
            if active:
                raise StoreError("conversation_active", 409)
            result = db.execute("UPDATE conversations SET paused=0 WHERE user_id=? AND session_id=?", (user_id, session_id))
            if not result.rowcount:
                raise StoreError("conversation_not_found", 404)
