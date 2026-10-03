# Mobile implementation architecture

Status: implementation in progress, 2026-10-03. The owner has now requested
implementation of the application. Routine layout parameters may be refined
within the agreed reference; the product behavior in [DESIGN.md](DESIGN.md)
remains binding.

## Runtime boundaries

- A shared Kotlin module owns Compose screens, application state, transport
  models, and the HTTP API client. Android and iOS retain native integration
  for camera/QR, media/document selection, recording, secure storage, haptics,
  notifications, and lifecycle events.
- Ktor uses OkHttp on Android and Darwin on iOS. The configured API host is
  the only destination for application service requests. Bundle visual assets.
- The existing host owns identity, conversations, workspace files, model
  routing, durable work, and scheduled sends. Mobile adapters extend that
  service when required; there is no independent mobile conversation backend.
- Device appearance, drafts, and pending delivery are persisted locally.
  Authorization material belongs in platform-protected storage.
- Preserve the existing backend owner/session mapping, including local-owner
  operation. Mobile pairing must not create a different account namespace.

## Build baseline

Pin Kotlin 2.3.21, Compose Multiplatform 1.10.3, AGP 8.11.1, Gradle 8.14.3,
and JDK 17. This uses a verified compatible toolchain already available on the
host rather than changing every installed build tool during app creation.
The Android minimum is API 26, compile/target API 36. Native targets include
arm64 devices and Apple Silicon simulators.

`androidTarget` with AGP 8.x remains supported in this pinned combination;
the newer Android KMP library plugin is a future build migration, not a
reason to combine an AGP upgrade with this product implementation.

The initial Android debug build passed. Full Xcode is missing on the current
Mac, so Apple compilation and device/simulator behavior are not yet verified.

## State and correctness

- Address every operation by backend conversation identity. Late stream events,
  upload completions, and saves must not attach themselves to the current screen.
- Keep requested model separate from effective answering model. Use backend
  provider metadata to enforce same-provider fallback.
- Keep queue, steering, cancellation, and scheduled sends as distinct operations.
  Stop never means execute the next item automatically.
- File autosave retains newer local edits until their matching write is
  acknowledged. Failed writes retain recovery data.
- A host-accepted schedule and a local offline submission have different states.
  Never report local queue persistence as successful server scheduling.

## Validation

Compile the Android app, run shared state/transport tests, exercise an Android
emulator, and inspect actual screenshots. Test service extensions independently
with isolated data before integration. Preserve existing staged backend edits.
Apple verification remains explicitly pending until a full Xcode SDK is available.

Sources: [KMP compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html),
[Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html),
[Ktor multiplatform client](https://ktor.io/docs/client-create-multiplatform-application.html).
