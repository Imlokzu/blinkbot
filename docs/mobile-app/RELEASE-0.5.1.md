# Blink Android 0.5.1

Android version code 31. Sending reads as one continuous motion (source 1fafe83e).

- The send button shrinks and hops, then flies on a curve into the new
  message's slot, widening and morphing from a circle into the bubble's shape
  and colour with velocity motion blur. It lands on the real bubble, whose text
  then comes into focus. Stop rises from below into the button's place. The
  flight takes about 0.55 s.
- The message is shown as soon as Send is tapped instead of after the host
  acknowledges it, which used to add a network round trip before anything
  appeared. A refused send (4xx) removes the bubble as its text returns to the
  composer; a network failure keeps it queued for retry, as before.
- The typing dots no longer pop in 850 ms later. They bud from their tail corner
  300 ms after a send at the earliest, once the flight has landed, and also show
  while the send is still being acknowledged.

Reduced motion skips the flight. The release was built from committed source in
a clean sparse worktree with only the version bump applied.

The owner-distributed APK is `mobile-app/build/Blink-0.5.1.apk`
(7716288 bytes), signed with the same certificate as 0.4.18–0.5.0
(`dd6356d2…ff89a3`).

SHA-256: `e794e52a80e9ece9048d14aa4f72492168c25bfd0bfa95de93d500867eb9e98f`.

Published to the stable in-app update channel on 2026-10-26 with
`scripts/mobile_publish.sh --restart --public` (zero active mobile jobs). Live
metadata offers code 31 to code 30 on stable and beta and reports code 31 as
current; both download routes serve bytes matching the checksum above.

## Verification

- 332 shared unit tests pass.
- 155 of 159 Android instrumentation tests pass on a Pixel 8 API 35 emulator,
  with the same four pre-existing failures; `FileWorkspaceIntegrationTest` was
  excluded for the emulator WebView crash recorded in 0.4.21.
- Two tests were updated for the new contract: the typed-send test now checks
  that the bubble appears over Compose frames without jumping (it previously
  checked a scale entrance), and the held-provider test expects the dots after
  300 ms instead of 850 ms.
- A new motion test taps Send, saves a 44-frame filmstrip (reviewed), checks
  Stop and the dots, and that nothing from the flight stays drawn.
- The R8 release APK installs in place over 0.5.0 and cold-starts without a
  crash. The flight on a real paired device was not observed.
