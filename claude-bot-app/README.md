# "Claude Bot" — app

A single React Native interface that works as **a site, an app on iPhone
and Android, and a desktop app for macOS / Windows / Linux**. The backend is the same
FastAPI that already serves the debug dashboard: the app rewrites nothing in it,
it is simply another client.

## How this differs from `Virtual Bot`

`Virtual Bot` is a **stand**: eight sections (memory, vision, services, logs,
browser, settings) for development and diagnostics. It remains as such
further on — it's convenient for seeing what the bot is doing inside.

This app is the **product**: only the conversation and coding mode. No brain tab,
no services management, no console. What is used
every day, and nothing more.

## Architecture

```
packages/core     Platform-agnostic TypeScript: backend client, types, design
                  tokens. No DOM, no React Native, no Node — so the exact
                  same code runs in the browser, in Expo Go and Electron.

apps/app          Expo (React Native + react-native-web).
                  ONE UI → iOS, Android, web.

apps/desktop      Electron: a wrapper around the Expo web build.
                  → macOS, Windows, Linux.
```

Why exactly like this, and not "regular React for the web": so that `expo go` actually provides
the phone, the UI must be on React Native. The web is not lost with this —
`react-native-web` renders the same components into the DOM, and Electron simply
shows this web build. The cost of the solution: web libraries like
Ant Design X or CodeMirror do not work here, so the interface is built on
RN primitives.

## Launch

First the **backend** (it also serves the debug dashboard):

```bash
cd "../Virtual Bot"
./start.sh                 # http://127.0.0.1:8100
```

Next, monorepo dependencies (once):

```bash
npm install
```

### Web (development)

```bash
npm run web                # http://localhost:8081
```

### Web (build)

```bash
npm run build:web          # apps/app/dist — static files
```

### iPhone / Android via Expo Go

```bash
npm start                  # will show QR code
```

Scan the code with the Expo Go app. The app determines the backend address itself —
it takes the computer's IP from the address the bundle was loaded from. If the phone and
the computer are in different networks or a different host is needed, specify explicitly:

```bash
EXPO_PUBLIC_API_URL=http://192.168.0.10:8100 npm start
```

> "localhost" on the phone means the phone itself, not your computer — which is exactly
> why an IP in the network is needed.

### Computer (Electron)

```bash
npm run desktop            # will build web and open window
npm run build:desktop      # dmg / nsis / AppImage
```

## What is needed from the backend

The app accesses these endpoints of the existing backend:

| Endpoint | Purpose |
|---|---|
| `GET /api/models` | list of models, selected one, and who answered last |
| `POST /api/model` | model selection (this is state on the backend, not a request parameter) |
| `POST /api/chat` | message → reply |
| `GET /api/sessions` | list of conversations |
| `GET /api/code/status` | whether to show the "Code" mode |
| `POST /api/code/chat` | task for the coding agent (SSE) |
| `GET /api/auth/config` | whether login is enabled |

### The only necessary change in the backend is CORS

> **Warning:** this snippet is applied in the working tree `Virtual Bot/main.py`,
> but is NOT committed together with the app: that file contains a lot of
> uncommitted work on the backend, and mixing it with this commit would be
> wrong. If you cloned the repository and the app doesn't see the bot —
> add this manually.

In `Virtual Bot/main.py`, next to other imports:

```python
from fastapi.middleware.cors import CORSMiddleware
```

Right after `app = FastAPI(...)`:

```python
_CORS_DEFAULT = [
    "http://localhost:8081", "http://127.0.0.1:8081",    # Metro (expo web)
    "http://localhost:8082", "http://127.0.0.1:8082",    # Electron
    "http://localhost:19006", "http://127.0.0.1:19006",  # historic Expo port
]
_cors_env = (os.environ.get("CORS_ORIGINS") or "").strip()
_cors_origins = [o.strip() for o in _cors_env.split(",") if o.strip()] or _CORS_DEFAULT

app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_credentials=False,   # the token travels in the header, cookies are not needed
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["Authorization", "Content-Type"],
)
```

Why exactly like this. The debug dashboard is served by the
backend itself, so it doesn't need CORS; the app lives on another port, and without
permission the browser blocks every request. The list of origins is limited to local
addresses (`CORS_ORIGINS` in `.env` — if another host is needed), `allow_credentials`
is intentionally disabled: the token travels in the header, cookies are not used.

## Brains and models

The app itself does not choose models — it shows what `/api/models` returns
(the list is hardcoded in `config.yaml`, section `omni.models`).

Models `opencode-go/*` are available **only via the Omni router on
`127.0.0.1:20128`**. If it is not running, the backend falls back along the chain to
the demo mode — and the app honestly writes "demo mode (brain unavailable)"
under the reply, rather than attributing it to the selected model.

## Local mode without login

```bash
CLERK_DISABLED=1 ./start.sh
```

Then the backend does not demand a token, and the app works without the login screen.

## What's next

- List of conversations (the backend already returns `/api/sessions`)
- Coding mode via SSE: `POST /api/code/chat` returns a stream, and it requires
  different readers — `EventSource` on the web and `fetch` with a stream in RN
- Attaching files (`POST /api/chat/upload`)
- Voice (`/api/asr`, `/api/tts`)
