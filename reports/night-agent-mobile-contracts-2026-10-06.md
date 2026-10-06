# Night agent review: mobile stream and update contracts

This pass reviewed the new persistent Updates screen, release-channel metadata,
native image zoom, math rendering, question composer and mobile file previews.
It fixed the remaining updater contract mismatch from the 2026-10-05 audit:
the API accepted Android APKs up to 100 MiB while native Android's installer
handoff rejected packages over 20 MiB. The server download guard and Kotlin
download reader now share a 20 MiB boundary, and the API suite includes a file
just above that boundary that is rejected before response streaming.

The prior paired-device stream fix was also rechecked on this branch. It now
includes actual loopback HTTP EOF coverage after revocation, expiry and buffered
replay, with another authorized device receiving the later event while the job
continues. That fix is already in the current branch; this night pass did not
duplicate it.

Validation:

- Mobile API tests: 26 passed after the update-size boundary regression.
- Focused stream authorization tests: 7 passed; related mobile API/stream/
  lifecycle tests: 47 passed.
- Shared Android unit tests: 231 shared and 78 Android tests passed before the
  size-only Kotlin change; the shared suite was rerun after it and passed.
- Dashboard: 215 unit tests and TypeScript typecheck passed.
- Final offline Python suite from this checkout: 1,559 passed, 7 skipped, 178
  subtests. The earlier known image-generation empty-PID fixture race passed on
  isolated rerun; the complete run was clean.
- Isolated app smoke: 26 checks passed for app/dashboard/screen/status/auth,
  mobile capabilities, workspace and static assets; memory/workspace traversal
  returned 400. Providers were mocked, dotenv reads were blocked, state was
  temporary, lifespan was disabled, and the smoke server was stopped.
- Independent code and architecture reviews approved the stream fix. A supported
  reviewer was requested for the updater-size change because the configured
  reviewer model was unavailable. No credentials, signed packages, providers,
  physical devices, or production jobs were used.

Other agents' mobile UI/release drafts and dashboard edits remain outside this
scoped work. The Mac remains muted.
