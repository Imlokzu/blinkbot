# Repository audit — 2026-10-04

My main idea is to make background work understandable: what is waiting, what
is actually running, what will happen after a reconnect, and which work survives
deleting a conversation. The new mobile queue already has useful foundations,
including durable IDs, replay, explicit pause, and atomic claims. The gaps below
concern its failure and retention behavior.

This is a new folder beside the October 2 and October 3 agent reports.
The starting source revision was `702de21`; unrelated staged and unstaged work
was present. Product files were read, not changed. Proposed fixes are examples
and acceptance criteria, not installed features.

## What changed since the previous audit

- The October 3 file-editor draft issue has a committed follow-up and regression
  coverage, described in `reports/night-agent-files-editor-2026-10-03.md`.
  It is not repeated as a new finding here.
- The dashboard publisher now checks source revisions and preserves old chunks
  before switching the entry. The previous build-publication idea has received
  implementation work; this report does not reopen the old direct-build issue.
- The mobile app and host now implement pairing, queued/scheduled requests,
  replayable events, edit/regenerate forks, and revision-aware file saving.
  Steer and remote push remain explicitly unsupported, and iOS runtime checks
  remain outside this audit.
- Earlier security and persistence findings remain in their original reports.
  This pass is a bounded review of newer queue, storage, and event behavior.

## Ranked findings

| ID | Priority | Finding | Confidence |
| --- | --- | --- | --- |
| M-01 | High after a storage failure | One claim error kills the mobile scheduler; Start cannot revive it | Reproduced with an injected storage exception |
| M-02 | Medium; deletion-policy gap | Scheduled work survives deleting its conversation | Reproduced through the real local routes |
| M-03 | Medium under database contention | SQLite waits run on the async event loop | Reproduced with a temporary database lock |
| M-04 | Medium as usage grows | Completed jobs and intermediate reply snapshots have no retention boundary | Reproduced storage/list behavior; growth impact inferred |
| M-05 | Medium under event bursts | Audience filtering happens after queue capacity has been consumed | Reproduced with isolated subscribers |

All probes use synthetic data, temporary databases, and no provider work.
The deletion probe establishes claimability after deletion; it does not invoke
a real scheduled agent turn. See [VALIDATION.md](VALIDATION.md) and the included
[probes.py](probes.py) for commands and the evidence limits.

### M-01 — A failed claim permanently stops queue scanning

**Evidence:** `Virtual Bot/mobile_api.py:379-394`, `MobileRuntime._scan`,
calls `store.claim_next()` outside an exception handler. Its task is created at
`:352-359`; `start()` returns whenever `self.scheduler` is non-null, even if
that task has already failed. `notify()` only sets an event at `:337-339`.

**Observed:** injecting one `sqlite3.OperationalError` into the claim operation
made the scheduler task finish with that exception. Removing the injection,
calling `notify()`, and calling `start()` still left the same dead task in place.
The accepted fixture job remained `queued`; no provider ran.

**Impact:** a busy/locked database, I/O failure, or another uncaught claim error
can leave accepted work waiting indefinitely in an otherwise responsive host.
This is a failure-path finding, not a claim that ordinary jobs currently stall.

**How I would fix it:** supervise the scan task and expose runner readiness,
last successful scan, and a stable failure code. Retry only known transient
storage failures with a bounded backoff. A fatal storage failure should make
new submissions report unavailable rather than silently extending a dead queue.
Before restarting a failed scanner, retain ownership of existing worker tasks
and the runner lock; do not recover or rerun jobs that may have side effects.

An existing-task check should at least distinguish live from failed:

```python
def scheduler_is_live(runtime) -> bool:
    task = runtime.scheduler
    return task is not None and not task.done()
```

That check is only a diagnostic building block. Replacing the task safely also
requires handling the retained lock and any still-running jobs.

**Acceptance check:** inject one transient claim failure and later allow the
claim to succeed. The queued request executes once, existing active work is
never replayed, and unrecoverable failures appear in host/mobile status.

### M-02 — Deletion removes history while future work stays runnable

**Evidence:** `Virtual Bot/main.py:3121-3127`, `api_session_delete`, removes
the JSON history through `chat_store.delete`. It does not update the mobile
queue. `MobileStore.claim_next` at `Virtual Bot/mobile_store.py:269-284`
uses job state, schedule time, and conversation pause state; it does not check
whether the shared history file still exists.

**Observed:** a real TestClient POST accepted a scheduled fixture message for
an existing conversation. DELETE returned HTTP 200 with `ok: true` and removed
that history. The job remained `scheduled`. Advancing only the fixture clock
made it claimable for the deleted conversation.

**Impact/policy limit:** this proves that deleting history does not cancel
future work. Whether deletion should cancel schedules is a product decision.
If the UI means “delete this conversation and its work,” the current behavior
can recreate the conversation and execute actions later. If it means “delete
history only,” the retained schedule needs to be disclosed explicitly.
This is separate from October 3's in-memory-history deletion finding.

**How I would fix it:** define one deletion policy shared by desktop and phone.
For a complete conversation deletion, first commit a tombstone and stop pending
jobs in SQLite, keyed by owner, conversation kind, and session. Fence new claims,
submissions, and late writes from desktop and messenger paths as well as mobile.
Request cancellation of active work and wait for cleanup, then remove the shared
history through retryable filesystem cleanup. SQLite and JSON files do not share
one atomic transaction. Decide whether job payloads, replay events, and frozen
fork prefixes remain retained; deleting the JSON file alone does not remove them.
Treat Gateway transcript removal as a separate supported operation; the probe
does not establish its behavior.

**Acceptance check:** deletion with one scheduled, one queued, and one active
request leaves no future runnable work under the chosen policy. Test a late
completion and a retry using the original client ID. A history-only action
must retain schedules visibly and must have a distinct label.

### M-03 — A database lock can stall unrelated async work

**Evidence:** `Virtual Bot/mobile_store.py:92-110` opens synchronous SQLite
connections with a ten-second timeout. `_scan`, async submission/listing routes,
and replay handlers call store methods directly on the event-loop thread
(`mobile_api.py:383`, `:538-562`, `:578-584`). Transactions are short normally,
but their lock wait is still synchronous.

**Observed:** a second temporary SQLite connection held a write lock for a
quarter second. A direct claim took about 0.27 seconds; an already scheduled
20ms asyncio timer was still unfinished when the call returned. The exact
duration depends on scheduling and is not a production performance benchmark.

**Impact:** under contention or slow storage, the loop cannot serve other
streams or run cancellation/timer callbacks during that wait. The ten-second
timeout is an upper lock-wait setting, not a measured ten-second pause here.
This latency issue is distinct from M-01's dead scheduler after an exception.

**How I would fix it:** move database calls off the loop while preserving
transaction and claim ordering. The existing store opens/closes a connection
inside each method, so a worker-thread boundary can own that whole operation:

```python
import asyncio

async def claim_without_blocking(store):
    return await asyncio.to_thread(store.claim_next)
```

Apply the same discipline to recording, finishing, listing, and replay reads.
Account for cancellation: cancelling an await does not stop a worker-thread
transaction. Keep a committed claim tracked until its outcome is reconciled;
otherwise a thread migration could introduce duplicate or stranded work.
Drain and reconcile in-flight database calls before releasing the runner lock.
Restart a scanner without calling `recover()` against its still-live workers:
that method marks all running/stopping jobs interrupted. The snippet only
illustrates the thread boundary; it is not a complete cancellation-safe claim.

**Acceptance check:** hold a database lock while a replay listener and short
timer run. The timer remains responsive, claims remain unique, and cancellation
before/during/after a committed claim leaves a recoverable durable state.
Shutdown retains the runner lock until pending database calls are reconciled.

### M-04 — Replay storage grows with every intermediate answer

**Evidence:** `MobileStore._event` and `record` at
`Virtual Bot/mobile_store.py:183-187` and `:286-296` insert every event.
There is no purge/compaction method in the store. `messages` at `:245-251`
loads all matching jobs, including terminal jobs, and `/api/mobile/messages`
at `mobile_api.py:554-562` returns them without pagination. The phone polls
job lists in `mobile-app/shared/src/commonMain/kotlin/me/waveio/claudebot/state/AppController.kt:77-85`.

**Observed:** 64 progressively longer synthetic `reply_snapshot` events
retained 266,944 JSON characters for an 8,192-character final answer: about
32.6 times the final text, before other events. After adding forty completed
fixture jobs, the default list returned all 41 terminal jobs. This demonstrates
the storage/list policy, not an observed real-phone memory failure.

**Impact:** replay can eventually hold another large copy of message content,
and ordinary queue polling grows with the entire account history. The
long-lived host and Raspberry Pi deployment need a declared retention budget.

**How I would fix it:** paginate summaries and separate active queue inventory
from historical receipts. After a declared replay window, compact replacement
snapshots into a checkpoint while retaining required narration/tool outcomes
and the final result. Give reconnecting clients a defined checkpoint/reset
protocol and preserve a monotonic sequence watermark. Add or negotiate client
support before compaction: the current phone advances cursors before discarding
unknown event types, and HTTP 410 enters its generic reconnect loop
(`AppController.kt:439-440`, `:468-476`). Silently deleting sequence numbers
would break old clients. Retain idempotency fingerprints and terminal receipts
so cleanup cannot make an old client retry run again.

**Acceptance check:** replay before and after checkpointing yields the same
final answer and relevant activity; old cursors receive explicit recovery
instructions. Terminal storage/list size is bounded, active jobs are never
purged, and a retried old client ID cannot trigger another model call. Existing
clients retain compatible replay until they support checkpoint recovery.

### M-05 — Another user's events can consume a subscriber's capacity

**Evidence:** `Virtual Bot/events.py:76-85`, `publish`, enqueues each event
for every subscriber. The audience check happens later, when the subscriber
reads, at `:280-283`. The bounded queue has 200 slots (`:33`). Scoped question,
choice, and todo tools use this bus from `Virtual Bot/tools/ui_tools.py`.

**Observed:** Bob's isolated stream received 200 queued Alice-only fixture
events. Bob's next relevant event was dropped because the queue was full.
Reading the stream discarded the Alice events and returned a keepalive;
the Bob event never arrived. This is a capacity/isolation bug, not disclosure
of another user's content: the existing read-time filter did reject it.

**How I would fix it:** store audience metadata with each subscription and
filter before enqueueing. Preserve the existing public-event behavior. A
predicate matching the current truthiness-based semantics is:

```python
def audience_matches(target: str | None, subscriber: str | None) -> bool:
    return not target or target == subscriber
```

Use that predicate before consuming a queue slot. For overflow of genuinely
relevant events, expose a resync indication or keep a current-state snapshot
instead of silently losing user actions.

**Acceptance check:** fill Alice's own queue and still deliver Bob's next
private event. Public music/face events reach their intended streams, invalid
tokens remain rejected, and closing subscribers removes their metadata.

## Additions I would build next

1. **A background-work page with honest health.** Show queued, scheduled,
   paused, active, and interrupted work with last host contact and runner
   readiness. “Connected” should not imply “the scheduler is running.” Start
   with the existing durable job records and operator-safe diagnostics.
2. **Visible missed-schedule behavior.** The host already runs overdue requests
   at the first available scan (`Virtual Bot/docs/MOBILE-API.md:188-191`). Show
   the original requested time and the catch-up outcome; consider configurable
   expiry or confirmation when sending late would surprise the user. Test the
   existing catch-up policy with a fake clock and an offline outbox before
   adding more calendar UI.
3. **A recovery view for interrupted work.** Show which tools completed before
   a failure and distinguish resuming a queue from re-running a task. This lets
   the user decide how to handle partial work without hiding possible duplicate
   side effects behind a generic Retry button.
4. **Recovery that preserves the operation.** Separate delivery retry using the
   same client ID from a deliberate new-ID rerun: an old ID returns its existing
   terminal receipt (`mobile_store.py:219-222`), not another execution. Retain
   delivery mode, schedule, model/effort, attachments, and fork intent. The
   source at `AppController.kt:568-575` reconstructs a failed server job as a
   new OutboxItem whose delivery defaults to queue. A fork rerun should preserve
   its captured prefix or disclose rebuilding from a changed/deleted source.
   Keep operation-changing recovery explicit; unsupported Steer is not silently
   equivalent to submitting another ordinary turn. This is a source-based
   design concern, not a native-runtime reproduction in this pass; Steer is
   currently disabled.
5. **Storage controls with a replay guarantee.** Show owner-scoped queue/event
   storage usage, retention age, and export/cleanup options. Tie cleanup to
   checkpoint and idempotency guarantees from M-04, so smaller storage does
   not compromise delivery correctness.

## Suggested order and limits

Address scheduler supervision and database-loop blocking first, then define
deletion/schedule behavior. Move audience filtering ahead of enqueueing and
add the replay/list retention contract before long-term usage grows.

Fix examples reuse the current FastAPI, SQLite, OpenClaw, and Kotlin client;
no replacement platform is proposed. Existing suites pass, but these probes
exercise omitted failure cases. No real model run, paid image request, public
site change, phone pairing, emulator session, or iOS validation was performed.
The report is additional evidence, not an exhaustive certification.
