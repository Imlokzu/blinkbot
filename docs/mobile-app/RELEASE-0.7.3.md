# Blink Android 0.7.3

Android version code 37; published to stable on 2026-11-06. The dashboard
was published from the same committed effort sources.

## Correct effort choices

Both clients use only the selected provider/model/runtime's gateway-declared
`efforts` and resolved `effort_default`. They no longer insert Automatic or
borrow the entire OpenClaw schema enum. Unknown and off-only profiles expose
no adjustable slider. Saved unsupported values migrate only to a verified
model default; neither client guesses a first level.

The backend rejects unsupported/stale model writes, serializes model/effort
changes, and invalidates in-flight profile snapshots after a setting write.
Inherited mobile image routing omits the text model's resolved effort.

The live gateway currently confirms Low, Medium, High, XHigh, Max and Ultra
for `openai/gpt-6.1-sol`. Other configured models currently report missing or
off-only profiles; their controls show unavailable. This release does not
claim those models lack reasoning or silently rewrite gateway configuration.

## Build and delivery evidence

Android and dashboard sources were built from clean committed `17a35cb1`.
Backend cache-race follow-up: `8a8bc910`. Unrelated checkout drafts were
excluded from both client artifacts. The existing backend service still
runs from the shared checkout and was restarted through the standard publisher.

The signed APK is 7,734,688 bytes. SHA-256:
`d8cf406f0e12db83b438ed553e543c4cc601ce2c9770eafd2cb887b5e5b8c6fb`. Its certificate matches earlier stable APKs.
APK and R8 mapping are archived in ignored `mobile-app/build/`.

Clean Android release and 362 shared / 78 Android unit tests pass. Dashboard
249 tests, clean TypeScript and production build pass. The Python matrix
passed 90 checks; later serialized-write and cache-read regression checks
also pass. Independent Gemini/native reviews found image-routing and cache/
write races that were fixed and covered by unit tests. Fable was unavailable.

No simulator or browser runtime QA was completed for this release, following
the owner's explicit instruction. Earlier attempted browser checks did not
pass and supply no UI evidence. Physical-device and iOS checks are not claimed.
Gradle was stopped after the build.

Stable offers code 37 to code 36 and reports 37 as current. Downloaded bytes
match the signed APK's size/digest. Live model metadata is model-scoped;
the dashboard index and ChatPanel chunk match the clean artifact. Local
static routes return 200 and workspace traversal returns 400. Public routes
require authentication (401 without a device token). No live provider turn
or physical phone installation was performed. Publication began with zero
running/stopping mobile jobs; previous APKs remain available for rollback.
