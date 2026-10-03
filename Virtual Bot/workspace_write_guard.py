"""One reservation shared by mobile compare-and-save and workspace writers."""

from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager, contextmanager
from contextvars import ContextVar
from pathlib import Path
from threading import Lock
from weakref import WeakValueDictionary

_registry: WeakValueDictionary[Path, Lock] = WeakValueDictionary()
_registry_lock = Lock()
_reserved: ContextVar[frozenset[Path]] = ContextVar("workspace_reserved_paths", default=frozenset())


def _lock(path: Path):
    with _registry_lock:
        guard = _registry.get(path)
        if guard is None:
            guard = Lock()
            _registry[path] = guard
        return guard


@contextmanager
def writing(path: Path):
    path = path.resolve()
    if path in _reserved.get():
        yield
    else:
        guard = _lock(path)
        if not guard.acquire(blocking=False):
            raise ValueError("workspace_write_busy")
        try:
            yield
        finally:
            guard.release()


@asynccontextmanager
async def mobile_reservation(_user_id: str, _session_id: str, relative: str):
    import workspace

    path = workspace._resolve(relative).resolve()
    guard = _lock(path)
    # Waiting in short async intervals avoids blocking the event loop and
    # does not strand a thread-acquired lock if the request is cancelled.
    while not guard.acquire(blocking=False):
        await asyncio.sleep(0.01)
    token = _reserved.set(_reserved.get() | {path})
    try:
        yield
    finally:
        _reserved.reset(token)
        guard.release()
