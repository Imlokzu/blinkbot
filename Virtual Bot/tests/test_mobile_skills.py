"""Functional catalog coverage and separate ordinary review regressions.

All skill lists, CLI output and credentials below are test fixtures. No test
starts an agent turn, reads installed skill files, or changes gateway config.
"""

from __future__ import annotations

import asyncio
import json
from threading import Event, get_ident
from unittest.mock import Mock

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import httpx
import pytest

import mobile_skills
from mobile_skills import router
from openclaw_store import OpenClawStoreError


@pytest.fixture(autouse=True)
def fixture_only_cli(monkeypatch):
    def unexpected_cli(*_args, **_kwargs):
        pytest.fail("A catalog test attempted to run the real OpenClaw CLI")

    monkeypatch.setattr(mobile_skills, "_run", unexpected_cli)
    monkeypatch.setattr(mobile_skills, "_agent_args", lambda: [])


async def owner(request: Request) -> str:
    if "x-owner" not in request.headers:
        raise HTTPException(401, {"code": "fixture_auth_required"})
    # The empty string preserves the existing authenticated local owner.
    return request.headers["x-owner"]


def make_app(reader=None, *, require_user=owner) -> FastAPI:
    app = FastAPI()
    app.include_router(router(require_user, list_skills=reader))
    return app


def ready(name="weather", **metadata):
    return {"name": name, "description": "Forecasts from installed tools.",
            "eligible": True, "disabled": False, **metadata}


class TestCatalogApi:
    def test_catalog_preserves_installed_metadata_and_exact_reference(self):
        reader = Mock(return_value=[
            ready("zeta", disabled=True),
            ready("Release-Notes", description="Write release notes for this project.",
                  source="openclaw-workspace", bundled=False, emoji="📝",
                  homepage="https://example.invalid/release-notes", userInvocable=True,
                  modelVisible=True, commandVisible=True),
            ready("alpha", eligible=False, bundled=True,
                  missing={"bins": ["helper"], "env": ["HELPER_KEY"], "config": []}),
        ])
        with TestClient(make_app(reader)) as client:
            response = client.get("/api/mobile/skills", headers={"x-owner": "alice"})

        assert response.status_code == 200
        assert response.headers["cache-control"] == "no-store"
        rows = response.json()["skills"]
        assert [row["name"] for row in rows] == ["alpha", "Release-Notes", "zeta"]
        assert rows[1] == {
            "name": "Release-Notes", "description": "Write release notes for this project.",
            "emoji": "📝", "source": "openclaw-workspace", "bundled": False,
            "enabled": True, "eligible": True, "missing": [],
            "homepage": "https://example.invalid/release-notes", "user_invocable": True,
            "blocked_by_allowlist": False, "blocked_by_agent_filter": False,
            "model_visible": True, "command_visible": True, "selectable": True,
            "invocation": "$release-notes",
        }
        assert rows[0]["missing"] == ["bins:helper", "env:HELPER_KEY"]
        assert rows[0]["selectable"] is False and rows[0]["invocation"] == ""
        assert rows[2]["enabled"] is False and rows[2]["invocation"] == ""
        reader.assert_called_once_with()

    def test_authentication_is_required_before_discovery(self):
        reader = Mock(return_value=[ready()])
        with TestClient(make_app(reader)) as client:
            response = client.get("/api/mobile/skills")
        assert response.status_code == 401
        assert response.json() == {"detail": {"code": "fixture_auth_required"}}
        reader.assert_not_called()

    @pytest.mark.parametrize("user_id", ["", "alice"])
    def test_parent_authentication_owns_the_original_identity(self, user_id):
        authenticated = []

        async def authenticate(request: Request):
            authenticated.append(request.headers["authorization"])
            return user_id

        reader = Mock(return_value=[ready()])
        with TestClient(make_app(reader, require_user=authenticate)) as client:
            response = client.get("/api/mobile/skills", headers={"authorization": "Bearer fixture"})
        assert response.status_code == 200
        assert authenticated == ["Bearer fixture"]
        assert response.json()["skills"][0]["invocation"] == "$weather"
        reader.assert_called_once_with()

    def test_empty_installation_is_an_empty_catalog(self):
        with TestClient(make_app(lambda: [])) as client:
            response = client.get("/api/mobile/skills", headers={"x-owner": ""})
        assert response.status_code == 200
        assert response.json() == {"skills": []}

    def test_each_read_sees_current_installed_status(self):
        reader = Mock(side_effect=[[ready()], [ready(disabled=True)]])
        with TestClient(make_app(reader)) as client:
            first = client.get("/api/mobile/skills", headers={"x-owner": "alice"}).json()
            second = client.get("/api/mobile/skills", headers={"x-owner": "alice"}).json()
        assert first["skills"][0]["selectable"] is True
        assert second["skills"][0]["enabled"] is False
        assert second["skills"][0]["invocation"] == ""
        assert reader.call_count == 2

    def test_default_reader_reuses_the_web_cli_and_agent_selection(self, monkeypatch):
        run = Mock(return_value=json.dumps({"skills": [ready()]}))
        monkeypatch.setattr(mobile_skills, "_run", run)
        monkeypatch.setattr(mobile_skills, "_agent_args", lambda: ["--agent", "main"])
        with TestClient(make_app()) as client:
            response = client.get("/api/mobile/skills", headers={"x-owner": "alice"})
        assert response.status_code == 200
        assert response.json()["skills"][0]["invocation"] == "$weather"
        run.assert_called_once_with(["skills", "list", "--json", "--agent", "main"], timeout=30)

    def test_default_reader_accepts_the_existing_bare_list_format(self, monkeypatch):
        monkeypatch.setattr(mobile_skills, "_run", lambda *_args, **_kwargs: json.dumps([ready()]))
        with TestClient(make_app()) as client:
            response = client.get("/api/mobile/skills", headers={"x-owner": "alice"})
        assert response.status_code == 200
        assert response.json()["skills"][0]["eligible"] is True

    def test_only_a_catalog_get_is_registered(self):
        app = make_app(lambda: [])
        assert set(app.openapi()["paths"]) == {"/api/mobile/skills"}
        assert set(app.openapi()["paths"]["/api/mobile/skills"]) == {"get"}
        with TestClient(app) as client:
            for method in ("post", "put", "patch", "delete"):
                assert client.request(method, "/api/mobile/skills",
                                      headers={"x-owner": "alice"}).status_code == 405


class TestCatalogReviewRegressions:
    """Review ordinary list/visibility/error behavior independently of happy paths."""

    @pytest.mark.parametrize("metadata", [
        {"disabled": True}, {"eligible": False}, {"userInvocable": False},
        {"blockedByAllowlist": True}, {"blockedByAgentFilter": True},
        {"modelVisible": False}, {"commandVisible": False},
    ])
    def test_unavailable_rows_remain_readable_without_an_invocation(self, metadata):
        # Eligibility alone does not include the configured agent's allowlist.
        reader = lambda: [ready(**metadata)]
        with TestClient(make_app(reader)) as client:
            response = client.get("/api/mobile/skills", headers={"x-owner": "alice"})
        assert response.status_code == 200
        row = response.json()["skills"][0]
        assert row["name"] == "weather" and row["description"]
        assert row["selectable"] is False and row["invocation"] == ""

    def test_older_cli_defaults_follow_existing_skill_frontmatter_defaults(self):
        with TestClient(make_app(lambda: [ready("release_notes")])) as client:
            row = client.get("/api/mobile/skills", headers={"x-owner": ""}).json()["skills"][0]
        assert row["user_invocable"] is True
        assert row["model_visible"] is True and row["command_visible"] is True
        assert row["invocation"] == "$release_notes"

    @pytest.mark.parametrize("name,reference", [
        ("PDF", "$pdf"), ("screen-app-native", "$screen-app-native"),
        ("expo:expo-native-ui", "$expo:expo-native-ui"), ("12weather", "$12weather"),
    ])
    def test_reference_preserves_declared_name_without_truncating_or_renaming(self, name, reference):
        with TestClient(make_app(lambda: [ready(name)])) as client:
            row = client.get("/api/mobile/skills", headers={"x-owner": "alice"}).json()["skills"][0]
        assert row["name"] == name
        assert row["selectable"] is True and row["invocation"] == reference

    @pytest.mark.parametrize("name", ["123", "release.notes", "release notes", "summary:"])
    def test_nonreference_names_are_listed_without_guessing_a_command(self, name):
        # The catalog has no stable command identity for these declared names.
        with TestClient(make_app(lambda: [ready(name)])) as client:
            row = client.get("/api/mobile/skills", headers={"x-owner": "alice"}).json()["skills"][0]
        assert row["name"] == name and row["eligible"] is True
        assert row["selectable"] is False and row["invocation"] == ""

    @pytest.mark.parametrize("names", [
        ["release-notes", "release_notes"], ["Weather", "weather"],
        ["release.notes", "release_notes"],
    ])
    def test_ambiguous_reference_aliases_never_select_another_skill(self, names):
        with TestClient(make_app(lambda: [ready(name) for name in names])) as client:
            rows = client.get("/api/mobile/skills", headers={"x-owner": "alice"}).json()["skills"]
        assert len(rows) == len(names)
        assert all(row["invocation"] == "" and not row["selectable"] for row in rows)

    @pytest.mark.parametrize("metadata", [
        {"eligible": "false"}, {"disabled": "false"}, {"userInvocable": "true"},
        {"blockedByAgentFilter": "false"}, {"modelVisible": "true"},
    ])
    def test_nonboolean_availability_does_not_enable_selection(self, metadata):
        with TestClient(make_app(lambda: [ready(**metadata)])) as client:
            row = client.get("/api/mobile/skills", headers={"x-owner": "alice"}).json()["skills"][0]
        assert row["selectable"] is False and row["invocation"] == ""

    @pytest.mark.parametrize("code,status", [("timeout", 504), ("not_found", 502), ("command_failed", 502)])
    def test_cli_failures_return_a_localizable_code_without_diagnostics(self, code, status):
        reader = Mock(side_effect=OpenClawStoreError("private CLI diagnostic", code=code,
                                                   output="private CLI output"))
        with TestClient(make_app(reader)) as client:
            response = client.get("/api/mobile/skills", headers={"x-owner": "alice"})
        assert response.status_code == status
        assert response.headers["cache-control"] == "no-store"
        assert response.json() == {"detail": {"code": "skills_catalog_unavailable"}}

    @pytest.mark.parametrize("payload", [{}, {"skills": {}}, {"skills": None}, "not JSON"])
    def test_invalid_cli_catalog_is_an_error_instead_of_a_successful_empty_list(self, monkeypatch, payload):
        monkeypatch.setattr(mobile_skills, "_run", lambda *_args, **_kwargs: json.dumps(payload))
        with TestClient(make_app()) as client:
            response = client.get("/api/mobile/skills", headers={"x-owner": "alice"})
        assert response.status_code == 502
        assert response.json() == {"detail": {"code": "skills_catalog_unavailable"}}

    def test_only_public_metadata_is_projected_without_modifying_the_source(self):
        items = [ready(filePath="/fixture/private/SKILL.md", baseDir="/fixture/private",
                       primaryEnv="FIXTURE_API_KEY", install=[{"command": "fixture command"}]),
                 None, {}, {"name": 123}, "not a row"]
        before = json.dumps(items, sort_keys=True)
        with TestClient(make_app(lambda: items)) as client:
            response = client.get("/api/mobile/skills", headers={"x-owner": "alice"})
        assert response.status_code == 200
        row = response.json()["skills"][0]
        assert len(response.json()["skills"]) == 1
        assert not {"filePath", "baseDir", "primaryEnv", "install"}.intersection(row)
        assert json.dumps(items, sort_keys=True) == before

    def test_discovery_runs_off_the_request_event_loop(self):
        async def scenario():
            entered, release = Event(), Event()
            loop_thread = get_ident()
            reader_threads = []

            def reader():
                reader_threads.append(get_ident())
                entered.set()
                assert release.wait(2), "test did not release the fixture reader"
                return [ready()]

            app = make_app(reader)

            @app.get("/heartbeat")
            async def heartbeat():
                return {"ok": True}

            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://fixture") as client:
                request = asyncio.create_task(client.get("/api/mobile/skills", headers={"x-owner": "alice"}))
                try:
                    assert await asyncio.to_thread(entered.wait, 1)
                    response = await asyncio.wait_for(client.get("/heartbeat"), timeout=1)
                    assert response.json() == {"ok": True}
                finally:
                    release.set()
                    result = await request
                assert result.status_code == 200
            assert reader_threads and reader_threads[0] != loop_thread

        asyncio.run(scenario())
