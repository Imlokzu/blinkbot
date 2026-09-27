# HANDOFF — Claude Bot (session 2026-07-26, Claude Code / Fable 5)

Context handover file for continuing work in another session (Claude Desktop).
Read it completely before making any changes.

---

## 1. What is this project

**Claude Bot** is a DIY personal AI companion: Raspberry Pi 3 (camera, mic, speaker, SPI display) + home i5 server + Claude API as a "personality". The approach is **software first, hardware later**: the owner has NOT yet bought the bot, so everything must work virtually on macOS.

Full spec: `claude-bot-full-spec-v3.md` (Edge/Fog/Cloud architecture, BOM, roadmap).
Development order: `claude-bot-dev-order.md` (6 steps: vision → RAG → voice → emotions → cameras → UI).

## 2. Repository structure (`/Users/hhh/projects/claude bot/`)

| Folder | What it is | Stack | Port |
|---|---|---|---|
| `Vision Agent/` | Eyes: face/motion detection | FastAPI + OpenCV | 8000 |
| `Voice Loop/` | Ears/mouth: Whisper STT → OpenClaw → pyttsx3 TTS | Python | — |
| `OpenClaw Vision Plugin/` | `vision_check_camera` tool for the agent | TypeScript | — |
| `claude-bot-display/` | Face: pixel eyes, 15 emotions, 4 screens | FastAPI + React/Vite | 8001 (WS) |
| `Remote Control/` | USB remote (VID:PID 0627:697d) + I2C LCD status | Python (Pi) | — |
| `Device Setup Wizard/` | «Claude Bot Studio» — setup | Electron + Vite/React/TS | — |

OpenClaw gateway (brain): `127.0.0.1:18789`, token — env `OPENCLAW_TOKEN` (priority) or `Voice Loop/config.yaml`. **The token is a secret, do not publish it anywhere.**

## 3. What was done in this session (everything applied and built)

- **OpenClaw Vision Plugin**: test fixed (`"echo"` → `"vision_check_camera"`), proper error messages. Tests + tsc clean.
- **Vision Agent** (`main.py`): lock around `_prev_gray` (thread race condition), Haar cascade `.empty()` check, MJPEG stream ends after 30 failed frames (doesn't spin CPU), 8MP per frame limit, camera reopening after failure.
- **Voice Loop**: `OPENCLAW_TOKEN` env, try/except in `transcribe()`, TTS engine is reused (⚠️ see point 4 — this introduced a regression), `validate_config()`.
- **Remote Control**: remote auto-search by VID/PID with fallback, reconnection on OSError, LCD loop doesn't crash without aplay/arecord, errors are printed.
- **Device Setup Wizard**: motion sensitivity slider now on the same scale as API (ratio 0.002–0.05), Vision-fetch error handling, start wait 6s → 15s. tsc clean.
- **claude-bot-display**: WS URL dynamic (`VITE_WS_URL` or page hostname), exponential backoff 2s→30s, countdown timer implemented, `duration_seconds` per contract, ErrorBoundary, pyserial removed. Build + pytest (6) clean.

## 4. ⚠️ RESIDUAL BUGS (found by adversarial verification, NOT YET FIXED)

Priority 1 — **critical**:
1. **Voice Loop `voice_loop.py:~109-132`** — pyttsx3 engine reuse: on macOS (nsss) the second `runAndWait()` on the same engine often hangs or silently says nothing, WITHOUT an exception — try/except won't work. Plus: if TTS does throw an error, `_tts_engine` is not reset to None — the broken engine is cached forever. Fix: on darwin — init on every call (or watchdog + reinit), and drop the cache in except.

Priority 2 — moderate:
2. **Vision Agent `main.py:~209-218`** — 8MP check is placed AFTER `cv2.imdecode`: a ~300KB PNG bomb (100MP) eats ~0.7s CPU and ~900MB RAM until failure (measured). Fix: limit on BYTES length before decoding (e.g., 5MB).
3. **Vision Agent `main.py:~107`** — `cv2.imdecode` on an empty buffer THROWS `cv2.error` (doesn't return None) → 500 instead of 400. Fix: `arr.size == 0` → None or try/except.
4. **display `useWebSocket.js:~50`** — `onclose` without an intentional close guard: in dev under React.StrictMode it results in 2 live sockets (events processed twice, send() lost). Fix: `closedByCleanup` flag in effect cleanup + `if (wsRef.current === ws)` before NULL.
5. **display `App.jsx:~64-78`** — `resetIdle` and `scheduleReturn` share one `idleTimer` ref: a `speaking`/`speaking_end` event during a custom screen with `duration_seconds=0` («показувати доки не замінять» (show until replaced)) resets it to face after 10s, and durations >10s are clipped. Fix: separate refs / skip resetIdle when a duration-managed screen is active.

Priority 3 — minor:
6. **Vision Agent `main.py:~288`** — `_release_camera` (shutdown) touches `_capture` without `_capture_lock` — a narrow race condition on shutdown.
7. **display `App.jsx:~143`** — timer on the setTimeout(1000) chain drifts; better to anchor to a target timestamp.

Remote Control and Setup Wizard passed verification completely — nothing left there.

## 5. NEXT BIG TASK (owner's request, not yet started)

The owner wants (their words, paraphrased): «софт спочатку, бота куплю потім; зроби HTML-вікі по проєкту; все до шику через агентів, кожного робочого агента перевіряє окремий Fable-агент на максимальному зусиллі (ловить всі баги); потім веб-додаток для керування всім — як бот, але віртуальний».

### 5.1 `wiki.html` (project root)
Self-contained HTML (no CDN, works offline), in Ukrainian, in the pixel-retro style of the project:
- project overview and why it exists;
- SVG architecture diagram (Edge RPi3 / Fog i5 / Cloud + components + ports 8000/8001/8100/18789);
- map of «що де лежить» (what is where): all .md files (spec, dev-order, README of each module, `claude-bot-display/API_CONTRACT.md`, AGENTS.md/CLAUDE.md of the wizard) with a description;
- how to run each module (verify commands with README!);
- interactive roadmap checklist (steps 1–6 / phases 0–4; done: steps 1,3, partially 6; NOT started: RAG memory (step 2), emotion layer (step 4), face recognition, navigation) — checkbox state in localStorage;
- changelog of this session (section 3) + known bugs (section 4);
- NO secrets (no tokens).

### 5.2 "Virtual Bot" — web app (new folder `Virtual Bot/`)
Virtual embodiment of the bot before buying hardware + control dashboard. FastAPI backend on **127.0.0.1:8100**, frontend is static vanilla JS/HTML/CSS (no build) in `Virtual Bot/static/`, which the backend serves.

**API contract (agreed, must be followed):**
- `GET /` → `static/index.html`
- `GET /api/status` → `{"openclaw":bool,"anthropic":bool,"vision":bool,"display":bool,"mode":"openclaw"|"anthropic"|"demo"}`
- `POST /api/chat` `{"message":str}` → `{"reply":str,"emotion":str}`; emotion ∈ `idle|listening|thinking|speaking|happy|sad|confused|surprised|love|sleepy`
- `GET /api/vision/snapshot` → proxy JSON from `http://127.0.0.1:8000/vision/snapshot`; offline → 503 `{"error":...}`
- `GET /api/memory/list` → `{"files":[{"path":"people/name.md","title":...}]}`; `GET /api/memory/file?path=...`; `POST /api/memory/save {"path","content"}` — folder `Virtual Bot/brain/{people,topics,logs}/` with starter notes; **path traversal protection is mandatory**
- `POST /api/services/{vision|display}/start|stop`, `GET /api/services` — start/stop local services (uvicorn in the respective folder, use its .venv if present)
- MJPEG frontend fetches DIRECTLY from `http://127.0.0.1:8000/vision/stream.mjpg` (do not proxy)

**Chat brain (by priority):** OpenClaw gateway (pattern from `Voice Loop/openclaw_client.py`, token from env) → direct Anthropic API (`ANTHROPIC_API_KEY`, model `claude-sonnet-5`, via httpx, no SDK) → demo mode (prepared Ukrainian responses) so the app always works. The token must NEVER be given to the frontend.

**Emotion layer (step 4 of the spec):** the system prompt asks the model to start the response with the tag `[емоція:happy]`; parse and remove; fallback — keyword heuristic. Simple memory: top 3 notes from `brain/` by keywords → into the system prompt.

**Frontend dashboards:** Face (pixel eyes with emotions/blinking — get inspired by `claude-bot-display/frontend/src/components/PixelEyes.jsx`), Chat («думає…» (thinking...) state), Vision (stream/status), Memory (viewing/editing notes), Services (start/stop buttons), Status. Everything in Ukrainian.

**Device screen (`/screen`):** separate vanilla UI 320×240 with a tile carousel, quick actions shade, and an Android-like app drawer (5 columns, Камера (Camera), Сервіси (Services), локальна Панель (Dashboard), Памʼять (Memory), Розмови (Conversations), and Налаштування (Settings) without navigating to `/`). Memory reads real `.md` notes via `/api/memory/list|file`, Conversations — saved sessions via `/api/sessions`; with Clerk without login, shows a clear access message. In Settings, the theme, brightness, voice/volume, Piper voice selection, and three icon styles actually work: a colorful 16×16 pixel-pack Pxlkit in the drawer, monochromatic SVGs with color selection, and separate colored SVGs. Additionally, the return-to-home timer, auto-sleep, 12/24 hour time format, date display, and reduced animation mode work; all local parameters survive reboots and are reset by the reset button. The small toggles and clock remain in the internal pixel-language of the bot; for Pxlkit, local SVG assets and visible attribution in Settings have been added. The event feed is removed; swipe up opens the drawer.

**Dependencies:** fastapi, uvicorn, httpx, pyyaml. `requirements.txt`, `config.yaml`, `README.md`, `start.sh`, venv in `Virtual Bot/.venv`.

### 5.3 Process (owner's requirement)
Every worker agent → separate **Fable reviewer at max effort**, who adversarially searches for bugs and FIXES them. At the end — a smoke test: spin up the server, curl all endpoints (including a path traversal attempt → expect 400), check static files, kill processes.

## 6. Action plan for the next session

1. Fix bugs from section 4 (start with critical #1).
2. Build `wiki.html` (5.1).
3. Build `Virtual Bot` (5.2) per contract.
4. Everything — through agents with Fable review (5.3); after each block — run tests/builds.
5. Update this HANDOFF.md at the end (what is done, what is left).

Notes: the project is NOT a git repository (no diffs — be careful with overwrites); the path contains a space — always quote; comments/UI in Ukrainian; code style — minimal surgical changes in existing files.


## 7. Session 2026-08-28 (ZCode): screen store + Now Playing

Everything is in the branch `feat/bot-tools-workspace-and-chat-ui`, commits are small (feat/fix/docs).
Tests: **167 passed** (`pytest tests/ --ignore=tests/test_asr_regolo_live.py`),
smoke test passed (endpoints + path traversal → 400/404 + statics).

### What was added
- **Store on the screen** (`/screen → Застосунки → Магазин (Apps → Store)`): tabs Apps /
  Skins (local packages `store/packages/<id>/package.json`, installation =
  copy to `store/installed/`, state = file system) + Скіли / Тулзи (Skills / Tools)
  (display and installation via the existing OpenClaw `/api/store`). Installed
  apps appear as tiles in the drawer and open in the iframe
  `/store-apps/<id>/`. Sample packages: Metronome, Pixel Workshop, skins
  AMOLED / Sunset / Terminal (skins apply instantly, `botSkin` is passed into the
  iframe via postMessage). Code: `screen_store.py` + UI in
  `static/screen/screen.js`.
- **Now Playing** (bar at the bottom of the screen): source icon (tap — change
  YouTube/Radio), title scrolls if long, progress, full player
  with seek/queue/stations; ducking during bot's speech. Audio —
  `/api/music/stream` (proxy with Range → real seek): Invidious
  `latest_version?local=true` (googlevideo gate returns 403 on direct links) with
  auto-discovery of instances (api.invidious.io, 24h cache), parallel
  probes and retries; radio — SomaFM/Radio Paradise (no Range, browser
  UA — otherwise icecast cuts off). Transcribe — youtube-transcript-api +
  fallback to Invidious captions (VTT).
- **YouTube app in the store**: search on the screen (keyboard in browser /
  voice via bot), tap on video → `POST /api/music/play` → SSE → plays in
  Now Playing (music lives after closing the app). Tools `play_music`/
  `listen_to_video` send the same event; autoplay block after tap in iframe
  is removed by a retry on the first screen touch.
- **Brain tools**: `play_music`, `stop_music`, `listen_to_video` (SSE event
  `music` controls the screen); `open_screen` now knows the `store`.
- **Docs for developers**: `Virtual Bot/docs/SCREEN-PLATFORM.md` (package
  formats, limitations, API) and `docs/YOUTUBE-CLIENTS.md` (choice of stack
  yt-dlp + Invidious + youtube-transcript-api; youtubei.js — option for
  React dashboard). README updated.

### Known limits / what's next
- Public Invidious instances flap for minutes (502↔206): in a "bad"
  minute stream is 502 → tap again. Stable solution for a real
  bot — own Invidious in docker, listed FIRST in
  `config.yaml → music.invidious_instances` (`http://` is allowed).
- Store package icons — only from the existing screen sets (custom SVGs in
  the drawer don't load yet).
- The "skill" package in the screen store installs via openclaw CLI — if
  OpenClaw is unavailable, the tab shows an honest error.
- Smoke test via Fable reviewer (section 5.3) was NOT run on the NEW code.

## 8. Session 2026-09-02: Agent Talk and Watch

- **Agent Talk:** a new conversation is now isolated by a version counter; late
  restoration of an old session or an old stream cannot return text after
  pressing "+". Switching Chat/Code also clears the visible stream.
- **Watch (`/console`):** the initial `/api/trace` and `/api/console` accept
  `session_id`, so Watch shows only the active dialog. An empty new session
  does not pull the old global history; changing the session in Agent Talk synchronizes
  the open Watch window via `storage`.
- **Mobile client:** fixed sending `kind=chat|code` for the list,
  opening and deleting sessions; async responses of an old dialog are ignored.
- Checks: `PYTHONPATH=. .venv/bin/pytest -q` → **214 passed, 1 skipped**;
  `npm run build` in `Virtual Bot/chat-panel`, `npm run typecheck`
  in `claude-bot-app` and `node --check` for Watch — clean.

## 9. Session 2026-09-03: conversation participants (in progress)

- In the branch `feat/bot-tools-workspace-and-chat-ui` with HEAD `070ef2b` there is
  an uncommitted feature of explicit Agent Talk participants: `participants[]`, `events[]`,
  `participant_name` in chat, presence string and system events in the dashboard.
- The base snapshot of this work is verified: `.venv/bin/pytest -q` → **218 passed,
  1 skipped**; `npm run build` in `Virtual Bot/chat-panel` — clean, assets
  are in sync with the source.
- Agreed leave-contract: `POST /api/sessions/{id}/participants/leave`
  with body `{"name":"…"}` returns `{"left":true|false}` and HTTP 200;
  a successful leave removes the name from active `participants` and adds the event
  `participant_left` to `events`. Repeated leave is idempotent.
- Before commit still needed: sanitization of frame/control characters in the name and
  cutting off the bot's name, correct cleanup of empty sessions without bypassing `_prune`,
  UI fixes for the hotkey and system bubbles, full tests and
  manual smoke test.
- Participants currently intentionally belong to the "Chat" mode; code mode does not accept
  `participant_name`. The documentation must remain in sync after the final
  feature commit.

## 10. Session 2026-09-04: virtual device settings

- Committed an isolated layer `56b3536` (`feat(screen): add virtual device
  settings package`) with no changes in the occupied `main.py`, `screen.js`, `screen.css`,
  `i18n.js`, ASR or YouTube files.
- `Virtual Bot/system_status.py` provides a router with `/api/system/status`,
  `/api/system/audio/devices` and `/api/system/network`. The `virtual` snapshot has
  the bot's name/battery, Wi‑Fi, Bluetooth/headphones, mic/speaker and volume
  routes for the bot, YouTube, alarm and notifications.
- `Virtual Bot/store/packages/device-settings/` — a ready 320×240 store app:
  tabs «Звʼязок» (Connection)/«Звук» (Sound), Wi‑Fi/Bluetooth cards, audio selection,
  per-app sliders and mute. Volume values are saved locally and
  passed to the future native mixer via `postMessage`.
- Isolated part checks: `17 passed`, Python compile and JS syntax
  are clean. Full integration requires adding the `system_status` import
  and one line `app.include_router(system_status.router)` in `main.py`;
  this is done by the owner of the shared area after their edits are finished.
- 2026-09-04 a visual pass was done after real headless render
  320×240: `device-settings` translated from the blue dashboard palette into
  Claude Bot tokens (`#16181a`, `#1e2124`, copper `#d17a58`, olive `#8ca879`),
  header/tabs tightened, title/subtitle overlap fixed and touch-cards left
  scrollable for the small screen.
- 2026-09-04 commits `2c242b2` and `931ba43` fixed gestures and theme:
  carousel and layers use Pointer Capture, the iframe app has
  its own swipe-bridge, and `light` is passed along with skin variables. The white
  palette of `device-settings` was verified by headless render; the bridge only accepts
  messages from the parent `/screen` with the same origin, and
  vertical list scrolling does not close the app accidentally.
- 2026-09-04 commit `070b096` swipe zone extended to the whole scene and store app:
  cards and buttons can start a horizontal/vertical swipe, but
  taps on buttons are not lost; input/select/textarea, sliders and scrolling
  remain interactive. Pointer Capture is not removed for buttons, and
  gesture completion is picked up via `window`.
- After fix: `pytest -q tests/test_screen_store.py tests/test_system_status.py`
  → **17 passed**; `node --check` for both JS paths is clean; live smoke
  on `8100`: `/screen`, app statics, `/api/system/status` → 200,
  attempt `static/../main.py` → 404.

## 11. Session 2026-09-20: chat workspace and quick launchers

- Fixed the radial plus menu: repeat click/tap closes it, keyboard activation
  retains focus, and cancelled pointer capture cannot leave a stuck gesture.
- Chat sidebars now extend to the window bottom. The conversation alone clears
  the compact bottom dock; left/right docks have a continuous navigation rail.
- Desktop chat has a bottom-right pin picker for Projects, Vision, and the real
  `/screen` iframe. Choice and order persist in `claudeBotChatPins`. Vision is
  opt-in; removing a pin unmounts its iframe/stream. Pins are desktop-only.
- Legacy standalone images separated by prose now share the existing React Bits
  accordion. Prose/captions remain. Inline images, links, code and tables are not
  regrouped. Dashboard production assets were rebuilt.
- Added `Launch Bot.command` (macOS), `Launch Bot.cmd` (Windows), and
  `launch-bot.sh` (Linux). Native pickers select Dashboard, Screen, OpenClaw,
  Vision, Display backend, or Dashboard + OpenClaw. No Electron or auto-installs;
  module environments must already exist. See `launcher/README.md`.
- Launchers reuse healthy services and refuse occupied ports; failed new launches
  clean up their own process trees. They never reset OpenClaw config or copy keys.
- Independent adversarial review covered radial input, layout/pins/gallery, and
  launcher process handling. Fable was unavailable, so an available reviewer
  agent was used. Native Windows/Linux execution remains unverified.
- Validation: dashboard unit tests, typecheck, build and browser regression
  (mouse/keyboard menu, gallery, persisted pins, all dock sides, mobile width).
  Python suite: 409 passed, excluding the opt-in external Regolo ASR live test.
  Frontend: 5 unit tests passed. The macOS native picker compiled successfully;
  `Launch Bot.command --start pair` reused the live dashboard and OpenClaw.
  HTTP smoke: dashboard/screen/OpenClaw health 200; memory path traversal 400 in
  an isolated loopback server with lifespan disabled. That test server and the
  test browser were stopped; requested production services remain running.

### Native launcher window follow-up

- Replaced the macOS no-argument launcher entry with a persistent AppKit window:
  six service buttons, asynchronous launch feedback, visible errors and Logs.
- The direct `launcher/build/Claude Bot Launcher.app` opens without Terminal.
  `Launch Bot.command` builds/opens it; existing CLI arguments remain unchanged.
  Windows/Linux pickers are unchanged. The native binary is local build output,
  not a standalone distribution of the repository or its Python environments.
- Shared Ukrainian/English locale keys drive the GUI. Minimal native typography
  and flat warm surfaces follow the minimalist-ui direction, with no animation.
- Independent review fixed deployment-target/cache invalidation and screenshot
  false positives. Build explicitly targets macOS 11.0 on the build host's arch.
- Validation: 415 Python tests passed with opt-in native GUI checks enabled,
  excluding the external Regolo ASR live test. GUI tests exercise both locales,
  real button-to-helper success/error paths, busy guards, and screenshot failures.
  Native screenshots were inspected; isolated HTTP smoke returned 200 for app
  and assets, 400 for memory traversal. The smoke server was shut down.

### Launcher/auth repair follow-up

- Fixed a GUI-only hang where the helper waited forever after opening a browser:
  URL opens are now detached with standard streams redirected away from the GUI
  pipe. The actual `.app --smoke-test --test-action pair` now exits successfully
  after starting the web backend.
- Clerk JWKS retrieval now uses an existing `httpx` client with
  `trust_env=False`, avoiding stale desktop proxy failures. Launcher-created
  service environments drop unreachable loopback proxy variables while retaining
  reachable or remote proxies for external API traffic.
- Restarted only the launcher-owned web backend; OpenClaw was left running.
  Live checks: dashboard `200`, OpenClaw health `200`, direct Clerk JWKS fetch
  returned one key. Python suite: **414 passed, 6 skipped** (external live ASR
  test excluded). Targeted auth/launcher tests: 29 passed.

### OpenClaw routing migration follow-up

- Removed the unstable Omni route from the active OpenClaw config. The gateway
  now prefers direct `opencode-go/kimi-k3`, then the free NVIDIA NIM
  `nvidia/openai/gpt-oss-20b` endpoint, and finally the custom
  OpenAI-compatible `regolo/gpt-oss-120b` provider. NVIDIA auth is stored in
  the user's ignored OpenClaw auth store; no key is stored in the repository.
  `regolo/qwen3.5-122b` remains the authored image model. Existing
  OpenCode/Omni config backups remain under the user's ignored `~/.openclaw`
  directory.
- The app backend now routes both text and image turns through OpenClaw. Vision
  sends an explicit `x-openclaw-model` for the configured image model; no
  `20128` Omni request is made.
- Gateway lifecycle `phase=model` events are now captured for each streamed
  turn, so the topbar reports the effective provider/model after fallback
  (for example `nvidia/openai/gpt-oss-20b · OpenClaw`) instead of the primary
  model that failed before the fallback ran. The composer remains the model
  choice for the next request and is intentionally separate from last-run
  telemetry.
- Virtual Bot chat requests now derive a stable, non-identifying
  `virtual-bot-v2:<hash>` Gateway session key from the user and chat id.
  Previously every streamed turn used a random key, which prevented OpenClaw
  from keeping one cache lineage and created unnecessary short-lived sessions.
  Stable-key requests no longer resend the application history: OpenClaw owns
  that transcript, preventing the duplicate `[Chat messages since your last
  reply]` block visible in the Control UI. The v2 namespace isolates new turns
  from sessions created by the old duplicate-history behavior.
- OpenClaw was updated from 2026.9.1 to 2026.9.5. OpenCode Go now reaches its
  provider, which returns HTTP 403 because this account has no active Go
  subscription; OpenClaw correctly falls back to Regolo. With an active Go
  subscription, the same primary route will be used without config changes.
- Live verification: `openclaw agent` through the gateway completed via the
  NVIDIA fallback in about 5 seconds for the full agent cycle. Direct NIM
  probes measured `openai/gpt-oss-20b` at roughly 0.37–0.86 seconds,
  `nemotron-3-super-120b-a12b` at 0.50–4.88 seconds, and
  `nemotron-3-ultra-550b-a55b` at about 1.03 seconds. Several older catalog
  IDs returned 404/410 and were not selected. Direct Regolo text and Qwen
  vision endpoint probes returned 200. Gateway, dashboard, and OpenClaw
  remain loopback-only. The Omni shim is no longer required for chat.

### Dashboard loading and bot identity follow-up (2026-09-21)

- The dashboard header and assistant messages now share the static pixel-crab
  mark from the device face; locale keys keep the wordmark translatable.
- Fixed a race in the dashboard event bus: simultaneous widget mounts could
  each open an SSE stream while the Clerk token was loading. Browsers cap
  HTTP/1.1 SSE connections per origin at six, so the leaked streams could
  leave sessions and model queries in a permanent skeleton state. Opening is
  now single-flight, and a regression test covers sharing and cleanup.
- Validation: dashboard build, typecheck, 12 unit tests, browser regression,
  and live loopback checks for `/dash/`, referenced bundles, and `/api/status`
  passed. Existing tabs with old streams should be hard-refreshed once after
  the deployment so the service worker picks up the new bundle.
- The macOS/local launcher now starts the loopback dashboard with
  `CLERK_DISABLED=1` by default (an explicit environment value still wins),
  because Clerk is an unnecessary second login for a single-user local bot.
  OpenClaw remains separately token-protected and loopback-only.
- The chat model catalog is browser-cacheable for 30 seconds, the `+` menu now
  uploads real attachments through `/api/chat/upload`, and gateway model events
  are rendered as the first OpenClaw status line while a response streams.
- `workspace_show` now opens a temporary right-side dock over the chat instead
  of navigating away. Text and Markdown files reuse CodeMirror for inline edits
  and save back to workspace; images and HTML render as previews. Closing the
  dock leaves the conversation untouched.
- Chat tables now establish a real minimum width and scroll inside the message;
  the scroll-to-current control sits above the composer as a labelled pill.
  Conversations are grouped into Today / This week / This month / Earlier,
  with day-relative timestamps for the last week. Global `ask_question` and
  `show_choice` UI events now render an actionable overlay that sends the
  selected or custom answer back through the active chat runtime.
- Internal `[емоція:…]` markers are stripped from streamed `done` frames and
  loaded assistant history; the crab still receives the emotion separately, so
  the marker cannot leak into visible chat text.
- Dashboard startup no longer blocks the composer on the slow OpenClaw model
  catalog CLI: it shows a local fallback immediately, caches the last catalog
  in browser storage, and defers the SSE connection briefly so critical queries
  win the browser connection pool. UI question events are scoped to the Clerk
  user when auth is enabled, and selecting an answer cancels the originating
  tool turn before sending the new message.

### Touch and mobile dashboard follow-up (2026-09-22)

- The dashboard now keeps the existing desktop components and switches to a
  touch-first shell below 760px: bottom navigation is fixed to the safe area,
  dock controls have 44px hit targets, and dock relocation stays a desktop
  gesture so one-finger taps do not move the navigation.
- Chat sessions and pinned Projects/Vision/Mini-screen panels are available in
  bottom sheets on phones and tablets; the same `PinnedPanels` component is
  reused instead of maintaining a second mobile implementation. Composer,
  menus, session rows, image controls and bot-question actions grow their hit
  targets only for coarse pointers.
- Horizontal section swipes work on touch/coarse pointers with a 48px threshold,
  axis lock, browser edge guard and exclusions for controls, editors, galleries,
  tables, session swipes and FolderFloat gestures. Image viewing also supports
  left/right swipes when not zoomed.
- Mobile overlays reserve space above the composer and bottom dock; markdown
  tables keep an inner horizontal scroll surface and no page-level horizontal
  overflow was observed at 320, 390, 768, 1024 and 1180px widths.
- Validation: dashboard tests 18 passed, typecheck and production build passed;
  browser smoke verified 390px pins sheet, 320/390/768/1024/1180px overflow and
  a real touch swipe from chat to memory. Generated `static/dash` assets were
  rebuilt after each UI change.
- Final touch review also covers the 320px toolbar shrink case, 44px pin
  actions/footer controls, and keyboard focus containment in Command Palette;
  the independent reviewer’s initial P1 findings were fixed and rechecked.
- Command Palette now captures the element focused before opening, traps Tab
  inside the dialog, and restores that element after Escape (verified with the
  chat composer focused first).
