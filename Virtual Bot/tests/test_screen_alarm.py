"""Calendar alarms persist once and never burst after offline time."""
import asyncio
from datetime import datetime
from unittest.mock import patch
from zoneinfo import ZoneInfo

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import screen_widgets as sw
from tools import timer_tools


def stamp(value, zone="Europe/Berlin", fold=0):
    return datetime.fromisoformat(value).replace(tzinfo=ZoneInfo(zone), fold=fold).timestamp()


@pytest.fixture(autouse=True)
def state(tmp_path):
    clock = {"now": stamp("2026-10-23T08:00")}
    with patch.object(sw, "_state_path", lambda: tmp_path / "widgets.json"), \
         patch.object(sw, "_now", lambda: clock["now"]), \
         patch.object(sw.events, "publish") as publish:
        yield clock, publish


def alarm(at="09:00", repeat="daily", label="Morning"):
    return sw.add_alarm(at, label, repeat, "Europe/Berlin")


def test_api_and_tool_share_record(state):
    app = FastAPI(); app.include_router(sw.router)
    with TestClient(app) as client:
        response = client.post("/api/screen/timers", json={"action":"set", "kind":"reminder",
            "at":"9:00", "label":"Morning", "repeat":"daily", "timezone":"Europe/Berlin"})
    assert response.status_code == 200
    first = response.json()["timers"][0]
    second = asyncio.run(timer_tools.set_reminder("Morning", at="09.00", repeat="daily", timezone="Europe/Berlin"))["reminder"]
    assert {k:v for k,v in first.items() if k != "id"} == {k:v for k,v in second.items() if k != "id"}
    assert first["at"] == "09:00"


def test_weekdays_skip_weekend_and_resume(state):
    clock, _ = state
    item = alarm(repeat="weekdays")
    sw.update_timer("pause", item["id"])
    clock["now"] = stamp("2026-10-24T10:00")
    assert sw.process_due_alarms() == 0
    resumed = sw.update_timer("resume", item["id"])[0]
    assert resumed["ends_at"] == stamp("2026-10-26T09:00")
    assert resumed["activation"] == 2
    sw.update_timer("cancel", item["id"])
    assert sw.list_timers() == []


def test_spring_gap_first_valid_minute(state):
    clock, _ = state
    clock["now"] = stamp("2026-03-29T00:00")
    assert alarm("02:30")["ends_at"] == stamp("2026-03-29T03:00")


def test_fold_uses_first_occurrence_only(state):
    clock, _ = state
    clock["now"] = stamp("2026-10-25T00:00")
    item = alarm("02:30")
    assert item["ends_at"] == stamp("2026-10-25T02:30", fold=0)
    clock["now"] = item["ends_at"]
    assert sw.process_due_alarms() == 1
    assert sw.list_timers()[0]["ends_at"] == stamp("2026-10-26T02:30")
    # Creation during the second 02:00 does not use the second 02:30.
    clock["now"] = stamp("2026-10-25T02:10", fold=1)
    assert alarm("02:30")["ends_at"] == stamp("2026-10-26T02:30")


def test_autumn_day_is_25_hours(state):
    clock, _ = state
    clock["now"] = stamp("2026-10-24T08:00")
    item = alarm()
    clock["now"] = item["ends_at"]
    sw.process_due_alarms()
    assert sw.list_timers()[0]["ends_at"] - item["ends_at"] == 25 * 3600


def test_batch_persists_before_publish_and_deduplicates(state):
    clock, publish = state
    first, second = alarm(), alarm(label="Second")
    clock["now"] = first["ends_at"] + 60
    observed = []
    def observe(event):
        if event["type"] == "timer":
            observed.append(event)
            assert all(t["activation"] == 2 for t in sw._load()["timers"])
            assert len(sw._load()["notices"]) == 2
    publish.side_effect = observe
    assert sw.process_due_alarms() == 2
    assert len(observed) == 1
    assert {t["id"] for t in observed[0]["due"]} == {first["id"], second["id"]}
    assert all(t["activation"] == 1 for t in observed[0]["due"])
    assert sw.process_due_alarms() == 0
    assert len(observed) == 1


def test_restart_skips_many_missed_days_with_one_notice(state):
    clock, publish = state
    item = alarm()
    clock["now"] += 20 * 86400
    publish.reset_mock()
    assert sw.process_due_alarms() == 1
    assert publish.call_args_list[0].args[0]["due"] == []
    current = sw.list_timers()[0]
    assert current["ends_at"] > clock["now"]
    assert current["activation"] == 2
    notice = sw.list_notices()[0]
    assert notice["key"] == "alarm:" + item["id"]
    assert notice["code"] == "alarmMissed"
    clock["now"] = current["ends_at"]
    sw.process_due_alarms()
    assert len(sw.list_notices()) == 1
    assert sw.list_notices()[0]["count"] == 2


def test_failed_save_emits_nothing_and_keeps_old_occurrence(state):
    clock, publish = state
    item = alarm(); clock["now"] = item["ends_at"]
    before = sw._state_path().read_bytes()
    publish.reset_mock()
    with patch.object(sw, "_save", side_effect=OSError("disk full")), pytest.raises(OSError):
        sw.process_due_alarms()
    publish.assert_not_called()
    assert sw._state_path().read_bytes() == before


def test_overdue_schedules_count_against_both_creation_paths(state):
    clock, _ = state
    for i in range(sw.MAX_REMINDERS):
        alarm(label=str(i))
    clock["now"] += 20 * 86400
    with pytest.raises(ValueError, match="at most"):
        alarm()
    with pytest.raises(ValueError, match="at most"):
        sw.add_timer(60, "Other", "reminder")
    assert sw.add_timer(60)["kind"] == "timer"


def test_extending_recurring_alarm_rejects_without_mutation(state):
    item = alarm()
    before = sw._state_path().read_bytes()
    assert "error" in asyncio.run(timer_tools.timer_control("add", item["id"], minutes=10))
    assert sw._state_path().read_bytes() == before


@pytest.mark.parametrize("fields", [
    {"at":"25:00"}, {"at":"12:60"}, {"timezone":"not/a/zone"},
    {"repeat":"sometimes"}, {"label":" "},
])
def test_invalid_clock_values(fields):
    kwargs = dict(at="09:00", label="Morning", repeat="daily", timezone="Europe/Berlin")
    kwargs.update(fields)
    with pytest.raises(ValueError):
        sw.add_alarm(**kwargs)
    assert not sw._state_path().exists()


def test_api_rejects_ambiguous_fields():
    app = FastAPI(); app.include_router(sw.router)
    with TestClient(app) as client:
        for payload in [{"at":"09:00"}, {"repeat":"daily"}, {"timezone":"UTC"},
                        {"kind":"reminder", "at":"09:00", "seconds":60}]:
            assert client.post("/api/screen/timers", json={"action":"set", "label":"Test", **payload}).status_code == 400
    assert not sw.list_timers()


def test_legacy_relative_and_dated_reminders():
    assert asyncio.run(timer_tools.set_reminder("Water", minutes=1))["ok"]
    assert asyncio.run(timer_tools.set_reminder("Water", minutes=1, at=None))["ok"]
    with patch.object(timer_tools, "_seconds_until", return_value=90):
        assert asyncio.run(timer_tools.set_reminder("Dated", at="2026-10-24 09:00"))["reminder"]["seconds"] == 90
    assert "error" in asyncio.run(timer_tools.set_reminder("No time", repeat="daily"))
    assert alarm(repeat="none")["repeat"] == "none"


def test_zone_fallback_is_explicit():
    with patch.dict("os.environ", {"TZ":""}), patch.object(sw.Path, "resolve", side_effect=OSError):
        assert sw.add_alarm("09:00", "Morning")["timezone"] == "UTC"


def test_loop_is_idempotent_and_cancellable():
    async def run():
        with patch.object(sw, "process_due_alarms") as process:
            sw.start_alarm_loop(); first = sw._alarm_task
            sw.start_alarm_loop(); assert sw._alarm_task is first
            await asyncio.sleep(0)
            process.assert_called_once()
            await sw.stop_alarm_loop()
            assert first.done() and sw._alarm_task is None
            await sw.stop_alarm_loop()
    asyncio.run(run())


@pytest.mark.parametrize("damaged", [{"timezone":"removed/zone"}, {"at":"25:00"}, {"at":None}])
def test_invalid_schedule_pauses_without_starving_other_alarms(state, damaged):
    clock, publish = state
    invalid, valid = alarm(label="Invalid"), alarm(label="Valid")
    data = sw._load()
    data["timers"][0].update(damaged)
    sw._save(data)
    clock["now"] = valid["ends_at"]
    publish.reset_mock()
    assert sw.process_due_alarms() == 2
    batches = [call.args[0] for call in publish.call_args_list if call.args[0].get("action") == "alarms_due"]
    assert len(batches) == 1
    assert [item["id"] for item in batches[0]["due"]] == [valid["id"]]
    assert sw.list_timers()[0]["state"] == "paused"
    assert {notice["code"] for notice in sw.list_notices()} == {"alarmDue", "alarmInvalid"}
    assert sw.process_due_alarms() == 0
