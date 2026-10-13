# Audit evidence — 2026-10-13

Starting revision: `01bce45c`. Unrelated tracked/untracked work was already in
the shared checkout. Only this folder and an audit handoff pointer belong to
the task. Repairs in [REPORT.md](REPORT.md) remain proposals.

## Reproducible observations

From the repository root:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-13/probes.py
```

The script loads actual `memory.py` with a minimal synthetic `brain_context`
root adapter. It starts no application, lifespan, tool registry, dream cycle,
provider or device. Temporary directories contain all profiles/notes/locks and
recovery-shaped files. Environment is filtered; Python guards block dotenv
reads, socket connect/bind and DNS lookup. Source bytecode writes are disabled.
Mac output is muted.

The async contention case extracts/compiles the actual `api_memory_save` body
through Python AST with immediate synthetic owner/context adapters and a local
HTTP-exception equivalent. It holds a real flock and changes only the wait
budget to 120ms, using the real acquisition/polling logic. The 10ms asyncio
timer and handler share one loop. The probe releases its lock before cleanup.

| Scenario | Actual result |
| --- | --- |
| One profile text-read failure, later bytes/write succeed | Append returns normally; two existing facts disappear; new fact remains |
| Brain reservation contention | About 122ms elapsed; 10ms timer still unfinished; endpoint-equivalent status 500 |
| Synthetic backup/staged note catalog | Three rows including two hidden recovery paths; both recovery rows rejected by reader |
| Directory fsync only fails | Write returns normally; one open directory descriptor remains |

The directory descriptor is closed explicitly after observation. No real I/O
failure, busy brain, recovery transaction or descriptor exhaustion was induced.
The catalog shape models consolidation artifacts without executing a transaction.
Faults affect fixture operations only. The first run failed an injection assertion
because macOS temporary-directory aliases did not compare equal after resolve;
resolving the fixture root corrected the harness. No product change was needed.

## Existing checks

`tests/test_memory_reasoning.py` and `tests/test_dream_cycle.py`: **43 passed,
35 subtests passed**, in 1.70 seconds on the final guarded run. One existing
Starlette/httpx deprecation warning remains. The Virtual Bot interpreter used
offline mode, a synthetic issuer/configuration, temporary runtime paths, owner
runtime/profile/repository-dotenv guards and a dedicated pytest base directory.
ASR warmup was disabled for this focused run, and provider health targets were
redirected to an unused loopback port. Existing model/provider tests use mocks.

The initial combined run had seven teardown failures and 36 passes; an isolated
one-case retry reproduced a teardown failure. The guard had interpreted relative
`os.open(..., dir_fd=...)` cleanup paths as if they were relative to the repository,
blocking temporary subdirectories named `runtime`. Restricting owner-runtime
checks to absolute paths fixed the harness. No product change or assertion
suppression was made; the final full focused run passed. The initial run also
logged unrelated ASR warmup from a test lifespan; the final run disabled it.
These results do not certify physical ASR or provider behavior.

Python syntax, report fences and local links passed. The checks cover current
working source, not a clean checkout excluding other agents' pending edits.
No dashboard/mobile production build or deployment was needed for report-only
changes. Historical suites in earlier reports are not fresh results here.
macOS output remains muted.

## Independent review and post-review smoke

Two separate maximum-effort supported native reviewers applied the installed
code-reviewer and architect instructions. The configured specialist models were
unsupported and Fable was unavailable; this is native fallback review, not Fable
certification. Code/spec/security verdict: **APPROVE** for the three artifacts.
Architectural status: **CLEAR** after the repair-boundary clarifications.

Both reviewers reran the guarded probe; all four findings reproduced. Their
contention timings were about 124–127ms, consistent with the shortened deadline.
Corrections made the bounded worker/admission/context/cancellation lifecycle
concrete, prevented non-reentrant lock reuse, and distinguished visible file
replacement from uncertain directory-sync durability. Guard coverage was also
made precise. Python AST/compile checks, four repair sketches, Markdown links,
fences and whitespace checks passed. LSP/ast-grep tools were unavailable; the
explicitly authorized source/syntax/probe fallback supplied diagnostics.
No product files were changed by either reviewer.

After review, the actual `main.app` ran on `127.0.0.1:18113` with Uvicorn lifespan
disabled. Its launcher redirected brain, workspace/code, chats, uploads, store,
widgets, profile, mobile/usage databases, integrations and OpenClaw paths into a
temporary root. It filtered the environment, supplied synthetic configuration,
denied real runtime/profile/dotenv reads and blocked external connections/DNS.
Provider health targets pointed at an unused loopback port; background jobs,
ASR/TTS warmup, integrations and paid model work were not started.

**29 routing/direct-asset expectations passed:** 24 returned 200, three traversal
attempts returned 400, the synthetic mobile host required authentication with
401 and excluded `/api/events` with 404. Checks covered root, dashboard, screen,
static entry, auth config, status, projects, empty memory/workspace/mobile
inventory, tools, eleven directly referenced dashboard script/style/modulepreload
assets and the service worker. Memory, workspace-file and mobile web-preview
parent paths were rejected.

**Three additional real memory-route checks matched expectations:** the fixture
catalog returned 200 with a live note plus a hidden recovery note; the live note
read returned 200; the catalog's recovery path read returned 400. That last
response reproduces B-03, not a shipped repair. The launcher seeded inert files
only in the brain belonging to the synthetic `audit-fixture` session.

All 32 expected statuses and catalog/body assertions matched. The server exited
with code 0 and port 18113 was confirmed closed. This verifies routing and direct
published assets, not a browser-rendered editor, recursive asset graph, physical
device, cloud provider or production memory incident. Other fault cases remain
guarded function/endpoint-body observations. No product repair was applied, and
macOS output remains muted.
