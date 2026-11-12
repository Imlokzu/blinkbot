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

1. Open Profile, then **Providers & models**, and choose **Add provider** (or
   edit an existing one).
2. Paste the endpoint URL (a preset chip fills the well-known ones) and the API
   key, then tap **Connect and detect**. Blink Chat probes the endpoint:
   - **API layer**: which of Anthropic Messages, OpenAI Chat Completions and
     OpenAI Responses answer. Each probe is a POST with an empty JSON body; an
     existing route rejects it as invalid before any model runs, so nothing is
     billed. Answered layers are marked ✓, and the best one is preselected.
   - **Models**: the catalog with each model's context window, output cap and
     image input, read from Anthropic `capabilities`, OpenRouter-style
     `context_length` and `architecture`, and LiteLLM's `/model/info`.
     Embedding, speech, video and moderation models are left out of chat.
   - **Effort levels** per model: a published list (Anthropic `capabilities`,
     explicit effort lists) is used as is. Otherwise Blink Chat sends the model
     an effort value no provider accepts; validation rejects it before any
     model runs, and the rejection lists the allowed levels (QwenCloud:
     `'reasoning_effort' must be one of: 'none', 'minimal', ...`). Endpoints
     that ignore unknown values get `low`/`medium`/`high`. Up to 48 models are
     checked during Connect; any other model is checked the first time it is
     opened in the chat model picker, including providers saved before 0.3.1.
   - **Images**: an OpenAI Images route or the QwenCloud host enables image
     generation with a prefilled endpoint.
3. Choose the provider's roles. **Chat** and **Images** are separate switches:
   a text-only provider leaves Images off and can be combined with another
   provider that generates images. Endpoints without a model list accept model
   IDs typed by hand.
4. Save. Each provider's key is encrypted separately with Android Keystore and
   is never shown again. An empty key field keeps the saved key; a saved key is
   reused for **Detect again** only on the same origin it was saved for.
5. Choose **Default chat** and **Default image** independently. The default
   image may be unset when no provider generates images. Defaults apply to new
   conversations; existing chats keep their provider and model.
6. In a chat, the model picker is Blink's shared picker: models from every
   provider in one list, with the effort slider showing only the levels that
   model accepts, including **Off** where the provider allows disabling
   thinking (sent as `none`). Until a level is picked the slider shows the
   provider's default (`medium` for OpenAI-compatible APIs). The chosen effort
   is stored per chat and sent only after it was explicitly picked
   (`output_config.effort`, `reasoning_effort` or `reasoning.effort`,
   depending on the layer).

Model discovery uses the provider's `/models` endpoint. The selected model
must support the requested capability; the app does not silently substitute
another provider or model.

Providers may use identical model IDs: every picker entry retains its provider
identity and sends the raw model ID only to that provider's endpoint with its
own key. Image tools use the conversation's image provider, which may differ
from its text provider; the image tool is not offered when no image model is
set. Global instructions and the tools toggle apply across providers.

Anthropic tool rounds send the assistant's content blocks back unchanged,
including thinking blocks and their signatures. Responses requests use
`store: false` and resend the visible history each time.

The original single-provider setup migrates automatically, preserving its
encrypted key, settings, defaults and chat history; saved providers without an
API layer are Chat Completions. Removing a provider removes its credential
while retaining conversations; those conversations require an explicit new
model choice before they can send again. At least one chat provider must
remain.

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
