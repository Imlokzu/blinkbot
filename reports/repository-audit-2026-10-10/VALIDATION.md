# Audit evidence — 2026-10-10

Starting revision: `eb1436be`. Other agents' unfinished tracked/untracked work
was already present. Only the dated audit folder and an audit handoff pointer
belong to this task. Product repairs in [REPORT.md](REPORT.md) remain proposals.

## Reproduced observations

From the repository root:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-10/probes.py
```

The script loads real `projects`, `chat_store` and `chat_interactions`, with
minimal synthetic workspace-root, owner and profile adapters. It does not load
app configuration, the application/lifespan, tools, providers or real runtime
state. Python guards block dotenv and socket operations, inherited environment
is filtered, and bytecode writes are disabled. All folders, symlinks, metadata,
conversation files and SQLite data belong to one temporary directory. It is
removed after execution. The synthetic profile adapter reads no owner profile.

| Scenario | Observed result |
| --- | --- |
| Invalid metadata array | `AttributeError` stops the project list |
| Invalid metadata timestamp | `ValueError` stops the project list |
| Invalid display-name type | Dictionary returned as `name` |
| Project-directory symlink | Outside synthetic name listed; outside metadata changed by rename |
| Fixed temporary-file symlink | Separate outside synthetic JSON file changed |
| Two event-gated rename threads | A returns B's name; B raises `FileNotFoundError` |
| Expired interaction quota | Two expired rows retained; sole current call rejected with `interaction_limit` |

The thread fixture coordinates real writes/replaces, with bounded event waits
and executor shutdown while patches remain active. It injects no write error.
It is a concurrent module probe, not a same-worker HTTP race. The interaction
fixture temporarily sets `MAX_RECORDS=2`; production remains 2,000. It replaces
only synthetic transcript data to model old calls no longer being authorized.
No victim data, remote attack, or production incident is claimed.

## Existing suites

`tests/test_projects.py` and `tests/test_chat_interactions.py`: **30 passed,
four subtests passed**, in 3.32 seconds on the final guarded run. One existing
Starlette/httpx deprecation warning remains. The Virtual Bot interpreter ran in
offline mode with synthetic configuration/issuer and temporary runtime paths.
The wrapper denied repository `.env*` opens and owner profile/runtime access.
The final run used a dedicated pytest base directory inside its temporary root.

The first run also passed all 30 tests, but emitted 20 cleanup warnings while
pytest tried to remove unrelated old directories in its shared default temp
area. Nothing in the product was changed for this harness issue. Moving pytest
to a dedicated base directory removed those warnings on the final run; no
manual cleanup of other agents' directories was performed.

These checks exercise current working source, not a clean checkout excluding
other agents' pending edits. No dashboard, mobile or deployment build was needed
for report-only changes. Historical full-suite counts in previous reports are
not new results here. macOS output was muted before checks and remains muted.

## Independent review and post-review smoke

Two separate maximum-effort supported native reviewers applied the installed
code-reviewer and architect instructions. The configured specialist model
returned an unsupported-model error; Fable was unavailable. This is a native
fallback review, not Fable certification. Code/spec/security verdict:
**APPROVE** for the three audit artifacts. Architectural status: **CLEAR**.

Both reviewers reran the guarded Python probe and confirmed all four findings
and their conditional impact. The code reviewer corrected the code-delete
containment citation. The architecture review refined reclamation to require a
successful validated source read, and clarified shared atomic revision checks.
Python AST/compile checks passed for the probe, five related modules, and repair
snippets; Markdown links were checked. LSP/ast-grep tools were unavailable, so
the explicitly authorized source/syntax/probe fallback supplied diagnostics.
Reviewers changed no product files and used no owner runtime data or credentials.

After review, the actual `main.app` ran on `127.0.0.1:18110` with Uvicorn lifespan
disabled. Its launcher redirected code/projects, workspace, brain, chats,
uploads, store, widgets, profile, mobile/usage databases, integration and OpenClaw
paths into a temporary root, with a filtered environment and synthetic config.
Guards denied real runtime/profile/dotenv reads and external DNS/connections.
Provider health targets pointed at an unused loopback port; no background jobs,
ASR/TTS, integration polling or provider/device calls were started.

**29 routing/direct-asset curl checks passed:** 24 returned 200, three traversal
attempts returned 400, the synthetic mobile host required authentication with
401 and excluded `/api/events` with 404. Checks covered root, dashboard, screen,
static entry, auth config, status, projects, empty memory/workspace/mobile
inventory, tools, eleven dashboard script/style/modulepreload references and
the service worker. Memory, workspace-file and mobile web-preview parent paths
were rejected.

**Seven further HTTP expectations confirmed existing defects:** array metadata
made `/api/projects` return 500; a bad timestamp returned 400; replacing that
fixture with valid metadata restored a 200 list. A synthetic directory symlink
was listed and its external metadata was changed by the real rename route. A
temporary-file symlink changed another outside fixture through that route.
Finally, the real interaction route returned 409/`interaction_limit` with exactly
2,000 expired rows pre-seeded in its temporary database and one live call. This
uses the production quota without changing the server constant. None of the
expired rows represented a real user action.

All 36 expected statuses and associated body/file assertions matched. Defect
responses are reproductions, not passing repairs. No concurrent HTTP rename
race was attempted; P-03 remains a controlled module/thread observation.
The server exited with code 0, and port 18110 was confirmed closed. This covers
actual routing and direct published assets, not browser rendering, a recursive
asset graph, provider integration or public deployment security. No product fix
was applied; macOS output remains muted.
