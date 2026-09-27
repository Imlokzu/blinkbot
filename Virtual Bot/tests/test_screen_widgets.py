"""
Timers and the weather tile: state shared by the bot (tools) and the screen.

"Put a ten minute timer on" is said to the bot but rings on the screen, and
"how long is left?" is asked of the bot about what the screen shows — so
both must read one state, and it must survive a restart.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

import screen_widgets
from tools import registry, timer_tools


@pytest.fixture(autouse=True)
def isolated_state(tmp_path: Path):
    clock = {"now": 1_000_000.0}
    with patch.object(screen_widgets, "_state_path", lambda: tmp_path / "screen-widgets.json"), \
         patch.object(screen_widgets, "_now", lambda: clock["now"]), \
         patch.object(screen_widgets.events, "publish") as publish:
        screen_widgets._weather_cache.clear()
        yield clock, publish


def _run(coro):
    # Not asyncio.run: on Python 3.9 it leaves no current loop behind, and
    # the old TestClient used further down needs one.
    loop = asyncio.new_event_loop()
    try:
        return loop.run_until_complete(coro)
    finally:
        loop.close()


class TestTimers:
    def test_set_timer_from_minutes_counts_down(self, isolated_state):
        clock, publish = isolated_state
        result = _run(timer_tools.set_timer(minutes=10, label="чай"))
        assert result["ok"] and result["timer"]["seconds"] == 600
        clock["now"] += 125
        [timer] = screen_widgets.list_timers()
        assert timer["label"] == "чай" and timer["state"] == "running"
        assert timer["left"] == pytest.approx(475)
        # The screen hears about it, and switches to the timer tile
        kinds = [c.args[0].get("type") for c in publish.call_args_list]
        assert "timer" in kinds and "screen" in kinds

    def test_status_splits_what_is_left_for_speech(self, isolated_state):
        _run(timer_tools.set_timer(hours=1, minutes=2, seconds=3))
        status = _run(timer_tools.timer_status())
        assert status["timers"][0]["left_parts"] == {"hours": 1, "minutes": 2, "seconds": 3}

    def test_a_rung_timer_is_still_listed_as_done_for_a_while(self, isolated_state):
        clock, _ = isolated_state
        _run(timer_tools.set_timer(seconds=30, label="паста"))
        clock["now"] += 60
        assert screen_widgets.list_timers()[0]["state"] == "done"
        clock["now"] += screen_widgets.DONE_KEEP_S + 1
        assert screen_widgets.list_timers() == []

    def test_pause_freezes_and_resume_continues(self, isolated_state):
        clock, _ = isolated_state
        _run(timer_tools.set_timer(minutes=5))
        clock["now"] += 60
        _run(timer_tools.timer_control("pause"))
        clock["now"] += 600                       # paused time does not count
        assert screen_widgets.list_timers()[0]["left"] == pytest.approx(240)
        _run(timer_tools.timer_control("resume"))
        clock["now"] += 40
        assert screen_widgets.list_timers()[0]["left"] == pytest.approx(200)

    def test_control_by_label_and_cancel_all(self, isolated_state):
        _run(timer_tools.set_timer(minutes=3, label="чай"))
        _run(timer_tools.set_timer(minutes=8, label="паста"))
        added = _run(timer_tools.timer_control("add", label="ПАСТ", minutes=2))
        assert added["changed"][0]["label"] == "паста"
        assert {t["label"]: t["left"] for t in screen_widgets.list_timers()}["паста"] == pytest.approx(600)
        _run(timer_tools.timer_control("cancel", label="чай"))
        assert [t["label"] for t in screen_widgets.list_timers()] == ["паста"]
        _run(timer_tools.timer_control("cancel_all"))
        assert screen_widgets.list_timers() == []

    def test_nonsense_durations_and_unknown_timers_are_errors(self, isolated_state):
        assert "error" in _run(timer_tools.set_timer())
        assert "error" in _run(timer_tools.set_timer(hours=30))
        assert "error" in _run(timer_tools.timer_control("cancel", label="нема"))
        assert "error" in _run(timer_tools.timer_control("explode"))

    def test_timers_survive_a_restart(self, isolated_state, tmp_path: Path):
        _run(timer_tools.set_timer(minutes=1, label="чай"))
        assert (tmp_path / "screen-widgets.json").is_file()
        assert screen_widgets._load()["timers"][0]["label"] == "чай"

    def test_the_brain_sees_the_tools(self):
        assert {"set_timer", "timer_control", "timer_status"} <= registry.tool_names()


class TestTimerApi:
    def test_screen_buttons_drive_the_same_state(self, isolated_state):
        import main

        asyncio.set_event_loop(asyncio.new_event_loop())
        client = TestClient(main.app)
        r = client.post("/api/screen/timers", json={"action": "set", "seconds": 300, "label": "чай"})
        assert r.status_code == 200 and r.json()["timers"][0]["seconds"] == 300
        tid = r.json()["timers"][0]["id"]
        r = client.post("/api/screen/timers", json={"action": "add", "id": tid, "seconds": 60})
        assert r.json()["timers"][0]["left"] == pytest.approx(360)
        assert client.post("/api/screen/timers", json={"action": "set", "seconds": 0}).status_code == 400
        assert client.post("/api/screen/timers", json={"action": "boom"}).status_code == 422


class TestWeatherTile:
    def test_the_tile_shows_what_the_bot_just_looked_up(self, isolated_state):
        _, publish = isolated_state
        answer = {"city": "Львів", "temperature": 12, "condition": "Хмарно", "forecast": []}

        async def fake_get_weather(city):
            return answer

        with patch.object(registry, "get_weather", fake_get_weather):
            _run(registry.execute_tool("weather", {"city": "Львів"}))
        event = publish.call_args.args[0]
        assert event["type"] == "weather" and event["weather"]["temperature"] == 12

    def test_weather_is_cached_between_tile_refreshes(self, isolated_state):
        clock, _ = isolated_state
        calls = []

        async def fake_get_weather(city):
            calls.append(city)
            return {"city": city, "temperature": 5}

        with patch("tools.weather.get_weather", fake_get_weather):
            _run(screen_widgets.weather_now("Kyiv"))
            _run(screen_widgets.weather_now("Kyiv"))
            clock["now"] += screen_widgets.WEATHER_TTL_S + 1
            _run(screen_widgets.weather_now("Kyiv"))
        assert calls == ["Kyiv", "Kyiv"]

    def test_city_is_remembered(self, isolated_state):
        assert screen_widgets.weather_city() == screen_widgets.DEFAULT_CITY
        screen_widgets.set_weather_city("  Львів ")
        assert screen_widgets.weather_city() == "Львів"
        with pytest.raises(ValueError):
            screen_widgets.set_weather_city("   ")


class TestReminders:
    def test_reminder_in_an_hour_is_a_reminder_not_a_timer(self, isolated_state):
        result = _run(timer_tools.set_reminder("випити воду", hours=1))
        assert result["ok"] and result["reminder"]["kind"] == "reminder"
        assert result["reminder"]["label"] == "випити воду"
        # Timers and reminders have separate limits: five kitchen timers do
        # not block a reminder for tonight
        for _ in range(screen_widgets.MAX_TIMERS):
            _run(timer_tools.set_timer(minutes=1))
        assert _run(timer_tools.set_reminder("ще одне", minutes=5))["ok"]

    def test_clock_time_means_the_next_such_time(self):
        from datetime import datetime, timezone
        now = datetime(2026, 9, 27, 20, 0, tzinfo=timezone.utc)
        assert timer_tools._seconds_until("20:30", now) == 30 * 60
        assert timer_tools._seconds_until("19:00", now) == 23 * 3600      # passed → tomorrow
        assert timer_tools._seconds_until("2026-09-29 20:00", now) == 2 * 86400
        with pytest.raises(ValueError):
            timer_tools._seconds_until("пізніше", now)
        with pytest.raises(ValueError):
            timer_tools._seconds_until("25:00", now)

    def test_a_fired_reminder_waits_longer_than_a_timer(self, isolated_state):
        clock, _ = isolated_state
        _run(timer_tools.set_reminder("зателефонувати", minutes=1))
        _run(timer_tools.set_timer(minutes=1, label="чай"))
        clock["now"] += 60 + screen_widgets.DONE_KEEP_S + 5
        left = screen_widgets.list_timers()
        assert [t["kind"] for t in left] == ["reminder"] and left[0]["state"] == "done"

    def test_a_reminder_needs_text_and_a_sane_time(self, isolated_state):
        assert "error" in _run(timer_tools.set_reminder("  ", minutes=5))
        assert "error" in _run(timer_tools.set_reminder("щось", hours=24 * 8))


class TestNotices:
    def test_a_repeating_problem_is_one_notice_with_a_count(self, isolated_state):
        _, publish = isolated_state
        for _ in range(3):
            screen_widgets.notify("brain.offline", code="brainOffline", level="error")
        [notice] = screen_widgets.list_notices()
        assert notice["count"] == 3 and notice["level"] == "error"
        assert publish.call_args.args[0]["type"] == "notice"

    def test_fixed_problems_clear_themselves(self, isolated_state):
        screen_widgets.notify("tts.fallback", code="ttsFallback", params={"reason": "quota"})
        screen_widgets.notify("", title="Готово", body="Звіт лежить у робочій теці", source="bot")
        assert screen_widgets.resolve("tts.fallback") is True
        assert [n["title"] for n in screen_widgets.list_notices()] == ["Готово"]
        assert screen_widgets.resolve("tts.fallback") is False

    def test_dismiss_one_or_all(self, isolated_state):
        a = screen_widgets.notify("", title="a")
        screen_widgets.notify("", title="b")
        assert screen_widgets.dismiss_notice(a["id"]) == 1
        assert [n["title"] for n in screen_widgets.list_notices()] == ["b"]
        assert screen_widgets.dismiss_notice("all") == 1
        assert screen_widgets.list_notices() == []

    def test_the_shade_does_not_grow_forever(self, isolated_state):
        clock, _ = isolated_state
        for i in range(screen_widgets.MAX_NOTICES + 5):
            clock["now"] += 1
            screen_widgets.notify("", title=f"n{i}")
        notices = screen_widgets.list_notices()
        assert len(notices) == screen_widgets.MAX_NOTICES
        assert notices[0]["title"] == f"n{screen_widgets.MAX_NOTICES + 4}"   # newest first

    def test_bot_tool_posts_a_notice(self, isolated_state):
        result = _run(timer_tools.post_notification("Завдання", "Зібрав звіт", "info"))
        assert result["ok"] and screen_widgets.list_notices()[0]["source"] == "bot"
        assert "error" in _run(timer_tools.post_notification())

    def test_tts_fallback_raises_a_notice_and_success_clears_it(self, isolated_state):
        import main

        class Cloud:
            MEDIA_TYPE = "audio/mpeg"
            fail = True

            @classmethod
            def synthesize(cls, text, speaker, speed):
                if cls.fail:
                    raise RuntimeError("ElevenLabs 401: quota_exceeded")
                return b"cloud"

        asyncio.set_event_loop(asyncio.new_event_loop())
        client = TestClient(main.app)
        with patch.object(main, "_tts_backend", lambda: ("elevenlabs", Cloud)), \
             patch.object(main.piper_voice, "is_available", lambda: True), \
             patch.object(main.piper_voice, "synthesize", lambda text, speaker, speed: b"piper"):
            assert client.post("/api/tts", json={"text": "привіт"}).content == b"piper"
            [notice] = screen_widgets.list_notices()
            assert notice["code"] == "ttsFallback" and notice["params"]["reason"] == "quota"
            Cloud.fail = False
            assert client.post("/api/tts", json={"text": "привіт"}).content == b"cloud"
            assert screen_widgets.list_notices() == []
