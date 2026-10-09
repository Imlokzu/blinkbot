# Audit evidence — 2026-10-09

Starting revision: `a6291e37`. Existing unrelated tracked/untracked changes were
present. Only this dated folder and an audit handoff note belong to the task.
Repairs in [REPORT.md](REPORT.md) are proposals.

## Fresh checks

- Dashboard `npm test`: **227 passed**, zero failures/skips, in 3.64 seconds.
  `npm run typecheck` passed. No dashboard production build was run, because
  ordinary builds publish shared live assets and this task changes reports.
- Backend `tests/test_activity_stream.py` and
  `tests/test_mobile_question_stream.py`: **20 passed**, in 2.75 seconds on the
  final guarded run. One
  existing Starlette/httpx deprecation warning remains. A temporary wrapper used
  offline mode, synthetic configuration/issuer, temporary runtime paths, and
  blocked owner profile/runtime and real repository dotenv file access. The
  final guard also rejects all repository `.env*` opens, rather than only the
  known backend/panel dotenv paths.
- Python and browser observations below reproduced. They exercise current
  working source; other agents' pending work is not certified as a clean build.

The Mac was muted before checks and remains muted. No real credentials,
conversations, owner profiles, provider/device sessions, or installed screen/store
packages were used. Previous report test counts are historical, not results for this pass.

## Reproducible observations

Run from the repository root with the existing local backend/dashboard packages
and `agent-browser` installed:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-09/probes.py
node reports/repository-audit-2026-10-09/probes.mjs
```

The Python fixture loads real `ui_tools` and `ActivityLog` without the tool
registry initializer, app configuration, or application. It supplies synthetic
event/owner adapters, a deterministic fixture interaction ID, filters inherited
environment settings, blocks dotenv and Python socket operations, and disables
bytecode writes. All output contains synthetic arguments and redacted activity.

The browser fixture compiles actual `InteractiveToolCard`, `interactiveToolData`,
`useChatRuntime`, activity/timeline/navigation/token helpers, and English locale
code into a temporary folder. It extracts the actual `ChatPanel` message-bridge
effect through a TypeScript AST; it does not reimplement that effect or mount
the complete ChatPanel layout. Real React/ReactDOM run in an isolated Chromium
session. Icons, toast/query adapters, assistant-ui plumbing, and API/stream
adapters are fixtures. An unresolved synthetic stream records calls, feeds its
real tool handler, and releases on cancellation; it makes no provider request.

Vite runs at `127.0.0.1:18119`, with no repository config/env loading, a temporary
cache, and no proxy. Browser traffic is allowlisted to `127.0.0.1`, with an empty
explicit browser configuration and a fresh daemon namespace. The fixture never
loads the owner dashboard, browser profile, stored state, or external pages. It
closes its named browser and Vite server and removes temporary files.
This is browser-level containment, not an operating-system firewall.

| Scenario | Observed result |
| --- | --- |
| Valid question with no options | Backend succeeds; inline renderer has zero cards/inputs |
| Existing-session turn plus early question event | Card says “Answer sent”; only the original stream call exists; source stream remains running and un-aborted |
| Tick and remount same checklist | Checkbox returns to unchecked; zero mutation calls |
| Remount answered marker | Sent marker disappears and option becomes enabled; no duplicate-delivery claim |
| Normalization agreement | Choice label: Python 80 vs inline 100 characters; todo text: Python 180 vs inline 120 |

Initial browser harness attempts failed because the installed TypeScript package
has no JS transpiler API, then because synchronous browser execution blocked
the fixture server and async stdin was not closed. The reviewed harness uses
the installed Vite transformer and asynchronous child execution with explicit
stdin closure. Those were audit-harness defects; no product code was changed.
An initial focused pytest invocation used the wrong working directory and ran
no tests; the successful run above used `Virtual Bot/` as its working directory.

## Independent review and post-review smoke

Two separate maximum-effort supported native agents applied the installed
code-reviewer and architect instructions. The configured specialist model was
unsupported and Fable was unavailable; this is a native fallback review, not
Fable certification. Code/spec/security verdict: **APPROVE** for the four audit
artifacts. Architectural status: **CLEAR** after correcting proposal boundaries.

The code reviewer reran Python and browser probes, checked Node syntax, Python
AST and Markdown fences, and corrected browser isolation to use an empty
configuration, fresh namespace, and temporary working directory. Fixture port
18119 was released. The architecture reviewer independently reran the Python
probe and checked callback stability against the real provider/client setup.
Corrections acknowledge mobile's existing queued answers, require atomic
checklist updates, and specify the new fork-prefix policy needed for continuation.
LSP/ast-grep tools were unavailable; source tracing, AST/transformation and
isolated probes were the explicitly authorized diagnostic fallback.

After review, the actual `main.app` ran on `127.0.0.1:18109`, with Uvicorn
lifespan disabled and temporary brain, workspace, chats, uploads, store, widgets,
profile, mobile/usage database, integration and OpenClaw paths. The launcher
filtered inherited environment, supplied synthetic configuration, denied real
runtime/profile/dotenv reads, and blocked external DNS/connections. Provider
health targets pointed at an unused loopback port. Background jobs, ASR/TTS,
integrations and real provider/device activity were not started.

**28 curl checks passed:** 23 returned 200; memory, workspace-file and mobile
web-preview traversal attempts returned 400; the synthetic mobile host required
authentication with 401 and excluded `/api/events` with 404. Coverage included
the root, dashboard, screen, static entry, auth configuration, status, empty
memory/workspace/mobile inventory, tools, eleven directly referenced dashboard
script/style/modulepreload assets, and the service worker.

The server exited with code 0 and port 18109 was confirmed closed. This checks
actual routing and direct published assets; it does not render the complete
dashboard, prove that current source is published, walk a recursive asset graph,
test real answer delivery, or certify public deployment/provider behavior.
Browser probes establish the reported source behavior under their explicitly
documented fixture adapters. macOS output remains muted.
