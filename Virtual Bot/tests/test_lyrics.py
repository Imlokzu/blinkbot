"""Timed lyrics: LRC parsing, picking the right LRCLIB hit, and the fallback
to plain YouTube Music text. All offline: the network is replaced."""

from __future__ import annotations

import asyncio

import httpx
import pytest
from fastapi.testclient import TestClient

import lyrics
import ytmusic


def run(coro):
    loop = asyncio.new_event_loop()
    try:
        asyncio.set_event_loop(loop)
        return loop.run_until_complete(coro)
    finally:
        loop.close()
        asyncio.set_event_loop(asyncio.new_event_loop())


@pytest.fixture(autouse=True)
def clean_cache():
    lyrics._CACHE.clear()
    yield
    lyrics._CACHE.clear()


def test_parse_lrc_handles_repeats_gaps_and_metadata():
    text = "[ar:Queen]\n[00:07.13]Second\n[00:00.15][01:02.5]Chorus\n[00:30.00]\nnot a line"
    assert lyrics.parse_lrc(text) == [
        {"t": 0.15, "text": "Chorus"},
        {"t": 7.13, "text": "Second"},
        {"t": 30.0, "text": ""},
        {"t": 62.5, "text": "Chorus"},
    ]


@pytest.mark.parametrize("raw,expected", [
    ("Обійми (Official Video)", "Обійми"),
    ("Song [Lyric Video] feat. Someone", "Song"),
    ("Хочеш", "Хочеш"),
])
def test_clean_title(raw, expected):
    assert lyrics.clean_title(raw) == expected


def test_split_artist_title_prefers_the_title_artist():
    assert lyrics.split_artist_title("Океан Ельзи - Обійми", "Some Channel") == ("Океан Ельзи", "Обійми")
    assert lyrics.split_artist_title("Обійми", "Океан Ельзи") == ("Океан Ельзи", "Обійми")


def test_pick_synced_rejects_a_different_cut():
    results = [
        {"duration": 420, "syncedLyrics": "[00:01.00]live"},
        {"duration": 213, "syncedLyrics": None},
        {"duration": 215, "syncedLyrics": "[00:01.00]studio"},
    ]
    assert lyrics.pick_synced(results, 213)["syncedLyrics"].endswith("studio")
    assert lyrics.pick_synced(results[:2], 213) is None


def _mock_transport(handler):
    real = httpx.AsyncClient

    def factory(*args, **kwargs):
        kwargs["transport"] = httpx.MockTransport(handler)
        return real(*args, **kwargs)
    return factory


def test_timed_lyrics_uses_lrclib_search_when_get_misses(monkeypatch):
    seen = []

    def handler(request):
        seen.append((request.url.path, dict(request.url.params)))
        if request.url.path.endswith("/get"):
            return httpx.Response(404, json={"code": 404})
        return httpx.Response(200, json=[{"duration": 176, "syncedLyrics": "[00:13.02]З тобою"}])

    monkeypatch.setattr(lyrics.httpx, "AsyncClient", _mock_transport(handler))
    monkeypatch.setattr(ytmusic, "available", lambda: False)
    result = run(lyrics.timed_lyrics("abcdefghijk", "Хочеш (Official Video)", "Олена Тополя, X", 176))
    assert result == {"kind": "synced", "lines": [{"t": 13.02, "text": "З тобою"}], "source": "LRCLIB"}
    # The noise is gone and only the first artist is asked for
    assert seen[0][1]["track_name"] == "Хочеш"
    assert seen[0][1]["artist_name"] == "Олена Тополя"


def test_timed_lyrics_falls_back_to_plain_text(monkeypatch):
    def handler(request):
        raise httpx.ConnectError("offline", request=request)

    async def fake_run(command, arg="", limit=20):
        assert command == "lyrics"
        return {"lyrics": "line one\r\nline two", "source": "Source: Musixmatch"}

    monkeypatch.setattr(lyrics.httpx, "AsyncClient", _mock_transport(handler))
    monkeypatch.setattr(ytmusic, "available", lambda: True)
    monkeypatch.setattr(ytmusic, "run", fake_run)
    result = run(lyrics.timed_lyrics("abcdefghijk", "Song", "Artist", 100))
    assert result == {"kind": "plain", "text": "line one\nline two", "source": "Source: Musixmatch"}


def test_synced_endpoint_validates_the_id(monkeypatch):
    from main import app

    async def fake(video_id, title, artist="", duration=0):
        return {"kind": "none", "id": video_id, "title": title}

    monkeypatch.setattr(lyrics, "timed_lyrics", fake)
    with TestClient(app) as client:
        ok = client.get("/api/ytm/synced", params={"id": "dQw4w9WgXcQ", "title": "T"})
        assert ok.status_code == 200 and ok.json()["id"] == "dQw4w9WgXcQ"
        assert client.get("/api/ytm/synced", params={"id": "../../etc/pw"}).status_code == 422


def test_no_artist_skips_the_exact_match(monkeypatch):
    seen = []

    def handler(request):
        seen.append((request.url.path, dict(request.url.params)))
        return httpx.Response(200, json=[{"duration": 355, "syncedLyrics": "[00:00.15]Is this the real life?"}])

    monkeypatch.setattr(lyrics.httpx, "AsyncClient", _mock_transport(handler))
    monkeypatch.setattr(ytmusic, "available", lambda: False)
    result = run(lyrics.timed_lyrics("BSTsnWoslP4", "Bohemian Rhapsody", "", 355))
    assert result["kind"] == "synced"
    assert [path for path, _ in seen] == ["/api/search"]
    assert "artist_name" not in seen[0][1]
