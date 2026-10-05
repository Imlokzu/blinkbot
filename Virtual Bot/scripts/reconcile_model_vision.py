#!/usr/bin/env python3
"""Operator-only repair of missing authored OpenAI image input declarations.

Default execution reads local JSON snapshots and prints a redacted plan. It
does not invoke OpenClaw, refresh catalogs, read credentials or send requests.
``--runtime-models`` optionally supplies installed-resolver rows; a picker
catalog is not a substitute. Discovered models are never added to config.

Review the default plan, then explicitly pass --apply and its --expect-evidence
fingerprint. Apply validates through the installed CLI first, rechecks every
input file, and conditionally merges model IDs against the entire authored
provider snapshot, including routing. No runtime restart or provider check
follows. The parent/operator owns those checks.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from model_vision import (  # noqa: E402
    VisionPlanError, merge_model_rows, plan_reconciliation, public_plan,
    public_provider_snapshot,
)


CONFIG_KEY = "models.providers.openai"


def read_json(path: Path) -> tuple[bytes, dict]:
    try:
        raw = path.read_bytes()
        data = json.loads(raw)
    except (OSError, ValueError, UnicodeError):
        raise VisionPlanError("vision_snapshot_unreadable") from None
    if not isinstance(data, dict):
        raise VisionPlanError("vision_snapshot_invalid")
    return raw, data


def snapshot_fingerprint(snapshots: dict[str, tuple[Path, bytes]]) -> str:
    """Bind review to config, inventory and exact evidence bytes, not filenames."""
    hashes = {name: hashlib.sha256(raw).hexdigest()
              for name, (_path, raw) in snapshots.items()}
    return hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()


def assert_unchanged(snapshots: dict[str, tuple[Path, bytes]]) -> None:
    for path, expected in snapshots.values():
        try:
            same = path.read_bytes() == expected
        except OSError:
            same = False
        if not same:
            raise VisionPlanError("vision_snapshot_changed")


def cli_set(cli: str, config_path: Path, patch: list[dict], *,
            expected: dict | None = None) -> None:
    args = [cli, "config", "set", CONFIG_KEY, json.dumps({"models": patch}), "--merge", "--strict-json"]
    if expected is None:
        args.append("--dry-run")
    else:
        args.extend(["--expect-current-json", json.dumps(expected)])
    try:
        result = subprocess.run(
            args, env={**os.environ, "OPENCLAW_CONFIG_PATH": str(config_path)},
            capture_output=True, timeout=60, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        raise VisionPlanError("vision_cli_failed") from None
    if result.returncode:
        # CLI diagnostics can include source config or credentials. Never echo.
        code = "vision_cli_validation_failed" if expected is None else "vision_cli_apply_failed"
        raise VisionPlanError(code)


def apply_plan(cli: str, config_path: Path, config: dict, plan: dict,
               snapshots: dict[str, tuple[Path, bytes]], expected_fingerprint: str) -> str:
    if expected_fingerprint != snapshot_fingerprint(snapshots):
        raise VisionPlanError("vision_evidence_fingerprint_mismatch")
    assert_unchanged(snapshots)
    if plan["blocked"]:
        raise VisionPlanError("vision_plan_blocked")
    patch = plan["patch_models"]
    if not patch:
        return "no_changes"
    # Route changes after the last file check must fail the CLI's atomic
    # condition too. The entire expected provider must be safe for argv.
    provider = public_provider_snapshot(config["models"]["providers"]["openai"])
    authored = provider["models"]
    merged = merge_model_rows(authored, patch)
    cli_set(cli, config_path, patch)
    assert_unchanged(snapshots)
    cli_set(cli, config_path, patch, expected=provider)
    _, written = read_json(config_path)
    if written.get("models", {}).get("providers", {}).get("openai", {}).get("models") != merged:
        raise VisionPlanError("vision_config_verification_failed")
    # OpenClaw may update its own bookkeeping, but other authored settings must
    # remain identical. A failed verification is reported, never auto-reverted.
    before, after = deepcopy(config), deepcopy(written)
    before["models"]["providers"]["openai"]["models"] = merged
    before.pop("meta", None)
    after.pop("meta", None)
    if before != after:
        raise VisionPlanError("vision_unrelated_config_changed")
    return "config_applied_runtime_unverified"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path,
                        default=Path(os.environ.get("OPENCLAW_CONFIG_PATH", "~/.openclaw/openclaw.json")))
    parser.add_argument("--catalog", type=Path, default=Path("~/.openclaw/virtual-bot-brain-models.json"))
    parser.add_argument("--provider-cache", type=Path, default=Path("~/.codex/models_cache.json"))
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--runtime-models", type=Path)
    parser.add_argument("--openclaw", default=shutil.which("openclaw"))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--expect-evidence")
    args = parser.parse_args(argv)
    try:
        if args.apply and not args.expect_evidence:
            raise VisionPlanError("vision_review_fingerprint_required")
        if args.manifest is None:
            if not args.openclaw:
                raise VisionPlanError("vision_manifest_required")
            args.manifest = Path(args.openclaw).resolve().parent / "dist/extensions/openai/openclaw.plugin.json"
        paths = {"config": args.config, "catalog": args.catalog,
                 "provider_cache": args.provider_cache, "manifest": args.manifest}
        if args.runtime_models:
            paths["runtime_models"] = args.runtime_models
        snapshots, data = {}, {}
        for name, path in paths.items():
            path = path.expanduser().resolve()
            raw, data[name] = read_json(path)
            snapshots[name] = (path, raw)
        plan = plan_reconciliation(
            data["config"], data["catalog"], data["provider_cache"], data["manifest"],
            runtime_models=data.get("runtime_models", {"models": []}),
        )
        fingerprint = snapshot_fingerprint(snapshots)
        mode = "dry_plan"
        if args.apply:
            if not args.openclaw:
                raise VisionPlanError("vision_cli_unavailable")
            mode = apply_plan(args.openclaw, snapshots["config"][0], data["config"], plan,
                              snapshots, args.expect_evidence)
        print(json.dumps({"mode": mode, "evidence_sha256": fingerprint, **public_plan(plan)}))
        return 0
    except VisionPlanError as error:
        print(json.dumps({"error": str(error)}))
        return 2
    except (ValueError, TypeError, KeyError, AttributeError):
        # Malformed external snapshots must not escape as a raw-data traceback.
        print(json.dumps({"error": "vision_snapshot_invalid"}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
