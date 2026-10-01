# Repository review — 2026-10-01

This is a source review of the current checkout, with the emphasis on the
Virtual Bot control plane and the Vision Agent. It records concrete bugs,
deployment risks, and product ideas with an implementation direction for each.
The report is English-only, as required by `AGENTS.md`.

The checkout already contained unrelated uncommitted dashboard, instruction,
and generated-asset changes. I did not edit, stage, or judge those changes. The
only change for this task is this report.

## Validation snapshot

- `Virtual Bot`: `PYTHONPATH=. .venv/bin/pytest -q` → **928 passed, 6 skipped,
  150 subtests passed**.
- Isolated live server on `127.0.0.1:18100`:
  `/` → 200, `/api/status` → 200, `/static/index.html` → 200, and a memory
  traversal request → 400. The server was stopped afterwards.
- Temporary fixture probes reproduced four issues below: an unauthenticated
  HTML upload, a user-brain context mismatch, invalid Vision settings causing a
  later `ValueError`, and an app install that deletes the old copy when a copy
  fails.
- Fable and the configured native reviewer lanes were unavailable in this
  runtime: each rejected the installed default model before doing work. The
  findings below are therefore the primary review evidence, with the
  reproduction steps recorded so a future reviewer can rerun them.

## Priority order

| ID | Finding | Severity | Confidence | First action |
|---|---|---:|---:|---|
| R-01 | Chat uploads have no auth or byte limit and are served as same-origin files | High when remotely reachable | Reproduced | Gate, cap, isolate, and serve safely |
| R-02 | Setup mutation endpoints bypass the Clerk gate | High when remotely reachable | Reproduced | Apply an explicit onboarding/operator policy |
| R-03 | Context preview and compaction do not activate the user's brain root | Medium | Reproduced | Enter `_brain_context` before building prompts |
| R-04 | Vision settings accept values that crash the next frame | Medium | Reproduced | Constrain the request model |
| R-05 | Camera read failures keep a dead capture open | Medium | High from source | Release and reopen after failed reads |
| R-06 | Screen app installation is destructive before it is durable | Medium | Reproduced | Stage and atomically swap the new copy |
| R-07 | Concurrent chat mutations can lose the last writer's changes | Medium | High from source | Add per-session locking or use SQLite transactions |
| R-08 | URL saves follow redirects without a private-network check | Medium | High from source | Reuse a redirect-by-redirect public-fetch guard |
| R-09 | Workspace preview/file routes bypass the user gate | Medium when remotely reachable | High from source | Authenticate private file reads; keep public shares separate |

The “when remotely reachable” qualifier matters because the normal Virtual Bot
server setting is loopback. It becomes a real network vulnerability if the
service is bound to a LAN address, put behind a reverse proxy, or exposed by a
tunnel without an equivalent auth layer.

## Findings

### R-01 — Chat uploads are unauthenticated, unlimited, and same-origin

**Evidence.** `Virtual Bot/main.py:2993-3018` defines
`POST /api/chat/upload` without calling `_require_user`. It copies the complete
`UploadFile` into `cfg.UPLOADS_DIR` with `shutil.copyfileobj` and returns a
predictable `/uploads/<sanitized-name>` URL. The matching reader at
`Virtual Bot/main.py:4167-4181` returns `FileResponse(target)` without
`Content-Disposition: attachment` or `X-Content-Type-Options: nosniff`.

**Observed behavior.** With `CLERK_DISABLED` forced off, a temporary TestClient
posted `proof.html` without credentials and received 200. A subsequent GET of
the returned URL received 200 with `text/html; charset=utf-8`. The upload code
therefore permits both disk exhaustion and same-origin HTML execution if the
service is reachable by an untrusted caller. The filename collision loop also
has a check-then-open race under concurrent uploads.

**How I would fix it.** Make ownership and storage part of the API contract,
then make the file response inert:

```python
MAX_UPLOAD_BYTES = 8 * 1024 * 1024

@app.post("/api/chat/upload")
async def api_chat_upload(request: Request, file: UploadFile = File(...)):
    user_id = await _require_user(request)
    data = await read_limited(file, MAX_UPLOAD_BYTES)
    media = sniff_allowed_media(data, file.content_type)
    if media not in {"image/png", "image/jpeg", "image/webp", "text/plain"}:
        raise HTTPException(415, "unsupported upload type")
    name = f"{user_id}/{uuid.uuid4().hex}{media.extension}"
    atomic_create(cfg.UPLOADS_DIR / name, data)
    return {"url": signed_upload_url(name), "type": media.mime, "size": len(data)}
```

The read route should require the same user (or a short-lived, signed token),
set `Content-Disposition: attachment`, set `X-Content-Type-Options: nosniff`,
and never execute an uploaded HTML/SVG document on the dashboard origin. Add a
test that posts an over-limit body and a test that an unauthenticated upload
gets 401.

### R-02 — Setup mutation routes bypass the auth policy

**Evidence.** `POST /api/setup` at `Virtual Bot/main.py:1042-1046`,
`POST /api/setup/mcp/enable` at `:1087-1092`, and
`POST /api/setup/preset/enable` at `:1095-1103` do not accept `Request` and do
not call `_require_user`. The MCP route passes user-supplied environment values
to `_enable_one_mcp`, which invokes an `openclaw` subprocess at
`:1064-1084`. The protected store routes immediately above them do call
`_require_user`, so this is an inconsistent boundary rather than a deliberate
global policy.

**Observed behavior.** With the Clerk verifier forced on and the profile path
patched to a temporary file, both `GET /api/setup` and `POST /api/setup` were
200 without credentials. The profile was changed by the unauthenticated POST.

**Impact.** A remote caller can change the bot identity and, depending on the
installed OpenClaw catalog, make the process run setup commands. A cross-site
form can also reach these POST routes because they have no CSRF token or
origin policy.

**How I would fix it.** Decide which of these is intended:

1. For a signed-in dashboard, call `_require_user(request)` on every setup
   route and use an operator allowlist for MCP installation.
2. For first-run local onboarding, expose a separate loopback-only route with a
   one-time setup nonce, expire it after the first successful setup, and reject
   forwarded requests. Do not use the global `CLERK_DISABLED=1` switch as the
   production authorization mechanism.

Whichever policy is chosen, add a route matrix test that runs with auth both
enabled and disabled. The test should assert the expected status for every
state-changing endpoint.

### R-03 — Context preview and compaction use the wrong brain root

**Evidence.** `api_chat_context` enters only
`brain_context.set_clerk_user(clerk_uid)` at `Virtual Bot/main.py:2886-2887`.
It calls `brains.system_prompt_parts` at `:2891-2897`, but does not enter the
same `_brain_context(session_id, clerk_uid)` used by the real chat path. The
same pattern appears in `api_session_compact` at `:2929-2951`.

`brains.system_prompt_parts` reads profile and notes through the active brain
root (`Virtual Bot/brains.py:300-330`). The real chat endpoint establishes that
root; the context-preview and compaction endpoints do not.

**Observed behavior.** In a temporary fixture, an Alice-only note appeared in
the `notes` prompt part when both Clerk and brain context were set. With only
`set_clerk_user("alice")`, the `notes` part disappeared. The UI can therefore
report a context size that does not match the next request, and a compaction
summary is generated without the user's profile/notes.

**How I would fix it.** Use one context wrapper for all prompt-producing paths:

```python
with _brain_context(session_id, clerk_uid), chat_store.set_kind(_chat_kind(kind)):
    history = chat_store.history(session_id, cfg.CHAT_HISTORY_LIMIT)
    parts = brains.system_prompt_parts(message or "")
```

Keep the existing validation for `session_id`, and add an isolation test that
compares `/api/chat/context` with the exact `system_prompt_parts` used by a
real chat request for two users.

### R-04 — Invalid Vision settings can turn into a later 500

**Evidence.** `Vision Agent/main.py:187-195` declares `min_face_size` as an
unconstrained `list[int]` and `motion_min_area_ratio` as an unconstrained
`float`. `update_settings` stores the values directly at `:212-228`.
`detect_faces` unpacks exactly two values at `:129-147`.

**Observed behavior.** Calling `update_settings(VisionSettingsUpdate(min_face_size=[]))`
returned a valid response containing an empty list. The next
`detect_faces(...)` raised `ValueError: not enough values to unpack`.
Negative/zero sizes and ratios outside 0–1 can also reach OpenCV or make the
detector meaningless.

**How I would fix it.** Put the invariant at the request boundary:

```python
from typing import Annotated
from pydantic import Field

FaceSize = Annotated[list[int], Field(min_length=2, max_length=2)]

class VisionSettingsUpdate(BaseModel):
    motion_min_area_ratio: float | None = Field(default=None, ge=0.0, le=1.0)
    min_face_size: FaceSize | None = None

    @field_validator("min_face_size")
    @classmethod
    def positive_face_size(cls, value):
        if any(size < 1 for size in value):
            raise ValueError("face dimensions must be positive")
        return value
```

Return 422 for malformed settings and add a regression test that posts `[]`,
`[0, 40]`, and `[40, 40, 40]` before calling a frame endpoint.

### R-05 — Camera read failure does not trigger recovery

**Evidence.** `Vision Agent/main.py:318-330` returns an error when
`cap.read()` fails, but never releases `_capture` or sets it to `None`.
`_get_capture` only reopens when the object is missing or `isOpened()` is false
(`:294-315`). Many camera failures leave an object that still reports opened,
so every later snapshot/stream attempt can reuse the dead handle.

**How I would fix it.** Under `_capture_lock`, release and clear the handle on
read failure, then let the next request reopen it. For a stream, use bounded
backoff and a failure counter so a disconnected USB camera does not create a
busy loop:

```python
ok, frame = cap.read()
if not ok or frame is None:
    cap.release()
    _capture = None
    return None, "camera read failed; reopening"
```

Add a fake `VideoCapture` test whose first read fails and whose second call
must construct a new capture object.

### R-06 — Screen app installation removes the old copy before the new one is safe

**Evidence.** `Virtual Bot/screen_store.py:269-303` deletes the destination
with `shutil.rmtree(dst)` at `:287` and then copies files one by one. If a copy,
disk write, or process interruption fails, the old working app is already gone
and the destination may be partial.

**Observed behavior.** A temporary install with an existing `old` app and a
patched `shutil.copyfile` that raised `OSError("disk full")` returned `io_error`
and left no old `index.html`.

**How I would fix it.** Copy into a sibling staging directory, validate the
manifest and entry, fsync files if durability matters, rename the old directory
to a backup, rename staging into place, then remove the backup. On any failure,
remove only staging and keep the old destination. This is the same atomic
pattern already used by `import_archive` at `screen_store.py:545-563`.

Add a test that injects a copy failure halfway through and asserts that the
previous version remains launchable.

### R-07 — Concurrent chat mutations can lose updates

**Evidence.** `Virtual Bot/chat_store.py:268-337` reads a session JSON, mutates
it, writes a temporary file, and replaces the session. Participant, reaction,
title, pin, project, and compaction paths use the same read/replace pattern
(`chat_store.py:190-216`, `:360-436`, `:617-660`, and `:664-716`) without a
per-session lock or an optimistic revision check.

Two simultaneous chat streams in separate workers or integration threads can
both read version N, append different messages, and replace the file. The
second replace wins, silently dropping the first response. The current
single-worker event loop makes this less likely during ordinary dashboard use,
but the file format has no protection when the deployment gains a second
worker or a threaded bridge. A late reaction or title update can similarly
overwrite a newer assistant message.

**How I would fix it.** For a small single-process deployment, keep a bounded
`asyncio.Lock`/threading lock keyed by session id around every read-modify-write
operation. For multiple workers or future Pi/server separation, move sessions
to SQLite and use a transaction with a revision column. Add a concurrency test
that starts two appends with a barrier between read and replace and asserts all
four messages survive.

### R-08 — Saving a URL follows redirects without checking the destination

**Evidence.** `Virtual Bot/workspace.py:303-347` accepts any URL beginning with
`https://` and creates an `httpx.Client(..., follow_redirects=True)` at
`:328-330`. It checks the final response's content type and size, but never
resolves or rejects private, loopback, link-local, or metadata IPs on the
original URL or each redirect.

The authenticated `POST /api/workspace/save-url` route reaches it at
`Virtual Bot/main.py:3684-3692`. This is intentionally useful for saving public
images, but it also gives a user-controlled URL a server-side network request.

**How I would fix it.** Share a `fetch_public_url` helper with
`Virtual Bot/web_browser.py`, which already checks every redirect at
`:125-181`. Resolve DNS, reject private/link-local/loopback/multicast ranges,
disable proxy inheritance for local services, cap redirects and bytes, and
recheck the destination before reading the body. Add tests for a public URL
redirecting to `127.0.0.1` and to an RFC-1918 address.

### R-09 — Private workspace preview routes bypass user authentication

**Evidence.** `GET /api/browser/page` at `Virtual Bot/main.py:3939-3967`,
`GET /preview/{file_path}` at `:4016-4036`, `GET /file/{file_path}` at
`:4128-4161`, and `GET /uploads/{file_path}` at `:4167-4181` do not call
`_require_user`. The first three operate on the workspace selected by the
`session_id` query parameter, while the upload route is global to the process.
The path checks prevent traversal, but they do not establish ownership.

These routes are convenient for the local dashboard, and `/site/...` is
deliberately public for an explicitly shared site. They should not silently
inherit that public behavior if the server is exposed beyond loopback.

**How I would fix it.** Split private and public surfaces:

- require Clerk (and set the user workspace context) for `/preview`, `/file`,
  `/uploads`, and `/api/browser/page`;
- keep `/site/{slug}` public, but serve it from a separate public-only handler;
- add an explicit `public_share` route rather than allowing a query parameter to
  choose a session's files;
- test anonymous, another-user, and owner requests separately.

## Ideas I would add next

### 1. Make authorization a declared route contract

The current code mixes Clerk-protected dashboard routes, loopback OpenClaw
bridges, and tokenless device routes. Add a small table or decorator with four
classes: `public_share`, `device_loopback`, `user`, and `operator`. Generate a
test matrix from that table. This prevents a new endpoint from accidentally
becoming “public because the screen has no token.”

### 2. Add resource budgets as first-class configuration

The Pi-facing services should expose a bounded budget for camera FPS, frame
pixels, upload bytes, ASR bytes, concurrent TTS jobs, and queue depth. Show the
current budget and degraded reason in `/api/status`. A bot that says “camera
offline” or “voice queue full” is easier to operate than one that silently
swaps to a slow path until the Pi runs out of memory.

### 3. Treat memory and chat as user-owned data with export and retention

Add an authenticated export endpoint that produces a manifest plus brain notes,
chat sessions, and attachments for one user. Add retention controls for raw
logs, compaction archives, and uploads. The export should be generated from a
snapshot so an active chat cannot produce a half-consistent archive.

### 4. Add deterministic sensor and gateway replay

The system already has clear boundaries (`Vision Agent`, ASR, display events,
OpenClaw gateway). Save sanitized frame metadata, event streams, and mocked
gateway responses as fixtures. A replay command could exercise “camera drops,
gateway times out, TTS falls back, then reconnects” without hardware or paid
providers. This would cover the failure paths that the current happy-path
tests cannot observe.

### 5. Add a physical-safety capability boundary before mobility

The full spec defers motors and navigation. Before adding them, introduce a
capability layer that distinguishes read-only observation, reversible device
actions, and physical movement. Require an explicit user confirmation and a
hardware watchdog for movement; keep the existing LLM/tool layer unable to
write GPIO directly. That boundary will be cheaper to test before hardware is
attached.

### 6. Replace deprecations before the next framework upgrade

The test run reports FastAPI `on_event` deprecations and the installed
Starlette TestClient warns about the `httpx` compatibility layer. Migrate
`omni_shim.py` and the remaining event handlers to lifespan context managers,
then pin the supported TestClient/httpx pair in the project requirements. This
is low priority today, but it will otherwise become an upgrade blocker.

## Items I checked and did not repeat as current bugs

Several older handoff findings appear addressed in the current source: the
Darwin Voice Loop creates a fresh `pyttsx3` engine per call and clears the
cached engine on errors (`Voice Loop/voice_loop.py:106-155`); Vision checks
empty/broken image buffers, byte size, and OpenCV pixel count
(`Vision Agent/main.py:116-127`, `:231-271`); and the display WebSocket cleanup
and custom-screen timer have dedicated guards (`claude-bot-display/frontend/src/hooks/useWebSocket.js:45-83`,
`frontend/src/App.jsx:56-178`). I would not open those as duplicate tickets
unless a new regression appears.
