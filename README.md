# Blink

[Website](https://waveio.me) · [Repository](https://github.com/Imlokzu/claude-bot)

A DIY personal AI companion with a virtual crab mascot, a web workspace, and
Android, iOS, and macOS clients. Start with the software on your computer;
the physical Raspberry Pi 3 bot is being developed separately.

The host brings together conversation, voice, vision, persistent memory,
tools, and an animated device screen. OpenClaw connects the host to configured
AI providers. An optional demo mode supports basic conversations without a
working gateway. Hardware, microphones, and external integrations have their
own setup requirements.

> Documentation and code comments must be written in **English**. User-visible
> text belongs in locale files and is referenced by translation keys, with
> Ukrainian and English translations (see [`AGENTS.md`](AGENTS.md) and the
> [localization guide](docs/I18N.md)).

**Key docs:**

- [`blink-full-spec-v3.md`](blink-full-spec-v3.md) — full architecture (Edge/Fog/Cloud), BOM, roadmap
- [`blink-dev-order.md`](blink-dev-order.md) — development order (6 steps)
- [`HANDOFF.md`](HANDOFF.md) — session handoff, current state, known bugs, next tasks
- [`AGENTS.md`](AGENTS.md) — rules for AI agents/tools working in this repo
- [`DESIGN.md`](DESIGN.md) — current product and screen design decisions
- [`STATUS.md`](STATUS.md) — historical status summary with dated updates
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — multi-agent workflow, review, and validation evidence
- [`LICENSE`](LICENSE) — GNU GPL v3

## What is implemented

- **Web workspace:** a React dashboard for chat, files, memory, tools,
  model selection, and device settings, served by the Virtual Bot host.
- **Living screen:** a top-down office where the crab works, reads, rests,
  and reacts to actual bot activity. The classic face remains available.
  The screen includes voice interaction, apps, skins, timers, and alarms.
- **Memory and emotions:** persistent Markdown notes, retrieval with
  Ukrainian word-form matching, background dream-cycle consolidation,
  reply emotions, and ambient moods weighted by local time of day.
- **Companion clients:** shared Kotlin Multiplatform/Compose chat and file
  editors on Android, iOS, and Apple Silicon macOS. Devices pair with the
  existing host and receive individually revocable credentials.
- **Vision and voice:** a separate face/motion detection service, camera
  streaming, host speech recognition and synthesis, and the standalone
  OpenClaw voice loop.

This is an actively developed prototype. Android and iOS simulator validation
are documented; physical iPhone testing and signed store distribution remain
pending. Remote APNs/FCM push is not configured, and iOS has no background
outbox worker. The macOS build is unsigned and not notarized. See the
[mobile](mobile-app/README.md) and [macOS](mobile-app/desktopApp/README.md)
guides for current platform limits.

---

## Architecture (three tiers)

| Tier | Target | Role |
|---|---|---|
| **Edge** (device) | Raspberry Pi 3 | Physical display, audio, sensors, and planned local reflexes |
| **Fog** (host) | Computer / home server | FastAPI host, orchestration, tools, scheduler, memory, and client APIs |
| **Cloud** (models) | Configured model providers | Conversation, reasoning, vision, and optional speech services |

The virtual screen runs in a browser against the same host. Physical mobility
and autonomous navigation remain roadmap work; the architecture table describes
the intended hardware split, not measured latency or a completed robot.

---

## Modules

| Folder | What it does | Stack | Port |
|---|---|---|---|
| [`Vision Agent/`](Vision%20Agent/) | Eyes: face + motion detection over HTTP | FastAPI + OpenCV | **8000** |
| [`Voice Loop/`](Voice%20Loop/) | Ears/mouth: Whisper STT → OpenClaw → pyttsx3 TTS | Python | — |
| [`OpenClaw Vision Plugin/`](OpenClaw%20Vision%20Plugin/) | `vision_check_camera` tool for the agent | TypeScript | — |
| [`blink-display/`](blink-display/) | Face: pixel eyes, 15+ emotions, 4 screens | FastAPI + React/Vite | **8001** (WS) |
| [`Remote Control/`](Remote%20Control/) | USB remote (VID:PID `0627:697d`) + I2C LCD status | Python (Pi) | — |
| [`Device Setup Wizard/`](Device%20Setup%20Wizard/) | "Blink Studio" — setup UI | Electron + Vite/React/TS | — |
| [`Virtual Bot/`](Virtual%20Bot/README.md) | Main host, tools, memory, voice, screen, and client APIs | FastAPI + Python + browser JS | **8100** |
| [`Virtual Bot/dashboard/`](Virtual%20Bot/dashboard/) | Current web workspace; production build served by the host | React + TypeScript + Vite | **5180** (dev) |
| [`mobile-app/`](mobile-app/README.md) | Current Android/iOS companion clients | Kotlin Multiplatform + Compose; SwiftUI iOS host | Uses host API |
| [`mobile-app/desktopApp/`](mobile-app/desktopApp/README.md) | Current Apple Silicon macOS client | Compose Desktop + native integrations | Uses host API |
| [`launcher/`](launcher/README.md) | Selectively start local services and open their interfaces | Go + native macOS picker | — |
| [`hardware/soma-bot/`](hardware/soma-bot/README.md) | Current Clawd-shaped physical prototype model | CadQuery + SOMA | — |

[`blink-app/`](blink-app/README.md) is the earlier Expo/Electron client scaffold.
[`hardware/enclosure/`](hardware/enclosure/README.md) contains the earlier
OpenSCAD enclosure design. Use the current client guides above when starting
new work.

**Brain / gateway:** conversation routes through OpenClaw, by default at
`127.0.0.1:18789`. Configure models and provider fallbacks in OpenClaw; host
settings live in `Virtual Bot/config.yaml`. The host reads a local gateway's
token from OpenClaw's own configuration; `OPENCLAW_TOKEN` can supply a remote
gateway token. Omni, Anthropic-compatible, and Chat2API adapters remain as
legacy helpers.

Gateway failures produce an explicit offline response by default. For a local
demonstration, enable `chat.demo_fallback` in `Virtual Bot/config.yaml`.
Mobile turns still require a working gateway.

---

## How to run each module

> The project path contains a space — always quote paths (`cd "Virtual Bot"`).

Before running the server, audio, or UI tests on macOS, mute the output and
leave it muted, as required by [`AGENTS.md`](AGENTS.md):

```bash
osascript -e 'set volume output muted true'
```

### Virtual Bot (recommended starting point) — port 8100

Install Python 3.10 or newer; `start.sh` selects an available compatible
interpreter, creates `.venv`, and installs the Python requirements.

```bash
cd "Virtual Bot"
./start.sh
```

Open the interface you need:

| Interface | Local URL |
|---|---|
| Current dashboard | <http://127.0.0.1:8100/static/dash/> |
| Bot screen and living office | <http://127.0.0.1:8100/screen/> |
| Original control panel | <http://127.0.0.1:8100/> |
| API documentation | <http://127.0.0.1:8100/docs> |

For local development without Clerk sign-in, run `CLERK_DISABLED=1 ./start.sh`
instead. This disables API authentication; keep that process bound to loopback
and do not expose it through a public tunnel. Normal authenticated setup uses
Clerk; see the [host environment template](Virtual%20Bot/.env.example).

Manual startup from `Virtual Bot/`:

```bash
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
./.venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8100 \
  --timeout-graceful-shutdown 3
```

### Rebuild or develop the web dashboard

The repository includes built dashboard assets. After editing its React
sources, rebuild them from the repository root with pnpm and a Node.js version
supported by the dashboard's Vite dependency:

```bash
cd "Virtual Bot/dashboard"
pnpm install --frozen-lockfile
pnpm run typecheck
pnpm test
pnpm run build
```

The build publishes to `Virtual Bot/static/dash/`. For development, keep the
backend running and use `pnpm run dev`; Vite serves the dashboard at
`http://localhost:5180/static/dash/` and proxies backend routes to `:8100`.

### Companion apps and launcher

- [Android and iOS](mobile-app/README.md): build instructions, host pairing,
  file editors, offline queue behavior, and platform limits.
- [macOS](mobile-app/desktopApp/README.md): Apple Silicon build, Keychain
  credentials, native previews, and packaging requirements.
- [Quick launcher](launcher/README.md): `Launch Bot.command` on macOS,
  `Launch Bot.cmd` on Windows, or `./launch-bot.sh` on Linux. Prepare each
  module's environment first; the launcher does not install dependencies.

### Vision Agent — port 8000
```bash
cd "Vision Agent"
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
./.venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8000
# health: curl http://127.0.0.1:8000/health
# In another terminal: ./.venv/bin/python test_webcam.py (quit with q)
```

### Voice Loop
```bash
cd "Voice Loop"
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
# Requires OpenClaw on :18789 with chatCompletions enabled and OPENCLAW_TOKEN set.
./.venv/bin/python voice_loop.py  # speak after the prompt; Ctrl+C to quit
```

See the [Voice Loop guide](Voice%20Loop/README.md) for gateway and OS audio setup.

### blink-display — port 8001
```bash
cd blink-display
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt && pip install -e .
cd frontend && npm install && cd ..

# Terminal 1 (backend):
python -m backend.server --port 8001
# Terminal 2 (frontend dev server):
cd frontend && npm run dev
```

### OpenClaw Vision Plugin
```bash
cd "OpenClaw Vision Plugin"
npm install
npm run plugin:build      # compile TS → dist/, generate manifest
npm run plugin:validate   # validate manifest with OpenClaw CLI
npm test
openclaw plugins install .   # install into OpenClaw, then restart the gateway
```
Requires **Vision Agent** running on `:8000`.

### Device Setup Wizard (Electron app)
```bash
cd "Device Setup Wizard"
npm install
npm run dev            # Vite dev server
npm run electron:dev   # run the Electron app in dev
npm run electron:build # build a distributable (dmg/nsis/AppImage)
```

### Remote Control (runs on the Raspberry Pi)
```bash
cd "Remote Control"
python3 -m venv .venv
./.venv/bin/pip install evdev
./.venv/bin/python remote_listener.py  # user must be in the `input` group
```

---

## Secrets

**Never commit tokens or API keys.** Secrets come from environment variables or
a local, git-ignored `.env` file (see `.gitignore`):

- `OPENCLAW_TOKEN` — gateway token for the standalone Voice Loop or a remote host gateway
- `REGOLO_ASR_API_KEY` — optional host speech recognition provider key
- `ELEVENLABS_API_KEY` — optional speech synthesis provider key

[`Virtual Bot/.env.example`](Virtual%20Bot/.env.example) lists the supported
environment variables without values. Only configure integrations you use.
Agent workflows must use the agents' vault described in [`AGENTS.md`](AGENTS.md).

---

## Validation

From the repository root, after muting macOS output:

```bash
cd "Virtual Bot"
PYTHONPATH="$PWD" .venv/bin/pytest tests/ -q
```

For dashboard changes, run the typecheck, tests, and build shown above.
For mobile and hardware changes, follow their module-specific checks. Service
changes also need a live smoke test: check the affected APIs and static assets,
verify rejected file traversal returns `400`, then stop the test processes.

## Roadmap and current boundaries

- **Software companion:** implemented and evolving: chat, tools, memory,
  dream cycle, emotions, web workspace, living screen, and companion clients.
- **Physical desk bot:** CAD models and component planning are in
  [`hardware/soma-bot/`](hardware/soma-bot/README.md); dimensions and real
  assembly still need hardware validation.
- **Mobility and spatial awareness:** chassis integration, reactive navigation,
  recognition of specific people, and physical `find_person` remain planned.
- **Client releases:** physical-device validation, signing/distribution,
  remote push, and platform background-delivery work remain open.

The [full specification](blink-full-spec-v3.md) preserves the original phase
plan. Use the dated entries in [`HANDOFF.md`](HANDOFF.md) and the linked
module guides for implementation and validation details; older handoff and
status sections describe earlier snapshots.

---

## Contributing / AI agents

This repo is worked on by AI coding tools. **All contributors (human or AI) must
follow [`AGENTS.md`](AGENTS.md)**: commit after every logical change, use
Conventional Commits, run the relevant tests/builds, and never commit secrets.
