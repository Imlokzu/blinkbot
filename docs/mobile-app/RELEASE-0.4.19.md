# Blink Android 0.4.19

Android version code 26. The chat gains strong blur and spring motion:

- A sent bubble springs up out of the composer from a 14dp blur and settles
  with a slight overshoot. Replies and the typing indicator condense out of a
  20dp blur; the indicator's dots hop in a travelling wave.
- Streamed text comes into focus as an age gradient. Each appended chunk keeps
  its own age, grouped into at most four blurred bands, so earlier glyphs keep
  sharpening instead of snapping into focus when the next chunk lands. The
  fade lasts 460 ms and then the text goes completely idle.
- Typed characters and live dictation in the composer use the same effect.
  Sent text lifts out of the field and dissolves while its bubble rises.
- The send button pops with a spring when it becomes enabled; its arrow
  shoots out through the top on send and springs back from below.
- Chat items glide (animateItem placement) when content above them grows.

Reduced motion disables all of it. Source: 5d9c08be, e19ffe4e and the
version bump. The release was built from committed source in a clean sparse
worktree, excluding unrelated workspace drafts.

The owner-distributed APK is `mobile-app/build/Blink-0.4.19.apk`
(7167706 bytes), signed with the same certificate as 0.4.18
(`dd6356d2…ff89a3`).

SHA-256: `dd6fa8e811d1b424a4452d13f3377203f3407afad55bf2b556daede551634bff`.

Published to the stable in-app update channel on 2026-10-26 with
`scripts/mobile_publish.sh --restart --public` (zero active mobile jobs). Live
metadata offers code 26 to code 25 on both stable and beta (beta has no
separate release and falls back to stable) and reports code 26 as current.
Both download routes serve bytes matching the checksum above; the dashboard
answers 200 and the public endpoint 401 without a token. Before the restart,
80 backend memory, profile and mobile API tests passed against the working
tree the service now runs, including the owner's uncommitted edits.

## Verification

- 332 shared unit tests pass; Android and iOS simulator compilation succeed.
- 156 of 160 Android instrumentation tests pass on a Pixel 8 API 35 emulator.
  The four failures reproduce identically on the pre-change source
  (6eedf51d): two tablet-layout tests on a phone-sized device, the React
  preview fixture that was not built, and the generated-workspace-file preview.
- `StreamingTailRenderingTest` asserts that only the fresh suffix changes and
  that it settles; its settle wait now exceeds the longer fade.
- Streamed-table frame costs on the same debug emulator: median 34 ms and p95
  67 ms, the same as before; the worst frame rose from 67–84 ms to 100–117 ms.
- The R8 release APK installs in place over 0.4.18 and cold-starts without a
  crash. Its chat screen was not exercised on a device (it needs pairing).
- The desktop target does not compile because of `DesktopBrowserSurface.kt`
  (JCEF); this predates the change.
