"""Exercise actual Blink routes with synthetic state and no production services."""
from __future__ import annotations

import builtins
import contextlib
import io
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
from unittest.mock import patch

sys.dont_write_bytecode = True
REPO = Path(__file__).resolve().parents[2]
BASE = REPO / "Virtual Bot"


def run():
    retained = {k: os.environ[k] for k in ("PATH", "TMPDIR", "LANG", "LC_ALL") if k in os.environ}
    os.environ.clear()
    os.environ.update(retained)
    if sys.platform == "darwin":
        subprocess.run(["osascript", "-e", "set volume output muted true"], check=True)
    with tempfile.TemporaryDirectory(prefix="blink-wave1-smoke-") as directory, contextlib.chdir(directory):
        temp = Path(directory).resolve()
        os.environ.update({
            "CLERK_DISABLED": "1", "CLERK_ISSUER": "https://clerk.fixture.invalid",
            "VBOT_OFFLINE": "1", "OPENCLAW_CONFIG_PATH": str(temp / "openclaw.json"),
            "VBOT_JEV_STATE_FILE": str(temp / "jev.json"), "VBOT_USAGE_DB": str(temp / "usage.sqlite3"),
            "MOBILE_DATABASE_PATH": str(temp / "mobile.sqlite3"),
            "VBOT_INTEGRATIONS_DIR": str(temp / "integrations"),
            "VBOT_MODEL_INTEL_FILE": str(temp / "models.json"),
            "MOBILE_API_ORIGIN": "https://mobile.fixture.invalid",
        })
        forbidden = [BASE / name for name in (
            "user_data", "runtime", "uploads", "brain", "bot_profile.json",
            "config.yaml", "store/installed", "store/shared",
        )]

        def guard(event, args):
            if event == "open" and isinstance(args[0], (str, bytes, os.PathLike)):
                path = Path(os.fsdecode(args[0])).resolve()
                if path.name.startswith(".env") or any(path.is_relative_to(p) for p in forbidden):
                    raise PermissionError("Smoke blocks owner state and credentials")
            if event in {"socket.connect", "socket.bind"}:
                address = args[1]
                if isinstance(address, tuple) and address[0] not in {"127.0.0.1", "::1", "localhost"}:
                    raise PermissionError("Smoke permits only loopback sockets")
            if event == "socket.getaddrinfo" and args[0] not in {None, "127.0.0.1", "::1", "localhost"}:
                raise PermissionError("Smoke blocks external DNS")
            if event == "subprocess.Popen" and Path(os.fsdecode(args[0])).name not in {"curl", "osascript"}:
                raise PermissionError("Smoke blocks unrelated subprocesses")

        sys.addaudithook(guard)
        sys.path.insert(0, str(BASE))
        original_open = builtins.open

        def fixture_config(file, *args, **kwargs):
            if isinstance(file, (str, bytes, os.PathLike)) and Path(os.fsdecode(file)).resolve() == BASE / "config.yaml":
                mode = args[0] if args else kwargs.get("mode", "r")
                return io.BytesIO(b"{}") if "b" in mode else io.StringIO("{}")
            return original_open(file, *args, **kwargs)

        with patch("builtins.open", fixture_config), patch("io.open", fixture_config):
            import app_config as cfg
        for name in ("BRAIN_DIR", "WORKSPACE_DIR", "CODE_DIR", "UPLOADS_DIR", "SERVICE_LOGS_DIR", "STORE_DIR"):
            setattr(cfg, name, temp / name.lower())
        cfg.STORE_DIR.mkdir()
        shutil.copytree(BASE / "store/packages/clock", cfg.STORE_DIR / "packages/clock")
        import brain_context
        brain_context.USER_DATA_DIR = temp / "users"
        brain_context.BRAIN_RUNTIME_DIR = temp / "users"
        import main
        import profile_store
        profile_store.PROFILE_PATH = temp / "profile.json"
        main.chat_store.CHATS_DIR = temp / "chats"
        main.screen_widgets._state_path = lambda: temp / "widgets.json"
        import uvicorn

        with socket.socket() as listener, socket.socket() as unavailable:
            listener.bind(("127.0.0.1", 0))
            port = listener.getsockname()[1]
            unavailable.bind(("127.0.0.1", 0))
            empty_origin = f"http://127.0.0.1:{unavailable.getsockname()[1]}"
            for name in ("OMNI_BASE_URL", "OPENCLAW_BASE_URL", "CHAT2API_BASE_URL", "VISION_BASE_URL", "DISPLAY_BASE_URL"):
                setattr(cfg, name, empty_origin)
            server = uvicorn.Server(uvicorn.Config(main.app, host="127.0.0.1", port=port,
                lifespan="off", access_log=False, log_level="warning", timeout_graceful_shutdown=3))
            thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]})
            thread.start()
            checks = []

            def request(path, expected=200, payload=None):
                destination = temp / "response"
                command = ["curl", "-q", "--noproxy", "*", "--silent", "--show-error", "--max-time", "10",
                    "--output", str(destination), "--write-out", "%{http_code}"]
                if payload is not None:
                    command += ["--header", "Content-Type: application/json", "--data-binary", json.dumps(payload)]
                status = int(subprocess.check_output(command + [f"http://127.0.0.1:{port}{path}"], text=True))
                assert status == expected, (path, status, destination.read_text()[:300])
                checks.append({"path": path, "status": status})
                return destination.read_bytes()

            try:
                deadline = time.monotonic() + 15
                while not server.started:
                    assert thread.is_alive() and time.monotonic() < deadline
                    time.sleep(0.05)
                for path in ("/", "/screen", "/api/status", "/api/setup", "/api/screen/timers"):
                    request(path)
                profile = json.loads(request("/api/setup", payload={"name": "SmokeCrab", "greeting": "A gentle hello", "language": "en"}))["profile"]
                assert profile["greeting"] == "A gentle hello" and profile["name"] == "SmokeCrab"
                assert json.loads(request("/api/setup"))["profile"] == profile
                request("/api/chat/context?message=hello")
                timers = json.loads(request("/api/screen/timers", payload={"action": "set", "seconds": 60, "label": "Synthetic timer"}))["timers"]
                timer = timers[0]
                assert timer["kind"] == "timer" and timer["activation"] == 1
                request("/api/screen/timers", payload={"action": "pause", "id": timer["id"]})
                request("/api/screen/timers", payload={"action": "resume", "id": timer["id"]})
                request("/api/screen/timers", payload={"action": "cancel", "id": timer["id"]})
                alarms = json.loads(request("/api/screen/timers", payload={"action": "set", "kind": "reminder",
                    "at": "09:00", "repeat": "weekdays", "timezone": "Europe/Berlin", "label": "Synthetic alarm"}))["timers"]
                assert alarms[0]["repeat"] == "weekdays" and alarms[0]["timezone"] == "Europe/Berlin"
                request("/api/screen/timers", payload={"action": "pause", "id": alarms[0]["id"]})
                request("/api/screen/timers", payload={"action": "resume", "id": alarms[0]["id"]})
                request("/api/screen/timers", payload={"action": "add", "id": alarms[0]["id"], "seconds": 60}, expected=400)
                request("/api/screen-store/install", payload={"id": "clock"})
                assert request("/store-apps/clock/index.html") == (BASE / "store/packages/clock/index.html").read_bytes()
                for asset in ("i18n.js", "timer-delivery.js", "screen.js"):
                    assert request("/static/screen/" + asset) == (BASE / "static/screen" / asset).read_bytes()
                request("/api/memory/file?path=..%2Foutside.md", 400)
                request("/api/workspace/file?path=..%2Foutside", 400)
            finally:
                server.should_exit = True
                thread.join(timeout=10)
                assert not thread.is_alive(), "Smoke server did not stop"
        with socket.socket() as check:
            assert check.connect_ex(("127.0.0.1", port)) != 0
        print(json.dumps({"checks": checks, "total": len(checks), "server_stopped": True, "port_closed": port}, indent=2))


if __name__ == "__main__":
    run()
