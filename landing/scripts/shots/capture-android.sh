#!/usr/bin/env bash
# Capture the real Kotlin Multiplatform Android app with an English fixture.
# Usage: ./capture-android.sh emulator-5580
# Requires a dedicated running emulator, Android SDK/JDK17, cwebp and python3.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../../.." && pwd)"
CAPTURE_SERIAL="${1:?pass a dedicated emulator serial}"
CAPTURE_ADB="${CAPTURE_ADB:-adb}"
case "$CAPTURE_SERIAL" in emulator-[0-9]*) ;; *) echo 'Use a dedicated emulator, never a physical phone.' >&2; exit 1 ;; esac
ab() { "$CAPTURE_ADB" -s "$CAPTURE_SERIAL" "$@"; }
if [[ "$(ab shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo 'The capture target must be an Android emulator.' >&2; exit 1
fi
osascript -e 'set volume output muted true' 2>/dev/null || true
CAPTURE_SIZE="$(ab shell wm size | sed -n 's/Override size: //p' | tr -d '\r')"
CAPTURE_DENSITY="$(ab shell wm density | sed -n 's/Override density: //p' | tr -d '\r')"
CAPTURE_FONT="$(ab shell settings get system font_scale | tr -d '\r')"
cleanup() {
  ab shell am force-stop me.waveio.claudebot >/dev/null 2>&1 || true
  ab shell wm size "${CAPTURE_SIZE:-reset}" >/dev/null 2>&1 || true
  ab shell wm density "${CAPTURE_DENSITY:-reset}" >/dev/null 2>&1 || true
  if [[ "$CAPTURE_FONT" == null ]]; then ab shell settings delete system font_scale >/dev/null 2>&1 || true
  else ab shell settings put system font_scale "$CAPTURE_FONT" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
# Match the landing's 390x844 CSS frame at 3x without stretching native pixels.
ab shell wm size 1170x2532
ab shell wm density 480
ab shell settings put system font_scale 1.0
(cd "$REPO/mobile-app" && ./gradlew :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest)
ab install -r "$REPO/mobile-app/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
ab install -r "$REPO/mobile-app/androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk"
mkdir -p "$HERE/raw" "$REPO/landing/public/shots/en"
CAPTURE_REMOTE=/sdcard/Android/data/me.waveio.claudebot/files/landing-capture/mobile.png
ab shell rm -f "$CAPTURE_REMOTE"
ab shell am instrument -w -r -e class me.waveio.claudebot.LandingScreenshotTest \
  me.waveio.claudebot.test/androidx.test.runner.AndroidJUnitRunner | tee "$HERE/raw/android-capture.log"
if ! grep -q 'OK (1 test)' "$HERE/raw/android-capture.log"; then
  echo 'Native capture failed; existing marketing images were not replaced.' >&2; exit 1
fi
ab pull "$CAPTURE_REMOTE" "$HERE/raw/en-native-mobile.png"
python3 - "$HERE/raw/en-native-mobile.png" <<'PY'
import struct, sys
with open(sys.argv[1], 'rb') as image:
    header = image.read(24)
assert header[:8] == b'\x89PNG\r\n\x1a\n', 'Expected a native PNG screenshot'
assert struct.unpack('>II', header[16:24]) == (1170, 2532), 'Unexpected capture dimensions'
PY
cwebp -quiet -q 82 -m 6 "$HERE/raw/en-native-mobile.png" -o "$REPO/landing/public/shots/en/mobile.webp"
cwebp -quiet -q 80 -m 6 -resize 585 1266 "$HERE/raw/en-native-mobile.png" -o "$REPO/landing/public/shots/en/mobile-half.webp"
printf '%s\n' 'Native Android capture written to landing/public/shots/en/mobile*.webp'
