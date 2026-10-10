#!/usr/bin/env python3
"""Fetch and verify the pinned Apple Silicon desktop browser/runtime at build time."""
import hashlib
import pathlib
import subprocess
import tarfile
import urllib.request

NAME = "jbrsdk_jcef-25.0.4.1-osx-aarch64-b635.70"
SHA512 = "dcec6fa11bddc600249d8ab9fb1ef95e0cfab641816a2e73baa4e5aefbc5c276002b300fe81796858f1409fe1b5e2a6cbce57896793c66b067626f2df2065f2e"
ROOT = pathlib.Path(__file__).resolve().parents[2] / "build" / "desktop-runtime"


def prepare():
    ROOT.mkdir(parents=True, exist_ok=True)
    home = ROOT / NAME / "Contents" / "Home"
    api = ROOT / "jcef-api"
    if home.exists() and api.exists():
        print(home)
        return
    archive = ROOT / "jbrsdk.tar.gz"
    if not archive.exists():
        with urllib.request.urlopen(f"https://cache-redirector.jetbrains.com/intellij-jbr/{NAME}.tar.gz", timeout=60) as source:
            with archive.open("wb") as target:
                while block := source.read(1024 * 1024):
                    target.write(block)
    digest = hashlib.sha512()
    with archive.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    if digest.hexdigest() != SHA512:
        raise RuntimeError("Desktop runtime checksum does not match the pinned release")
    if not home.exists():
        with tarfile.open(archive) as source:
            for member in source.getmembers():
                path = pathlib.PurePosixPath(member.name)
                if path.is_absolute() or ".." in path.parts:
                    raise RuntimeError("Unsafe runtime archive path")
            source.extractall(ROOT)
    if not api.exists():
        subprocess.run([str(home / "bin" / "jmod"), "extract", "--dir", str(api), str(home / "jmods" / "jcef.jmod")], check=True)
    print(home)


if __name__ == "__main__":
    prepare()
