"""Public-fixture exceptions never authorize a changed or verified credential."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("fixture_filter", Path(__file__).resolve().parents[1] / "filter_trufflehog_public_fixtures.py")
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class PublicFixtureTests(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.report = self.root / "report.jsonl"
        self.raw = "public-synthetic-fixture"
        self.allowed = {("Example", hashlib.sha256(self.raw.encode()).hexdigest(), "tests/fixture.py")}

    def finding(self, **patch):
        result = {"DetectorName": "Example", "Raw": self.raw, "Verified": False,
                  "SourceMetadata": {"Data": {"Filesystem": {"file": str(self.root / "tests/fixture.py")}}}}
        result.update(patch)
        return result

    def classify(self, *rows):
        self.report.write_text("\n".join(json.dumps(row) for row in rows))
        return module.classify(self.report, self.root, self.allowed)

    def test_only_exact_unverified_fixture_is_accepted(self):
        self.assertEqual(self.classify(self.finding()), (1, 0))

    def test_verified_missing_or_nonboolean_status_never_passes(self):
        for value in (True, None, 0, "false"):
            with self.subTest(value=value):
                self.assertEqual(self.classify(self.finding(Verified=value)), (0, 1))

    def test_changed_value_or_detector_still_blocks(self):
        for change in ({"Raw": "another-value"}, {"DetectorName": "Other"}, {"Raw": ""}):
            with self.subTest(change=change):
                self.assertEqual(self.classify(self.finding(**change)), (0, 1))

    def test_same_value_in_an_unreviewed_file_blocks(self):
        candidate = self.finding()
        candidate["SourceMetadata"]["Data"]["Filesystem"]["file"] = str(self.root / "production.py")
        self.assertEqual(self.classify(candidate), (0, 1))

    def test_one_unknown_among_known_findings_blocks(self):
        self.assertEqual(self.classify(self.finding(), self.finding(Raw="new-candidate")), (1, 1))

    def test_git_history_uses_the_same_exact_path_contract(self):
        self.assertEqual(self.classify(self.finding(SourceMetadata={"Data": {"Git": {"file": "tests/fixture.py"}}})), (1, 0))

    def test_outside_root_and_git_traversal_fail_closed(self):
        for source in ({"Filesystem": {"file": str(self.root / "../outside")}}, {"Git": {"file": "../tests/fixture.py"}}, {"Unknown": {}}):
            with self.subTest(source=source), self.assertRaises((ValueError, KeyError)):
                self.classify(self.finding(SourceMetadata={"Data": source}))

    def test_empty_or_unrecognized_reports_fail_closed(self):
        for rows in ((), ({"unexpected": "shape"},), ({"level": "info", "msg": "started"},), ([],)):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                self.classify(*rows)

    def test_diagnostics_do_not_hide_a_finding(self):
        self.assertEqual(self.classify({"level": "info", "msg": "scan"}, self.finding(Raw="unknown")), (0, 1))

    def test_verification_errors_are_unknown_and_block(self):
        self.assertEqual(self.classify(self.finding(VerificationError="timeout")), (0, 1))

    def test_policy_schema_and_exact_paths(self):
        policy = self.root / "policy.json"
        digest = hashlib.sha256(self.raw.encode()).hexdigest()
        entry = {"detector": "Example", "sha256": [digest[i:i+16] for i in range(0,64,16)],
                 "paths": ["tests/fixture.py"], "reason": "Synthetic fixture"}
        document = {"schema_version": 1, "entries": [entry]}
        policy.write_text(json.dumps(document))
        self.assertEqual(module.reviewed_entries(policy), self.allowed)
        bad = [dict(document, schema_version=True), dict(document, entries={})]
        for field, value in [("sha256", "x" * 64), ("sha256", "+" + "a" * 63), ("sha256", ["a" * 16] * 3),
                             ("sha256", ["A" * 16] * 4), ("sha256", [0] * 4),
                             ("paths", "tests/fixture.py"), ("paths", ["../outside"]),
                             ("paths", ["/absolute"]), ("reason", 1)]:
            bad.append(dict(document, entries=[dict(entry, **{field: value})]))
        for candidate in bad:
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                policy.write_text(json.dumps(candidate))
                module.reviewed_entries(policy)

    def test_malformed_json_fails_closed(self):
        self.report.write_text("not-json")
        with self.assertRaises(ValueError):
            module.classify(self.report, self.root, self.allowed)


if __name__ == "__main__":
    unittest.main()
