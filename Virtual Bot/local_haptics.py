"""Best-effort trackpad ticks for the dashboard running directly on this Mac."""
from __future__ import annotations

import asyncio
import os
import sys
import time
from threading import Lock
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Request, Response


_OSASCRIPT = "/usr/bin/osascript"
_MIN_INTERVAL = 0.1
_PROCESS_TIMEOUT = 1.0
_LOOPBACK_HOSTS = {"localhost", "127.0.0.1", "::1"}
# Public AppKit constants: alignment is a discrete guide/detent, not a generic
# alert. Immediate delivery does not require an osascript drawing/run loop.
_TICK_SCRIPT = """ObjC.import('AppKit');
$.NSHapticFeedbackManager.defaultPerformer.performFeedbackPatternPerformanceTime(
    $.NSHapticFeedbackPatternAlignment, $.NSHapticFeedbackPerformanceTimeNow);
"""


class LocalHaptics:
    def __init__(self) -> None:
        self._gate = Lock()
        self._last_tick = float("-inf")

    def available(self) -> bool:
        # This identifies the native bridge, not whether a Force Touch trackpad
        # is connected or the user's system settings permit tactile feedback.
        return sys.platform == "darwin" and os.access(_OSASCRIPT, os.X_OK)

    async def tick(self) -> bool:
        # No queue: a busy bridge or rapid drag simply drops the extra detents.
        if not self.available() or not self._gate.acquire(blocking=False):
            return False
        try:
            now = time.monotonic()
            if now - self._last_tick < _MIN_INTERVAL:
                return False
            self._last_tick = now
            return await self._perform()
        finally:
            self._gate.release()

    async def _perform(self) -> bool:
        process = None

        async def run() -> bool:
            nonlocal process
            process = await asyncio.create_subprocess_exec(
                _OSASCRIPT, "-l", "JavaScript", "-e", _TICK_SCRIPT,
                stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
            )
            return await process.wait() == 0

        try:
            return await asyncio.wait_for(run(), timeout=_PROCESS_TIMEOUT)
        except (OSError, asyncio.TimeoutError):
            return False
        finally:
            if process is not None and process.returncode is None:
                try:
                    process.kill()
                except OSError:
                    pass
                # Reap even on cancellation, without holding the gate forever.
                try:
                    await asyncio.wait_for(process.wait(), timeout=0.25)
                except (OSError, asyncio.TimeoutError):
                    pass


_haptics = LocalHaptics()


def _origin(value: str) -> tuple[str, str, int] | None:
    """Parse a serialized local origin, rejecting URL paths and credentials."""
    if any(character.isspace() for character in value) or any(character in value for character in "\\?#"):
        return None
    try:
        parsed = urlsplit(value)
        if (
            parsed.scheme not in {"http", "https"}
            or parsed.hostname not in _LOOPBACK_HOSTS
            or parsed.username is not None or parsed.password is not None
            or parsed.path or parsed.query or parsed.fragment
        ):
            return None
        return parsed.scheme, parsed.hostname, parsed.port or (443 if parsed.scheme == "https" else 80)
    except ValueError:
        return None


async def _require_direct_local(request: Request) -> None:
    headers = request.headers
    hosts = headers.getlist("host")
    origins = headers.getlist("origin")
    expected = _origin(f"{request.scope.get('scheme', 'http')}://{hosts[0]}") if len(hosts) == 1 else None
    origin_matches = len(origins) == 1 and _origin(origins[0]) == expected
    if (
        request.client is None or request.client.host not in {"127.0.0.1", "::1"}
        or expected is None
        or any(name == "forwarded" or name.startswith("x-forwarded-") for name in headers)
        or headers.get("sec-fetch-site", "same-origin") not in {"same-origin", "none"}
        or (origins and not origin_matches)
        or (request.method == "POST" and (
            not origin_matches or headers.getlist("x-blink-haptic") != ["1"]
        ))
    ):
        raise HTTPException(
            status_code=403, detail={"code": "local_haptics_forbidden"},
            headers={"Cache-Control": "no-store"},
        )


def router(require_operator) -> APIRouter:
    routes = APIRouter(
        prefix="/api/local/haptics",
        dependencies=[Depends(_require_direct_local), Depends(require_operator)],
    )

    @routes.get("")
    async def capability(response: Response):
        response.headers["Cache-Control"] = "no-store"
        available = _haptics.available()
        return {"available": available, "kind": "macos-trackpad" if available else None}

    @routes.post("/tick")
    async def tick(response: Response):
        response.headers["Cache-Control"] = "no-store"
        return {"accepted": await _haptics.tick()}

    return routes
