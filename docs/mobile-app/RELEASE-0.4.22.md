# Blink Android 0.4.22

Android version code 29. The effort level name rolls on a slot-machine reel
(source 6afb8ae6). All level names sit on a vertical reel that springs to the
selected level: a higher level drops in from the top, a lower one rises from
below, and a fast swipe spins through every level in between. Names blur with
the reel's speed, compress and fade toward the window edges, and are sharp at
rest (about 150 ms after a change). Only the settled name is exposed to
accessibility. Reduced motion snaps.

The release was built from committed source in a clean sparse worktree with
only the version bump applied.

The owner-distributed APK is `mobile-app/build/Blink-0.4.22.apk`
(7167710 bytes), signed with the same certificate as 0.4.18–0.4.21
(`dd6356d2…ff89a3`).

SHA-256: `a4949e129eb6300d34f8902af8ad28bc93c4c7befd422b5c7b17d292f760ea4d`.

Published to the stable in-app update channel on 2026-10-26 with
`scripts/mobile_publish.sh --restart --public` (zero active mobile jobs). Live
metadata offers code 29 to code 28 on stable and beta and reports code 29 as
current; both download routes serve bytes matching the checksum above.

## Verification

- 332 shared unit tests pass.
- 153 of 157 Android instrumentation tests pass on a Pixel 8 API 35 emulator,
  with the same four pre-existing failures; `FileWorkspaceIntegrationTest` was
  excluded for the emulator WebView crash recorded in 0.4.21.
- A new motion-on test drags the slider, saves a filmstrip of the reel (reviewed
  frame by frame), checks the reel comes to rest, and that cancelling restores
  the saved level.
- The R8 release APK installs in place over 0.4.21 and cold-starts without a
  crash.
