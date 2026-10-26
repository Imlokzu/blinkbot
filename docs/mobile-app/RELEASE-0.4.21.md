# Blink Android 0.4.21

Android version code 28. Replies now read like typing (source 408e101f):

- Streamed text flows. Gateway snapshots arrive in large chunks, which used to
  appear as blocks with only a 16-character blurred tail. Each appended chunk is
  now spread evenly over the frames before a 280 ms deadline that later chunks
  speed up rather than extend, and blurs in as an age gradient. Bursts beyond
  480 characters show their older part at once. Replies over 1024 characters
  step about every 48 ms; tables and replies over 4096 characters keep the
  previous immediate behaviour.
- The typing pill becomes the reply. A live bubble starts at the pill's exact
  size, widens to the right and grows down as its first words flow in, and keeps
  easing to each new size, so line wraps never jump. The dots are nudged aside
  by the first words and fade. A blank live reply shows the dots inside its own
  bubble, so pill and reply share one position. Tables do not animate their size.
- New paragraphs that appear mid-stream blur in from their first glyph.

Reduced motion disables all of it. The release was built from committed source
in a clean sparse worktree with only the version bump applied.

The owner-distributed APK is `mobile-app/build/Blink-0.4.21.apk`
(7167710 bytes), signed with the same certificate as 0.4.18–0.4.20
(`dd6356d2…ff89a3`).

SHA-256: `b8385b63c736a404e3f0bff1aaa5b8eb6ffd248a45ec9a5e8f8424d732d0da42`.

## Verification

- 332 shared unit tests pass. Two reveal tests were updated for the new
  contract: a burst may now leave up to 480 characters flowing, and the
  "large document" fixture exceeds the new 4096-character threshold.
- 152 of 156 Android instrumentation tests pass on a Pixel 8 API 35 emulator,
  with the same four pre-existing failures. `FileWorkspaceIntegrationTest` was
  run separately: a native crash inside the system WebView (Chromium SIGSEGV)
  kills the process. The committed 0.4.20 source crashes the same way (4 of 4
  runs of the affected test, versus 1 of 4 for this source), so it is an
  emulator WebView fault, not this change.
- New checks: a blank live reply opens exactly where the dots were, at the
  pill's size, and widens; filmstrips of the growth and a second streamed chunk
  were reviewed frame by frame.
- Streamed-table frame costs: median 34 ms, p95 67 ms, worst 84–101 ms, the same
  as 0.4.20 on the same emulator. Pacing tables and animating a growing table's
  size had raised the worst frame to about 250 ms, hence both exclusions.
- The R8 release APK installs in place over 0.4.20 and cold-starts without a
  crash. Its chat screen was not exercised on a device (it needs pairing).
