# Claude Bot Mobile

New Kotlin Multiplatform Android/iOS client for the existing Claude Bot backend.
Requirements: [product specification](../docs/mobile-app/DESIGN.md) and
[screen plan](../docs/mobile-app/SCREEN_PLAN.md).

## Development

- JDK 17 and Android SDK 36.
- Kotlin 2.3.21, Compose Multiplatform 1.10.3, AGP 8.11.1, Gradle 8.14.3.
- Full Xcode is required to compile Apple targets; Command Line Tools alone are
  insufficient. The current development host has no full Xcode installation.

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testDebugUnitTest
```

Set `sdk.dir` in the ignored `local.properties` when the Android SDK is not
discovered automatically. Never add credentials or pairing payloads to source.

## Modules

- `shared`: Compose UI, state, service contracts, and platform network engines.
- `androidApp`: Android application entry point and native integration.
- Apple frameworks: `iosArm64` and `iosSimulatorArm64`, named `ClaudeBot`.

The initial bootstrap build is not a completed feature release. Implementation
and verification status are recorded as the client is connected to real services.
