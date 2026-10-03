from __future__ import annotations

import asyncio
import io
import json
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx
from fastapi.testclient import TestClient

import main
import model_intelligence as mi
import openclaw_models

HEADERS = {
    "gpqa_diamond.csv": "Model version,mean_score,Best score (across scorers),Release date",
    "otis_mock_aime_2024_2025.csv": "Model version,mean_score,Best score (across scorers),Release date",
    "scicode_external.csv": "Model version,Score,Provider",
    "arc_agi_2_external.csv": "Model version,Score,Release date",
    "simpleqa_verified.csv": "Model version,mean_score,Best score (across scorers),Release date",
    "critpt_external.csv": "Model version,Accuracy,Release date",
}
COLUMN = {"gpqa_diamond.csv": 2, "otis_mock_aime_2024_2025.csv": 2, "scicode_external.csv": 1,
          "arc_agi_2_external.csv": 1, "simpleqa_verified.csv": 2, "critpt_external.csv": 1}
FILE = {key: file for key, _title, file, *_rest in mi.BENCHMARKS}


def archive(rows: dict[str, dict[str, float]]) -> bytes:
    """A miniature Epoch ZIP: {epoch model version: {benchmark key: score}}."""
    lines = {file: [header] for file, header in HEADERS.items()}
    for version, scores in rows.items():
        for bench, value in scores.items():
            file = FILE[bench]
            cells = [version, "", "", ""]
            cells[COLUMN[file]] = str(value)
            lines[file].append(",".join(cells))
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as bundle:
        for file, content in lines.items():
            bundle.writestr(file, "\n".join(content) + "\n")
    return buffer.getvalue()


def ladder(count: int = 12) -> dict[str, dict[str, float]]:
    """Models of steadily rising skill, each scored on all six benchmarks."""
    rows = {}
    for step in range(count):
        skill = 0.1 + 0.07 * step
        rows[f"ladder-{step}_high"] = {
            "gpqa": 0.25 + 0.7 * skill, "aime": skill, "scicode": 0.6 * skill,
            "arc_agi_2": skill ** 2, "simpleqa": 0.8 * skill, "critpt": 0.3 * skill ** 2,
        }
    return rows


class KeyTests(unittest.TestCase):
    def test_epoch_and_catalog_spellings_meet(self) -> None:
        # The catalog says openai/gpt-6-sol; Epoch says gpt-6-sol_max. A
        # mismatch here silently leaves a model without an index.
        cases = {
            "gpt-6-sol_max": "gpt-6-sol",
            "openai/gpt-6-sol": "gpt-6-sol",
            "gpt-5.4-nano-2026-03-17_high": "gpt-5.4-nano",
            "openai/gpt-oss-120b_high": "gpt-oss-120b",
            "qwen3.5-122b-a10b_none": "qwen3.5-122b",
            "regolo/qwen3.5-122b": "qwen3.5-122b",
            "qwen3.5-9B": "qwen3.5-9b",
            "claude-opus-5-5_max": "claude-opus-5-5",
        }
        for raw, expected in cases.items():
            self.assertEqual(mi.model_key(raw), expected, raw)


class ScoreTests(unittest.TestCase):
    def test_best_effort_setting_wins(self) -> None:
        scores = mi.read_scores(archive({
            "gpt-6-sol_medium": {"gpqa": 0.6},
            "gpt-6-sol_max": {"gpqa": 0.9},
            "gpt-6-sol_low": {"gpqa": 0.4},
        }))
        self.assertEqual(scores["gpt-6-sol"]["gpqa"], 0.9)

    def test_blank_and_broken_scores_are_skipped(self) -> None:
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as bundle:
            bundle.writestr("gpqa_diamond.csv", HEADERS["gpqa_diamond.csv"]
                            + "\nm-1,,,\nm-2,,n/a,\nm-3,,0.5,\n")
        self.assertEqual(mi.read_scores(buffer.getvalue()), {"m-3": {"gpqa": 0.5}})

    def test_index_follows_skill(self) -> None:
        index = mi.fit(mi.read_scores(archive(ladder())))
        ordered = [index[f"ladder-{step}"] for step in range(12)]
        self.assertEqual(ordered, sorted(ordered))
        self.assertTrue(all(0 <= value <= 100 for value in ordered))

    def test_missing_hard_benchmarks_do_not_inflate(self) -> None:
        # The whole point of the Rasch fit. A model that only took the two
        # easiest tests, scoring like ladder-5 there, must land near ladder-5.
        # A plain mean of normalized scores puts it above ladder-7 (0.43
        # against 0.40), because ladder-7's average includes CritPt.
        rows = ladder()
        middle = rows["ladder-5_high"]
        rows["easy-only_high"] = {"gpqa": middle["gpqa"], "aime": middle["aime"]}
        index = mi.fit(mi.read_scores(archive(rows)))
        self.assertLess(index["easy-only"], index["ladder-7"])
        self.assertGreater(index["easy-only"], index["ladder-3"])

    def test_one_benchmark_is_not_enough(self) -> None:
        rows = ladder()
        rows["lonely_max"] = {"gpqa": 0.99}
        self.assertNotIn("lonely", mi.fit(mi.read_scores(archive(rows))))

    def test_catalog_reply_keeps_only_scored_models(self) -> None:
        data = mi.build(archive(ladder()))
        reply = mi.for_catalog(data, ["openai/ladder-3", "openai/unknown-model"])
        self.assertTrue(reply["available"])
        self.assertEqual(list(reply["models"]), ["openai/ladder-3"])
        self.assertEqual(len(reply["models"]["openai/ladder-3"]["scores"]), 6)
        self.assertEqual([b["key"] for b in reply["benchmarks"]], [b[0] for b in mi.BENCHMARKS])
        # Each benchmark carries the field's best score: the top ladder rung's.
        top = mi.read_scores(archive(ladder()))["ladder-11"]
        self.assertEqual({b["key"]: b["top"] for b in reply["benchmarks"]},
                         {key: round(value, 4) for key, value in top.items()})
        # CC BY: the reply carries what the picker needs to credit Epoch.
        self.assertEqual(reply["source"]["name"], "Epoch AI")

    def test_no_data_is_reported_as_unavailable(self) -> None:
        reply = mi.for_catalog(None, ["openai/gpt-6-sol"])
        self.assertFalse(reply["available"])
        self.assertEqual(reply["models"], {})


class LoadTests(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = tempfile.TemporaryDirectory()
        self.path = Path(self.dir.name) / "intel.json"
        self.env = patch.dict("os.environ", {"VBOT_MODEL_INTEL_FILE": str(self.path)})
        self.env.start()
        mi._memory = None

    def tearDown(self) -> None:
        mi._memory = None
        self.env.stop()
        self.dir.cleanup()

    def test_downloads_once_then_serves_from_cache(self) -> None:
        download = AsyncMock(return_value=archive(ladder()))
        with patch.object(mi, "_download", download):
            first = asyncio.run(mi.load())
            second = asyncio.run(mi.load())
        self.assertEqual(download.await_count, 1)
        self.assertIs(first, second)
        self.assertTrue(self.path.exists())

    def test_a_restart_reads_the_disk_cache(self) -> None:
        self.path.write_text(json.dumps({"updated": time.time(), "models": {"m": {"index": 50, "scores": {}}}}))
        download = AsyncMock()
        with patch.object(mi, "_download", download):
            data = asyncio.run(mi.load())
        download.assert_not_awaited()
        self.assertIn("m", data["models"])

    def test_failed_refresh_keeps_the_stale_table(self) -> None:
        # Old order beats no order: the picker keeps showing the index.
        self.path.write_text(json.dumps({"updated": 1, "models": {"m": {"index": 50, "scores": {}}}}))
        failing = AsyncMock(side_effect=httpx.ConnectError("offline"))
        with patch.object(mi, "_download", failing):
            data = asyncio.run(mi.load())
        failing.assert_awaited_once()
        self.assertIn("m", data["models"])

    def test_endpoint_matches_the_catalog(self) -> None:
        catalog = [{"id": "openai/ladder-11"}, {"id": "regolo/not-scored"}]
        with patch.object(mi, "_download", AsyncMock(return_value=archive(ladder()))), \
             patch.object(openclaw_models, "reachable", return_value=True), \
             patch.object(openclaw_models, "catalog", AsyncMock(return_value=catalog)):
            reply = TestClient(main.app).get("/api/brain/intelligence").json()
        self.assertEqual(list(reply["models"]), ["openai/ladder-11"])
        self.assertGreater(reply["models"]["openai/ladder-11"]["index"], 50)


if __name__ == "__main__":
    unittest.main()
