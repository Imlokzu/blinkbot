# Blink Android 0.4.18

Android version code 25. The effort picker again shows the selected model's
provider logo before its name, using the same themed artwork as the model list.
The model list reserves a fixed trailing checkmark slot for every row so names
and intelligence scores stay aligned when selection changes.

The existing compact effort layout, slider gestures, model search, unavailable
states, text truncation and accessibility selection semantics remain intact.
The application ID and signing certificate match the existing installed app.

The release is built from committed source plus these focused edits, excluding
unrelated workspace drafts. The owner-distributed APK is
`mobile-app/build/Blink-0.4.18.apk` (7167710 bytes).

SHA-256: `ca546c7ff00f987161aa7c44772beca42e6ff80e44613654da01d5b126fa12de`.

Validation and stable-channel publication results are recorded in HANDOFF.md.

## Verification

The release build, 332 shared tests, 78 Android tests and nine native Compose
picker tests passed. The focused UI checks cover logo paint and provider
switching, label/gauge coordinates across selection changes, 320dp layouts,
large Ukrainian text, gestures, keyboard control and cancellation. Screenshots
were inspected in both themes. Independent review approved the changes.

Post-review isolated server smoke passed 37 HTTP checks, including update
metadata, downloaded APK integrity and traversal rejection. All 205 bundled
workspace-editor assets match their manifest checksums. Tests used the shared
UI on the desktop JVM; no Android device/emulator execution is claimed.
