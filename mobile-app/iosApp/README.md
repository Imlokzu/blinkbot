# Blink iOS host

The SwiftUI application hosts the shared Compose `App(PlatformBridge)` through
`MainViewController(bridge:)`. `IosBridge` owns its `StateFlow`; Swift implements
`IosNativeDelegate` and never implements a Kotlin coroutine interface.

## Build with full Xcode

From this directory:

```sh
xcodegen generate --spec project.yml
open ClaudeBot.xcodeproj
```

Select the ClaudeBot scheme and an Apple Silicon simulator, or select a physical
device and your signing team in Signing & Capabilities. The existing shared
Gradle configuration provides `iosSimulatorArm64` and `iosArm64`; an Intel
simulator is not supported by that configuration. Xcode's prebuild phase runs
the real `:shared:embedAndSignAppleFrameworkForXcode` task before Swift compilation.
The task builds, links, copies Compose resources, and signs the framework using
Xcode's environment. Script sandboxing is disabled for that Gradle phase. The
framework is `ClaudeBot`; the app's Swift module is `ClaudeBotApp` to avoid an
import collision. Keep the generated project and `project.yml` in sync by
regenerating after configuration changes. No CocoaPods or network SDK is used.

Use Xcode Product > Test for the hosted XCTest target. Command Line Tools alone
cannot compile UIKit/SwiftUI, export the iOS Kotlin framework, link an iOS app,
or exercise camera/microphone/notification behavior.

## Simulator build in GitHub Actions

The manual `iOS build and tests` workflow uses an Apple Silicon runner with
Xcode 26.3 and JDK 17. To build the simulator app without Apple signing:

```sh
gh workflow run ios-build.yml -f build-device-archive=false
```

The workflow builds Blink 0.4.16 (build 23), runs the hosted XCTest target and
uploads the simulator app, test results, screenshots and build logs. The app
product is `Blink.app`; the scheme and shared framework retain their existing
`ClaudeBot` names for compatibility. An optional device archive is explicitly
unsigned and is not suitable for installation on a phone by itself.

Native workspace tests use the actual Compose/UIKitView and WKWebView hosts
with synthetic documents. The Kotlin fixture source is included only when
`-PblinkIosUiFixtures=true`; its Swift tests are gated by
`BLINK_IOS_UI_FIXTURES`. Ordinary builds and Release archives exclude those
fixture exports. The CI script enables both flags for its debug test build.
No test uses an owner's connection, invokes a model provider or records audio.

After downloading and extracting the simulator app on a Mac with full Xcode:

```sh
xcrun simctl install booted Blink.app
xcrun simctl launch booted me.waveio.claudebot.mobile
```

## Checks available without Xcode

```sh
sh Tools/check-native.sh
```

This parses every Swift source, validates plists and both locale resources,
compiles and runs the production bounded reader and Keychain decoder against real macOS files, runs
the Foundation-only PCM WAV and callback-mailbox checks, and
generates the actual Xcode project when XcodeGen is installed. The reader checks
the exact 20 MiB boundary, a chunk boundary, oversize rejection, empty data,
directories, missing files, aliased import cleanup, and Keychain failure decoding.
The Keychain checks use synthetic results and never access stored credentials.
The recording checks use harmless PCM fixtures and never request microphone access:
byte-exact signed PCM16 WAV headers, cumulative snapshots at two-second sample intervals,
the final interval's tail, silence preservation, the 600-second/20-MiB ceilings, immutable
emitted snapshots, and coalescing/invalidation of 10,000 pending updates. Matching XCTest
coverage is included in the hosted test target, alongside real Kotlin pairing-state tests.
They do not typecheck UIKit or validate an
exported Kotlin framework header.

## Native behavior and verification

- Keychain uses device-only, unlocked generic passwords. Updates are atomic
  and failures preserve existing credentials. Swift returns an explicit error
  status; `IosBridge` throws that error in Kotlin so common code cannot treat a
  failed write as saved. `UserDefaults` stores non-secret preferences only.
- Lifecycle observers are filtered to the host's scene, with one window enabled.
  Becoming inactive changes `foreground`; entering the background additionally
  cancels recording and picker/capture operations. A permission prompt becoming
  inactive does not cancel its own pending request. Host teardown clears
  callbacks before releasing resources, including results pending capture teardown
  or modal dismissal. SwiftUI dismantling explicitly detaches the host instead of
  waiting for Kotlin/Native garbage collection.
- Recording uses an AVAudioEngine input tap at the actual hardware format and an
  AVAudioConverter to signed 16-bit mono 16 kHz PCM, with live normalized RMS metering.
  A bounded in-memory buffer retains every sample, including silence. Every approximately
  two seconds a finalized WAV snapshot contains all PCM from sample zero; common code
  uploads it through the existing partial-ASR endpoint. Native code makes no ASR requests.
  One coalescing mailbox retains only the latest meter/snapshot, with generation checks
  before Kotlin callbacks. Stop drains the converter and returns the entire WAV, including
  samples after the last partial. The sample/time ceiling is 600 seconds and the complete
  WAV stays below 20 MiB. Silence never ends recording. Cancellation, denial, interruption,
  conversion errors, engine reconfiguration, backgrounding and host teardown remove the tap,
  stop the engine, clear buffered PCM and restore/deactivate the owned audio session.
- The `claudebot` URL scheme is registered in Info.plist. SwiftUI `.onOpenURL` forwards
  into `IosBridge.deliverIncomingPairing`; Kotlin retains only bounded, validated
  `claudebot://pair` payloads in a private MutableStateFlow exposed as read-only StateFlow.
  Common application state decides
  whether a disconnected client may pair. Native code never replaces a connection.
- QR capture requests video permission and processes `.qr` metadata. All session
  mutation/start/stop runs on one serial queue. Cancel, interruption or background
  tears down inputs, outputs, preview and delegates before returning null.
- Photos/wallpaper use the privacy-preserving system photo picker without full
  library access. Document imports use security scope and coordinated bounded
  reads. File representation URLs are read before their provider callback ends.
  Camera images are normalized to at most 4096 pixels on the longest edge before
  JPEG encoding. All attachment bytes are bounded at 20 MiB in Swift and Kotlin.
- Haptics, pasteboard and sharing use UIKit; the share sheet has an iPad anchor.
  Notifications request authorization only when common code requests it. Reply
  notifications carry `conversationId` in their payload. They do not fetch replies
  in the background, and the fixed bridge has no notification-tap routing callback.
- Native labels/errors and privacy descriptions are provided in English and
  Ukrainian. ATS allows local networking for the user-selected bot; public
  endpoints should use HTTPS. No third-party network service is introduced.

The Xcode 26.3 build exports `IosNativeDelegate` callbacks with Swift `Void`
returns and boxed `KotlinFloat`/`KotlinBoolean` payloads. Keep the exported
`writeSecret(key:value_:)` and `doCopyText(value:)` spellings in the Swift
implementation. The framework header also exports the three recording callbacks
including `onPartial`, `IosSecretResult`, `IosBridgeKt.iosPickedFile` with
`NSData`/Swift `Data`, and `MainViewControllerKt.MainViewController(bridge:)`.
These bindings are checked by the GitHub Actions build; the local preflight
cannot verify them with Command Line Tools alone.

## Wallpaper decoding and launcher assets

The iOS shared image decoder rejects encoded input over 20 MiB, inspects ImageIO
geometry without caching source pixels, and creates an oriented first-frame
thumbnail before handing its bounded PNG to Skia. Output is at most 2048 pixels
per edge (4,194,304 pixels). Sources above 32,768 pixels on an edge or 268,435,456
pixels are rejected. CF image/data/options ownership is released explicitly.
The Kotlin/ImageIO integration compiles in the Xcode framework build. Its
decoding behavior still needs iOS runtime coverage; the macOS-only script does
not exercise it.

Android uses a matching encoded/source/output cap with bounds-first
`BitmapFactory` power-of-two subsampling. Real Android instrumentation covers
small PNGs, odd-sized large JPEG/PNG downsampling, panoramic aspect ratios, and
the exact 20 MiB boundary.

Both launcher icons reuse the existing dashboard mascot. The iOS asset catalog
contains an opaque 1024-pixel icon and is selected in `project.yml`. Source,
licensing pointer, and deterministic conversion command are recorded in
[`androidApp/LAUNCHER_ICON.md`](../androidApp/LAUNCHER_ICON.md).

Before release, exercise denied/granted permissions, permission-prompt
cancellation, rapid repeated start/stop, cumulative partials during speech and silence,
44.1/48-kHz hardware conversion, recording interruption, scene background
and return, QR success/cancel, document/photo-provider failure, oversized files,
iPad share dismissal, cold/warm pairing URLs, and authorized/denied notifications on a real device.
