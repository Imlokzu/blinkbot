# Night-agent timer activation repair — 2026-10-11

Starting revision: `9f21a8c6`. This pass inspected the new screen conversation
and wake-name work plus the October 11 repository audit. The existing audit's
real-source Node probe reproduced T-01: the first expiry rang once, but an
explicit restart under the same timer ID produced no second alarm. DOM and
audio were inert adapters; no owner timer or provider was used.

## Behavior and contract

Each timer/reminder now exposes a persisted positive `activation` integer.
Creation starts at 1. An `add` that gives exhausted time another positive
countdown increments it, including done → pause with zero left → add → resume.
Ordinary running/paused extensions and pause/resume keep their activation.
The existing minimum-one-second behavior for zero/negative additions remains.
Legacy timers receive generation 1 on the server, saved on their first timer
read or mutation; subsequent reads do not repeat the migration. Client snapshots
without the field use that same initial generation.

The screen's delivery ledger replaces the permanent ID-only marker. It keeps
the highest observed activation per timer and a delivery receipt for that
activation. Done history is suppressed without suppressing a newer restart;
known older activations cannot replace the newer countdown or its clock sample.
Repeated ticks/snapshots do not repeat a delivered activation. Every current ID
retains its fence, including completed timers. Retired history retains at most
512 additional IDs, so the record bound is **current snapshot IDs + 512**.

The API shape is extended by one timer field; existing fields, IDs, routes,
timer tools, labels, visuals and audible alarm pattern remain. No new visible
message or dependency was introduced. The new module is a local ES module and
works offline with the screen's existing assets.

## Fresh validation

- Final full guarded backend run: **1,856 passed, eight skipped, 178 subtests
  passed**, with six warnings, in 85.90 seconds. Five warnings are existing
  deprecations; one is pytest's already-imported-anyio rewrite warning.
- Final focused widget/activation suite: **47 passed**. It executes actual
  shipped screen functions and the delivery module through Node with inert
  alarm/DOM adapters, and checks real backend persistence/API transitions.
  Cases include first expiry, repeated snapshots, explicit/history restart,
  pause/add/resume, zero-time resume, negative/zero additions, legacy migration,
  reminders, cancellation, clock correction, stale generations/clock samples,
  large completed snapshots and bounded retired retention.
- Recent voice/wake work: **81 focused tests passed**, using synthetic media
  and transports. This does not retest real ASR, ElevenLabs or acoustic echo.
- Dashboard: **231 tests passed**, zero failures/skips; TypeScript passed.
  Translation catalog checks and **21 i18n checker tests** passed. No production
  dashboard build or publication was needed for this screen/backend repair.
- Python AST/compile, both JavaScript syntax checks, and scoped whitespace
  checks passed.

The first full run had 1,827 passes and 27 failures during fixture teardown,
plus eight skips. A retry of the affected modules reproduced those cleanup
failures. The temporary guard had mistaken descriptor-relative `runtime`
entries for the real owner directory, denying their removal. The corrected
guard retains absolute owner-path and dotenv restrictions while allowing
descriptor-relative fixture entries. All 63 affected-module tests and 44
subtests then passed; the final full run above passed without cleanup failures.
No product workaround or test exclusion was added. Exact failed-run fixture
roots were cleaned up without sweeping other agents' temporary directories.

The guard clears inherited provider/updater settings, blocks owner dotenv,
profile and runtime reads, supplies synthetic configuration, and redirects
brain/workspace/code/chats/widgets/databases/integrations into temporary storage
with offline mode. Checks exercise working source, including unrelated existing
drafts; they do not certify a clean deployment excluding that work.

Re-run the focused suite from `Virtual Bot/`, with the Mac muted:

```bash
PYTHONPATH="$PWD" .venv/bin/pytest tests/test_screen_widgets.py tests/test_timer_activations.py -q
```

## Independent review and browser/server evidence

Fable and the configured specialist model were unavailable. Two supported
native reviewers worked separately at maximum effort using the installed
code-reviewer/architect instructions. LSP/ast-grep were unavailable; executable
probes, source tracing, Python AST/compile and Node syntax checks were explicitly
authorized diagnostic fallbacks. This is native review, not Fable certification.

The first code review requested changes: evicting a completed current ID could
replay its stale activation, and an older generation's clock sample could
delay the newer deadline. Both now have regression tests and repairs. Final
code/spec/security verdict: **APPROVE**, with 47 independent test passes.
Architecture: **WATCH**, no unresolved T-01 blocker, retaining the transport
limits below. Its independent large-history probe preserved 4,000 current done
entries before pruning retired history to 513 records. Synthesis: **COMMENT**.

An isolated Chromium browser loaded the complete screen at **320×240**, including
the actual local module and actual HTTP timer routes. A temporary screen copy
exposed fixture controls and replaced only alarm/TTS execution with counters.
Three expiries, with explicit same-ID restarts, produced activations **1, 2, 3**
and exactly **three alarm calls**, with zero uncaught page errors. The timer view
was inspected. It used an empty browser configuration, a fresh namespace and
session, loopback-only traffic and a synthetic server clock. The final run after
review fixes passed; its browser/server closed and port 18141 was verified closed.
This checks rendered wiring with inert audio, not physical speaker behavior.

After both final reviews, uninstrumented actual `main.app` ran temporarily on
**127.0.0.1:18142**, lifespan disabled and external connections/DNS blocked.
**37 curl expectations passed**, including timer creation, completed history,
persisted activation-2 restart, paused extension without another generation,
cancellation, three traversal responses of 400, mobile-host 401/404 boundaries,
direct dashboard assets and the new module. Timer bodies and on-disk activation
matched. The server exited zero and its port was confirmed closed.

## Limits and separate follow-ups

Activation ordering does not order ordinary edits within one activation or
create cancellation tombstones. Delayed same-generation pause/extension/cancel
snapshots retain their earlier transport limitations. Retired IDs evicted beyond
the 512-record history no longer have a replay fence. Delivery receipts are per
screen instance; opening a screen suppresses already-done history, and multiple
screens can each deliver their own alarm.

Publishing a committed restart can still fail, and SSE reconnect does not
automatically reload timer state. A later snapshot/page reload can recover the
persisted generation; automatic resynchronization needs a separate transport
repair. Neither snapshot revisions nor a remote delivery acknowledgment system
was added. The audit's regional DST scheduling and site publication findings
remain unresolved and separate.

No real reminders, physical audio, microphone, ASR/provider calls, cloud mutation,
production reload or deployment were performed. Existing shared drafts remain
preserved, test services are stopped, and the Mac remains muted.
