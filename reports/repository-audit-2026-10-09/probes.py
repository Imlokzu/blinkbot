"""Produce synthetic UI-tool fixtures without configuration, network, or lifespan."""

import asyncio
import json
import os
from pathlib import Path
import subprocess
import sys
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


def guard_io(event, args):
    if event == "open" and isinstance(args[0], (str, bytes, os.PathLike)):
        name = Path(os.fsdecode(args[0])).name
        if name == ".env" or name.startswith(".env."):
            raise FileNotFoundError("Dotenv disabled in audit fixture")
    if event in {"socket.connect", "socket.getaddrinfo", "socket.bind"}:
        raise OSError("Network disabled in audit fixture")


sys.addaudithook(guard_io)
tools = types.ModuleType("tools")
tools.__path__ = [str(REPOSITORY / "Virtual Bot/tools")]
events = types.ModuleType("events")
brain = types.ModuleType("brain_context")
brain.get_active_clerk_user = lambda: "synthetic-audit-owner"
sys.modules.update(tools=tools, events=events, brain_context=brain)
sys.path.insert(0, str(REPOSITORY / "Virtual Bot"))
from tools import ui_tools
from tool_activity import ActivityLog


async def observe(name, arguments):
    captures = []
    events.publish_ui = lambda kind, data, audience=None: captures.append({
        "kind": kind, "data": data, "audience": audience,
    })
    log = ActivityLog()
    log.record({"type": "tool_start", "tool": name, "call_id": "audit-" + name,
                "input": arguments})
    with patch.object(ui_tools.uuid, "uuid4", return_value=types.SimpleNamespace(hex="0" * 32)):
        result = await ui_tools.HANDLERS[name](**arguments)
    log.record({"type": "tool_done", "tool": name, "call_id": "audit-" + name,
                "result": result})
    assert result.get("ok") and len(captures) == 1
    return {"tool_ok": True, "step": log.finish()[0], "published": captures[0]}


async def main():
    observations = {
        "free_text_question": await observe("ask_question", {"question": "What should this fixture be called?"}),
        "choice_normalization": await observe("show_choice", {
            "title": "Pick one", "options": [{"label": "a" * 100, "description": "Inert fixture"}],
        }),
        "todo_normalization": await observe("todo_list", {"title": "Audit checklist", "items": ["b" * 180]}),
    }
    assert observations["free_text_question"]["published"]["data"]["options"] == []
    assert len(observations["choice_normalization"]["published"]["data"]["options"][0]["label"]) == 80
    assert len(observations["todo_normalization"]["published"]["data"]["items"][0]["text"]) == 180
    print(json.dumps(observations, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
