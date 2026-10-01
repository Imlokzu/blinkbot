"""Operator boundaries and real RPC contracts for the dashboard control pages."""

import asyncio
import json
import unittest
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

import main
import openclaw_control as control


class ControlTests(unittest.TestCase):
    def setUp(self):
        control._cache.clear()
        control._pending.clear()
        self.client = TestClient(main.app, client=("127.0.0.1", 50000), base_url="http://localhost")

    def test_operator_gate_precedes_any_gateway_read_or_write(self):
        # A valid chat login is not permission to schedule work in another
        # account's gateway. Authentication must happen before spawning the CLI.
        with patch.object(main, "_require_user", AsyncMock(return_value="other-user")), \
             patch.dict("os.environ", {"VBOT_OPERATOR_USER_IDS": "owner-user"}), \
             patch.object(control.usage, "_call", AsyncMock()) as call:
            self.assertEqual(self.client.get("/api/openclaw/control/agents").status_code, 403)
            self.assertEqual(self.client.post("/api/openclaw/control/jobs/x/enabled",
                json={"enabled": True, "revision": "r1"}).status_code, 403)
            call.assert_not_awaited()
        with patch.object(main, "_require_user", AsyncMock(return_value="owner-user")), \
             patch.dict("os.environ", {"VBOT_OPERATOR_USER_IDS": "owner-user"}), \
             patch.object(control.usage, "_call", AsyncMock(return_value={"agents": []})):
            self.assertEqual(self.client.get("/api/openclaw/control/agents").status_code, 200)

    def test_auth_disabled_does_not_grant_remote_operator_access(self):
        with patch.object(control.usage, "_call", AsyncMock()) as call:
            remote = TestClient(main.app, client=("203.0.113.10", 50000))
            self.assertEqual(remote.get("/api/openclaw/control/jobs").status_code, 403)
            call.assert_not_awaited()

    def test_auth_disabled_bypass_excludes_proxied_and_foreign_origin_requests(self):
        # Socket loopback also describes a tunnel or reverse proxy. Neither
        # its public Host nor a browser's foreign Origin grants operator access.
        with patch.object(control.usage, "_call", AsyncMock()) as call:
            for headers in [{"host": "dashboard.example.com"},
                            {"origin": "https://example.com"}, {"origin": "null"},
                            {"x-forwarded-for": "203.0.113.10"},
                            {"forwarded": "for=203.0.113.10"}]:
                with self.subTest(headers=headers):
                    self.assertEqual(self.client.get("/api/openclaw/control/agents", headers=headers).status_code, 403)
            call.assert_not_awaited()

    def test_session_projection_omits_text_keys_paths_and_owner_metadata(self):
        raw = {"totalCount": 3, "hasMore": True, "nextOffset": 50, "sessions": [{
            "key": "agent:main:telegram:person@example.com", "sessionId": "private-id",
            "label": "Private conversation", "lastMessagePreview": "private text",
            "path": "/private/transcript", "owner": {"email": "private@example.com"},
            "agentId": "main", "model": "test", "totalTokens": 0,
            "totalTokensFresh": False, "contextTokens": 128000, "hasActiveRun": True,
        }]}
        with patch.object(control.usage, "_call", AsyncMock(return_value=raw)) as call:
            response = self.client.get("/api/openclaw/control/sessions?agent=main")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["cache-control"], "no-store")
        body = response.json()
        for secret in ["person@example.com", "Private conversation", "private text", "private-id", "/private/transcript"]:
            self.assertNotIn(secret, json.dumps(body))
        self.assertTrue(body["sessions"][0]["active"])
        self.assertFalse(body["sessions"][0]["tokens_fresh"])
        self.assertEqual(body["sessions"][0]["tokens"], 0)
        self.assertEqual(body["next_offset"], 50)
        params = call.await_args.args[1]
        self.assertFalse(params["includeLastMessage"])
        self.assertTrue(params["configuredAgentsOnly"])
        self.assertEqual(params["agentId"], "main")

    def test_session_projection_preserves_real_gateway_status_and_source_values(self):
        statuses = ["queued", "running", "done", "failed", "killed", "timeout"]
        raw = {"sessions": [{"key": str(i), "status": status, "classification": "direct",
                             "lastActivityAt": 0, "updatedAt": 100} for i, status in enumerate(statuses)]}
        with patch.object(control.usage, "_call", AsyncMock(return_value=raw)):
            body = self.client.get("/api/openclaw/control/sessions").json()
        self.assertEqual([row["status"] for row in body["sessions"]], statuses)
        self.assertTrue(all(row["source"] == "direct" for row in body["sessions"]))
        self.assertTrue(all(row["updated_at"] == 0 for row in body["sessions"]))

    def test_channel_runtime_is_distinct_from_verified_connectivity(self):
        raw = {"channelLabels": {"telegram": "Telegram"}, "channelAccounts": {"telegram": [{
            "accountId": "person@example.com", "enabled": True, "configured": True,
            "running": True, "tokenSource": "secret", "token": "private-token",
            "lastError": "authentication failed with private-token",
        }]}}
        with patch.object(control.usage, "_call", AsyncMock(return_value=raw)):
            body = self.client.get("/api/openclaw/control/channels").json()
        self.assertTrue(body["channels"][0]["running"])
        self.assertIsNone(body["channels"][0]["connected"])
        self.assertTrue(body["channels"][0]["has_error"])
        self.assertNotIn("private-token", json.dumps(body))
        self.assertNotIn("person@example.com", json.dumps(body))

    def test_failed_and_malformed_rpc_never_look_like_an_empty_inventory(self):
        for raw in [None, {}, [], "private-token", {"agents": {}}]:
            with self.subTest(raw=raw), patch.object(control.usage, "_call", AsyncMock(return_value=raw)):
                response = self.client.get("/api/openclaw/control/agents?refresh=true")
                self.assertEqual(response.status_code, 502)
                self.assertEqual(response.json()["detail"], "gateway_unavailable")
        self.assertFalse(control._cache)

    def test_cli_failures_are_sanitized_and_optional_scheduler_status_can_fail(self):
        with patch.object(control.usage, "_call", AsyncMock(side_effect=OSError("private-path"))):
            response = self.client.get("/api/openclaw/control/agents")
        self.assertEqual(response.status_code, 502)
        self.assertEqual(response.json()["detail"], "gateway_unavailable")

        async def rpc(method, *args, **kwargs):
            if method == "cron.status":
                raise asyncio.TimeoutError("private-error")
            return {"jobs": []}

        with patch.object(control.usage, "_call", AsyncMock(side_effect=rpc)):
            response = self.client.get("/api/openclaw/control/jobs")
        self.assertEqual(response.status_code, 200)
        self.assertIsNone(response.json()["scheduler_enabled"])

    def test_jobs_and_history_hide_commands_payloads_errors_and_results(self):
        raw = {"jobs": [{"id": "test-id", "name": "Nightly", "enabled": True,
            "schedule": {"kind": "stream", "command": ["private-command"]},
            "payload": {"message": "private-prompt"}, "lastRunError": "private-error",
            "state": {"lastRunStatus": "error", "nextRunAtMs": 123}, "configRevision": "r1"}],
            "total": 1}
        async def rpc(method, *args, **kwargs):
            if method == "cron.status":
                return {"enabled": False, "storePath": "/private/path"}
            if method == "cron.runs":
                return {"entries": [{"status": "ok", "durationMs": 0, "summary": "private-output",
                    "error": "private-error", "sessionKey": "private-key"}]}
            return raw
        with patch.object(control.usage, "_call", AsyncMock(side_effect=rpc)) as call:
            body = self.client.get("/api/openclaw/control/jobs").json()
            history = self.client.get("/api/openclaw/control/jobs/test-id/runs").json()
        self.assertFalse(body["scheduler_enabled"])
        self.assertEqual(body["jobs"][0]["schedule"], {"kind": "other"})
        self.assertEqual(body["jobs"][0]["last_status"], "error")
        self.assertEqual(history["runs"][0]["duration_ms"], 0)
        self.assertNotIn("private", json.dumps([body, history]))
        listing = next(c.args[1] for c in call.await_args_list if c.args[0] == "cron.list")
        self.assertFalse(listing["includeDeliveryPreviews"])

    def test_creation_is_paused_isolated_and_has_no_automatic_delivery(self):
        async def rpc(method, params=None, **kwargs):
            return {"agents": [{"id": "main"}]} if method == "agents.list" else {"id": "new-job"}
        with patch.object(control.usage, "_call", AsyncMock(side_effect=rpc)) as call:
            response = self.client.post("/api/openclaw/control/jobs", json={
                "name": " Daily review ", "message": " Review my tasks ", "agent": "main",
                "kind": "daily", "hour": 9, "minute": 30, "timezone": "Europe/Berlin"})
        self.assertEqual(response.status_code, 200)
        method, params = call.await_args.args
        self.assertEqual(method, "cron.add")
        self.assertFalse(params["enabled"])
        self.assertEqual(params["sessionTarget"], "isolated")
        self.assertEqual(params["delivery"], {"mode": "none"})
        self.assertFalse(params["failureAlert"])
        self.assertEqual(params["schedule"], {"kind": "cron", "expr": "30 9 * * *", "tz": "Europe/Berlin"})
        self.assertEqual(params["payload"], {"kind": "agentTurn", "message": "Review my tasks"})
        self.assertNotIn("message", response.text)

    def test_job_agent_filter_reaches_gateway_before_pagination(self):
        # Filtering an already paginated inventory can falsely show no jobs
        # for an agent whose first job is on a later unfiltered page.
        with patch.object(control.usage, "_call", AsyncMock(return_value={"jobs": []})) as call:
            response = self.client.get("/api/openclaw/control/jobs?agent=main&offset=50")
        self.assertEqual(response.status_code, 200)
        listing = next(c.args[1] for c in call.await_args_list if c.args[0] == "cron.list")
        self.assertEqual(listing["agentId"], "main")
        self.assertEqual(listing["offset"], 50)

    def test_explicit_history_refresh_bypasses_the_metadata_cache(self):
        with patch.object(control.usage, "_call", AsyncMock(return_value={"entries": []})) as call:
            self.assertEqual(self.client.get("/api/openclaw/control/jobs/job/runs").status_code, 200)
            self.assertEqual(self.client.get("/api/openclaw/control/jobs/job/runs").status_code, 200)
            self.assertEqual(call.await_count, 1)
            self.assertEqual(self.client.get("/api/openclaw/control/jobs/job/runs?refresh=true").status_code, 200)
            self.assertEqual(call.await_count, 2)

    def test_creation_validation_cannot_inject_config_or_shell_schedules(self):
        base = {"name": "Review", "message": "Tasks", "agent": "main", "kind": "every"}
        with patch.object(control.usage, "_call", AsyncMock(return_value={"agents": [{"id": "main"}]})) as call:
            for changes, status in [({"message": "  "}, 400), ({"agent": "other"}, 400),
                                    ({"timezone": "../etc/passwd"}, 400), ({"kind": "stream"}, 422),
                                    ({"minutes": 0}, 422), ({"payload": {"kind": "command"}}, 422)]:
                with self.subTest(changes=changes):
                    self.assertEqual(self.client.post("/api/openclaw/control/jobs",
                        json={**base, **changes}).status_code, status)
            self.assertTrue(all(c.args[0] == "agents.list" for c in call.await_args_list))

    def test_enable_uses_revision_guard_and_strict_boolean(self):
        with patch.object(control.usage, "_call", AsyncMock(return_value={"id": "job"})) as call:
            response = self.client.post("/api/openclaw/control/jobs/job/enabled",
                json={"enabled": False, "revision": "r1"})
            self.assertEqual(response.status_code, 200)
            call.assert_awaited_once_with("cron.update", {"id": "job", "expectedConfigRevision": "r1",
                "patch": {"enabled": False}}, timeout=20)
            self.assertEqual(self.client.post("/api/openclaw/control/jobs/job/enabled",
                json={"enabled": "false", "revision": "r1"}).status_code, 422)
            self.assertEqual(self.client.post("/api/openclaw/control/jobs/job/enabled",
                json={"enabled": True, "revision": "r" * 129}).status_code, 422)

    def test_mutation_errors_invalidate_cache_and_malformed_results_cannot_report_success(self):
        # The gateway can commit a write before the CLI loses its reply. A
        # failed reply therefore invalidates inventory just like a success.
        for result in [None, {}, {"id": "another-job"}]:
            with self.subTest(result=result), patch.object(control.usage, "_call", AsyncMock(return_value=result)):
                control._cache["jobs:0:"] = (0, {"jobs": []})
                response = self.client.post("/api/openclaw/control/jobs/job/enabled",
                    json={"enabled": True, "revision": "r1"})
                self.assertEqual(response.status_code, 502)
                self.assertFalse(control._cache)

    def test_malformed_metadata_cannot_leak_a_status_payload_or_crash_projection(self):
        async def rpc(method, *args, **kwargs):
            if method == "cron.status":
                return {"enabled": {"token": "private-token"}}
            if method == "cron.list":
                return {"jobs": [{"state": {"lastRunStatus": {}}, "id": "job"}]}
            return {"sessions": [{"key": "opaque", "status": {}, "classification": {},
                                  "totalTokens": 10**300, "contextTokens": float("inf")}]}
        with patch.object(control.usage, "_call", AsyncMock(side_effect=rpc)):
            jobs = self.client.get("/api/openclaw/control/jobs").json()
            sessions = self.client.get("/api/openclaw/control/sessions").json()
        self.assertIsNone(jobs["scheduler_enabled"])
        self.assertIsNone(jobs["jobs"][0]["last_status"])
        self.assertIsNone(sessions["sessions"][0]["status"])
        self.assertIsNone(sessions["sessions"][0]["tokens"])
        self.assertIsNone(sessions["sessions"][0]["context_window"])
        self.assertNotIn("private-token", json.dumps(jobs))

    def test_concurrent_reads_share_one_rpc_and_refresh_bypasses_cache(self):
        async def run():
            async def rpc(*args, **kwargs):
                await asyncio.sleep(.01)
                return {"agents": []}
            with patch.object(control.usage, "_call", AsyncMock(side_effect=rpc)) as call:
                await asyncio.gather(*(control._read("agents", control.agents) for _ in range(4)))
                self.assertEqual(call.await_count, 1)
                await control._read("agents", control.agents)
                self.assertEqual(call.await_count, 1)
                await control._read("agents", control.agents, refresh=True)
                self.assertEqual(call.await_count, 2)
        asyncio.run(run())

    def test_mutation_invalidates_pending_reads_without_dropping_a_newer_request(self):
        async def run():
            old_started, old_finish, new_started, new_finish = [asyncio.Event() for _ in range(4)]

            async def old_load():
                old_started.set()
                await old_finish.wait()
                return {"revision": "old"}

            async def new_load():
                new_started.set()
                await new_finish.wait()
                return {"revision": "new"}

            old_read = asyncio.create_task(control._read("jobs", old_load))
            await old_started.wait()
            control._invalidate()
            new_read = asyncio.create_task(control._read("jobs", new_load))
            await new_started.wait()
            old_finish.set()
            await old_read
            self.assertNotIn("jobs", control._cache)
            self.assertIn("jobs", control._pending)
            new_finish.set()
            await new_read
            self.assertEqual(control._cache["jobs"][1], {"revision": "new"})
            self.assertFalse(control._pending)
        asyncio.run(run())
