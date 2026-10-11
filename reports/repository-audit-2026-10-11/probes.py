"""Isolated timer/publication observations using synthetic state and adapters."""

import asyncio
import atexit
from datetime import datetime
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
from unittest.mock import patch
from zoneinfo import ZoneInfo


REPOSITORY = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
if sys.platform == "darwin":
    subprocess.run(["osascript", "-e", "set volume output muted true"],
                   capture_output=True, check=True, timeout=5)
retained = {key: os.environ[key] for key in ("PATH", "TMPDIR", "LANG", "LC_ALL")
            if key in os.environ}
os.environ.clear()
os.environ.update(retained)
temporary = tempfile.TemporaryDirectory(prefix="blink-audit-20261011-")
atexit.register(temporary.cleanup)
ROOT = Path(temporary.name)


def guard_io(event, args):
    if event == "open" and isinstance(args[0], (str, bytes, os.PathLike)):
        name = Path(os.fsdecode(args[0])).name
        if name == ".env" or name.startswith(".env."):
            raise FileNotFoundError("Dotenv reads disabled in audit fixture")
    if event in {"socket.connect", "socket.getaddrinfo", "socket.bind"}:
        raise OSError("Network disabled in audit fixture")


sys.addaudithook(guard_io)
configuration = types.ModuleType("app_config")
configuration.BASE_DIR = ROOT
events = types.ModuleType("events")
events.publish = lambda *_args, **_kwargs: None
workspace = types.ModuleType("workspace")
workspace._resolve = lambda path, **_kwargs: ROOT / path
tools = types.ModuleType("tools")
tools.__path__ = [str(REPOSITORY / "Virtual Bot/tools")]
sys.modules.update(app_config=configuration, events=events, workspace=workspace, tools=tools)
sys.path.insert(0, str(REPOSITORY / "Virtual Bot"))
import screen_widgets
import site_share
from tools import timer_tools


def timer_restart():
    clock = {"now": 1000.0}
    with patch.object(screen_widgets, "_now", lambda: clock["now"]):
        initial = screen_widgets.add_timer(1, "Inert timer")
        clock["now"] = 1002.0
        completed = screen_widgets.list_timers()[0]
        restarted = screen_widgets.update_timer("add", initial["id"], 60)[0]
        clock["now"] = 1062.0
        completed_again = screen_widgets.list_timers()[0]
    assert initial["id"] == completed["id"] == restarted["id"] == completed_again["id"]
    assert completed["state"] == completed_again["state"] == "done" and restarted["state"] == "running"
    return {"initial": initial, "completed": completed, "restarted": restarted, "completed_again": completed_again}


def reminder_dst():
    zone = ZoneInfo("Europe/Berlin")
    observations = []
    # Native astimezone() returns a fixed-offset tzinfo for the current instant.
    # Passing that fixed-offset form models the real default path deterministically.
    for current, requested, expected in (
        (datetime(2026, 10, 24, 20, 0, tzinfo=zone), "2026-10-25 09:00", datetime(2026, 10, 25, 9, 0, tzinfo=zone)),
        (datetime(2026, 3, 28, 20, 0, tzinfo=zone), "2026-03-29 09:00", datetime(2026, 3, 29, 9, 0, tzinfo=zone)),
    ):
        fixed = datetime.fromisoformat(current.isoformat())
        duration = timer_tools._seconds_until(requested, fixed)
        target_stamp = current.timestamp() + duration
        actual = datetime.fromtimestamp(target_stamp, zone)
        correct_duration = expected.timestamp() - current.timestamp()
        observations.append({"requested": requested, "computed_seconds": duration,
            "correct_seconds": correct_duration, "actual_local": actual.isoformat(),
            "error_seconds": duration - correct_duration})
    assert [item["error_seconds"] for item in observations] == [-3600.0, 3600.0]
    return observations


async def concurrent_publication():
    state = ROOT / "shares-concurrent.json"
    state.write_text("{}", encoding="utf-8")
    arrivals = 0
    ready = asyncio.Event()
    remote = {"registry": {}}
    restarts = []

    async def put(shares):
        nonlocal arrivals
        arrivals += 1
        if arrivals == 2:
            ready.set()
        await asyncio.wait_for(ready.wait(), 3)
        remote["registry"] = dict(shares)

    async def dns(_slug):
        return None

    with patch.object(site_share, "STATE_PATH", state), patch.object(site_share, "_put_ingress", put), \
         patch.object(site_share, "_ensure_dns", dns), patch.object(site_share, "_restart_cloudflared", lambda: restarts.append(True)):
        results = await asyncio.gather(site_share.share("one", "audit-one"), site_share.share("two", "audit-two"))
    local = json.loads(state.read_text())
    assert all("url" in item for item in results) and len(local) == len(remote["registry"]) == 1
    return {"successful_calls": len(results), "local_slugs": sorted(local),
            "remote_slugs": sorted(remote["registry"]), "fixture_restarts": len(restarts)}


async def partial_publication():
    state = ROOT / "shares-partial.json"
    original = {"audit-existing": "old"}
    state.write_text(json.dumps(original), encoding="utf-8")
    remote = {"registry": dict(original)}
    restarts = []

    async def put(shares):
        remote["registry"] = dict(shares)

    async def dns(_slug):
        raise RuntimeError("Synthetic DNS failure")

    with patch.object(site_share, "STATE_PATH", state), patch.object(site_share, "_put_ingress", put), \
         patch.object(site_share, "_ensure_dns", dns), patch.object(site_share, "_restart_cloudflared", lambda: restarts.append(True)):
        result = await site_share.share("new", "audit-new")
    local = json.loads(state.read_text())
    assert "error" in result and local == original and remote["registry"] != local and not restarts
    return {"reported_error": result["error"], "local_slugs": sorted(local),
            "remote_slugs": sorted(remote["registry"]), "fixture_restarts": len(restarts)}


async def main():
    print(json.dumps({"timer_restart": timer_restart(), "reminder_dst": reminder_dst(),
        "concurrent_publication": await concurrent_publication(),
        "partial_publication": await partial_publication()}, indent=2, sort_keys=True))
    temporary.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
