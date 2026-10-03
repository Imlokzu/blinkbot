"""Revision-aware mobile workspace routes over the parent's existing endpoints.

Integration::

    app.include_router(mobile_content.router(
        _require_user, read_file=api_workspace_file,
        save_file=api_workspace_save, write_guard=mobile_workspace_guard,
    ))

``require_user(request)`` must authenticate mobile credentials as well as the
existing owner. The async callbacks retain their existing endpoint signatures:
``read_file(request, path=..., session_id=...)`` and ``save_file(req, request)``.
The save request has the existing workspace fields plus a required revision.

An optional ``write_guard(user_id, session_id, path)`` returns an async context
manager reserving the parent's writer gate for the entire check/save operation.
It should raise the existing busy/ownership HTTP error on refusal. Other writers
(desktop routes and bot tools) must use that same reservation to exclude writes
during a mobile save. The local lock serializes mobile access to each resolved
file, including aliases. This adapter does not start another mobile runtime.

GET returns the existing file payload plus an opaque ``revision``. POST requires
that token, returns the existing save payload plus its new revision, and rejects
stale tokens with HTTP 409. ``revision="missing"`` creates only an absent file;
missing reads retain the existing 404 response. No revision index is persisted.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager
import hashlib
from pathlib import Path
from typing import AsyncContextManager
from weakref import WeakValueDictionary

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field

import brain_context
import workspace


class WorkspaceWriteRequest(BaseModel):
    """The parent's write request shape with mandatory optimistic concurrency."""

    model_config = ConfigDict(extra="forbid")
    path: str = Field(min_length=1, max_length=1024)
    session_id: str = Field(default="", max_length=64)
    content: str = Field(default="", max_length=workspace.MAX_WRITE_BYTES)
    append: bool = False
    revision: str = Field(min_length=1, max_length=128)


ReadFile = Callable[..., Awaitable[dict]]
SaveFile = Callable[[WorkspaceWriteRequest, Request], Awaitable[dict]]
WriteGuard = Callable[[str, str, str], AsyncContextManager[None]]


def _revision(target: Path) -> str:
    try:
        stat = target.stat()
    except FileNotFoundError:
        return "missing"
    # ctime detects a same-size rewrite even when a writer restores mtime.
    # Metadata also works for binary/oversized files without reading them again.
    stamp = (str(target), stat.st_dev, stat.st_ino, stat.st_size,
             stat.st_mtime_ns, stat.st_ctime_ns)
    return "v1:" + hashlib.sha256(repr(stamp).encode("utf-8")).hexdigest()


@asynccontextmanager
async def _unguarded(_user_id: str, _session_id: str, _path: str):
    yield


def router(
    require_user: Callable[[Request], Awaitable[str]],
    *,
    read_file: ReadFile,
    save_file: SaveFile,
    write_guard: WriteGuard | None = None,
) -> APIRouter:
    """Build GET/POST /api/mobile/workspace/file; parent owns all integration."""
    locks: WeakValueDictionary[Path, asyncio.Lock] = WeakValueDictionary()
    guard = write_guard or _unguarded

    async def private(response: Response):
        response.headers["Cache-Control"] = "no-store"

    routes = APIRouter(prefix="/api/mobile/workspace", dependencies=[Depends(private)])

    def resolve(path: str) -> Path:
        try:
            return workspace._resolve(path)
        except ValueError:
            raise HTTPException(400, {"code": "invalid_workspace_path"}) from None
        except OSError:
            raise HTTPException(500, {"code": "workspace_io_error"}) from None

    def revision(target: Path) -> str:
        try:
            return _revision(target)
        except OSError:
            raise HTTPException(500, {"code": "workspace_io_error"}) from None

    def lock(target: Path) -> asyncio.Lock:
        # Keep a strong reference in each request while it waits or holds the
        # lock; unused entries disappear instead of growing with every file.
        selected = locks.get(target)
        if selected is None:
            selected = asyncio.Lock()
            locks[target] = selected
        return selected

    def conflict(current: str):
        raise HTTPException(409, {"code": "workspace_revision_conflict", "revision": current})

    @routes.get("/file")
    async def get_file(
        request: Request,
        path: str = Query(min_length=1, max_length=1024),
        session_id: str = Query(default="", max_length=64),
        user_id: str = Depends(require_user),
    ) -> dict:
        with brain_context.set_clerk_user(user_id), workspace.set_session(session_id):
            target = resolve(path)
            async with lock(target):
                before = revision(target)
                result = await read_file(request, path=path, session_id=session_id)
                current = revision(target)
                # A token for newer bytes must never accompany older content.
                if before != current:
                    conflict(current)
                return {**result, "revision": current}

    @routes.post("/file")
    async def post_file(
        req: WorkspaceWriteRequest,
        request: Request,
        user_id: str = Depends(require_user),
    ) -> dict:
        with brain_context.set_clerk_user(user_id), workspace.set_session(req.session_id):
            target = resolve(req.path)
            async with lock(target):
                async with guard(user_id, req.session_id, req.path):
                    current = revision(target)
                    if req.revision != current:
                        conflict(current)
                    result = await save_file(req, request)
                    return {**result, "revision": revision(target)}

    return routes
