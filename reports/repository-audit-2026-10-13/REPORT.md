# Repository audit — 2026-10-13

Four fresh memory-persistence findings, with concrete solution examples,
acceptance checks, and three additions I would build. This folder follows the
existing dated agent reports in `reports/`. The repairs are proposals; this
audit adds reports and isolated observations, not product changes.

Starting revision: `01bce45c`. The latest [wake-intent repair](../night-agent-wake-intent-2026-10-12.md)
addresses October 12 V-01 and is not counted again. The other voice, publication,
project, ownership and retention follow-ups remain separate. I compared the
October 1–12 audits, read the current project/spec/API context, then focused on
memory error handling, mutation locking and recovery visibility. This is a
bounded review, not a whole-repository or deployment certification.

Other agents' unfinished edits were present. No owner profile, real note/chat,
credential, vault, provider or device was used. All notes and paths below are
synthetic. See [validation](VALIDATION.md) and the [reproducible probe](probes.py).

## Ranked findings

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| B-01 | High if an existing profile read fails transiently | Appending one fact can overwrite all previous profile facts | One injected text-read failure; actual append/write functions |
| B-02 | Medium during brain-lock contention | Async memory writes poll a synchronous lock on the event loop | Actual endpoint body and flock polling with a shortened wait budget |
| B-03 | Medium while recovery artifacts exist | Catalog includes backup/staged notes that the reader rejects | Actual list/read over synthetic recovery folders |
| B-04 | Low; repeated failures can exhaust resources | Directory fsync failure leaks its open file descriptor | Actual atomic writer with only directory fsync failing |

Confidence is high in the reproduced behaviors. I/O failures and lock contention
are synthetic preconditions, not observed failures of this Mac's real memory.
The recovery catalog probe models the directories used by consolidation; it
does not crash or run a live dream cycle. Deployed frequency and process-wide
resource exhaustion were not measured.

## B-01 — An unreadable existing profile is treated as an empty profile

**Source:** [`memory.py`](../../Virtual%20Bot/memory.py), `459–505`;
[`main.py`](../../Virtual%20Bot/main.py), `2066–2078`.

`append_user_profile()` initializes `current` to an empty string and ignores
`BrainPathError`, `FileNotFoundError` **and** `OSError` while reading the profile.
It constructs a new profile from that empty value. Later, it successfully reads
the old bytes into `previous` for rollback, but normal successful writes do not
use those bytes to rebuild the new content. Only navigation failure triggers
rollback.

The fixture starts with two existing facts in `people/user.md`. One text read
raises `OSError`; the later byte read and writes succeed. Appending a new fact
returns normally, but both existing facts are absent afterwards. The mutation
lock prevents competing cooperative writers; it does not make a failed read
equivalent to an empty file.

**Impact:** a temporary read problem can erase durable facts during a successful
automatic append. This differs from earlier hidden chat-save failures: the new
memory write itself succeeds with a destructive payload. No actual owner fact
was read or deleted, and no filesystem outage was induced.

**How I would fix it:** only a genuinely absent file should initialize a new
profile. Fail closed on an unreadable/invalid existing profile, preserving its
bytes and returning a clear persistence error. For example:

```python
try:
    current = read_note(_USER_PROFILE_PATH)
except FileNotFoundError:
    current = ""
```

Do not catch general `OSError` or a path-validation failure as empty content.
Prefer one safely opened, bounded snapshot used both for append construction
and rollback. Decide the encoding-corruption contract explicitly; replacement
decoding is not necessarily proof that the old profile is usable. The caller
can report a localized memory-save failure without retrying a destructive
empty-profile fallback. Keep deduplication and the mutation reservation.

**Acceptance:** missing profile creation works; transient read, permission,
path-validation and malformed-source failures preserve existing content and
do not report a successful append. Navigation rollback still restores exactly
the previous snapshot. A later successful retry adds the fact once without
losing older facts.

## B-02 — Memory mutation waits block the server event loop

**Source:** `memory.py:65–117`, `199–214`;
`main.py:3707–3719`, `3723–3761`;
[`tools/registry.py`](../../Virtual%20Bot/tools/registry.py), `203–212`.

The async memory-save route calls synchronous `memory.save_note()` directly.
Its lock acquisition repeatedly opens/flocks and calls `time.sleep()` while
contended, for up to ten seconds by default. Brain directory/file/navigation
routes and their async tool wrappers use the same synchronous mutation path.
A lock holder in another thread/process can therefore stall unrelated tasks
on that server event loop, even though the lock wait has a bounded timeout.

The probe compiles the actual `api_memory_save` body without importing the
application. A synthetic owner/context adapter is immediate. It holds a real
brain lock, shortens only acquisition's budget to 120ms, and starts a 10ms
async timer. After the handler returns an HTTP-500-equivalent exception around
122ms later, the async timer is still unfinished. The real polling/flock code
ran; no ten-second service stall was forced. The ten-second default is a source
fact, not a measured outage duration.

This is distinct from October 4's mobile SQLite lock wait: the memory reservation
is a separate file-lock implementation and separate async API/tool consumers.

**How I would fix it:** run the entire synchronous mutation, including lock,
read, write, navigation and rollback, in a bounded worker, or use cooperative
async acquisition followed by controlled disk work. This illustrates only the
thread/context transfer, not a complete cancellation-safe route:

```python
with _brain_context(req.session_id, clerk_uid):
    await asyncio.to_thread(memory.save_note, req.path, req.content)
```

`asyncio.to_thread` propagates the active context, but its cancellation does not
stop the worker. A concrete proposed lifecycle is:

1. A supervisor accepts operations through a finite queue with immediate busy
   rejection, for example two active workers and sixteen queued writes. A fixed
   worker pool owns those operations; do not create one waiting task per request.
   These example limits need measurement before choosing production values.
2. While the authenticated brain context is active, capture its context and
   validated owner/root plus an operation ID. The queued worker runs the entire
   mutation in that captured context, rather than consulting a later request's
   current brain. The supervisor retains the task and its completion record.
3. The request awaits a shielded completion. Disconnect/cancellation ends that
   waiter, not the accepted operation. A deadline returns pending/unknown with
   the operation ID; it must not report rollback, replay the write, cancel the
   worker, or release its admission slot. Reconcile the eventual success/error
   in the supervisor even when no request remains to await it.
4. The synchronous worker alone acquires and releases the file reservation,
   covering snapshot, write, navigation and any rollback. Only actual worker
   completion frees the active slot. Keep the existing finite acquisition
   deadline; stalled disk work remains counted and visible instead of spawning
   replacement workers. A lock timeout maps to a retryable busy result.
5. Shutdown stops admission and drains accepted operations before retiring their
   executor and ownership records. Reconcile completed results before releasing
   reservations; process interruption uses the separate recovery contract.

Apply the same policy to directory/file/navigation tools; changing only one
route leaves other blocking consumers. This supervisor is a proposed repair,
not an existing helper or a behavior tested by the defect probe.
An async lock adapter must release correctly if cancelled after acquisition.
The existing dream-cycle adapter is a useful starting point, but do not acquire
that lock and then call a helper that acquires the same non-reentrant flock
again. Either let the worker own the full lock/mutation or provide an explicit
already-reserved internal mutation entry point with one release owner.

**Acceptance:** a held brain reservation does not delay unrelated health/timer/
stream work. Contended requests return a defined busy response or wait within
a cooperative deadline. Cancelled workers cannot mutate a different brain,
release another writer's reservation, or be falsely reported rolled back.
Verify successful commit and rollback behavior still serialize across processes.

## B-03 — Recovery files appear as ordinary editable notes

**Source:** `memory.py:178–196`, `132–153`;
[`dream_cycle.py`](../../Virtual%20Bot/dream_cycle.py), `444–455`, `483–501`,
`508–550`; `main.py:3682–3705`.

`list_notes()` recursively lists every `.md` file below the brain root after
checking resolved containment. It does not exclude hidden/service/recovery
components. Dream consolidation stages writes and backups below
`.dream-recovery-…/writes/…` and `backups/…`; failed recovery can retain them.
The read validator, in contrast, rejects hidden components.

The fixture places one live note and two inert recovery notes below such a
folder. The real catalog returns all three, including titles from a previous
backup and an uncommitted staged write. Calling the real note reader with either
returned recovery path raises `BrainPathError`. Thus the catalog advertises
rows that are not ordinary readable/editable notes.

**Impact:** temporary or retained recovery artifacts pollute the memory view
with duplicate/stale/uncommitted content and controls that reject the selected
row. All content remains inside the synthetic owner's root; this is not shown
as cross-account disclosure. The probe does not establish how often a real
consolidation overlaps catalog loading.

**How I would fix it:** prune hidden/recovery/service directories during walking
and use the same public-note path policy for listing and reading. A minimal
filter before title extraction is:

```python
relative = path.relative_to(root)
if any(part.startswith(".") for part in relative.parts):
    continue
```

This fixes the demonstrated hidden recovery rows, but full catalog policy must
also distinguish generated indexes, logs, reserved service paths, symlinks and
normal user notes. Define the intentionally visible subsets explicitly rather
than requiring every list row to be writable. If an index/log is visible but
read-only, return that capability. Expose recovery artifacts through a separate
owner/operator-authorized recovery view with transaction status, not the ordinary
note editor. Never delete recovery bytes merely to clean the catalog.

**Acceptance:** prepared, failed-rollback and committed-but-not-cleaned recovery
folders never appear as ordinary notes. Catalog rows satisfy their declared
read/write capability. Normal notes and intended logs/index views remain
available, symlink boundaries hold, and recovery state can still be inspected
through its explicitly authorized surface.

## B-04 — Failed directory sync leaves its descriptor open

**Source:** `memory.py:266–283`.

The atomic writer opens the parent directory, calls `os.fsync(fd)`, then closes
it. If directory sync raises `OSError`, control jumps to the broad handler
before `os.close(fd)`. The operation suppresses that error and returns with the
directory descriptor still open. The staged file handle itself uses a context
manager and is not the leaked handle.

The fixture allows the ordinary file sync, opens the real temporary directory,
and makes only its directory fsync fail. The write returns normally and
`os.fstat()` confirms one unclosed directory descriptor. The probe closes it
after observing the leak. Repetition could exhaust descriptors on a filesystem
or failure mode where directory sync consistently fails, but no exhaustion or
platform-wide unsupported-sync claim is made.

**How I would fix it:** close the acquired descriptor in `finally`, whether the
sync succeeds or fails:

```python
fd = os.open(path.parent, os.O_RDONLY)
try:
    os.fsync(fd)
finally:
    os.close(fd)
```

Handle the intended sync-error policy outside that lifetime block. If durability
cannot be guaranteed, preserve an explicit diagnostic rather than conflating a
best-effort sync with a durable commit. Directory sync occurs after `os.replace`:
new bytes may already be visible, so an error receipt must distinguish committed
visibility with uncertain durability from a write that never happened. Do not
invite blind replay or promise rollback merely because the directory sync failed.
Do not broaden the swallowed-error path
or change file-data sync behavior as part of this small resource fix.

**Acceptance:** repeated directory-fsync failures leave no descriptor growth;
successful writes still sync and close once. Test directory-open failure,
file-sync failure, replace failure and rollback separately. Document whether a
directory-sync failure returns an error or a degraded durability receipt.

## Additions I would build

1. **A memory persistence receipt.** Show saved, pending, failed and durability
   status for a newly learned fact, linked to its safe source and generation.
   A chat reply should not imply that a fact was remembered merely because text
   generation succeeded. This extends the earlier lifecycle/provenance ideas
   to automatic fact appends; user-facing statuses use locale keys.
2. **A brain recovery panel.** Separate live notes from prepared/committed/failed
   transactions. Show safe generation/status details and offer owner-scoped
   inspect/recover/export actions that preserve originals. Use the existing
   recovery machinery instead of teaching the note editor to write backups.
3. **A reservation health indicator.** Surface busy duration, queued mutation
   count and last completed/failed generation without exposing personal note
   content. Help the operator distinguish consolidation from a stuck write and
   retry safely. Build it on the current file-lock/context architecture.

I would preserve existing profile bytes on failed reads first, make mutation
waits cooperative next, then separate recovery visibility and close sync handles.
These repairs fit the current Python/FastAPI/filesystem stack; no replacement
agent runtime or new database/service is proposed. Each sketch needs its listed
regression checks before it is treated as an implemented fix.
