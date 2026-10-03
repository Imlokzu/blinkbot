"""Web search through Exa's hosted MCP, with a DuckDuckGo fallback."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
from html import unescape
from time import monotonic
from urllib.parse import parse_qs, urljoin, urlsplit

import httpx

from tools.locales import t

log = logging.getLogger("virtual_bot.tools.search")

_EXA_URL = "https://mcp.exa.ai/mcp"
_SEARCH_TIMEOUT = 25.0
_PROVIDER_TIMEOUT = 10.0
_DDG_URLS = (
    "https://html.duckduckgo.com/html/",
    "https://lite.duckduckgo.com/lite/",
)
_DDG_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "text/html",
    "Accept-Language": "uk-UA,uk;q=0.9,en-US;q=0.8,en;q=0.7",
}
_COOLDOWNS: dict[str, float] = {}
_LIMITED: set[str] = set()


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
        return url if parsed.scheme in {"http", "https"} and parsed.hostname else ""
    except ValueError:
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
    failure: Exception | None = None
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
            failure = exc
        except httpx.RequestError as exc:
            failure = exc
    if failure:
        raise failure
    return []


async def search_web(query: str, count: int = 3) -> dict:
    """Keep the existing result contract while switching failed providers."""
    query = (query or "").strip()
    if not query:
        return _error("search.empty_query")
    try:
        count = max(1, min(int(3 if count is None else count), 5))
    except (TypeError, ValueError, OverflowError):
        return _error("search.invalid_count")

    # wait_for also supports Python 3.10, the launcher's minimum version.
    try:
        return await asyncio.wait_for(_try_providers(query, count), timeout=_SEARCH_TIMEOUT)
    except asyncio.TimeoutError:
        log.warning("Search fallback chain timed out")
        return _error("search.unavailable")


async def _try_providers(query: str, count: int) -> dict:
    had_empty_results = False
    limited = False
    async with httpx.AsyncClient(timeout=10.0, follow_redirects=True) as client:
        for provider, handler in (("exa", _search_exa), ("duckduckgo", _search_ddg)):
            if _COOLDOWNS.get(provider, 0) > monotonic():
                limited |= provider in _LIMITED
                continue
            try:
                # HTTPX limits each read; an SSE stream needs an absolute deadline.
                results = await asyncio.wait_for(handler(client, query, count),
                                                 timeout=_PROVIDER_TIMEOUT)
            except (httpx.HTTPError, _SearchFailure, ValueError, asyncio.TimeoutError) as exc:
                status = exc.response.status_code if isinstance(exc, httpx.HTTPStatusError) else None
                blocked = status == 429 or isinstance(exc, _SearchFailure) and exc.limited
                delay = 60.0 if blocked else 15.0
                if blocked and isinstance(exc, httpx.HTTPStatusError):
                    try:
                        delay = max(delay, min(float(exc.response.headers.get("retry-after", 60)), 300))
                    except ValueError:
                        pass
                _COOLDOWNS[provider] = monotonic() + delay
                if blocked:
                    _LIMITED.add(provider)
                else:
                    _LIMITED.discard(provider)
                limited |= blocked
                reason = exc.reason if isinstance(exc, _SearchFailure) else type(exc).__name__
                log.warning("Search provider %s failed: %s (HTTP %s)", provider,
                            reason, status)
                continue
            _COOLDOWNS.pop(provider, None)
            _LIMITED.discard(provider)
            if results:
                return {"query": query, "results": results, "provider": provider}
            had_empty_results = True

    if had_empty_results:
        return _error("search.no_results")
    return _error("search.rate_limited" if limited else "search.unavailable")
