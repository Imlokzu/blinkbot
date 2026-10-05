# Repository audit — 2026-10-05

My recommendation is to connect privacy and lifecycle guarantees across the
whole operation. A revocation should affect an already-open listener, a cancelled
generation should account for its unfinished disk work, and a file reservation
should cover moves as well as writes.

This dated folder follows the existing agent-audit convention under `reports/`.
Starting revision: `821e605c`. The working checkout contained unrelated staged
and unstaged changes. Product implementation was not edited. The report contains
findings, solution examples, and acceptance checks; none of the proposed fixes
is installed by this task.

## What this pass adds

The October 1–4 reports remain the baseline. Yesterday's queue/storage findings
are not repeated here. The event-stream privacy finding below is distinct from
October 4's audience-capacity issue: private data can be published with no target
audience at all. The workspace finding concerns the newer save reservation,
not the earlier unsafe-public-preview route finding.

New mobile work has added bounded image fetching, workspace downloads, and app
updates. The missing update-checksum gate already has a fix and a separate
report (`reports/night-agent-mobile-update-2026-10-04.md`). The new mobile image
proxy validates redirects and pins DNS destinations; this pass does not reopen
the older image proxy's separate SSRF issue as a defect in that new handler.

## Ranked findings

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| L-01 | High if the general dashboard API is accessible to untrusted viewers | Untargeted reply/log events reach anonymous listeners | Handler/stream probe reproduced |
| L-02 | High while a revoked device holds an active listener | Device revocation does not invalidate an already-open job stream | Route identity and iterator probe reproduced |
| L-03 | Medium after cancellation during disk publication | A cancelled image request releases its slot while its publication thread runs | Reproduced with a fake provider and real temporary file publication |
| L-04 | Medium with competing mutation threads | Rename/delete bypass the mobile save reservation | Reproduced using isolated competing contexts |
| L-05 | Medium for published APKs larger than 20 MiB | Update download and installation size limits disagree | Source tracing; native installation not exercised |

The four replay probes use synthetic data and temporary files. No real model,
pairing exchange, signing key, APK, vault credential, phone, or public endpoint
was used. The replay script blocks dotenv reads and network activity. Existing
suite results and scope limits are in [VALIDATION.md](VALIDATION.md).

### L-01 — The general stream treats private reply text as public data

**Evidence:** `Virtual Bot/main.py:3517-3532`, `api_events`, permits a missing
token through `_clerk_user_or_none` (`:393-411`). `events.publish_reply` at
`Virtual Bot/events.py:104-133` publishes the answer with the default
`audience=None`. `publish_log` at `:227-233` also publishes untargeted log
content. `sse_stream` at `:280-284` rejects mismatched nonempty audiences but
does not reject untargeted content. Ordinary chat calls `publish_reply` at
`main.py:2380`, and streaming chat uses the same helper at `:2670`.

**Observed:** with Clerk authentication enabled, a synthetic remote request
with no token opened the handler's event stream. It received both a fixture
reply marker and a fixture log marker. This is a handler/iterator reproduction,
not a public-network reachability test. No private owner data was inspected.

**Deployment limit:** the dedicated mobile API hostname excludes `/api/events`
from its allowlist (`mobile_bridge.py:133-142`, `main.py:375-388`). This probe
does not demonstrate leakage through that hostname. The finding concerns the
general dashboard/LAN/reverse-proxy surface when reachable, or another viewer
with access to the same service. The physical screen's need for reply text
does not establish ownership for every anonymous listener.

**How I would fix it:** separate generic device state from owner content.
Publish replies, questions, file paths, and diagnostic text with an explicit
owner. Allow authenticated owner listeners to receive them; provision a local
device credential or a clearly defined direct-loopback device channel for the
physical screen. Keep public emotion/timer/music metadata on a separate declared
contract. Do not use `None` or the empty local-owner ID to represent both
anonymous access and a trusted owner.

For example, make private publication require ownership metadata rather than
inheriting the public default:

```python
def private_event(payload: dict, owner_id: str) -> dict:
    return {"scope": "owner", "owner_id": owner_id, "payload": payload}
```

This is a contract sketch, not a replacement bus implementation. The subscriber
must authenticate an owner and enforce the scope before enqueueing, which also
addresses the earlier capacity finding. Log endpoints and buffered diagnostics
need the same ownership decision.

**Acceptance check:** an anonymous listener receives permitted device state
but no chat text or logs. Alice, Bob, and the provisioned screen receive only
their permitted content. Test ordinary replies, streaming replies, and logs;
the mobile API hostname's existing route restrictions must remain intact.

### L-02 — Revocation checks stop at the stream handshake

**Evidence:** `mobile_api.router.identity` at `Virtual Bot/mobile_api.py:521-523`
authenticates the device. `/messages/{job_id}/events` at `:611-647` then closes
over the resolved user ID and repeatedly reads events without revalidating the
credential. `MobileStore.revoke` at `mobile_store.py:170-175` updates the device
row; future authentication fails, but the live iterator does not consult it.

**Observed:** a deterministic credential inserted only into a temporary fixture
database opened the route after its real identity check. Revocation caused a
fresh identity check to return HTTP 401. The existing iterator still delivered
a newly recorded marker after revocation. The test uses the actual route
identity/handler and store, not an external HTTP connection.

**Impact/limit:** a lost device already subscribed to a running job can keep
receiving later content until the listener closes or the job reaches terminal
state. The probe does not establish that revoked devices can make new requests.
Whether revocation cancels previously accepted work is a separate policy;
closing its listener need not stop an owner-authorized background job.

**How I would fix it:** retain authenticated device identity separately from
owner identity and bind streams to a credential generation/expiry. Close
matching listeners on revocation, and recheck before delivering another batch
as a fallback for missed invalidation notifications. Do not echo token material
into events or logs. An event already in transit cannot be recalled; document
that small boundary rather than promising retroactive secrecy.

**Acceptance check:** revoke during a running stream, record later content,
and verify the revoked listener gets no later batch. A second owner device may
continue listening, and the original device's next request remains 401.

### L-03 — Cancelling the publisher await does not stop publication

**Evidence:** `image_generation.generate` at
`Virtual Bot/image_generation.py:282` awaits `asyncio.to_thread(_publish, ...)`.
Its `finally` at `:290-291` resets `_busy` regardless of whether that worker
thread has completed. `_publish` at `:222-241` creates and replaces the upload
file synchronously. Cancelling the await does not terminate that thread.

**Observed:** a fake Codex session supplied synthetic output. The real temporary
publication was held behind a thread barrier, then the request was cancelled.
`_busy` became false while the publisher remained blocked. A second generation
entered publication before the first was released. After release, both files
were published, despite the first caller being cancelled.

**Limit:** provider work had already completed in this fixture. This does not
prove duplicate paid generation or an escaped Codex process. It demonstrates
untracked disk publication and premature release of the operation's slot.

**How I would fix it:** create and supervise a publication task, and shield its
initial await so request cancellation cannot cancel that task. On cancellation,
await its settlement under shielding before releasing the slot, then apply an
explicit result policy: retain a recoverable cancelled receipt or safely remove
an output that has not been referenced anywhere. Do not delete outputs merely
because a client disconnected if another record already references them.

```python
import asyncio

async def settle_publication(publication: asyncio.Task):
    while not publication.done():
        try:
            await asyncio.shield(publication)
        except asyncio.CancelledError:
            continue
    return publication.result()
```

This helper only illustrates settlement under repeated cancellation. The caller
must remember cancellation and re-raise it after applying the result policy,
and needs a bounded failure strategy for a stuck disk operation. A settlement
timeout cannot stop the thread: keep its ownership/slot supervised until the
worker actually finishes.

**Acceptance check:** cancel after provider completion but before file replace.
The slot is not released while publication remains untracked; repeated cancels
settle once and do not leave an orphan temporary/final file without a receipt.

### L-04 — Save reservations do not cover rename and delete

**Evidence:** `workspace.write_file` at `Virtual Bot/workspace.py:238-254`
uses `workspace_write_guard.writing(path)`. Rename at `:370-380` and deletion
through `_trash_move` at `:265-293` do not. Mobile compare-and-save reserves the
path across its check/write operation (`mobile_content.py:145-152`).

**Observed:** with a mobile reservation held, a competing thread's ordinary
rewrite failed with `workspace_write_busy`, while renaming that same reserved
file succeeded. A separate reservation on the moved path still permitted its
deletion. The competing context deliberately did not inherit the holder's
ContextVar exemption.

**Impact/limit:** the reservation is incomplete as a filesystem-mutation
boundary. Under thread-level mutation, a rename/delete can invalidate an
in-progress save or allow the original path to be recreated later. No complete
production HTTP data-loss race was reproduced. In a single event loop, the
currently synchronous callback portions may not yield enough for every race;
the function-level omission and thread behavior are the demonstrated facts.
External processes and multiple workers also need a separate coordination policy.

**How I would fix it:** apply shared mutation reservations to source and target
paths, including delete-to-trash. Acquire multiple path locks in a stable order
to avoid deadlock. Directory moves/removals must coordinate with descendant
writes, not just the directory's exact path. Keep path resolution and the actual
mutation inside the selected reservation strategy.

**Acceptance check:** reserve a file, then attempt write, rename, deletion, and
parent-directory removal from another context. Each operation waits or reports
the agreed busy result. After release, each valid operation succeeds without
recreating deleted content or losing the user's latest revision.

### L-05 — The updater advertises a larger package than Android accepts

**Evidence:** `Virtual Bot/mobile_api.py:559-571` permits a configured APK up
to 100 MiB. `BotApi.downloadUpdate` at
`mobile-app/shared/src/commonMain/kotlin/me/waveio/claudebot/data/BotApi.kt:225-234`
downloads up to that same size. `AndroidBridge.installPackage` at
`mobile-app/androidApp/src/main/kotlin/me/waveio/claudebot/platform/AndroidBridge.kt:465-467`
rejects anything above 20 MiB. Its shared-file preparation also checks
`NativeFileTransfers.valid`/`prepareShare` at
`mobile-app/androidApp/src/main/kotlin/me/waveio/claudebot/platform/NativeFileTransfers.kt:35-40`,
using `PickedFileLimits.MAX_BYTES`, defined as 20 MiB at
`mobile-app/shared/src/commonMain/kotlin/me/waveio/claudebot/platform/PlatformBridge.kt:8-10`.

**Inference:** a correctly signed, digest-verified package larger than 20 MiB and
no larger than 100 MiB can download successfully and still fail before installer
handoff. This is a source contract mismatch. No actual APK size, installation,
signature, or
installed-device failure was examined in this pass; the current release may
fit the lower limit.

**How I would fix it:** define one update-specific limit shared by metadata,
download, and installer preparation, rather than reusing the attachment/share
limit. Include artifact byte size and revision in update metadata so the client
can reject an unsupported package before downloading. For larger updates, stream
to a private temporary file with an incremental digest, then hand that verified
file to the installer instead of carrying several large ByteArray copies.
Preserve the existing required digest and platform signing checks.

**Acceptance check:** test boundary sizes with synthetic data and a fake native
handoff. Supported sizes reach verification and preparation; unsupported sizes
fail early with a specific localized size error. No test needs a signing key or
real package installation.

## Additions I would build next

1. **A device privacy control.** Show exactly which paired devices may display
   chat text, notifications, or diagnostics. Revocation closes their listeners;
   display sharing is an explicit choice instead of an anonymous event default.
2. **Generated-media receipts.** Keep a small owner-scoped receipt connecting
   request, provider completion, publication, cancellation, and final attachment.
   A client can recover a finished image after disconnecting without regenerating
   it, and cleanup can identify genuinely unreferenced files.
3. **Conflict recovery with choices.** The phone already retains a file draft
   when remote content changes. Add a baseline/local/remote comparison with
   Copy draft, Save as new file, or deliberate merge. Repeated Retry alone cannot
   resolve a persistent content conflict; preserve the user's work throughout.
4. **A release manifest with compatibility checks.** Publish package size,
   digest, immutable artifact revision, minimum client contract, and provenance
   together. Report unsupported sizes/contracts before download and keep the
   installed and offered versions distinguishable until installation completes.
5. **An audit-status index.** Link the daily reports, findings, fix commits, and
   regression evidence in one small Markdown index. Mark fixed, open, policy
   decision, or superseded explicitly. This would make these recurring audits
   actionable without treating each report as a fresh backlog.

## Suggested order

Close the content-scope and revocation gaps first. Then make publication
cancellation accountable and complete the mutation reservation contract.
Align updater sizes before publishing a package beyond the native limit.

These proposals reuse the current FastAPI, asyncio, SQLite, OpenClaw/Codex
adapter, and Kotlin client. Existing suites pass, but do not settle all of the
failure boundaries above. External deployment, native UI, paid providers,
physical devices, and iOS remain outside this evidence.
