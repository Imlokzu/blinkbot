"""Host-runner failures must preserve evidence and attempt every restoration."""

import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("ui_matrix", Path(__file__).with_name("android-ui-matrix.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class MatrixRunnerTests(unittest.TestCase):
    def exercise(self, *, timeout=False, cleanup_failure=False, existing=False):
        calls = []

        def adb(command, **kwargs):
            args = command[3:]
            calls.append(args)
            if args == ["shell", "wm", "size"]:
                return "Physical size: 1080x2400\nOverride size: 800x1200"
            if args == ["shell", "wm", "density"]:
                return "Physical density: 420\nOverride density: 300"
            if args == ["shell", "settings", "get", "system", "font_scale"]:
                return "1.3"
            if args[:3] == ["shell", "am", "instrument"]:
                if timeout:
                    raise subprocess.TimeoutExpired(command, 300, output=b"partial instrument output")
                return "OK (13 tests)"
            if cleanup_failure and args == ["shell", "wm", "size", "800x1200"]:
                raise subprocess.CalledProcessError(1, command)
            return ""

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            if existing:
                (output / "phone").mkdir()
            arguments = ["matrix", "--serial", "emulator-5556", "--adb", "adb",
                         "--output", temporary, "--cases", "phone"]
            with patch("sys.argv", arguments), patch.object(runner.subprocess, "check_output", side_effect=adb), \
                    patch.object(runner.shutil, "which", return_value=None), patch.object(runner.time, "sleep"):
                if existing:
                    with self.assertRaises(SystemExit) as error:
                        runner.main()
                    self.assertEqual(2, error.exception.code)
                    self.assertEqual([], calls)
                    return
                result = runner.main()
            self.assertIn(["shell", "wm", "size", "800x1200"], calls)
            self.assertIn(["shell", "wm", "density", "300"], calls)
            self.assertIn(["shell", "settings", "put", "system", "font_scale", "1.3"], calls)
            summary = json.loads((output / "summary.json").read_text())
            cleanup = json.loads((output / "cleanup.json").read_text())
            self.assertEqual(1 if timeout or cleanup_failure else 0, result)
            if timeout:
                self.assertEqual("partial instrument output", (output / "phone.log").read_text())
                self.assertFalse(summary[0]["success"])
                self.assertIn("timed out", summary[0]["error"])
            else:
                self.assertTrue(summary[0]["success"])
            self.assertEqual(1 if cleanup_failure else 0, len(cleanup["errors"]))
            return calls

    def test_success_restores_original_configuration(self):
        self.exercise()

    def test_timeout_keeps_partial_log_and_failed_summary(self):
        self.exercise(timeout=True)

    def test_one_restore_failure_does_not_skip_other_settings(self):
        self.exercise(cleanup_failure=True)

    def test_existing_artifacts_are_rejected_before_touching_device(self):
        self.exercise(existing=True)

    def test_remote_screenshots_are_isolated_from_previous_runs(self):
        directories = []
        for _ in range(2):
            calls = self.exercise()
            instrument = next(args for args in calls if args[:3] == ["shell", "am", "instrument"])
            directory = instrument[instrument.index("screenshotDir") + 1]
            self.assertRegex(directory, r"^phone-[a-f0-9]{32}$")
            pull = next(args for args in calls if args[0] == "pull")
            self.assertEqual(f"{runner.REMOTE}/{directory}", pull[1])
            directories.append(directory)
        self.assertNotEqual(*directories)


if __name__ == "__main__":
    unittest.main()
