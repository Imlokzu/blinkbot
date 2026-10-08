# Audit evidence — 2026-10-08

Starting revision: `793beed2`. Existing unrelated tracked/untracked changes were
present before this pass. Only the dated audit artifacts and an audit handoff
pointer belong to this task. Product repairs in [REPORT.md](REPORT.md) remain
proposals.

## Fresh validation

- `tests/test_screen_store.py` and `tests/test_chat_uploads.py`: **82 passed**,
  in 15.87 seconds on the final guarded run. One existing Starlette/httpx
  deprecation warning remains.
  Run with the Virtual Bot Python interpreter, offline mode, temporary runtime
  paths, synthetic configuration, and real repository dotenv reads blocked.
  The final rerun also denied owner profile/runtime file reads and writes.
- The five observations in `probes.py` all reproduced. No deployed service,
  real package, credential, owner profile, conversation, or device was used.

macOS output was muted before execution and remains muted. These checks cover
current working source, including other agents' pending edits; they do not
certify a clean checkout or any proposed repair. The broader historical test
counts in older audits are not fresh results for this pass. No dashboard/mobile
build or external publication was needed for these report-only changes.

## Reproduce the observations

From the repository root:

```bash
"Virtual Bot/.venv/bin/python" reports/repository-audit-2026-10-08/probes.py
```

The script imports the real `screen_store.py` and `chat_attachments.py`, with a
minimal synthetic `app_config` containing only a temporary `STORE_DIR`. It does
not import the application, tool registry, or lifespan. Inherited environment
variables are cleared except PATH/TMPDIR/LANG/LC_ALL. A Python audit hook blocks
dotenv reads, socket connects, DNS lookups, and binds. Source bytecode writes are
disabled. All files are created in a temporary directory and removed afterwards.

| Scenario | Actual result |
| --- | --- |
| Installed app → same-ID skin → remove | Old installed HTML remains; shared-app policy becomes `None` |
| Failure of staging promotion | `io_error`; final source absent; hidden backup and installed HTML remain |
| Damaged stored ZIP member | Raw `BadZipFile`, not handled `StoreError` |
| Compressible over-budget package | Export returns 8,564 bytes; importer rejects 8,388,609-byte payload with `too_large` |
| Identical DOCX entity document, two encodings | UTF-8 rejected; UTF-16 accepted and expands 32 references to 672 characters |

The synthetic promotion failure intentionally logs an exception containing only
fixture paths and text. It is evidence of an injected failure, not a new host
incident. The entity document is small; no amplification attack is attempted.
The exported payload is about 8 MiB of inert repeated characters, not a zip bomb.

## Independent review and HTTP smoke

Two separate maximum-effort supported native reviewers applied the installed
code-reviewer and architect instructions. The configured specialist model
returned an unsupported-model error, and Fable was unavailable; this is a native
fallback review, not Fable certification. Both reviewers reran the five probes.
The code/spec/security lane returned **APPROVE** for the audit artifacts and the
architecture lane returned **CLEAR**. Corrections clarified observed behavior
versus inferred impact and credited the earlier general receipt proposal.

Python AST checks passed for the probe and repair snippets. LSP and ast-grep
tools were unavailable, so the explicitly authorized AST/probe fallback was
used. Separately, the proposed expat callbacks rejected DTDs in UTF-8, UTF-16LE,
and UTF-16BE while accepting small ordinary XML documents in each encoding.
This is an example check, not validation of an integrated production repair.

After review, the parent started the actual `main.app` on `127.0.0.1:18108`,
with Uvicorn lifespan disabled. The temporary launcher redirected brain,
workspace, chats, uploads, store, widgets, profile, mobile database, usage,
integrations, and OpenClaw configuration. Python guards denied real runtime/
profile/dotenv reads and external DNS/connections. Provider health targets
pointed to an unused loopback port. No pollers, ASR warmup, scheduler, TTS,
browser, real package installation, or external provider request was started.

**28 routing/direct-asset curl checks passed:** 23 returned 200, three traversal
attempts returned 400, the synthetic mobile host required authentication with
401, and that host excluded `/api/events` with 404. Coverage included the root,
dashboard, screen, static entry, auth configuration, status, empty memory/
workspace/mobile inventory, tools, eleven dashboard-entry script/style/module
preload references, and the service worker. Traversal checks covered memory,
workspace-file, and mobile web-preview parent paths.

**Eight additional curl expectations confirmed reported defects:** an inert
shared app initially returned its sandbox CSP; after the same-ID skin update
and removal, the identical HTML still returned 200 without CSP. A corrupt-member
archive returned 500. Export returned a small archive that import rejected with
400 and `too_large`. These are reproductions of existing failures, not healthy
behavior being counted as successful repairs. The temporary store contained
only synthetic files, and no executable malicious payload was used.

All 36 expected statuses/header/body assertions matched. The server exited with
code 0; a socket check confirmed port 18108 closed. This verifies HTTP routing
and directly referenced assets, not browser rendering, a complete recursive
asset graph, provider integration, or public deployment security. macOS output
remains muted.
