"""Private static app previews over the existing owner/session workspace."""

from __future__ import annotations

import asyncio
from collections.abc import Callable
import json
from pathlib import Path
import re

from fastapi import APIRouter, Depends, HTTPException, Query, Response
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.routing import APIRoute
from starlette.exceptions import HTTPException as StarletteHTTPException

import brain_context
import mobile_workspace
import workspace


HEADERS = {
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Access-Control-Allow-Origin": "*",
    "Content-Security-Policy": (
        "default-src 'none'; script-src 'self' 'unsafe-inline'; "
        "style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
        "font-src 'self' data: blob:; connect-src 'self'; object-src 'none'; "
        "worker-src 'none'; frame-src 'none'; form-action 'none'; base-uri 'none'"
    ),
}
MIME_TYPES = {
    ".html": "text/html", ".htm": "text/html",
    ".js": "text/javascript", ".mjs": "text/javascript", ".css": "text/css",
    ".json": "application/json", ".webmanifest": "application/manifest+json",
    ".png": "image/png", ".jpg": "image/jpeg", ".jpeg": "image/jpeg",
    ".gif": "image/gif", ".webp": "image/webp", ".avif": "image/avif",
    ".svg": "image/svg+xml", ".ico": "image/x-icon", ".bmp": "image/bmp",
    ".woff": "font/woff", ".woff2": "font/woff2", ".ttf": "font/ttf",
    ".otf": "font/otf", ".eot": "application/vnd.ms-fontobject",
    ".wasm": "application/wasm",
}
_PRIVATE_DIRS = {"node_modules", "src", "source", "sources", "secrets", "private", "config"}
_PRIVATE_FILES = {
    "package.json", "package-lock.json", "npm-shrinkwrap.json", "bun.lock",
    "tsconfig.json", "jsconfig.json", "config.json", "settings.json",
    "credentials.json", "secrets.json", "service-account.json", "service_account.json",
}
_SESSION_PATTERN = r"^(?:[A-Za-z0-9_-]{1,64})?$"


class _PrivateRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()

        async def private(request):
            try:
                response = await handler(request)
            except StarletteHTTPException as exc:
                exc.headers = {**(exc.headers or {}), **HEADERS}
                raise
            except RequestValidationError as exc:
                response = await request_validation_exception_handler(request, exc)
            response.headers.update(HEADERS)
            return response

        return private


def _invalid(code: str = "invalid_workspace_path"):
    raise HTTPException(400, {"code": code})


def _parts(value: str) -> tuple[str, ...]:
    # ASGI has already URL-decoded the query once. Never decode it again or
    # accept encoded separators that a native URL mapper could decode later.
    if (value != value.strip() or value.startswith(("/", "\\"))
            or any(ord(char) < 32 or ord(char) == 127 or char in "\\:?#" for char in value)
            or re.search(r"%[0-9a-fA-F]{2}", value)):
        _invalid()
    parts = tuple(part for part in value.split("/") if part not in {"", "."})
    if any(part.startswith(".") for part in parts):
        _invalid()
    return parts


def _public(parts: tuple[str, ...], *, workspace_relative: bool = False) -> None:
    # Only workspace-rooted paths have a trusted session namespace. Its slug
    # may be named "config"; project/asset directories with that name may not.
    if (workspace_relative and len(parts) >= 2 and parts[0] == "sessions"
            and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", parts[1])):
        parts = (parts[0], *parts[2:])
    lowered = tuple(part.lower() for part in parts)
    if (any(part in _PRIVATE_DIRS for part in lowered)
            or any(part in _PRIVATE_FILES or ".config." in part
                   or part.startswith(("tsconfig.", "jsconfig.", "credentials.", "secrets."))
                   for part in lowered)):
        raise HTTPException(403, {"code": "web_resource_forbidden"})


class _Workspace:
    def __init__(self, resolve_file: Callable[..., Path]):
        self.base = workspace.root()
        self.resolve_file = resolve_file

    def relative(self, target: Path) -> str:
        return "" if target == self.base else target.relative_to(self.base).as_posix()

    def resolve(self, relative: str) -> Path:
        parts = _parts(relative)
        if parts and parts[0] == "session":
            parts = ("sessions", workspace.active_session_slug(), *parts[1:])
        lexical = self.base.joinpath(*parts)
        target = self.resolve_file(relative, must_exist=False)
        # The shared resolver follows internal symlinks. Keep its namespace,
        # but reject aliases rather than letting resolution hide a symlink.
        if target != lexical:
            _invalid()
        current = self.base
        for part in parts:
            current /= part
            if current.is_symlink():
                _invalid()
        return lexical

    def read(self, target: Path) -> bytes:
        checked = self.resolve(self.relative(target))
        return mobile_workspace._read_original(checked, self.base)

    def project(self, directory: Path) -> Path | None:
        while directory.is_relative_to(self.base):
            try:
                raw = self.read(directory / "package.json")
            except FileNotFoundError:
                pass
            else:
                try:
                    manifest = json.loads(raw)
                except (ValueError, UnicodeError):
                    _invalid("invalid_web_project")
                if not isinstance(manifest, dict):
                    _invalid("invalid_web_project")
                for group in ("dependencies", "devDependencies", "peerDependencies"):
                    deps = manifest.get(group, {})
                    if isinstance(deps, dict) and {"vite", "react", "react-dom", "react-scripts"} & deps.keys():
                        return directory
                scripts = manifest.get("scripts", {})
                if isinstance(scripts, dict) and any(
                    isinstance(command, str) and re.search(r"\b(?:vite|react-scripts)\b", command)
                    for command in scripts.values()
                ):
                    return directory
            if directory == self.base:
                break
            directory = directory.parent
        return None

    def describe(self, relative: str) -> dict:
        _public(_parts(relative), workspace_relative=True)
        target = self.resolve(relative)
        if not target.exists():
            raise FileNotFoundError
        directory = target if target.is_dir() else target.parent
        if target != directory and target.suffix.lower() not in {".html", ".htm"}:
            if not target.is_file():
                _invalid("invalid_workspace_file")
            # Extensionless links may be regular text files rather than app
            # directories. Leave their content and size handling to the editor.
            return {"ready": False, "root": self.relative(directory), "entry": target.name,
                    "project_path": self.relative(directory), "kind": "file", "buildable": False}
        project = self.project(directory)
        # The parent's build queue requires a nonempty workspace-relative
        # directory. A root-level package can preview, but cannot target Build.
        buildable = project is not None and project != self.base
        candidates = [project / folder / "index.html" for folder in ("dist", "build")] if project else (
            [directory / folder / "index.html" for folder in ("dist", "build")]
            + [directory / "index.html", directory / "index.htm"] if target == directory else [target]
        )
        for index in candidates:
            try:
                self.read(index)
            except FileNotFoundError:
                continue
            root = index.parent
            entry = target if target != directory and target.is_relative_to(root) else index
            if entry != index:
                self.read(entry)
            return {"ready": True, "root": self.relative(root),
                    "entry": entry.relative_to(root).as_posix(),
                    "project_path": self.relative(project or directory), "kind": "web",
                    "buildable": buildable}
        if project is not None:
            return {"ready": False, "reason": "build_required",
                    "project_path": self.relative(project), "root": self.relative(project),
                    "kind": "web", "buildable": buildable}
        raise FileNotFoundError

    def resource(self, root: str, path: str, entry: str) -> Response:
        root_parts, path_parts, entry_parts = _parts(root), _parts(path), _parts(entry)
        _public(root_parts, workspace_relative=True)
        for parts in (path_parts, entry_parts):
            _public(parts)
        if not entry_parts or Path(entry_parts[-1]).suffix.lower() not in {".html", ".htm"}:
            _invalid("invalid_web_entry")
        asset_root = self.resolve(root)
        # A page placed at the workspace root gets only its own HTML and its
        # explicit assets subtree, never other workspace folders or notes.
        if asset_root == self.base and (len(entry_parts) != 1 or (
            path_parts and path_parts != entry_parts and path_parts[0] != "assets"
        )):
            raise HTTPException(403, {"code": "web_resource_forbidden"})
        selected = asset_root.joinpath(*entry_parts)
        descriptor = self.describe(self.relative(selected))
        if not descriptor["ready"]:
            raise HTTPException(409, {"code": "build_required"})
        if descriptor["root"] != self.relative(asset_root) or descriptor["entry"] != "/".join(entry_parts):
            _invalid("invalid_web_root")
        target = asset_root.joinpath(*path_parts) if path_parts else selected
        target = self.resolve(self.relative(target))
        if not target.is_relative_to(asset_root):
            _invalid()
        if target.is_dir():
            for name in ("index.html", "index.htm"):
                try:
                    content = self.read(target / name)
                except FileNotFoundError:
                    continue
                return Response(content, media_type="text/html")
        extension = target.suffix.lower()
        if not extension:
            # Route URLs are never served as arbitrary extensionless files.
            return Response(self.read(selected), media_type="text/html")
        if extension not in MIME_TYPES:
            raise HTTPException(403, {"code": "web_resource_forbidden"})
        return Response(self.read(target), media_type=MIME_TYPES[extension])


def router(require_user, *, resolve_file: Callable[..., Path]) -> APIRouter:
    """Register only authenticated reads; builds use the parent's chat queue."""
    routes = APIRouter(prefix="/api/mobile/workspace", route_class=_PrivateRoute)

    async def run(user_id: str, session_id: str, operation):
        try:
            with brain_context.set_clerk_user(user_id), workspace.set_session(session_id):
                return await asyncio.to_thread(lambda: operation(_Workspace(resolve_file)))
        except FileNotFoundError:
            raise HTTPException(404, {"code": "not_found"}) from None
        except PermissionError:
            raise HTTPException(403, {"code": "forbidden"}) from None
        except (ValueError, RuntimeError, OSError):
            raise HTTPException(400, {"code": "invalid_workspace_path"}) from None

    @routes.get("/web-preview")
    async def preview(
        path: str = Query(default="", max_length=1024),
        session_id: str = Query(default="", max_length=64, pattern=_SESSION_PATTERN),
        user_id: str = Depends(require_user),
    ) -> dict:
        return await run(user_id, session_id, lambda files: files.describe(path))

    @routes.get("/web-resource")
    async def resource(
        root: str = Query(max_length=1024),
        path: str = Query(default="", max_length=1024),
        entry: str = Query(min_length=1, max_length=1024),
        session_id: str = Query(default="", max_length=64, pattern=_SESSION_PATTERN),
        user_id: str = Depends(require_user),
    ) -> Response:
        return await run(user_id, session_id, lambda files: files.resource(root, path, entry))

    return routes
