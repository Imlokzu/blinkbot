# Calendar alarms

Wave 1 adds the shared `screen_widgets.add_alarm(at, label, repeat, timezone)`
path for the Clock app and `set_reminder` tool. The existing timer endpoint,
response envelope and duration-based calls remain compatible. The Clock package
is version 1.1.0 with an Alarms tab alongside clock, countdown and stopwatch.

Daily and weekday alarms use saved IANA zones and calendar dates. A spring gap
moves to the first valid minute; an autumn fold uses the first occurrence only.
Pause suspends scheduling; resume chooses a future occurrence. Countdown
extension is rejected for recurring schedules. Twenty reminder schedules and
five countdown timers remain the limits.

The lifespan loop advances due schedules, increments activations and saves
bounded keyed notices in one atomic file replacement before emitting one batch.
Occurrences at most 60 seconds late may ring through the existing screen audio
path. Older occurrences produce a missed notice and jump to the next future
date. Invalid schedule fields pause that alarm with a localized notice, allowing
other alarms to continue. A screen must be connected to receive a live ring;
persisted notices cover missed events. Exactly-once audio across disconnection
or power loss is not guaranteed. Music and phone fallback are not implemented.

Delivery receipts are keyed by ID and activation independently of the latest
snapshot. Recurring countdowns never ring themselves on a suspended screen's
return. Stale generations cannot replace newer list membership, and clearing
notifications preserves recurring schedules. The Clock app fences in-flight
polls against mutations, preserves failed form drafts and renders labels as text.

The OpenCode implementation worker stopped midway at its step limit; the
coordinator completed and tested the backend. Independent GLM review approved
the design and suggested stale-snapshot, invalid-schedule and clear-notice fixes;
those fixes and regression checks are included. See VALIDATION.md for final
commands and results. The owner’s running bot was not restarted.
