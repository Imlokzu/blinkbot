"""
Timed lyrics for the screen's music player: lines with start times, so the
current line can follow the song.

Sources, first hit wins:
  1. LRCLIB (lrclib.net) — a free, keyless, community database of synced
     (LRC) lyrics. The bot calls it, never the device.
  2. YouTube Music's own lyrics (ytm-helper) — plain text, no timing. The
     player scrolls these by progress instead of highlighting a line, because
     a guessed highlight would be wrong most of the time.

Results are cached per video id for a day: lyrics do not change, and a song
on repeat must not hit LRCLIB on every play.
"""

from __future__ import annotations

import logging
import re
import time
from typing import Any

import httpx

import ytmusic

log = logging.getLogger("virtual_bot.lyrics")

LRCLIB = "https://lrclib.net/api"
# LRCLIB asks clients to identify themselves.
_HEADERS = {"User-Agent": "claude-bot/1.0 (https://github.com/Imlokzu)"}
_TIMEOUT_S = 8
_TTL_S = 86400
# "Nothing found" may be a network blip, so it is retried much sooner.
_MISS_TTL_S = 600
_CACHE: dict[str, tuple[float, dict[str, Any]]] = {}   # id -> (expires at, result)
# A synced text for a different cut of the song (radio edit, live) drifts out
# of time, so the length must match. LRCLIB's own matcher uses ±2 s; search
# results get a little more slack because YouTube durations are rounded.
_DURATION_SLACK_S = 4

_TAG_RE = re.compile(r"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]")
# Noise YouTube adds to titles; LRCLIB knows the bare song name.
_TITLE_NOISE_RE = re.compile(
    r"\s*[(\[](?:official|офіційн|lyric|текст|audio|video|кліп|clip|visuali[sz]er|live|hd|4k|mv)[^)\]]*[)\]]",
    re.IGNORECASE,
)
_FEAT_RE = re.compile(r"\s*[(\[]?\s*(?:feat\.?|ft\.?|prod\.?)\s.*$", re.IGNORECASE)


def parse_lrc(text: str) -> list[dict[str, Any]]:
    """LRC -> [{"t": seconds, "text": line}] sorted by time.

    One line may carry several timestamps (a repeated chorus); metadata tags
    such as [ar:...] have no digits and are skipped. An empty text is kept as
    an instrumental gap, so the highlight does not sit on the last sung line
    through a guitar solo.
    """
    lines: list[dict[str, Any]] = []
    for raw in (text or "").splitlines():
        stamps = list(_TAG_RE.finditer(raw))
        if not stamps:
            continue
        words = raw[stamps[-1].end():].strip()
        for m in stamps:
            frac = m.group(3) or "0"
            seconds = int(m.group(1)) * 60 + int(m.group(2)) + int(frac) / (10 ** len(frac))
            lines.append({"t": round(seconds, 2), "text": words})
    lines.sort(key=lambda x: x["t"])
    return lines


def clean_title(title: str) -> str:
    """'Song (Official Video) feat. X' -> 'Song'."""
    out = _TITLE_NOISE_RE.sub("", title or "")
    out = _FEAT_RE.sub("", out)
    return out.strip(" -–—") or (title or "").strip()


def split_artist_title(title: str, artist: str) -> tuple[str, str]:
    """A plain YouTube upload is often 'Artist - Song' with the channel as
    the uploader; YouTube Music gives the two apart. Take the title's own
    artist when it has one."""
    for dash in (" - ", " – ", " — "):
        if dash in title:
            left, right = title.split(dash, 1)
            if left.strip() and right.strip():
                return left.strip(), right.strip()
    return artist, title


def _first_artist(artist: str) -> str:
    return re.split(r",|&| x | feat\.? | ft\.? ", artist or "", maxsplit=1)[0].strip()


def pick_synced(results: list[dict[str, Any]], duration: float) -> dict[str, Any] | None:
    """The best LRCLIB search hit: synced, and the same length as our cut."""
    synced = [r for r in results if isinstance(r, dict) and r.get("syncedLyrics")]
    if not synced:
        return None
    if duration:
        close = [r for r in synced if abs(float(r.get("duration") or 0) - duration) <= _DURATION_SLACK_S]
        if not close:
            return None
        return min(close, key=lambda r: abs(float(r.get("duration") or 0) - duration))
    return synced[0]


async def _lrclib(client: httpx.AsyncClient, artist: str, title: str, duration: float) -> dict[str, Any] | None:
    search: dict[str, Any] = {"track_name": title}
    if artist:
        search["artist_name"] = artist
    try:
        # /get is an exact match and needs the artist; without one only the
        # search (checked against the duration) is worth asking.
        if artist:
            params: dict[str, Any] = {"artist_name": artist, "track_name": title}
            if duration:
                params["duration"] = int(round(duration))
            resp = await client.get(LRCLIB + "/get", params=params)
            if resp.status_code == 200:
                hit = resp.json()
                if isinstance(hit, dict) and hit.get("syncedLyrics"):
                    return hit
        # Search forgives spelling and album names.
        resp = await client.get(LRCLIB + "/search", params=search)
        if resp.status_code == 200:
            return pick_synced(resp.json() or [], duration)
    except (httpx.HTTPError, ValueError) as exc:
        log.info("LRCLIB lookup failed for %r / %r: %s", artist, title, exc)
    return None


async def timed_lyrics(video_id: str, title: str, artist: str = "", duration: float = 0) -> dict[str, Any]:
    """{"kind": "synced", "lines": [...]} | {"kind": "plain", "text": ...} | {"kind": "none"}."""
    cached = _CACHE.get(video_id)
    if cached and time.monotonic() < cached[0]:
        return cached[1]

    artist_guess, song = split_artist_title(title, artist)
    song = clean_title(song)
    artist_guess = _first_artist(artist_guess)
    result: dict[str, Any] = {"kind": "none"}

    if song:
        async with httpx.AsyncClient(timeout=_TIMEOUT_S, headers=_HEADERS) as client:
            hit = await _lrclib(client, artist_guess, song, duration)
        if hit:
            lines = parse_lrc(hit["syncedLyrics"])
            if lines:
                result = {"kind": "synced", "lines": lines, "source": "LRCLIB"}

    if result["kind"] == "none" and ytmusic.available():
        try:
            data = await ytmusic.run("lyrics", video_id)
        except ytmusic.YtmError as exc:
            log.info("YT Music lyrics failed for %s: %s", video_id, exc)
            data = {}
        text = str(data.get("lyrics") or "").replace("\r", "").strip()
        if text:
            result = {"kind": "plain", "text": text, "source": str(data.get("source") or "YouTube Music")}

    ttl = _MISS_TTL_S if result["kind"] == "none" else _TTL_S
    _CACHE[video_id] = (time.monotonic() + ttl, result)
    if len(_CACHE) > 300:
        for key in sorted(_CACHE, key=lambda k: _CACHE[k][0])[:100]:
            _CACHE.pop(key, None)
    return result
