"""Read-only mobile catalog of the skills available to the existing agent.

The parent registers ``mobile_skills.router(_require_user)`` before its static
catch-all. Authentication stays with that mobile-aware owner dependency; this
module creates no store, scheduler, or invocation endpoint.

GET /api/mobile/skills returns ``{skills: [...]}``. Every row carries the web
catalog's metadata plus user/agent visibility and a ``selectable`` flag.
``invocation`` is the exact composable ``$skill-name`` reference, or an empty
string when selection is unavailable. Selecting a row inserts that reference
into the current draft, preserving its text and attachments, without sending.
The existing message submission path performs invocation when the user sends.

The repository's web SkillsSection only lists/toggles installed skills. The
reference insertion behavior comes from the installed OpenClaw Control UI:
docs/tools/skills.md, "Reference a skill in a prompt". Generic HTTP agent turns
expand these references too; there is no separate mobile skill executor.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
import re
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field

from openclaw_extensions import _missing
from openclaw_store import OpenClawStoreError, _agent_args, _json_from_output, _run


class InstalledSkill(BaseModel):
    """Public metadata only; never skill contents, local paths or credentials."""

    name: str
    description: str = ""
    emoji: str = ""
    source: str = ""
    bundled: bool = False
    enabled: bool = True
    eligible: bool = False
    missing: list[str] = Field(default_factory=list)
    homepage: str = ""
    user_invocable: bool = True
    blocked_by_allowlist: bool = False
    blocked_by_agent_filter: bool = False
    model_visible: bool = False
    command_visible: bool = False
    selectable: bool = False
    invocation: str = ""


class SkillCatalog(BaseModel):
    skills: list[InstalledSkill] = Field(default_factory=list)


def _installed_skills() -> list[dict[str, Any]]:
    # Use the web reader's exact command and agent selection. Its projection
    # drops userInvocable and agent-filter flags, which the picker must honor.
    payload = _json_from_output(_run(["skills", "list", "--json", *_agent_args()], timeout=30))
    items = payload.get("skills") if isinstance(payload, dict) else payload
    if not isinstance(items, list):
        raise OpenClawStoreError("invalid_skill_catalog", code="invalid_json")
    return items


def _reference(name: str) -> str:
    # OpenClaw also resolves the declared skill name, so no truncated native
    # slash-command name or collision suffix needs to be guessed here.
    value = name.lower()
    if re.fullmatch(r"[a-z0-9_-]+(?::[a-z0-9_-]+)*", value) and re.search(r"[a-z]", value):
        return "$" + value
    return ""


def _catalog(items: list[dict[str, Any]]) -> SkillCatalog:
    rows = []
    for item in items:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str) or not item["name"]:
            continue
        enabled = item.get("disabled", False) is False
        eligible = item.get("eligible", False) is True
        user_invocable = item.get("userInvocable", True) is True
        blocked_by_allowlist = item.get("blockedByAllowlist", False) is not False
        blocked_by_agent_filter = item.get("blockedByAgentFilter", False) is not False
        available = enabled and eligible and not blocked_by_allowlist and not blocked_by_agent_filter
        model_visible = item.get("modelVisible", available) is True
        command_visible = item.get("commandVisible", available and user_invocable) is True
        invocation = _reference(item["name"])
        selectable = available and user_invocable and model_visible and command_visible and bool(invocation)
        rows.append(InstalledSkill(
            name=item["name"],
            description=str(item.get("description") or ""),
            emoji=str(item.get("emoji") or ""),
            source=str(item.get("source") or ""),
            bundled=bool(item.get("bundled")),
            enabled=enabled,
            eligible=eligible,
            missing=_missing(item),
            homepage=str(item.get("homepage") or ""),
            user_invocable=user_invocable,
            blocked_by_allowlist=blocked_by_allowlist,
            blocked_by_agent_filter=blocked_by_agent_filter,
            model_visible=model_visible,
            command_visible=command_visible,
            selectable=selectable,
            invocation=invocation if selectable else "",
        ))

    # Lookup accepts declared names and sanitized native command names. A raw
    # name is not enough to disambiguate collisions between these aliases.
    # Native suffix allocation belongs to OpenClaw, so leave ambiguous rows
    # readable rather than guess a reference that can invoke another skill.
    aliases: dict[str, set[int]] = {}
    for index, row in enumerate(rows):
        declared = re.sub(r"[\s_]+", "-", row.name.lower())
        native = re.sub(r"[^a-z0-9_]+", "_", row.name.lower())
        native = re.sub(r"_+", "_", native).strip("_")[:32] or "skill"
        for alias in (declared, native.replace("_", "-")):
            aliases.setdefault(alias, set()).add(index)
    for row in rows:
        if len(aliases[re.sub(r"[\s_]+", "-", row.name.lower())]) > 1:
            row.selectable = False
            row.invocation = ""
    rows.sort(key=lambda row: (not row.enabled, row.name.casefold()))
    return SkillCatalog(skills=rows)


def router(
    require_user: Callable[[Request], Awaitable[str]],
    *,
    list_skills: Callable[[], list[dict[str, Any]]] | None = None,
) -> APIRouter:
    """Build an authenticated catalog; the synchronous reader is injectable."""
    reader = list_skills or _installed_skills

    async def private(response: Response):
        response.headers["Cache-Control"] = "no-store"

    routes = APIRouter(prefix="/api/mobile", dependencies=[Depends(private)])

    @routes.get("/skills", response_model=SkillCatalog)
    async def skills(user_id: str = Depends(require_user)) -> SkillCatalog:
        # Empty user_id is the authenticated local owner, not a new identity.
        try:
            items = await asyncio.to_thread(reader)
            if not isinstance(items, list):
                raise OpenClawStoreError("invalid_skill_catalog", code="invalid_json")
            return _catalog(items)
        except OpenClawStoreError as exc:
            # CLI diagnostics can include configuration/credentials. Clients
            # localize the code; neither the raw error nor its output is sent.
            raise HTTPException(
                504 if exc.code == "timeout" else 502,
                {"code": "skills_catalog_unavailable"},
                headers={"Cache-Control": "no-store"},
            ) from None

    return routes
