# Audit evidence — 2026-10-07

Starting revision: `4d48b37b`. The shared working checkout already contained
unrelated staged/unstaged edits. Audit artifacts are English-only and do not
modify product behavior.

## Existing suites

- Backend: **1,591 passed, 8 skipped, 178 subtests passed**, in 92.32 seconds.
  The Virtual Bot interpreter ran `pytest tests/ -q` in offline mode with a
  nonsecret synthetic Clerk issuer. The wrapper blocked repository dotenv reads
  and cleared inherited updater fields for the test process. Existing
  FastAPI/Starlette deprecation warnings remain.
- Dashboard: `npm test` — **221 passed**; `npm run typecheck` passed.
- No APK build, signing report, installed-device check, or release workflow was
  performed. This audit does not change Kotlin code or publish new binaries.

macOS output was muted before checks and remains muted. The passing suites
cover current working source, not a clean checkout assembled from unrelated
pending changes, and do not certify the proposed fixes.

## Reproducible probes

From the repository root:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-07/probes.py
```

The script blocks dotenv reads and Python socket operations, provides an empty
runtime-configuration fallback, and clears inherited environment data except
nonsecret process settings. It loads the real search and locale sources without
the tool-registry initializer or application imports. Mock HTTP transport and
DNS do not contact providers. It starts no application lifespan. Bash evaluation
uses only a harmless synthetic marker; its child scripts are local fixtures.

The release-pull probe runs an unchanged copy of the pull script inside a
temporary repository. Both `gh` and the publication script are fake commands;
the only asset bytes are a noninstallable synthetic file. No real release,
signing material, dotenv, API token, updater field, or service is accessed.

| Scenario | Observed result |
| --- | --- |
| Page body size | All 2,097,152 bytes consumed; successful result reports only 200,000 |
| Slow DNS | Synthetic 20ms deadline exceeded; about 125ms elapsed; 10ms timer still pending |
| Workflow interpolation | Rendered assignment executes harmless marker; environment-variable alternative keeps literal text |
| Pull naming/code | Workflow basename fails the pull pattern; matching dummy asset reaches publisher; identical bytes acquire codes 20 and 21 |

These are transport/handler/Bash/script fixtures, not production outage or
GitHub exploit tests. No real APK manifest is extracted, no certificate is
compared, and no native installation is attempted. The CI signing finding is
source-based risk. The naming contract is additionally checked against the
official GitHub CLI documentation linked in the report.

The initial guarded run blocked two dotenv reads and one urllib3 import-time
IPv6 bind check, used one synthetic config read, and made zero socket connections
or DNS lookups. The independent reviewer removed unused application imports and
the registry initializer; the reviewed rerun needed no dotenv/config reads or
socket operations. It reproduced all four scenarios, including 129ms elapsed
for the synthetic 20ms deadline. Small timing differences on rerun are expected.

## Independent review and HTTP smoke

A separate supported native reviewer rechecked R-01 through R-06 against the
cited source, compared the October 2–5 reports, reviewed the proposed fixes and
probe isolation, and reran the four guarded probes. Fable was unavailable in
this session; this is a native-review fallback, not a Fable review. Verdict:
**READY for the scoped audit artifacts**, with no factual finding retracted.
Corrections narrowed probe imports, disabled source bytecode writes, and made
the shell-probe impact and process-level I/O guard description precise.

The review does not validate real GitHub execution, certificate compatibility,
release downloads, publication, devices, or providers.

## HTTP smoke

After review, the parent ran the actual router on `127.0.0.1:18107` with
temporary runtime data, filtered environment, blocked dotenv loading, an empty
synthetic configuration, and Uvicorn lifespan disabled. Background jobs, ASR
warmup, integrations, and release operations were not started. Provider health
URLs pointed at an unused loopback port; deployed services were not probed.

**28 curl checks passed:** 23 returned 200, three traversal attempts returned
400, an anonymous request to the synthetic dedicated mobile host returned 401,
and that host rejected `/api/events` with 404. Checks covered the root,
dashboard, screen, static entry, auth configuration, status, memory/workspace,
tool catalog, mobile capabilities/empty job inventory, direct dashboard-entry
scripts/styles/module preloads, registration script, and service worker.
Memory, workspace-file, and mobile-web-preview parent paths were rejected.

This is HTTP routing and direct-asset verification, not browser rendering,
recursive asset closure, a real release, or external-host certification. The
temporary process exited successfully and a socket check confirmed port 18107
was closed. macOS output remains muted. No release artifacts or product
implementation were changed by this audit.
