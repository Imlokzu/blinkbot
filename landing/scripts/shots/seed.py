#!/usr/bin/env python3
"""
Fill a throwaway Virtual Bot instance with the demo conversation, notes and
files that the landing's dashboard screenshots show.

Why a separate instance at all: the landing is public, and the owner's real
chats, notes and files must never end up in a screenshot. So the pictures
come from a clean checkout (a git worktree, where the git-ignored user_data/
starts empty) running on its own port, filled with the scripted content in
demo-data.json.

Chats are written straight to disk because no API creates a finished reply
with tool steps. Notes go through the real /api/memory/save, so they land
wherever the running server keeps them.

Usage (see README.md in this folder):
    python3 seed.py --root "/tmp/cb-shots/Virtual Bot" --url http://127.0.0.1:8199 --lang en
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
DATA = HERE / "demo-data.json"
DIAGRAM = REPO / "docs" / "diagrams" / "architecture.excalidraw"
DEMO_PREFIX = "demo"


def refuse_real_checkout(root: Path) -> None:
    """The one mistake this script must not make is seeding the owner's bot."""
    real = (REPO / "Virtual Bot").resolve()
    if root.resolve() == real:
        sys.exit(f"Refusing to seed {root}: that is the real bot. Use a worktree copy.")
    if not (root / "main.py").is_file():
        sys.exit(f"{root} does not look like a Virtual Bot folder (no main.py).")


def post(url: str, body: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={"content-type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read() or b"{}")


def build_message(raw: dict, ts: int, index: int) -> dict:
    message = {"id": f"demo{index:04d}", "role": raw["role"], "ts": ts}
    if raw["role"] == "user" or "parts" not in raw:
        message["content"] = raw.get("content", "")
        return message
    # The plain answer is what voice and search read; the bubbles are parts.
    answer = [part["text"] for part in raw["parts"] if part["type"] == "text" and not part.get("note")]
    message["content"] = "\n\n".join(answer)
    message["parts"] = raw["parts"]
    started = ts * 1000 - 6000
    steps = []
    for step in raw.get("steps", []):
        ended = started + int(step.get("seconds", 1) * 1000)
        steps.append({
            "id": step["id"],
            "label": step["label"],
            "detail": step["detail"],
            "status": "done",
            "startedAt": started,
            "endedAt": ended,
            "source": "openclaw",
            "input": step.get("input"),
            "result": step.get("result"),
        })
        started = ended + 200
    message["steps"] = steps
    return message


def seed_chats(root: Path, sessions: list[dict]) -> None:
    chats = root / "user_data" / "chats"
    chats.mkdir(parents=True, exist_ok=True)
    for old in chats.glob(f"{DEMO_PREFIX}*.json"):
        old.unlink()
    now = int(time.time())
    for session in sessions:
        updated = now - session["agoMinutes"] * 60
        count = len(session["messages"])
        # Replies are spaced a little apart so the thread reads as a real exchange.
        messages = [
            build_message(raw, updated - (count - i) * 40, i)
            for i, raw in enumerate(session["messages"])
        ]
        data = {
            "id": session["id"],
            "title": session["title"],
            "titled": True,
            "created": messages[0]["ts"],
            "updated": updated,
            "messages": messages,
            "participants": [],
            "events": [],
        }
        if session.get("channel"):
            data["channel"] = session["channel"]
        (chats / f"{session['id']}.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8"
        )


def brain_dir(root: Path, url: str) -> Path:
    """Ask the server to save a probe note, then find where it went."""
    probe = "landing-seed-probe.md"
    post(f"{url}/api/memory/save", {"path": probe, "content": "# probe\n"})
    hits = list((root / "user_data").rglob(probe))
    if len(hits) != 1:
        sys.exit(f"Could not locate the brain folder (found {len(hits)} probes).")
    hits[0].unlink()
    return hits[0].parent


def seed_notes(root: Path, url: str, notes: dict[str, str]) -> None:
    brain = brain_dir(root, url)
    for old in brain.rglob("*.md"):
        # The navigation index is the server's own file; everything else is ours.
        if old.name != "_navigation.md":
            old.unlink()
    for path, content in notes.items():
        if path.startswith("logs/"):
            # The journal is the bot's own service folder: the API refuses to
            # write there, because only the bot keeps it. Put it on disk directly.
            target = brain / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
            continue
        post(f"{url}/api/memory/save", {"path": path, "content": content})


def seed_workspace(root: Path) -> None:
    notes = root / "user_data" / "owner" / "workspace" / "notes"
    notes.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(DIAGRAM, notes / "architecture.excalidraw")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", required=True, type=Path, help="the throwaway Virtual Bot folder")
    parser.add_argument("--url", required=True, help="where that instance listens")
    parser.add_argument("--lang", required=True, choices=["uk", "en"])
    args = parser.parse_args()

    refuse_real_checkout(args.root)
    demo = json.loads(DATA.read_text(encoding="utf-8"))[args.lang]
    seed_chats(args.root, demo["sessions"])
    seed_notes(args.root, args.url.rstrip("/"), demo["notes"])
    seed_workspace(args.root)
    print(f"Seeded {len(demo['sessions'])} chats and {len(demo['notes'])} notes ({args.lang}).")


if __name__ == "__main__":
    main()
