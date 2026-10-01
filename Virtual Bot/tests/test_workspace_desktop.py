"""Finder must only receive paths belonging to the authenticated workspace."""

from unittest.mock import patch

from fastapi.testclient import TestClient
import pytest

import main
import workspace
import workspace_desktop


@pytest.fixture
def desktop_client(tmp_path, monkeypatch):
    monkeypatch.setattr(workspace, "WORKSPACE_DIR", tmp_path / "workspace")
    with workspace.set_session("finder-test"):
        workspace.write_file("session/plan.md", "# Plan\n")
    return TestClient(main.app)


def test_reveals_session_file_without_shell(desktop_client):
    with patch.object(workspace_desktop, "can_reveal", return_value=True), \
         patch.object(workspace_desktop.subprocess, "run") as run:
        response = desktop_client.post("/api/workspace/reveal", json={
            "path": "session/plan.md", "session_id": "finder-test",
        })
    assert response.status_code == 200
    assert response.json()["path"] == "sessions/finder-test/plan.md"
    assert run.call_args.args[0] == [
        "open", "-R", str(workspace.root() / "sessions/finder-test/plan.md"),
    ]
    assert "shell" not in run.call_args.kwargs


def test_rejects_traversal_and_external_symlinks(desktop_client, tmp_path):
    outside = tmp_path / "outside.md"
    outside.write_text("private", encoding="utf-8")
    (workspace.root() / "escape.md").symlink_to(outside)
    with patch.object(workspace_desktop.subprocess, "run") as run:
        for path in ("../outside.md", "session/../../outside.md", "escape.md"):
            response = desktop_client.post("/api/workspace/reveal", json={"path": path})
            assert response.status_code == 400
        run.assert_not_called()


def test_missing_file_and_other_platform_do_not_launch_finder(desktop_client):
    with patch.object(workspace_desktop.subprocess, "run") as run:
        assert desktop_client.post("/api/workspace/reveal", json={"path": "missing.md"}).status_code == 404
        with patch.object(workspace_desktop, "can_reveal", return_value=False):
            response = desktop_client.post("/api/workspace/reveal", json={
                "path": "session/plan.md", "session_id": "finder-test",
            })
            assert response.status_code == 501
        run.assert_not_called()


def test_finder_failure_is_reported(desktop_client):
    with patch.object(workspace_desktop, "can_reveal", return_value=True), \
         patch.object(workspace_desktop.subprocess, "run", side_effect=OSError("unavailable")):
        response = desktop_client.post("/api/workspace/reveal", json={
            "path": "session/plan.md", "session_id": "finder-test",
        })
    assert response.status_code == 500
    assert response.json()["detail"] == "workspace.finderFailed"
