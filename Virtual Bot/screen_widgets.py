"""
State behind the screen's live tiles: timers and the weather tile.

Both are things the bot and the person share. "Put a ten minute timer on"
is said to the bot but ticks on the screen, and "how long is left?" is
asked of the bot about what the screen shows. So the state lives here, on
the server, and both sides read it: the brain through tools
(tools/timer_tools.py), the screen through /api/screen/* and SSE.

The server never fires a timer itself. It stores when each one ends; the
screen counts down and rings. A bot with no screen attached has nobody to
ring for anyway, and a scheduler here would only add a second clock that
could disagree with the one on display.

Persisted to runtime/screen-widgets.json (runtime/ is not committed), so a
restart in the middle of a pomodoro does not lose it.
"""

from __future__ import annotations

import json
import logging
import time
import uuid
from pathlib import Path
from threading import Lock
from typing import Any

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


def _view(timer: dict, now: float) -> dict:
    """A timer as clients see it: what is left right now, not only when it ends."""
    if timer.get("paused_left") is not None:
        left = float(timer["paused_left"])
        state = "paused"
    else:
        left = max(0.0, float(timer["ends_at"]) - now)
        state = "done" if left <= 0 else "running"
    return {
        "id": timer["id"],
        "kind": timer.get("kind", "timer"),
        "label": timer.get("label", ""),
        "seconds": int(timer["seconds"]),
        "ends_at": timer.get("ends_at"),
        "left": round(left, 1),
        "state": state,
    }


def _alive(timers: list[dict], now: float) -> list[dict]:
    keep = []
    for timer in timers:
        grace = REMINDER_KEEP_S if timer.get("kind") == "reminder" else DONE_KEEP_S
        if timer.get("paused_left") is None and float(timer["ends_at"]) + grace < now:
            continue
        keep.append(timer)
    return keep


def list_timers() -> list[dict]:
    now = _now()
    with _lock:
        data = _load()
        timers = _alive(data.get("timers") or [], now)
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
        "kind": "reminder" if reminder else "timer",
        "label": text,
        "seconds": int(round(seconds)),
        "ends_at": now + seconds,
        "paused_left": None,
    }
    with _lock:
        data = _load()
        timers = _alive(data.get("timers") or [], now)
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
    """
    now = _now()
    with _lock:
        data = _load()
        timers = _alive(data.get("timers") or [], now)
        targets = list(timers) if (action == "cancel" and key == "all") else _find(timers, key)
        if not targets:
            return []
        for timer in targets:
            if action == "cancel":
                timers.remove(timer)
            elif action == "pause" and timer.get("paused_left") is None:
                timer["paused_left"] = max(0.0, float(timer["ends_at"]) - now)
            elif action == "resume" and timer.get("paused_left") is not None:
                timer["ends_at"] = now + float(timer["paused_left"])
                timer["paused_left"] = None
            elif action == "add":
                extra = max(-MAX_TIMER_S, min(MAX_TIMER_S, float(seconds)))
                if timer.get("paused_left") is not None:
                    timer["paused_left"] = max(1.0, float(timer["paused_left"]) + extra)
                else:
                    base = max(now, float(timer["ends_at"]))   # extending a rung timer restarts it
                    timer["ends_at"] = max(now + 1, base + extra)
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


@router.get("/timers")
async def api_timers() -> dict:
    return {"timers": list_timers(), "now": _now()}


@router.post("/timers")
async def api_timer_action(req: TimerAction) -> dict:
    try:
        if req.action == "set":
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
