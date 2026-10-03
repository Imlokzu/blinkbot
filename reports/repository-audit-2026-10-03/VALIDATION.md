# Audit evidence — 2026-10-03

## Existing checks

The source checkout started at `9a33139` and contained unrelated staged and
unstaged edits. Checks ran against that working checkout.

- `cd "Virtual Bot"; PYTHONPATH="$PWD" VBOT_OFFLINE=1 .venv/bin/pytest tests/ -q`:
  **994 passed, 7 skipped, 150 subtests passed**, in 57.94 seconds. Offline mode
  skips an additional optional network test. Existing FastAPI/Starlette
  deprecation warnings remain.
- `cd "Virtual Bot/dashboard"; npm test`: **99 passed**.
- `npm run typecheck`: passed.
- No dashboard build was performed: this task changes audit artifacts only,
  and the build writes into the shared live asset directory.

macOS output was muted before checks and remains muted.

## Reproducible local probes

Run from the repository root with the existing Virtual Bot environment:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-03/probes.py
```

The script mutes macOS, writes synthetic state under a temporary directory,
mocks all provider/publishing actions, and does not run the app's lifespan.
Its only child is a short Python fixture to exercise stderr flow control.
Its JSON results are observations, not assertions that proposed fixes exist.

| Probe | Observed result |
| --- | --- |
| Account cache | Bob disk history empty; Bob cache history contains Alice fixture messages |
| Chat deletion | POST 200 with two disk messages before deletion; DELETE ok; disk empty, two cached messages remain |
| Invalid session ID | HTTP 200, unchanged ID containing spaces, empty message ID, no saved session |
| Injected disk failure | One failed temporary-file write inside append; HTTP 200 with answer, empty message ID, no persistence field; disk empty, two cached messages remain |
| Session site publication | Mocked publish returns URL; local public reader returns 404 |
| Coding registry | Two same-key pre-start calls return different objects; first is unregistered |
| Coding stderr | `STARTED` confirms the child is running; readiness blocked before drain; `READY` after draining 20,971,520 bytes |

The cache/publishing cases call local functions or a lifespan-free TestClient.
They do not prove a specific external deployment is reachable. The registry
probe proves replacement before startup; its overlapping-request impact is a
source inference. The FilesPanel draft race remains a source finding and needs
a browser regression before a product fix is accepted.

The primary OpenClaw path ignores the application history array when a stable
Gateway key is supplied. The account-cache and deletion probes establish local
cache behavior, not leakage or forgetting through the Gateway. History-based
demo replies can consume that array, but demo fallback is disabled in the
inspected configuration. The disk-failure probe establishes loss of the local
application transcript; it does not establish a Gateway persistence failure.

## Independent review

A separate adversarial reviewer checked F-01 through F-08 against current
source and the October 1/2 reports, inspected the proposed remedies, and reran
all seven probes after correcting the evidence artifacts. Fable is unavailable
in this runtime, so the review used the supported inherited native model.

- Scoped F-01's High impact to multiple accounts using a history-consuming
  reply path, and added the Gateway transcript limits to F-01, F-03, and F-04.
- Identified FilesPanel's manual-refetch trigger and the requirement for changed
  returned data; retained its source-only evidence classification.
- Distinguished F-03 from the older concurrent chat-store mutation finding.
- Strengthened the probes with pre-delete disk history, a filesystem failure
  inside the real append implementation, explicit persistence-field presence,
  and a child-start handshake plus bounded stderr reads and cleanup.

Both the original and corrected probes completed with exit code 0 and the
recorded behavior. The reviewer did not rerun the full product suites, invoke
providers, publish a site, or exercise a browser or real omp job. The existing
Starlette TestClient deprecation warning remains. No product code was changed.

**Artifact verdict:** ready for the local HTTP smoke. The findings remain
unfixed product issues; this review approves the evidence, not those behaviors.

## HTTP smoke

An isolated instance of the actual application router ran on
`127.0.0.1:18103`, with temporary chat/brain/workspace state and Uvicorn
lifespan disabled. Background integrations, scheduled jobs, ASR warmup, and
service startup did not run. Provider health URLs pointed to an unused
loopback port; the owner's services were not probed.

**20 curl checks passed: 18 returned 200 and two returned 400.** They covered
the root, dashboard, screen, static entry, auth configuration, status, memory
list, workspace information, dashboard entry scripts/styles/module preloads,
registration script, and service worker. Both memory and workspace file APIs
rejected parent-directory paths with HTTP 400.

This verifies HTTP routing and the dashboard entry's direct asset references;
it is not a browser-render or recursive asset-graph check. The smoke process
exited successfully and a socket check confirmed port 18103 was closed.
macOS output remains muted. Product code and the shared build assets were not
modified by the audit.
