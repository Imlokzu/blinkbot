# "Blink" — Virtual Bot

Virtual embodiment of Blink + web dashboard. Until the real hardware
(Raspberry Pi 3, camera, display) is purchased, the entire bot lives locally on macOS:
FastAPI backend at **http://127.0.0.1:8100**, frontend — static vanilla
JS/HTML/CSS in `static/` (without build and without CDN), served by the backend itself.

## Launch

```bash
cd "Virtual Bot"
./start.sh
```

The script will create a `.venv` itself, install dependencies (`fastapi`, `uvicorn`,
`httpx`, `pyyaml`, `websockets`) and start the server. Then open
http://127.0.0.1:8100.

Manual option:

```bash
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
./.venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8100
```

## Frontend dashboards

The frontend (`static/`) is a dashboard with tabs:

- **«Обличчя»** (Face) — animated face of the bot with the current emotion;
- **«Чат»** (Chat) — conversation with the bot (each reply has an emotion);
- **«Зір»** (Vision) — snapshot and live MJPEG stream from the camera (the stream is taken directly from
  `http://127.0.0.1:8000/vision/stream.mjpg`, not via 8100);
- **«Памʼять»** (Memory) — viewing and editing markdown notes in `brain/`;
- **«Сервіси»** (Services) — starting/stopping Vision Agent (port 8000) and Display (port 8001);
- **«Статус»** (Status) — availability of brains and services, active mode.

## Brain modes (by priority)

0. **Omni-router** — MAIN brain: OpenAI-compatible multi-model gateway at
   `127.0.0.1:20128/v1` (`POST /chat/completions`). The model is selected in the dashboard
   («Модель» (Model) in the chat header) from a hardcoded list in `config.yaml` (`omni` section);
   default — Claude (`claude/claude-sonnet-5`). If the selected model
   fails with a fast error (401/404/503), Omni automatically tries the fallback model
   (`omni.fallback_model`, the "second brain" — `opencode-go/…`), and only then falls back
   to the next brain. Key: env `OMNI_API_KEY` — only from the `.env` file (see
   "Secrets" below). The key is a secret: it never appears in API responses,
   static files, this README or wiki.

   > The list of models contains direct provider ids that are configured in Omni. Regolo
   > is added exactly in the Omni configuration: Virtual Bot does not have a separate Regolo LLM
   > client and passes only the selected model id to the router. We use direct `claude/…`,
   > `opencode-go/…` or confirmed `regolo/…` ids, not `auto/*` (that one
   > combo-routes and can hit dead providers).
   > The chat has a short timeout (`chat.omni_timeout_s`) and a safeguard
   > (`chat.omni_backoff_s`), so a hung router does not hold every reply.
1. **OpenClaw** — gateway at `127.0.0.1:18789` (OpenAI-compatible
   `/v1/chat/completions`). Token: env `OPENCLAW_TOKEN` (priority), otherwise
   read from `../Voice Loop/config.yaml`. The token is a secret.
2. **Anthropic API** — direct call `POST /v1/messages` via httpx (without SDK).
   Base URL is configurable (`anthropic.base_url`): currently it's **SotaModel**
   (www.sotamodel.net) — a third-party Anthropic-compatible Claude gateway with models
   `claude-opus-5` / `claude-opus-5-max` / `claude-opus-5-xhigh`. Key: env
   `ANTHROPIC_API_KEY` (this is the gateway's key, NOT official Anthropic). While the
   SotaModel account has zero balance, every request returns INSUFFICIENT_BALANCE
   and the chain simply proceeds further; after top-up the brain comes alive without code changes.
3. **Chat2API** — local OpenAI-compatible server at `127.0.0.1:8080/v1`
   (`POST /chat/completions`), default model `Qwen3.7-Max`
   (`config.yaml`, `chat2api` section). Authorization is usually not needed;
   if env `CHAT2API_API_KEY` is set — `Authorization: Bearer` is sent.
4. **Demo** — prepared Ukrainian responses by keywords, so that
   the app always works, even without networks and keys.

The active mode is visible in `GET /api/status` (`mode` field). After the first chat
`mode` shows the brain that ACTUALLY answered last (ping can "lie":
the gateway is available, but chatCompletions returns 500); before the first chat — the expected
mode by availability. All five brains receive the same system prompt
(emotion tag + top-3 memory notes) — it is model-agnostic.

### Omni model selection

In the header of the "Chat" dashboard there is a dropdown list of models. The list is hardcoded in
`config.yaml` (`omni.models` section: `id` — as returned by the router `/v1/models`,
`label` — caption in the UI). `GET /api/models` returns the list + current selection,
`POST /api/model {"model": "<id>"}` switches the model (ONLY ids from
the list are accepted — arbitrary strings are rejected with `400`). The selection operates at the process level
(shared for all clients; resets to default on server restart).

### Voice

- **ASR:** `/api/asr` passes browser audio to Regolo `faster-whisper-large-v3`.
  Requires local secret `REGOLO_ASR_API_KEY`; the route requires the same
  Clerk login as chat, and accepts no more than 10 MiB of audio per request. If Regolo
  is unavailable, the backend returns `503`, and the dashboard uses its browser
  fallback mode. There is no server fallback during this testing period.
- **Live intermediate results:** `/api/asr/partial` — draft text, while
  the human is STILL speaking. The screen records audio in 1.2s chunks and sends the ACCUMULATED
  data (the first chunk carries webm headers, so a separate chunk cannot be decoded),
  and shows what managed to be recognized. The model is separate and smaller
  (`asr.partial_model`, typically `small`): speed is important here, and accuracy
  will be caught up by the final `/api/asr`. Can be disabled with `asr.partials: false`.
- **Local ASR speed** (measured on M2 Pro, 1.1s phrase):
  was 5.7s → became 2.5s final and 0.7s intermediate. Three things gave this:
  `cpu_threads` by the number of cores, `float32` instead of `int8` on arm64
  (ctranslate2 doesn't have fast int8 kernels there) and model warmup on start
  (the `warm()` function existed, but no one called it, so the FIRST
  recognition paid another ~5s for loading). For comparison: cloud
  Regolo answers in ~0.65s — `asr.provider: regolo`.
- **Complex names:** `asr.hotwords` — a list of terms ("Клод Код" (Claude Code), "Пайпер" (Piper),
  AI model names) that goes to the model as preceding context. Without it, Whisper
  guessed at random: "клод-код", "Piper", "коміт-угід". Terms ONLY in Cyrillic:
  measured that Latin in prompts pulls adjacent Ukrainian words with it
  ("про це" → "Proceek"). Locally this is `hotwords=`, in Regolo — the same string
  as `prompt`.
- **Canonical names:** `asr_terms.py` + `asr.aliases` reduce pronunciations to a single
  spelling AFTER recognition ("джемінай" and "геміні" → `Gemini`, "кван" →
  `Qwen`). Needed because model names in the config are in Latin, and short names
  are not pulled by hints. This is a mirror to `voice_latin.py`, which does the
  reverse before synthesis; canon pronunciation is also defined there.
- **Two languages:** `asr.languages: [uk, en]`. One language = hard fixation.
  Multiple = we determine the language before recognition, but ONLY among the listed ones,
  so "heard Polish" is impossible by design. Determined by a SMALL model
  (the same one as drafts): 0.48s at 0.98 confidence, whereas
  large-v3-turbo gives the same answer in 2.98s, and `language=None` costs
  +2.8s. The price of two languages is +0.5s per phrase (2.65s → 3.17s); drafts remain
  in the first language.
- **TTS:** `/api/tts` synthesizes Ukrainian text-to-speech with local Piper. This is a shared
  voice for the virtual and future physical bot.

### Images and long replies on the screen

The bot searches for images with the `image_search` tool and inserts them into the reply as
`![caption](url)`. The «Розмова» (Conversation) tile rendered this markdown before as well (smd),
but the clock face and «Бот сказав» (Bot said) showed RAW text — meaning instead of a photo
the person saw brackets with a link. Now both parse the reply:

- the caption remains in the text instead of the markup (the sentence reads further);
- on the clock face, photo mode turns on (`.tile-face.photo`): **one frame taking
  almost the entire screen, with one image in it**, and in the bottom right corner sits
  a crab holding it by the right edge with a raised claw (sprite `HOLDER_SPRITE` in
  `screen.js`; the mascot from `crab.js` has its own state machine and the "holding" pose is not there).
  There is one claw on purpose — two symmetrical "hands" would turn the crab into a spider.
  The microphone moves to the left for this time, so as not to fight with the crab for the corner.
  Everything else disappears for this time:
  the full-height mascot, the emotion caption, the subtitle — on 320×240 two centers
  of attention turn the screen into mush;
- multiple images are scrolled by swiping on the frame, arrows on the sides, and are shown
  by dots. The swipe quenches the event bubbling: otherwise the same gesture would also turn
  the tile carousel;
- an image that doesn't fit in the frame remains WHOLE — `object-fit: contain`
  on a light background, i.e. with margins. Cropping a photo to 2.4" mostly
  means making it unrecognizable;
- both external `https://` (as returned by `image_search`) and paths on
  our own server — `/uploads/…`, `/file/…` are accepted, meaning the bot can show both
  found on the network and its own file;
- the markup is not read out loud: already cleaned text goes into Piper.

At the same time, three truncations were removed, because of which a long reply looked chopped off:
the clock face subtitle was cut to the last 140 characters (now the text is full, and
the frame scrolls and is held longer — proportionally to the length), the SSE event
`reply` was cut at 2000 characters (now 16000), and an incoming chat message
longer than 8000 characters bounced back with a 422 (now 32000).

### Streaming the response

`/api/chat` with `stream:true` returns tokens as they come. But **the OpenClaw gateway
does not stream**: measured — on a simple reply it is silent for 7–30s and returns the entire
response in ONE chunk. Therefore, a chunk longer than 40 characters the backend considers
"not streaming" and scatters into words (`_LUMP_CHARS` in `main.py`) — the text
appears before your eyes, not as a wall. A real stream of fine tokens (Omni,
Anthropic) passes untouched.

The brain delay itself is NOT cured by this: 7–30s is OpenClaw's own agentic cycle.
For comparison, the same few words via Omni: ~2.2s
(`regolo/gpt-oss-20b`) and ~2.7s (`opencode/muse-spark-1.2-contributor-free`).

### Emotions

The system prompt asks the model to start the response with the tag `[емоція:happy]`
(one of `idle | listening | thinking | speaking | happy | sad | confused |
surprised | love | sleepy`). The backend parses the tag and removes it from the text; if
there is no tag — the emotion is guessed by heuristics from the keywords of the response.

**The bot changes emotions on its own too.** Background "life supervision" (`vision_watcher`)
publishes emotions via SSE (`/api/events`), so the face comes alive without a request:
- reaction to the camera (when Vision is online): human appearance → `surprised`→`happy`
  + greeting; human left → `sad`; dozing after 10 min of silence → `sleepy`;
- **spontaneous emotions in idle**: when the bot is calm (no interactions
  ≥`mood_calm_s`, not dozing and not sad), it rarely ITSELF shows a random "alive"
  emotion (`happy/thinking/surprised/love/listening`) for a few seconds, then
  returns to `idle`. This works without Vision too. Settings — in `config.yaml`
  (`liveliness.mood_*`); `mood_duration_s: 0` completely disables this behavior.

### Memory

Simple file memory — markdown notes in `brain/{people,topics,logs}/`.
On every chat the top-3 notes by query keyword match are mixed
into the system prompt. Note paths are validated (protection from path traversal).

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/` | Dashboard (serves `static/index.html`) |
| GET | `/api/status` | `{"omni","openclaw","anthropic","chat2api","vision","display","mode"}` |
| POST | `/api/chat` | `{"message"}` → `{"reply","emotion"}` |
| GET | `/api/models` | `{"models":[{"id","label"}],"selected","default"}` — Omni models (images + direct call) |
| GET | `/api/brain/models` | models of OpenClaw ITSELF (`openclaw models list`) + selection and thinking level — what the bot actually answers with in the chat |
| POST | `/api/brain/model` | overrides OpenClaw model (`x-openclaw-model` header) |
| POST | `/api/brain/thinking` | OpenClaw thinking level (`agents.defaults.thinkingDefault`) |
| GET | `/api/chat/context` | what the context of the next request consists of, in characters |
| POST | `/api/sessions/{id}/compact` | compresses the conversation into a summary (original — in `pre-compact/`) |
| POST | `/api/model` | `{"model":"<id>"}` → switch Omni model (only from the list) |
| GET | `/api/vision/snapshot` | Proxy JSON from Vision Agent; `503` if offline |
| GET | `/api/memory/list` | `{"files":[{"path","title"}]}` |
| GET | `/api/memory/file?path=...` | Note content |
| POST | `/api/memory/save` | `{"path","content"}` — save note |
| GET | `/api/services` | Vision/Display statuses |
| POST | `/api/services/{vision\|display}/start` | Start service |
| POST | `/api/services/{vision\|display}/stop` | Stop service (only its own process) |

Service management: uses the `.venv` of the respective module, if present;
repeated `start` does not spawn processes; `stop` never kills a process that
we didn't start. Process logs are in `service_logs/`.

### Separate console (`/console`)

An observation window, kept to the side during a conversation with the bot: you can see not
only what the bot answered, but through whom it went. Opened by the button
«Окремим вікном» (Separate window) in the «Логи» (Logs) tab of the dashboard or directly at the address `/console`.

| Method | Path | Description |
|---|---|---|
| GET | `/console` | Console page (processes + conversation progress + logs) |
| GET | `/api/trace` | `{"turns":[…],"events":[…]}` — history of turns by steps; `?session_id=` limits to active session |
| GET | `/api/console` | Log history; `?session_id=` limits to active session |
| GET | `/api/processes` | Chain state: port/health/pid + current brain and backoffs |

A turn is one user replica. Its steps write `brains.chat`
(which model was tried, how long it took, why it failed) and tool executions;
steps outside a turn (tool from external brain by a separate HTTP request, ASR, TTS)
go into their own ring and are shown in the feed by time. Module —
`trace_log.py`, processes overview — `processes.py`; both intentionally have no
right to break the chat: trace error is swallowed. Watch on opening takes
only the active session from Agent Talk, and an empty new conversation starts without the old
stream of text.

### Device display (`/screen`), store and music

| Method | Path | Description |
|---|---|---|
| GET | `/api/screen-store/catalog` | Screen packages catalog (apps, skins) |
| GET | `/api/screen-store/installed` | Installed packages |
| POST | `/api/screen-store/install` | `{"id"}` — install package |
| POST | `/api/screen-store/uninstall` | `{"id"}` — remove package |
| GET | `/store-apps/<id>/...` | Static files of installed app (iframe) |
| GET | `/api/music/status` | yt-dlp / transcribe availability |
| GET | `/api/music/search?q=` | YouTube search (yt-dlp, no keys) |
| GET | `/api/music/radio` | Live radio stations |
| GET | `/api/music/stream` | Audio with Range — seeking works (Invidious → yt-dlp) |
| GET | `/api/music/transcript` | Video subtitles (youtube-transcript-api, free) |
| POST | `/api/music/play` | `{"id"\|"query", title?…}` — play in Now Playing (for screen apps) |
| POST | `/api/music/stop` | Stop Now Playing |

Brain tools: `play_music`, `stop_music`, `listen_to_video` (transcribe +
audio on the screen). The screen is controlled from the brain with the tool `open_screen` (now also
`store`).

**Developing apps for the screen** — package format, skins, 320×240 limits
and reference: [`docs/SCREEN-PLATFORM.md`](docs/SCREEN-PLATFORM.md). Comparison
of unofficial YouTube clients and transcribe options:
[`docs/YOUTUBE-CLIENTS.md`](docs/YOUTUBE-CLIENTS.md).

## Configuration

`config.yaml` — ports, paths, base URLs and Omni models list, **no secrets**.

### Secrets (`.env`)

Secrets live only in the environment. Most conveniently — in the `.env` file next to `config.yaml`
(format `KEY=VALUE`, quotes allowed, inline comment " #..." is stripped); it
is read by `app_config` at startup — regardless of the launch method (therefore `start.sh`
does NOT source it, only locks permissions `chmod 600`). A real `export KEY=...` in
the environment always has priority over `.env`. Keep `.env` with `600` permissions.

- `OMNI_API_KEY` — Omni-router key (main brain).
- `OPENCLAW_TOKEN` — OpenClaw token (otherwise taken from `../Voice Loop/config.yaml`).
- `ANTHROPIC_API_KEY` — direct Anthropic API key.
- `CHAT2API_API_KEY` — optional local Chat2API key.
- `REGOLO_ASR_API_KEY` — Regolo key only for speech recognition.

**`.env` is a secret:** do not commit it, do not add it to wiki/frontend. Keys
are never returned from the API and do not end up in static files.
