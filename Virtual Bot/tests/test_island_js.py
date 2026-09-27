"""The screen's island (static/screen/island.js), run through node.

island.js decides which activities the pill shows and in what order; it has
no DOM on purpose, so the rules run here without a browser.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

import app_config

SCREEN = Path(app_config.STATIC_DIR) / "screen"
NODE = shutil.which("node")

pytestmark = pytest.mark.skipif(NODE is None, reason="node is not installed")


def _run(body: str):
    url = (SCREEN / "island.js").as_uri()
    script = f"import * as m from {json.dumps(url)};\n{body}"
    out = subprocess.run([NODE, "--input-type=module", "-e", script],
                         capture_output=True, text=True, timeout=20, check=True)
    return json.loads(out.stdout)


def _kinds(state: dict) -> list[str]:
    return _run(f"console.log(JSON.stringify(m.activities({json.dumps(state)}).map(a => a.key)));")


SONG = {"id": "BSTsnWoslP4", "title": "Bohemian Rhapsody", "uploader": "Queen",
        "provider": "youtube", "playing": True, "position": 12, "duration": 355}


def test_nothing_going_on_means_no_island():
    assert _kinds({}) == []
    assert _kinds({"timers": [{"id": "a", "state": "done", "left": 0}]}) == []


def test_reminders_are_not_countdowns():
    state = {"timers": [{"id": "r", "kind": "reminder", "state": "running", "left": 60}]}
    assert _kinds(state) == []


def test_ringing_first_then_moving_then_paused():
    state = {
        "ringing": True,
        "music": {**SONG, "playing": False},
        "timers": [
            {"id": "slow", "state": "running", "left": 600},
            {"id": "held", "state": "paused", "left": 30},
            {"id": "soon", "state": "running", "left": 5},
        ],
    }
    assert _kinds(state) == ["ring", "timer:soon", "timer:slow", "music:BSTsnWoslP4", "timer:held"]


def test_playing_music_comes_before_a_running_timer():
    state = {"music": SONG, "timers": [{"id": "t", "state": "running", "left": 60}]}
    assert _kinds(state) == ["music:BSTsnWoslP4", "timer:t"]


def test_an_activity_hides_while_its_own_app_is_in_front():
    assert _kinds({"music": SONG, "openApp": "yt-music"}) == []
    assert _kinds({"music": SONG, "openApp": "youtube"}) == ["music:BSTsnWoslP4"]
    video = {**SONG, "fromVideo": True}
    assert _kinds({"music": video, "openApp": "youtube"}) == []
    assert _kinds({"music": video}) == ["video:BSTsnWoslP4"]
    # Radio has no app of its own: it always shows
    radio = {**SONG, "provider": "radio"}
    assert _kinds({"music": radio, "openApp": "yt-music"}) == ["radio:BSTsnWoslP4"]


def test_the_pick_survives_updates_and_falls_back_when_gone():
    out = _run(
        "const list = [{key: 'music:x'}, {key: 'timer:a'}];"
        "console.log(JSON.stringify([m.selectKey(list, 'timer:a'), m.selectKey(list, 'timer:gone'),"
        " m.selectKey(list, ''), m.selectKey([], 'timer:a')]));"
    )
    assert out == ["timer:a", "music:x", "music:x", ""]


def test_fmt_clock():
    out = _run("console.log(JSON.stringify([m.fmtClock(0), m.fmtClock(4.2), m.fmtClock(245), m.fmtClock(3723)]));")
    assert out == ["0:00", "0:05", "4:05", "1:02:03"]
