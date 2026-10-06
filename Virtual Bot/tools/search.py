"""Web search through Exa's hosted MCP, DuckDuckGo, and Bing (parallel).

Also exposes `fetch_top_pages`: search once, then fetch the top N pages in
parallel and extract a title plus a body-text preview for each. Fetches go
through the same public-IP allowlist as web_browser, so SSRF to internal
services stays blocked.
"""

from __future__ import annotations

import asyncio
import base64
import ipaddress
import json
import logging
import os
import re
import socket
from html import unescape
from time import monotonic
from urllib.parse import parse_qs, urljoin, urlsplit, urlparse

import httpx

from tools.locales import t

log = logging.getLogger("virtual_bot.tools.search")

_EXA_URL = "https://mcp.exa.ai/mcp"
_SEARCH_TIMEOUT = 8.0
_PROVIDER_TIMEOUT = 4.0
_DDG_URLS = (
    "https://html.duckduckgo.com/html/",
    "https://lite.duckduckgo.com/lite/",
)
_DDG_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "text/html",
    "Accept-Language": "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
}
_BING_URL = "https://www.bing.com/search"
_BING_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
}
_COOLDOWNS: dict[str, float] = {}
_LIMITED: set[str] = set()
# DuckDuckGo drops any parallel burst with a challenge page and a 60s cool-off;
# a non-blocking semaphore keeps us from DDoSing ourselves. Bing and Exa
# handle concurrent load fine, so only DDG is gated this way.
_DDG_LOCK = asyncio.Lock()


class _SearchFailure(Exception):
    """Provider failed without exposing its response or credentials."""

    def __init__(self, reason: str, limited: bool = False):
        self.reason = reason
        self.limited = limited


def _error(key: str) -> dict:
    return {"error": t(key), "error_code": key}


def _clean_text(text: str) -> str:
    """Decode HTML entities and collapse whitespace."""
    return re.sub(r"\s+", " ", unescape(text)).strip()


def _result_url(url: str, base: str = "") -> str:
    url = urljoin(base, unescape(url)) if base else unescape(url)
    try:
        parsed = urlsplit(url)
        if parsed.hostname == "duckduckgo.com" or (parsed.hostname or "").endswith(".duckduckgo.com"):
            url = parse_qs(parsed.query).get("uddg", [url])[0]
            parsed = urlsplit(url)
        if parsed.hostname and (parsed.hostname == "bing.com" or parsed.hostname.endswith(".bing.com")):
            url = _unwrap_bing(url)
            if not url:
                return ""
            parsed = urlsplit(url)
        return url if parsed.scheme in {"http", "https"} and parsed.hostname else ""
    except ValueError:
        return ""


def _unwrap_bing(url: str) -> str:
    """Bing redirects wrap the target in bing.com/ck/a with u=a1<base64url>."""
    try:
        parsed = urlsplit(url)
        qs = parse_qs(parsed.query)
        u = qs.get("u", [""])[0]
        if not u.startswith("a1"):
            return url
        payload = u[2:]
        payload += "=" * (-len(payload) % 4)
        return base64.urlsafe_b64decode(payload).decode("utf-8", errors="replace")
    except (ValueError, TypeError):
        return ""


def _snippet(text: str) -> str:
    text = _clean_text(text)
    return text[:200] + "…" if len(text) > 200 else text


def _is_rate_limit(text: str) -> bool:
    return bool(re.search(r"rate[ -]?limit|too many requests|\b429\b", text, re.IGNORECASE))


def _parse_exa_response(response: httpx.Response, count: int) -> list[dict]:
    """Read JSON or SSE MCP responses without relying on page separators."""
    body = response.text.strip()
    if body.startswith("{"):
        messages = [json.loads(body)]
    else:
        messages = []
        for event in re.split(r"\r?\n\r?\n", body):
            data = "\n".join(line[5:].lstrip() for line in event.splitlines()
                             if line.startswith("data:"))
            if data and data != "[DONE]":
                messages.append(json.loads(data))

    for message in messages:
        if not isinstance(message, dict) or message.get("id") != 1:
            continue
        if message.get("error"):
            raise _SearchFailure("rpc_error", limited=_is_rate_limit(str(message["error"])))
        result = message.get("result")
        if not isinstance(result, dict) or not isinstance(result.get("content"), list):
            raise _SearchFailure("invalid_response")
        if result.get("isError"):
            text = " ".join(str(item.get("text", "")) for item in result["content"]
                            if isinstance(item, dict)).lower()
            raise _SearchFailure("tool_error", limited=_is_rate_limit(text))
        results: list[dict] = []
        for item in result["content"]:
            if not isinstance(item, dict) or item.get("type") != "text":
                continue
            text = str(item.get("text") or "")
            matches = list(re.finditer(r"^Title:[ \t]*(.+)\r?\nURL:[ \t]*(\S+)",
                                       text, re.MULTILINE))
            for i, match in enumerate(matches):
                url = _result_url(match.group(2))
                if not url:
                    continue
                end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
                content = text[match.end():end]
                highlights = re.search(r"^(?:Highlights|Text):[ \t]*\r?\n?", content, re.MULTILINE)
                snippet = content[highlights.end():] if highlights else ""
                results.append({"title": _clean_text(match.group(1)), "url": url,
                                "snippet": _snippet(snippet.rstrip().removesuffix("---"))})
        if results or not result["content"]:
            return results[:count]
        raise _SearchFailure("invalid_results")
    raise _SearchFailure("missing_response")


def _parse_ddg_results(html: str, count: int) -> list[dict]:
    results: list[dict] = []
    # HTML and Lite use different classes and either attribute order.
    for m in re.finditer(
        r"<a[^>]*(?:href=['\"]([^'\"]+)['\"][^>]*class=['\"][^'\"]*(?:result-link|result__a)[^'\"]*['\"]|class=['\"][^'\"]*(?:result-link|result__a)[^'\"]*['\"][^>]*href=['\"]([^'\"]+)['\"])[^>]*>(.*?)</a>",
        html,
        re.IGNORECASE | re.DOTALL,
    ):
        url = _result_url(m.group(1) or m.group(2), _DDG_URLS[0])
        title = _clean_text(re.sub(r"<[^>]+>", "", m.group(3)))
        if title and url:
            results.append({"title": title, "url": url})

    # Snippets follow the same order as result links.
    snippets = re.findall(
        r"<(?:td|a)[^>]*class=['\"][^'\"]*(?:result-snippet|result__snippet)[^'\"]*['\"][^>]*>(.*?)</(?:td|a)>",
        html,
        re.IGNORECASE | re.DOTALL,
    )
    for i, snippet_html in enumerate(snippets):
        if i >= len(results):
            break
        results[i]["snippet"] = _snippet(re.sub(r"<[^>]+>", "", snippet_html))

    return results[:count]


def _parse_bing_results(html: str, count: int) -> list[dict]:
    """Extract Bing b_algo organic hits; redirect URLs are unwrapped via _result_url."""
    results: list[dict] = []
    blocks = re.findall(r'<li class="b_algo"[^>]*>(.*?)</li>', html, re.IGNORECASE | re.DOTALL)
    for block in blocks:
        h2 = re.search(r"<h2[^>]*>(.*?)</h2>", block, re.IGNORECASE | re.DOTALL)
        if not h2:
            continue
        anchor = re.search(r'<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', h2.group(1),
                           re.IGNORECASE | re.DOTALL)
        if not anchor:
            continue
        url = _result_url(anchor.group(1))
        title = _clean_text(re.sub(r"<[^>]+>", "", anchor.group(2)))
        if not (url and title):
            continue
        snippet = ""
        p = re.search(r"<p[^>]*>(.*?)</p>", block, re.IGNORECASE | re.DOTALL)
        if p:
            snippet = _snippet(re.sub(r"<[^>]+>", "", p.group(1)))
        results.append({"title": title, "url": url, "snippet": snippet})
    return results[:count]


async def _search_exa(client: httpx.AsyncClient, query: str, count: int) -> list[dict]:
    headers = {"Accept": "application/json, text/event-stream"}
    key = os.environ.get("EXA_API_KEY", "").strip()
    if key:
        headers["x-api-key"] = key
    # Custom API-key headers are not stripped by HTTPX on cross-host redirects.
    response = await client.post(_EXA_URL, headers=headers, follow_redirects=False, json={
        "jsonrpc": "2.0", "id": 1, "method": "tools/call",
        "params": {"name": "web_search_exa", "arguments": {
            "query": query, "objective": query[:4096], "numResults": count,
        }},
    })
    response.raise_for_status()
    return _parse_exa_response(response, count)


async def _search_ddg(client: httpx.AsyncClient, query: str, count: int) -> list[dict]:
    """DDG hits both endpoints; a non-challenge failure on both returns []."""
    for url in _DDG_URLS:
        try:
            response = await client.post(url, headers=_DDG_HEADERS,
                                         data={"q": query, "kl": "uk-ua"})
            response.raise_for_status()
            html = response.text
            if response.status_code == 202 or re.search(
                r"<form\b[^>]*(?:anomaly\.js|challenge-form)", html, re.IGNORECASE,
            ):
                # Both frontends share the block; a second request only adds load.
                raise _SearchFailure("challenge", limited=True)
            results = _parse_ddg_results(html, count)
            if results:
                return results
        except _SearchFailure:
            raise
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 429:
                raise
            log.debug("DDG endpoint %s failed: HTTP %s", url, exc.response.status_code)
        except httpx.RequestError as exc:
            log.debug("DDG endpoint %s failed: %s", url, type(exc).__name__)
    return []


async def _search_bing(client: httpx.AsyncClient, query: str, count: int) -> list[dict]:
    """Hit Bing with a browser-like UA and parse organic b_algo blocks."""
    try:
        response = await client.get(_BING_URL, headers=_BING_HEADERS,
                                    params={"q": query, "count": str(max(count, 10))})
    except httpx.RequestError:
        raise
    if response.status_code == 429:
        raise _SearchFailure("rate_limit", limited=True)
    response.raise_for_status()
    results = _parse_bing_results(response.text, count)
    return results


def _merge_results(lists: list[list[dict]], count: int) -> list[dict]:
    """Merge providers' hits by URL (first provider wins for a given URL)."""
    seen: set[str] = set()
    out: list[dict] = []
    for hits in lists:
        for hit in hits:
            url = hit.get("url")
            if not url or url in seen:
                continue
            seen.add(url)
            out.append(hit)
            if len(out) >= count:
                return out
    return out


async def search_web(query: str, count: int = 3) -> dict:
    """Run all providers in parallel and merge their results."""
    query = (query or "").strip()
    if not query:
        return _error("search.empty_query")
    try:
        count = max(1, min(int(3 if count is None else count), 10))
    except (TypeError, ValueError, OverflowError):
        return _error("search.invalid_count")

    # wait_for also supports Python 3.10, the launcher's minimum version.
    try:
        return await asyncio.wait_for(_run_providers(query, count), timeout=_SEARCH_TIMEOUT)
    except asyncio.TimeoutError:
        log.warning("Search fallback chain timed out")
        return _error("search.unavailable")


async def _call_provider(name: str, handler, client: httpx.AsyncClient,
                         query: str, count: int) -> dict:
    """Return {hits, limited, alive} for one provider; never raises.

    alive=True means the provider answered at all (even with an empty list),
    so the caller can tell 'all dead' from 'all alive but no matches'.
    """
    if _COOLDOWNS.get(name, 0) > monotonic():
        return {"hits": [], "limited": name in _LIMITED, "alive": False}

    # DDG rejects parallel bursts with a 202 challenge; rather than wait, the
    # current request simply skips DDG when another one is already in flight.
    lock = _DDG_LOCK if name == "duckduckgo" else None
    if lock is not None and lock.locked():
        log.debug("Search provider %s skipped: another request is in flight", name)
        return {"hits": [], "limited": False, "alive": False}

    async def _call() -> list[dict]:
        if lock is None:
            return await asyncio.wait_for(handler(client, query, count),
                                          timeout=_PROVIDER_TIMEOUT)
        async with lock:
            return await asyncio.wait_for(handler(client, query, count),
                                          timeout=_PROVIDER_TIMEOUT)

    try:
        hits = await _call()
    except (httpx.HTTPError, _SearchFailure, ValueError, asyncio.TimeoutError) as exc:
        status = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else None
        blocked = status == 429 or isinstance(exc, _SearchFailure) and exc.limited
        delay = 60.0 if blocked else 15.0
        if blocked and isinstance(exc, httpx.HTTPStatusError):
            try:
                delay = max(delay, min(float(exc.response.headers.get("retry-after", 60)), 300))
            except ValueError:
                pass
        _COOLDOWNS[name] = monotonic() + delay
        if blocked:
            _LIMITED.add(name)
        else:
            _LIMITED.discard(name)
        reason = exc.reason if isinstance(exc, _SearchFailure) else type(exc).__name__
        log.warning("Search provider %s failed: %s (HTTP %s)", name, reason, status)
        return {"hits": [], "limited": blocked, "alive": False}
    _COOLDOWNS.pop(name, None)
    _LIMITED.discard(name)
    return {"hits": hits, "limited": False, "alive": True}


async def _run_providers(query: str, count: int) -> dict:
    """All providers in parallel; pick whichever return results and merge."""
    async with httpx.AsyncClient(timeout=_PROVIDER_TIMEOUT + 0.5,
                                 follow_redirects=True) as client:
        outcomes = await asyncio.gather(
            _call_provider("exa", _search_exa, client, query, count),
            _call_provider("duckduckgo", _search_ddg, client, query, count),
            _call_provider("bing", _search_bing, client, query, count),
        )

    provider_names = ("exa", "duckduckgo", "bing")
    hits_lists: list[list[dict]] = []
    used_providers: list[str] = []
    any_alive = False
    any_limited = False
    for name, outcome in zip(provider_names, outcomes):
        if outcome["alive"]:
            any_alive = True
        if outcome["hits"]:
            used_providers.append(name)
            hits_lists.append(outcome["hits"])
        if outcome["limited"]:
            any_limited = True

    if hits_lists:
        merged = _merge_results(hits_lists, count)
        if merged:
            return {"query": query, "results": merged,
                    "provider": "+".join(used_providers)}
    if any_alive:
        return _error("search.no_results")
    return _error("search.rate_limited" if any_limited else "search.unavailable")


# ---------------------------------------------------------------------------
# fetch_top_pages: search + parallel page download + text extraction.
# ---------------------------------------------------------------------------

_FETCH_TIMEOUT = 6.0
_FETCH_MAX_BYTES = 200_000
_FETCH_TEXT_CHARS = 600
_FETCH_DEFAULT_COUNT = 5
_FETCH_MAX_COUNT = 10
_STRIP_TAGS = re.compile(
    r"<(script|style|noscript|template|svg|iframe|form|button|nav|header|footer|aside)[^>]*>.*?</\1>",
    re.IGNORECASE | re.DOTALL,
)
_TAG = re.compile(r"<[^>]+>")


def _check_public_fetch(url: str) -> None:
    """Same allowlist idea as web_browser._check_public: http(s) only, no
    private/loopback/reserved IPs. Raises ValueError on rejection."""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        raise ValueError("only http(s) is allowed")
    host = parsed.hostname
    if not host:
        raise ValueError("invalid host")
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror as exc:
        raise ValueError(f"unresolvable host: {host}") from exc
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if (ip.is_private or ip.is_loopback or ip.is_link_local
                or ip.is_reserved or ip.is_multicast or ip.is_unspecified):
            raise ValueError(f"private/loopback IP blocked: {ip}")


def _extract_title(html: str) -> str:
    m = re.search(r"<title[^>]*>(.*?)</title>", html, re.IGNORECASE | re.DOTALL)
    return _clean_text(m.group(1))[:200] if m else ""


def _extract_text(html: str, limit: int = _FETCH_TEXT_CHARS) -> str:
    """Strip boilerplate tags, collapse remaining markup to text, cap length."""
    body = _STRIP_TAGS.sub(" ", html)
    # Drop comments and remaining tags.
    body = re.sub(r"<!--.*?-->", " ", body, flags=re.DOTALL)
    body = _TAG.sub(" ", body)
    text = _clean_text(body)
    if len(text) <= limit:
        return text
    return text[:limit].rstrip() + "…"


async def _fetch_one(client: httpx.AsyncClient, url: str) -> dict:
    """Fetch a single URL; never raises. Returns {url, ok, ...} dict."""
    try:
        _check_public_fetch(url)
    except ValueError as exc:
        return {"url": url, "ok": False, "reason": f"blocked: {exc}"}
    try:
        response = await client.get(
            url,
            headers={
                "User-Agent": _BING_HEADERS["User-Agent"],
                "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
                "Accept-Language": "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
            },
            timeout=_FETCH_TIMEOUT,
            follow_redirects=False,  # stay safe; redirects would need re-validation
        )
    except httpx.HTTPError as exc:
        return {"url": url, "ok": False, "reason": type(exc).__name__}
    if response.is_redirect:
        return {"url": url, "ok": False, "reason": f"redirect {response.status_code}"}
    if response.status_code >= 400:
        return {"url": url, "ok": False, "reason": f"HTTP {response.status_code}"}
    content_type = (response.headers.get("content-type") or "").split(";")[0].lower()
    if content_type and not (
        content_type.startswith("text/html")
        or content_type.startswith("application/xhtml")
        or content_type.startswith("text/plain")
    ):
        return {"url": url, "ok": False, "reason": f"not html: {content_type}"}
    body = response.content[:_FETCH_MAX_BYTES]
    text = body.decode(response.encoding or "utf-8", errors="replace")
    return {
        "url": url,
        "ok": True,
        "title": _extract_title(text),
        "text": _extract_text(text),
        "bytes": len(body),
    }


async def fetch_top_pages(query: str, count: int = _FETCH_DEFAULT_COUNT) -> dict:
    """Search once, then fetch the top N result pages in parallel.

    Returns {"query", "results": [{url, title, snippet, text, ok}], "fetched",
             "provider"} or an _error dict. Never raises.
    """
    query = (query or "").strip()
    if not query:
        return _error("search.empty_query")
    try:
        count = max(1, min(int(_FETCH_DEFAULT_COUNT if count is None else count),
                           _FETCH_MAX_COUNT))
    except (TypeError, ValueError, OverflowError):
        return _error("search.invalid_count")

    search_result = await search_web(query, count)
    if "error" in search_result:
        return search_result

    hits = search_result.get("results", [])[:count]
    if not hits:
        return _error("search.no_results")

    try:
        async with httpx.AsyncClient(timeout=_FETCH_TIMEOUT + 1.0) as client:
            fetched = await asyncio.wait_for(
                asyncio.gather(*[_fetch_one(client, hit["url"]) for hit in hits]),
                timeout=_FETCH_TIMEOUT * 2,
            )
    except asyncio.TimeoutError:
        log.warning("fetch_top_pages gather timed out")
        return _error("search.unavailable")

    out: list[dict] = []
    successful_fetches = 0
    for hit, fetch in zip(hits, fetched):
        row = {
            "url": hit["url"],
            "title": hit.get("title") or fetch.get("title", ""),
            "snippet": hit.get("snippet", ""),
            "ok": fetch["ok"],
        }
        if fetch["ok"]:
            successful_fetches += 1
            row["text"] = fetch["text"]
            if not row["title"]:
                row["title"] = fetch.get("title", "")
        else:
            row["reason"] = fetch.get("reason", "fetch failed")
        out.append(row)

    return {
        "query": query,
        "provider": search_result.get("provider", ""),
        "results": out,
        "fetched": successful_fetches,
        "total": len(out),
    }
