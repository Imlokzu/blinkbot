"""Memory observations use real functions, synthetic notes, no app or providers."""

import asyncio
import ast
import atexit
from contextlib import nullcontext
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import types
from unittest.mock import patch


REPOSITORY = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
if sys.platform == "darwin":
    subprocess.run(["osascript", "-e", "set volume output muted true"],
                   check=True, timeout=5, capture_output=True)
retained = {key: os.environ[key] for key in ("PATH", "TMPDIR", "LANG", "LC_ALL")
            if key in os.environ}
os.environ.clear()
os.environ.update(retained)
temporary = tempfile.TemporaryDirectory(prefix="blink-audit-20261013-")
atexit.register(temporary.cleanup)
ROOT = Path(temporary.name).resolve()
active = {"root": ROOT}


def guard_io(event, args):
    if event == "open" and isinstance(args[0], (str, bytes, os.PathLike)):
        path = Path(os.fsdecode(args[0]))
        if path.name == ".env" or path.name.startswith(".env."):
            raise FileNotFoundError("Dotenv reads disabled in audit fixture")
    if event in {"socket.connect", "socket.getaddrinfo", "socket.bind"}:
        raise OSError("Network disabled in audit fixture")


sys.addaudithook(guard_io)
brain = types.ModuleType("brain_context")
brain.BRAIN_DIR = ROOT
brain.get_active_brain_root = lambda: active["root"]
sys.modules["brain_context"] = brain
sys.path.insert(0, str(REPOSITORY / "Virtual Bot"))
import memory


def profile_read_failure():
    active["root"] = ROOT / "profile-case"
    profile = active["root"] / "people/user.md"
    profile.parent.mkdir(parents=True)
    original = "# Synthetic profile\n\n- Existing fact one\n- Existing fact two\n"
    profile.write_text(original, encoding="utf-8")
    original_read = Path.read_text
    failures = []

    def fail_once(path, *args, **kwargs):
        if path == profile and not failures:
            failures.append(True)
            raise OSError("Synthetic one-time text read failure")
        return original_read(path, *args, **kwargs)

    with patch.object(Path, "read_text", fail_once):
        memory.append_user_profile("New synthetic fact")
    result = profile.read_text(encoding="utf-8")
    assert len(failures) == 1 and "New synthetic fact" in result
    assert "Existing fact one" not in result and "Existing fact two" not in result
    return {"injected_read_failures": len(failures), "append_returned_normally": True,
            "existing_fact_count_before": 2, "existing_fact_count_after": 0,
            "new_fact_written": True}


def recovery_catalog():
    active["root"] = ROOT / "catalog-case"
    root = active["root"]
    live = root / "topics/live.md"
    backup = root / ".dream-recovery-fixture/backups/topics/old.md"
    staged = root / ".dream-recovery-fixture/writes/topics/new.md"
    for path, content in ((live, "# Live synthetic note"), (backup, "# Old recovery copy"),
                          (staged, "# Uncommitted staged note")):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
    listed = memory.list_notes()
    recovery = [item for item in listed if item["path"].startswith(".dream-recovery-")]
    rejections = []
    for item in recovery:
        try:
            memory.read_note(item["path"])
        except memory.BrainPathError:
            rejections.append(item["path"])
        else:
            raise AssertionError("Expected hidden recovery path to be rejected")
    assert len(recovery) == len(rejections) == 2
    return {"total_catalog_rows": len(listed), "recovery_rows": recovery,
            "recovery_rows_rejected_by_reader": len(rejections)}


def directory_fsync_handles():
    active["root"] = ROOT / "fsync-case"
    active["root"].mkdir()
    original_open, original_fsync = os.open, os.fsync
    directory_handles = []

    def opened(path, flags, *args, **kwargs):
        descriptor = original_open(path, flags, *args, **kwargs)
        if Path(path) == active["root"]:
            directory_handles.append(descriptor)
        return descriptor

    def fsync(descriptor):
        if descriptor in directory_handles:
            raise OSError("Synthetic unsupported directory fsync")
        return original_fsync(descriptor)

    with patch.object(memory.os, "open", opened), patch.object(memory.os, "fsync", fsync):
        memory._atomic_write(active["root"] / "fixture.md", "# Inert fixture")
    still_open = []
    for descriptor in directory_handles:
        try:
            os.fstat(descriptor)
            still_open.append(descriptor)
        except OSError:
            pass
    try:
        assert len(still_open) == 1
        return {"injected_directory_fsync_failure": True, "write_returned_normally": True,
                "unclosed_directory_handles": len(still_open)}
    finally:
        for descriptor in still_open:
            os.close(descriptor)


async def event_loop_lock_wait():
    active["root"] = ROOT / "lock-case"
    root = active["root"]
    root.mkdir()
    # Execute the actual endpoint body without importing main.app or lifespan.
    tree = ast.parse((REPOSITORY / "Virtual Bot/main.py").read_text())
    function = next(node for node in tree.body if isinstance(node, ast.AsyncFunctionDef)
                    and node.name == "api_memory_save")
    function.decorator_list = []

    class HttpError(Exception):
        def __init__(self, status_code, detail):
            self.status_code = status_code
            self.detail = detail

    async def owner(_request):
        return ""

    namespace = {"MemorySaveRequest": object, "Request": object, "_require_user": owner,
                 "_brain_context": lambda *_args: nullcontext(), "memory": memory, "HTTPException": HttpError}
    exec(compile(ast.Module(body=[function], type_ignores=[]), "main.py", "exec"), namespace)
    acquire = memory.acquire_brain_mutation_lock
    holder = acquire(root, timeout_s=0)
    timer = asyncio.create_task(asyncio.sleep(0.01))
    await asyncio.sleep(0)
    started = time.monotonic()
    try:
        # Shorten only the wait budget; use the real polling/open/flock logic.
        with patch.object(memory, "acquire_brain_mutation_lock", lambda selected=None: acquire(selected, timeout_s=0.12, poll_s=0.02)):
            try:
                await namespace["api_memory_save"](types.SimpleNamespace(path="topics/fixture.md", content="fixture", session_id=""), object())
            except HttpError as error:
                status = error.status_code
            else:
                raise AssertionError("Expected contended mutation failure")
        elapsed = time.monotonic() - started
        unfinished = not timer.done()
    finally:
        memory.release_brain_mutation_lock(holder)
        await timer
    assert status == 500 and elapsed >= 0.1 and unfinished
    return {"fixture_wait_seconds": 0.12, "production_wait_seconds": memory._MUTATION_LOCK_TIMEOUT_S,
            "elapsed_seconds": round(elapsed, 3), "ten_ms_timer_unfinished_after_handler": unfinished,
            "endpoint_status": status}


async def main():
    results = {"profile_read_failure": profile_read_failure(), "recovery_catalog": recovery_catalog(),
               "directory_fsync_handles": directory_fsync_handles(), "event_loop_lock_wait": await event_loop_lock_wait()}
    print(json.dumps(results, indent=2, sort_keys=True))
    temporary.cleanup()


if __name__ == "__main__":
    asyncio.run(main())
