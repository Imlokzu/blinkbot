from __future__ import annotations

import asyncio
import json
import os
import unittest
from unittest.mock import patch

import httpx

from tools import search


_REAL_ASYNC_CLIENT = httpx.AsyncClient
_HTML = """
<div class="result">
  <a rel="nofollow" class="result__a" href="https://openai.com/codex/">OpenAI Codex</a>
  <a class="result__snippet">Coding agent from OpenAI.</a>
</div>
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


class WebSearchTests(unittest.TestCase):
    def setUp(self) -> None:
        # Never use an owner's paid key or a cooldown left by another test.
        self.environment = patch.dict(os.environ, {"EXA_API_KEY": ""})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        search._COOLDOWNS.clear()
        search._LIMITED.clear()
        self.addCleanup(search._COOLDOWNS.clear)
        self.addCleanup(search._LIMITED.clear)

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

    @staticmethod
    def _ddg_only(request):
        if str(request.url) == search._EXA_URL:
            return httpx.Response(503)
        return httpx.Response(200, text=_HTML)

    def test_exa_is_primary_and_uses_current_hosted_mcp_schema(self) -> None:
        result, requests = self._run(lambda request: httpx.Response(200, json=_exa_message()))

        self.assertEqual(len(requests), 1)
        self.assertEqual(str(requests[0].url), search._EXA_URL)
        self.assertNotIn("x-api-key", requests[0].headers)
        self.assertEqual(json.loads(requests[0].content), {
            "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": "web_search_exa", "arguments": {
                "query": "OpenAI Codex", "objective": "OpenAI Codex", "numResults": 3,
            }},
        })
        self.assertEqual(result, {
            "query": "OpenAI Codex", "provider": "exa",
            "results": [
                {"title": "OpenAI Codex", "url": "https://openai.com/codex/",
                 "snippet": "Coding agent from OpenAI."},
                {"title": "Python documentation", "url": "https://docs.python.org/3/",
                 "snippet": "Official Python reference."},
            ],
        })

    def test_exa_key_is_header_only_and_never_sent_to_fallback(self) -> None:
        with patch.dict(os.environ, {"EXA_API_KEY": "test-exa-key"}):
            result, requests = self._run(self._ddg_only)

        self.assertEqual(result["provider"], "duckduckgo")
        self.assertEqual(requests[0].headers["x-api-key"], "test-exa-key")
        for request in requests:
            self.assertNotIn("test-exa-key", str(request.url))
            self.assertNotIn("test-exa-key", request.content.decode())
        self.assertNotIn("x-api-key", requests[1].headers)

    def test_exa_redirect_does_not_forward_api_key_to_another_host(self) -> None:
        def handler(request):
            if str(request.url) == search._EXA_URL:
                return httpx.Response(302, headers={"location": "https://other.example/search"})
            if str(request.url) == search._DDG_URLS[0]:
                return httpx.Response(200, text=_HTML)
            self.fail("An Exa redirect must not receive the API-key header")

        with patch.dict(os.environ, {"EXA_API_KEY": "test-exa-key"}):
            result, requests = self._run(handler)

        self.assertEqual(result["provider"], "duckduckgo")
        self.assertEqual([str(request.url) for request in requests],
                         [search._EXA_URL, search._DDG_URLS[0]])
        self.assertEqual(requests[0].headers["x-api-key"], "test-exa-key")
        self.assertNotIn("x-api-key", requests[1].headers)

    def test_exa_accepts_sse_with_adjacent_results_and_highlights(self) -> None:
        # Exa's text does not promise blank lines or separators between hits.
        payload = (
            'event: endpoint\ndata: {"jsonrpc":"2.0","method":"notifications/ping"}\n\n'
            "event: message\ndata: " + json.dumps(_exa_message()) + "\n\n"
            "data: [DONE]\n\n"
        )
        result, requests = self._run(lambda request: httpx.Response(
            200, text=payload, headers={"content-type": "text/event-stream"}
        ))

        self.assertEqual(result["provider"], "exa")
        self.assertEqual(len(requests), 1)
        self.assertEqual([hit["title"] for hit in result["results"]],
                         ["OpenAI Codex", "Python documentation"])
        self.assertEqual(result["results"][0]["snippet"], "Coding agent from OpenAI.")
        self.assertEqual(result["results"][1]["snippet"], "Official Python reference.")

    def test_exa_accepts_text_snippets_without_highlights(self) -> None:
        result, requests = self._run(lambda request: httpx.Response(
            200, json=_exa_message(_EXA_TEXT.replace("Highlights:", "Text:"))
        ))

        self.assertEqual(result["provider"], "exa")
        self.assertEqual(len(requests), 1)
        self.assertEqual(result["results"][0]["snippet"], "Coding agent from OpenAI.")

    def test_query_is_trimmed_and_objective_respects_provider_limit(self) -> None:
        query = "x" * 5000
        result, requests = self._run(
            lambda request: httpx.Response(200, json=_exa_message()), "  " + query + "  "
        )
        arguments = json.loads(requests[0].content)["params"]["arguments"]

        self.assertEqual(result["query"], query)
        self.assertEqual(arguments["query"], query)
        self.assertEqual(arguments["objective"], query[:4096])

    def test_result_count_is_clamped_and_limits_returned_hits(self) -> None:
        text = "".join(
            f"Title: Result {i}\nURL: https://example.com/{i}\nHighlights:\nSnippet {i}\n"
            for i in range(7)
        )
        for count, expected in ((-5, 1), (0, 1), (1, 1), (3, 3), (99, 5)):
            with self.subTest(count=count):
                result, requests = self._run(
                    lambda request: httpx.Response(200, json=_exa_message(text)), count=count
                )
                arguments = json.loads(requests[0].content)["params"]["arguments"]
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

        self.assertEqual([str(request.url) for request in requests],
                         [search._EXA_URL, search._DDG_URLS[0]])
        self.assertEqual(result["provider"], "duckduckgo")
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
                    url = str(request.url)
                    if url == search._EXA_URL:
                        return httpx.Response(503)
                    if url == search._DDG_URLS[0]:
                        if failure == "network":
                            raise httpx.ConnectError("Mock failure", request=request)
                        return httpx.Response(500)
                    return httpx.Response(200, text=lite_html)

                result, requests = self._run(handler)
                self.assertEqual([str(request.url) for request in requests],
                                 [search._EXA_URL, *search._DDG_URLS])
                self.assertEqual(result["provider"], "duckduckgo")
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
            return httpx.Response(503) if str(request.url) == search._EXA_URL else httpx.Response(200, text=html)

        result, _ = self._run(handler)
        self.assertEqual(result["results"][0], {
            "title": "Cars & parts", "url": "https://example.com/?x=1&y=2",
            "snippet": "A <useful> reference.",
        })

    def test_duckduckgo_search_results_about_captcha_are_not_challenges(self) -> None:
        # Search subjects can mention these words without blocking the provider.
        html = _HTML.replace("OpenAI Codex", "CAPTCHA reference").replace(
            "Coding agent from OpenAI.",
            "Bots use DuckDuckGo; challenge-form and anomaly.js explained.",
        )
        result, requests = self._run(lambda request: (
            httpx.Response(503) if str(request.url) == search._EXA_URL
            else httpx.Response(200, text=html)
        ))

        self.assertEqual(result["provider"], "duckduckgo")
        self.assertEqual(result["results"][0]["title"], "CAPTCHA reference")
        self.assertEqual(len(requests), 2)
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
                result, requests = self._run(
                    lambda request: response if str(request.url) == search._EXA_URL else httpx.Response(200, text=_HTML)
                )
                self.assertEqual(result["provider"], "duckduckgo")
                self.assertEqual(len(requests), 2)

    def test_exa_rate_limit_cools_down_then_recovers(self) -> None:
        def limited_exa(request):
            if str(request.url) == search._EXA_URL:
                return httpx.Response(429, headers={"retry-after": "120"})
            return httpx.Response(200, text=_HTML)

        with patch.object(search, "monotonic", return_value=1000.0):
            result, first_requests = self._run(limited_exa)
            result_again, second_requests = self._run(limited_exa)

        self.assertEqual(result["provider"], "duckduckgo")
        self.assertEqual(result_again["provider"], "duckduckgo")
        self.assertEqual(str(first_requests[0].url), search._EXA_URL)
        self.assertTrue(all(str(request.url) != search._EXA_URL for request in second_requests))
        self.assertGreaterEqual(search._COOLDOWNS["exa"], 1120.0)
        deadline = search._COOLDOWNS["exa"]
        with patch.object(search, "monotonic", return_value=deadline + 1):
            recovered, requests = self._run(lambda request: httpx.Response(200, json=_exa_message()))

        self.assertEqual(recovered["provider"], "exa")
        self.assertEqual(len(requests), 1)
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
                    result, requests = self._run(lambda request: (
                        httpx.Response(200, json=payload)
                        if str(request.url) == search._EXA_URL else httpx.Response(503)
                    ))

                self.assertEqual(result["error_code"], "search.rate_limited")
                self.assertGreaterEqual(search._COOLDOWNS["exa"], 1060.0)
                self.assertIn("exa", search._LIMITED)
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
                    return httpx.Response(503) if str(request.url) == search._EXA_URL else response

                with patch.object(search, "monotonic", return_value=1000.0):
                    result, first_requests = self._run(handler)
                    result_again, second_requests = self._run(handler)
                self.assertEqual(result["error_code"], "search.rate_limited")
                self.assertEqual(result_again["error_code"], "search.rate_limited")
                self.assertEqual(len(first_requests), 2)
                self.assertEqual(second_requests, [])

                deadline = max(search._COOLDOWNS.values())
                with patch.object(search, "monotonic", return_value=deadline + 1):
                    recovered, _ = self._run(self._ddg_only)
                self.assertEqual(recovered["provider"], "duckduckgo")
                self.assertNotIn("duckduckgo", search._COOLDOWNS)
                self.assertNotIn("duckduckgo", search._LIMITED)

    def test_empty_successful_results_have_distinct_error(self) -> None:
        def handler(request):
            if str(request.url) == search._EXA_URL:
                return httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "result": {"content": []}})
            return httpx.Response(200, text="<html>No matching pages</html>")

        result, requests = self._run(handler)
        self.assertEqual(result["error_code"], "search.no_results")
        self.assertEqual(len(requests), 3)

    def test_complete_provider_chain_has_a_bounded_timeout(self) -> None:
        self.assertLessEqual(search._SEARCH_TIMEOUT, 25.0)
        cancelled = []

        async def stalled_provider(request):
            try:
                await asyncio.sleep(10)
            finally:
                cancelled.append(True)
            return httpx.Response(200, json=_exa_message())

        with patch.object(search, "_SEARCH_TIMEOUT", 0.02):
            result, requests = self._run(stalled_provider)
        self.assertEqual(result["error_code"], "search.unavailable")
        self.assertEqual(cancelled, [True])
        self.assertEqual(len(requests), 1)

    def test_stalled_provider_is_cancelled_and_cooled_down_before_fallback(self) -> None:
        cancelled = []

        async def handler(request):
            if str(request.url) == search._EXA_URL:
                try:
                    await asyncio.sleep(10)
                finally:
                    cancelled.append(True)
            return httpx.Response(200, text=_HTML)

        with patch.object(search, "_PROVIDER_TIMEOUT", 0.02):
            first, first_requests = self._run(handler)
            second, second_requests = self._run(handler)

        self.assertEqual(first["provider"], "duckduckgo")
        self.assertEqual(second["provider"], "duckduckgo")
        self.assertEqual(cancelled, [True])
        self.assertEqual([str(request.url) for request in first_requests],
                         [search._EXA_URL, search._DDG_URLS[0]])
        self.assertEqual([str(request.url) for request in second_requests],
                         [search._DDG_URLS[0]])
        self.assertIn("exa", search._COOLDOWNS)
        self.assertNotIn("exa", search._LIMITED)


if __name__ == "__main__":
    unittest.main()
