#!/usr/bin/env python3
"""Accept only reviewed, exact public-fixture matches from TruffleHog JSONL.

This processes exit-183 reports. It never accepts verified credentials, unknown
fingerprints, changed paths, malformed reports or an empty detection stream.
Matched values are hashed in memory and never written to diagnostic output.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath


def reviewed_entries(path: Path) -> set[tuple[str, str, str]]:
    document = json.loads(path.read_text())
    if (not isinstance(document, dict) or type(document.get("schema_version")) is not int
            or document["schema_version"] != 1 or not isinstance(document.get("entries"), list)):
        raise ValueError("Unsupported public-fixture schema")
    result = set()
    for entry in document["entries"]:
        if not isinstance(entry, dict):
            raise ValueError("Invalid public-fixture entry")
        detector, chunks = entry["detector"], entry["sha256"]
        # Four literal groups preserve the full digest without making the
        # policy itself resemble a contiguous 40-character CircleCI token.
        if (not isinstance(detector, str) or not detector or not isinstance(chunks, list)
                or len(chunks) != 4 or any(not isinstance(chunk, str)
                or not re.fullmatch(r"[0-9a-f]{16}", chunk) for chunk in chunks)):
            raise ValueError("Invalid public-fixture entry")
        digest = "".join(chunks)
        if (not isinstance(entry.get("reason"), str) or not entry["reason"].strip()
                or not isinstance(entry.get("paths"), list) or not entry["paths"]):
            raise ValueError("A public fixture needs paths and a review reason")
        for path in entry["paths"]:
            if not isinstance(path, str) or not path or PurePosixPath(path).is_absolute() or ".." in PurePosixPath(path).parts:
                raise ValueError("Invalid public-fixture path")
            result.add((detector, digest, path))
    return result


def report_path(finding: dict, root: Path) -> str:
    source = finding["SourceMetadata"]["Data"]
    if "Filesystem" in source:
        return Path(source["Filesystem"]["file"]).resolve().relative_to(root.resolve()).as_posix()
    if "Git" in source:
        path = PurePosixPath(source["Git"]["file"])
        if path.is_absolute() or ".." in path.parts:
            raise ValueError("Invalid Git finding path")
        return path.as_posix()
    raise ValueError("Unsupported finding source")


def classify(report: Path, root: Path, allowed: set[tuple[str, str, str]]) -> tuple[int, int]:
    accepted = rejected = 0
    for line in report.read_text().splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if not isinstance(row, dict):
            raise ValueError("Invalid report record")
        if "DetectorName" not in row:
            # Scanner diagnostics are JSON log records mixed into stderr.
            if "level" in row and any(key in row for key in ("msg", "message")):
                continue
            raise ValueError("Unrecognized report record")
        raw = row.get("Raw")
        if (row.get("Verified") is not False or row.get("VerificationError")
                or not isinstance(raw, str) or not raw):
            rejected += 1
            continue
        key = (row["DetectorName"], hashlib.sha256(raw.encode()).hexdigest(), report_path(row, root))
        if key in allowed:
            accepted += 1
        else:
            rejected += 1
    if accepted + rejected == 0:
        raise ValueError("Exit-183 report contains no findings")
    return accepted, rejected


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    parser.add_argument("source_root", type=Path)
    args = parser.parse_args()
    try:
        accepted, rejected = classify(args.report, args.source_root, reviewed_entries(
            Path(__file__).resolve().parents[1] / ".trufflehog-public-fixtures.json"))
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        print("TruffleHog public-fixture review failed closed; report or policy is invalid.")
        return 1
    print(f"TruffleHog: {accepted} exact public fixtures; {rejected} other candidates.")
    return 1 if rejected else 0


if __name__ == "__main__":
    raise SystemExit(main())
