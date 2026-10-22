"""Small, content-free activity snapshots for the living office.

Only live scopes are retained. A failed scope emits a transient help cue;
reconnecting after it has ended sees idle. Private brain roots are mapped
from authenticated chat/SSE contexts, never guessed from their directory.
"""
from __future__ import annotations

import asyncio
from collections import OrderedDict
from contextlib import contextmanager
from contextvars import ContextVar
from functools import wraps
from itertools import count
from pathlib import Path
from threading import RLock

import brain_context

_lock = RLock()
_ids = count()
_jobs: dict[str, dict[int, tuple[int, str]]] = {}
_roots: OrderedDict[str, str] = OrderedDict()
_priority: ContextVar[int] = ContextVar("office_priority", default=0)
_MAX_JOBS = 256
_MAX_ROOTS = 512
_UNSET = object()
MEMORY_TOOLS = frozenset({
    "memory_search", "memory_read", "memory_write", "memory_list", "save_memory",
    "save_fact", "create_brain_file", "create_brain_directory", "read_brain_file",
    "update_brain_file", "delete_brain_file", "list_brain_files", "search_memory",
    "list_brain_navigation",
})


def register_root(root: Path, audience: str) -> None:
    """Remember only identities already established by an authenticated scope."""
    key = str(root.resolve())
    with _lock:
        _roots[key] = audience
        _roots.move_to_end(key)
        while len(_roots) > _MAX_ROOTS:
            _roots.popitem(last=False)


def register_subscriber(audience: str | None) -> None:
    if audience:
        root = brain_context.BRAIN_RUNTIME_DIR / brain_context.clerk_user_dir_name(audience) / "brain"
        register_root(root, audience)


def current_audience() -> str:
    audience = brain_context.get_active_clerk_user() or ""
    if audience:
        # Utility callers may carry a user without a brain-root context. Do
        # not accidentally assign the shared template to that private user.
        register_subscriber(audience)
    else:
        register_root(brain_context.get_active_brain_root(), audience)
    return audience


def dream_audience() -> str | None:
    """Unknown private/session roots are silent rather than broadcast locally."""
    root = brain_context.get_active_brain_root().resolve()
    with _lock:
        if str(root) in _roots:
            return _roots[str(root)]
    if root == brain_context.BRAIN_DIR.resolve():
        return ""
    return None


def snapshot(audience: str | None = None) -> dict:
    with _lock:
        jobs = _jobs.get(audience or "", {})
        activity = max(jobs.items(), key=lambda item: (item[1][0], item[0]))[1][1] if jobs else "idle"
    return {"type": "office_activity", "activity": activity, "active": bool(jobs)}


def _publish(audience: str, *, failed: bool = False) -> None:
    import events
    state = snapshot(audience)
    if failed and not state["active"]:
        state["activity"] = "help"
    events.publish(state, audience=audience)


class Activity:
    failed = False


@contextmanager
def activity(kind: str, *, audience=_UNSET, priority: int | None = None):
    """A finally-balanced scope; newer equal-priority jobs win without erasure."""
    handle = Activity()
    if audience is _UNSET:
        audience = current_audience()
    if audience is None:
        yield handle
        return
    rank = priority if priority is not None else max(100, _priority.get())
    with _lock:
        # Bound exceptional caller fan-out without changing the underlying job.
        token = next(_ids) if sum(map(len, _jobs.values())) < _MAX_JOBS else None
        if token is not None:
            _jobs.setdefault(audience, {})[token] = (rank, kind)
    if token is None:
        yield handle
        return
    marker = _priority.set(rank)
    _publish(audience)
    try:
        yield handle
    except asyncio.CancelledError:
        raise
    except Exception:
        handle.failed = True
        raise
    finally:
        _priority.reset(marker)
        with _lock:
            jobs = _jobs.get(audience, {})
            jobs.pop(token, None)
            if not jobs:
                _jobs.pop(audience, None)
        _publish(audience, failed=handle.failed)


def tool_kind(name: str) -> str:
    name = name.rsplit("__", 1)[-1]
    if name in MEMORY_TOOLS:
        return "read"
    if name in {"ask_question", "show_choice"}:
        return "help"
    return "work"


def chat_turn(function):
    """Cover all callers of brains.chat, including mobile and messengers."""
    @wraps(function)
    async def tracked(*args, **kwargs):
        with activity("work", priority=100) as job:
            result = await function(*args, **kwargs)
            if isinstance(result, tuple) and len(result) > 2 and result[2] == "offline":
                job.failed = True
            return result
    return tracked
