# Mobile 0.4.2: inline assistant images

Images in assistant replies render directly inside the message. Supported forms
include Markdown images, reference images, clickable images and direct raster
URLs. Prose and captions retain their order, and code examples do not download
images. During streaming, unfinished destinations wait for their closing syntax.

Foreign images use the authenticated host proxy at
`GET /api/mobile/images/fetch?url=...`. The host pins checked public IP addresses,
validates each redirect, accepts bounded raster bytes, and does not forward
app credentials to the image server. Owned `/uploads/` and `/preview/` links use
their existing owner/session APIs. Failed image loads offer Retry in place.

Portraits retain their aspect ratio. Tapping an image opens the existing native
Compose viewer; Save and Share use the original bytes and MIME extension.
Inline decoding is capped at 1024 pixels, while the full viewer uses its existing
2048-pixel limit. No changes to provider routing, workbench editors or updates.

## Validation

- 168 shared tests and 78 Android native unit tests passed.
- Four new UI scenarios passed: automatic portrait display and exact-byte Save;
  bare/extensionless/owned sources; localized retry including invalid bytes;
  image arrival before the streamed response completes.
- Four existing image/table/stream-follow UI regressions passed.
- 102 focused backend tests and eight subtests passed.
- Independent reviews closed the nested-image, cache-failure and export-name
  findings. Actual loopback TCP smoke covered bytes/auth/invalid destination/static
  assets. API-host missing credentials returned 401; local capabilities returned 200.

UI/provider services were fixtures. The separately optimized release is installed
and launch-tested on the Android emulator; iOS source/runtime is not certified.
The earlier test-run interruption was a fixture scroll fighting automatic follow;
the final fixture establishes reading intent with a real drag. Assertions were
retained. No production chat messages or paid model-provider calls were made.

A live Wikimedia reference-image smoke returned 9022 JPEG bytes. Some public
hosts reject generic HTTP client User-Agents; the proxy now identifies ClaudeBot.
The optimized APK is version 0.4.2/code 9 (2,809,550 bytes), SHA-256
`82423afb1fbffa7413ad243d796c5abf2d3786b234056a4d3f0991f224fb4542`.
