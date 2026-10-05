"""Pure, exact-ID image capability reconciliation for an OpenClaw operator.

Provider evidence establishes capability, not delivery. Optional runtime rows
must come from the installed resolver, never from picker badges. Only missing
input on existing authored rows can be repaired; discovered rows are never
authored by this helper. This module performs no I/O or model selection.
"""

from __future__ import annotations

from copy import deepcopy
import re
from typing import Any
from urllib.parse import urlsplit


MODEL_FIELDS = frozenset({
    "id", "name", "input", "api", "baseUrl", "reasoning", "contextWindow",
    "contextTokens", "contextWindows", "contextWindowDefault", "maxTokens",
    "compat", "cost", "thinkingLevelMap", "mediaInput",
})
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,159}\Z")
_MODALITIES = frozenset({"text", "image", "audio", "video", "document"})
_PRIVATE_KEYS = frozenset({
    "apikey", "api_key", "authorization", "headers", "token", "password",
    "secret", "credentials", "prompt", "instructions", "messages", "request",
})


class VisionPlanError(ValueError):
    """Stable diagnostic codes only; exception text never contains source data."""


def _modalities(value: Any) -> list[str] | None:
    if not isinstance(value, list) or not value:
        return None
    if any(not isinstance(item, str) or item not in _MODALITIES for item in value):
        return None
    return sorted(set(value))


def native_image_support(input_value: Any) -> bool | None:
    """Interpret an effective runtime input array; '-' and missing mean unknown."""
    modalities = _modalities(input_value)
    return "image" in modalities if modalities is not None else None


def _rows(value: Any) -> list[dict]:
    rows = value.get("models", []) if isinstance(value, dict) else value
    if not isinstance(rows, list) or any(not isinstance(row, dict) for row in rows):
        raise VisionPlanError("vision_invalid_model_rows")
    return rows


def _bare_id(value: Any) -> str | None:
    return value if isinstance(value, str) and _ID.fullmatch(value) else None


def _openai_id(row: dict) -> str | None:
    value = row.get("key", row.get("id"))
    if isinstance(value, str) and value.startswith("openai/"):
        if row.get("provider", "openai") != "openai":
            return None
        return _bare_id(value[len("openai/"):])
    return _bare_id(value) if row.get("provider") == "openai" else None


def collect_evidence(provider_cache: dict, manifest: dict) -> dict[str, dict]:
    """Use explicit per-model modalities, preferring the provider over its bundle.

    Cache keys: models[].slug/id and input_modalities/inputModalities. Manifest
    keys: modelCatalog.providers.openai.models[].id/input. Conflicting duplicate
    rows or cache spellings are unresolved. No family matching or defaults.
    """
    groups: dict[str, dict[str, list[list[str]]]] = {}
    manifest_rows = manifest.get("modelCatalog", {}).get("providers", {}).get("openai", {}).get("models", [])
    for source, rows, fields in (
        ("bundled_manifest", _rows(manifest_rows), ("input",)),
        ("provider_cache", _rows(provider_cache), ("input_modalities", "inputModalities")),
    ):
        for row in rows:
            model_id = _bare_id(row.get("slug", row.get("id")))
            if model_id is None:
                continue
            if row.get("slug") and row.get("id") and row["slug"] != row["id"]:
                raise VisionPlanError("vision_conflicting_evidence_identity")
            for field in fields:
                modalities = _modalities(row.get(field))
                if modalities is not None:
                    groups.setdefault(model_id, {}).setdefault(source, []).append(modalities)
    evidence = {}
    for model_id, sources in groups.items():
        source = "provider_cache" if "provider_cache" in sources else "bundled_manifest"
        variants = {tuple(value) for value in sources[source]}
        conflict = len(variants) != 1
        evidence[model_id] = {
            "input": None if conflict else list(next(iter(variants))),
            "sources": [source],
            "conflict": conflict,
        }
    return evidence


def _assert_public(value: Any) -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if not isinstance(key, str) or key.lower() in _PRIVATE_KEYS:
                raise VisionPlanError("vision_private_model_metadata")
            _assert_public(item)
    elif isinstance(value, list):
        for item in value:
            _assert_public(item)


def public_model(row: dict) -> dict:
    """Project model metadata without copying credentials, prompts or headers."""
    result = {key: deepcopy(value) for key, value in row.items() if key in MODEL_FIELDS}
    _assert_public(result)
    if result.get("baseUrl"):
        url = urlsplit(result["baseUrl"])
        if url.username or url.password or url.query or url.fragment:
            raise VisionPlanError("vision_private_model_url")
    return result


def public_provider_snapshot(provider: dict) -> dict:
    """Validate the entire CLI condition before any provider data enters argv.

    The condition includes routing and models to close a route-change race.
    Unknown provider fields may contain credentials, so fail instead of
    filtering them out and weakening the compare-and-set condition.
    """
    if not isinstance(provider, dict) or set(provider) - {"api", "baseUrl", "models"}:
        raise VisionPlanError("vision_private_provider_fields")
    if not _chatgpt_route({}, provider):
        raise VisionPlanError("vision_unsafe_provider_route")
    if "baseUrl" in provider:
        value = provider["baseUrl"]
        if not isinstance(value, str) or any(character.isspace() for character in value):
            raise VisionPlanError("vision_unsafe_provider_route")
    if "models" not in provider:
        raise VisionPlanError("vision_invalid_model_rows")
    if any(public_model(row) != row for row in _rows(provider)):
        raise VisionPlanError("vision_private_authored_model_fields")
    return deepcopy(provider)


def _chatgpt_route(row: dict, provider: dict) -> bool:
    api = row.get("api", provider.get("api"))
    raw_url = row.get("baseUrl", provider.get("baseUrl"))
    if not raw_url:
        return api == "openai-chatgpt-responses"
    try:
        url = urlsplit(raw_url)
        return (api == "openai-chatgpt-responses" and url.scheme == "https"
                and url.hostname == "chatgpt.com" and url.port in (None, 443)
                and url.path.rstrip("/") == "/backend-api/codex"
                and not any((url.username, url.password, url.query, url.fragment)))
    except (ValueError, TypeError, AttributeError):
        return False


def merge_model_rows(existing: list[dict], patch: list[dict]) -> list[dict]:
    """Simulate the narrow ID merge, refusing additions or non-input edits."""
    result = deepcopy(existing)
    indexes = {row["id"]: index for index, row in enumerate(result)}
    if len(indexes) != len(result):
        raise VisionPlanError("vision_invalid_authored_identity")
    for row in patch:
        if (set(row) != {"id", "input"} or row["id"] not in indexes
                or row["input"] != ["text", "image"]):
            raise VisionPlanError("vision_patch_out_of_scope")
        target = result[indexes[row["id"]]]
        if "input" in target:
            raise VisionPlanError("vision_authored_input_changed")
        target["input"] = list(row["input"])
    return result


def plan_reconciliation(config: dict, catalog: Any, provider_cache: dict,
                        manifest: dict, *, runtime_models: Any = ()) -> dict:
    """Repair omitted authored input using exact, explicit capability evidence.

    Returns patch_models, decisions and blocked. Explicit authored modalities
    are never overwritten. Catalog '-' proves nothing about runtime input.
    Discovered rows stay unchanged or require manual diagnosis; no additions.
    Existing metadata is preserved because patches contain only id and input.
    """
    provider = config.get("models", {}).get("providers", {}).get("openai", {})
    authored = {}
    for row in _rows(provider):
        model_id = _bare_id(row.get("id"))
        if model_id is None or model_id in authored:
            raise VisionPlanError("vision_invalid_authored_identity")
        authored[model_id] = row
    evidence = collect_evidence(provider_cache, manifest)
    runtimes = {}
    for row in _rows(list(runtime_models) if isinstance(runtime_models, tuple) else runtime_models):
        model_id = _openai_id(row)
        if model_id is not None:
            if model_id in runtimes:
                raise VisionPlanError("vision_duplicate_runtime_identity")
            runtimes[model_id] = row
    plan: dict[str, Any] = {"provider": "openai", "patch_models": [], "decisions": [], "blocked": []}
    seen = set()
    for item in _rows(catalog):
        model_id = _openai_id(item)
        if model_id is None:
            continue
        if model_id in seen:
            raise VisionPlanError("vision_duplicate_catalog_identity")
        seen.add(model_id)
        row = authored.get(model_id)
        runtime = runtimes.get(model_id)
        proof = evidence.get(model_id, {})
        decision = {"model_id": f"openai/{model_id}", "sources": proof.get("sources", [])}

        def finish(status: str, reason: str) -> None:
            decision.update(status=status, reason=reason)
            plan["decisions"].append(decision)
            if status == "blocked":
                plan["blocked"].append(decision["model_id"])

        if item.get("available") is not True:
            finish("skipped", "availability_unverified")
        elif runtime and native_image_support(runtime.get("input")) is True:
            finish("unchanged", "runtime_already_accepts_images")
        elif row is not None and "input" in row:
            finish("unchanged", "authored_input_preserved")
        elif row is None:
            finish("manual_diagnosis", "discovered_model_requires_runtime_diagnosis")
        elif native_image_support(proof.get("input")) is not True:
            finish("manual_diagnosis", "image_capability_unverified")
        elif not _chatgpt_route(row, provider) or (runtime and not _chatgpt_route(runtime, provider)):
            finish("blocked", "effective_route_unverified")
        elif any(source.get(key) for source in (provider, row)
                 for key in ("headers", "request", "params", "localService")):
            finish("blocked", "custom_transport_requires_manual_diagnosis")
        else:
            plan["patch_models"].append({"id": model_id, "input": ["text", "image"]})
            finish("patch_existing", "authored_input_missing")
    return plan


def public_plan(plan: dict) -> dict:
    """Only IDs, modalities and stable reason codes belong in operator output."""
    return {
        "provider": "openai", "decisions": deepcopy(plan["decisions"]),
        "blocked": list(plan["blocked"]),
        "patch_models": [{"id": row["id"], "input": row["input"]} for row in plan["patch_models"]],
    }
