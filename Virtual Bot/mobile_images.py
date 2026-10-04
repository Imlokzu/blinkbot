"""Authenticated original raster bytes over public, DNS-pinned HTTP(S)."""

from __future__ import annotations

import asyncio
import ipaddress
import socket

import httpx
from fastapi import APIRouter, Depends, HTTPException, Query, Response

from image_proxy import MAX_BYTES, TIMEOUT_S

MAX_REDIRECTS = 3
TOTAL_TIMEOUT_S = 45.0
RASTER_TYPES = frozenset({"image/png", "image/jpeg", "image/gif", "image/webp", "image/avif"})
_HEADERS = {"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"}


def _error(status: int, code: str) -> HTTPException:
    return HTTPException(status, {"code": code}, headers=_HEADERS)


def _url(raw: str) -> httpx.URL:
    # Reject ambiguous input before HTTPX can normalize away control characters.
    if not raw or len(raw) > 8192 or any(ord(c) <= 32 or ord(c) == 127 for c in raw) or "\\" in raw:
        raise _error(400, "invalid_image_url")
    try:
        url = httpx.URL(raw)
        if url.scheme not in {"http", "https"} or not url.host or url.userinfo or "%" in url.host:
            raise ValueError
        if url.port is not None and not 1 <= url.port <= 65535:
            raise ValueError
    except (httpx.InvalidURL, ValueError):
        raise _error(400, "invalid_image_url") from None
    return url.copy_with(fragment=None)


def _public(address: str) -> bool:
    ip = ipaddress.ip_address(address)
    if isinstance(ip, ipaddress.IPv6Address):
        # Transition/scoped addresses must not tunnel into an internal network.
        if ip.ipv4_mapped or ip.sixtofour or ip.teredo or ip.is_site_local:
            return False
    return ip.is_global and not (ip.is_multicast or ip.is_reserved or ip.is_unspecified)


async def _destination(url: httpx.URL) -> httpx.URL:
    port = url.port or (443 if url.scheme == "https" else 80)
    infos = await asyncio.to_thread(socket.getaddrinfo, url.host, port, type=socket.SOCK_STREAM)
    addresses = sorted({info[4][0] for info in infos})
    if not addresses or not all(_public(address) for address in addresses):
        raise _error(400, "unsafe_image_destination")
    # Connect to the checked numeric address, not to the hostname a second time.
    return url.copy_with(host=addresses[0])


def _matches_type(body: bytes, media: str) -> bool:
    if media == "image/png":
        return body.startswith(b"\x89PNG\r\n\x1a\n")
    if media == "image/jpeg":
        return body.startswith(b"\xff\xd8\xff")
    if media == "image/gif":
        return body.startswith((b"GIF87a", b"GIF89a"))
    if media == "image/webp":
        return len(body) >= 12 and body[:4] == b"RIFF" and body[8:12] == b"WEBP"
    if media == "image/avif" and len(body) >= 16 and body[4:8] == b"ftyp":
        end = int.from_bytes(body[:4], "big")
        return 16 <= end <= len(body) and (
            body[8:12] in {b"avif", b"avis"}
            or any(body[i:i + 4] in {b"avif", b"avis"} for i in range(16, end, 4))
        )
    return False


async def _fetch(raw: str) -> tuple[bytes, str]:
    current = _url(raw)
    for hop in range(MAX_REDIRECTS + 1):
        destination = await _destination(current)
        # A fresh cookie jar per hop and trust_env=False prevent credentials or
        # environment proxies from changing the destination/security boundary.
        async with httpx.AsyncClient(timeout=TIMEOUT_S, follow_redirects=False, trust_env=False) as client:
            async with client.stream("GET", destination, headers={
                "Host": current.netloc.decode("ascii"),
                "Accept": ", ".join(sorted(RASTER_TYPES)),
                "Accept-Encoding": "identity",
            }, extensions={"sni_hostname": current.host}) as upstream:
                if upstream.status_code in {301, 302, 303, 307, 308}:
                    location = upstream.headers.get("location", "")
                    if not location or hop == MAX_REDIRECTS:
                        raise _error(502, "image_redirect_failed")
                    # Check raw redirects too; joining can hide control bytes.
                    if any(ord(c) <= 32 or ord(c) == 127 for c in location) or "\\" in location:
                        raise _error(400, "invalid_image_url")
                    current = _url(str(current.join(location)))
                    continue
                if upstream.status_code != 200:
                    raise _error(502, "image_fetch_failed")
                media = upstream.headers.get("content-type", "").split(";", 1)[0].strip().lower()
                if media not in RASTER_TYPES:
                    raise _error(415, "unsupported_image_type")
                # Raster formats are already compressed. Avoid decompression
                # bombs and return the original file bytes unchanged.
                if upstream.headers.get("content-encoding", "identity").lower() != "identity":
                    raise _error(415, "unsupported_image_encoding")
                length = upstream.headers.get("content-length")
                if length is not None:
                    if not length.isascii() or not length.isdigit() or len(length) > 20:
                        raise _error(502, "image_fetch_failed")
                    if int(length) > MAX_BYTES:
                        raise _error(413, "image_too_large")
                body = bytearray()
                async for chunk in upstream.aiter_raw():
                    if len(body) + len(chunk) > MAX_BYTES:
                        raise _error(413, "image_too_large")
                    body.extend(chunk)
                content = bytes(body)
                if not _matches_type(content, media):
                    raise _error(415, "unsupported_image_type")
                return content, media
    raise _error(502, "image_redirect_failed")


async def fetch(url: str) -> tuple[bytes, str]:
    """Limit total time including DNS and all hops; never expose target URLs."""
    try:
        async with asyncio.timeout(TOTAL_TIMEOUT_S):
            return await _fetch(url)
    except (httpx.HTTPError, httpx.InvalidURL, OSError, TimeoutError, ValueError):
        raise _error(502, "image_fetch_failed") from None


def router(require_user) -> APIRouter:
    routes = APIRouter(prefix="/api/mobile/images")

    @routes.get("/fetch")
    async def fetch_image(
        url: str = Query(min_length=1, max_length=8192),
        user_id: str = Depends(require_user),
    ) -> Response:
        content, media = await fetch(url)
        return Response(content, media_type=media, headers=_HEADERS)

    return routes
