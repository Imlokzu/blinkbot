# Audit evidence — 2026-10-11

Starting revision: `6a3078c5`. Other agents' unfinished tracked/untracked changes
were already present. Only this folder and an audit handoff pointer belong to
the task. Product fixes in [REPORT.md](REPORT.md) remain proposals.

## Reproducible observations

From the repository root:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-11/probes.py
node reports/repository-audit-2026-10-11/probes.mjs
```

The Python fixture loads real `screen_widgets`, `timer_tools` and `site_share`
with a temporary configuration base, inert event bus, and a synthetic workspace
resolver. The resolver returns fixture paths without checking existence because
the publication probes target snapshot/step behavior, not path authorization.
It bypasses `tools/__init__.py`, reads no application configuration/credentials,
starts no application/lifespan, filters inherited environment, blocks dotenv and
Python socket operations, and disables source bytecode writes. Widget and site
registries belong to a temporary directory removed after execution.

Publication adapters replace ingress, DNS and connector restart. The concurrent
fixture waits for both coroutines at ingress with an asyncio event and bounded
timeout. It runs real registry loading/saving; no remote API or process restart
occurs. The failure fixture changes only the fake remote registry before raising
a harmless DNS error. No Cloudflare credential, vault, tunnel or owner site is
used.

The Node fixture parses the actual screen source with the dashboard's installed
Babel parser, extracts `timerLeft`, `setTimers`, `ringTimerDone`, `stopRing` and
`tickTimers`,
and executes them in a VM with a synthetic clock. DOM/presentation callbacks,
interval scheduling and alarm audio are inert adapters. Alarm calls are counted;
no sound or speech is generated. Its Python child uses the same guarded fixture.
This is function-level screen execution, not a browser/physical-screen test.

| Scenario | Actual result |
| --- | --- |
| Initial timer deadline | One alarm adapter call |
| Stopping the first alarm | Real `stopRing()` clears `ringUntil` using inert interval/render adapters |
| Completed timer restarted under same ID | Zero additional alarm calls at the new deadline, after the original ring window expires |
| Fall clock-time reminder | Requested 09:00 resolves to 08:00 local |
| Spring clock-time reminder | Requested 09:00 resolves to 10:00 local |
| Two concurrent publications | Both report success; only one slug remains locally/remotely in fixtures |
| DNS failure after ingress | Error returned; fake remote registry has new slug absent locally |

The reminder fixture uses Python fixed-offset datetime values representing the
same current Berlin instants as the real default conversion. Regional expected
deadlines use installed `ZoneInfo` data. No system clock/timezone setting is
changed, and no real reminder is scheduled.

## Existing checks

`tests/test_screen_widgets.py`: **22 passed**, in 0.83 seconds. One existing
Starlette/httpx deprecation warning remains. The Virtual Bot interpreter used
offline mode, synthetic configuration/issuer, temporary runtime paths, denied
repository dotenv/owner-profile/runtime access, and a dedicated pytest base.
The first wrapper attempt referenced a temporary file from the previous day
that no longer existed and ran no tests; a fresh wrapper produced this result.

No product source changed. Historical suites in other audits are not fresh
results for this pass. No mobile/dashboard build or external deployment was
needed. macOS output was muted before checks and remains muted.

Python 3.12 documentation and official Cloudflare documentation were read for
timezone and publication context. The web connector returned an authentication
error; official pages were retrieved directly with bounded curl requests. No
source-provider credentials or authenticated cloud operations were involved.
Findings are based on repository source and synthetic observations, not claims
of measured Cloudflare behavior.

## Independent review and post-review smoke

Two separate maximum-effort supported native reviewers applied the installed
code-reviewer and architect instructions. The configured specialist model was
unsupported and Fable was unavailable; this is native fallback review, not Fable
certification. Code/spec/security verdict: **APPROVE** for the four artifacts.
Architectural status: **CLEAR** after the proposal clarifications.

Both reviewers ran Python and Node probes. The code reviewer isolated the timer
reactivation by executing real `stopRing()` and checking a new deadline after
the first ring window. The architecture lane also checked same-zone subtraction
against elapsed UTC time and exercised a separate synthetic cancellation after
remote apply; it observed local/remote divergence. That additional cancellation
check is reviewer evidence, not an extra standalone finding or a live operation.
Corrections clarify activation renewal, regional-time ambiguity, attempted DNS
setup, and fencing of unknown remote outcomes. Python AST and Node syntax checks
passed. LSP/ast-grep tools were unavailable; source/syntax/probes were the
explicitly authorized diagnostic fallback. No product files were changed.

After review, the actual `main.app` ran on `127.0.0.1:18111` with Uvicorn lifespan
disabled. The temporary launcher redirected brain, workspace/code, chats,
uploads, store, widgets, profile, mobile/usage databases, integrations and
OpenClaw state. It filtered the environment, used synthetic configuration,
denied real runtime/profile/dotenv reads and blocked external connections/DNS.
Provider health targets pointed at an unused loopback port. No background jobs,
ASR/TTS warmup, integration polling, Cloudflare mutation or production restart
was started.

**30 routing/direct-asset curl checks passed:** 25 returned 200, three traversal
attempts returned 400, the synthetic mobile host required authentication with
401 and excluded `/api/events` with 404. Coverage included root, dashboard,
screen, static entry, auth config, status, projects, empty memory/workspace/mobile
inventory, timers, tools, eleven direct dashboard script/style/modulepreload
references and the service worker. Memory, workspace-file and mobile web-preview
parent paths were rejected.

**Three additional timer-route checks passed:** create a one-second inert timer,
read its completed state after the deadline, and restart it for two seconds with
`add`. The real API kept the same ID and returned a running timer. These checks
confirm backend transition wiring; the Node probe separately establishes the
screen delivery-marker defect. Curl does not play audio or run screen JavaScript.

All 33 statuses and timer body assertions matched. The server exited with code 0
and port 18111 was confirmed closed. This is routing/direct-published-asset
verification, not browser rendering, a complete recursive asset graph, provider
integration or public deployment certification. Widget test and probe data were
temporary; no product fixes were applied and macOS output remains muted.
