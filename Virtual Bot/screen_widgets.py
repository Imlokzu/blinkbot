"""
State behind the screen's live tiles: timers, alarms and the weather tile.

Both are things the bot and the person share. "Put a ten minute timer on"
is said to the bot but ticks on the screen, and "how long is left?" is
asked of the bot about what the screen shows. So the state lives here, on
the server, and both sides read it: the brain through tools
(tools/timer_tools.py), the screen through /api/screen/* and SSE.

Duration timers and one-shot dated reminders keep the original contract:
the server never fires them. It stores when each one ends; the screen
counts down and rings. A recurring clock alarm (daily/weekdays) is the one
exception: a schedule outlives any one screen session, so process_due_alarms()
on a lifespan task advances it to the next LOCAL-CALENDAR occurrence,
persists the new deadline and notices atomically, and lets the screen ring
through its existing audio path. No model or audio call happens here.

Persisted to runtime/screen-widgets.json (runtime/ is not committed), so a
restart in the middle of a pomodoro does not lose it.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time
import uuid
from datetime import datetime, timedelta, timezone as dt_timezone
from pathlib import Path
from threading import Lock
from typing import Any
from zoneinfo import ZoneInfo

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

import app_config
import events

log = logging.getLogger("virtual_bot.screen_widgets")

router = APIRouter(prefix="/api/screen", tags=["screen"])

MAX_TIMERS = 5
MAX_REMINDERS = 20
MAX_TIMER_S = 24 * 3600
# A reminder can be for later this week ("нагадай у пʼятницю"), a kitchen
# timer cannot sensibly run for days.
MAX_REMINDER_S = 7 * 24 * 3600
MAX_LABEL = 40
MAX_REMINDER_TEXT = 200
# A finished timer stays listed for a while, so "did my tea timer go off?"
# still has an answer after it rang. A reminder that fired stays much
# longer: it may have gone off while nobody was in the room, and the
# notification shade is where it waits to be seen.
DONE_KEEP_S = 10 * 60
REMINDER_KEEP_S = 12 * 3600

# ------------------------------------------------------------------ alarms
# A clock alarm is a reminder that owns its own schedule: "at" (HH:MM) in an
# explicit IANA zone, "daily" or on "weekdays". It advances on the alarm's
# LOCAL CALENDAR — never by adding 24 hours of seconds, so a 25-hour DST day
# still lands on the same clock time.
ALARM_REPEATS = ("none", "daily", "weekdays")
_RECURRING = ("daily", "weekdays")
# An occurrence may ring when it is at most this late; anything older only
# becomes a notice, so a long offline period never bursts into sound.
ALARM_LATE_RING_S = 60.0
ALARM_TICK_S = 1.0
ALARM_MAX_DAYS = 8          # search bound for the next local occurrence
ALARM_NOTICE_PREFIX = "alarm:"

_lock = Lock()


def _state_path() -> Path:
    return Path(app_config.BASE_DIR) / "runtime" / "screen-widgets.json"


def _load() -> dict[str, Any]:
    try:
        data = json.loads(_state_path().read_text("utf-8"))
        if isinstance(data, dict):
            return data
    except (OSError, ValueError):
        pass
    return {}


def _save(data: dict[str, Any]) -> None:
    path = _state_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), "utf-8")
    tmp.replace(path)


def _now() -> float:
    return time.time()


# ------------------------------------------------------------------ timers

def _clean_label(label: object) -> str:
    return " ".join(str(label or "").split())[:MAX_LABEL]


def _activation(timer: dict) -> int:
    value = timer.get("activation")
    return value if type(value) is int and value > 0 else 1


def _timer_state(data: dict, now: float) -> list[dict]:
    """Migrate legacy timers once, with one server-owned initial activation."""
    timers = data.get("timers") or []
    migrated = False
    for timer in timers:
        activation = _activation(timer)
        if type(timer.get("activation")) is not int or timer.get("activation") != activation:
            timer["activation"] = activation
            migrated = True
    if migrated:
        _save(data)
    return _alive(timers, now)


def _view(timer: dict, now: float) -> dict:
    """A timer as clients see it: what is left right now, not only when it ends."""
    if timer.get("paused_left") is not None:
        left = float(timer["paused_left"])
        state = "paused"
    else:
        left = max(0.0, float(timer["ends_at"]) - now)
        if timer.get("repeat") in _RECURRING:
            # A recurring alarm is never "done": the backend fires it and the
            # deadline always points at the upcoming occurrence. This also
            # keeps an overdue one counted against the reminder cap.
            state = "running"
        else:
            state = "done" if left <= 0 else "running"
    view = {
        "id": timer["id"],
        "activation": _activation(timer),
        "kind": timer.get("kind", "timer"),
        "label": timer.get("label", ""),
        "seconds": int(timer["seconds"]),
        "ends_at": timer.get("ends_at"),
        "left": round(left, 1),
        "state": state,
    }
    if timer.get("at"):
        # Alarm views carry the schedule that produced them; plain countdown
        # timers and one-shot dated reminders do not.
        view.update({
            "at": timer.get("at"),
            "repeat": timer.get("repeat", "none"),
            "timezone": timer.get("timezone", ""),
        })
    return view


def _alive(timers: list[dict], now: float) -> list[dict]:
    keep = []
    for timer in timers:
        if timer.get("repeat") in _RECURRING:
            # A recurring schedule survives the old one-shot grace pruning,
            # even after days offline: the alarm loop owns its lifetime, not
            # REMINDER_KEEP_S.
            keep.append(timer)
            continue
        grace = REMINDER_KEEP_S if timer.get("kind") == "reminder" else DONE_KEEP_S
        if timer.get("paused_left") is None and float(timer["ends_at"]) + grace < now:
            continue
        keep.append(timer)
    return keep


def list_timers() -> list[dict]:
    now = _now()
    with _lock:
        data = _load()
        timers = _timer_state(data, now)
    return [_view(t, now) for t in sorted(timers, key=lambda t: _view(t, now)["left"])]


def _publish(action: str, timer: dict | None = None) -> None:
    try:
        events.publish({"type": "timer", "action": action, "timer": timer, "timers": list_timers()})
    except Exception:  # noqa: BLE001 — a lost screen update must not fail the tool
        log.exception("Could not publish the timer event")


def add_timer(seconds: float, label: str = "", kind: str = "timer") -> dict:
    """
    Start a timer, or a reminder (kind="reminder": the label is what to
    remind about, and it may be days away). Raises ValueError on a duration
    nobody means.
    """
    seconds = float(seconds)
    reminder = kind == "reminder"
    ceiling = MAX_REMINDER_S if reminder else MAX_TIMER_S
    if not 1 <= seconds <= ceiling:
        raise ValueError("duration must be between 1 second and " + ("7 days" if reminder else "24 hours"))
    now = _now()
    text = " ".join(str(label or "").split())[:MAX_REMINDER_TEXT] if reminder else _clean_label(label)
    if reminder and not text:
        raise ValueError("a reminder needs a text")
    timer = {
        "id": uuid.uuid4().hex[:8],
        "activation": 1,
        "kind": "reminder" if reminder else "timer",
        "label": text,
        "seconds": int(round(seconds)),
        "ends_at": now + seconds,
        "paused_left": None,
    }
    with _lock:
        data = _load()
        timers = _timer_state(data, now)
        running = [t for t in timers
                   if _view(t, now)["state"] != "done" and t.get("kind", "timer") == timer["kind"]]
        limit = MAX_REMINDERS if reminder else MAX_TIMERS
        if len(running) >= limit:
            raise ValueError(f"at most {limit} {'reminders' if reminder else 'timers'} at once")
        timers.append(timer)
        data["timers"] = timers
        _save(data)
    view = _view(timer, now)
    _publish("set", view)
    return view


def _zone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except (KeyError, ValueError) as exc:
        raise ValueError("unknown IANA timezone") from exc


def _default_zone() -> ZoneInfo:
    candidates = [os.environ.get("TZ", "").lstrip(":")]
    try:
        local = str(Path("/etc/localtime").resolve())
        if "/zoneinfo/" in local:
            candidates.append(local.split("/zoneinfo/", 1)[1])
    except OSError:
        pass
    for name in candidates:
        if name:
            try:
                return _zone(name)
            except ValueError:
                pass
    return _zone("UTC")


def _clock_time(at: str) -> str:
    match = re.fullmatch(r"\s*([0-9]{1,2})[:.]([0-9]{2})\s*", at)
    if not match or int(match[1]) > 23 or int(match[2]) > 59:
        raise ValueError("alarm time must be HH:MM")
    return f"{int(match[1]):02d}:{int(match[2]):02d}"


def _calendar_stamp(local: datetime, zone: ZoneInfo) -> float:
    # Round-trip through UTC detects nonexistent local times. fold=0 selects
    # the first occurrence in an autumn overlap; never ring twice that day.
    for minute in range(24 * 60 + 1):
        candidate = (local + timedelta(minutes=minute)).replace(tzinfo=zone, fold=0)
        back = candidate.astimezone(dt_timezone.utc).astimezone(zone)
        if back.replace(tzinfo=None) == candidate.replace(tzinfo=None):
            return candidate.timestamp()
    raise ValueError("no valid local alarm time within one day")


def _next_occurrence(at: str, repeat: str, zone: ZoneInfo, now: float) -> tuple[str, float]:
    at = _clock_time(at)
    if repeat not in ALARM_REPEATS:
        raise ValueError("repeat must be none, daily or weekdays")
    hour, minute = map(int, at.split(":"))
    today = datetime.fromtimestamp(now, zone).date()
    for offset in range(ALARM_MAX_DAYS):
        day = today + timedelta(days=offset)
        if repeat == "weekdays" and day.weekday() >= 5:
            continue
        stamp = _calendar_stamp(datetime(day.year, day.month, day.day, hour, minute), zone)
        if stamp > now:
            return at, stamp
    raise ValueError("could not find the next alarm occurrence")


def add_alarm(at: str, label: str = "", repeat: str = "none", timezone: str = "") -> dict:
    """Create a clock reminder, shared by the screen and the bot tool."""
    zone = _zone(timezone) if timezone else _default_zone()
    now = _now()
    at, stamp = _next_occurrence(at, repeat, zone, now)
    text = " ".join(str(label or "").split())[:MAX_REMINDER_TEXT]
    if not text:
        raise ValueError("a reminder needs a text")
    timer = {"id": uuid.uuid4().hex[:8], "activation": 1, "kind": "reminder",
             "label": text, "seconds": int(round(stamp - now)), "ends_at": stamp,
             "paused_left": None, "at": at, "repeat": repeat, "timezone": zone.key}
    with _lock:
        data = _load()
        timers = _timer_state(data, now)
        active = [t for t in timers if t.get("kind") == "reminder"
                  and (_view(t, now)["state"] != "done" or t.get("repeat") in _RECURRING)]
        if len(active) >= MAX_REMINDERS:
            raise ValueError(f"at most {MAX_REMINDERS} reminders at once")
        data["timers"] = [*timers, timer]
        _save(data)
    view = _view(timer, now)
    _publish("set", view)
    return view


def process_due_alarms() -> int:
    """Advance a batch and save notices before emitting any live occurrence."""
    now = _now()
    due = []
    count = 0
    with _lock:
        data = _load()
        timers = _alive(data.get("timers") or [], now)
        notices = data.get("notices") or []
        for timer in timers:
            if (timer.get("repeat") not in _RECURRING or timer.get("paused_left") is not None
                    or float(timer["ends_at"]) > now):
                continue
            try:
                _, next_stamp = _next_occurrence(timer["at"], timer["repeat"], _zone(timer["timezone"]), now)
            except (KeyError, TypeError, ValueError):
                # A removed timezone or damaged schedule must not starve other
                # alarms or retry noisily every second. Keep it for correction.
                log.warning("Pausing invalid alarm schedule %s", timer["id"])
                timer["paused_left"] = 0.0
                fresh, code = False, "alarmInvalid"
            else:
                fresh = now - float(timer["ends_at"]) <= ALARM_LATE_RING_S
                code = "alarmDue" if fresh else "alarmMissed"
                if fresh:
                    due.append(_view(timer, now))
                timer.update(ends_at=next_stamp, seconds=int(round(next_stamp - now)),
                             activation=_activation(timer) + 1)
            key = ALARM_NOTICE_PREFIX + timer["id"]
            notice = next((n for n in notices if n.get("key") == key), None)
            if notice is None:
                notice = {"id": uuid.uuid4().hex[:8], "key": key, "count": 0}
                notices.append(notice)
            notice.update(code=code,
                          params={"label": timer["label"][:120]}, title="", body="",
                          level="info" if fresh else "warn", source="system", at=now,
                          count=int(notice.get("count") or 0) + 1)
            count += 1
        if not count:
            return 0
        data["timers"] = timers
        data["notices"] = sorted(notices, key=lambda n: -float(n.get("at") or 0))[:MAX_NOTICES]
        _save(data)
        snapshot = [_view(t, now) for t in timers]
    try:
        events.publish({"type": "timer", "action": "alarms_due", "due": due, "timers": snapshot})
    except Exception:  # a disconnected screen does not roll back persisted advancement
        log.exception("Could not publish alarm occurrences")
    _publish_notices("add")
    return count


_alarm_task: asyncio.Task | None = None


async def _alarm_loop() -> None:
    while True:
        try:
            process_due_alarms()
        except Exception:
            log.exception("Could not advance clock alarms")
        await asyncio.sleep(ALARM_TICK_S)


def start_alarm_loop() -> None:
    global _alarm_task
    if _alarm_task is None or _alarm_task.done():
        _alarm_task = asyncio.create_task(_alarm_loop(), name="screen-clock-alarms")


async def stop_alarm_loop() -> None:
    global _alarm_task
    task, _alarm_task = _alarm_task, None
    if task is not None:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass


def _find(timers: list[dict], key: str) -> list[dict]:
    """By id, by label (case-insensitive substring), or "" for the nearest one."""
    key = _clean_label(key).lower()
    if not timers:
        return []
    if not key:
        now = _now()
        live = [t for t in timers if _view(t, now)["state"] != "done"] or timers
        return [min(live, key=lambda t: _view(t, now)["left"])]
    exact = [t for t in timers if t["id"] == key]
    if exact:
        return exact
    return [t for t in timers if key in str(t.get("label", "")).lower()]


def update_timer(action: str, key: str = "", seconds: float = 0) -> list[dict]:
    """
    cancel / pause / resume / add (extend by `seconds`) the timers matching key.
    "all" with cancel clears every timer. Returns the timers that changed.
    Raises ValueError for attempts that would leave broken state (a recurring
    alarm offered to the old countdown `add`), before anything is mutated.
    """
    now = _now()
    with _lock:
        data = _load()
        timers = _timer_state(data, now)
        targets = list(timers) if (action == "cancel" and key == "all") else _find(timers, key)
        if not targets:
            return []
        if action == "add" and any(t.get("repeat") in _RECURRING for t in targets):
            # A recurring alarm owns its schedule; stretching the countdown
            # would silently re-time the next occurrence. Rejected atomically:
            # nothing is mutated or saved.
            raise ValueError("a repeating alarm cannot be extended; cancel it or set a new one")
        for timer in targets:
            if action == "cancel":
                timers.remove(timer)
            elif action == "pause" and timer.get("paused_left") is None:
                timer["paused_left"] = max(0.0, float(timer["ends_at"]) - now)
            elif action == "resume" and timer.get("paused_left") is not None:
                if timer.get("repeat") in _RECURRING:
                    # Resume never replays the pause: skip to the next future
                    # clock occurrence on the alarm's own local calendar.
                    zone = _zone(str(timer.get("timezone") or "UTC"))
                    _, stamp = _next_occurrence(str(timer.get("at", "")), timer["repeat"], zone, now)
                    timer["ends_at"] = round(stamp, 3)
                    timer["seconds"] = int(round(stamp - now))
                    timer["activation"] = _activation(timer) + 1
                else:
                    timer["ends_at"] = now + float(timer["paused_left"])
                timer["paused_left"] = None
            elif action == "add":
                # Zero remaining time ends an activation even if it was then paused.
                exhausted = (float(timer["paused_left"]) <= 0
                             if timer.get("paused_left") is not None
                             else float(timer["ends_at"]) <= now)
                extra = max(-MAX_TIMER_S, min(MAX_TIMER_S, float(seconds)))
                if timer.get("paused_left") is not None:
                    timer["paused_left"] = max(1.0, float(timer["paused_left"]) + extra)
                else:
                    base = max(now, float(timer["ends_at"]))   # extending a rung timer restarts it
                    timer["ends_at"] = max(now + 1, base + extra)
                if exhausted:
                    timer["activation"] = _activation(timer) + 1
                timer["seconds"] = int(timer["seconds"] + max(0, extra))
            else:
                continue
        data["timers"] = timers
        _save(data)
    changed = [_view(t, now) for t in targets]
    _publish(action, changed[0] if changed else None)
    return changed


# ------------------------------------------------------------------ notices

# The notification shade (swipe down from the left). Two kinds of notice:
#   - system ones, raised by the service when something is wrong (no brain,
#     the voice fell back to Piper, recognition failing). Each has a stable
#     `key`, so a failure that repeats every turn updates ONE notice with a
#     count instead of filling the shade, and `resolve(key)` removes it by
#     itself once things work again — a notice about a fixed problem is
#     just noise;
#   - the bot's own (tool post_notification), freeform text.
# System notices carry `code` + `params` and the screen words them in its
# own language; the bot's carry `title`/`body` as written.

MAX_NOTICES = 30
NOTICE_LEVELS = ("info", "warn", "error")


def _notice_view(notice: dict) -> dict:
    return {k: notice.get(k) for k in ("id", "key", "code", "params", "title", "body", "level", "source", "at", "count")}


def list_notices() -> list[dict]:
    with _lock:
        notices = _load().get("notices") or []
    return [_notice_view(n) for n in sorted(notices, key=lambda n: -float(n.get("at") or 0))]


def _publish_notices(action: str, notice: dict | None = None) -> None:
    try:
        events.publish({"type": "notice", "action": action, "notice": notice, "notices": list_notices()})
    except Exception:  # noqa: BLE001 — a lost screen update must not break the caller
        log.exception("Could not publish the notice event")


def notify(key: str = "", *, code: str = "", params: dict | None = None, title: str = "",
           body: str = "", level: str = "info", source: str = "system") -> dict:
    """Raise a notice, or refresh the one with the same key."""
    level = level if level in NOTICE_LEVELS else "info"
    now = _now()
    clean = {
        "code": str(code or "")[:40],
        "params": {str(k)[:20]: str(v)[:120] for k, v in (params or {}).items()},
        "title": " ".join(str(title or "").split())[:80],
        "body": " ".join(str(body or "").split())[:400],
        "level": level,
        "source": str(source or "system")[:20],
        "at": now,
    }
    if not (clean["code"] or clean["title"] or clean["body"]):
        raise ValueError("a notice needs a code, a title or a body")
    key = str(key or "")[:60]
    with _lock:
        data = _load()
        notices = data.get("notices") or []
        same = next((n for n in notices if key and n.get("key") == key), None)
        if same:
            same.update(clean)
            same["count"] = int(same.get("count") or 1) + 1
            notice = same
        else:
            notice = {"id": uuid.uuid4().hex[:8], "key": key, "count": 1, **clean}
            notices.append(notice)
        notices = sorted(notices, key=lambda n: -float(n.get("at") or 0))[:MAX_NOTICES]
        data["notices"] = notices
        _save(data)
    view = _notice_view(notice)
    _publish_notices("add", view)
    return view


def resolve(key: str) -> bool:
    """The problem behind a system notice is gone: drop the notice."""
    if not key:
        return False
    with _lock:
        data = _load()
        notices = data.get("notices") or []
        keep = [n for n in notices if n.get("key") != key]
        if len(keep) == len(notices):
            return False
        data["notices"] = keep
        _save(data)
    _publish_notices("resolve")
    return True


def dismiss_notice(notice_id: str) -> int:
    """The person swiped it away; "all" clears the shade."""
    with _lock:
        data = _load()
        notices = data.get("notices") or []
        keep = [] if notice_id == "all" else [n for n in notices if n.get("id") != notice_id]
        gone = len(notices) - len(keep)
        if gone:
            data["notices"] = keep
            _save(data)
    if gone:
        _publish_notices("dismiss")
    return gone


def notify_safely(key: str, **kwargs) -> None:
    """For hooks inside chat/TTS/ASR: a notice must never break the thing it reports on."""
    try:
        notify(key, **kwargs)
    except Exception:  # noqa: BLE001
        log.exception("Could not raise notice %s", key)


def resolve_safely(key: str) -> None:
    try:
        resolve(key)
    except Exception:  # noqa: BLE001
        log.exception("Could not resolve notice %s", key)


# ------------------------------------------------------------------ weather

DEFAULT_CITY = "Kyiv"


def weather_city() -> str:
    with _lock:
        city = _clean_label(_load().get("weather_city"))
    return city or DEFAULT_CITY


def set_weather_city(city: str) -> str:
    city = _clean_label(city)
    if not city:
        raise ValueError("empty city")
    with _lock:
        data = _load()
        data["weather_city"] = city
        _save(data)
    return city


_weather_cache: dict[str, tuple[float, dict]] = {}
WEATHER_TTL_S = 20 * 60


async def weather_now(city: str | None = None, fresh: bool = False) -> dict:
    """The weather tile's data, cached: the tile refreshes itself, the free
    services behind it (Nominatim, Open-Meteo) ask for restraint."""
    from tools.weather import get_weather

    city = _clean_label(city) or weather_city()
    hit = _weather_cache.get(city.lower())
    if hit and not fresh and _now() - hit[0] < WEATHER_TTL_S:
        return hit[1]
    data = await get_weather(city)
    if "error" not in data:
        data = {**data, "fetched_at": _now()}
        _weather_cache[city.lower()] = (_now(), data)
    return data


def remember_weather(city: str, data: dict) -> None:
    """The bot just looked up the weather: the tile shows the same answer."""
    if not isinstance(data, dict) or "error" in data or not city:
        return
    data = {**data, "fetched_at": _now()}
    _weather_cache[_clean_label(city).lower()] = (_now(), data)
    try:
        events.publish({"type": "weather", "weather": data})
    except Exception:  # noqa: BLE001
        log.exception("Could not publish the weather event")


# ------------------------------------------------------------------ API

class TimerAction(BaseModel):
    action: str = Field(pattern="^(set|cancel|pause|resume|add)$")
    seconds: float = 0
    label: str = Field(default="", max_length=200)
    id: str = Field(default="", max_length=40)
    kind: str = Field(default="timer", pattern="^(timer|reminder)$")
    at: str = Field(default="", max_length=20)
    repeat: str = Field(default="none", pattern="^(none|daily|weekdays)$")
    timezone: str = Field(default="", max_length=100)


@router.get("/timers")
async def api_timers() -> dict:
    return {"timers": list_timers(), "now": _now()}


@router.post("/timers")
async def api_timer_action(req: TimerAction) -> dict:
    try:
        if (req.repeat != "none" or req.timezone) and not req.at:
            raise ValueError("repeat and timezone require a clock time")
        if req.at and (req.action != "set" or req.kind != "reminder"):
            raise ValueError("clock time requires setting a reminder")
        if req.action == "set":
            if req.at:
                if req.seconds:
                    raise ValueError("choose a clock time or a duration")
                add_alarm(req.at, req.label, req.repeat, req.timezone)
            else:
                add_timer(req.seconds, req.label, req.kind)
        else:
            update_timer(req.action, req.id or req.label, req.seconds)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"timers": list_timers(), "now": _now()}


class CityRequest(BaseModel):
    city: str = Field(min_length=1, max_length=80)


@router.get("/weather")
async def api_weather(fresh: bool = False) -> dict:
    data = await weather_now(fresh=fresh)
    return {"city": weather_city(), "weather": data}


@router.post("/weather/city")
async def api_weather_city(req: CityRequest) -> dict:
    try:
        city = set_weather_city(req.city)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return {"city": city, "weather": await weather_now(city)}


class DismissRequest(BaseModel):
    id: str = Field(min_length=1, max_length=40)


@router.get("/notices")
async def api_notices() -> dict:
    return {"notices": list_notices(), "now": _now()}


@router.post("/notices/dismiss")
async def api_dismiss_notice(req: DismissRequest) -> dict:
    dismiss_notice(req.id)
    return {"notices": list_notices(), "now": _now()}
