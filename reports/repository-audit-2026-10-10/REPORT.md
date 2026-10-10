# Repository audit — 2026-10-10

Four new findings around project metadata and retained interaction state, with
concrete repair examples, acceptance criteria, and three additions I would build.
This folder follows the existing dated audit folders in `reports/`. Repairs are
proposals; the audit adds documentation and synthetic probes only.

Starting revision: `eb1436be`. The October 8–9 findings were addressed by later
commits; see [the repair record](../audit-fixes-2026-10-09.md). The release-input
repair is documented [separately](../night-agent-release-inputs-2026-10-09.md).
Those findings are not counted again here. I inspected recent mobile/editor
changes, then narrowed reproduction work to project and interaction boundaries
where deterministic failures were found. This is not an exhaustive repository
review or a claim about deployed exploitability.

Other agents' unfinished changes were present before this pass. Personal
runtime data, actual screen packages, profiles, conversations, vaults and
credentials were not used. See [validation](VALIDATION.md) and [probes](probes.py).

## Ranked findings

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| P-01 | High if untrusted/local project links exist | Project metadata operations follow directory and temporary-file symlinks outside the code root | Synthetic external files read and changed |
| P-02 | Medium | One malformed project metadata record breaks the whole list | Real project module with invalid metadata |
| P-03 | Medium with concurrent module/multi-worker callers | Renames share a temporary pathname and can return another caller's name | Deterministically interleaved real rename calls |
| I-01 | Medium for long-lived conversations | Action quota counts expired calls that can no longer be used | Real SQLite updates with a reduced fixture limit |

Confidence is high in the reproduced module behavior. P-01 requires a symlink
already present in the code tree; this probe does not establish that remote
users can create one or gain OS permissions they did not already have. P-03
requires concurrent callers; the current single-worker async project routes
call the module synchronously, so same-worker HTTP requests do not establish
that interleaving. Multi-worker/direct-thread impact is conditional.

## P-01 — The metadata path does not enforce the code-root boundary

**Source:** [`projects.py`](../../Virtual%20Bot/projects.py), `69–82`, `101–124`,
`129–133`, `151–161`;
[`main.py`](../../Virtual%20Bot/main.py), `4164–4184`;
[`workspace.py`](../../Virtual%20Bot/workspace.py), `119–123`, `265–275`,
`296–302`.

The registry selects folders with `is_dir()`, which follows a symlink. Get and
rename join a validated slug to the code root, but do not resolve and check that
the resulting folder remains inside that root. Rename reads/writes metadata
through the link. The normal code-delete helper does resolve/check containment;
metadata operations do not share that safeguard.

The fixture creates `code/linked-fixture` pointing to a sibling temporary folder.
Listing exposes its synthetic project name. `rename_project()` changes the
sibling's `.project.json`. A second fixture puts a symlink at the fixed
`.project.json.tmp` name inside a normal project. `_write_meta()` follows it
when writing, changes a separate synthetic JSON file, and then promotes the
symlink to the project's metadata pathname.

**Impact:** metadata operations can read/write outside the documented code root
under the server's existing filesystem permissions. This weakens the separation
between coding projects and owner data. The imported-screen sandbox and the
previous unsandboxed coding-agent finding are separate boundaries. No real
private file, credential, script execution, or cross-account access was probed.

**How I would fix it:** use one project-folder resolver for list/get/rename,
refuse symlinked project entries and metadata files, and stage metadata with an
exclusively created unique file. For a static-path containment check:

```python
def checked_project_folder(slug: str) -> Path:
    base = _projects_root().resolve()
    candidate = base / _safe_slug(slug)
    if candidate.is_symlink():
        raise ValueError("invalid_project_path")
    resolved = candidate.resolve()
    if resolved == base or not resolved.is_relative_to(base):
        raise ValueError("invalid_project_path")
    return resolved
```

This example does not close path replacement races by itself. Where concurrent
filesystem writers are possible, keep an opened directory descriptor and use
relative descriptor operations with no-follow/exclusive flags. Reads of
`.project.json` must reject symlinks as well, and writes must never reuse a
predictable temporary filename. Do not rely on authentication to prove that a
path still identifies a permitted project.

**Acceptance:** directory, metadata, and temporary-path symlinks are refused or
skipped; outside fixture bytes remain unchanged. Concurrent replacement of a
project directory cannot redirect writes. Normal metadata updates still work,
and deletion/get/rename/list enforce the same root contract.

## P-02 — Syntactically valid metadata can stop every project listing

**Source:** `projects.py:69–76`, `101–125`; `main.py:3987–3997`, `4164–4169`.

`_read_meta()` accepts any JSON value. `_as_dict()` assumes it is a dictionary,
that `created` can be converted to an integer, and that `name` is displayable
text. The catalog builds all entries in one comprehension without isolating a
bad record.

The fixture keeps a healthy project next to one damaged record:

| Metadata | Result |
| --- | --- |
| `[]` | `AttributeError`; the whole list fails |
| `{"created":"not-a-time","name":"Fixture"}` | `ValueError`; the whole list fails |
| `{"created":1,"name":{"unexpected":"object"}}` | Dictionary is returned as the project name |

The API wrapper handles `ValueError` as HTTP 400 and has no `AttributeError`
handler, so the first case takes the 500 path. This is stored-state corruption
being reported as a failure of a valid list request. Project files can be edited
by local tools; a malformed metadata record should not hide unrelated projects.
No browser crash is claimed for the dictionary-name case.

**How I would fix it:** bound the read, validate the metadata shape and field
types before projection, and provide safe per-project fallback metadata with
an explicit corruption diagnostic. A minimal shape check starts with:

```python
raw = json.loads(bounded_metadata_bytes)
if not isinstance(raw, dict):
    raise ProjectMetadataError("project_metadata_invalid")
name = raw.get("name")
created = raw.get("created")
if name is not None and (not isinstance(name, str) or len(name) > MAX_NAME):
    raise ProjectMetadataError("project_metadata_invalid")
if created is not None and (type(created) is not int or created < 0):
    raise ProjectMetadataError("project_metadata_invalid")
```

At list projection, keep the folder's safe ID/name and available timestamp if
its metadata is corrupt, and expose a stable `metadata_status` code. Preserve
the original file for recovery; do not silently rewrite it during a GET. Genuine
filesystem failures need a separate diagnostic. Add locale keys for any warning
or repair control shown to a user.

**Acceptance:** a bad record never prevents healthy projects from loading.
Wrong types, nonfinite/huge timestamps, oversized files, malformed JSON and
metadata symlinks follow the defined error/fallback contract. Create/rename
continue writing the validated schema, with no automatic loss of damaged bytes.

## P-03 — Concurrent metadata writers share one staging file

**Source:** `projects.py:79–82`, `151–162`.

Every write uses `.project.json.tmp`. Two rename callers can both write that
same path before either promotes it. The controlled thread fixture produces:

1. Caller A writes metadata with name A, then pauses.
2. Caller B overwrites the same temporary file with name B.
3. A promotes that file and returns name B.
4. B's promotion raises `FileNotFoundError` because the file was already moved.

The probe interleaves real writes/replaces using event gates; it does not replace
rename logic or inject a filesystem error. P-01 is a trust-boundary defect;
this one occurs with two ordinary project updates and no symlinks. The current
single-worker project endpoints have no await during these operations; that
configuration does not itself reproduce simultaneous HTTP callers. Multiple
server workers, threads, or local module consumers can share the path.

**How I would fix it:** stage each writer's bytes through an exclusive unique
file, then atomically replace metadata. For example:

```python
with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=folder,
                                 prefix=".project-", suffix=".tmp", delete=False) as staged:
    json.dump(meta, staged, ensure_ascii=False)
    staged.flush()
    os.fsync(staged.fileno())
temporary_path = Path(staged.name)
try:
    os.replace(temporary_path, folder / _META_NAME)
finally:
    temporary_path.unlink(missing_ok=True)
```

Use the safe opened-directory approach from P-01 when the directory can move.
Unique staging prevents collision, but does not provide revision conflict
detection. Define rename semantics separately: atomically compare the expected
revision and publish under coordination shared by every writer, returning a
receipt for that committed generation rather than rereading possibly newer
metadata. A check followed by `os.replace()` is not a compare-and-swap operation.
An in-process lock alone does not cover
multiple server workers. Cleanup must also handle failure during write/fsync,
which is outside the short promotion example.

**Acceptance:** two interleaved renames do not mix staging bytes or fail as a
missing file. Each response identifies its committed generation or returns an
explicit revision conflict. Failed writes preserve prior metadata and remove
only their own staging file. Test the chosen multi-worker coordination contract.

## I-01 — Expired checklist calls permanently consume live capacity

**Source:** [`chat_interactions.py`](../../Virtual%20Bot/chat_interactions.py),
`16`, `53–60`, `166–198`;
[`chat_store.py`](../../Virtual%20Bot/chat_store.py), `68`, `326`.

Interaction rows are keyed by conversation/call ID and limited to 2,000 per
conversation. Cleanup deletes rows only when their **conversation file** is
gone. Transcript history is bounded to 500 messages; old calls can disappear
while the same conversation remains active. `_source()` already refuses those
expired calls, but their SQLite rows still count against new actions.

The fixture lowers the limit to two, records an action for each of two calls,
then replaces the transcript with a third current call. Neither old call remains
in the transcript. Updating the current checklist fails with `interaction_limit`,
while SQLite still contains both expired call rows. The production threshold
was not reached by running thousands of real conversations; the reduced-limit
fixture demonstrates the same count/cleanup branch.

**Impact:** a long-lived conversation eventually loses the ability to persist
new checklist actions. This is not unbounded mobile job storage from the
October 4 audit: the action store is bounded, but obsolete rows block useful
work and have no automatic reclamation at call level.

**How I would fix it:** reconcile rows against live eligible call IDs from the
same bounded transcript before charging new actions to its quota. Keep receipt
retention and client invalidation semantics explicit. A call-level SQL boundary
for a nonempty allowlist is:

```sql
DELETE FROM actions
WHERE session_id = ? AND call_id NOT IN (<bound placeholders for live calls>);
```

Handle the empty allowlist separately. Reclamation requires a successful,
validated transcript read: `chat_store.load():161–187` currently returns an
empty message skeleton for missing files and for I/O/JSON failures. That fallback
is not proof that no calls remain. On unreadable/corrupt source, skip reclamation
or fail with an explicit error and preserve actions. Derive the live set from an
authoritative snapshot and coordinate transcript pruning with action updates;
the actions database's `BEGIN IMMEDIATE` does not lock the JSON transcript.
Deleting based on a stale snapshot could discard a concurrent valid action.
Chunk allowlists
or use a temporary relation rather than assuming unlimited SQL placeholders.
Only reclaim rows that `_source()` can no longer authorize, and keep responses
to stale cards explicit. Deleting/pruning whole conversations should also clean
their state without waiting for an unrelated future toggle.

**Acceptance:** after history trimming, expired rows free space and a new
eligible call can persist. Live checklist revisions/ticks remain intact. Owner
and chat/code namespaces stay separate. Concurrent trimming/toggling cannot
delete a newly accepted state, transient transcript read errors never erase live
progress, and stale controls cannot resurrect discarded rows.

## Additions I would build

1. **A project health view.** Show metadata status, safe root, last successful
   update, and a repair/export action for damaged records. Keep raw paths and
   diagnostics operator-scoped where needed; use localized status codes. A
   project should stay visible when its presentation metadata needs repair.
2. **A revision history for project identity.** Record renames with generation,
   previous/new display name, actor and receipt. Offer undo under the same
   ownership/revision policy. Stable project slugs already exist; this adds
   explainable, conflict-aware metadata changes without moving project files.
3. **A compact interaction-state inspector.** Show current/expired action counts
   and the reason a control is unavailable; offer safe export and cleanup of
   expired state. This specializes earlier retention/receipt ideas for the new
   action store. It should never erase live progress as a workaround for I-01.

I would close the project path boundary first, isolate malformed records next,
then repair action reclamation and concurrent metadata staging. These fit the
existing filesystem, FastAPI and SQLite stack; no replacement agent runtime or
new service is proposed. The example snippets are design sketches, not complete
integrated fixes, and each needs its listed regression checks before shipping.
