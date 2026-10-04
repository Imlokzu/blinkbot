# Audit evidence — 2026-10-04

Starting revision: `702de21`. Checks used the shared working checkout, which
already contained unrelated staged and unstaged edits. Audit artifacts are
English-only; the scope does not include product implementation fixes.

## Existing suites

- In `Virtual Bot`:
  `PYTHONPATH="$PWD" VBOT_OFFLINE=1 CLERK_ISSUER=https://clerk.audit.invalid .venv/bin/pytest tests/ -q`:
  **1,189 passed, 7 skipped, 178 subtests passed**, in 79.95 seconds.
  The issuer is a nonsecret synthetic test value. No live issuer verification
  or vault credential was needed. Existing FastAPI/Starlette warnings remain.
- In `Virtual Bot/dashboard`, `npm test`: **207 passed**.
- `npm run typecheck`: passed.
- In `mobile-app`,
  `./gradlew --offline :shared:testDebugUnitTest :androidApp:testDebugUnitTest`:
  **85 shared and 62 Android unit tests passed**, with zero failures/errors/skips
  in the XML results. Compilation reused cached outputs; both test tasks executed.
  This is host unit testing, not Android device/UI or iOS testing.

macOS output was muted before tests and remains muted. No release assets or
APK were published. The audit does not require a dashboard production rebuild.

## Reproducible probes

From the repository root:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-04/probes.py
```

The script mutes macOS, clears inherited environment variables except nonsecret
process settings, and pins synthetic Clerk/mobile origins. It blocks dotenv
reads and socket connections/DNS/binds before application imports, supplies an
empty synthetic configuration for the exact `config.yaml` read, and redirects
runtime databases and chat data to temporary directories. It never starts the
application lifespan or calls a provider or pairing exchange. Client cleanup
uses an ExitStack without entering TestClient's lifespan; fixture cleanup runs
on probe failure and has an exit fallback for import failure. The script reports
observations rather than pretending the proposed fixes already exist.

| Probe | Observed result |
| --- | --- |
| Scheduler failure | One injected claim exception; task done; Start reuses dead task; job remains queued |
| Scheduled deletion | DELETE 200/ok; disk history removed; scheduled job remains and becomes claimable |
| Event-loop lock wait | Reviewer rerun: 0.283s claim; 20ms timer still unfinished until the loop regains control |
| Retained history | 64 snapshots / 266,944 JSON characters for 8,192 final characters; all 41 completed jobs returned |
| Audience capacity | Bob queue contains 200 Alice-only events; Bob's relevant event dropped; next frame is keepalive |

Scheduler failure is injected, not a reproduced production disk failure.
Deletion proves future claimability but does not execute a real scheduled task.
Content growth is a small synthetic fixture, not a throughput or phone-memory
benchmark. Audience filtering prevents content disclosure in that probe;
its failure is event loss. The native retry idea is source tracing only.

The final guarded reviewer run exited 0 and reported two prevented dotenv reads,
one synthetic configuration read, zero connection attempts, and zero DNS
lookups. One denied `socket.bind` was traced using event type and stack metadata
to urllib3's import-time IPv6 capability check (`connection.py`, `_has_ipv6`);
it was not a completed external request. No credential contents or network
addresses were inspected to identify it. The existing Starlette TestClient
deprecation warning remains.

## Independent review and HTTP smoke

The independent accuracy lane supported M-01 through M-05 and reran all five
probes under guards. Review corrections fixed the queue-size line reference,
acknowledged the already documented overdue-schedule policy, and added the
dotenv/configuration/network guards and failure-safe fixture cleanup.
Architecture feedback was incorporated into the deletion, cancellation,
checkpoint rollout, and retry/rerun recommendations. Fable was not selectable;
the review used supported native agents. Failed fixed-model preset launches
produced no review evidence and were replaced by native independent lanes.

Final artifact verdict: the accuracy lane recommended **APPROVE**; the
architecture lane returned **CLEAR** after rereading the corrected artifacts.
Its recheck was static; the final guarded rerun and counters above were executed
by the independent reviewer. No product fixes are certified by those verdicts.

Full suites, devices, iOS, provider execution, and a production configuration
were not rerun by the reviewer.

## HTTP smoke

After review, the root agent ran the actual router on `127.0.0.1:18104` with
temporary runtime state, filtered process environment, blocked dotenv loading,
and an empty synthetic configuration. Uvicorn lifespan was disabled, so the
mobile scheduler, integrations, ASR warmup, and background services did not
start. Provider health URLs pointed at an unused loopback port; production
services were not probed.

**24 curl checks passed: 22 returned 200 and two returned 400.** Checks covered
the root, dashboard, screen, static entry, auth configuration, status, memory
list, workspace information, mobile capabilities and empty job inventory,
dashboard entry scripts/styles/module preloads, registration script, and
service worker. Memory and workspace file APIs rejected parent-directory paths
with HTTP 400.

This checks routing and direct dashboard-entry asset references, not browser
rendering, recursive asset closure, or a live provider/mobile job. The smoke
process exited successfully, and a socket check confirmed port 18104 was
closed. macOS output remains muted. Product files and published build assets
were not modified by the audit.
