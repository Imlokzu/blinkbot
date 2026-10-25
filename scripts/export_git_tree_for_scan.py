#!/usr/bin/env python3
"""Materialize committed blob bytes for scanning, without archive attributes.

Symlink blobs become ordinary files containing the link text. This scans the
actual committed bytes without following links into the owner's filesystem.
Submodule gitlinks name another repository's commit, not blobs in this tree.
"""
from __future__ import annotations

import argparse
from pathlib import Path, PurePosixPath
import subprocess


def export_tree(tree: str, destination: Path) -> None:
    if not destination.is_dir() or any(destination.iterdir()):
        raise ValueError("Scan destination must be an empty directory")
    entries = subprocess.check_output(["git", "ls-tree", "-r", "-z", tree])
    # One process streams all blobs; launching Git per file is expensive in
    # this repository's thousands of bundled assets.
    process = subprocess.Popen(["git", "cat-file", "--batch"], stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE)
    try:
        for entry in entries.split(b"\0"):
            if not entry:
                continue
            metadata, name = entry.split(b"\t", 1)
            mode, kind, oid = metadata.decode("ascii").split()
            relative = PurePosixPath(name.decode("utf-8", "surrogateescape"))
            if relative.is_absolute() or any(part in ("..", ".git") for part in relative.parts):
                raise ValueError("Unsafe tree path")
            if kind == "commit" and mode == "160000":
                continue
            if kind != "blob" or mode not in ("100644", "100755", "120000"):
                raise ValueError("Unsupported tree entry")
            target = destination.joinpath(*relative.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            process.stdin.write((oid + "\n").encode("ascii"))
            process.stdin.flush()
            header = process.stdout.readline().decode("ascii").split()
            if len(header) != 3 or header[:2] != [oid, "blob"] or not header[2].isdigit():
                raise ValueError("Unexpected Git blob response")
            remaining = int(header[2])
            with target.open("xb") as output:
                while remaining:
                    chunk = process.stdout.read(min(remaining, 1024 * 1024))
                    if not chunk:
                        raise ValueError("Incomplete Git blob")
                    output.write(chunk)
                    remaining -= len(chunk)
            if process.stdout.read(1) != b"\n":
                raise ValueError("Missing Git blob separator")
            if mode == "100755":
                target.chmod(0o700)
        process.stdin.close()
        if process.wait() != 0:
            raise ValueError("Git blob export failed")
    finally:
        if process.poll() is None:
            process.kill()
        process.wait()
        process.stdin.close()
        process.stdout.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("tree")
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    try:
        export_tree(args.tree, args.destination)
    except (OSError, ValueError, subprocess.CalledProcessError):
        print("Unable to export the exact Git tree for scanning.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
