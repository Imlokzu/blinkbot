"""Authenticated, bounded original workspace bytes for native preview/export."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
import mimetypes
import os
from pathlib import Path
import stat
from urllib.parse import quote

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response

import brain_context
import workspace

MAX_DOWNLOAD_BYTES = 20 * 1024 * 1024


def _read_original(target: Path, root: Path) -> bytes:
    # Hold a trusted root descriptor and reject symlinks on every component.
    # An ancestor swapped after _resolve must not redirect the read outside it.
    parts = target.relative_to(root).parts
    if not parts:
        raise HTTPException(400, {"code": "invalid_workspace_file"})
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    directory = os.open(root, directory_flags)
    try:
        for part in parts[:-1]:
            child = os.open(part, directory_flags, dir_fd=directory)
            os.close(directory)
            directory = child
        descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=directory)
    finally:
        os.close(directory)
    # Inspect the opened descriptor rather than a prior stat; changing files
    # cannot bypass the limit or turn into a directory/device.
    with os.fdopen(descriptor, "rb") as source:
        info = os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode):
            raise HTTPException(400, {"code": "invalid_workspace_file"})
        if info.st_size > MAX_DOWNLOAD_BYTES:
            raise HTTPException(413, {"code": "workspace_file_too_large"})
        content = source.read(MAX_DOWNLOAD_BYTES + 1)
        if len(content) > MAX_DOWNLOAD_BYTES:
            raise HTTPException(413, {"code": "workspace_file_too_large"})
        return content


def router(require_user, *, resolve_file: Callable[..., Path]) -> APIRouter:
    """Reuse the parent's identity and workspace resolver; no second file store."""
    routes = APIRouter(prefix="/api/mobile/workspace")

    @routes.get("/download")
    async def download(
        request: Request,
        path: str = Query(min_length=1, max_length=1024),
        session_id: str = Query(default="", max_length=64),
        user_id: str = Depends(require_user),
    ) -> Response:
        # Paths are workspace-relative, never URLs or absolute server paths.
        if path != path.strip() or path.startswith(("/", "\\")) or any(
            char in path for char in ("\\", ":", "\x00", "\r", "\n")
        ):
            raise HTTPException(400, {"code": "invalid_workspace_path"})
        try:
            with brain_context.set_clerk_user(user_id), workspace.set_session(session_id):
                target = resolve_file(path, must_exist=True)
                content = await asyncio.to_thread(_read_original, target, workspace.root())
        except FileNotFoundError:
            raise HTTPException(404, {"code": "not_found"}) from None
        except (ValueError, IsADirectoryError):
            raise HTTPException(400, {"code": "invalid_workspace_path"}) from None
        except PermissionError:
            raise HTTPException(403, {"code": "forbidden"}) from None
        except OSError:
            raise HTTPException(400, {"code": "invalid_workspace_file"}) from None
        return Response(content, media_type=mimetypes.guess_type(target.name)[0] or "application/octet-stream", headers={
            "Cache-Control": "no-store",
            "Content-Disposition": "attachment; filename*=UTF-8''" + quote(target.name, safe=""),
            "X-Content-Type-Options": "nosniff",
            "Content-Security-Policy": "sandbox; default-src 'none'",
        })

    return routes
