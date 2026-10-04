# Mobile visual and adaptive-layout verification

The 0.2.1 follow-up preserves the 0.2.0 palette, typography, icons and custom
controls. Screenshots of that implementation and the approved product decisions
are the visual baseline. There is no fixed Figma target or pixel-diff similarity
score; these checks verify actual rendering, readable text and reachable actions.

## Reproduce

Use a dedicated Android emulator with host GPU rendering. The test host is Ktor
MockEngine and the OS bridge is silent. No real microphone, provider request,
production message or backend process is needed.

Build from an isolated source snapshot when other agents are editing the shared
worktree: another Git hook may temporarily stash tracked files during compilation.
With JDK 17 and SDK 36 configured, run from `mobile-app`:

```sh
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest \
  :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
adb -s emulator-5556 install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s emulator-5556 install -r androidApp/build/outputs/apk/androidTest/debug/androidApp-debug-androidTest.apk
python3 scripts/android-ui-matrix.py --serial emulator-5556 --output /tmp/claude-mobile-ui
```

The runner requires an emulator serial and a fresh output directory, mutes the
Mac, changes actual Android display/font settings, and captures logs/screenshots.
It retains partial timeout output, attempts each restoration independently in
`finally`, and records restoration errors in `cleanup.json`. Four host-side unit
tests cover these failure paths (`python3 -m unittest discover -s scripts`).
`--cases phone small` selects a subset. Do not use the
emulator interactively while the runner owns its display configuration.

| Case | Pixels / density | Logical size | System font scale | Scenarios |
| --- | --- | --- | --- | --- |
| Phone | 1080 x 2400 / 420 | Approximately 411 x 914 dp | 100% | 13 |
| Compact | 720 x 1280 / 320 | 360 x 640 dp | 160% | 6 |
| Small | 640 x 1136 / 320 | 320 x 568 dp | 200% | 6 |
| Landscape | 1280 x 720 / 320 | 640 x 360 dp | 100% | 6 |

Using an overridden Compose `LocalDensity` alone is insufficient: separate popup
and dialog windows can retain the platform configuration. The matrix therefore
sets Android's `font_scale` before launching each instrumentation session.

## Regressions exercised

- QR pairing remains reachable when enlarged connection instructions need scrolling.
- Model search retains visible results above the keyboard. Landscape places
  search and results alongside each other without moving focus when the IME opens.
- Long model names preserve the menu, new-chat, effort and Done controls.
- A delayed catalog shows loading feedback; an empty search shows a distinct result.
- All six attachment actions remain reachable by scrolling on short windows.
- Calendar digits stay on one line, weekday labels remain compact, and time
  controls stack when enlarged numbers need more width. Each enabled date is
  clicked and its selected state verified, including adjacent cells.
- Ukrainian wallpaper actions wrap onto separate rows before a button disappears.
- System icons switch to dark on plain light surfaces and back for dark themes.

The normal-phone suite also retains seven existing flows: streamed output before
completion, dictation, attachments/skills/message actions, scheduling, model/effort
search with the keyboard, drawer navigation/files/themes, and paced drawer motion.

Text checks inspect actual line extents and truncation. `hasVisualOverflow` alone
can be misleading in semantics: a paragraph can retain its loose layout width
while its surrounding Text node measures to the shorter glyph width. Screenshots
are reviewed separately; passing semantic checks does not certify visual quality.

## Scope

The matrix has 31 successful scenario executions across four configurations.
Shared tests: 85 passed; Android unit tests: 62 passed. API 35, debug build, host
GPU emulator. Native platform instrumentation had 44 passing cases in the 0.2.0
baseline; this follow-up does not recertify every native boundary.

This is not a full accessibility certification. Calendar cells remain a dense
seven-column control; date-center selection is tested, not every possible touch
location. iOS compilation/runtime, physical phones, store builds, and real
provider/Cloudflare streaming remain outside this visual fixture run.
