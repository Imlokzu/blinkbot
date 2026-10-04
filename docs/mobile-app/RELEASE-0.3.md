# Claude Bot Mobile 0.3.0

Android version code 4. This release addresses the phone's reported streaming,
image-delivery, table, navigation, message-action and performance problems.

## Proven causes and changes

- Native gateway assistant events without `replaceable=true` were discarded by
  the mobile preview bridge. They now produce cumulative live snapshots. The
  backend reconciles HTTP-first and WebSocket-first ordering without duplicating
  output, and final HTTP completion remains authoritative.
- Upload and submission succeeded for the reported image turns, but routing then
  rejected the selected text-model candidate. An inherited image selection now
  uses the configured gateway image model; an explicit model remains explicit.
  Missing vision metadata remains unknown. Capability failures have their own
  localized message instead of appearing as a network failure. Manual retry,
  edit and regenerate preserve image intent and attachment manifests.
- The previous Markdown table component defaulted to one line with ellipsis.
  Cells now wrap completely, the table scrolls horizontally, and unchanged cells
  reuse their inline formatting as new rows arrive. Existing parsed content stays
  visible during asynchronous updates. Following responds to measured content
  growth and yields to a real user drag.
- Human reactions persist per assistant bubble; bot reactions appear on user
  messages. Notes retain their separate identity and original bubble indices.
- Message actions use a compact custom popup. Send options preserve the current
  keyboard and draft; Back dismisses nonfocusable menus explicitly. Platform press
  rectangles are replaced by the custom controls' motion.
- The drawer opens from inside the content, beyond Android's system-back edge.
  Conversations are grouped by local day and support rename/delete via long press.
  Deletion refuses chats with pending work. Manual names survive delayed automatic
  title generation.
- Camera/photos/files appear in a full-width attachment surface. Uploaded and
  restored images have authenticated thumbnails, with an account-scoped bounded
  in-memory cache. Tool names match the dashboard's localized labels; full tool
  details can be opened instead of being permanently truncated.

## Verification and reproduction

Shared state/data tests: 131 passed. Android unit tests: 62 passed. UI scenarios:
23 passed on the normal phone plus 18 across the compact/large-font/landscape
matrix (41 successful executions). QA runner tests: 5 passed. Backend
transport/routing/session tests: 62 passed. These include actual loopback TCP
HTTP/WebSocket streams gated before completion, native assistant envelopes,
image-only turns, byte-exact PNG and 8/10 MiB JPEG delivery, explicit oversized
rejection, reaction removal, rename races and error-code classification.

The Android interaction suite exercises the real Compose/controller code with
a deterministic host and a silent native bridge. It includes decoded image
pixels, multipart content, per-bubble reactions, actual IME visibility, gestures,
table text layout, note-only growth, manual reading position and streaming frames.
The adaptive display runner additionally checks 360x640 dp at 160% font scale,
320x568 dp at 200%, and 640x360 dp landscape.

```sh
./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest
./gradlew -PuiTestBuildType=benchmark :androidApp:assembleBenchmark \
  :androidApp:assembleBenchmarkAndroidTest
adb -s emulator-5556 install -r androidApp/build/outputs/apk/benchmark/androidApp-benchmark.apk
adb -s emulator-5556 install -r androidApp/build/outputs/apk/androidTest/benchmark/androidApp-benchmark-androidTest.apk
adb -s emulator-5556 shell am instrument -w -r \
  -e class me.waveio.claudebot.ChatInteractionRegressionTest,me.waveio.claudebot.MobileUiTest \
  me.waveio.claudebot.test/androidx.test.runner.AndroidJUnitRunner
python3 scripts/android-ui-matrix.py --serial emulator-5556 \
  --cases compact small landscape --output /tmp/claude-mobile-0.3-ui
./gradlew :androidApp:assembleRelease
```

Performance checks use a non-debuggable benchmark variant without R8 stripping:
the external Compose test APK needs APIs that R8 legitimately removes from the
application. The distributed release has R8/code-resource shrinking enabled and
is separately installed and launch-tested. Do not describe fixture UI tests as
tests of the exact minified release binary. AGP 8.13.2 includes Kotlin-2.3-compatible
R8; see the [Android release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

Frame measurements use an API 35 emulator with host graphics. A 60-update growing
table test records frame durations and verifies visible output while completion
is blocked. These are emulator observations, not proof of 120 Hz on a physical
phone. The final stream sample recorded 125 frames, median 18 ms, p95 23 ms and
maximum 34 ms; the drawer sample recorded 176 frames, median 17 ms and p95 23 ms.
Android 15+ receives a high frame-rate category request for the real app;
the operating system still controls the display and power policy.

## Host and remaining limits

The registered host was restarted only after a read-only count showed no running
or stopping mobile jobs. The tunnel configuration was unchanged. After restart,
the public API returned the expected JSON authentication response for an ordinary
OkHttp request. A read-only catalog check confirmed that the configured image
model is present, available and advertised as image-capable. No paid provider call or real production message was sent by QA.

The release retains the previous APK signing certificate and can update that
installation. This is the owner's development signing identity, not store signing.
iOS metadata is aligned, but full Xcode is unavailable and Apple runtime behavior
remains unverified. Prior limitations around remote push and strict Steer remain.
The earlier interrupted broad backend suite is not counted as passing here.
