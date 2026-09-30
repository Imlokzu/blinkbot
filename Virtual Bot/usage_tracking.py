"""Durable chat metadata for usage attribution; never store conversation text.

Gateway tokens and inference costs are resolved separately from its transcript.
This ledger identifies the user turn, its modality, and actual router overhead.
"""

from __future__ import annotations

import asyncio
from contextlib import closing
from contextvars import ContextVar
from dataclasses import dataclass, field
from functools import wraps
import hashlib
import inspect
import logging
import os
from pathlib import Path
import re
import sqlite3
import time
import uuid

import brain_context

log = logging.getLogger("virtual_bot.usage_tracking")

CLASSIFIER_INPUT_USD_PER_MILLION = 0.042  # https://docs.typesafe.ai/models
MAX_TURNS = 10000
_MODEL = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9_./:-]{0,159}$")
_CHANNELS = {"chat", "screen", "telegram", "discord", "voice"}
_MODES = {"openclaw", "offline", "demo", "error", "cancelled"}
_SCHEMA = """
CREATE TABLE IF NOT EXISTS turns (
    request_id TEXT PRIMARY KEY,
    owner_hash TEXT NOT NULL,
    session_hash TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    ended_at INTEGER NOT NULL,
    modality TEXT NOT NULL,
    channel TEXT,
    mode TEXT NOT NULL,
    router_enabled INTEGER NOT NULL,
    routed INTEGER NOT NULL,
    tier TEXT,
    source TEXT,
    routed_model TEXT,
    routing_cost REAL,
    router_calls INTEGER NOT NULL,
    classifier_input_tokens INTEGER
);
CREATE INDEX IF NOT EXISTS turns_owner_time ON turns (owner_hash, started_at);
"""
_PUBLIC_FIELDS = (
    "request_id", "session_hash", "started_at", "ended_at", "modality", "channel",
    "mode", "router_enabled", "routed", "tier", "source", "routed_model",
    "routing_cost", "router_calls", "classifier_input_tokens",
)


def session_hash(key: str) -> str:
    """Match an allowed Gateway session without retaining its raw key."""
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


def _owner_hash(user_id: str | None) -> str:
    identity = "user:" + user_id if user_id else "local-owner"
    return hashlib.sha256(identity.encode("utf-8")).hexdigest()


def _database_path() -> Path:
    configured = os.environ.get("VBOT_USAGE_DB")
    return Path(configured).expanduser() if configured else Path(__file__).resolve().parent / "runtime" / "usage.sqlite3"


@dataclass
class _Turn:
    owner_hash: str
    session_hash: str
    modality: str
    channel: str | None
    database: Path
    request_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    started_at: int = field(default_factory=lambda: time.time_ns() // 1_000_000)
    routed: bool = False
    tier: str | None = None
    source: str | None = None
    routed_model: str | None = None
    classifier_calls: list[int | None] = field(default_factory=list)
    closed: bool = False


_current: ContextVar[_Turn | None] = ContextVar("usage_turn", default=None)


def note_route(tier: str, model: str, source: str = "") -> None:
    """Record the actual route, independent of global last-model display state."""
    turn = _current.get()
    if turn is None or turn.closed:
        return
    turn.routed = True
    turn.tier = tier if tier in {"fast", "smart", "build"} else None
    turn.source = "jev" if isinstance(source, str) and source.startswith("jev ") else "keywords" if source in {"", "keywords"} else None
    turn.routed_model = model if isinstance(model, str) and _MODEL.fullmatch(model) else None


def note_classifier(input_tokens: object = None, *, call_id: int | None = None) -> int | None:
    """Start a real API attempt, or attach a successful response to that attempt.

Call once before HTTP, then with the returned ``call_id`` on status 200. A
timeout, error, or missing token count stays unknown; it is never free. Even
a low-confidence result is billed, before keyword fallback picks the route.
"""
    turn = _current.get()
    if turn is None or turn.closed:
        return None
    tokens = input_tokens if type(input_tokens) is int and 0 <= input_tokens <= 2**63 - 1 else None
    if call_id is None:
        turn.classifier_calls.append(tokens)
        return len(turn.classifier_calls) - 1
    if type(call_id) is int and 0 <= call_id < len(turn.classifier_calls):
        turn.classifier_calls[call_id] = tokens
    return call_id


def _persist(turn: _Turn, mode: str) -> None:
    turn.closed = True
    ended_at = max(turn.started_at + 1, time.time_ns() // 1_000_000)
    known = all(tokens is not None for tokens in turn.classifier_calls)
    tokens = sum(turn.classifier_calls) if known else None
    if tokens is not None and tokens > 2**63 - 1:
        tokens = None
    cost = tokens * CLASSIFIER_INPUT_USD_PER_MILLION / 1_000_000 if tokens is not None else None
    row = (
        turn.request_id, turn.owner_hash, turn.session_hash, turn.started_at, ended_at,
        turn.modality, turn.channel, mode, int(turn.routed or bool(turn.classifier_calls)), int(turn.routed),
        turn.tier, turn.source, turn.routed_model, cost, len(turn.classifier_calls), tokens,
    )
    turn.database.parent.mkdir(parents=True, exist_ok=True)
    # SQLite otherwise creates a world-readable file under a typical umask.
    fd = os.open(turn.database, os.O_CREAT | os.O_WRONLY, 0o600)
    os.close(fd)
    turn.database.chmod(0o600)
    # A short lock wait keeps a damaged/busy ledger from holding up a reply.
    with closing(sqlite3.connect(turn.database, timeout=0.1)) as db:
        db.executescript(_SCHEMA)
        with db:
            db.execute("INSERT INTO turns VALUES (" + ",".join("?" for _ in row) + ")", row)


def record_chat(function):
    """Wrap async chat without changing its arguments, return value, or errors."""
    signature = inspect.signature(function)

    @wraps(function)
    async def tracked(*args, **kwargs):
        turn = None
        try:
            arguments = signature.bind(*args, **kwargs).arguments
            key = arguments.get("session_key")
            if isinstance(key, str) and key.strip():
                channel = arguments.get("channel")
                turn = _Turn(
                    owner_hash=_owner_hash(brain_context.get_active_clerk_user()),
                    session_hash=session_hash(key),
                    modality="voice" if arguments.get("voice") or arguments.get("spoken") else "text",
                    channel=channel if isinstance(channel, str) and channel in _CHANNELS else None,
                    database=_database_path(),
                )
        except (Exception, asyncio.CancelledError):
            # Analytics must not alter argument validation or the reply path.
            pass
        # A session-less nested title/background call must not inherit a turn.
        token = _current.set(turn)
        mode = "error"
        try:
            result = await function(*args, **kwargs)
            if isinstance(result, tuple) and len(result) > 2 and result[2] in _MODES:
                mode = result[2]
            return result
        except asyncio.CancelledError:
            mode = "cancelled"
            raise
        finally:
            try:
                if turn is not None:
                    await asyncio.to_thread(_persist, turn, mode)
            except (Exception, asyncio.CancelledError) as exc:
                log.debug("Usage ledger unavailable (%s)", type(exc).__name__)
            finally:
                _current.reset(token)

    return tracked


def read_turns(start_ms: int, end_ms: int, user_id: str | None = None) -> list[dict]:
    """Read sanitized rows with start-inclusive/end-exclusive timestamps.

``None`` or an empty user id means the exact local owner, never all owners.
Authenticated callers must supply their verified user id explicitly.
Only the latest MAX_TURNS overlapping this range are read. Reports disclose
when this cap is reached rather than treating a bounded ledger as complete.
"""
    if (type(start_ms) is not int or type(end_ms) is not int
            or not 0 <= start_ms < end_ms <= 2**63 - 1):
        raise ValueError("invalid_range")
    if user_id is not None and not isinstance(user_id, str):
        raise ValueError("invalid_user")
    path = _database_path()
    if not path.is_file():
        return []
    try:
        with closing(sqlite3.connect(path, timeout=0.1)) as db:
            db.row_factory = sqlite3.Row
            rows = db.execute(
                "SELECT " + ",".join(_PUBLIC_FIELDS) + " FROM turns "
                "WHERE owner_hash = ? AND ended_at > ? AND started_at < ? "
                "ORDER BY started_at DESC, request_id DESC LIMIT ?",
                (_owner_hash(user_id), start_ms, end_ms, MAX_TURNS),
            ).fetchall()
        return [{**dict(row), "router_enabled": bool(row["router_enabled"]),
                 "routed": bool(row["routed"])} for row in reversed(rows)]
    except (OSError, sqlite3.Error):
        return []
