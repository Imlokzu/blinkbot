"""A browser must never vibrate another user's host or queue delayed ticks."""
from __future__ import annotations

import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

from fastapi import FastAPI, HTTPException, Request
import httpx
import pytest

import local_haptics


@pytest.fixture(autouse=True)
def isolated_bridge(monkeypatch):
    # Every test defaults to a tripwire: neither capability nor a bad request
    # may accidentally reach the real trackpad on a developer's Mac.
    monkeypatch.setattr(local_haptics, "_haptics", local_haptics.LocalHaptics())
    monkeypatch.setattr(local_haptics.asyncio, "create_subprocess_exec", AsyncMock(
        side_effect=AssertionError("unexpected_native_haptic"),
    ))


def request(method="GET", *, headers=None, base="http://127.0.0.1:8100",
            peer="127.0.0.1", require_auth=False):
    async def operator(request: Request):
        if require_auth and request.headers.get("authorization") != "Bearer operator":
            raise HTTPException(status_code=403, detail="operator_required")

    app = FastAPI()
    app.include_router(local_haptics.router(operator))

    async def run():
        transport = httpx.ASGITransport(app=app, client=(peer, 12345))
        async with httpx.AsyncClient(transport=transport, base_url=base) as client:
            path = "/api/local/haptics/tick" if method == "POST" else "/api/local/haptics"
            return await client.request(method, path, headers=headers)

    return asyncio.run(run())


def tick_headers(origin="http://127.0.0.1:8100", **extra):
    return {"Origin": origin, "X-Blink-Haptic": "1", **extra}


def test_capability_is_a_silent_bridge_probe_not_a_hardware_promise(monkeypatch):
    monkeypatch.setattr(local_haptics.sys, "platform", "darwin")
    executable = Mock(return_value=True)
    monkeypatch.setattr(local_haptics.os, "access", executable)
    result = request()
    assert result.status_code == 200
    assert result.json() == {"available": True, "kind": "macos-trackpad"}
    assert result.headers["cache-control"] == "no-store"
    executable.assert_called_once_with("/usr/bin/osascript", local_haptics.os.X_OK)
    local_haptics.asyncio.create_subprocess_exec.assert_not_called()


@pytest.mark.parametrize("platform,executable", [("linux", True), ("darwin", False)])
def test_unsupported_hosts_are_quiet_noops(monkeypatch, platform, executable):
    monkeypatch.setattr(local_haptics.sys, "platform", platform)
    monkeypatch.setattr(local_haptics.os, "access", lambda *_: executable)
    assert request().json() == {"available": False, "kind": None}
    result = request("POST", headers=tick_headers())
    assert result.status_code == 200 and result.json() == {"accepted": False}
    local_haptics.asyncio.create_subprocess_exec.assert_not_called()


@pytest.mark.parametrize("options", [
    {"peer": "203.0.113.1"},
    {"peer": "localhost"},
    {"base": "http://dashboard.example:8100"},
    {"headers": {"Origin": "https://evil.example"}},
    {"headers": {"Origin": "null"}},
    {"headers": {"Origin": "http://127.0.0.1:8101"}},
    {"headers": {"Origin": "https://127.0.0.1:8100"}},
    {"headers": {"Origin": "http://localhost:8100"}},
    {"headers": {"Forwarded": "for=127.0.0.1"}},
    {"headers": {"X-Forwarded-For": "127.0.0.1"}},
    {"headers": {"X-Forwarded-Host": "127.0.0.1:8100"}},
    {"headers": {"Sec-Fetch-Site": "cross-site"}},
    {"headers": {"Host": "["}},
    {"headers": {"Host": "localhost:invalid"}},
    {"headers": [("Host", "127.0.0.1:8100"), ("Host", "evil.example")]},
    {"headers": [("Origin", "http://127.0.0.1:8100"), ("Origin", "http://127.0.0.1:8100")]},
])
def test_even_capability_is_only_exposed_to_a_direct_local_dashboard(options):
    result = request(**options)
    assert result.status_code == 403
    assert result.json()["detail"]["code"] == "local_haptics_forbidden"
    local_haptics.asyncio.create_subprocess_exec.assert_not_called()


@pytest.mark.parametrize("headers", [
    {},
    {"Origin": "http://127.0.0.1:8100"},
    {"X-Blink-Haptic": "1"},
    tick_headers("null"),
    tick_headers("http://127.0.0.1:8100/"),
    tick_headers("http://user@127.0.0.1:8100"),
    tick_headers("http://127.0.0.1@evil.example:8100"),
    tick_headers("http://127.0.0.1:8100", **{"X-Blink-Haptic": "0"}),
    tick_headers("http://127.0.0.1:8100", **{"Forwarded": ""}),
    [("Origin", "http://127.0.0.1:8100"), ("X-Blink-Haptic", "1"), ("X-Blink-Haptic", "1")],
])
def test_tick_requires_exact_origin_and_custom_header(headers):
    assert request("POST", headers=headers).status_code == 403
    local_haptics.asyncio.create_subprocess_exec.assert_not_called()


@pytest.mark.parametrize("method", ["GET", "POST"])
def test_operator_auth_is_required_in_addition_to_local_origin(method, monkeypatch):
    monkeypatch.setattr(local_haptics._haptics, "tick", AsyncMock(return_value=True))
    assert request(method, headers=tick_headers(), require_auth=True).status_code == 403
    result = request(method, headers=tick_headers(Authorization="Bearer operator"), require_auth=True)
    assert result.status_code == 200


@pytest.mark.parametrize("base,peer", [
    ("http://127.0.0.1:8100", "127.0.0.1"),
    ("http://localhost:8100", "127.0.0.1"),
    ("http://[::1]:8100", "::1"),
])
def test_tick_accepts_only_same_local_origin(base, peer, monkeypatch):
    performer = AsyncMock(return_value=True)
    monkeypatch.setattr(local_haptics._haptics, "tick", performer)
    result = request("POST", base=base, peer=peer, headers=tick_headers(base))
    assert result.status_code == 200 and result.json() == {"accepted": True}
    assert result.headers["cache-control"] == "no-store"
    performer.assert_awaited_once()


def test_module_is_registered_with_the_existing_operator_guard(monkeypatch):
    import main

    async def reject_operator():
        raise HTTPException(status_code=403, detail="operator_required")

    monkeypatch.setitem(main.app.dependency_overrides, main._require_openclaw_operator, reject_operator)

    async def run():
        transport = httpx.ASGITransport(app=main.app, client=("127.0.0.1", 12345))
        async with httpx.AsyncClient(transport=transport, base_url="http://127.0.0.1:8100") as client:
            for method, path in [("GET", "/api/local/haptics"), ("POST", "/api/local/haptics/tick")]:
                response = await client.request(method, path, headers=tick_headers())
                assert response.status_code == 403
                assert response.json()["detail"] == "operator_required"

    asyncio.run(run())


def test_rapid_ticks_are_dropped_instead_of_queued(monkeypatch):
    bridge = local_haptics.LocalHaptics()
    monkeypatch.setattr(bridge, "available", lambda: True)
    performer = AsyncMock(return_value=True)
    monkeypatch.setattr(bridge, "_perform", performer)
    clock = {"now": 10.0}
    monkeypatch.setattr(local_haptics.time, "monotonic", lambda: clock["now"])

    async def run():
        assert await bridge.tick() is True
        clock["now"] = 10.05
        assert await bridge.tick() is False
        clock["now"] = 10.11
        assert await bridge.tick() is True

    asyncio.run(run())
    assert performer.await_count == 2


def test_concurrent_ticks_are_dropped_while_the_first_performer_is_running(monkeypatch):
    bridge = local_haptics.LocalHaptics()
    monkeypatch.setattr(bridge, "available", lambda: True)
    monkeypatch.setattr(local_haptics, "_MIN_INTERVAL", 0)

    async def run():
        entered, release = asyncio.Event(), asyncio.Event()

        async def perform():
            entered.set()
            await release.wait()
            return True

        monkeypatch.setattr(bridge, "_perform", perform)
        first = asyncio.create_task(bridge.tick())
        await entered.wait()
        assert await bridge.tick() is False
        release.set()
        assert await first is True
        assert await bridge.tick() is True

    asyncio.run(run())


def fake_process(exit_code=0):
    process = SimpleNamespace(returncode=None)

    async def wait():
        process.returncode = exit_code
        return exit_code

    process.wait = AsyncMock(side_effect=wait)
    process.kill = Mock(side_effect=lambda: setattr(process, "returncode", -9))
    return process


@pytest.mark.parametrize("exit_code,accepted", [(0, True), (1, False)])
def test_native_call_has_a_fixed_script_and_suppresses_process_output(monkeypatch, exit_code, accepted):
    process = fake_process(exit_code)
    spawn = AsyncMock(return_value=process)
    monkeypatch.setattr(local_haptics.asyncio, "create_subprocess_exec", spawn)
    assert asyncio.run(local_haptics.LocalHaptics()._perform()) is accepted
    spawn.assert_awaited_once_with(
        "/usr/bin/osascript", "-l", "JavaScript", "-e", local_haptics._TICK_SCRIPT,
        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
    )
    assert "NSHapticFeedbackPatternAlignment" in local_haptics._TICK_SCRIPT
    process.kill.assert_not_called()


def test_process_launch_failure_is_quiet_and_releases_the_inflight_gate(monkeypatch):
    bridge = local_haptics.LocalHaptics()
    monkeypatch.setattr(bridge, "available", lambda: True)
    monkeypatch.setattr(local_haptics, "_MIN_INTERVAL", 0)
    spawn = AsyncMock(side_effect=OSError("not available"))
    monkeypatch.setattr(local_haptics.asyncio, "create_subprocess_exec", spawn)

    async def run():
        assert await bridge.tick() is False
        spawn.side_effect = None
        spawn.return_value = fake_process()
        assert await bridge.tick() is True

    asyncio.run(run())


@pytest.mark.parametrize("cancel", [False, True])
def test_timeout_and_request_cancellation_kill_the_child_and_release_the_gate(monkeypatch, cancel):
    bridge = local_haptics.LocalHaptics()
    monkeypatch.setattr(bridge, "available", lambda: True)
    monkeypatch.setattr(local_haptics, "_MIN_INTERVAL", 0)
    monkeypatch.setattr(local_haptics, "_PROCESS_TIMEOUT", 0.01)

    async def run():
        entered = asyncio.Event()
        process = fake_process()

        async def wait():
            entered.set()
            if process.returncode is None:
                await asyncio.Event().wait()
            return process.returncode

        process.wait = AsyncMock(side_effect=wait)
        spawn = AsyncMock(return_value=process)
        monkeypatch.setattr(local_haptics.asyncio, "create_subprocess_exec", spawn)
        task = asyncio.create_task(bridge.tick())
        await entered.wait()
        if cancel:
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
        else:
            assert await task is False
        process.kill.assert_called_once()
        assert process.wait.await_count == 2
        spawn.return_value = fake_process()
        assert await bridge.tick() is True

    asyncio.run(run())
