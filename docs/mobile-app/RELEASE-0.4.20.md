# Blink Android 0.4.20

Android version code 27. Chat entrances move from slow, pale fades to motion
driven by speed (source 9d384816):

- A shutter-style motion blur smears moving content along its path in
  proportion to its on-screen velocity and is sharp the moment it stops.
- Sent bubbles are thrown up out of the composer on a fast spring with a slight
  overshoot, stretched a little while fast; opaque within 60 ms and settled by
  about 200 ms.
- Replies land from 16dp below out of a brief 5dp defocus and are visible from
  their first frame, so the typing indicator hands over without a gap. They are
  sharp by about 130 ms.
- Sent text accelerates out of the field and the send arrow fires with the same
  motion blur. Streamed and typed text sharpen over 320 ms instead of 460 ms.
- Fix: the typing indicator could stay drawn beneath the reply that replaced
  it (seen on a tablet in 0.4.19). It no longer uses a list fade-out, which was
  the only path that keeps a removed item drawn.

Reduced motion disables all of it. The release was built from committed source
in a clean sparse worktree with only the version bump applied.

The owner-distributed APK is `mobile-app/build/Blink-0.4.20.apk`
(7167710 bytes), signed with the same certificate as 0.4.18 and 0.4.19
(`dd6356d2…ff89a3`).

SHA-256: `a43d19f6bd7617f63e4567bdfc4ebdd5550f79d5201e01be1d533a49ae226a82`.

Published to the stable in-app update channel on 2026-10-26 with
`scripts/mobile_publish.sh --restart --public` (zero active mobile jobs). Live
metadata offers code 27 to code 26 on stable and beta and reports code 27 as
current; both download routes serve bytes matching the checksum above.

## Verification

- 332 shared unit tests pass; Android and iOS simulator compilation succeed.
- 159 of 163 Android instrumentation tests pass on a Pixel 8 API 35 emulator.
  The four failures are the same pre-existing ones recorded for 0.4.19.
- New motion-on checks in `MobileMotionTest`: after a reply replaces the typing
  indicator (including the empty-live-reply and new-chat final-reply paths),
  the chat is pixel-identical to a freshly opened one; a send settles quickly;
  a filmstrip of send, typing and reply frames is saved and was reviewed.
  The tablet artifact itself was not reproduced on the emulator.
- The R8 release APK installs in place over 0.4.19 and cold-starts without a
  crash. Its chat screen was not exercised on a device (it needs pairing).
