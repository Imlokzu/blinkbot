# Durable mobile adapter

`mobile_api.py` and `mobile_store.py` are independent of `main.py`, Clerk,
brains, and provider configuration. They extend the existing host; they do
not create another chat-history or workspace namespace. All timestamps in
responses are Unix seconds (possibly fractional).

## Parent integration contract

Construct one `MobileStore` for the host and pass that same instance to the
router and authentication hook. The default, lazily initialized store is
`Virtual Bot/runtime/mobile.sqlite3`. No runtime database is opened on import.

Register `mobile_api.router(require_user, require_operator, run_turn,
store=store, server_origin="https://<approved-api-host>")` before the static
catch-all. Both authentication callbacks are async and accept a FastAPI
`Request`. `require_user` returns the original Clerk user ID, or **the empty
string** for the existing local owner. `require_operator` must enforce the
existing direct-local/authorized-operator policy, including forwarded-request
checks. The pairing issuer is not a public remote endpoint.

The returned `MobileRouter` exposes public resources for sibling modules:

- `mobile_routes.store`: the exact `MobileStore` passed to the factory.
- `mobile_routes.runtime`: the existing scheduler/task owner, not a new runtime.
- `mobile_routes.identity(request)`: its mobile-aware owner authentication.

The parent may include a workspace/content router with
`shared_store=mobile_routes.store` and `runtime=mobile_routes.runtime`, and may
inject `mobile_routes.identity` as its user gate. Only the mobile router owns
runtime startup/shutdown; sibling routers must not create another scheduler.
`runtime.submit(user_id, validated_payload, request=original_request)` durably
submits through the same store and wakes that scheduler, returning the full
stored job. Alternatively call `runtime.notify()` after a separate store write.
`request` is optional and is only for internally prepared operations; public
message submissions still fingerprint the full validated request.

Before any Clerk verification or disabled-auth early return, extract the
request credential and call `mobile_api.authenticate_token(token, store=store)`.
Test the result with **`is not None`**, not truthiness:

- Nonmobile credentials return `None`; continue the existing authentication.
- A valid mobile token returns its original owner, including `""`.
- Invalid, expired, or revoked `cbm_` credentials raise HTTP 401 and must
  never fall through to disabled authentication.

Apply this interception to shared authenticated routes and any optional-auth
SSE gate that should accept the phone credential. The mobile router performs
the same interception itself. Token prefixes are not proof of validity.

`run_turn(job)` must return an async iterator of `(event_name, JSON_dict)`
tuples. An async factory returning that iterator is also accepted. Its input
contains `id`, `user_id`, `client_id`, `session_id`, `message`, `attachments`,
`model`, `reasoning_effort`, `delivery`, and `scheduled_at`. The `session_id`
is generated and durably recorded **before** the callback starts.

`mobile_api.current_turn_options()` returns task-local
`{model,reasoning_effort,user_id,session_id,seed_history}`, or `None` outside a
mobile run. The adapter establishes this context before calling the factory
and preserves it throughout iteration/cleanup. Child chat tasks inherit it;
other conversations and PC requests do not. The getter returns a copy.

Image turns use the same `brains.chat_openclaw` dispatch as the web client.
The shared `_image_headers()` reads the configured gateway image model; if it is
unset, the gateway chooses. Mobile text selections and incomplete catalog
`vision` badges do not block or override this path. The phone still applies
session-local effort, without pinning a text model during an image request.
Gateway fallback and its actual model report are preserved, with no additional
phone-side image retry or provider restriction. This supersedes the stricter
0.3.0 image eligibility gate; installed clients need no update for this fix.

For text-only turns, `mobile_routing.chat_gateway` validates the model against the existing
catalog and supplies an `x-openclaw-model` override without changing `_selected`
or the gateway's global primary model. Blank text model uses the catalog default;
`jev/auto` invokes the existing Jev router directly for this turn, independently
of the PC picker. Jev's generated thinking directive is not sent: explicit
effort is applied through the session override instead.

Before each candidate call, `sessions.patch` receives `{key: session_key,
model: candidate_id, thinkingLevel: selected_effort}`. `none` clears that
session override with `thinkingLevel: null`; `off` sends `"off"` explicitly.
These are session-scoped preferences reapplied for each mobile turn, not
inline `/think` directives or changes to `agents.defaults`. The shared turn
lease prevents another PC/mobile turn in the same conversation from racing
the patch. Unsupported model/effort combinations must fail truthfully.

For fork jobs, `seed_history` is true and the callback also receives `history`
and `participant_name`. The parent must change the existing
`request_history = [] if session_key else history` suppression in
`brains.chat_openclaw`: use the provided history for a mobile first-fork
turn even though it has a fresh gateway session key. Subsequent jobs do not
carry `seed_history`; the existing gateway transcript remains authoritative.
Otherwise a fork would appear correct in shared files but answer without its
earlier context. Never reuse the source gateway session key.

`mobile_api.iter_chat_events(response)` parses the existing `chat_turn`
StreamingResponse into the required tuples and closes its body iterator on
completion/cancellation. It accepts fragmented UTF-8/CRLF and preserves JSON
payloads. A minimal parent callback, **with the routing/history hooks above
installed**, is:

```python
async def mobile_run_turn(job):
    req = ChatRequest(
        message=job["message"], stream=True, session_id=job["session_id"],
        attachments=job["attachments"], history=job.get("history", []),
        participant_name=job.get("participant_name", ""),
        reasoning_effort="none",  # Gateway effort comes from the task context.
    )
    response = await chat_turn(req, job["user_id"], "chat")
    async for event in mobile_api.iter_chat_events(response):
        yield event
```

The response model must be the effective answering model. Capture the gateway's
`model` event per run; do not infer it from the requested pick or the global
`brains.get_last_model()`, which can belong to another concurrent turn/title.
The saved assistant record also retains `model` (provider-qualified),
`provider`, `requested_model`, and `fallback`, including an interrupted reply,
so reopening the shared history preserves the disclosure.
Mobile history reads use the existing owner-scoped disk store; saving a mobile
turn invalidates the desktop's legacy session-only memory cache.

The callback must:

1. Run the existing `chat_turn` under this original user/session identity.
2. Apply `model` and effort using a per-turn context override. Do not mutate
   the global model picker. Blank model uses the existing default.
3. Bridge the gateway effort values explicitly. Legacy `ChatRequest` accepts
   only `none|low|medium|high`; do not pass `xhigh`, etc. into it unchanged.
4. Parse the existing SSE response iterator into named events and JSON payloads.
   Preserve original event payloads and the actual effective-model metadata.
5. Emit the existing `done` event on success, or `error` on failure. Exhaustion
   without `done` is a failed job, never an invented successful response.
6. Close the underlying iterator/subscriptions and cancel the active work when
   cancelled or closed. Completion waits for callback cleanup before advancing
   the conversation queue. Cancellation must propagate through this wrapper.

The adapter serializes its own jobs per owner/conversation. Existing PC/direct
chat requests must participate in the parent's shared turn gate if they can
execute in the same conversation concurrently. This module does not change
the legacy direct-chat routes. The API hostname also needs an exemption from
the existing wildcard site-host rewrite middleware.

FastAPI includes the router's lifespan: startup acquires an exclusive OS
runner lock, recovers interrupted jobs, and starts the scheduler. Shutdown
cancels and awaits owned tasks before releasing the lock. Use **one live
mobile scheduler per database**; a second worker fails startup rather than
recovering another worker's live runs. The backend host is macOS/Linux.

## Endpoints

All paths below start with `/api/mobile`. Responses use `Cache-Control:
no-store`. Auth is required except for one-time code exchange.

| Method/path | Request | Response |
| --- | --- | --- |
| POST `/pairings` | Operator auth; `{server?: "https://origin"}` | `{code,expires_at,qr_payload}` |
| POST `/pair/exchange` | `{code,device_name,platform:"android"|"ios"}` | `{token,device_id,expires_at}` |
| GET `/devices` | Owner auth | `{devices:[{device_id,device_name,platform,created_at,expires_at,revoked_at}]}` |
| DELETE `/devices/{device_id}` | Owner auth | `{ok:true}` |
| GET `/capabilities` | Owner auth | `{version:1,pairing:true,queue:true,steer:false,stop:true,scheduled_send:true,event_replay:true,idempotency:true,history_fork:true,push:false}` |
| POST `/messages` | Submission below | Job summary below |
| GET `/messages?session_id=` | Optional conversation filter | `{messages:[submission/status projection]}` |
| GET `/messages/{id}/events?after=0` | Exclusive sequence cursor | Stored/live named SSE with `id: <seq>` |
| POST `/messages/{id}/stop` | Empty body | Job summary below |
| POST `/sessions/{id}/resume` | Empty body | `{ok:true,session_id,paused:false}` |
| POST `/sessions/{source_id}/fork` | Fork action below | Job summary with new `session_id` and `fork` metadata |

Every job summary (submit, retry, stop, fork) includes `id`, `session_id`,
`state`, `message`, `scheduled_at` (nullable Unix seconds), `model`, optional
`error`, and optional `fork:{source_session_id,message_id,action}`. The list
endpoint includes these fields plus the full delivery projection described
below, so queued work can be reconstructed after reconnect.

Pairing codes expire after five minutes and can be exchanged once. Device
tokens expire after 90 days and start with `cbm_`. Only SHA-256 hashes of
codes/tokens are stored. The QR payload is
`claudebot://pair?server=<percent-encoded-https-origin>&code=<code>`.
The optional server request must match the configured approved origin. Without
an explicitly configured origin, an operator must supply a valid HTTPS origin.
Device revocation rejects future authenticated requests; already accepted host
work remains durable.

Submission fields:

- Required `client_id` (1–128 characters) and `message` (up to 32,000 characters).
  Blank text requires at least one attachment; the original blank text is
  retained in delivery metadata and shared history.
- `session_id`: default `""`; otherwise 1–64 ASCII letters/digits/underscore/hyphen.
- `attachments`: default `[]`, maximum eight; original upload metadata is
  passed to the existing callback for content/ownership validation.
- `model`: default `""`, maximum 200 characters, explicitly passed to the callback.
- `reasoning_effort`: default `none`; allowed `none`, `off`, `minimal`, `low`,
  `medium`, `high`, `xhigh`, `adaptive`, `max`, `ultra`.
- `delivery`: `queue` (default) or `steer`.
- `scheduled_at`: omitted/null, nonnegative Unix seconds, or an ISO timestamp
  with explicit timezone. Future values are scheduled; past values are queued
  immediately. Schedules remain accepted while the host is offline, and run
  at the first available scan after their deadline.

The client must persist a `client_id` before sending and reuse the same request
after a lost acknowledgement. Uniqueness is per owner, across their devices.
An unchanged retry returns the same job/session; changed content under the
same ID is HTTP 409 `idempotency_conflict`. A retry of an originally blank
session request must retain that blank field. It must not replace it with the
returned generated ID while reusing the original client ID.

List rows include `id`, `session_id`, `state`, `client_id`, `message`,
`attachments`, `model`, `reasoning_effort`, `delivery`, `scheduled_at`,
`created_at`, `updated_at`, `conversation_paused`, and optional `error`.

## Saved-message edit and regeneration

POST `/sessions/{source_id}/fork` takes `client_id`, a stable saved `message_id`,
`action:"edit"|"regenerate"`, optional `model`, and optional `reasoning_effort`.
For `edit`, the target must be a user message and `message` is required; optional
`attachments` replace the original attachments, while omission preserves them.
For `regenerate`, the target must be an assistant message. The adapter resubmits
the preceding user's text and attachments; overriding them is HTTP 422.

The original API request is durably idempotent. A retry remains valid after
the source gains new messages or is deleted; changing an action/model/text
under the same client ID is HTTP 409. Each action needs a new client ID.

The source prefix **before the edited/repeated user turn** is captured in
SQLite at acceptance. Execution atomically creates a new history file in the
existing owner's chat namespace, retaining original prefix messages/IDs and
their display metadata. It never overwrites the source or another destination.
The callback appends the replacement turn to this new conversation through
the existing shared backend. Later source messages are not copied; the source
conversation remains available unchanged. Fork execution receives canonical
`history` for initial gateway seeding as described above. The new shared
history contains `fork_of` metadata, and the job summary names the new session.
Source edits do not silently change an accepted fork's captured prefix.

Message targeting requires a stable backend message ID. Client-generated
display IDs, compacted summary rows without IDs, and unsaved streaming bubbles
are not valid mutation targets. Missing/wrong-owner targets return HTTP 404;
wrong-role targets or invalid saved request content return HTTP 422.

## State and event semantics

States are `queued`, `scheduled`, `running`, `stopping`, `completed`, `failed`,
`stopped`, `interrupted`, and `unsupported`. Success advances eligible pending
work sequentially. Future schedules do not block currently eligible work.
Failure/interruption pauses the conversation so pending jobs need an explicit
resume. Stop pauses before cancelling, retains other queued/scheduled jobs,
and **never** starts the next job. A cancellation-resistant callback yields
`stopping` until it actually settles; Stop does not claim success merely
because the caller disconnected. Stopping a pending job cancels that job and
pauses its conversation queue. Resume while a run is active is HTTP 409.

At startup, previous `running`/`stopping` jobs become `interrupted` and their
queues remain paused. They are never retried automatically: the previous run
may already have performed side effects. Accepted queued/scheduled jobs and
original event payloads survive process restarts.

SSE sequences are monotonically increasing **per job**. `after` is exclusive;
`Last-Event-ID` is used when `after` is omitted. The stream persists and replays
the original event names/data unchanged, plus reserved `mobile_state` events
containing `{id,state,error?}` (the initial event also includes `session_id`).
Ignore comment keepalives. A listener disconnect does not cancel the turn.
Terminal streams drain all stored events and then close. Clients must tolerate
duplicate delivery after reconnect and deduplicate using the sequence ID.

## Steering and remaining integration limits

Steer submissions return HTTP 501 with
the job summary, `state:"unsupported"`, and `error:"steer_unsupported"`. They are
durably idempotent and **never** invoke the runner or cancel an active job.

Installed OpenClaw documents genuine active-run steering in
`docs/concepts/queue-steering.md`, `docs/tools/steer.md`, and
`docs/plugins/codex-harness-runtime/queue-and-feedback.md`. However `/steer`
explicitly falls back to a normal new prompt when the session is idle or the
runtime rejects steering. Codex steering preparation/acknowledgement is not
proof of consumption. The existing `chat_turn` wrapper has no strict steer
acceptance hook. A future parent hook must bind the active run/session and
confirm same-turn consumption without normal-turn fallback before this
capability can become true.

More precisely, the installed `docs/gateway/protocol/rpc-session-control.md`
and `dist/sessions-messaging-BQ-lPqc-.mjs` identify `sessions.steer` as a
deprecated `chat.send` alias with `queueMode:"interrupt"`. It cancels/replaces
work and must not implement the mobile Steer action. The installed
`ChatSendParamsSchema` supports `queueMode`, but exposes no strict active-run
steer-only flag. Its internal injection path can reject and fall through to a
new prompt. Native Codex `turn/steer` is genuine steering, but the current HTTP
chat wrapper does not expose its runtime handle/turn ID or consumption proof.
The safe parent hook needs a bound active run, strict no-followup behavior,
and confirmation of same-turn consumption. Do not convert an ordinary
`chat.send` acknowledgement into a confirmed mobile steer.

Push registration/delivery is not implemented. Same-provider fallback policy
and actual gateway abort acknowledgement remain parent runner responsibilities.
Events/jobs are retained without automatic pruning; define a retention policy
before serving an unbounded production workload.

## Validation

Run `PYTHONPATH="$PWD" .venv/bin/pytest tests/test_mobile_store.py
tests/test_mobile_api.py -q` from `Virtual Bot`. Tests use temporary databases,
an isolated FastAPI app, and fake callbacks. They never start the real backend,
provider jobs, or integrations. Mute the Mac before testing. Parent integration
and provider behavior require a separate review after the surgical hooks land.

## Original workspace downloads

`GET /api/mobile/workspace/download?path=<relative-path>&session_id=<chat-id>`
returns original file bytes for native previews, Save and Share. It uses the same
user and session workspace resolver as the editor. Device credentials are
required; paths are relative to that workspace. The response is capped at
20 MiB and uses no-store, attachment disposition and nosniff headers. Descriptor
traversal rejects symlinks on every component after resolution. Missing files
return 404, invalid destinations 400, and oversized files 413.

The mobile chat derives delivered files from actual successful workspace tool
results, including MCP wrappers. Running writes are not downloaded. Text editor
previews still use `/api/workspace/file`; extracted preview text is never used
as the original export payload.
