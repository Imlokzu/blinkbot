# Night review: platform-specific updater metadata

The beta-channel implementation hardcoded Android in its environment resolver.
Consequently an iOS capabilities request could advertise the Android version,
APK URL, changelog, digest and mandatory flag, while ignoring the already
supported `MOBILE_UPDATE_IOS_*` settings. This was reproduced against the actual
FastAPI capabilities route using synthetic Android and iOS release metadata:
eight regression cases failed and one unchanged Android-route case passed.

The resolver now reads the requested platform's channel overrides, falling back
to that same platform's legacy keys. Explicit empty overrides remain empty.
The selected update and stable/beta informational records use the requested
platform's metadata and version-code comparison. The existing informational beta
record retains `available:false`; stable information still compares with zero.

The optional `ios_url` destination uses the corresponding iOS channel URL,
including legacy `MOBILE_UPDATE_IOS_URL`. Android's owned-download route retains
the resolver's Android default and its existing channel query behavior. No new
schema, native client change, release publication or provider/configuration work
was needed. Per-field fallback within a platform is unchanged; this pass does
not implement the audit's broader immutable-release-manifest proposal.

## Fresh validation

- New platform matrix: nine tests passed. Cases cover both platforms/channels,
  metadata and digest isolation, version gating, unpublished iOS, legacy iOS
  compatibility, explicit empty overrides, Android URL generation, unknown
  channels, platform validation and authentication.
- Platform matrix plus the existing mobile API suite: 35 passed. Separate
  reviewer runs passed 13 and 12 focused compatibility cases respectively.
  The exact staged resolver, reconstructed on committed source without the
  other agent's size-limit hunks, passed 34 cases; the unrelated dirty size
  regression was excluded from that scope.
- Final offline Virtual Bot suite: 1,600 passed, eight skipped, 178 subtests.
  Owner dotenv credentials and inherited updater settings were excluded. The
  first harness was interrupted after an overbroad dotenv guard also blocked
  synthetic fixture files; a guard restricted to repository dotenv files
  completed the suite successfully. No product change was made for that issue.
- Dashboard: 221 tests, TypeScript and isolated guarded production build passed.
  Build output remained outside live assets. The working build includes the
  existing untracked integration sources; the previously documented clean
  checkout limitation is not resolved by this server fix.
- Landing: eight tests and isolated build passed. Launcher Go tests passed.
  Renamed display module: six tests passed using its cached Python 3.9 test
  packages. An initial invocation in the web backend's environment lacked the
  async pytest plugin; no display code or dependencies were changed.
- Gradle shared/Android unit-test tasks passed with 278/78 passing JVM cases,
  reusing up-to-date results. No physical device, iOS runtime, native installation
  or new package distribution is claimed.
- Independent code review: APPROVE; architectural review: CLEAR. Supported
  maximum-effort native agents followed installed role instructions because
  Fable/configured specialist models were unavailable. AST/syntax and tests were
  explicitly authorized as the diagnostic fallback for unavailable LSP tools.
- Post-review actual-app curl smoke passed 35 status checks plus platform/version
  and synthetic-download assertions. iOS received codes 9/10 with iOS links;
  Android received 42/44 with owned-download links. Invalid mobile credentials
  returned 401; memory/workspace traversal returned 400. App, screen, dashboard,
  synthetic workspace file and 14 entry asset references returned 200.
  Smoke used temporary state, blocked dotenv reads, mocked provider health and
  disabled lifespan. Its server stopped and port 18107 was confirmed closed.

Other agents' APK-size changes, editor drafts, brand profile and native zoom/
release work remain outside this commit. No real credentials, release packages,
provider calls, signing keys or production jobs were used. The Mac remains muted.
