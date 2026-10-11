# Repository audit — 2026-10-11

Four new findings in timer activation, reminder scheduling, and site publication,
with repair examples, acceptance checks, and three additions I would build.
This folder sits beside the existing dated agent audits. These are proposals;
the audit changes reports and synthetic probes, not product behavior.

Starting revision: `6a3078c5`. The latest [project-path repair](../night-agent-project-paths-2026-10-10.md)
addresses October 10 P-01 and ordinary shared-temporary-file collisions. Its
documented metadata, revision/conflict, and interaction-retention follow-ups are
not counted as new issues here. Earlier October 1–10 audits were compared.

I inspected the recent handoff, display/API contracts and timer/publication
paths, then reproduced four bounded cases. This is not an exhaustive repository
or deployment audit. Other agents' unfinished edits were present. Real profiles,
conversations, runtime packages, vaults, credentials and provider configuration
were left untouched. See [validation](VALIDATION.md), [Python probes](probes.py)
and the [screen-function probe](probes.mjs).

## Ranked findings

| ID | Priority | Finding | Evidence |
| --- | --- | --- | --- |
| T-01 | Medium | Extending a completed timer restarts its countdown but suppresses its next ring | Real backend transitions and screen functions with inert audio adapter |
| T-02 | Medium where local clocks change | A clock-time reminder crossing DST is an hour early/late | Real scheduling helper with Berlin transition fixtures |
| S-01 | High for concurrent publication | Two successful publication calls overwrite each other's registry/configuration | Actual `share()` calls with awaited provider fixtures |
| S-02 | Medium for publication consistency | Failure after remote configuration changes leaves local state behind | Actual `share()` with synthetic DNS failure |

Confidence is high in the observed source behavior. Screen execution uses Node
VM functions with DOM/audio adapters, not a rendered physical display. Publication
uses fake remote operations, not real Cloudflare changes. Timing, outage frequency,
provider consistency and deployed exposure have not been measured.

## T-01 — A timer ID is used as a permanent “already rang” marker

**Source:** [`screen_widgets.py`](../../Virtual%20Bot/screen_widgets.py), `187–221`;
[`screen.js`](../../Virtual%20Bot/static/screen/screen.js), `5486–5488`, `5515–5523`,
`5581–5609`, `5614–5619`.

Backend `add` explicitly restarts an expired timer by moving `ends_at` into the
future. It keeps the timer's ID. The screen instead tracks a permanent
`timerRung` set keyed only by that ID. Receiving a done timer or ringing it once
adds its ID; receiving the new running deadline does not remove that marker.

The fixture starts a one-second timer at synthetic time 1000. The real screen
functions invoke the alarm adapter once at 1002, then the real `stopRing()`
clears the active alarm. Backend `add` restarts the same ID for another sixty
seconds. At time 1062 the original thirty-second ring window
has expired and the restarted timer is due, but the same functions make zero
additional alarm calls. No sound is produced: the adapter counts calls instead
of creating audio. Restarting a done timer received from history can
hit the same permanent marker even if this screen never rang it initially.

**How I would fix it:** give each running activation a persisted generation and
mark delivery by `(timer_id, activation)`, rather than by timer ID alone. Advance
the generation when restarting a completed timer; retain it during ordinary
updates to the current activation. This includes a new running interval reached
through completed → pause with zero remaining → add → resume: do not rely only
on the immediate previous state being named `done`. For the screen's ring decision:

```javascript
const deliveryKey = `${tm.id}:${tm.activation}`;
if (tm.state === 'running' && !timerRung.has(deliveryKey) && timerLeft(tm) <= 0) {
  ringTimerDone(tm);
}
```

Change both marker writers to use that key and bound/prune old markers. Define
how legacy persisted timers without a generation migrate, so clients do not
invent different activations. A deadline-based key is a smaller option, but
requires careful rules for ordinary extensions, pause/resume, delayed snapshots
and a deadline already delivered. A plain `timerRung.clear()` would replay old
alarms and is not a suitable repair.

**Acceptance:** first expiry and explicit restart each ring once; repeated
snapshots never duplicate either ring. Test restart from history, pause/resume,
extension before expiry, cancellation, clock correction and reconnect. Keep
the intended behavior that already-completed historical timers do not suddenly
ring when a screen opens.

## T-02 — Future local-clock reminders reuse today's fixed offset

**Source:** [`tools/timer_tools.py`](../../Virtual%20Bot/tools/timer_tools.py),
`68–100`.

The default current time is `datetime.now().astimezone()`. With no timezone
argument, Python attaches a local `timezone` object with the system offset;
it does not supply future regional DST transition rules. See
[Python 3.12 datetime documentation](https://docs.python.org/3.12/library/datetime.html#datetime.datetime.astimezone).
The helper replaces the date/time on that value and subtracts to obtain seconds,
therefore preserving today's offset for a future date.

Two Europe/Berlin fixtures pass the fixed-offset form of the current instant
into the real helper, modeling its default conversion without depending on the
host's timezone. Both reminders request 09:00 the following day:

| Transition | Computed delay | Correct elapsed delay | Actual local deadline |
| --- | --- | --- | --- |
| October 24 → 25, 2026 | 46,800 seconds | 50,400 seconds | 08:00 after the clock moves back |
| March 28 → 29, 2026 | 46,800 seconds | 43,200 seconds | 10:00 after the clock moves forward |

This is a calculation error, not an observed missed real reminder. It affects
clock-time scheduling across an offset change. An elapsed “remind me in one
hour” request follows a different path and is not shown broken by this probe.
Simply replacing the fixed offset with `ZoneInfo` is insufficient if same-zone
datetime subtraction still computes wall-time difference rather than elapsed
seconds.

**How I would fix it:** keep an explicit regional timezone setting, resolve the
requested local date/time in that zone, and subtract UTC timestamps. For valid,
already-resolved local times:

```python
zone = ZoneInfo(configured_timezone)
now = datetime.now(zone)
target = requested_local_datetime.replace(tzinfo=zone)
seconds = target.timestamp() - now.timestamp()
```

Validate nonexistent and ambiguous local times before this step; attaching a
zone alone does not resolve the user's intent. Decide whether ambiguous times
need a user choice or a documented fold, and reject or adjust nonexistent times
explicitly. Store the timezone and resolved deadline with the reminder, and
format confirmation from that instant in the same zone. Do not guess a regional
zone from the current UTC offset. Control/status text needs locale keys.

**Acceptance:** valid next-day reminders survive spring/fall transitions with
the correct elapsed delay. Cover both explicit dates and “next HH:MM”, fold/gap
cases, timezone changes, and the existing seven-day limit. Elapsed-duration
timers remain elapsed durations rather than being recomputed as wall times.

## S-01 — Publication reads and replaces a shared snapshot across awaits

**Source:** [`site_share.py`](../../Virtual%20Bot/site_share.py), `74–83`, `86–106`,
`183–226`; [`main.py`](../../Virtual%20Bot/main.py), `4252–4266`.

Each `share()` loads the registry into a dictionary, adds its slug, then awaits
remote configuration/DNS calls before writing that original dictionary locally.
There is no shared mutation lease or revision check. Two requests can load the
same old registry and each replace it with a snapshot containing only its own
new site. Remote `_put_ingress()` is also given those separate snapshots.

The fixture launches two real `share()` calls and pauses both at the provider
adapter until each has loaded its snapshot. Both return a URL and both reach
the restart adapter. The final local registry and fake remote registry each
contain only one slug. The surviving slug is an ordering artifact; the lost
publication is the important result. This can occur between coroutines in one
event loop because the real path awaits, unlike October 10's synchronous
project-metadata thread collision.

**Impact:** a successful receipt is not evidence that the publication survives
another concurrent request. A concurrent unshare can similarly overwrite a
new share or reintroduce a removed slug; those combinations are source-based
risks, not separately reproduced here. Existing October 3's session-relative
publication-path finding concerns a different root-context problem.

**How I would fix it:** coordinate **all** share/unshare mutations and the
remote snapshot apply, reload state after obtaining the lease, and assign an
operation/generation ID to its receipt. A single-worker skeleton is:

```python
async with publication_lock:
    current = load_validated_registry()
    proposed = apply_mutation(current, request)
    await reconcile_publication(current, proposed)
    save_registry_atomically(proposed)
```

This is a sketch, not an existing helper implementation. An `asyncio.Lock`
coordinates one event loop only; use a cross-process coordinator if several
workers/local writers can publish. Define how operator edits to remote ingress
are preserved rather than overwriting an unrelated configuration. The lease
must span the awaited read/apply/commit sequence, with bounded deadlines and
explicit cancellation recovery; locking only the final JSON write is too late.
A timeout or cancelled await does not prove that a remote write was rejected.
Keep that operation/generation fenced until its outcome is reconciled before
applying a newer snapshot or compensating the old one.

**Acceptance:** two successful adds retain both sites; add/remove interleavings
follow one defined order. Concurrent workers use the same coordinator. Receipts
refer to the committed generation, cancelled operations reconcile, and unrelated
remote routing is preserved. No paid/live publication is needed for regressions.

## S-02 — A post-configuration failure is reported without reconciliation

**Source:** `site_share.py:194–206`, `215–226`.

`share()` changes remote ingress first, then attempts DNS setup through its
existing helper, then saves the local registry. A `RuntimeError` from DNS
returns an error immediately,
without restoring remote ingress or recording the pending operation locally.
`unshare()` has the same multi-step shape. A later restart failure instead
returns an error after the local registry has already changed.

The fixture starts with one registered site. Fake ingress accepts the proposed
second site, then fake DNS raises `Synthetic DNS failure`. `share()` reports
that error. Its local registry still contains only the old site, while the fake
remote registry includes both. It performs no restart. This demonstrates
divergent state; it does not establish that the new site is publicly reachable,
that live Cloudflare failed, or that rollback can always be completed.

**How I would fix it:** make publication a recorded operation with intended
generation and step results, followed by idempotent reconciliation. Use the
coordination from S-01. For each operation, distinguish desired local state,
last verified remote state, DNS result, and connector readiness. If a step
fails, either safely compensate to the old generation or preserve a pending
operation that can be resumed; do not return a bare error that implies nothing
changed. Never undo newer routing while compensating an old operation.

A useful receipt shape is:

```json
{"operation_id":"fixture-op","state":"reconciliation_required",
 "generation":2,"configuration_applied":true,"dns_verified":false,
 "connector_verified":false}
```

The receipt contains safe status fields, not raw provider credentials or logs.
Creating DNS before applying ingress narrows one failure window but does not
make the operation atomic; successful DNS followed by failed ingress remains
another partial state. Validate remote step outcomes, keep the previous
generation, persist local state atomically, and reconcile at startup/on retry.

**Acceptance:** DNS, configuration, local-save, restart and cancellation faults
have explicit recoverable outcomes. Retries do not duplicate or lose sites.
Compensation respects the generation/ownership boundary, and readiness reflects
verified routing rather than only a successful local write.

## Additions I would build

1. **A reminder receipt with timezone and activation.** Show the exact local
   deadline, regional zone and delivery status before the user relies on it.
   Let them inspect missed/acknowledged reminders and change the deadline
   explicitly. This builds on T-01/T-02 and earlier lifecycle-receipt ideas;
   it does not require waking the language model when the clock fires.
2. **A publication operation view.** Show pending/applying/ready/recovery status,
   configuration generation and the safe reason a step failed. Offer retry or
   rollback only for the current authorized generation. Reuse the current
   publication service and locale system, not a second deployment platform.
3. **A deterministic clock/reconnect simulator.** Replay screen snapshots across
   expiry, pause/resume, restart, clock shifts and reconnect without audio or
   provider traffic. Extend the previous sensor/gateway replay idea to deadlines,
   using shared fixtures for backend state and the shipped screen functions.

I would repair the missed reactivation ring and serialize publication first,
then fix regional-time scheduling and publication recovery. The examples fit
the existing Python, JavaScript and filesystem architecture. Each proposal needs
its acceptance checks before implementation is considered done.
