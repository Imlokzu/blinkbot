# Audit evidence — 2026-10-12

Starting revision: `147a0818`. Other agents' unfinished tracked/untracked files
were already present. Only this folder and an audit handoff pointer belong to
this task. Repairs in [REPORT.md](REPORT.md) remain proposals.

## Reproducible observations

From the repository root, using existing dashboard dependencies and Node:

```bash
node reports/repository-audit-2026-10-12/probes.mjs
```

The script imports the real detector and wake parser, then extracts actual
screen functions through a Babel AST. Node VM environments provide inert
media, clock, DOM/presentation, timer and HTTP adapters. `MediaRecorder` emits
only synthetic bytes; ASR/history responses are manually resolved promises.
`sendChat` records dispatch text/session rather than contacting the bot.
It loads no app configuration, dotenv, real runtime, profile or stored media.
Inherited environment is filtered. macOS output is muted before execution.
No microphone, browser session, audio playback or provider call is started.

| Scenario | Actual result |
| --- | --- |
| Idle unarmed Wake mode, unnamed transcript | No dispatch; captured intent remains false |
| Same transcript during active reply | Source aborts; captured intent becomes true; unnamed text dispatches |
| Two clips with first ASR pending | Two uploads; first signal aborted; only second transcript dispatches |
| Continuous level through two recorder caps, first ASR pending | Clips stop at synthetic 15.2/30.4 seconds; first signal aborted; only “twelve minutes” dispatches |
| Pending ASR followed by new-chat | Old signal remains active; dispatch targets new `screen-8` selection |
| Open A then B, resolve B then A | Selection remains B; final title and displayed history belong to A |

The probes execute the source control flow, not a browser/device integration.
They establish routing/state behavior, not ASR accuracy, physical echo,
network timing, paid turn execution, or cross-owner access. Recording rollover
uses a synchronous fixture stop event; actual browser event timing may differ.
The cap case drives the real `onMicLevel()` stop condition rather than manually
stopping a phrase. The separate two-clip observation can also be consistent with
an intentional latest-utterance-wins policy; it does not by itself prove a queue
is required.

Some `rg` searches stalled in this environment. Only this task's known search
processes were terminated; direct-file Python searches/reads completed discovery.
No unrelated service or agent process was stopped.

## Existing checks

`test_screen_voice.py`, `test_screen_voice_review.py`, `test_wake_feedback.py`
and `test_wake_feedback_review.py`: **81 passed**, in 9.52 seconds. One existing
Starlette/httpx deprecation warning remains. The Virtual Bot interpreter used
offline mode, a synthetic issuer/configuration, temporary runtime paths,
repository dotenv/owner-profile/runtime access guards and a dedicated pytest
base directory. Provider clients in these existing tests use fixture adapters;
no real paid request or audio output was used.

Node syntax, Markdown fences and local links passed. Current working source
includes other agents' pending edits; these checks do not certify a clean
checkout. No dashboard/mobile production build was needed for report-only
changes. Historical suites in previous audits are not fresh results here.
macOS output remains muted.

## Independent review and post-review smoke

Two separate maximum-effort supported native reviewers applied the installed
code-reviewer and architect instructions. The configured specialist models were
unsupported, and Fable was unavailable. This is native fallback review, not
Fable certification. Code/spec/security verdict: **APPROVE** for the three
artifacts. Architectural status: **CLEAR**, scoped to the report and probes.

Both reviewers reran the final muted Node probe; all six observations passed.
The code reviewer added the automatic cap-split case and narrowed V-02 so ordinary
latest-utterance-wins is not incorrectly declared a defect. Architectural review
preserved the first-sound intent capture, made queue behavior conditional, and
required one selection generation across admission/permission/partial/final/
success/failure/cleanup. Supplemental real-function VM checks confirmed late
history failure clearing a newer view, old history repopulating a fresh chat,
and navigation during recording routing old input to the new selection. These
are reviewer checks of existing findings, not extra standalone findings.

Node syntax, AST extraction, report fences and links passed. LSP/ast-grep tools
were unavailable; source/syntax/probes were the explicitly authorized diagnostic
fallback. No product files were changed by either reviewer.

After review, the actual `main.app` ran on `127.0.0.1:18112` with Uvicorn lifespan
disabled. The temporary launcher redirected brain, workspace/code, chats,
uploads, store, widgets, profile, mobile/usage databases, integrations and
OpenClaw state. It filtered inherited environment, supplied synthetic config,
denied real runtime/profile/dotenv reads, and blocked external connections/DNS.
Provider health targets pointed at an unused loopback port. No background jobs,
ASR/TTS warmup, integration polling, microphone, speech synthesis or paid turn
was started.

**34 curl expectations passed:** 29 returned 200, three traversal attempts
returned 400, the synthetic mobile host required authentication with 401 and
excluded `/api/events` with 404. Coverage included root, dashboard, screen,
static entry, auth config, status, projects, empty memory/workspace/mobile
inventory, tools, eleven direct dashboard script/style/modulepreload references,
the service worker, and five screen scripts (`screen.js`, `voice-turn.js`,
`wake.js`, `wake-cues.js`, `reply.js`). Memory, workspace-file and mobile
web-preview parent paths were rejected.

The server exited with code 0 and port 18112 was confirmed closed. This verifies
actual routing and direct assets; it does not render the screen, recursively
check assets, exercise live recognition/history races through a browser, or
certify provider/deployment behavior. The function probes supply the documented
callback evidence. No product repair was applied; macOS output remains muted.
