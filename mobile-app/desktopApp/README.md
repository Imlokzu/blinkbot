# Blink for macOS

The macOS app runs the current Blink shared Compose application. It includes
conversations and the Files workspace with bundled Tiptap, CodeMirror and
Excalidraw editors, interactive HTML previews, revision checks and draft
recovery. It does not use the legacy Electron/Expo desktop shell.

## Build and run

Requirements: Apple Silicon Mac, macOS 13 or newer, JDK 17, Android SDK 36 for
the shared Gradle project, and Xcode Command Line Tools with `swiftc`.
Full Xcode is not required for the desktop build.

From `mobile-app/`:

```sh
./gradlew --no-daemon --max-workers=2 --no-parallel :desktopApp:run
./gradlew --no-daemon --max-workers=2 --no-parallel :desktopApp:packageDmg
```

Packaging produces `desktopApp/build/compose/binaries/main/dmg/Blink-1.0.0.dmg`
and `desktopApp/build/compose/binaries/main/app/Blink.app`. The build downloads
a pinned JetBrains Runtime with JCEF, checks its SHA-512 digest and bundles it
with the app. End users do not need Java or a separate browser installed.

This local distribution is unsigned and is not notarized. Developer ID signing
and notarization are required before a public macOS release. Intel builds are
not provided by this configuration.

## Connect and use

Use Settings / Devices in the host dashboard to create a connection code.
Enter the code in Blink, open a `claudebot://pair` link, or select a QR image.
Each Mac gets its own revocable `macos` device credential, stored in macOS
Keychain. Host desktop capabilities omit Android APK update offers.

The window supports resizing, Escape to dismiss shared back actions, native
file selection, clipboard copying, original-file Save and native sharing.
Closing the window or choosing Quit flushes the active editor and keeps the
window open if a draft cannot be preserved. Audio recording feeds the existing
host transcription API. Microphone permission text is included in English and
Ukrainian.

HTML previews run on a private, ephemeral loopback origin with a response-header
sandbox. They can use local project assets, JavaScript and CSS; external
services, remote framed documents, downloads, popups, device permissions and
native editor messages are blocked. Inline frames inherit the opaque sandbox
and an engine-enforced connection allowlist that disables WebRTC. PDF and Office files retain Save/Share rather than a
native document editor. Preview resources never receive the host credential.

Camera capture, local reply notifications and closed-app background delivery
are not implemented on macOS. The app reports unsupported permissions rather
than claiming that those services are enabled. Recording hardware and delivery
through individual macOS sharing services require device testing.

## Verification

Mute the machine before UI or audio tests:

```sh
osascript -e 'set volume output muted true'
./gradlew --no-daemon --max-workers=2 --no-parallel \
  :shared:desktopBrowserTest :shared:desktopTest :desktopApp:test \
  :shared:testDebugUnitTest :shared:compileDebugKotlinAndroid
```

The opt-in browser lane opens synthetic native Compose windows and uses the
bundled Chromium engine. It checks editor input and flush, Excalidraw pointer
input, native painting and preview isolation. Regular desktop tests exercise
shared application behavior, image bounds and the real loopback resource
server. Platform tests use fake recording hardware and temporary preferences.
No test pairs with the owner's bot, uses their clipboard, or sends messages.
