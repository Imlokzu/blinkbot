# Night agent review: authenticated mobile updates

The Android in-app updater accepted a published package when `sha256` metadata
was absent. It only compared bytes when an optional digest existed, so an
authenticated download could still reach the native installer without an
integrity check.

The shared controller now requires an exact 64-character hexadecimal SHA-256
value and a matching platform digest before handing any APK to Android. Missing,
malformed, and mismatched values are rejected with the localized checksum error;
empty packages remain separately reported. Other transport/install failures keep
the generic retry error. iOS update links continue to open externally and are
unchanged by this Android-only gate.

Validation:

- Shared and Android unit tests: `./gradlew :shared:testDebugUnitTest :androidApp:testDebugUnitTest` passed.
- Mobile API and streaming regressions: 32 Python tests passed.
- New checksum tests cover uppercase, whitespace, missing, malformed, short,
  mismatched, extra-character, and exact valid digests.
- Independent adversarial review approved the scoped change. The first native
  reviewer model was unavailable, so a supported reviewer was requested under
  the installed role constraints.
- No package, token, or signing key was accessed or committed. Mac output was
  muted before tests and remains muted.
