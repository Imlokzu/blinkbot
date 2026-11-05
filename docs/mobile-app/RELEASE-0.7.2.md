# Blink Android 0.7.2

Android version code 36. Profile now exposes one Language navigation row
below the main settings. System, Ukrainian and English choices open on a
separate Language page. Selection applies immediately and persists locally;
toolbar and native Back return to Profile. Existing translations are reused.

Built from committed source `5623830f` in a clean detached sparse worktree;
unrelated checkout drafts are excluded from the APK. Debug/instrumentation
build and 358 shared / 78 Android tests pass. Three native tests cover
persisted Ukrainian/system language changes, both Back paths, rapid settings
navigation and reduced motion. Profile and Language screenshots were inspected.
Gemini independently approved the source diff; Fable was unavailable.
Isolated actual-app curl smoke passes including traversal HTTP 400 and static
assets, with lifespan disabled, no providers and the temporary server stopped.
Physical-device installation and iOS runtime checks are not claimed.

## Stable publication

Published through `scripts/mobile_publish.sh --restart --public` with zero
running/stopping mobile jobs. Release APK is 7,734,676 bytes;
SHA-256: `629cb1fe0b12b6a3a1ef5ebe87c55c981f281312e427ef44b284a47fc2c32fed`. Signature matches the preceding stable
release. APK and R8 mapping are archived in ignored `mobile-app/build/`.
The preceding 0.7.1 artifact remains available for rollback.

Clean release build and its 436 unit tests pass. Another 61 backend update
platform/release workflow checks pass. Stable offers code 36 to code 35,
code 36 is current, and beta falls back to stable. Local download bytes
match size/digest. Public unauthenticated endpoints return 401; no public
credential or physical phone installation was used. Live dashboard/assets
return 200 and workspace traversal returns 400. The host restart uses the
existing shared-checkout service, preserving its previous runtime setup.
