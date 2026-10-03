# Mobile implementation architecture

Status: Android implementation validated, iOS device validation pending, 2026-10-03. The owner has now requested
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


## Implementation validation (2026-10-03)

The delivered code includes shared Compose UI and state, device pairing and
stream transport, native Android integrations with WorkManager, and a SwiftUI
iOS host. Android debug assembly, 69 shared tests, 62 Android unit tests,
32 native instrumentation cases and two Compose/controller UI flows passed.
The screen flows use deterministic service responses, not paid model calls.

The app's foreground outbox and Android background worker share an atomic
preference transaction. A three-way snapshot merge preserves worker receipts,
permanent failures and newly queued items while allowing explicit cancellation
and retry. Credential epochs and per-request generations reject stale work from
an old device/connection. A foreground refusal to queue is retained through
backgrounding.

The host adapter has durable SQLite jobs, replay cursors, session turn leases,
revision-aware workspace writes, model overrides, and shared-history forks.
Same-provider fallback happens only before observed output/tool work; persisted
assistant records retain the effective model. The gateway's exported API does
not guarantee strict steering, so the capability remains false. APNs/FCM remote
push is unconfigured. iOS outbox retry currently requires a foreground opportunity.
Full Apple compilation remains unverified because this Mac lacks full Xcode.


## Custom interface and live reply refinement (0.2.0)

The mobile interface now uses custom Foundation-based controls, a solid model
panel with an effort page, opaque message bubbles, and a chat-foreground drawer.
Manrope and a Lora-derived greeting font are bundled with license notices. ASR
partials render inside the composer; only explicit Stop/Use text commits the
transcription to the draft. Attachment tiles open native pickers, while the
Skills destination reads the existing host catalog and inserts a real reference.

The gateway's replaceable assistant HTTP output can wait for finalization.
Mobile opts into its real WebSocket answer snapshots, replacing cumulative
answer text and suppressing duplicate HTTP prefixes. HTTP completion remains
authoritative. Notes and tool activity retain separate timeline positions.
Transport error frames and premature EOF now preserve failure/queue-pause state.
A per-session stream revision stops stale list responses from clearing Stop;
opening a conversation reconciles a completion that raced its history read.

Wallpaper blur uses cached local image layers and masks. They avoid tracking
window coordinates as the foreground chat moves. Transition blur effects are
reused in a few steps, then removed when the animation settles. Reduced-motion
mode removes transition clocks, including waiting-dot loops. Fonts/typography
are retained across reply updates. Performance comparisons are emulator/debug
measurements, not a guarantee for every physical phone.
