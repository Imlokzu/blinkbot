# Clock alarms: implementation contract

Coordinator review, 2026-10-25. The ZCode design worker inspected the timer,
clock and delivery sources but its provider repeatedly terminated streams.
This contract records the coordinator's design before implementation.

## Compatibility boundary

Keep `/api/screen/timers`, its `{timers, now}` envelope, existing actions,
and `kind: timer|reminder`. Existing duration-based calls and timer views
retain their fields and behavior. Add optional request fields `at` (`HH:MM`),
`repeat` (`none|daily|weekdays`) and `timezone` (IANA name) for clock alarms,
which remain reminders. Alarm views add those same three fields. Existing
clients can ignore them. The Clock UI and `set_reminder` call one shared
`screen_widgets.add_alarm` function. Existing dated/relative reminders remain
supported through their original path.

The clock gains an Alarms tab for creating, pausing/resuming and cancelling
alarms and viewing existing reminders. It keeps the existing clock, countdown
and stopwatch. Package version increases; labels use its uk/en dictionaries.
Music, phone fallback and push remain out of scope.

## Scheduling and delivery

The backend owns recurring occurrence advancement, persistence and activation
numbers. A cancellable lifespan task checks due recurring alarms; old one-shot
timers keep their existing screen countdown behavior. Each due batch advances
to the next calendar occurrence and saves its notices atomically before any
event is emitted. No model or audio call happens in this loop.

One existing `type: timer` event uses `action: alarms_due`, a `due` array of
the old occurrences eligible to ring, and the usual updated `timers` list.
Batching prevents one alarm's next snapshot from swallowing another alarm's
simultaneous occurrence. The screen rings through its existing audio path,
deduplicating by `(id, activation)` before applying the new snapshots.
Occurrence receipts remain separate from the latest countdown generation so
an out-of-order snapshot cannot suppress an otherwise fresh due event or mark
the next occurrence as delivered. Legacy delivery tests must remain green.

Occurrences at most 60 seconds late may ring on a connected, updated screen.
Older missed occurrences become notices and advance directly to the next
future occurrence; they never produce an overdue burst of sound. A stable
notice key per alarm bounds retained history. No exactly-once audio guarantee
is claimed across crashes or disconnection: persisted advancement prevents
replays, and notices retain the occurrence when a live event is missed.

## Calendar and controls

Store the chosen IANA timezone and schedule by local calendar date, not by
adding 24 hours. In a DST gap move to the first valid local minute after the
requested time; in a fold use the first occurrence only. Weekdays mean Monday
through Friday in the alarm's saved zone. Browser UI supplies its zone; bot
calls use a resolvable server zone, with an explicit UTC fallback if unavailable.

Paused recurring alarms do not advance. Resume chooses the next future
calendar occurrence, without replaying skipped alarms. Cancellation removes
future scheduling. Extending a recurring alarm through the old countdown
`add` action is rejected atomically; countdown extension keeps its old behavior.
Keep the existing timer/reminder count caps and one-shot duration ceilings.

## Ownership and acceptance

Backend worker: `screen_widgets.py`, `tools/timer_tools.py`, and new
`tests/test_screen_alarm.py`. Coordinator: the small lifespan hooks in
`main.py`, delivery handling in `screen.js` / `timer-delivery.js`, clock package,
and additional screen locale keys. No other actor may edit these files.

Require API/tool record equivalence, persistence/restart, weekday and DST
gap/fold tests, duplicate/reordered/simultaneous delivery tests, paused and
cancelled alarms, missed-occurrence notices and unchanged old timer suites.
Then run the full backend suite, isolated HTTP smoke, and a 320x240 visual
check in both locales and screen styles. The owner's live bot is not restarted.
