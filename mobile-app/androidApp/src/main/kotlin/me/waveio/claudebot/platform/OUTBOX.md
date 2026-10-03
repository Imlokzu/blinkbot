# Android persisted outbox

The source remains `native-preferences` with `device_id`, `server`,
`<deviceId>.outbox.v1` (a JSON list of common `OutboxItem`), and
`<deviceId>.outbox.allowed` (a JSON list of approved client IDs).
`device_token` remains encrypted in `native-secrets` with an Android Keystore key.
Tokens are never WorkManager input/output data or log messages.

`AndroidBridge.updatePreferences(keys, transform)` delegates to
`NativeOutboxStore.updatePreferences` under the same process-wide monitor used by
workers. Common persistence should apply explicit additions/removals to the latest
values supplied to this transform, commit queue/allowed changes together, and
reread afterward to synchronize its cache. It should not replace persistence with
a stale cached whole list.

Acknowledgement rereads the queue under that monitor and removes only an exact
matching item after a validated server acknowledgement. The sidecar
`<deviceId>.outbox.ack.v1` stores acknowledged IDs; stale foreground writes cannot
reintroduce them. `<deviceId>.outbox.failed.v1` maps blocked IDs to status, safe
machine code, and payload fingerprint. Permanent failures retain their original
queue entry for foreground recovery. Foreground recovery may remove or replace
that entry with a new client ID; clearing a failure marker is an explicit retry.

`OutboxItem.deliveryDeclined` preserves an explicit foreground refusal while
retaining its payload. Background authorization and every pre-send check exclude
both declined and permanently failed entries, even if a stale allowed list still
contains them. Legacy whole-list saves cannot clear these guards; an explicit
atomic retry clears the guard and grants that client ID together.

`native-outbox-meta` stores a credential epoch, readiness/binding metadata,
per-item owner hashes and an optional authorization-failure block. A partial
credential update disables background work. Pairing writes `device_id`, then
`device_token`, then `server`; the final server write binds the coherent tuple.
Only the first migration can bind existing credentials without that sequence.
Old entries retain their credential binding even if a new pairing reuses the same
device ID. No token is stored as plaintext in either preference file.

Changes to queue/allowed values enqueue the unique `claudebot-outbox` chain using
`APPEND_OR_REPLACE` and a connected-network constraint. Startup uses `KEEP` to
avoid multiplying retries. Each worker snapshots its account, uses common
`BotApi`, preserves `client_id`, and rechecks account/approval before sends and
account/exact payload before removal. Batches are limited to 50 with an eight
minute drain timeout. Transient network/408/425/429/5xx failures retry with
exponential backoff; 501 and permanent errors stay blocked for recovery.

The current common fork adapter supports edit/regenerate metadata, model and
effort. Fork attachments, scheduling, or alternate delivery are retained as
unsupported rather than silently omitted until the common adapter supports them.

WorkManager persists OS work after the activity/task closes; timing depends on
connectivity, OS restrictions and OEM behavior. Force-stop suppresses execution
until the app is opened again. Cancelling/re-pairing stops future submissions;
an already transmitted request cannot be recalled. A completed worker means the
drain finished, not that every retained entry was delivered.
