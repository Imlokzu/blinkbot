# Virtual Bot — features and tests checklist

Run 2026-08-25 (full workflow: pytest + live backend + UI in browser).
Statuses: `[x]` works · `[~]` works with caveat · `[✗]` broken · `[ ]` not run.

**Summary: 136 unit tests green; end-to-end verified chat, stream, tools, images, coding, voice (TTS→ASR), memory, workspace, projects, wizard.**

## 1. Chat brain (chain: openclaw → omni → anthropic → chat2api → demo)

- [x] OpenClaw gateway (18789) — main brain; live response ~8–20 s, emotions, persona
- [x] Omni router (shim 20128 → `opencode serve` 20131) — both directly and as fallback
- [x] Fallback Omni model (`fallback_model`) — logic in brains, covered by tests
- [~] **SotaModel** (anthropic slot, www.sotamodel.net) — **connected**: key is valid, base_url/model are correct, request reaches; BUT the account has 0 balance → INSUFFICIENT_BALANCE, so real responses will start after a top-up. Models: claude-opus-5 (default), claude-opus-5-max, claude-opus-5-xhigh — toggle in `config.yaml`.
- [ ] Chat2API (8080) — not running on the machine (offline in status, chain bypasses)
- [x] Demo mode — covered by tests (`chat_demo`)
- [x] Circuit breakers omni/openclaw (backoff) — covered by tests
- [x] Omni model selection: `GET /api/models` + `POST /api/model`, allowlist (400 on alien)
- [x] Reasoning levels for kimi/glm/deepseek/qwen/minimax/grok (in `/api/models`; UI shows selector)
- [x] SSE streaming: delta → early emotion → done; tag `[емоція:x]` is stripped from the stream
- [x] `/api/status` — all brains + real mode

## 2. Emotions and "liveliness"

- [x] Emotion tag is parsed; heuristic as fallback (tests + live chat: greeting/happy/confused)
- [x] SSE `/api/events` — ping, log, emotions (crab face reacts; saw «слухає…» (listening...), gift in idle)
- [x] Spontaneous emotions in idle (saw crab pose change between actions)
- [ ] Camera reactions — requires Vision Agent (not running)

## 3. Chat tools (via /api/tools/call and via live chat)

- [x] `weather` (Kyiv: temperature + 5-day forecast)
- [x] `currency` (USD→UAH 44.73, date)
- [x] `facts` (Wiki; returns title/extract/summary/url)
- [~] `web_search` (works; first DDG result sometimes an ad — cosmetic)
- [x] `image_search` (DDG) → **carousel in chat: 3 crabs rendered, captions, 1/3, dots**
- [x] Carousel: fullscreen modal, Copy/Link/Save/Like
- [x] «У бібліотеку сесії» (To session library) from carousel → file actually landed in `sessions/<sid>/library/` (checked on disk)
- [x] `workspace_info` / `list` / `read` / `write` / `mkdir` / `delete`→`.trash` — full cycle
- [x] `workspace_show` — publishes preview event (SSE)
- [x] `create_brain_file` / `create_brain_directory` / `list_brain_navigation`
- [x] `ask_question` (backend ok + ui event) — didn't run card render in chat end-to-end
- [ ] `todo_list` / `show_choice` / `open_screen` — didn't run live (same mechanism as `ask_question`)

## 4. Coding mode (omp)

- [x] Chat ⇄ Code toggle; separate conversation space (`code-chats`) — own tasks are visible
- [x] `POST /api/code/chat`: **live task → omp created `hello.txt` and `index.html`** (real tool calls, files checked on disk, content is correct)
- [x] SSE steps in UI: block «write index.html — готово» (write index.html — done), response token stream
- [x] Coding model selection: list, toggle, rejection of alien id (`evil/…` → 400)
- [x] Selection of `code/` or `code/<project>/` folder (backend `_code_cwd` + 404 on non-existent)
- [x] `POST /api/code/stop`
- [~] File preview from omp step: only filename is shown — `/preview/` only sees `workspace/`, not `code/` (gap, not bug; candidate for enhancement)

## 5. Workspace folder

- [x] «Файли» (Files) tab: tree, editor with line numbers, Save/trash, session folder indicator
- [x] `/preview/{path}` (200), `/file/{path}` — styled page
- [x] `/api/workspace/save-url` — downloads image to library (verified file on disk)
- [x] Session and project library (`session/library`, `projects/<slug>/library`, strict subdir format)
- [x] Isolation: coding only sees `code/`, chat tools — only `workspace/`

## 6. Projects

- [x] CRUD via API: create (slug transliteration «Тест Воркфлоу» (Test Workflow)→`test-workflow`), list, rename, delete→`.trash`
- [x] Chat binding to project (+tests); unbinding on project deletion
- [x] Projects at the top of the chat list in the dashboard

## 7. Chat sessions

- [x] History on disk, restoring the last conversation after reboot; new chat does not return old messages
- [x] Auto-title in background (saw live: «Знайди і покажи…» (Find and show…) → «Три піксельні краби» (Three pixel crabs))
- [x] Pin (star) — API + UI; session deletion
- [x] Tool steps are saved to the session and restored
- [x] Upload: `POST /api/chat/upload` (PNG ok; protection: type/size/signature)
- [x] **Vision:** picture in chat → immediately `opencode-go`/`minimax-m3` → «червоне коло + синій прямокутник» (red circle + blue rectangle) ✓
- [x] Long pastes → chip with mini-editor (code in place; UI mechanic from previous test run)

## 8. Voice

- [x] TTS `/api/tts` — Piper, ukr., WAV 22 kHz (after fixing venv paths); 3 voices in `/api/tts/status`
- [x] ASR `/api/asr` — **local faster-whisper large-v3-turbo**; TTS→ASR cycle recognized the phrase word for word
- [x] MicButton/VoiceMode UI: «Голосова розмова» (Voice conversation) modal with auto-listening, «підтакувати» (nodding), speed (didn't run live mic in automation)

## 9. Memory

- [x] Auto-facts from messages → profile (tests) + auto conversation journal (saw live)
- [x] Top-3 notes into prompt (tests `test_user_brain_isolation`)
- [x] Memory dashboard: note list, editor, preview (after rebuilding bundle)
- [x] `/api/memory/list|file|save` + traversal protection (tests)
- [x] Brain isolation: by `session_id` in dev mode, by Clerk user in prod (tests)

## 10. Dashboard shell

- [x] Tabs: Chat (Чат) / Памʼять (Memory) / Файли (Files) / Браузер (Browser) / Зір (Vision) / Сервіси (Services) / Логи (Logs) / Налаштування (Settings)
- [x] Crab face on the right: states «Очікування/слухає…/дивиться теку» (Waiting/listening.../looking at folder), lives without requests
- [x] Built-in browser `/api/browser/page` (proxy + navigation interception)
- [ ] Vision (needs Vision Agent on 8000 — not running)
- [~] Services: endpoints exist; start/stop didn't run live (to avoid spawning processes)
- [x] Logs `/api/console`
- [x] Wizard: Особистість (Personality) (імʼя/мова/8 характерів/кастом), Мозок і ключі (Brain and keys) (+«Перевірити звʼязок» (Check connection) → «мозок відповів (openclaw)» (brain answered (openclaw))), rest of the sections render
- [ ] Skills store — didn't run (needs openclaw CLI request)

## 11. Authorization

- [x] Clerk JWT gate on sensitive routes; `CLERK_DISABLED=1` = local dev mode
- [~] **Gap:** `/api/status`, `/api/setup*` (including `POST /api/setup/keys`!) WITHOUT auth gate — on localhost this is ok, but with Clerk in prod it should be gated (intentionally untouched: static wizard doesn't send token)
- [x] Secrets only in `.env` (600), not exposed in API

## 12. Integrations

- [ ] Display bridge — display not running
- [ ] Vision watcher with camera — Vision Agent not running
- [x] Dream cycle — covered by tests (`test_dream_cycle`)

---

## New provider: SotaModel

- `.env` → `ANTHROPIC_API_KEY=<key in .env, does not go to repo>` (SotaModel gateway key)
- `config.yaml` → `anthropic.base_url: https://www.sotamodel.net`, `model: claude-opus-5`
- Protocols: both Anthropic `/v1/messages` and OpenAI `/chat/completions`; in `/v1/models` there are also hidden `model-S/O/A/T`
- Status: key is alive, **balance 0** → INSUFFICIENT_BALANCE on any request (checked all 3 models + both protocols). Once topped up, brain #2 (after openclaw/omni) will start answering itself.

## Fixed in this run

1. **`main.py`:** 3 routes (`workspace/delete`, `workspace/save-url`, `workspace/show`) executed their body OUTSIDE `with brain_context.set_clerk_user(...)` — in multi-user they would work with someone else's workspace.
2. **`main.py _require_user`:** in dev mode returned `"dev"` → all conversations merged into one clerk-brain, per-session isolation dead, tests wrote to real `user_data`. Now empty uid → old local model.
3. **`App.jsx`:** `toolSteps`/`mode` were declared inside `try` and used in `catch` → any early error (network/401) gave `ReferenceError` and the error message didn't appear in chat.
4. **`ImageGallery.jsx`:** «У бібліотеку» (To library) went with a bare `fetch` without token → with Clerk it would fall with 401. Now `authFetch`.
5. **Vision end-to-end:** `omni_shim` discarded images (text model «не бачу» (I don't see) = success, fallback didn't trigger). Now the shim converts data-URLs into `opencode` file parts, and brains with images immediately ask the vision model. Checked live.
6. **TTS 503:** venv moved with the repo (`projects/` → `projects/maintained/`), 39 scripts in `.venv/bin` pointed to the old path — fixed, Piper works.
7. **`workspace.save_url`:** network errors flew as 500s → now clean 400 with text; added browser User-Agent (CDNs like Wikimedia block bare httpx).
8. **`memory-panel`:** stale bundle in `static/` made no requests — rebuilt, note list appeared.
9. **Tests:** `conftest` disables Clerk (`CLERK_DISABLED=1`); regolo test of omni models became config-driven; vision fallback test updated for "vision immediately"; `test_projects` seeder without clerk context. Was 16 fails → **136 passed, 1 skipped**.

## Known gaps (not broken, but worth keeping in mind)

- `/api/setup/keys` and company without auth (see §11) — and the wizard doesn't know how to save the SotaModel key (only Omni/OpenClaw), edit it manually in `.env`.
- File preview from omp coding steps (`code/` folder) is missing — `/preview` only sees `workspace/`.
- `mode` in coding done-event says "omp · model" — the dashboard shows it honestly, ok.
- Chat2API/Vision/Display — not running (optional by design).
- Port-note: `user_data/<sha(clerk:dev)>/` contains old test chats from the «dev» uid dev mode; they are no longer visible to the app (new path model). If needed — move them manually, but it's just test garbage there.
