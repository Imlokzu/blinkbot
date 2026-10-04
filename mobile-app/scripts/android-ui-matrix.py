#!/usr/bin/env python3
"""Exercise the installed fixture APKs at real Android display/font settings.

Emulators only. Does not build, start servers, or connect to a real bot. Restores
the original display settings on exit, including failed instrumentation runs.
"""

import argparse
import json
import re
import shutil
import subprocess
import time
from pathlib import Path
from uuid import uuid4


CASES = {
    "phone": ("1080x2400", "420", "1.0"),
    "compact": ("720x1280", "320", "1.6"),
    "small": ("640x1136", "320", "2.0"),
    "landscape": ("1280x720", "320", "1.0"),
}
CLASS = "me.waveio.claudebot.MobileUiTest"
ADAPTIVE_TESTS = [
    "largeTextModelSearchAndEffortRemainReachable",
    "largeTextCalendarNumbersAndActionsRemainReadable",
    "largeUkrainianAttachmentMenuKeepsAllActionsReachable",
    "catalogLoadingAndEmptySearchHaveVisibleFeedback",
    "longModelNameKeepsPickerAndEffortActionsAvailable",
    "ukrainianConnectionAndWallpaperActionsFitLargeText",
]
REMOTE = "/sdcard/Android/data/me.waveio.claudebot/files/ui-qa"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--cases", nargs="+", choices=CASES, default=list(CASES))
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use a dedicated emulator, never a physical phone.")
    if any((args.output / name).exists() for name in args.cases):
        parser.error("Use a fresh output directory; existing screenshots must not be mixed with this run.")
    args.output.mkdir(parents=True, exist_ok=True)

    def adb(*command, timeout=30):
        return subprocess.check_output([args.adb, "-s", args.serial, *command], text=True, timeout=timeout).strip()

    def override(kind):
        match = re.search(r"Override [^:]+: (.+)", adb("shell", "wm", kind))
        return match.group(1) if match else "reset"

    if shutil.which("osascript"):
        subprocess.run(["osascript", "-e", "set volume output muted true"], check=True)
    original = (override("size"), override("density"), adb("shell", "settings", "get", "system", "font_scale"))
    results = []
    cleanup_errors = []
    run_id = uuid4().hex
    try:
        for name in args.cases:
            size, density, scale = CASES[name]
            remote_case = f"{name}-{run_id}"
            adb("shell", "wm", "size", size)
            adb("shell", "wm", "density", density)
            adb("shell", "settings", "put", "system", "font_scale", scale)
            # Let Android apply configuration changes before ActivityScenario starts.
            time.sleep(1)
            tests = CLASS if name == "phone" else ",".join(f"{CLASS}#{method}" for method in ADAPTIVE_TESTS)
            print(f"Running {name}: {size}, density {density}, font scale {scale}", flush=True)
            error = None
            try:
                log = adb("shell", "am", "instrument", "-w", "-r", "-e", "class", tests,
                          "-e", "screenshotDir", remote_case,
                          "me.waveio.claudebot.test/androidx.test.runner.AndroidJUnitRunner", timeout=300)
            except (subprocess.SubprocessError, OSError) as failure:
                partial = getattr(failure, "output", None) or ""
                log = partial.decode("utf-8", errors="replace") if isinstance(partial, bytes) else partial
                error = str(failure)
            (args.output / f"{name}.log").write_text(log)
            match = re.search(r"OK \((\d+) tests?\)", log)
            expected = 13 if name == "phone" else len(ADAPTIVE_TESTS)
            passed = int(match.group(1)) if match else 0
            results.append({"case": name, "pixels": size, "density": int(density), "font_scale": float(scale),
                            "expected": expected, "passed": passed, "success": passed == expected and error is None,
                            "error": error})
            (args.output / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
            if error is not None:
                break
            try:
                adb("pull", f"{REMOTE}/{remote_case}", str(args.output / name))
            except (subprocess.SubprocessError, OSError) as failure:
                results[-1].update(success=False, error=str(failure))
                (args.output / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
            print(f"{name}: {passed}/{expected} passed", flush=True)
    finally:
        # Instrumentation may continue in the device after a host-side timeout.
        commands = [
            ("shell", "am", "force-stop", "me.waveio.claudebot"),
            ("shell", "wm", "size", original[0]),
            ("shell", "wm", "density", original[1]),
            ("shell", "settings", "delete", "system", "font_scale") if original[2] == "null" else
            ("shell", "settings", "put", "system", "font_scale", original[2]),
        ]
        for command in commands:
            try:
                adb(*command)
            except (subprocess.SubprocessError, OSError) as failure:
                cleanup_errors.append(str(failure))
        (args.output / "cleanup.json").write_text(json.dumps({"errors": cleanup_errors}, indent=2) + "\n")
    return 0 if not cleanup_errors and all(result["success"] for result in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
