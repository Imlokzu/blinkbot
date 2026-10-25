"""Archive attributes and checkout filters cannot hide staged blob bytes."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("tree_export", Path(__file__).resolve().parents[1] / "export_git_tree_for_scan.py")
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class ExactTreeTests(unittest.TestCase):
    def test_export_preserves_staged_blobs_despite_attributes_and_worktree_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / "repo"; repo.mkdir()
            destination = root / "scan"; destination.mkdir()
            def git(*args):
                return subprocess.check_output(["git", "-C", str(repo), *args])
            git("init", "-q")
            (repo / ".gitattributes").write_text("hidden.txt export-ignore\nformat.txt export-subst\n*.txt text eol=crlf\n")
            (repo / "hidden.txt").write_bytes(b"synthetic-public-fixture\n")
            (repo / "format.txt").write_bytes(b"$Format:%H$\n")
            (repo / "link").symlink_to("/outside-owner-data")
            (repo / "empty.bin").write_bytes(b"")
            (repo / "binary.bin").write_bytes(bytes(range(256)) * 4097)
            git("add", ".")
            tree = git("write-tree").decode().strip()
            (repo / "hidden.txt").write_text("unstaged change")
            previous = Path.cwd()
            try:
                os.chdir(repo)
                module.export_tree(tree, destination)
            finally:
                os.chdir(previous)
            for name in ("hidden.txt", "format.txt", ".gitattributes", "link", "empty.bin", "binary.bin"):
                self.assertEqual((destination / name).read_bytes(), git("show", tree + ":" + name))
            self.assertFalse((destination / "link").is_symlink())
            with self.assertRaises(ValueError):
                module.export_tree(tree, destination)


if __name__ == "__main__":
    unittest.main()
