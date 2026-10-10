# Blink 0.4.16 iOS simulator validation

Blink 0.4.16, build 23, compiled, launched and passed all 26 hosted native tests
on an iPhone SE (3rd generation) simulator running iOS 26.2. The successful
[GitHub Actions run](https://github.com/Imlokzu/blinkbot/actions/runs/38080622410)
built commit `3928210743b80268c6846d00aa7bf50a72b1bee2` with Xcode 26.3 on
the standard Apple Silicon `macos-15` runner. The native suite completed in
28.424 seconds, excluding compilation, simulator startup and artifact export.

## Verified behavior

- Tiptap typing and HTML CodeMirror typing reach Kotlin before the native flush
  acknowledgement. The checks use the bundled editors in real WKWebViews.
- Scripted Excalidraw input creates a live rectangle with positive dimensions;
  the resulting scene JSON reaches Kotlin and flushes successfully. The fixture
  emulates capture only for its synthetic pointer ID and restores the methods.
- A valid editor main-frame bridge message is accepted; a child-frame forgery
  cannot replace the accepted content.
- HTML preview has an opaque security origin, rejects storage and native bridge
  access, blocks network constructors, loads its local JavaScript module,
  rejects an HTML subresource with status 415, and blocks external navigation.
- The other 21 tests cover bounded file reads and original-byte exports,
  picker cancellation, Keychain failure decoding, pairing URL validation,
  QR cancellation/invalidation, and deterministic PCM/WAV recording buffers.

Startup and the four editor/preview attachments were exported. The normal Blink
startup screen and Tiptap, CodeMirror and Excalidraw images were inspected;
these are actual simulator images, not generated concepts.

## Repairs required by the first iOS build

The build exposed UIKit dialog parameter, common map/clock portability,
Foundation/WebKit import and initializer, Swift delegate callback, and audio
notification-name mismatches. The iOS plist now enables the frame-rate option
required by Compose, and the local preflight guards it.

WebKit rejects regular-expression disjunctions in content rules. The editor and
preview lists now use separate scheme rules while retaining their network and
cookie restrictions. A native probe also demonstrated that the same WKWebView
can arrive through different Kotlin wrappers; the message guard now compares
native-object equality and retains all frame, origin, method and path checks.

The test harness gained explicit WebView width, initial mode selection before
view creation, actual document-URL readiness checks, bounded JavaScript waits,
per-test deadlines and primitive JavaScript results. It packages the app and
captures startup before XCTest, exports failure attachments, and preserves the
original test failure status.

Eight earlier attempts found compile, runtime or harness problems; one was
cancelled after an editor wait stalled. Their final progression was 21/26,
then 24/26, then 26/26. The successful final run does not erase those failures.
Separate adversarial reviewers checked each repair. Fable was unavailable;
supported independent agents were used without claiming a Fable review.
Post-review isolated backend smoke passed 29 curl expectations, including
static assets and traversal responses of 400, then stopped its server.

## Artifact and installation

Local artifact: `mobile-app/build/Blink-0.4.16-ios-simulator.app.zip`.

- ZIP size: 33,212,646 bytes.
- SHA-256: `fc5136cbbb7fdee5ab8128addf75f761705d83e6031af124d2bfdce5123ab2d9`.
- Product: `Blink.app`; bundle ID: `me.waveio.claudebot.mobile`.
- The scheme and shared framework retain `ClaudeBot` names for compatibility.

The GitHub artifact `ios-build-9-1` also contains the app ZIP, build logs,
XCTest results and screenshots. Actions artifacts have 14-day retention; the
manual workflow can create a fresh build. See the
[iOS host README](../../mobile-app/iosApp/README.md) for building and installing
on a booted Apple Silicon simulator with full Xcode. No Apple signing account
is needed for simulator installation.

## Limits

This is a debug simulator build with test fixture exports and a hosted test
bundle. Ordinary builds exclude the fixture sources by default. No unsigned
device archive, signed IPA, TestFlight submission or store release was built.
The tests use synthetic documents and callbacks without the owner's workspace,
provider calls or audio capture. They do not certify physical touch, hardware
audio conversion, camera/notification permissions, older iOS versions,
background delivery or physical-device frame rates. ImageIO decoding compiles
but still needs dedicated iOS runtime coverage. Earlier Android/web workspace
validation and its feature limits remain in [the release notes](RELEASE-0.4.16.md).
