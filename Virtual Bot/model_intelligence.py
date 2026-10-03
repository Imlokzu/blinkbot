"""
An intelligence index for the model picker, built from public benchmarks.

Why our own composite instead of one leaderboard number. A single benchmark
measures one skill — GPQA is science, AIME is maths — and a model tuned for
one looks smarter than it is. Six benchmarks across five areas, folded into
one number, say more about "how capable is this model overall".

Where the scores come from: Epoch AI's benchmarking hub
(https://epoch.ai/benchmarks). Their whole dataset is one public ZIP of CSVs,
no API key, licensed CC BY 4.0 — so the picker must credit them. Artificial
Analysis was the other candidate, but their API needs a key per user.

How six scores become one number, and why not just their average. Coverage
is uneven: GPT-6 Sol has all six, gpt-oss-20b has four, Qwen3.5 122B two.
A plain mean rewards a model for missing the hard benchmarks — two scores of
0.9 on easy tests beat six honest ones. So the index fits a Rasch model, the
simplest form of item response theory:

    logit(normalized score of model m on benchmark b) ≈ ability[m] − hardness[b]

fitted on every model Epoch has scored (hundreds of them), which pins down
how hard each benchmark is. A model's index is then the score it would be
expected to get, averaged over all six benchmarks, as 0–100. A missing
benchmark is neither a free pass nor a zero: it is predicted from the
model's ability on the ones it did take.

Scores are the best published across reasoning-effort settings: Epoch lists
`gpt-6-sol_max`, `gpt-6-sol_medium`, ... and the index answers "how capable
is this model", not "at the effort you happen to have selected".
"""

from __future__ import annotations

import asyncio
import csv
import io
import json
import logging
import math
import os
import re
import time
import zipfile
from pathlib import Path

import httpx

log = logging.getLogger("virtual_bot.model_intelligence")

SOURCE_URL = "https://epoch.ai/data/benchmark_data.zip"
SOURCE_PAGE = "https://epoch.ai/benchmarks"
SOURCE_NAME = "Epoch AI"
LICENSE = "CC BY 4.0"

# Epoch refreshes the archive a few times a week; a day old is fresh enough
# for "which model is smarter", and the download is 2–3 MB.
TTL_S = 24 * 3600
_TIMEOUT_S = 30.0
# A model needs this many benchmarks before its ability means anything: one
# score cannot separate "smart model" from "easy benchmark".
MIN_BENCHMARKS = 2

# key, display name (a proper name, the same in every language), CSV file,
# score column, random-guess baseline, best reachable score. Baselines and
# ceilings are Epoch's own, from benchmark_metadata.csv, so a 25 % on a
# four-option test counts as zero, not as a quarter of the way.
BENCHMARKS: tuple[tuple[str, str, str, str, float, float], ...] = (
    ("gpqa", "GPQA Diamond", "gpqa_diamond.csv", "Best score (across scorers)", 0.25, 1.0),
    ("aime", "OTIS Mock AIME", "otis_mock_aime_2024_2025.csv", "Best score (across scorers)", 0.001, 1.0),
    ("scicode", "SciCode", "scicode_external.csv", "Score", 0.0, 1.0),
    ("arc_agi_2", "ARC-AGI-2", "arc_agi_2_external.csv", "Score", 0.0, 1.0),
    ("simpleqa", "SimpleQA Verified", "simpleqa_verified.csv", "Best score (across scorers)", 0.0, 1.0),
    ("critpt", "CritPt", "critpt_external.csv", "Accuracy", 0.0, 1.0),
)

# The logit of 0 or 1 is infinite. Clipping at 2 % keeps a perfect AIME and a
# zero on CritPt informative without letting one score dominate the fit.
_CLIP = 0.02
_ITERATIONS = 60
# Shrinkage, counted in benchmarks: each model's ability starts with one
# imaginary benchmark scored at the average ability. Two real scores then
# move it two-thirds of the way from the average, six scores six-sevenths.
# Without it GPT-5.5 Pro, scored on just ARC-AGI-2 and CritPt, outranked
# GPT-6 Astra with all six — two lucky numbers are not a measurement.
_PRIOR_WEIGHT = 1.0


def _cache_path() -> Path:
    override = os.environ.get("VBOT_MODEL_INTEL_FILE")
    if override:
        return Path(override)
    return Path(__file__).resolve().parent / "runtime" / "model-intelligence.json"


def model_key(name: str) -> str:
    """
    One spelling for one model, whichever list it came from.

    Epoch writes `gpt-6-sol_max`, `gpt-5.4-nano-2026-03-17_high`,
    `openai/gpt-oss-120b_high`, `qwen3.5-122b-a10b_none`; the OpenClaw catalog
    writes `openai/gpt-6-sol` and `regolo/qwen3.5-122b`. Dropped, in order:
    the host prefix, the reasoning-effort suffix, a release date, and the
    active-parameter tag of a mixture-of-experts model.
    """
    key = name.strip().lower().split("/")[-1]
    key = re.sub(r"_[a-z0-9-]+$", "", key)
    key = re.sub(r"-\d{4}-\d{2}-\d{2}$", "", key)
    key = re.sub(r"-a\d+(?:\.\d+)?b$", "", key)
    return key


def _number(raw: str | None) -> float | None:
    try:
        value = float(str(raw).strip())
    except (TypeError, ValueError):
        return None
    return value if math.isfinite(value) else None


def read_scores(archive: bytes) -> dict[str, dict[str, float]]:
    """{model key: {benchmark key: best raw score}} from Epoch's ZIP."""
    scores: dict[str, dict[str, float]] = {}
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        names = {Path(name).name: name for name in bundle.namelist()}
        for key, _title, file, column, _base, _ceiling in BENCHMARKS:
            if file not in names:
                log.warning("Epoch archive has no %s", file)
                continue
            text = bundle.read(names[file]).decode("utf-8-sig", errors="replace")
            for row in csv.DictReader(io.StringIO(text)):
                model = model_key(row.get("Model version") or "")
                value = _number(row.get(column))
                if not model or value is None:
                    continue
                by_bench = scores.setdefault(model, {})
                by_bench[key] = max(value, by_bench.get(key, value))
    return scores


def _logit(score: float, base: float, ceiling: float) -> float:
    share = (score - base) / (ceiling - base)
    share = min(1 - _CLIP, max(_CLIP, share))
    return math.log(share / (1 - share))


def fit(scores: dict[str, dict[str, float]]) -> dict[str, float]:
    """
    Index 0–100 for every model with at least MIN_BENCHMARKS scores.

    Alternating least squares on the two-way model above: ability is a
    model's mean logit after each benchmark's hardness is taken out (shrunk
    toward the average, see _PRIOR_WEIGHT), hardness is a benchmark's mean
    logit after each model's ability is taken out.
    Sixty rounds are far past convergence for a few hundred rows.
    """
    limits = {key: (base, ceiling) for key, _t, _f, _c, base, ceiling in BENCHMARKS}
    cells = {
        model: {bench: _logit(value, *limits[bench]) for bench, value in row.items() if bench in limits}
        for model, row in scores.items()
    }
    cells = {model: row for model, row in cells.items() if len(row) >= MIN_BENCHMARKS}
    if not cells:
        return {}
    hardness = {key: 0.0 for key in limits}
    ability = {model: 0.0 for model in cells}
    for _ in range(_ITERATIONS):
        average = sum(ability.values()) / len(ability)
        for model, row in cells.items():
            total = sum(x + hardness[b] for b, x in row.items()) + _PRIOR_WEIGHT * average
            ability[model] = total / (len(row) + _PRIOR_WEIGHT)
        for bench in hardness:
            column = [ability[m] - row[bench] for m, row in cells.items() if bench in row]
            hardness[bench] = sum(column) / len(column) if column else 0.0
        # The scale has one free offset; pin the mean hardness to zero.
        shift = sum(hardness.values()) / len(hardness)
        hardness = {b: h - shift for b, h in hardness.items()}
    return {
        model: 100 * sum(1 / (1 + math.exp(-(a - h))) for h in hardness.values()) / len(hardness)
        for model, a in ability.items()
    }


def build(archive: bytes) -> dict:
    """The cached document: every scored model, keyed by model_key."""
    scores = read_scores(archive)
    index = fit(scores)
    return {
        "updated": int(time.time()),
        "models": {
            model: {"index": round(index[model], 1), "scores": {b: round(v, 4) for b, v in row.items()}}
            for model, row in scores.items() if model in index
        },
    }


_lock = asyncio.Lock()
_memory: dict | None = None


def _read_cache() -> dict | None:
    try:
        data = json.loads(_cache_path().read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return data if isinstance(data, dict) and isinstance(data.get("models"), dict) else None


def _write_cache(data: dict) -> None:
    path = _cache_path()
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data), encoding="utf-8")
        tmp.replace(path)
    except OSError as exc:
        log.warning("could not save the intelligence cache: %s", exc)


async def _download() -> bytes:
    async with httpx.AsyncClient(timeout=_TIMEOUT_S, follow_redirects=True) as client:
        response = await client.get(SOURCE_URL)
        response.raise_for_status()
        return response.content


async def load(force: bool = False) -> dict | None:
    """
    The scored table, downloading it at most once a day.

    A failed download keeps the last good table, however old: a stale index
    is still the right order of models, and an empty one hides the feature.
    """
    global _memory
    async with _lock:
        if _memory is None:
            _memory = _read_cache()
        fresh = _memory is not None and time.time() - float(_memory.get("updated") or 0) < TTL_S
        if fresh and not force:
            return _memory
        try:
            archive = await _download()
            data = await asyncio.to_thread(build, archive)
        except (httpx.HTTPError, zipfile.BadZipFile, OSError) as exc:
            log.warning("Epoch benchmarks unavailable: %s", exc)
            return _memory
        if data["models"]:
            _memory = data
            _write_cache(data)
        return _memory


def for_catalog(data: dict | None, model_ids: list[str]) -> dict:
    """The API reply: the index of each catalog model that Epoch has scored."""
    found: dict[str, dict] = {}
    table = (data or {}).get("models") or {}
    for model_id in model_ids:
        entry = table.get(model_key(model_id))
        if entry:
            found[model_id] = entry
    return {
        "available": bool(table),
        "updated": (data or {}).get("updated") or 0,
        "source": {"name": SOURCE_NAME, "url": SOURCE_PAGE, "license": LICENSE},
        "benchmarks": [{"key": key, "name": title} for key, title, *_rest in BENCHMARKS],
        "models": found,
    }
