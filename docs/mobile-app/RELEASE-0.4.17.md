# Blink Android 0.4.17

Android version code 24. This release uses the approved orange-and-white cube
on the rounded charcoal tile in the app, with the corresponding adaptive
launcher icon. Settings identifies the application as Blink in both languages,
even when the server retains an old or customized bot name. Personalization
continues to display, edit and save that bot name independently.

The APK is built from committed source plus this focused change, excluding
unrelated local drafts. The existing application ID and signing identity are
preserved so installed copies can update in place. This remains the established
owner-distributed Android build, not a store release.

## Validation

- Optimized Android release build passed; 332 shared and 78 Android unit tests
  passed. A disk-space failure was resolved by clearing disposable build caches
  and retrying with a single worker and a 2 GiB Gradle heap.
- Two shared Compose UI tests passed on the desktop runtime, covering English
  light and Ukrainian dark themes, legacy/custom names, Settings and Updates
  branding, and personalization edit/save. Twelve native screenshots were saved
  and representative Settings/Updates captures were visually inspected.
- APK identity, version 0.4.17 / code 24, existing signing-certificate match,
  packaged logo bytes and both localized product names were verified.
- All 205 bundled workspace-editor files match their manifest checksums.
- Updated the launcher regression for the approved orange/white cube; Android
  instrumentation sources compile successfully.
- Post-review isolated actual-app smoke passed 37 HTTP checks, including update
  availability, the downloaded APK checksum and traversal rejection (HTTP 400).
  Its temporary server stopped.
- Device/emulator instrumentation was not run because local disk space is
  constrained; desktop Compose checks are not claimed as Android device tests.

Publication and download-integrity results are recorded in HANDOFF.md.

## Artifact

`mobile-app/build/Blink-0.4.17.apk` (7167710 bytes).

SHA-256: `5174eafb913fa5021a980fd796163567010d93dcec284eb50a1a5b62a4bfc308`.
