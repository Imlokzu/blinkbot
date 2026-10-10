"""Projects are named directories in the owner's separate code root.

The slug stays stable when a project is renamed, keeping linked chats intact.
Display names live in .project.json. Directory descriptors keep metadata
operations anchored to the opened project even if its pathname is replaced.
"""

from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import errno
import json
import os
import re
import stat
import time
from pathlib import Path
from uuid import uuid4

import workspace
from project_locales import t

_META_NAME = ".project.json"
_SLUG_BAD_CHARS_RE = re.compile(r"[^a-z0-9_-]+")
_SLUG_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,50}$")
MAX_NAME = 80
# Bound timestamp scanning so a large project does not stall the catalog.
_MTIME_SCAN_LIMIT = 300

# Transliteration prevents Cyrillic names from all collapsing to "project".
_TRANSLIT = {
    "а": "a", "б": "b", "в": "v", "г": "h", "ґ": "g", "д": "d", "е": "e", "є": "ie",
    "ж": "zh", "з": "z", "и": "y", "і": "i", "ї": "i", "й": "i", "к": "k", "л": "l",
    "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "kh", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "shch", "ь": "",
    "ю": "iu", "я": "ia", "ъ": "", "ы": "y", "э": "e", "ё": "e",
}


def _safe_slug(slug: str) -> str:
    """Slugs become directory entries, so validate them as strictly as chat IDs."""
    s = (slug or "").strip()
    if not _SLUG_ID_RE.match(s):
        raise ValueError(t("project.invalid_id"))
    return s


def _projects_root() -> Path:
    """Coding projects have their own root, separate from memory and user files."""
    return workspace.code_root()


def _slugify(name: str) -> str:
    lowered = (name or "").strip().lower()
    translit = "".join(_TRANSLIT.get(ch, ch) for ch in lowered)
    base = _SLUG_BAD_CHARS_RE.sub("-", translit).strip("-")[:40]
    return base or "project"


class ProjectPathError(ValueError):
    """An entry cannot be safely used as a project or metadata path."""

    def __init__(self):
        super().__init__(t("project.invalid_path"))


def _open_directory(name: str | Path, parent: int | None = None) -> int:
    try:
        return os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
    except OSError as exc:
        if exc.errno in {errno.ELOOP, errno.ENOTDIR}:
            raise ProjectPathError() from exc
        raise


@contextmanager
def _root_directory():
    descriptor = _open_directory(_projects_root())
    try:
        yield descriptor
    finally:
        os.close(descriptor)


@dataclass(frozen=True)
class _Project:
    root: int
    directory: int
    slug: str

    def require_current(self) -> None:
        """Detect replaced entries; all actual IO still uses the pinned descriptor."""
        _require_directory_entry(self.root, self.slug, self.directory)


def _require_directory_entry(parent: int, name: str, descriptor: int) -> None:
    try:
        entry = os.stat(name, dir_fd=parent, follow_symlinks=False)
    except FileNotFoundError as exc:
        raise ProjectPathError() from exc
    if not stat.S_ISDIR(entry.st_mode) or not os.path.samestat(entry, os.fstat(descriptor)):
        raise ProjectPathError()


@contextmanager
def _project_folder(root: int, slug: str):
    slug = _safe_slug(slug)
    try:
        descriptor = _open_directory(slug, root)
    except FileNotFoundError as exc:
        raise FileNotFoundError(t("project.not_found")) from exc
    try:
        project = _Project(root, descriptor, slug)
        project.require_current()
        _check_metadata_path(descriptor)
        yield project
    finally:
        os.close(descriptor)


def _check_metadata_path(directory: int) -> None:
    try:
        entry = os.stat(_META_NAME, dir_fd=directory, follow_symlinks=False)
    except FileNotFoundError:
        return
    if not stat.S_ISREG(entry.st_mode):
        raise ProjectPathError()


def _read_meta(project: _Project) -> dict:
    try:
        descriptor = os.open(_META_NAME, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                             dir_fd=project.directory)
    except FileNotFoundError:
        return {}
    except OSError as exc:
        if exc.errno == errno.ELOOP:
            raise ProjectPathError() from exc
        raise
    try:
        with os.fdopen(descriptor, "r", encoding="utf-8", closefd=False) as source:
            if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                raise ProjectPathError()
            try:
                return json.load(source)
            except (OSError, ValueError):
                return {}
    finally:
        os.close(descriptor)


def _write_meta(project: _Project, meta: dict) -> None:
    project.require_current()
    _check_metadata_path(project.directory)
    data = json.dumps(meta, ensure_ascii=False)
    temporary = f".project-{uuid4().hex}.tmp"
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                         0o600, dir_fd=project.directory)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", closefd=False) as staged:
            staged.write(data)
            staged.flush()
            os.fsync(staged.fileno())
        project.require_current()
        _check_metadata_path(project.directory)
        entry = os.stat(temporary, dir_fd=project.directory, follow_symlinks=False)
        if not os.path.samestat(entry, os.fstat(descriptor)):
            raise ProjectPathError()
        os.replace(temporary, _META_NAME, src_dir_fd=project.directory, dst_dir_fd=project.directory)
        project.require_current()
        _check_metadata_path(project.directory)
    finally:
        # A replaced staging entry belongs to the external writer; leave it intact.
        try:
            try:
                entry = os.stat(temporary, dir_fd=project.directory, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                if os.path.samestat(entry, os.fstat(descriptor)):
                    os.unlink(temporary, dir_fd=project.directory)
        finally:
            os.close(descriptor)


def _updated_at(directory: int) -> int:
    best = os.fstat(directory).st_mtime
    count = 0

    def scan(parent: int) -> None:
        nonlocal best, count
        with os.scandir(parent) as entries:
            for entry in entries:
                if count >= _MTIME_SCAN_LIMIT:
                    return
                count += 1
                if entry.name == _META_NAME or entry.name.startswith(".project-"):
                    continue
                try:
                    info = entry.stat(follow_symlinks=False)
                    if stat.S_ISLNK(info.st_mode):
                        continue
                    best = max(best, info.st_mtime)
                    if stat.S_ISDIR(info.st_mode):
                        child = _open_directory(entry.name, parent)
                        try:
                            scan(child)
                        finally:
                            os.close(child)
                except (OSError, ProjectPathError):
                    continue

    scan(directory)
    return int(best)


def _as_dict(project: _Project, meta: dict | None = None) -> dict:
    if meta is None:
        meta = _read_meta(project)
    try:
        created = int(meta.get("created") or int(os.fstat(project.directory).st_ctime))
    except OSError:
        created = int(time.time())
    return {
        "id": project.slug,
        "name": meta.get("name") or project.slug,
        "created": created,
        "updated": _updated_at(project.directory),
    }


def list_projects() -> list[dict]:
    """Return projects by newest file change, excluding unsafe and hidden entries."""
    out = []
    with _root_directory() as root:
        for slug in os.listdir(root):
            if not _SLUG_ID_RE.fullmatch(slug):
                continue
            try:
                with _project_folder(root, slug) as project:
                    out.append(_as_dict(project))
            except (FileNotFoundError, ProjectPathError):
                continue
    out.sort(key=lambda p: -p["updated"])
    return out


def get_project(slug: str) -> dict:
    with _root_directory() as root, _project_folder(root, slug) as project:
        return _as_dict(project)


def create_project(name: str) -> dict:
    clean = " ".join((name or "").split())[:MAX_NAME] or t("project.untitled")
    slug = _slugify(clean)
    counter = 2
    with _root_directory() as root:
        candidate = slug
        while True:
            try:
                os.mkdir(candidate, dir_fd=root)
                break
            except FileExistsError:
                candidate = f"{slug}-{counter}"
                counter += 1
        with _project_folder(root, candidate) as project:
            now = int(time.time())
            _write_meta(project, {"name": clean, "created": now})
            return {"id": candidate, "name": clean, "created": now, "updated": now}


def rename_project(slug: str, new_name: str) -> dict:
    clean = " ".join((new_name or "").split())[:MAX_NAME]
    if not clean:
        raise ValueError(t("project.name_required"))
    with _root_directory() as root, _project_folder(root, slug) as project:
        meta = _read_meta(project)
        meta["name"] = clean
        meta.setdefault("created", int(os.fstat(project.directory).st_ctime))
        _write_meta(project, meta)
        # Return this write's receipt, rather than rereading a newer caller's name.
        return _as_dict(project, meta)


def delete_project(slug: str) -> dict:
    """Move the directory entry into this root's trash without following links."""
    with _root_directory() as root, _project_folder(root, slug) as project:
        try:
            os.mkdir(workspace.TRASH_DIRNAME, dir_fd=root)
        except FileExistsError:
            pass
        trash = _open_directory(workspace.TRASH_DIRNAME, root)
        try:
            _require_directory_entry(root, workspace.TRASH_DIRNAME, trash)
            base = f"{int(time.time())}-{project.slug}"
            target = base
            counter = 1
            while True:
                _require_directory_entry(root, workspace.TRASH_DIRNAME, trash)
                try:
                    os.mkdir(target, dir_fd=trash)
                    break
                except FileExistsError:
                    target = f"{counter}-{base}"
                    counter += 1
            promoted = False
            try:
                project.require_current()
                _require_directory_entry(root, workspace.TRASH_DIRNAME, trash)
                os.rename(project.slug, target, src_dir_fd=root, dst_dir_fd=trash)
                promoted = True
                _require_directory_entry(root, workspace.TRASH_DIRNAME, trash)
                entry = os.stat(target, dir_fd=trash, follow_symlinks=False)
                if not os.path.samestat(entry, os.fstat(project.directory)):
                    raise ProjectPathError()
            except BaseException:
                # A committed move may contain user files; never remove its data.
                if not promoted:
                    os.rmdir(target, dir_fd=trash)
                raise
            return {"ok": True, "trashed": f"{workspace.TRASH_DIRNAME}/{target}"}
        finally:
            os.close(trash)


def exists(slug: str) -> bool:
    if not slug:
        return False
    try:
        with _root_directory() as root, _project_folder(root, slug):
            return True
    except (ValueError, FileNotFoundError):
        return False
