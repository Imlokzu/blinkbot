"""Live office cues must follow actual work and preserve audience boundaries."""
import asyncio
import json
from unittest.mock import patch

import pytest

import brain_context
import brains
import dream_cycle
import events
import office_activity as office


async def _next_event(stream) -> dict:
    """The next event that is not a log line. Once another test has installed
    the log handler, "SSE client connected" rides the same bus ahead of the
    snapshot and the probe; an office cue must still never slip in between."""
    while True:
        line = await anext(stream)
        if line.startswith("data: "):
            payload = json.loads(line[6:])
            if payload.get("type") != "log":
                return payload


@pytest.fixture(autouse=True)
def isolated_office(monkeypatch):
    monkeypatch.setattr(office, "_jobs", {})
    monkeypatch.setattr(office, "_roots", office.OrderedDict())
    monkeypatch.setattr(events, "_shutting_down", False)
    monkeypatch.setattr(events, "_latest_todo", {})


def test_foreground_interrupts_memory_and_restores_it():
    with office.activity("organize", audience="", priority=10):
        assert office.snapshot()["activity"] == "organize"
        with office.activity("work", audience="", priority=100):
            with office.activity("read", audience=""):
                assert office.snapshot()["activity"] == "read"
            assert office.snapshot()["activity"] == "work"
        assert office.snapshot()["activity"] == "organize"
    assert office.snapshot() == {"type": "office_activity", "activity": "idle", "active": False}
    assert office._jobs == {}


def test_overlapping_turns_do_not_finish_each_other():
    async def run():
        release = asyncio.Event()
        started = asyncio.Event()
        async def second():
            with office.activity("read", audience="a"):
                started.set()
                await release.wait()
        with office.activity("work", audience="a"):
            task = asyncio.create_task(second())
            await started.wait()
        assert office.snapshot("a")["activity"] == "read"
        release.set()
        await task
        assert not office.snapshot("a")["active"]
    asyncio.run(run())


def test_cancel_and_failure_release_live_state():
    async def run():
        with patch.object(events, "publish") as publish:
            with pytest.raises(asyncio.CancelledError):
                with office.activity("work", audience="a"):
                    raise asyncio.CancelledError
            assert publish.call_args.args[0]["activity"] == "idle"
            with pytest.raises(ValueError):
                with office.activity("read", audience="a"):
                    raise ValueError("private details must never reach the office")
            assert publish.call_args.args[0] == {"type": "office_activity", "activity": "help", "active": False}
            assert office.snapshot("a")["activity"] == "idle"
    asyncio.run(run())


def test_connect_replays_only_current_audience_and_repairs_a_missed_end(monkeypatch):
    async def run():
        with office.activity("read", audience="a"):
            stream = events.sse_stream("a")
            assert await anext(stream) == ": ping\n\n"
            first = json.loads((await anext(stream))[6:])
            assert first["activity"] == "read"
            assert office.snapshot()["activity"] == "idle"
        # Simulate a full subscriber queue dropping the terminal update.
        for q in events._subscribers:
            while not q.empty():
                q.get_nowait()
        monkeypatch.setattr(events, "KEEPALIVE_S", 0.01)
        repaired = json.loads((await anext(stream))[6:])
        assert repaired["activity"] == "idle"
        await stream.aclose()
    asyncio.run(run())


def test_local_cues_do_not_leak_into_an_authenticated_screen():
    async def run():
        stream = events.sse_stream("a")
        assert await anext(stream) == ": ping\n\n"
        assert (await _next_event(stream))["type"] == "office_activity"  # the idle snapshot
        with office.activity("work", audience=""):
            events.publish({"type": "probe"}, audience="a")
            assert await _next_event(stream) == {"type": "probe"}
        await stream.aclose()
    asyncio.run(run())


def test_private_cues_do_not_leak_to_local_or_another_user():
    async def run():
        for audience in (None, "b"):
            stream = events.sse_stream(audience)
            assert await anext(stream) == ": ping\n\n"
            assert (await _next_event(stream))["type"] == "office_activity"
            with office.activity("organize", audience="a", priority=10):
                events.publish({"type": "probe"}, audience=audience)
                assert await _next_event(stream) == {"type": "probe"}
            await stream.aclose()
    asyncio.run(run())


def test_subscription_resolves_private_dream_without_a_previous_chat(tmp_path, monkeypatch):
    monkeypatch.setattr(brain_context, "BRAIN_RUNTIME_DIR", tmp_path)
    root = tmp_path / brain_context.clerk_user_dir_name("a") / "brain"
    with brain_context.set_brain_root(root):
        assert office.dream_audience() is None
        office.register_subscriber("a")
        assert office.dream_audience() == "a"
        with office.activity("organize", audience=office.dream_audience(), priority=10):
            assert office.snapshot("a")["activity"] == "organize"
            assert not office.snapshot()["active"]


def test_real_brains_chat_balances_work_and_memory_before_return(monkeypatch):
    monkeypatch.setattr(brains.cfg, "get_openclaw_token", lambda: "")
    monkeypatch.setattr(brains.cfg, "CHAT_DEMO_FALLBACK", True)
    monkeypatch.setattr(brains, "build_system_prompt", lambda *a, **kw: "")
    async def demo(*a, **kw):
        assert office.snapshot()["activity"] == "work"
        return "hello", "happy", []
    monkeypatch.setattr(brains, "chat_demo", demo)
    asyncio.run(brains.chat("hello"))
    assert not office.snapshot()["active"]


def test_real_memory_tool_and_dream_hooks(monkeypatch):
    async def tool(name, args):
        assert office.snapshot()["activity"] == "read"
        return {"ok": True}
    monkeypatch.setattr(brains.tool_registry, "execute_tool", tool)
    asyncio.run(brains._execute_tool_traced("memory_search", {"query": "private"}))
    assert not office.snapshot()["active"]
    async def consolidate(retention):
        assert office.snapshot()["activity"] == "organize"
        return False
    monkeypatch.setattr(dream_cycle, "_dream_cycle", consolidate)
    monkeypatch.setattr(office, "dream_audience", lambda: "")
    with patch.object(events, "publish") as publish:
        assert not asyncio.run(dream_cycle.dream_cycle())
        assert publish.call_args.args[0]["activity"] == "help"
    assert not office.snapshot()["active"]


def test_tool_kinds_follow_the_registered_tools():
    # The office reads a book for memory, works for everything else, and
    # asks for help only for the question cards. Names arrive namespaced
    # from MCP (workspace__memory_search), so the suffix decides.
    import tools
    names = {schema["function"]["name"] for schema in tools.list_tools()}
    assert {"memory_search", "list_brain_navigation", "create_brain_file", "ask_question", "facts"} <= names
    for name in names:
        if "brain" in name or "memory" in name:
            assert office.tool_kind(name) == "read", name
        elif name in {"ask_question", "show_choice"}:
            assert office.tool_kind(name) == "help", name
        else:
            assert office.tool_kind(name) == "work", name
    assert office.tool_kind("workspace__memory_search") == "read"
    assert office.tool_kind("facts") == "work"       # a Wikipedia lookup, not the bot's memory
