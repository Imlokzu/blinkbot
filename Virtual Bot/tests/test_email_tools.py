"""
Unit tests for Agent Email tools and registry integration.
"""

from __future__ import annotations

import unittest
from tools import email_tools
from tools.registry import list_tools, execute_tool


class EmailToolsTests(unittest.IsolatedAsyncioTestCase):
    async def test_get_agent_email_default(self):
        res = await email_tools.get_agent_email("lokzu")
        self.assertEqual(res["status"], "ok")
        self.assertEqual(res["email"], "lokzu@ag.waveio.me")
        self.assertEqual(res["domain"], "ag.waveio.me")

    async def test_get_agent_email_custom_name(self):
        res = await email_tools.get_agent_email("qa_bot_1")
        self.assertEqual(res["status"], "ok")
        self.assertEqual(res["email"], "qa_bot_1@ag.waveio.me")

    async def test_registry_integration(self):
        tools = list_tools()
        names = [t["function"]["name"] for t in tools]
        self.assertIn("get_agent_email", names)
        self.assertIn("check_agent_inbox", names)
        self.assertIn("wait_for_otp_code", names)
        self.assertIn("send_agent_email", names)

        # Test executing tool via registry dispatcher
        res = await execute_tool("get_agent_email", {"agent_name": "lokzu"})
        self.assertEqual(res["status"], "ok")
        self.assertEqual(res["email"], "lokzu@ag.waveio.me")



def _mock_client(handler):
    """An AsyncClient factory whose requests go to `handler` instead of the network."""
    real = email_tools.httpx.AsyncClient

    def factory(*args, **kwargs):
        kwargs["transport"] = email_tools.httpx.MockTransport(handler)
        return real(*args, **kwargs)

    return factory


class AgentMailboxTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        from unittest import mock

        self.env = mock.patch.dict(
            "os.environ", {"AGENT_MAIL_API_URL": "https://mail.test", "AGENT_MAIL_API_KEY": "k"}
        )
        self.env.start()
        self.addCleanup(self.env.stop)

    def _patch(self, handler):
        from unittest import mock

        patcher = mock.patch.object(email_tools.httpx, "AsyncClient", _mock_client(handler))
        patcher.start()
        self.addCleanup(patcher.stop)

    async def test_check_inbox_returns_summaries(self):
        seen = {}

        def handler(request):
            seen["params"] = dict(request.url.params)
            return email_tools.httpx.Response(200, json={
                "total": 4, "unread": 1,
                "messages": [{"id": "m1", "seen": False, "from": "Olena <o@example.com>", "subject": "Invoice",
                              "snippet": "Hi", "attachments": [{"filename": "invoice.pdf"}]}],
            })

        self._patch(handler)
        res = await email_tools.check_agent_inbox("vault", unread_only=True, limit=5)
        self.assertEqual(seen["params"], {"to": "vault@ag.waveio.me", "limit": "5", "unread": "1"})
        self.assertEqual(res["total"], 4)
        self.assertEqual(res["messages"][0]["unread"], True)
        self.assertEqual(res["messages"][0]["attachments"], ["invoice.pdf"])

    async def test_read_email_returns_whole_message(self):
        def handler(request):
            self.assertEqual(request.url.path, "/api/message")
            self.assertEqual(request.url.params["id"], "m1")
            return email_tools.httpx.Response(200, json={
                "id": "m1", "from": "Olena <o@example.com>", "subject": "Invoice",
                "recipients": {"to": [{"address": "lokzu@ag.waveio.me"}], "cc": [{"address": "b@example.com"}]},
                "text": "  Hi! The invoice is attached.  " + "x" * 1000,
                "links": [{"url": "https://example.com/i/42", "text": "Open"}],
                "attachments": [{"filename": "invoice.pdf", "mime_type": "application/pdf", "size": 10}],
            })

        self._patch(handler)
        res = await email_tools.read_agent_email("m1", max_chars=500)
        self.assertEqual(res["status"], "ok")
        self.assertEqual(res["cc"], ["b@example.com"])
        self.assertTrue(res["text"].startswith("Hi! The invoice"))
        self.assertEqual(len(res["text"]), 500)
        self.assertTrue(res["truncated"])
        self.assertEqual(res["attachments"][0]["filename"], "invoice.pdf")

    async def test_read_email_old_record_falls_back_to_body(self):
        self._patch(lambda r: email_tools.httpx.Response(200, json={"id": "old", "body": "legacy text"}))
        res = await email_tools.read_agent_email("old")
        self.assertEqual(res["text"], "legacy text")

    async def test_wait_for_otp_accepts_link_only_mail(self):
        from datetime import datetime, timezone

        now = datetime.now(timezone.utc).isoformat()
        self._patch(lambda r: email_tools.httpx.Response(200, json={"otp": {
            "code": None, "link": "https://vault.bitwarden.com/#/finish-signup?token=t",
            "subject": "Verify Your Email", "from": "Bitwarden <no-reply@bitwarden.com>", "received_at": now,
        }}))
        res = await email_tools.wait_for_otp_code("vault", service="bitwarden", max_wait_seconds=5)
        self.assertTrue(res["found"])
        self.assertEqual(res["verification_link"], "https://vault.bitwarden.com/#/finish-signup?token=t")

    def test_stale_otp_is_not_fresh(self):
        from datetime import datetime, timedelta, timezone

        not_before = datetime.now(timezone.utc) - timedelta(seconds=email_tools.OTP_FRESHNESS_SECONDS)
        self.assertFalse(email_tools._is_fresh({"received_at": "2026-01-01T00:00:00Z"}, not_before))
        recent = (datetime.now(timezone.utc) - timedelta(seconds=30)).isoformat().replace("+00:00", "Z")
        self.assertTrue(email_tools._is_fresh({"received_at": recent}, not_before))
        self.assertTrue(email_tools._is_fresh({}, not_before), "no timestamp: do not hide the code")

    async def test_registry_lists_read_tool(self):
        names = [t["function"]["name"] for t in list_tools()]
        self.assertIn("read_agent_email", names)
