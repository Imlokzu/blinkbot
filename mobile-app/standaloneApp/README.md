# Blink Chat

A separately installable Android app for direct API chat. Blink's paired host
client remains `:androidApp` / `me.waveio.claudebot`; this app is
`:standaloneApp` / `me.waveio.blinkchat`. It has its own private storage and does
not pair with, start, or require the Blink host.

The chat, drawer, composer, Markdown, media viewer, wallpaper, fonts and themes
come from `:shared`. Optional extensions supply secondary screens, locale
overrides and supported composer controls. Existing Blink callers keep their
original behavior and enabled features.

## Configure

1. Open Profile, then **Providers & models**.
2. Edit the existing provider or choose **Add provider**. Give each provider a
   name, select its preset, and enter its HTTPS endpoints and API key.
3. Enter the provider's chat and image model IDs. Additional IDs go one per line;
   after saving, **Refresh model list** imports that provider's available models.
4. Save. Each provider's key is encrypted separately with Android Keystore and
   is never shown again. An empty key field retains its saved key; Remove key
   deletes only that provider's key.
5. Choose **Default chat** and **Default image** independently from the combined
   provider/model lists. Defaults apply to new conversations. Existing chats
   keep their provider and model, and the chat picker can explicitly change them.
6. Start a chat. Attach a photo for visual understanding. Select the image
   model from the chat model picker, or choose Create image in Tools, to use
   the composer as an image prompt.

Model discovery uses the provider's `/models` endpoint. Manual model IDs are
supported when the provider does not publish a catalog. The selected model
must support the requested capability; the app does not silently substitute
another provider or model.

Providers may use identical model IDs: every picker entry retains its provider
identity and sends the raw model ID only to that provider's endpoint with its
own key. Image tools use the conversation's image provider, which may differ
from its text provider. Global instructions and the tools toggle apply across
providers.

The original single-provider setup migrates automatically, preserving its
encrypted key, settings, defaults and chat history. Removing a provider removes
its credential while retaining conversations; those conversations require an
explicit new model choice before they can send again. At least one provider
must remain. Deleting a default provider assigns new-chat defaults to a remaining
provider only after confirmation.

The QwenCloud preset uses these documented protocols:

- Chat: OpenAI-compatible `POST /compatible-mode/v1/chat/completions`, with
  streaming text, inline `image_url` inputs and function calls.
- Image generation: synchronous
  `POST /api/v1/services/aigc/multimodal-generation/generation`.
- Custom image providers: OpenAI Images JSON, accepting a generated HTTPS URL
  or `b64_json` in `data`.

Each provider's API endpoints use its saved key. Generated images download separately
without an Authorization header, and requests do not follow redirects.
The model ID fields remain editable, including after preview models retire.

Sources: [QwenCloud integration overview](https://docs.qwencloud.com/token-plan/best-practices/integration-overview),
[QwenCloud image generation](https://docs.qwencloud.com/token-plan/best-practices/image-generation).

## Tools and local data

The text model can call five built-in tools when tools are enabled: generate
an image, read the device clock, list workspace files, read a file, and write
a file. A turn allows at most six model rounds and twelve tool calls. Tool
failures retain their actual outcome in the shared activity view.

Workspace tools operate only on bounded text files in the app's private
workspace. They cannot execute shell commands, install packages, or read
arbitrary device paths. The Files screen edits that same workspace. Image
attachments, generated images, conversation history, reactions and provider
settings are local to Blink Chat. Android backup is disabled.

This first version supports text chat, photo understanding and image
generation. Speech recognition, audio/video generation, host agents, remotely
installed skills, host queues and scheduled delivery are outside this version.
Dictation and scheduling controls stay hidden in this app.

## Build and verify

From `mobile-app`, with JDK 17 and Android SDK 36:

```sh
./gradlew :standaloneApp:assembleDebug :standaloneApp:testDebugUnitTest
./gradlew :standaloneApp:connectedDebugAndroidTest
```

The debug APK is
`standaloneApp/build/outputs/apk/debug/standaloneApp-debug.apk`.
Install with `adb install -r` to update Blink Chat alongside Blink.

Unit tests cover provider identities/defaults, payloads, SSE parsing, provider
errors, image protocols and workspace paths. Android tests exercise migration,
separate keys, cross-provider routing, default retention, provider removal,
the actual settings UI, image normalization, tools and local history.
The provider responses in those tests are fixtures; they do not prove that
every configured provider supports every optional capability.

The opt-in `LiveProviderSmokeTest` reads a temporary key from the app-private
`files/live-validation-key`, verifies QwenCloud text/vision and image generation,
and removes the key afterwards. Never pass a real key as an instrumentation
argument, embed one in a test, or save one in a report.
