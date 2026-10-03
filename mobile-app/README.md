# Claude Bot Mobile

Kotlin Multiplatform / Compose client for the existing Claude Bot host. Android
builds and native UI flows are verified; the iOS host is included but still needs
full Xcode compilation and device testing.

## Connect a phone

1. Install the backend changes and Python requirements in `Virtual Bot`.
2. Route the owner's HTTPS API hostname through Cloudflare Tunnel to that host.
   Set `MOBILE_API_ORIGIN=https://your-api-host` in the host environment when it
   differs from `https://api-bot.waveio.me`. No Cloudflare token belongs in the app.
3. In the PC dashboard, open Settings / Devices / Connect your phone. Enter that
   HTTPS origin and create a QR. The code expires after five minutes and is
   single-use. The phone receives its own revocable device credential.
4. Install the Android debug APK and scan the QR. Revoke a lost phone from the
   same PC panel. Tokens are stored with Android Keystore or iOS Keychain.

The client does not call model providers directly. Conversations, workspace,
models and bot personalization come from the existing host and owner identity.
Backend contract and deployment assumptions: [MOBILE-API.md](../Virtual%20Bot/docs/MOBILE-API.md).

## Implemented behavior

- Messenger bubbles, real streamed tool activity, history/search, attachments,
  edit/regenerate forks, per-chat model/effort and new-chat defaults.
- Centered model picker, 65% gesture drawer, supplied half-screen wallpaper with
  progressive blur, custom images, per-screen scope, dimming, light/dark themes.
- Dictation with actual audio amplitude and incremental ASR; manual Stop inserts
  transcription into the composer. No TTS or automatic sending.
- Workspace browsing and revision-aware text/Markdown autosave, with retained
  local recovery when the remote file changes or saving fails.
- Durable server queue, explicit Stop/pause/resume, scheduled messages,
  idempotent delivery and event replay. Android WorkManager retries approved
  offline submissions under the operating system's scheduling limits.
- Ukrainian/English resources, system locale/theme defaults, localized native
  permission descriptions, reduced-motion handling and optional haptics.

## Current limits

- The installed gateway cannot guarantee updating the current task through its
  exported API. Steer is disabled by capability rather than emulated as another
  turn. Queue, Stop and scheduled sends work independently.
- Native local reply notifications exist. Remote APNs/FCM push from a closed app
  is not configured. iOS has no background outbox worker yet; retained pending
  submissions retry on the next permitted foreground opportunity.
- Full Xcode is absent on the development Mac. Swift source checks and portable
  audio/file checks passed; an iOS app or IPA has not been compiled or tested.
- Real provider calls, real microphone recognition and signed store distribution
  are not certified by the deterministic test fixtures. Public hostname routing
  was checked separately; see [TUNNEL.md](../docs/mobile-app/TUNNEL.md).
- Re-pairing issues a new device identity. Old device-scoped unsent data is retained
  locally and never silently transferred to a different connection.

## Build and verify

Requirements: JDK17, Android SDK36. Pins: Kotlin2.3.21, Compose1.10.3,
AGP8.11.1, Gradle8.14.3; minimum Android26. Set `sdk.dir` in the ignored
`local.properties` if needed.

```sh
./gradlew :androidApp:assembleDebug :shared:testDebugUnitTest :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebugAndroidTest
# With an emulator/device attached:
./gradlew :androidApp:connectedDebugAndroidTest
```

APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
Apple build instructions: [iosApp/README.md](iosApp/README.md).

Validation on 2026-10-03: 69 shared state/transport tests, 62 Android unit tests,
32 native instrumentation tests, and two end-to-end Compose/controller flows
passed. The UI flows use a deterministic host fixture and silent OS boundary;
native permission/storage/worker checks run separately. Screenshots were
inspected for chat, model picker, drawer, dictation, editor, and both themes.

Architecture: [ARCHITECTURE.md](../docs/mobile-app/ARCHITECTURE.md).
Product decisions: [DESIGN.md](../docs/mobile-app/DESIGN.md).
Asset attribution: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
