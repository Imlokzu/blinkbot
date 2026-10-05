# Audit evidence — 2026-10-05

Starting revision: `821e605c`. Checks used the shared working tree, including
unrelated staged/unstaged edits. Product files and release artifacts were not
changed by this audit. The report and examples are English-only.

## Existing suites

- Backend: **1,317 passed, 8 skipped, 178 subtests passed**, in 85.55 seconds.
  The command used the Virtual Bot interpreter, `pytest tests/ -q`, `VBOT_OFFLINE=1`,
  a nonsecret synthetic Clerk issuer, and a wrapper blocking reads of repository
  dotenv files. Existing FastAPI/Starlette deprecation warnings remain.
- Dashboard: `npm test` — **215 passed**; `npm run typecheck` passed.
- Mobile: `./gradlew --offline :shared:testDebugUnitTest :androidApp:testDebugUnitTest`
  succeeded. Both test tasks reported **FROM-CACHE**, rather than fresh execution.
  XML results contain **171 shared and 78 Android tests**, zero failures/errors/
  skips. This is cached unit-test verification, not a new device or native UI run.

macOS output was muted before all checks and remains muted. No dashboard build,
APK publication, provider work, or production restart was needed.

## Reproducible local observations

From the repository root:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-05/probes.py
```

The script blocks repository dotenv reads and socket connections/DNS/binds,
uses an empty synthetic configuration, clears inherited environment data except
nonsecret process settings, and routes runtime data to temporary directories.
It starts no application lifespan. It uses a deterministic fixture-only device
credential by inserting its hash into the temporary database; no production
credential, pairing exchange, or vault item is involved. Credential values are
not printed. The image provider is a fake session; publication uses real files
only inside the temporary upload directory.

| Probe | Observed result |
| --- | --- |
| General event stream | Auth enabled, anonymous handler opens; fixture reply and log markers received |
| Mobile revocation | Fresh identity returns 401; existing iterator still receives a later marker |
| Workspace reservation | Competing rewrite reports busy; reserved rename and separate reserved deletion succeed |
| Cancelled image publication | Busy false while thread blocked; second publication enters; two final fixture files published; both workers completed without errors |

The general-stream observation is handler-level and does not include middleware
or a public-network exposure test. The dedicated mobile hostname rejects that
route in source. Revocation uses the real identity function and route iterator;
it does not prove new requests bypass revocation. The file-mutation probe is
thread-level, not a complete HTTP save corruption. Cancellation occurs after
fake provider completion, not during a paid model call. The APK-limit finding
and conflict-recovery idea are source-only.

The first guarded run and the independent rerun each blocked two dotenv reads
and one urllib3 import-time IPv6 bind capability check. Both recorded zero
connection attempts and zero DNS lookups. One synthetic configuration read
replaced the real configuration in each process.

## Independent review and HTTP smoke

A separate supported native reviewer checked L-01 through L-05 against the
working source and the October 1–4 reports. Fable was not selectable in this
runtime, so this is an independent native review fallback, not a Fable review.
The findings, conditional priorities, and stated reproduction limits were
supported. The reviewer did not rerun the full existing suites.

The review corrected publication-probe cleanup: worker completion is now tracked
in each publisher's `finally` and awaited with a bounded wait on both success
and exceptional paths. The temporary upload-path patch also spans
`asyncio.run` and its executor shutdown. Counting a final file alone no longer
stands in for worker settlement. The rerun completed successfully with two
completed publication workers and no worker errors. Both stream iterators are
closed in their probe `finally` blocks.

The report now states that the initial publication await must be shielded too,
because shielding an already-cancelled task cannot recover its worker. It also
cites the Android file-preparation limit and states the exact greater-than-20
MiB boundary. No product fix, provider call, credential read, package access,
or device operation was performed by the reviewer.

The independent artifact verdict was **READY** for the owner's HTTP smoke;
it approves the evidence and scope, not the unfixed product behavior.

## HTTP smoke

After review, the owning agent ran the actual router on `127.0.0.1:18105`
with temporary runtime data, filtered process environment, blocked dotenv
loading, an empty synthetic configuration, and Uvicorn lifespan disabled.
Provider health URLs pointed at an unused loopback port. Background jobs,
integrations, ASR warmup, and production services were not started or probed.

**26 curl checks passed:** 22 returned 200, two traversal attempts returned
400, an anonymous request to the dedicated synthetic mobile hostname returned
401, and that hostname rejected `/api/events` with 404. This confirms the
mobile-host restriction described in L-01 within an isolated local server;
it does not certify an external deployment.

Checks covered the root, dashboard, screen, static entry, auth configuration,
status, memory list, workspace information, mobile capabilities/job inventory,
dashboard entry scripts/styles/module preloads, registration script, and service
worker. Memory and workspace file APIs both rejected parent-directory paths.
This is HTTP routing/direct-asset verification, not a browser render, complete
recursive asset graph, native installation, or paid-provider check.

The temporary server exited successfully, and a socket check confirmed port
18105 was closed. macOS output remains muted. Published assets and product
implementation files were not changed by this audit.
