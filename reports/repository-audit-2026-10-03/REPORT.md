# Repository audit — 2026-10-03

My recommendation is to make successful work durable and recoverable before
adding more integrations. A response, a saved document, and a published site
currently have different definitions of success; several failures below are
hidden from the person using the bot.

This folder follows the existing `reports/repository-audit-2026-10-02/`
convention. It adds a fresh review of chat persistence, workspace publishing,
coding-process lifecycle, and the file editor. Product code was not changed.
Fixes and snippets below are proposals, not installed changes.

## Scope and evidence

The starting commit was `9a33139`. The checkout also contained other agents'
staged and unstaged changes, including `main.py`, its tool tests, `HANDOFF.md`,
and dashboard work. Findings describe the working source inspected during this
pass; named functions accompany line references so later edits remain traceable.

I compared the October 1 and October 2 reports. The forwarded-header tool fix
is present in the starting commit and is documented in
`reports/night-agent-tool-bridge-2026-10-02.md`; it is not reopened here. The
older launcher, custom-MCP, shared-profile, integration-ownership, and image
proxy findings remain separate work. The October 1 concurrent chat-store
mutation finding also remains separate: F-03 concerns error reporting on a
failed write, not lost updates from competing successful writes. The history-cache
issue below concerns conversation messages, not the shared profile root.

Seven isolated probes reproduced backend/lifecycle behavior. All chat-model,
display, fact-extraction, cloud publishing, and tunnel-restart calls in those
probes were mocked. Only synthetic content and temporary directories were used.
The file-editor finding is supported by source tracing; it has not been
reproduced in a browser during this pass. See [VALIDATION.md](VALIDATION.md)
and [probes.py](probes.py) for the checks and their limits.

## Ranked findings

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| F-01 | High if multiple accounts use a history-consuming reply path | Chat cache ignores the account owner | Cache contamination reproduced; reply impact inferred |
| F-02 | High for document work | File-editor refreshes can overwrite unsaved changes | Source tracing |
| F-03 | High for durability | Chat disk-save failures look like normal success | Reproduced |
| F-04 | Medium | Deleting a conversation leaves its cached history alive | Reproduced |
| F-05 | Medium | Invalid conversation IDs receive successful unsaved replies | Reproduced |
| F-06 | Medium | A shared session site loses its workspace context | Reproduced with publishing mocked |
| F-07 | Medium | Unread coding-worker stderr can stall the protocol | Reproduced with a synthetic child |
| F-08 | Medium | Coding-session registration can replace a worker before startup | Reproduced registry behavior; concurrent impact inferred |

### F-01 — In-memory history is shared by ID across accounts

**Evidence:** `Virtual Bot/main.py:1859`, `_sessions`, is a process-wide
dictionary keyed by the raw session ID. `_get_history` at `:1891-1905` consults
that dictionary before the disk store; `_save_history` at `:1915-1939` writes
the same key. In contrast, `Virtual Bot/chat_store.py:58-65` puts disk histories
under the active Clerk user's directory.

**Observed:** saving a synthetic exchange under Alice's context and reading the
same ID under Bob's context produced an empty Bob disk history but Alice's two
cached messages from `_get_history`. The request context does not partition the
dictionary. Explicit client-provided history bypasses that branch, so this
does not affect every request; it affects accounts using the same ID and server
history while the entry remains cached.

**Impact limit:** the primary OpenClaw path does not consume this array:
`brains.chat_openclaw` at `Virtual Bot/brains.py:795-798` discards application
history when supplied the account-specific stable Gateway key. This probe
therefore does not show cross-account leakage through the primary Gateway.
The demo fallback does consume history (`brains.py:1736-1747`, `:1979`), but
`chat.demo_fallback` is disabled in the inspected configuration. High impact
is conditional on multiple accounts using that or another history-consuming
reply path; contamination of the local cache itself is reproduced.

**How I would fix it:** introduce one cache-key helper and use it for reads,
writes, compaction, deletion, and expiry. Include account and conversation kind:

```python
import brain_context
import chat_store

def history_cache_key(session_id: str) -> tuple[str, str, str]:
    return (
        brain_context.get_active_clerk_user() or "",
        chat_store.active_kind(),
        session_id,
    )
```

**Acceptance check:** two users and both chat kinds may reuse the same ID;
their histories stay independent before and after a process restart. Reuse the
existing per-account disk layout and OpenClaw session mapping.

### F-02 — FilesPanel does not protect a dirty draft from refetches

**Evidence:** `Virtual Bot/dashboard/src/panels/files/FilesPanel.tsx:83-90`
unconditionally runs `setDraft(file.data.content)` and `setDirty(false)` whenever
`file.data` changes. The comment says only a different file should reset the
draft, but the dependency is the returned data object. `save` at `:92-102`
also marks the draft clean after an awaited request without comparing it with
the submitted text.

The editor's reload button directly calls `file.refetch()` at `:239-245`.
Identical results can retain their object identity through React Query's
structural sharing; the destructive effect requires changed returned data.
Window-focus refetching is disabled in `dashboard/src/main.tsx:11-18`.

**Inference:** if a same-file refetch returns changed server data while the
user has edits, the effect replaces those edits. If the user types more while
a save is pending, the older save response marks the newer draft clean. No
browser reproduction is claimed here. The existing Workbench provides a useful
local example: `Workbench.tsx:329-343` captures the submitted text and clears
only a matching draft.

**How I would fix it:** hold draft state per owner/path; keep a separate server
baseline and revision. A refresh updates the baseline and shows an external-change
notice while preserving a dirty draft. At save completion, clear dirty state
only if the current path and text still match the submitted snapshot. Serialize
overlapping writes and eventually require the baseline revision on save.

**Acceptance check:** edit a file, refetch a changed server version, and retain
the draft. Delay a save, type a second edit, then resolve the first request;
the second edit must still show as unsaved. Test switching files during that
request too. Put new notices in the existing English/Ukrainian locale modules.

### F-03 — The chat reply omits a failed persistence status

**Evidence:** `chat_store.append` at `Virtual Bot/chat_store.py:326-335`
returns `None` on a failed filesystem write. `_save_history` at
`Virtual Bot/main.py:1929-1939` updates memory before the disk operation and
also returns `None` if an exception escapes. The ordinary reply at
`main.py:2279-2314` still returns the generated answer, with empty message IDs.
The response has no explicit save-failed field.

**Observed:** an injected `OSError` on the temporary file write inside
`chat_store.append` yielded HTTP 200, the expected
reply text, empty message IDs, no disk messages, and no persistence status.
The fixture recorded one failed filesystem write and retained both messages
in the local cache. That application transcript lasts until a restart or cache
loss; the primary Gateway may retain its own transcript independently. No
Gateway persistence failure was reproduced.

**How I would fix it:** keep the useful generated answer, but make durability
explicit with `persistence: "saved" | "failed"` and a stable error code. Show a
localized unsaved warning and offer a persistence retry using a turn ID, so a
retry does not run the model again or duplicate the exchange. Apply the same
contract to SSE completion and messenger replies.

**Acceptance check:** simulate disk-full and permission failures. The response
must disclose the failure, preserve the answer for retry, and save exactly one
exchange when storage becomes available again.

### F-04 — Conversation deletion invalidates disk but not memory

**Evidence:** `api_session_delete` at `Virtual Bot/main.py:2987-2993`
deletes through `chat_store.delete` but never removes the `_sessions` entry.
The nearby compaction path at `:2980-2983` does invalidate memory, showing the
two paths are inconsistent.

**Observed:** a fixture chat returned 200 and had two disk messages before
deletion; DELETE returned `{"ok": true}`; the disk history became empty,
while `_get_history` still returned its two messages. A later consumer of
that array can therefore reuse deleted context. As in F-01, this does not
demonstrate that the primary Gateway reads the cached array.

**How I would fix it:** after a successful delete, invalidate the account/kind
cache key from F-01. Cancel or reject a late completion for a deleted session,
using a conversation generation/tombstone, so an in-flight turn cannot recreate
it silently. Define the Gateway deletion policy separately: clearing this cache
does not clear the account-specific Gateway transcript. If deletion promises
context removal there too, invalidate or rotate that conversation lineage using
the existing runtime's supported lifecycle operations.

**Acceptance check:** delete, read through both disk and cache paths, then
retry a late reply. Deleted local context must remain unavailable. The probe
covers cache invalidation; the in-flight completion and Gateway deletion-policy
cases still need their own regression tests.

### F-05 — Chat accepts IDs that the store refuses

**Evidence:** `ChatRequest.session_id` at `Virtual Bot/main.py:508` only
limits length. `_get_or_create_session_id` at `:1875-1880` accepts any nonempty
ID within that length. `chat_store.is_valid_id` and `_path` at
`chat_store.py:81-96` require letters, digits, underscores, or hyphens.

**Observed:** a chat ID containing spaces received HTTP 200 and was returned
unchanged, with an empty message ID and no listed saved conversation.

**How I would fix it:** validate a supplied ID before attachment extraction or
model work, and use the same validator across chat, coding, memory, and session
routes. An empty ID can still generate a fresh ID. A minimal boundary check is:

```python
from fastapi import HTTPException
import chat_store

def validate_supplied_session_id(value: str) -> str:
    session_id = value.strip()
    if session_id and not chat_store.is_valid_id(session_id):
        raise HTTPException(status_code=400, detail="invalid_session_id")
    return session_id
```

**Acceptance check:** IDs with spaces or punctuation fail before any model
invocation; valid IDs and empty-ID creation retain their existing behavior.

### F-06 — Published session paths do not identify the publication's root

**Evidence:** `Virtual Bot/site_share.py:183-206`, `share`, resolves the
path under the current workspace context, but stores the original string at
`:194-195`. `serve_shared_site` at `Virtual Bot/main.py:4248-4273` resolves
that string later without restoring the creator's user or session context.
`workspace._expand_session_prefix` at `workspace.py:134-140` expands `session/`
using the current context, whose default is `default`.

**Observed:** publishing `session/site` from `publishing-fixture` reported
a URL in a fixture with DNS/ingress/restart mocked. GET `/site/fixture-site`
returned 404 because the reader looked in the default session. This confirms
the local resolution bug, not a real cloud deployment. With another site under
the same default-relative path, the wrong content could be selected.

**How I would fix it:** publish a validated, immutable site snapshot under a
dedicated public root. Store its snapshot ID, owner, publication revision, and
source provenance instead of a context-dependent alias. Serve only that public
snapshot; update it explicitly after the owner edits the source.

**Acceptance check:** publish a session site, change the request's active user
and session, and get the same published content. An unshared or replaced
snapshot must not remain publicly selectable.

### F-07 — Coding workers have a stderr pipe with no reader

**Evidence:** `Virtual Bot/coding.py:126-133`, `CodingSession.start`, creates
both stdout and stderr pipes. The protocol only reads stdout at `:169-192`.
There is no stderr-draining task in the module. The configured asyncio stream
limit is eight MiB, so the risk requires substantial diagnostic output, not
one normal warning.

**Observed:** a synthetic child announced `STARTED`, then wrote 20 MiB to
stderr before its ready line. With the coding module's stream limit, readiness
remained blocked; starting a stderr drain released the child and produced
`READY`. No real omp job was run.

**How I would fix it:** drain stderr concurrently for the entire worker
lifetime. Discard or keep a bounded, redacted tail; retain and await the drain
task during shutdown. For example, this reader discards diagnostics safely:

```python
async def drain_stderr(process) -> None:
    if process.stderr is None:
        return
    while await process.stderr.read(64 * 1024):
        pass
```

**Acceptance check:** a noisy fixture worker can become ready and complete
its protocol; a timeout/cancel reaps the worker and drain task. Do not expose
raw worker diagnostics, which can contain private data.

### F-08 — Two callers can obtain different unstarted workers for one key

**Evidence:** `coding.get_session` at `Virtual Bot/coding.py:446-458`
publishes a new `CodingSession` without starting it. A subsequent caller only
reuses it if `session.alive` is already true; otherwise it stops/replaces it.
`prompt` starts the worker later at `:244-247`.

**Observed:** two `get_session` calls for one key before either starts returned
different objects; the first was no longer registered. No concurrent production
request was simulated. **Inference:** overlapping requests in the startup
window can launch two workers, and stop/shutdown can only find the registered
one. The prompt lock is per object, so it does not join the two workers.

**How I would fix it:** retain an idle/starting session or a per-key startup
future until readiness settles. Give it an explicit lifecycle state; replace
only a failed/stopped worker or a deliberately changed working directory.
Serialize per-key startup without holding the global registry lock across a
slow subprocess startup.

**Acceptance check:** two overlapping first prompts obtain one registered
worker; stopping during startup cleans it up, and failure permits one clean
retry. Test model changes during startup as a separate lifecycle case.

## Ideas I would add

1. **An action receipt.** Give chat saves, file writes, and publications a
   stable operation ID with `running`, `applied`, `saved`, or `failed` state.
   A compact activity view could distinguish an answer produced by the model
   from a change durably stored. Start with chat-save receipts and idempotent
   persistence retries; keep the existing OpenClaw runtime.
2. **A draft recovery tray.** Preserve file drafts across navigation and
   external writes, with a retained baseline and a diff against the new version.
   Scope recovery records to owner/path, add retention and a discard action,
   and restore only after the user chooses. Reuse Tiptap/CodeMirror and the
   Workbench's submitted-text guard.
3. **Memory provenance and a precise Forget action.** Show which conversation
   supplied a remembered fact, when it was stored, and whether it was inferred
   or explicitly requested. Deletion should invalidate related caches and
   summaries as well as the note. Begin with provenance metadata on new facts;
   do not silently reclassify existing personal notes.
4. **A publish preview and explicit update button.** Show the exact public
   snapshot, its revision, and the source files it contains before publishing.
   Subsequent workspace edits stay draft until Update. A publication can then
   be reproduced and rolled back without relying on whichever session happens
   to serve the request.
5. **A small release health record.** Associate a source revision, backend
   contract version, and dashboard asset manifest with each release. The status
   page can show when the UI and backend belong to different builds. Validate
   references before replacing live assets, using the existing build tools.

## Suggested order

Fix history ownership and durable-save reporting first. Then protect file
drafts and deletion, followed by session-ID validation. Isolate publication
roots and make coding-worker startup/shutdown reliable before broadening those
features. Each fix should be a separate tested commit under `AGENTS.md`.

The current suites pass, but they do not settle these omitted scenarios.
The older security reports still matter; this pass supplies additional concrete
work and does not claim that the repository has received an exhaustive audit.
