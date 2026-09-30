"""Bounded operator views of the gateway, without transcripts or credentials.

OpenClaw remains the source of truth. The dashboard creates paused, isolated
agent jobs; enabling an existing job uses its observed configuration revision.
No arbitrary RPC method, shell command or gateway config enters this API.
"""

from __future__ import annotations

import asyncio
import hashlib
import math
import time
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from pydantic import BaseModel, ConfigDict, Field, StrictBool

import openclaw_usage as usage

_cache: dict[str, tuple[float, dict]] = {}
_pending: dict[str, asyncio.Task] = {}
_cache_generation = 0


def _rows(value: object) -> list[dict]:
    return [row for row in value if isinstance(row, dict)] if isinstance(value, list) else []


def _text(value: object, limit: int = 200) -> str:
    return "".join(c for c in value if c.isprintable())[:limit] if isinstance(value, str) else ""


def _number(value: object) -> int | float | None:
    if type(value) not in (int, float) or value < 0 or value > 2**53 - 1:
        return None
    return value if math.isfinite(value) else None


def _enum(value: object, choices: set[str]) -> str | None:
    return value if isinstance(value, str) and value in choices else None


def _invalidate() -> None:
    global _cache_generation
    _cache_generation += 1
    _cache.clear()
    # Readers already awaiting these tasks can finish, but later readers must
    # start after the mutation and old tasks must never publish a stale cache.
    _pending.clear()


def _opaque(value: object) -> str:
    return hashlib.sha256(str(value).encode()).hexdigest()[:16]


async def _rpc(method: str, params: dict | None = None) -> dict:
    try:
        data = await usage._call(method, params, timeout=20)
    except (OSError, asyncio.TimeoutError):
        raise HTTPException(status_code=502, detail="gateway_unavailable") from None
    if not isinstance(data, dict):
        raise HTTPException(status_code=502, detail="gateway_unavailable")
    return data


async def _scheduler_status() -> dict | None:
    try:
        return await _rpc("cron.status")
    except HTTPException:
        return None


async def _read(key: str, load, refresh: bool = False) -> dict:
    # Concurrent widgets and refreshes share one CLI process per view. Failed
    # reads never populate the cache, and cancellation cannot kill another reader.
    hit = _cache.get(key)
    if not refresh and hit and time.monotonic() - hit[0] < 15:
        return hit[1]
    task = _pending.get(key)
    if task is None:
        generation = _cache_generation
        async def fetch():
            try:
                value = await load()
                if generation == _cache_generation:
                    _cache[key] = (time.monotonic(), value)
                    while len(_cache) > 32:
                        _cache.pop(next(iter(_cache)))
                return value
            finally:
                if _pending.get(key) is asyncio.current_task():
                    _pending.pop(key, None)
        task = asyncio.create_task(fetch())
        _pending[key] = task
    return await asyncio.shield(task)


async def agents() -> dict:
    data = await _rpc("agents.list")
    if not isinstance(data.get("agents"), list):
        raise HTTPException(502, "gateway_unavailable")
    items = []
    for row in _rows(data["agents"]):
        model = row.get("model")
        runtime = row.get("agentRuntime")
        identity = row.get("identity") if isinstance(row.get("identity"), dict) else {}
        items.append({
            "id": _text(row.get("id")),
            "name": _text(identity.get("name") or row.get("name") or row.get("id")),
            "default": row.get("id") == data.get("defaultId"),
            "model": _text(model.get("primary") if isinstance(model, dict) else model),
            "fallbacks": [_text(m) for m in model.get("fallbacks", []) if isinstance(m, str)]
                         if isinstance(model, dict) and isinstance(model.get("fallbacks"), list) else [],
            "runtime": _text(runtime.get("id") if isinstance(runtime, dict) else runtime),
            "thinking": _text(row.get("thinkingDefault")),
            "workspace_configured": bool(row.get("workspace")),
        })
    return {"agents": items}


async def sessions(limit: int, offset: int, agent: str = "") -> dict:
    params = {"limit": limit, "offset": offset, "sortBy": "activity",
              "configuredAgentsOnly": True, "includeDerivedTitles": False,
              "includeLastMessage": False, "includeGlobal": False, "includeUnknown": False}
    if agent:
        params["agentId"] = agent
    data = await _rpc("sessions.list", params)
    if not isinstance(data.get("sessions"), list):
        raise HTTPException(502, "gateway_unavailable")
    items = []
    for row in _rows(data["sessions"]):
        if not isinstance(row.get("key"), str) or not row["key"]:
            continue
        source = _enum(row.get("classification"), {
            "cron", "subagent", "custom", "main", "channel", "direct", "group",
            "thread", "heartbeat", "acp",
        })
        if source is None:
            source = "channel" if _enum(row.get("kind"), {"group", "channel"}) else "custom"
        activity_at = _number(row.get("lastActivityAt"))
        items.append({
            "id": _opaque(row["key"]), "agent": _text(row.get("agentId")),
            "source": source, "model": _text(row.get("model")),
            "provider": _text(row.get("modelProvider")),
            "updated_at": activity_at if activity_at is not None else _number(row.get("updatedAt")),
            "active": row.get("hasActiveRun") is True,
            "status": _enum(row.get("status"), {"queued", "running", "done", "failed", "killed", "timeout"}),
            "tokens": _number(row.get("totalTokens")),
            "tokens_fresh": row.get("totalTokensFresh") is True,
            "context_window": _number(row.get("contextTokens")),
        })
    return {"sessions": items, "total": _number(data.get("totalCount")),
            "has_more": data.get("hasMore") is True,
            "next_offset": _number(data.get("nextOffset")), "offset": offset}


def _schedule(value: object) -> dict:
    value = value if isinstance(value, dict) else {}
    kind = value.get("kind")
    if kind == "every":
        return {"kind": kind, "every_ms": _number(value.get("everyMs"))}
    if kind == "at":
        return {"kind": kind, "at": _text(value.get("at"))}
    if kind == "cron":
        return {"kind": kind, "expression": _text(value.get("expr")), "timezone": _text(value.get("tz"))}
    # Stream/on-exit schedules may contain shell commands; never project them.
    return {"kind": "other"}


async def jobs(offset: int = 0) -> dict:
    listing, status = await asyncio.gather(
        _rpc("cron.list", {"includeDisabled": True, "limit": 50, "offset": offset,
                           "includeDeliveryPreviews": False}),
        _scheduler_status(),
    )
    if not isinstance(listing.get("jobs"), list):
        raise HTTPException(502, "gateway_unavailable")
    items = []
    for row in _rows(listing["jobs"]):
        state = row.get("state") if isinstance(row.get("state"), dict) else {}
        items.append({
            "id": _text(row.get("id")), "name": _text(row.get("displayName") or row.get("name")),
            "agent": _text(row.get("effectiveAgentId") or row.get("agentId")),
            "enabled": row.get("enabled") is True, "schedule": _schedule(row.get("schedule")),
            "next_run": _number(row.get("nextRunAtMs", state.get("nextRunAtMs"))),
            "last_run": _number(row.get("lastRunAtMs", state.get("lastRunAtMs"))),
            "last_status": _enum(row.get("lastRunStatus", state.get("lastRunStatus")), {"ok", "error", "skipped"}),
            "running": _number(state.get("runningAtMs")) is not None,
            "revision": _text(row.get("configRevision")),
        })
    scheduler_enabled = status.get("enabled") if isinstance(status, dict) else None
    return {"jobs": items, "scheduler_enabled": scheduler_enabled if type(scheduler_enabled) is bool else None,
            "total": _number(listing.get("total")), "has_more": listing.get("hasMore") is True,
            "next_offset": _number(listing.get("nextOffset")), "offset": offset}


async def runs(job_id: str) -> dict:
    data = await _rpc("cron.runs", {"id": job_id, "limit": 20, "sortDir": "desc"})
    if not isinstance(data.get("entries"), list):
        raise HTTPException(502, "gateway_unavailable")
    return {"runs": [{"at": _number(row.get("runAtMs", row.get("ts"))),
                       "status": _enum(row.get("status"), {"ok", "error", "skipped"}),
                       "duration_ms": _number(row.get("durationMs")),
                       "model": _text(row.get("model")), "provider": _text(row.get("provider"))}
                      for row in _rows(data["entries"])], "has_more": data.get("hasMore") is True}


async def channels() -> dict:
    data = await _rpc("channels.status", {"probe": False})
    accounts = data.get("channelAccounts")
    if not isinstance(accounts, dict):
        raise HTTPException(502, "gateway_unavailable")
    labels = data.get("channelLabels") if isinstance(data.get("channelLabels"), dict) else {}
    items = []
    for channel, rows in accounts.items():
        for row in _rows(rows):
            items.append({
                "id": _opaque(f"{channel}:{row.get('accountId')}"),
                "channel": _text(channel), "label": _text(labels.get(channel) or channel),
                "enabled": row.get("enabled") if isinstance(row.get("enabled"), bool) else None,
                "configured": row.get("configured") if isinstance(row.get("configured"), bool) else None,
                "running": row.get("running") if isinstance(row.get("running"), bool) else None,
                "connected": row.get("connected") if isinstance(row.get("connected"), bool) else None,
                "has_error": bool(row.get("lastError")),
                "last_inbound": _number(row.get("lastInboundAt")),
                "last_outbound": _number(row.get("lastOutboundAt")),
            })
    return {"channels": items, "updated_at": _number(data.get("ts"))}


class JobCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=120)
    message: str = Field(min_length=1, max_length=4000)
    agent: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9_-]+$")
    kind: Literal["every", "daily"]
    minutes: int = Field(default=60, ge=5, le=10080)
    hour: int = Field(default=9, ge=0, le=23)
    minute: int = Field(default=0, ge=0, le=59)
    timezone: str = Field(default="UTC", min_length=1, max_length=100)


class JobEnabled(BaseModel):
    model_config = ConfigDict(extra="forbid")
    enabled: StrictBool
    revision: str = Field(min_length=1, max_length=128)


def router(require_operator) -> APIRouter:
    routes = APIRouter(prefix="/api/openclaw/control", dependencies=[Depends(require_operator)])

    @routes.get("/{view}")
    async def view(view: Literal["agents", "sessions", "jobs", "channels"], response: Response,
                   refresh: bool = False, offset: int = Query(default=0, ge=0, le=10000),
                   agent: str = Query(default="", max_length=80, pattern=r"^[A-Za-z0-9_-]*$")):
        response.headers["Cache-Control"] = "no-store"
        loaders = {"agents": agents, "sessions": lambda: sessions(50, offset, agent),
                   "jobs": lambda: jobs(offset), "channels": channels}
        return await _read(f"{view}:{offset}:{agent}", loaders[view], refresh)

    @routes.get("/jobs/{job_id}/runs")
    async def history(response: Response, job_id: str = _job_id()):
        response.headers["Cache-Control"] = "no-store"
        return await _read(f"runs:{job_id}", lambda: runs(job_id))

    @routes.post("/jobs")
    async def create(req: JobCreate, response: Response):
        response.headers["Cache-Control"] = "no-store"
        if not req.name.strip() or not req.message.strip():
            raise HTTPException(400, "invalid_job")
        if req.agent not in {item["id"] for item in (await agents())["agents"]}:
            raise HTTPException(400, "unknown_agent")
        try:
            ZoneInfo(req.timezone)
        except (ZoneInfoNotFoundError, ValueError):
            raise HTTPException(400, "invalid_timezone") from None
        schedule = {"kind": "every", "everyMs": req.minutes * 60000} if req.kind == "every" else {
            "kind": "cron", "expr": f"{req.minute} {req.hour} * * *", "tz": req.timezone}
        _invalidate()
        try:
            result = await _rpc("cron.add", {"name": req.name.strip(), "agentId": req.agent,
                "enabled": False, "schedule": schedule, "sessionTarget": "isolated", "wakeMode": "now",
                "payload": {"kind": "agentTurn", "message": req.message.strip()},
                "delivery": {"mode": "none"}, "failureAlert": False})
        finally:
            # A lost RPC reply can still represent a committed gateway write.
            _invalidate()
        if not isinstance(result.get("id"), str) or not result["id"]:
            raise HTTPException(502, "gateway_unavailable")
        return {"ok": True, "id": _text(result["id"])}

    @routes.post("/jobs/{job_id}/enabled")
    async def enabled(req: JobEnabled, response: Response, job_id: str = _job_id()):
        response.headers["Cache-Control"] = "no-store"
        _invalidate()
        try:
            result = await _rpc("cron.update", {"id": job_id, "expectedConfigRevision": req.revision,
                                               "patch": {"enabled": req.enabled}})
        finally:
            _invalidate()
        if result.get("id") != job_id:
            raise HTTPException(502, "gateway_unavailable")
        return {"ok": True}

    return routes


def _job_id():
    from fastapi import Path
    return Path(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
