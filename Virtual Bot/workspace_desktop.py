"""Desktop actions for validated workspace files, never arbitrary shell paths."""

from __future__ import annotations

import subprocess
import sys

from fastapi import HTTPException

import workspace


def can_reveal() -> bool:
    return sys.platform == "darwin"


def reveal(path: str) -> dict:
    """Select an existing workspace file in Finder on the server's Mac."""
    if not path.strip():
        raise ValueError("workspace.invalidPath")
    target = workspace._resolve(path, must_exist=True)
    if not can_reveal():
        raise HTTPException(status_code=501, detail="workspace.finderUnavailable")
    try:
        subprocess.run(
            ["open", "-R", str(target)],
            check=True, capture_output=True, timeout=5,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        raise HTTPException(status_code=500, detail="workspace.finderFailed") from exc
    return {"ok": True, "path": workspace.rel_path(target)}
