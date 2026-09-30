"""Read-only reporting over OpenClaw's usage RPCs; no transcript text leaves here."""

from __future__ import annotations

import asyncio
import datetime as dt
import hashlib
import math
import re
import time
from collections import Counter, OrderedDict, defaultdict

import openclaw_usage as usage

_cache: OrderedDict[tuple, tuple[float, dict]] = OrderedDict()
_MAX_CACHE = 12
_ID = re.compile(r"^[a-f0-9]{64}$")
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _rows(value: object) -> list[dict]:
    return [row for row in value if isinstance(row, dict)] if isinstance(value, list) else []


def _object(value: object) -> dict:
    return value if isinstance(value, dict) else {}


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def _number(value: object) -> float | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool) and value >= 0:
        try:
            if math.isfinite(value):
                return value
        except OverflowError:
            pass
    return None


def _date(value: object) -> dt.date | None:
    if isinstance(value, str) and _DATE.fullmatch(value):
        try:
            return dt.date.fromisoformat(value)
        except ValueError:
            pass
    return None


def _validate_range(days: int, limit: int, maximum: int) -> None:
    if type(days) is not int or not 1 <= days <= 90 or type(limit) is not int or not 1 <= limit <= maximum:
        raise ValueError("invalid_range")


def _session_id(row: dict) -> str:
    # Channel session keys can contain recipient addresses. An opaque id lets
    # the browser select a session without receiving that address or a file path.
    # Include the transcript instance so a reset cannot silently retarget an id.
    identity = _text(row.get("key")) + "\0" + _text(row.get("sessionId"))
    return hashlib.sha256(identity.encode()).hexdigest()


async def _report(days: int, limit: int, refresh: bool = False) -> dict | None:
    _validate_range(days, limit, 2000)
    today = dt.datetime.now(dt.timezone.utc).date()
    key = (today, days, limit)
    hit = _cache.get(key)
    if hit and not refresh:
        status = _object(hit[1].get("cacheStatus"))
        ttl = 5 if _text(status.get("status")) in {"refreshing", "partial", "stale"} else 30
        if time.monotonic() - hit[0] < ttl:
            _cache.move_to_end(key)
            return hit[1]
    start_date = str(today - dt.timedelta(days=days - 1))
    end_date = str(today)
    try:
        data = await usage._call("sessions.usage", {
            "startDate": start_date,
            "endDate": end_date,
            "agentScope": "all",
            "limit": limit,
        }, timeout=60)
    except (OSError, asyncio.TimeoutError):
        return None
    # A malformed response is unavailable, not a successful zero-traffic report.
    if (not isinstance(data, dict) or not isinstance(data.get("totals"), dict)
            or not isinstance(data.get("aggregates"), dict) or not isinstance(data.get("sessions"), list)
            or data.get("startDate") != start_date or data.get("endDate") != end_date):
        return None
    _cache[key] = (time.monotonic(), data)
    _cache.move_to_end(key)
    while len(_cache) > _MAX_CACHE:
        _cache.popitem(last=False)
    return data


def _costs(raw: object) -> dict:
    source = _object(raw)
    return {name: _number(source.get(name)) or 0 for name in usage._COST_FIELDS}


def _models(aggregate: dict) -> list[dict]:
    return [{
        "provider": _text(row.get("provider")),
        "model": _text(row.get("model")),
        "replies": _number(row.get("count")) or 0,
        **_costs(row.get("totals")),
    } for row in _rows(aggregate.get("byModel")) if _text(row.get("provider")) not in usage._INTERNAL_PROVIDERS]


def _no_cache_cost(models: list[dict], totals: dict) -> float:
    if not models:
        return totals["totalCost"]
    # Preserve reported costs that have no model breakdown; adjust only the
    # matching models' cache costs instead of dropping those unclassified costs.
    value = totals["totalCost"] + sum(usage._no_cache_cost([m]) - m["totalCost"] for m in models)
    number = _number(value)
    return number if number is not None else totals["totalCost"]


def _daily(aggregate: dict, field: str, start: dt.date, end: dt.date) -> list[dict]:
    rows = []
    for raw in _rows(aggregate.get(field)):
        date = _date(raw.get("date"))
        if date is None or not start <= date <= end:
            continue
        row = {"date": str(date), "tokens": _number(raw.get("tokens")) or 0,
               "cost": _number(raw.get("cost")) or 0}
        if field == "modelDaily":
            provider = _text(raw.get("provider"))
            if provider in usage._INTERNAL_PROVIDERS:
                continue
            row.update(provider=provider, model=_text(raw.get("model")))
        else:
            row["errors"] = _number(raw.get("errors")) or 0
        rows.append(row)
    return sorted(rows, key=lambda row: (row["date"], row.get("provider", ""), row.get("model", "")))


def _quota(raw: dict) -> dict:
    # Quota remains metadata only even if the upstream helper gains new fields.
    return {
        "provider": _text(raw.get("provider")), "name": _text(raw.get("name")), "plan": _text(raw.get("plan")),
        "windows": [{"label": _text(w.get("label")), "used_percent": _number(w.get("used_percent")),
                     "reset_at": _number(w.get("reset_at"))} for w in _rows(raw.get("windows"))],
        "billing": [{"type": _text(b.get("type")), "amount": _number(b.get("amount")),
                     "unit": _text(b.get("unit"))} for b in _rows(raw.get("billing"))],
    }


async def snapshot(days: int = 30, limit: int = 500, refresh: bool = False) -> dict:
    _validate_range(days, limit, 2000)
    data, quota, configured = await asyncio.gather(
        _report(days, limit, refresh), usage.quota(), usage._configured_providers(), return_exceptions=True,
    )
    data, quota, configured = usage._ok(data), usage._ok(quota), usage._ok(configured)
    if data is None:
        return {"available": False, "days": days, "totals": None, "providers": [], "models": [],
                "daily": [], "daily_models": [], "sessions": []}
    aggregate = _object(data.get("aggregates"))
    configured = _object(configured)
    models = _models(aggregate)
    providers = {_text(row.get("provider")): {
        "provider": _text(row.get("provider")), "replies": _number(row.get("count")) or 0,
        **_costs(row.get("totals")),
    } for row in _rows(aggregate.get("byProvider")) if _text(row.get("provider")) not in usage._INTERNAL_PROVIDERS}
    quotas = {_text(row.get("provider")): _quota(row) for row in _rows(quota) if _text(row.get("provider"))}
    names = {name for name in configured if isinstance(name, str)} | set(quotas) | {m["provider"] for m in models}
    for name in names - usage._INTERNAL_PROVIDERS:
        matching = [m for m in models if m["provider"] == name]
        providers.setdefault(name, {"provider": name, "replies": sum(m["replies"] for m in matching),
                                    **{field: sum(m[field] for m in matching) for field in usage._COST_FIELDS}})
    for name, row in providers.items():
        kind = configured.get(name)
        row["auth"] = kind if kind in ("oauth", "api_key", "token") else ""
        row["quota"] = quotas.get(name)
        row["noCacheCost"] = _no_cache_cost([m for m in models if m["provider"] == name], row)
    sessions = []
    source_sessions = data["sessions"]
    for row in _rows(source_sessions[:limit]):
        if not _text(row.get("key")):
            continue
        raw = row.get("usage") if isinstance(row.get("usage"), dict) else None
        counts = _object((raw or {}).get("messageCounts"))
        # Indexed sessions containing only user/setup events have no inference
        # to inspect. A null or incomplete usage record is still unknown.
        if (raw is not None and _number(counts.get("assistant")) == 0
                and raw.get("modelUsage") == [] and _number(raw.get("totalTokens")) == 0
                and not any(_number(raw.get(field)) for field in ("input", "output", "cacheRead", "cacheWrite"))):
            continue
        session_models = [{"provider": _text(m.get("provider")), "model": _text(m.get("model"))}
                          for m in _rows((raw or {}).get("modelUsage"))
                          if _text(m.get("provider")) not in usage._INTERNAL_PROVIDERS]
        # Store metadata describes the selected model. Usage describes the
        # models that actually answered, including fallbacks and earlier picks.
        names = {m["provider"] for m in session_models}
        sessions.append({
            "id": _session_id(row), "updated_at": _number(row.get("updatedAt")),
            "provider": next(iter(names)) if len(names) == 1 else "",
            "model": session_models[0]["model"] if len(session_models) == 1 else "",
            "replies": _number(counts.get("assistant")) or 0, "errors": _number(counts.get("errors")) or 0,
            "usage": _costs(raw) if raw is not None else None,
            # A session may use several models after a fallback or a model pick.
            "models": session_models,
        })
    status = _object(data.get("cacheStatus"))
    totals = _costs(data.get("totals"))
    totals["noCacheCost"] = _no_cache_cost(models, totals)
    start, end = _date(data.get("startDate")), _date(data.get("endDate"))
    replies = _number(_object(aggregate.get("messages")).get("assistant"))
    if replies is None:
        replies = sum(p["replies"] for p in providers.values())
    else:
        # Unmetered replies have no byModel entry. The message count still
        # includes them; remove internal delivery records where identified.
        internal = sum(_number(p.get("count")) or 0 for p in _rows(aggregate.get("byProvider"))
                       if _text(p.get("provider")) in usage._INTERNAL_PROVIDERS)
        replies = max(0, replies - internal)
    return {
        "available": True, "days": days, "start_date": data.get("startDate"), "end_date": data.get("endDate"),
        "updated_at": _number(data.get("updatedAt")), "indexing": _text(status.get("status")) in {"refreshing", "partial", "stale"},
        "totals": totals, "replies": replies,
        "errors": _number(_object(aggregate.get("messages")).get("errors")) or 0,
        "tool_calls": _number(_object(aggregate.get("tools")).get("totalCalls")) or 0,
        "providers": sorted(providers.values(), key=lambda p: (-p["replies"], p["provider"])),
        "models": models,
        "daily": _daily(aggregate, "daily", start, end),
        "daily_models": _daily(aggregate, "modelDaily", start, end),
        "sessions": sessions, "sessions_limited": len(source_sessions) >= limit,
    }


async def inferences(session_id: str, days: int, limit: int = 1000) -> dict:
    if not isinstance(session_id, str) or not _ID.fullmatch(session_id):
        raise ValueError("invalid_session")
    _validate_range(days, limit, 10000)
    unavailable = {"available": False, "inferences": [], "history_scope": "current_transcript", "message_limit": limit}
    report = await _report(days, 2000)
    if report is None:
        return unavailable
    session = next((row for row in _rows(report.get("sessions"))
                    if _text(row.get("key")) and _session_id(row) == session_id), None)
    if session is None:
        raise LookupError("unknown_session")
    # Logs resolve API pricing with OpenClaw's current model table. Raw
    # subscription messages often contain a zero cost, even for paid models.
    messages, logs = await asyncio.gather(
        usage._call("sessions.get", {"key": session["key"], "limit": limit}, timeout=45),
        usage._call("sessions.usage.logs", {"key": session["key"], "limit": 1000}, timeout=45),
        return_exceptions=True,
    )
    messages, logs = usage._ok(messages), usage._ok(logs)
    if not isinstance(messages, dict) or not isinstance(messages.get("messages"), list):
        return unavailable
    expected_instance = _text(session.get("sessionId"))
    if expected_instance:
        # sessions.get verifies its own read across resets, but it does not
        # accept an instance id. Verify the result against our cached report
        # before attributing the current transcript to that report's instance.
        try:
            described = await usage._call("sessions.describe", {"key": session["key"]}, timeout=30)
        except (OSError, asyncio.TimeoutError):
            described = None
        current_instance = _text(_object(_object(described).get("session")).get("sessionId"))
        if current_instance != expected_instance:
            return {**unavailable, "limited": True}
    source_messages = messages["messages"]
    current = _rows(source_messages[-limit:])
    counts = _object(_object(session.get("usage")).get("messageCounts"))
    reported_replies = _number(counts.get("assistant")) or 0
    # The range report discovers archival instances. sessions.get only reads
    # the current stored transcript (or its reset fallback), never that archive
    # inventory. Absence here must not erase already-reported archival traffic.
    if not current and reported_replies:
        return {**unavailable, "limited": True}
    priced = defaultdict(list)
    source_logs = _rows(_object(logs).get("logs"))[-1000:]
    for log in source_logs:
        stamp = _number(log.get("timestamp"))
        if stamp is not None and log.get("role") == "assistant":
            priced[stamp].append(log)
    stamp_counts = Counter(_number(message.get("timestamp")) for message in current if message.get("role") == "assistant")
    start = dt.date.fromisoformat(report["startDate"])
    end = dt.date.fromisoformat(report["endDate"]) + dt.timedelta(days=1)
    start_ms = dt.datetime.combine(start, dt.time(), dt.timezone.utc).timestamp() * 1000
    end_ms = dt.datetime.combine(end, dt.time(), dt.timezone.utc).timestamp() * 1000
    rows = []
    for i, message in enumerate(current):
        stamp = _number(message.get("timestamp"))
        if message.get("role") != "assistant" or _text(message.get("provider")) in usage._INTERNAL_PROVIDERS:
            continue
        if stamp is None or not start_ms <= stamp < end_ms:
            continue
        raw = _object(message.get("usage"))
        tokens = {name: _number(raw.get(name)) for name in ("input", "output", "cacheRead", "cacheWrite", "totalTokens")}
        if tokens["totalTokens"] is None:
            tokens["totalTokens"] = _number(raw.get("total"))
        if tokens["totalTokens"] is None and any(value is not None for value in tokens.values()):
            tokens["totalTokens"] = _number(sum(value or 0 for value in tokens.values()))
        # All-zero counts from a successful subscription reply mean the
        # provider supplied no usage. Show absence, never a fabricated zero.
        known_tokens = any(value for value in tokens.values())
        recorded = _object(raw.get("cost"))
        billed = recorded.get("totalOrigin") == "provider-billed"
        cost = _number(recorded.get("total"))
        if not billed and not cost:
            cost = None
        matches = priced.get(stamp, [])
        # Logs contain neither provider nor model. A timestamp collision cannot
        # safely attribute a resolved cost to one fallback/model response.
        if cost is None and stamp_counts[stamp] == 1 and len(matches) == 1:
            logged_tokens = _number(matches[0].get("tokens"))
            if logged_tokens is None or tokens["totalTokens"] is None or logged_tokens == tokens["totalTokens"]:
                cost = _number(matches[0].get("cost"))
                if not known_tokens and not cost:
                    cost = None
        stop = _text(message.get("stopReason"))
        rows.append({
            "id": str(i), "timestamp": stamp,
            "provider": _text(message.get("provider")), "model": _text(message.get("model")),
            "status": "error" if stop in {"error", "aborted"} else "tool" if stop == "toolUse" else "ok",
            **(tokens if known_tokens else {name: None for name in tokens}),
            "cost": cost,
        })
    return {"available": True, "inferences": sorted(rows, key=lambda r: r["timestamp"], reverse=True),
            "limited": len(source_messages) >= limit or reported_replies > len(rows), "message_limit": limit,
            "history_scope": "current_transcript", "pricing_limited": len(source_logs) >= 1000}
