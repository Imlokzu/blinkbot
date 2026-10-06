from __future__ import annotations

import asyncio
import json
import os
import unittest
from unittest.mock import patch

import httpx

import ipaddress
import socket
from tools import search


_REAL_ASYNC_CLIENT = httpx.AsyncClient
_HTML = """
<div class="result">
  <a rel="nofollow" class="result__a" href="https://openai.com/codex/">OpenAI Codex</a>
  <a class="result__snippet">Coding agent from OpenAI.</a>
</div>
"""
_BING_HTML = """
<li class="b_algo"><h2><a href="https://www.bing.com/ck/a?!&p=x&u=a1aHR0cHM6Ly9iaW5nLmV4YW1wbGUv">Bing Hit</a></h2>
<p>A hit only Bing returned.</p></li>
<li class="b_algo"><h2><a href="https://bing.example/second">Bing Second</a></h2>
<p>Second Bing hit.</p></li>
"""
_EXA_TEXT = """Title: OpenAI Codex
URL: https://openai.com/codex/
ID: https://openai.com/codex/
Highlights:
Coding agent from OpenAI.
Title: Python documentation
URL: https://docs.python.org/3/
Highlights:
Official Python reference.
"""


def _exa_message(text: str = _EXA_TEXT) -> dict:
    return {
        "jsonrpc": "2.0",
        "id": 1,
        "result": {"content": [{"type": "text", "text": text}]},
    }


def _url_str(request) -> str:
    return str(request.url)


class WebSearchTests(unittest.TestCase):
    def setUp(self) -> None:
        # Never use an owner's paid key or a cooldown left by another test.
        self.environment = patch.dict(os.environ, {"EXA_API_KEY": ""})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        # Most tests predate the SearXNG provider — disable it by default so
        # their hand-rolled handlers don't see a fourth request. The one test
        # that targets SearXNG explicitly re-enables it via `use_searxng()`.
        self._original_searxng = search._SEARXNG_URL
        search._SEARXNG_URL = ""
        self.addCleanup(self._restore_searxng)
        search._COOLDOWNS.clear()
        search._LIMITED.clear()
        self.addCleanup(search._COOLDOWNS.clear)
        self.addCleanup(search._LIMITED.clear)

    def _restore_searxng(self) -> None:
        search._SEARXNG_URL = self._original_searxng

    def use_searxng(self, url: str = "http://testhost.local:8888") -> None:
        """Re-enable the SearXNG provider for tests that target it."""
        search._SEARXNG_URL = url

    def _run(self, handler, query="OpenAI Codex", count=3):
        requests = []

        async def transport_handler(request):
            requests.append(request)
            response = handler(request)
            if asyncio.iscoroutine(response):
                response = await response
            return response

        def client_factory(*args, **kwargs):
            return _REAL_ASYNC_CLIENT(
                *args, **kwargs, transport=httpx.MockTransport(transport_handler)
            )

        with patch.object(search.httpx, "AsyncClient", side_effect=client_factory):
            result = asyncio.run(search.search_web(query, count))
        return result, requests

    # Default routing table: Exa replies, DDG replies, Bing 404s by default
    # so the existing tests keep their shape. Tests that care about Bing
    # override the table themselves.
    def _default_handler(self, request):
        url = _url_str(request)
        if url == search._EXA_URL:
            return httpx.Response(200, json=_exa_message())
        if url.startswith(search._BING_URL):
            return httpx.Response(404)
        return httpx.Response(200, text=_HTML)

    def _ddg_only(self, request):
        url = _url_str(request)
        if url == search._EXA_URL:
            return httpx.Response(503)
        if url.startswith(search._BING_URL):
            return httpx.Response(404)
        return httpx.Response(200, text=_HTML)

    def test_exa_is_primary_and_uses_current_hosted_mcp_schema(self) -> None:
        result, requests = self._run(self._default_handler)

        urls = {_url_str(r) for r in requests}
        self.assertIn(search._EXA_URL, urls)
        exa_request = next(r for r in requests if _url_str(r) == search._EXA_URL)
        self.assertNotIn("x-api-key", exa_request.headers)
        self.assertEqual(json.loads(exa_request.content), {
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "web_search_exa", "arguments": {
                "query": "OpenAI Codex", "objective": "OpenAI Codex", "numResults": 3,
            }},
        })
        # Exa returns 2 hits; DDG's 1 is appended, but the same URL is dropped.
        self.assertEqual(result["query"], "OpenAI Codex")
        self.assertIn("exa", result["provider"])
        urls = [r["url"] for r in result["results"]]
        # Exa hits must lead the merged list.
        self.assertEqual(urls[0], "https://openai.com/codex/")
        self.assertEqual(urls[1], "https://docs.python.org/3/")

    def test_exa_key_is_header_only_and_never_sent_to_fallback(self) -> None:
        with patch.dict(os.environ, {"EXA_API_KEY": "test-exa-key"}):
            result, requests = self._run(self._ddg_only)

        exa_request = next(r for r in requests if _url_str(r) == search._EXA_URL)
        self.assertEqual(exa_request.headers["x-api-key"], "test-exa-key")
        for request in requests:
            if _url_str(request) == search._EXA_URL:
                continue
            self.assertNotIn("test-exa-key", _url_str(request))
            self.assertNotIn("test-exa-key", request.content.decode())
            self.assertNotIn("x-api-key", request.headers)

    def test_exa_redirect_does_not_forward_api_key_to_another_host(self) -> None:
        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(302, headers={"location": "https://other.example/search"})
            if url.startswith(search._BING_URL):
                return httpx.Response(404)
            if url == search._DDG_URLS[0]:
                return httpx.Response(200, text=_HTML)
            self.fail("An Exa redirect must not receive the API-key header")

        with patch.dict(os.environ, {"EXA_API_KEY": "test-exa-key"}):
            result, requests = self._run(handler)

        self.assertIn("duckduckgo", result["provider"])
        exa_request = next(r for r in requests if _url_str(r) == search._EXA_URL)
        self.assertEqual(exa_request.headers["x-api-key"], "test-exa-key")
        for request in requests:
            if _url_str(request) != search._EXA_URL:
                self.assertNotIn("x-api-key", request.headers)

    def test_exa_accepts_sse_with_adjacent_results_and_highlights(self) -> None:
        payload = (
            'event: endpoint\ndata: {"jsonrpc":"2.0","method":"notifications/ping"}\n\n'
            "event: message\ndata: " + json.dumps(_exa_message()) + "\n\n"
            "data: [DONE]\n\n"
        )

        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(200, text=payload, headers={"content-type": "text/event-stream"})
            if url.startswith(search._BING_URL):
                return httpx.Response(404)
            return httpx.Response(200, text="<html></html>")

        result, requests = self._run(handler)
        self.assertIn("exa", result["provider"])
        self.assertEqual([hit["title"] for hit in result["results"]][:2],
                         ["OpenAI Codex", "Python documentation"])
        self.assertEqual(result["results"][0]["snippet"], "Coding agent from OpenAI.")
        self.assertEqual(result["results"][1]["snippet"], "Official Python reference.")

    def test_exa_accepts_text_snippets_without_highlights(self) -> None:
        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(200, json=_exa_message(
                    _EXA_TEXT.replace("Highlights:", "Text:")))
            if url.startswith(search._BING_URL):
                return httpx.Response(404)
            return httpx.Response(200, text="<html></html>")

        result, _ = self._run(handler)
        self.assertIn("exa", result["provider"])
        self.assertEqual(result["results"][0]["snippet"], "Coding agent from OpenAI.")

    def test_query_is_trimmed_and_objective_respects_provider_limit(self) -> None:
        query = "x" * 5000
        result, requests = self._run(self._default_handler, "  " + query + "  ")
        exa_request = next(r for r in requests if _url_str(r) == search._EXA_URL)
        arguments = json.loads(exa_request.content)["params"]["arguments"]

        self.assertEqual(result["query"], query)
        self.assertEqual(arguments["query"], query)
        self.assertEqual(arguments["objective"], query[:4096])

    def test_result_count_is_clamped_and_limits_returned_hits(self) -> None:
        text = "".join(
            f"Title: Result {i}\nURL: https://example.com/{i}\nHighlights:\nSnippet {i}\n"
            for i in range(12)
        )

        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(200, json=_exa_message(text))
            if url.startswith(search._BING_URL):
                return httpx.Response(404)
            return httpx.Response(200, text="<html></html>")

        for count, expected in ((-5, 1), (0, 1), (1, 1), (3, 3), (99, 10)):
            with self.subTest(count=count):
                result, requests = self._run(handler, count=count)
                exa_request = next(r for r in requests if _url_str(r) == search._EXA_URL)
                arguments = json.loads(exa_request.content)["params"]["arguments"]
                self.assertEqual(arguments["numResults"], expected)
                self.assertEqual(len(result["results"]), expected)

    def test_invalid_inputs_return_localized_errors_without_network(self) -> None:
        def unexpected_request(request):
            self.fail("Input validation must run before requesting a provider")

        for query in ("", " \n ", None):
            with self.subTest(query=query):
                result, requests = self._run(unexpected_request, query=query)
                self.assertEqual(result["error_code"], "search.empty_query")
                self.assertIsInstance(result["error"], str)
                self.assertTrue(result["error"])
                self.assertEqual(requests, [])
        for count in ("invalid", "", {}, [], float("inf")):
            with self.subTest(count=count):
                result, requests = self._run(unexpected_request, count=count)
                self.assertEqual(result["error_code"], "search.invalid_count")
                self.assertIsInstance(result["error"], str)
                self.assertTrue(result["error"])
                self.assertEqual(requests, [])

    def test_parses_current_duckduckgo_html_markup(self) -> None:
        result, requests = self._run(self._ddg_only)
        urls = {_url_str(r) for r in requests}
        self.assertIn(search._EXA_URL, urls)
        self.assertIn(search._DDG_URLS[0], urls)
        self.assertIn("duckduckgo", result["provider"])
        # The DDG hit follows Exa's empty list; the merged list is DDG's only entry.
        self.assertEqual(result["results"][0]["title"], "OpenAI Codex")
        self.assertEqual(result["results"][0]["url"], "https://openai.com/codex/")
        self.assertEqual(result["results"][0]["snippet"], "Coding agent from OpenAI.")

    def test_duckduckgo_lite_is_tried_after_html_endpoint_failure(self) -> None:
        lite_html = """
        <a href="https://example.com/reference" class="result-link">Reference</a>
        <td class="result-snippet">An offline-friendly reference.</td>
        """
        for failure in ("http", "network"):
            with self.subTest(failure=failure):
                search._COOLDOWNS.clear()

                def handler(request):
                    url = _url_str(request)
                    if url == search._EXA_URL:
                        return httpx.Response(503)
                    if url.startswith(search._BING_URL):
                        return httpx.Response(404)
                    if url == search._DDG_URLS[0]:
                        if failure == "network":
                            raise httpx.ConnectError("Mock failure", request=request)
                        return httpx.Response(500)
                    return httpx.Response(200, text=lite_html)

                result, requests = self._run(handler)
                urls = [_url_str(r) for r in requests]
                self.assertIn(search._DDG_URLS[0], urls)
                self.assertIn(search._DDG_URLS[1], urls)
                self.assertIn("duckduckgo", result["provider"])
                self.assertEqual(result["results"][0], {
                    "title": "Reference", "url": "https://example.com/reference",
                    "snippet": "An offline-friendly reference.",
                })

    def test_duckduckgo_redirect_is_unwrapped_and_entities_are_decoded(self) -> None:
        html = """
        <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F%3Fx%3D1%26y%3D2&amp;rut=123">Cars &amp; parts</a>
        <a class="result__snippet">A &lt;useful&gt; reference.</a>
        """

        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(503)
            if url.startswith(search._BING_URL):
                return httpx.Response(404)
            return httpx.Response(200, text=html)

        result, _ = self._run(handler)
        self.assertEqual(result["results"][0], {
            "title": "Cars & parts", "url": "https://example.com/?x=1&y=2",
            "snippet": "A <useful> reference.",
        })

    def test_duckduckgo_search_results_about_captcha_are_not_challenges(self) -> None:
        html = _HTML.replace("OpenAI Codex", "CAPTCHA reference").replace(
            "Coding agent from OpenAI.",
            "Bots use DuckDuckGo; challenge-form and anomaly.js explained.",
        )

        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(503)
            if url.startswith(search._BING_URL):
                return httpx.Response(404)
            return httpx.Response(200, text=html)

        result, _ = self._run(handler)
        self.assertIn("duckduckgo", result["provider"])
        self.assertEqual(result["results"][0]["title"], "CAPTCHA reference")
        self.assertNotIn("duckduckgo", search._LIMITED)

    def test_malformed_exa_responses_safely_fall_back(self) -> None:
        bad_responses = (
            httpx.Response(200, text="{broken json"),
            httpx.Response(200, text="event: message\ndata: {bad\n\n"),
            httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "error": {"code": -32603}}),
            httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "result": None}),
            httpx.Response(200, json=_exa_message("Title: A result without a URL\n")),
            httpx.Response(200, json=_exa_message("Title: Unsafe URL\nURL: javascript:alert(1)\n")),
            httpx.Response(200, json={
                **_exa_message(), "result": {"isError": True, "content": _exa_message()["result"]["content"]},
            }),
        )
        for response in bad_responses:
            with self.subTest(response=response.text):
                search._COOLDOWNS.clear()

                def handler(request):
                    url = _url_str(request)
                    if url == search._EXA_URL:
                        return response
                    if url.startswith(search._BING_URL):
                        return httpx.Response(404)
                    return httpx.Response(200, text=_HTML)

                result, _ = self._run(handler)
                self.assertIn("duckduckgo", result["provider"])

    def test_exa_rate_limit_cools_down_then_recovers(self) -> None:
        def limited_exa(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(429, headers={"retry-after": "120"})
            if url.startswith(search._BING_URL):
                return httpx.Response(404)
            return httpx.Response(200, text=_HTML)

        with patch.object(search, "monotonic", return_value=1000.0):
            result, first_requests = self._run(limited_exa)
            result_again, second_requests = self._run(limited_exa)

        self.assertIn("duckduckgo", result["provider"])
        self.assertIn("duckduckgo", result_again["provider"])
        first_urls = [_url_str(r) for r in first_requests]
        second_urls = [_url_str(r) for r in second_requests]
        self.assertIn(search._EXA_URL, first_urls)
        self.assertNotIn(search._EXA_URL, second_urls)
        self.assertGreaterEqual(search._COOLDOWNS["exa"], 1120.0)
        deadline = search._COOLDOWNS["exa"]
        with patch.object(search, "monotonic", return_value=deadline + 1):
            recovered, requests = self._run(self._default_handler)

        self.assertIn("exa", recovered["provider"])
        self.assertNotIn("exa", search._COOLDOWNS)
        self.assertNotIn("exa", search._LIMITED)

    def test_exa_rpc_and_tool_rate_limits_cool_down_without_leaking_key(self) -> None:
        payloads = (
            {"jsonrpc": "2.0", "id": 1,
             "error": {"code": -32000, "message": "Rate limit exceeded test-secret"}},
            {"jsonrpc": "2.0", "id": 1, "result": {
                "isError": True, "content": [{"type": "text", "text": "HTTP 429 test-secret"}],
            }},
        )
        for payload in payloads:
            with self.subTest(payload=payload):
                search._COOLDOWNS.clear()
                search._LIMITED.clear()
                with patch.dict(os.environ, {"EXA_API_KEY": "test-secret"}), \
                     patch.object(search, "monotonic", return_value=1000.0), \
                     self.assertLogs(search.log, level="WARNING") as captured:
                    def handler(request):
                        url = _url_str(request)
                        if url == search._EXA_URL:
                            return httpx.Response(200, json=payload)
                        if url.startswith(search._BING_URL):
                            return httpx.Response(429)
                        # DDG is the only one alive, and even it gets the
                        # challenge-form treatment.
                        return httpx.Response(200, text='<form id="challenge-form">CAPTCHA</form>')

                    result, requests = self._run(handler)

                self.assertEqual(result["error_code"], "search.rate_limited")
                self.assertGreaterEqual(search._COOLDOWNS["exa"], 1060.0)
                self.assertIn("exa", search._LIMITED)
                # One Exa + one Bing + one DDG (challenge-form raises immediately).
                self.assertEqual(len(requests), 3)
                self.assertNotIn("test-secret", json.dumps(result))
                self.assertNotIn("test-secret", " ".join(captured.output))

    def test_duckduckgo_challenges_and_429_are_not_reported_as_no_results(self) -> None:
        blocked_responses = (
            httpx.Response(202, text="Accepted"),
            httpx.Response(200, text='<form id="challenge-form">CAPTCHA</form>'),
            httpx.Response(200, text='<form action="//duckduckgo.com/anomaly.js">Challenge</form>'),
            httpx.Response(429),
        )
        for response in blocked_responses:
            with self.subTest(status=response.status_code, body=response.text):
                search._COOLDOWNS.clear()
                search._LIMITED.clear()

                def handler(request):
                    url = _url_str(request)
                    if url == search._EXA_URL:
                        return httpx.Response(503)
                    if url.startswith(search._BING_URL):
                        return httpx.Response(503)
                    return response

                with patch.object(search, "monotonic", return_value=1000.0):
                    result, first_requests = self._run(handler)
                    result_again, second_requests = self._run(handler)
                self.assertEqual(result["error_code"], "search.rate_limited")
                self.assertEqual(result_again["error_code"], "search.rate_limited")
                # One Exa + one Bing + two DDG (HTML then Lite, which gets the
                # same challenge/429 treatment). DDG raises on a challenge, so
                # only the first endpoint gets hit — that is 3 requests, not 4.
                self.assertIn(len(first_requests), (3, 4))
                # Second pass: everything is cooled down, no network call leaves.
                self.assertEqual(second_requests, [])

                deadline = max(search._COOLDOWNS.values())
                with patch.object(search, "monotonic", return_value=deadline + 1):
                    recovered, _ = self._run(self._ddg_only)
                self.assertIn("duckduckgo", recovered["provider"])
                self.assertNotIn("duckduckgo", search._COOLDOWNS)
                self.assertNotIn("duckduckgo", search._LIMITED)

    def test_empty_successful_results_have_distinct_error(self) -> None:
        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "result": {"content": []}})
            if url.startswith(search._BING_URL):
                return httpx.Response(200, text="<html></html>")
            return httpx.Response(200, text="<html>No matching pages</html>")

        result, _ = self._run(handler)
        self.assertEqual(result["error_code"], "search.no_results")

    def test_complete_provider_chain_has_a_bounded_timeout(self) -> None:
        self.assertLessEqual(search._SEARCH_TIMEOUT, 10.0)
        cancelled = []

        async def stalled(request):
            try:
                await asyncio.sleep(10)
            finally:
                cancelled.append(True)
            return httpx.Response(200, json=_exa_message())

        def handler(request):
            return stalled(request)

        with patch.object(search, "_SEARCH_TIMEOUT", 0.02):
            result, _ = self._run(handler)
        self.assertEqual(result["error_code"], "search.unavailable")
        # All three providers got cancelled by the global deadline.
        self.assertEqual(cancelled, [True, True, True])

    def test_stalled_provider_is_cancelled_and_cooled_down_before_fallback(self) -> None:
        cancelled = []

        async def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                try:
                    await asyncio.sleep(10)
                finally:
                    cancelled.append(True)
                return httpx.Response(200, json=_exa_message())
            if url.startswith(search._BING_URL):
                return httpx.Response(404)
            return httpx.Response(200, text=_HTML)

        with patch.object(search, "_PROVIDER_TIMEOUT", 0.02):
            first, first_requests = self._run(handler)
            second, second_requests = self._run(handler)

        self.assertIn("duckduckgo", first["provider"])
        self.assertIn("duckduckgo", second["provider"])
        self.assertEqual(cancelled, [True])
        first_urls = {_url_str(r) for r in first_requests}
        self.assertIn(search._EXA_URL, first_urls)
        second_urls = {_url_str(r) for r in second_requests}
        self.assertNotIn(search._EXA_URL, second_urls)
        self.assertIn("exa", search._COOLDOWNS)
        self.assertNotIn("exa", search._LIMITED)

    def test_bing_results_are_unwrapped_and_merged(self) -> None:
        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(503)
            if url.startswith(search._BING_URL):
                return httpx.Response(200, text=_BING_HTML)
            return httpx.Response(503)

        result, requests = self._run(handler)

        bing_request = next(r for r in requests if _url_str(r).startswith(search._BING_URL))
        self.assertIn("q=OpenAI+Codex", _url_str(bing_request))
        self.assertIn("bing", result["provider"])
        urls = [hit["url"] for hit in result["results"]]
        # First hit was wrapped in /ck/a with a base64 u=a1 payload; it must be unwrapped.
        self.assertIn("https://bing.example/", urls)
        self.assertIn("https://bing.example/second", urls)
        self.assertNotIn("bing.com/ck", " ".join(urls))

    def test_bing_429_cools_down_without_failing_other_providers(self) -> None:
        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                return httpx.Response(200, json=_exa_message())
            if url.startswith(search._BING_URL):
                return httpx.Response(429)
            return httpx.Response(200, text=_HTML)

        with patch.object(search, "monotonic", return_value=1000.0):
            result, _ = self._run(handler)

        self.assertIn("exa", result["provider"])
        self.assertGreaterEqual(search._COOLDOWNS["bing"], 1060.0)
        self.assertIn("bing", search._LIMITED)

    def test_merge_dedups_by_url_and_keeps_first_provider_order(self) -> None:
        exa_hits = [{"title": "Exa A", "url": "https://a.example", "snippet": "from exa"}]
        ddg_hits = [
            {"title": "DDG A dup", "url": "https://a.example", "snippet": "from ddg"},
            {"title": "DDG B", "url": "https://b.example", "snippet": "from ddg"},
        ]
        bing_hits = [{"title": "Bing C", "url": "https://c.example", "snippet": "from bing"}]

        def handler(request):
            url = _url_str(request)
            if url == search._EXA_URL:
                text = "".join(
                    f"Title: {h['title']}\nURL: {h['url']}\nHighlights:\n{h['snippet']}\n"
                    for h in exa_hits
                )
                return httpx.Response(200, json=_exa_message(text))
            if url.startswith(search._BING_URL):
                blocks = "".join(
                    f'<li class="b_algo"><h2><a href="{h["url"]}">{h["title"]}</a></h2>'
                    f'<p>{h["snippet"]}</p></li>'
                    for h in bing_hits
                )
                return httpx.Response(200, text=blocks)
            ddg_anchors = "".join(
                f'<a class="result__a" href="{h["url"]}">{h["title"]}</a>'
                f'<a class="result__snippet">{h["snippet"]}</a>'
                for h in ddg_hits
            )
            return httpx.Response(200, text=ddg_anchors)

        result, _ = self._run(handler)
        urls = [hit["url"] for hit in result["results"]]
        self.assertEqual(urls, ["https://a.example", "https://b.example", "https://c.example"])
        # The first provider to deliver a URL keeps its snippet.
        a_hit = next(hit for hit in result["results"] if hit["url"] == "https://a.example")
        self.assertEqual(a_hit["snippet"], "from exa")


class FetchTopPagesTests(unittest.TestCase):
    """End-to-end of the search+fetch pipeline, with both stages mocked."""

    def setUp(self) -> None:
        # These tests target the search + fetch pipeline, not the providers.
        self._original_searxng = search._SEARXNG_URL
        search._SEARXNG_URL = ""
        self.addCleanup(self._restore_searxng)
        search._COOLDOWNS.clear()
        search._LIMITED.clear()
        self.addCleanup(search._COOLDOWNS.clear)
        self.addCleanup(search._LIMITED.clear)
        # Mock DNS so any host resolves to a public IP except the ones the
        # test explicitly maps to private ranges.
        self._private_hosts: set[str] = set()
        real_getaddrinfo = socket.getaddrinfo

        def fake_getaddrinfo(host, port, *args, **kwargs):
            if host in self._private_hosts:
                return real_getaddrinfo("192.168.1.1", port, *args, **kwargs)
            # IP literals pass through unchanged so private-IP tests still hit
            # the is_private branch.
            try:
                ip = ipaddress.ip_address(host)
                return real_getaddrinfo(str(ip), port, *args, **kwargs)
            except ValueError:
                pass
            # Anything else resolves as a public IP so _check_public_fetch passes.
            return real_getaddrinfo("93.184.216.34", port, *args, **kwargs)  # example.com

        patcher = patch.object(search.socket, "getaddrinfo", side_effect=fake_getaddrinfo)
        patcher.start()
        self.addCleanup(patcher.stop)

    def _restore_searxng(self) -> None:
        search._SEARXNG_URL = self._original_searxng

    def _run_fetch(self, handler, query="cats", count=3):
        requests = []

        async def transport_handler(request):
            requests.append(request)
            response = handler(request)
            if asyncio.iscoroutine(response):
                response = await response
            return response

        def client_factory(*args, **kwargs):
            return _REAL_ASYNC_CLIENT(
                *args, **kwargs, transport=httpx.MockTransport(transport_handler)
            )

        with patch.object(search.httpx, "AsyncClient", side_effect=client_factory):
            result = asyncio.run(search.fetch_top_pages(query, count))
        return result, requests

    def _search_and_pages(self, request):
        """Bing returns 3 results; each result page returns minimal HTML."""
        url = _url_str(request)
        if url.startswith(search._BING_URL):
            html = "".join(
                f'<li class="b_algo"><h2><a href="https://p{i}.example/">Page {i}</a></h2>'
                f'<p>Snippet {i}</p></li>'
                for i in range(3)
            )
            return httpx.Response(200, text=html)
        if url == search._EXA_URL or url.startswith(search._DDG_URLS[0]) or url.startswith(search._DDG_URLS[1]):
            return httpx.Response(503)
        # Page fetch: /p0, /p1, /p2 all return simple HTML
        if url.startswith("https://p") and url.endswith(".example/"):
            return httpx.Response(200, text=f"""
                <html><head><title>Page {url}</title></head>
                <body><p>Body text for {url}</p>
                <script>ignored()</script><style>.x{{color:red}}</style>
                </body></html>""")
        return httpx.Response(404)

    def test_fetch_top_pages_fetches_all_in_parallel(self) -> None:
        result, _ = self._run_fetch(self._search_and_pages, count=3)
        self.assertNotIn("error", result)
        self.assertEqual(result["fetched"], 3)
        self.assertEqual(result["total"], 3)
        for row in result["results"]:
            self.assertTrue(row["ok"])
            self.assertIn("Body text", row["text"])
            # Script/style are stripped by _extract_text
            self.assertNotIn("ignored()", row["text"])
            self.assertNotIn("color:red", row["text"])

    def test_fetch_top_pages_blocks_private_ips(self) -> None:
        # Mark internal.example as resolving to 192.168.1.1 for the DNS mock.
        self._private_hosts.add("internal.example")

        def handler(request):
            url = _url_str(request)
            if url.startswith(search._BING_URL):
                html = (
                    '<li class="b_algo"><h2><a href="http://127.0.0.1/x">A</a></h2><p>x</p></li>'
                    '<li class="b_algo"><h2><a href="http://internal.example/x">B</a></h2><p>x</p></li>'
                    '<li class="b_algo"><h2><a href="http://10.0.0.1/x">C</a></h2><p>x</p></li>'
                )
                return httpx.Response(200, text=html)
            if url == search._EXA_URL:
                return httpx.Response(503)
            return httpx.Response(503)

        result, requests = self._run_fetch(handler)
        # None of the private-IP URLs should ever be touched.
        fetched_urls = {_url_str(r) for r in requests
                        if not _url_str(r).startswith(search._BING_URL)
                        and _url_str(r) != search._EXA_URL
                        and not _url_str(r).startswith(search._DDG_URLS[0])
                        and not _url_str(r).startswith(search._DDG_URLS[1])}
        self.assertEqual(fetched_urls, set())
        for row in result["results"]:
            self.assertFalse(row["ok"])
            self.assertIn("blocked", row["reason"])

    def test_fetch_top_pages_handles_failed_fetches(self) -> None:
        def handler(request):
            url = _url_str(request)
            if url.startswith(search._BING_URL):
                html = (
                    '<li class="b_algo"><h2><a href="https://a.example/">A</a></h2><p>a</p></li>'
                    '<li class="b_algo"><h2><a href="https://b.example/">B</a></h2><p>b</p></li>'
                    '<li class="b_algo"><h2><a href="https://c.example/">C</a></h2><p>c</p></li>'
                )
                return httpx.Response(200, text=html)
            if url == search._EXA_URL:
                return httpx.Response(503)
            if url == "https://a.example/":
                return httpx.Response(200, text="<html><body>A body</body></html>")
            if url == "https://b.example/":
                return httpx.Response(404)
            if url == "https://c.example/":
                return httpx.Response(301, headers={"location": "https://c.example/other"})
            return httpx.Response(503)

        result, _ = self._run_fetch(handler)
        self.assertEqual(result["fetched"], 1)
        self.assertEqual(result["total"], 3)
        by_url = {r["url"]: r for r in result["results"]}
        self.assertTrue(by_url["https://a.example/"]["ok"])
        self.assertFalse(by_url["https://b.example/"]["ok"])
        self.assertIn("404", by_url["https://b.example/"]["reason"])
        self.assertFalse(by_url["https://c.example/"]["ok"])
        self.assertIn("redirect", by_url["https://c.example/"]["reason"])

    def test_fetch_top_pages_propagates_search_errors(self) -> None:
        def handler(request):
            return httpx.Response(503)

        result, _ = self._run_fetch(handler, query="cats")
        self.assertIn("error", result)

    def test_fetch_top_pages_validates_inputs_before_network(self) -> None:
        def unexpected(request):
            self.fail("must not hit network on invalid input")

        result, _ = self._run_fetch(unexpected, query="   ")
        self.assertEqual(result["error_code"], "search.empty_query")
        result, _ = self._run_fetch(unexpected, count="nope")
        self.assertEqual(result["error_code"], "search.invalid_count")

    def test_fetch_top_pages_caps_count_at_10(self) -> None:
        # Build 15 result pages so we can verify the cap holds.
        def handler(request):
            url = _url_str(request)
            if url.startswith(search._BING_URL):
                html = "".join(
                    f'<li class="b_algo"><h2><a href="https://p{i}.example/">P{i}</a></h2>'
                    f'<p>S{i}</p></li>'
                    for i in range(15)
                )
                return httpx.Response(200, text=html)
            if url == search._EXA_URL:
                return httpx.Response(503)
            if url.startswith("https://p"):
                return httpx.Response(200, text="<html><body>x</body></html>")
            return httpx.Response(503)

        result, _ = self._run_fetch(handler, count=50)
        self.assertEqual(result["total"], 10)


if __name__ == "__main__":
    unittest.main()
