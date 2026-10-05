"""Synthetic-only operator reconciliation: no provider or real config writes."""

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import subprocess

import pytest

from model_vision import (
    VisionPlanError, collect_evidence, merge_model_rows, native_image_support,
    plan_reconciliation, public_plan, public_provider_snapshot,
)


SCRIPT = Path(__file__).parents[1] / "scripts/reconcile_model_vision.py"
spec = importlib.util.spec_from_file_location("reconcile_model_vision", SCRIPT)
operator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(operator)


@pytest.fixture
def inputs():
    config = {
        "models": {"providers": {
            "openai": {"api": "openai-chatgpt-responses", "models": [
                {"id": "gpt-6-luna", "contextWindow": 872000, "maxTokens": 128000,
                 "reasoning": True, "api": "openai-chatgpt-responses",
                 "compat": {"codeMode": "preferred"},
                 "cost": {"input": 1, "output": 2, "cacheRead": 0.1, "cacheWrite": 0}},
                {"id": "gpt-6-sol", "input": ["text", "image"]},
            ]},
            "regolo": {"models": [
                {"id": "gpt-oss-120b", "input": ["text"]},
                {"id": "qwen3.5-122b", "input": ["text", "image"]},
            ]},
        }},
        "agents": {"defaults": {"model": {"primary": "openai/gpt-6-sol"}}},
        "gateway": {"auth": {"token": "synthetic-private-value"}},
    }
    ids = ["gpt-6-luna", "gpt-6-sol", "gpt-6-astra", "gpt-6.1-sol"]
    catalog = {"models": [{"id": "openai/" + mid, "available": True, "input": "-"} for mid in ids]}
    catalog["models"].append({"id": "regolo/gpt-oss-120b", "available": True})
    cache = {"models": [
        {"slug": "gpt-6-luna", "input_modalities": ["text", "image"],
         "instructions": "synthetic-private-prompt"},
        {"slug": "gpt-6-sol", "inputModalities": ["text", "image"]},
    ]}
    manifest = {"modelCatalog": {"providers": {"openai": {"models": [
        {"id": "gpt-6-astra", "input": ["text", "image"]},
    ]}}}}
    runtime = {"models": [
        {"id": "openai/gpt-6-astra", "input": ["text", "image"]},
        {"id": "openai/gpt-6-luna", "input": ["text"], "api": "openai-chatgpt-responses"},
    ]}
    return config, catalog, cache, manifest, runtime


def make_plan(inputs):
    return plan_reconciliation(*inputs[:4], runtime_models=inputs[4])


def decision(plan, model_id):
    return next(row for row in plan["decisions"] if row["model_id"] == "openai/" + model_id)


@pytest.mark.parametrize(("value", "expected"), [
    (["text", "image"], True), (["image"], True), (["text"], False),
    (None, None), ([], None), ("-", None), ("text+image", None),
    (["text", True], None), (["text", "imaginary"], None),
])
def test_native_support_does_not_guess_from_picker_metadata(value, expected):
    assert native_image_support(value) is expected


def test_only_luna_is_repaired_without_changing_any_other_field(inputs):
    original = deepcopy(inputs)
    plan = make_plan(inputs)
    assert plan["patch_models"] == [{"id": "gpt-6-luna", "input": ["text", "image"]}]
    assert decision(plan, "gpt-6-astra")["reason"] == "runtime_already_accepts_images"
    assert decision(plan, "gpt-6.1-sol")["status"] == "manual_diagnosis"
    assert decision(plan, "gpt-6-sol")["status"] == "unchanged"
    config = inputs[0]
    authored = config["models"]["providers"]["openai"]["models"]
    merged = merge_model_rows(authored, plan["patch_models"])
    expected = deepcopy(authored)
    expected[0]["input"] = ["text", "image"]
    assert merged == expected
    assert inputs == original
    assert "synthetic-private" not in json.dumps(public_plan(plan))


@pytest.mark.parametrize("explicit", [["text"], ["text", "image"], [], None])
def test_explicit_authored_input_is_never_overwritten(inputs, explicit):
    inputs[0]["models"]["providers"]["openai"]["models"][0]["input"] = explicit
    assert make_plan(inputs)["patch_models"] == []


def test_missing_runtime_snapshot_does_not_make_catalog_a_runtime_claim(inputs):
    plan = plan_reconciliation(*inputs[:4])
    assert decision(plan, "gpt-6-astra")["status"] == "manual_diagnosis"
    assert plan["patch_models"] == [{"id": "gpt-6-luna", "input": ["text", "image"]}]


@pytest.mark.parametrize("runtime_input", [["text"], ["text", "image"], None])
def test_discovered_rows_are_never_authored_even_with_complete_metadata(inputs, runtime_input):
    row = inputs[4]["models"][0]
    row.update(deepcopy(inputs[0]["models"]["providers"]["openai"]["models"][0]))
    row.update(id="openai/gpt-6-astra", input=runtime_input)
    assert all(row["id"] != "gpt-6-astra" for row in make_plan(inputs)["patch_models"])


def test_no_version_family_capability_inference(inputs):
    inputs[0]["models"]["providers"]["openai"]["models"].append({"id": "gpt-6.1-sol"})
    plan = make_plan(inputs)
    assert decision(plan, "gpt-6.1-sol")["reason"] == "image_capability_unverified"
    assert all(row["id"] != "gpt-6.1-sol" for row in plan["patch_models"])


def test_provider_text_only_evidence_takes_precedence_over_manifest(inputs):
    inputs[2]["models"][0]["input_modalities"] = ["text"]
    inputs[3]["modelCatalog"]["providers"]["openai"]["models"].append(
        {"id": "gpt-6-luna", "input": ["text", "image"]})
    assert make_plan(inputs)["patch_models"] == []


def test_manifest_can_supply_exact_evidence_when_cache_has_no_modalities(inputs):
    inputs[2]["models"][0].pop("input_modalities")
    inputs[3]["modelCatalog"]["providers"]["openai"]["models"].append(
        {"id": "gpt-6-luna", "input": ["text", "image"]})
    plan = make_plan(inputs)
    assert decision(plan, "gpt-6-luna")["sources"] == ["bundled_manifest"]
    assert len(plan["patch_models"]) == 1


@pytest.mark.parametrize("duplicate", [False, True])
def test_conflicting_explicit_cache_evidence_is_unresolved(inputs, duplicate):
    if duplicate:
        inputs[2]["models"].append({"slug": "gpt-6-luna", "input_modalities": ["text"]})
    else:
        inputs[2]["models"][0]["inputModalities"] = ["text"]
    evidence = collect_evidence(inputs[2], inputs[3])
    assert evidence["gpt-6-luna"]["conflict"]
    assert make_plan(inputs)["patch_models"] == []


@pytest.mark.parametrize("available", [False, None])
def test_unavailable_or_unknown_models_are_not_patched(inputs, available):
    inputs[1]["models"][0]["available"] = available
    assert make_plan(inputs)["patch_models"] == []


@pytest.mark.parametrize("url", ["https://proxy.invalid/v1", "https://chatgpt.com/other",
                                 "https://user:private@chatgpt.com/backend-api/codex"])
def test_evidence_is_not_transferred_to_a_different_transport(inputs, url):
    inputs[0]["models"]["providers"]["openai"]["baseUrl"] = url
    plan = make_plan(inputs)
    assert plan["patch_models"] == []
    assert plan["blocked"] == ["openai/gpt-6-luna"]
    assert url not in json.dumps(public_plan(plan))


@pytest.mark.parametrize("patch", [
    [{"id": "missing", "input": ["text", "image"]}],
    [{"id": "gpt-6-luna", "reasoning": False, "input": ["text", "image"]}],
    [{"id": "gpt-6-sol", "input": ["text", "image"]}],
])
def test_merge_rejects_out_of_scope_or_stale_mutations(inputs, patch):
    with pytest.raises(VisionPlanError):
        merge_model_rows(inputs[0]["models"]["providers"]["openai"]["models"], patch)


@pytest.fixture
def operator_files(tmp_path, inputs):
    flags = ["--config", "--catalog", "--provider-cache", "--manifest", "--runtime-models"]
    args, paths = [], []
    for flag, data in zip(flags, inputs):
        path = tmp_path / (flag[2:] + ".json")
        path.write_text(json.dumps(data))
        paths.append(path)
        args.extend([flag, str(path)])
    return args, paths


def test_default_cli_is_read_only_and_does_not_start_any_process(operator_files, monkeypatch, capsys):
    args, paths = operator_files
    before = [path.read_bytes() for path in paths]
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: pytest.fail("dry plan started a process"))
    assert operator.main(args) == 0
    report = json.loads(capsys.readouterr().out)
    assert report["mode"] == "dry_plan"
    assert len(report["evidence_sha256"]) == 64
    assert report["patch_models"] == [{"id": "gpt-6-luna", "input": ["text", "image"]}]
    assert "synthetic-private" not in json.dumps(report)
    assert [path.read_bytes() for path in paths] == before


def reviewed_args(args, capsys):
    assert operator.main(args) == 0
    digest = json.loads(capsys.readouterr().out)["evidence_sha256"]
    return [*args, "--openclaw", "/synthetic/openclaw", "--apply", "--expect-evidence", digest]


@pytest.mark.parametrize("has_base_url", [False, True])
def test_apply_has_separate_validation_then_conditional_id_merge(
        operator_files, monkeypatch, capsys, has_base_url):
    args, paths = operator_files
    if has_base_url:
        config = json.loads(paths[0].read_text())
        config["models"]["providers"]["openai"]["baseUrl"] = "https://chatgpt.com/backend-api/codex"
        paths[0].write_text(json.dumps(config))
    args = reviewed_args(args, capsys)
    before = json.loads(paths[0].read_text())
    calls = []

    def fake_cli(command, **kwargs):
        calls.append(command)
        assert kwargs["capture_output"] and not kwargs.get("shell")
        assert "synthetic-private" not in json.dumps(command)
        assert command[1:4] == ["config", "set", "models.providers.openai"]
        payload = json.loads(command[4])
        assert set(payload) == {"models"}
        assert "--merge" in command and "--strict-json" in command
        if len(calls) == 1:
            assert "--dry-run" in command and "--expect-current-json" not in command
        else:
            assert "--dry-run" not in command
            expected = json.loads(command[command.index("--expect-current-json") + 1])
            assert expected == before["models"]["providers"]["openai"]
            written = deepcopy(before)
            written["models"]["providers"]["openai"]["models"] = merge_model_rows(expected["models"], payload["models"])
            paths[0].write_text(json.dumps(written))
        return subprocess.CompletedProcess(command, 0, b"synthetic-private-output", b"")

    monkeypatch.setattr(subprocess, "run", fake_cli)
    assert operator.main(args) == 0
    report = json.loads(capsys.readouterr().out)
    assert report["mode"] == "config_applied_runtime_unverified"
    assert len(calls) == 2
    after = json.loads(paths[0].read_text())
    before["models"]["providers"]["openai"]["models"][0]["input"] = ["text", "image"]
    assert after == before


@pytest.mark.parametrize(("field", "replacement"), [
    ("baseUrl", "https://route-changed.invalid/v1"),
    ("api", "openai-completions"),
])
def test_route_race_after_snapshot_check_fails_condition_without_patch(
        operator_files, monkeypatch, capsys, field, replacement):
    args, paths = operator_files
    config = json.loads(paths[0].read_text())
    config["models"]["providers"]["openai"]["baseUrl"] = "https://chatgpt.com/backend-api/codex"
    paths[0].write_text(json.dumps(config))
    args = reviewed_args(args, capsys)
    before = json.loads(paths[0].read_text())
    calls = []

    def fake_cli(command, **kwargs):
        calls.append(command)
        if "--dry-run" in command:
            return subprocess.CompletedProcess(command, 0, b"", b"")
        # Another operator changes the route after assert_unchanged completed,
        # immediately before the CLI reads its conditional-write snapshot.
        concurrent = deepcopy(before)
        provider = concurrent["models"]["providers"]["openai"]
        provider[field] = replacement
        paths[0].write_text(json.dumps(concurrent))
        actual = concurrent
        for key in command[3].split("."):
            actual = actual[key]
        expected = json.loads(command[command.index("--expect-current-json") + 1])
        if actual != expected:
            return subprocess.CompletedProcess(command, 1, b"", b"conditional mismatch")
        pytest.fail("The changed route incorrectly satisfied the write condition")

    monkeypatch.setattr(subprocess, "run", fake_cli)
    assert operator.main(args) == 2
    assert len(calls) == 2
    assert json.loads(capsys.readouterr().out)["error"] == "vision_cli_apply_failed"
    after = json.loads(paths[0].read_text())
    expected = deepcopy(before)
    expected["models"]["providers"]["openai"][field] = replacement
    assert after == expected
    assert "input" not in after["models"]["providers"]["openai"]["models"][0]


@pytest.mark.parametrize("field", ["apiKey", "auth", "authHeader", "headers", "unknown"])
def test_apply_refuses_all_nonpublic_provider_fields_before_cli(
        operator_files, monkeypatch, capsys, field):
    args, paths = operator_files
    config = json.loads(paths[0].read_text())
    config["models"]["providers"]["openai"][field] = "synthetic-private-value"
    paths[0].write_text(json.dumps(config))
    args = reviewed_args(args, capsys)
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: pytest.fail("private provider reached CLI"))
    assert operator.main(args) == 2
    assert "synthetic-private" not in capsys.readouterr().out


@pytest.mark.parametrize("url", [
    "https://user:synthetic-private@chatgpt.com/backend-api/codex",
    "https://chatgpt.com/backend-api/codex?token=synthetic-private",
    "https://chatgpt.com/backend-api/codex#synthetic-private",
    "https://chatgpt.com/backend-api/codex\n",
    "http://chatgpt.com/backend-api/codex",
    "file:///synthetic-private",
])
def test_provider_condition_rejects_unsafe_urls_even_with_safe_model_override(
        operator_files, monkeypatch, capsys, url):
    args, paths = operator_files
    config = json.loads(paths[0].read_text())
    provider = config["models"]["providers"]["openai"]
    provider["baseUrl"] = url
    provider["models"][0]["baseUrl"] = "https://chatgpt.com/backend-api/codex"
    paths[0].write_text(json.dumps(config))
    # A valid per-model route does not make the full provider safe for argv.
    with pytest.raises(VisionPlanError):
        public_provider_snapshot(provider)
    args = reviewed_args(args, capsys)
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: pytest.fail("unsafe URL reached CLI"))
    assert operator.main(args) == 2
    output = capsys.readouterr().out
    assert url not in output and "synthetic-private" not in output


@pytest.mark.parametrize("changed_index", [0, 1, 2, 3, 4])
def test_review_fingerprint_rejects_any_changed_snapshot(operator_files, monkeypatch, capsys, changed_index):
    args, paths = operator_files
    args = reviewed_args(args, capsys)
    paths[changed_index].write_text(paths[changed_index].read_text() + "\n")
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: pytest.fail("stale review started CLI"))
    assert operator.main(args) == 2
    assert json.loads(capsys.readouterr().out)["error"] == "vision_evidence_fingerprint_mismatch"


@pytest.mark.parametrize("changed_index", [0, 2])
def test_changes_during_cli_validation_prevent_apply(operator_files, monkeypatch, capsys, changed_index):
    args, paths = operator_files
    args = reviewed_args(args, capsys)
    calls = []

    def fake_cli(command, **kwargs):
        calls.append(command)
        paths[changed_index].write_text(paths[changed_index].read_text() + "\n")
        return subprocess.CompletedProcess(command, 0, b"", b"")

    monkeypatch.setattr(subprocess, "run", fake_cli)
    assert operator.main(args) == 2
    assert len(calls) == 1
    assert json.loads(capsys.readouterr().out)["error"] == "vision_snapshot_changed"


@pytest.mark.parametrize("failure_at", [1, 2])
def test_cli_failure_is_sanitized_and_never_retried(operator_files, monkeypatch, capsys, failure_at):
    args, paths = operator_files
    before = paths[0].read_bytes()
    args = reviewed_args(args, capsys)
    calls = []

    def fake_cli(command, **kwargs):
        calls.append(command)
        return subprocess.CompletedProcess(command, int(len(calls) == failure_at),
                                           b"synthetic-private-output", b"synthetic-private-error")

    monkeypatch.setattr(subprocess, "run", fake_cli)
    assert operator.main(args) == 2
    output = capsys.readouterr().out
    assert "synthetic-private" not in output
    assert len(calls) == failure_at
    assert paths[0].read_bytes() == before


def test_apply_refuses_private_authored_fields_in_conditional_argv(operator_files, monkeypatch, capsys):
    args, paths = operator_files
    config = json.loads(paths[0].read_text())
    config["models"]["providers"]["openai"]["models"][1]["headers"] = {"Authorization": "synthetic-private"}
    paths[0].write_text(json.dumps(config))
    args = reviewed_args(args, capsys)
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: pytest.fail("private config reached CLI"))
    assert operator.main(args) == 2
    assert "synthetic-private" not in capsys.readouterr().out


def test_apply_requires_review_and_noop_needs_no_cli(operator_files, monkeypatch, capsys):
    args, paths = operator_files
    monkeypatch.setattr(subprocess, "run", lambda *a, **k: pytest.fail("unexpected CLI"))
    assert operator.main([*args, "--apply"]) == 2
    assert json.loads(capsys.readouterr().out)["error"] == "vision_review_fingerprint_required"
    config = json.loads(paths[0].read_text())
    config["models"]["providers"]["openai"]["models"][0]["input"] = ["text", "image"]
    paths[0].write_text(json.dumps(config))
    assert operator.main(reviewed_args(args, capsys)) == 0
    assert json.loads(capsys.readouterr().out)["mode"] == "no_changes"
