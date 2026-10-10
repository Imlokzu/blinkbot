"""Synthetic project and interaction observations; no app, credentials or network."""

import atexit
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
from threading import Event, local
import types
from unittest.mock import patch


REPOSITORY = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
if sys.platform == "darwin":
    subprocess.run(["osascript", "-e", "set volume output muted true"],
                   check=True, capture_output=True, timeout=5)
retained = {key: os.environ[key] for key in ("PATH", "TMPDIR", "LANG", "LC_ALL")
            if key in os.environ}
os.environ.clear()
os.environ.update(retained)
temporary = tempfile.TemporaryDirectory(prefix="blink-audit-20261010-")
atexit.register(temporary.cleanup)
ROOT = Path(temporary.name)


def guard_io(event, args):
    if event == "open" and isinstance(args[0], (str, bytes, os.PathLike)):
        path = Path(os.fsdecode(args[0]))
        if path.name == ".env" or path.name.startswith(".env."):
            raise FileNotFoundError("Dotenv reads disabled in audit fixture")
    if event in {"socket.connect", "socket.getaddrinfo", "socket.bind"}:
        raise OSError("Network disabled in audit fixture")


sys.addaudithook(guard_io)
workspace = types.ModuleType("workspace")
workspace.code_root = lambda: ROOT / "code"
workspace.code_root().mkdir()
brain = types.ModuleType("brain_context")
brain.USER_DATA_DIR = ROOT / "users"
brain.get_active_clerk_user = lambda: ""
brain.clerk_user_dir_name = lambda value: "synthetic-owner"
profile = types.ModuleType("profile_store")
profile.load = lambda: {"name": "Synthetic fixture"}
sys.modules.update(workspace=workspace, brain_context=brain, profile_store=profile)
sys.path.insert(0, str(REPOSITORY / "Virtual Bot"))
import projects
import chat_store
import chat_interactions


def malformed_metadata():
    healthy = projects.create_project("Healthy fixture")
    broken = projects.create_project("Broken fixture")
    meta = workspace.code_root() / broken["id"] / ".project.json"
    observations = {}
    for label, value in (("array", []), ("invalid_created", {"name": "Fixture", "created": "not-a-time"})):
        meta.write_text(json.dumps(value), encoding="utf-8")
        try:
            projects.list_projects()
        except Exception as error:
            observations[label] = type(error).__name__
        else:
            raise AssertionError("Malformed metadata should reproduce the failure")
    meta.write_text(json.dumps({"name": {"unexpected": "object"}, "created": 1}), encoding="utf-8")
    observations["unvalidated_name_type"] = type(projects.get_project(broken["id"])["name"]).__name__
    assert observations == {"array": "AttributeError", "invalid_created": "ValueError", "unvalidated_name_type": "dict"}
    assert (workspace.code_root() / healthy["id"]).is_dir()
    meta.write_text('{"name":"Recovered fixture","created":1}', encoding="utf-8")
    return observations


def symlink_boundary():
    outside = ROOT / "outside-project"
    outside.mkdir()
    meta = outside / ".project.json"
    meta.write_text('{"name":"Outside fixture","created":1}', encoding="utf-8")
    alias = workspace.code_root() / "linked-fixture"
    alias.symlink_to(outside, target_is_directory=True)
    discovered = next(value for value in projects.list_projects() if value["id"] == alias.name)
    result = projects.rename_project(alias.name, "Changed through project link")
    observations = {"listed_outside_name": discovered["name"], "rename_returned_name": result["name"],
                    "outside_metadata_changed": json.loads(meta.read_text())["name"] == result["name"]}
    assert observations["outside_metadata_changed"]
    alias.unlink()
    # A fixed temporary filename also follows a pre-existing file symlink.
    inside = projects.create_project("Temp-link fixture")
    victim = ROOT / "outside-file.json"
    victim.write_text('{"inert":"original"}', encoding="utf-8")
    (workspace.code_root() / inside["id"] / ".project.json.tmp").symlink_to(victim)
    projects.rename_project(inside["id"], "Changed through temp link")
    observations["outside_temp_target_changed"] = json.loads(victim.read_text())["name"] == "Changed through temp link"
    assert observations["outside_temp_target_changed"]
    return observations


def rename_collision():
    selected = projects.create_project("Rename fixture")
    first_written, second_written, first_promoted = Event(), Event(), Event()
    caller = local()
    original_write, original_replace = Path.write_text, Path.replace

    def write(path, data, *args, **kwargs):
        if path.name != ".project.json.tmp":
            return original_write(path, data, *args, **kwargs)
        if caller.name == "B":
            assert first_written.wait(3), "First writer did not reach fixture gate"
        result = original_write(path, data, *args, **kwargs)
        if caller.name == "A":
            first_written.set()
            assert second_written.wait(3), "Second writer did not reach fixture gate"
        else:
            second_written.set()
        return result

    def replace(path, target):
        if path.name != ".project.json.tmp":
            return original_replace(path, target)
        if caller.name == "B":
            assert first_promoted.wait(3), "First writer did not promote fixture"
        result = original_replace(path, target)
        if caller.name == "A":
            first_promoted.set()
        return result

    def rename(name):
        caller.name = name
        try:
            return {"requested": name, "returned": projects.rename_project(selected["id"], name)["name"]}
        except Exception as error:
            return {"requested": name, "error": type(error).__name__}

    with patch.object(Path, "write_text", write), patch.object(Path, "replace", replace):
        with ThreadPoolExecutor(max_workers=2) as executor:
            a, b = executor.submit(rename, "A"), executor.submit(rename, "B")
            observations = [a.result(timeout=5), b.result(timeout=5)]
    assert observations == [{"requested": "A", "returned": "B"}, {"requested": "B", "error": "FileNotFoundError"}]
    return observations


def expired_interaction_capacity():
    chat_store.CHATS_DIR = ROOT / "chats"
    chat_store.CHATS_DIR.mkdir()
    transcript = chat_store.CHATS_DIR / "fixture-chat.json"

    def source(call_id):
        transcript.write_text(json.dumps({"id": "fixture-chat", "messages": [{"role": "assistant", "steps": [{
            "id": call_id, "label": "todo_list", "status": "done", "input": {"items": ["Synthetic task"]},
        }]}]}), encoding="utf-8")

    action = chat_interactions.Action(action="toggle", item_id="item-0", done=True, expected_revision=0)
    with patch.object(chat_interactions, "MAX_RECORDS", 2):
        for call in ("expired-1", "expired-2"):
            source(call)
            chat_interactions.update("fixture-chat", call, action)
        source("current-3")
        try:
            chat_interactions.update("fixture-chat", "current-3", action)
        except chat_interactions.InteractionError as error:
            code = error.code
        else:
            raise AssertionError("Expected stale rows to exhaust fixture capacity")
    with sqlite3.connect(chat_store.CHATS_DIR / ".interactions.sqlite3") as connection:
        stored = [value[0] for value in connection.execute("SELECT call_id FROM actions ORDER BY call_id")]
    live = [step["id"] for message in chat_store.load("fixture-chat")["messages"] for step in message["steps"]]
    assert code == "interaction_limit" and stored == ["expired-1", "expired-2"] and live == ["current-3"]
    return {"fixture_limit": 2, "production_limit": chat_interactions.MAX_RECORDS,
            "live_call_ids": live, "stored_call_ids": stored, "new_action_error": code}


def main():
    print(json.dumps({"project_metadata": malformed_metadata(), "project_symlinks": symlink_boundary(),
        "project_concurrent_rename": rename_collision(), "expired_interaction_capacity": expired_interaction_capacity()},
        indent=2, sort_keys=True))
    temporary.cleanup()


if __name__ == "__main__":
    main()
