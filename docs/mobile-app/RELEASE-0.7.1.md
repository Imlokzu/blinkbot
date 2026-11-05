# Blink Android 0.7.1

Android version code 35. Published to the stable in-app update channel on
2026-11-05 using `scripts/mobile_publish.sh --restart --public`.

## Fixes

- Top and bottom chat blur samples the complete wallpaper/message/media area,
  including empty chats and margins outside the reading column.
- Light-theme edges use the warm background tint instead of a black veil.
  Live theme changes refresh the tint; controls remain sharp.

## Build and verification

Built from committed source `56a43046` in a clean detached sparse worktree.
Unrelated local backend and mobile drafts are not included in the APK.
The existing host service was restarted through the standard publisher; its
working directory remains the shared checkout, including existing backend drafts.

Artifact: `mobile-app/build/Blink-0.7.1.apk`, 7,734,676 bytes. Embedded package
metadata reports 0.7.1 / 35. Its signing certificate matches 0.7.0:
`dd6356d2b3eeeb96506d7ca10944d6ffe5460a40959736eee2091947ebff89a3`.

SHA-256: `9eba9b31a6ceba0eb937402d9f84a9f2108a20479d4855bd38a872f5a2720a0f`.
R8 mapping is archived locally as `mobile-app/build/Blink-0.7.1-mapping.txt`.
The previous 0.7.0 runtime artifact remains available for rollback.

Clean-worktree build and all 358 shared / 78 Android unit tests pass.
Earlier native instrumentation passed four rendering/history tests, including
light/dark/light switching, spatial smoothing, panel growth and both themes;
phone and tablet screenshots were inspected. Gemini reviewed the theme fix
and release checklist. Fable was unavailable. No physical-device or iOS
runtime check is claimed.

The existing checkout passes 87 backend update/API/release-workflow tests.
There were zero running/stopping mobile jobs before publication. Live stable
metadata offers code 35 to code 34, reports code 35 as current, and beta falls
back to stable. Local download bytes, size, digest and no-store header match.
Public capabilities and download return 401 without a device credential;
no authenticated public download or physical phone installation is claimed.
Dashboard and static assets return 200; workspace traversal returns 400.
