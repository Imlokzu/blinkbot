# HANDOFF — Blink (сесія 2026-07-26, Claude Code / Fable 5)

## KMP phone client implementation (2026-10-03)

The new `mobile-app/` Kotlin Multiplatform client now has the shared Compose
chat/settings/files UI, Android native integrations and background outbox, and
an iOS SwiftUI host. The shared host gained one-time QR pairing, revocable
device tokens, durable jobs/event replay, pause/resume/scheduling, edit/regenerate
forks and revision-aware workspace saves. PC pairing lives in Settings/Profile.
Set `MOBILE_API_ORIGIN` for a nondefault HTTPS tunnel hostname; mobile traffic
preserves the existing owner/history/workspace.

Validation: 69 common state/transport tests, 62 Android unit tests, 32 native
instrumentation cases and two Compose/controller UI flows passed. Android APK
build passed and screenshots were inspected. The backend reviewer reports
69 mobile tests plus ordinary live-server pairing/SSE/queue smoke passed; the
broader suite passed 1,040 tests with 7 skipped and 88 probe cases excluded,
with one unrelated image timing failure passing on retry. PC typecheck/build
and the pairing panel's QR expiry/revocation flows passed.

Remaining release limits: full Xcode compilation/iOS runtime testing, iOS
background outbox scheduling, APNs/FCM remote push, and strict Steer through the
installed gateway's public API. Steer is explicitly disabled by capability.
Public tunnel/provider/microphone production operation was not exercised by
fixtures. Independent reviewers used available models; no Fable review is claimed.
See `mobile-app/README.md`, `docs/mobile-app/ARCHITECTURE.md`, and
`Virtual Bot/docs/MOBILE-API.md` for setup and precise boundaries.


Файл-передача контексту для продовження роботи в іншій сесії (Claude Desktop).
Прочитай його повністю перед будь-якими змінами.

---

## 1. Що це за проєкт

**Клод Бот** — DIY персональний AI-компаньйон: Raspberry Pi 3 (камера, мік, динамік, SPI-екран) + домашній сервер i5 + Claude API як "особистість". Підхід — **софт спочатку, залізо потім**: власник ще НЕ купив бота, тому все має працювати віртуально на macOS.

Повна спека: `blink-full-spec-v3.md` (архітектура Edge/Fog/Cloud, BOM, roadmap).
Порядок розробки: `blink-dev-order.md` (6 кроків: зір → RAG → голос → емоції → камери → UI).

## 2. Структура репозиторію (`/Users/hhh/projects/claude bot/`)

| Папка | Що це | Стек | Порт |
|---|---|---|---|
| `Vision Agent/` | Очі: детекція обличчя/руху | FastAPI + OpenCV | 8000 |
| `Voice Loop/` | Вуха/рот: Whisper STT → OpenClaw → pyttsx3 TTS | Python | — |
| `OpenClaw Vision Plugin/` | Інструмент `vision_check_camera` для агента | TypeScript | — |
| `blink-display/` | Обличчя: піксельні очі, 15 емоцій, 4 екрани | FastAPI + React/Vite | 8001 (WS) |
| `Remote Control/` | USB-пульт (VID:PID 0627:697d) + I2C LCD статус | Python (Pi) | — |
| `Device Setup Wizard/` | "Claude Bot Studio" — налаштування | Electron + Vite/React/TS | — |

OpenClaw gateway (мозок): `127.0.0.1:18789`, токен — env `OPENCLAW_TOKEN` (пріоритет) або `Voice Loop/config.yaml`. **Токен — секрет, нікуди не публікувати.**

## 3. Що зроблено в цій сесії (все застосовано і зібрано)

- **OpenClaw Vision Plugin**: виправлено тест (`"echo"` → `"vision_check_camera"`), нормальні повідомлення помилок. Тести + tsc чисті.
- **Vision Agent** (`main.py`): лок навколо `_prev_gray` (гонка потоків), перевірка `.empty()` Haar-каскаду, MJPEG-стрім завершується після 30 невдалих кадрів (не спінить CPU), ліміт 8МП на кадр, перевідкриття камери після збою.
- **Voice Loop**: `OPENCLAW_TOKEN` env, try/except у `transcribe()`, TTS-двигун реюзається (⚠️ див. п.4 — це внесло регресію), `validate_config()`.
- **Remote Control**: автопошук пульта за VID/PID з фолбеком, перепідключення при OSError, LCD-цикл не крашиться без aplay/arecord, помилки друкуються.
- **Device Setup Wizard**: слайдер чутливості руху тепер в одній шкалі з API (ratio 0.002–0.05), обробка помилок Vision-fetch, очікування старту 6с → 15с. tsc чистий.
- **blink-display**: WS URL динамічний (`VITE_WS_URL` або hostname сторінки), експоненційний backoff 2с→30с, таймер-відлік реалізовано, `duration_seconds` за контрактом, ErrorBoundary, прибрано pyserial. Build + pytest (6) чисті.

## 4. ⚠️ ЗАЛИШКОВІ БАГИ (знайдені адверсарною верифікацією, ЩЕ НЕ ВИПРАВЛЕНІ)

Пріоритет 1 — **критичне**:
1. **Voice Loop `voice_loop.py:~109-132`** — реюз pyttsx3 двигуна: на macOS (nsss) другий `runAndWait()` на тому самому двигуні часто висне або мовчки нічого не каже, БЕЗ виключення — try/except не спрацює. Плюс: якщо TTS таки кидає помилку, `_tts_engine` не скидається в None — зламаний двигун закешовано назавжди. Фікс: на darwin — init на кожен виклик (або watchdog + reinit), і скидати кеш в except.

Пріоритет 2 — помірне:
2. **Vision Agent `main.py:~209-218`** — перевірка 8МП стоїть ПІСЛЯ `cv2.imdecode`: PNG-бомба ~300КБ (100МП) з'їдає ~0.7с CPU і ~900МБ RAM до відмови (заміряно). Фікс: ліміт на довжину БАЙТІВ до декодування (напр. 5МБ).
3. **Vision Agent `main.py:~107`** — `cv2.imdecode` на порожньому буфері КИДАЄ `cv2.error` (не повертає None) → 500 замість 400. Фікс: `arr.size == 0` → None або try/except.
4. **display `useWebSocket.js:~50`** — `onclose` без guard навмисного закриття: у dev під React.StrictMode виходить 2 живі сокети (події обробляються двічі, send() губиться). Фікс: прапорець `closedByCleanup` у cleanup ефекту + `if (wsRef.current === ws)` перед NULL.
5. **display `App.jsx:~64-78`** — `resetIdle` і `scheduleReturn` ділять один `idleTimer` ref: подія `speaking`/`speaking_end` під час кастом-екрана з `duration_seconds=0` («показувати доки не замінять») скидає його на face через 10с, а тривалості >10с обрізаються. Фікс: окремі refs / пропускати resetIdle коли активний duration-managed екран.

Пріоритет 3 — дрібне:
6. **Vision Agent `main.py:~288`** — `_release_camera` (shutdown) чіпає `_capture` без `_capture_lock` — вузька гонка при вимкненні.
7. **display `App.jsx:~143`** — таймер на ланцюжку setTimeout(1000) дрейфує; краще якорити на кінцевий timestamp.

Remote Control і Setup Wizard верифікацію пройшли повністю — там нічого не лишилось.

## 5. НАСТУПНЕ ВЕЛИКЕ ЗАВДАННЯ (замовлення власника, ще не почато)

Власник хоче (його слова, переказ): «софт спочатку, бота куплю потім; зроби HTML-вікі по проєкту; все до шику через агентів, кожного робочого агента перевіряє окремий Fable-агент на максимальному зусиллі (ловить всі баги); потім веб-додаток для керування всім — як бот, але віртуальний».

### 5.1 `wiki.html` (корінь проєкту)
Самодостатній HTML (без CDN, працює офлайн), українською, у піксельно-ретро стилі проєкту:
- огляд проєкту і навіщо він;
- SVG-діаграма архітектури (Edge RPi3 / Fog i5 / Cloud + компоненти + порти 8000/8001/8100/18789);
- карта «що де лежить»: всі .md файли (спека, dev-order, README кожного модуля, `blink-display/API_CONTRACT.md`, AGENTS.md/CLAUDE.md візарда) з описом;
- як запускати кожен модуль (команди звірити з README!);
- інтерактивний чек-ліст дорожньої карти (кроки 1–6 / фази 0–4; зроблено: кроки 1,3, частково 6; НЕ почато: RAG-пам'ять (крок 2), шар емоцій (крок 4), face recognition, навігація) — стан чекбоксів у localStorage;
- журнал змін цієї сесії (розділ 3) + відомі баги (розділ 4);
- БЕЗ секретів (жодних токенів).

### 5.2 «Virtual Bot» — веб-додаток (нова папка `Virtual Bot/`)
Віртуальне втілення бота до купівлі заліза + панель керування. Бекенд FastAPI на **127.0.0.1:8100**, фронтенд — статичний vanilla JS/HTML/CSS (без збірки) у `Virtual Bot/static/`, який бекенд і роздає.

**API-контракт (узгоджений, дотримуватись):**
- `GET /` → `static/index.html`
- `GET /api/status` → `{"openclaw":bool,"anthropic":bool,"vision":bool,"display":bool,"mode":"openclaw"|"anthropic"|"demo"}`
- `POST /api/chat` `{"message":str}` → `{"reply":str,"emotion":str}`; emotion ∈ `idle|listening|thinking|speaking|happy|sad|confused|surprised|love|sleepy`
- `GET /api/vision/snapshot` → проксі JSON з `http://127.0.0.1:8000/vision/snapshot`; офлайн → 503 `{"error":...}`
- `GET /api/memory/list` → `{"files":[{"path":"people/imya.md","title":...}]}`; `GET /api/memory/file?path=...`; `POST /api/memory/save {"path","content"}` — папка `Virtual Bot/brain/{people,topics,logs}/` зі стартовими нотатками; **захист від path traversal обов'язковий**
- `POST /api/services/{vision|display}/start|stop`, `GET /api/services` — запуск/зупинка локальних сервісів (uvicorn у відповідній папці, використати її .venv якщо є)
- MJPEG фронтенд бере НАПРЯМУ з `http://127.0.0.1:8000/vision/stream.mjpg` (не проксювати)

**Мозок чату (за пріоритетом):** OpenClaw gateway (патерн з `Voice Loop/openclaw_client.py`, токен з env) → прямий Anthropic API (`ANTHROPIC_API_KEY`, модель `claude-sonnet-5`, через httpx, без SDK) → демо-режим (заготовлені українські відповіді), щоб додаток працював завжди. Токен НІКОЛИ не віддавати фронтенду.

**Шар емоцій (крок 4 спеки):** системний промпт просить модель починати відповідь тегом `[емоція:happy]`; парсити і прибирати; фолбек — евристика за ключовими словами. Проста памʼять: топ-3 нотатки з `brain/` за ключовими словами → у системний промпт.

**Фронтенд-панелі:** Обличчя (піксельні очі з емоціями/морганням — надихнутись `blink-display/frontend/src/components/PixelEyes.jsx`), Чат (стан «думає…»), Зір (стрім/статус), Пам'ять (перегляд/редагування нотаток), Сервіси (кнопки старт/стоп), Статус. Все українською.

**Екран пристрою (`/screen`):** окремий vanilla UI 320×240 із каруселлю тайлів, шторкою швидких дій та Android-подібною шухлядою застосунків (5 колонок, Камера, Сервіси, локальна Панель, Памʼять, Розмови й Налаштування без переходу на `/`). Памʼять читає реальні `.md`-нотатки через `/api/memory/list|file`, Розмови — збережені сесії через `/api/sessions`; при Clerk без входу показує зрозуміле повідомлення доступу. У Налаштуваннях реально працюють тема, яскравість, голос/гучність, вибір Piper-голосу та три стилі іконок: кольоровий 16×16 pixel-пак Pxlkit у шухляді, однотонні SVG з вибором кольору й окремо кольорові SVG. Додатково працюють таймер повернення додому, автосон, формат часу 12/24 години, показ дати й режим мінімальних анімацій; усі локальні параметри переживають перезавантаження та скидаються кнопкою скидання. Маленькі перемикачі та годинник лишаються у внутрішній pixel-мові бота; для Pxlkit додано локальні SVG-assets і visible attribution у Налаштуваннях. Стрічка подій прибрана; свайп угору відкриває шухляду.

**Залежності:** fastapi, uvicorn, httpx, pyyaml. `requirements.txt`, `config.yaml`, `README.md`, `start.sh`, venv у `Virtual Bot/.venv`.

### 5.3 Процес (вимога власника)
Кожен робочий агент → окремий **Fable-ревʼювер на максимальному зусиллі**, який адверсарно шукає баги і ФІКСИТЬ їх. Наприкінці — смоук-тест: підняти сервер, curl всі ендпоінти (включно зі спробою path traversal → очікувати 400), перевірити статику, погасити процеси.

## 6. Порядок дій для наступної сесії

1. Виправити баги з розділу 4 (почати з критичного №1).
2. Побудувати `wiki.html` (5.1).
3. Побудувати `Virtual Bot` (5.2) за контрактом.
4. Все — через агентів з Fable-ревʼю (5.3); після кожного блоку — запуск тестів/збірок.
5. Оновити цей HANDOFF.md наприкінці (що зроблено, що лишилось).

Примітки: проєкт НЕ git-репозиторій (диффів нема — обережно з перезаписами); шлях містить пробіл — завжди лапки; коментарі/UI українською; стиль коду — мінімальні хірургічні зміни в існуючих файлах.

---

## 7. Сесія 2026-08-28 (ZCode): магазин екрана + Now Playing

Все у гілці `feat/bot-tools-workspace-and-chat-ui`, коміти дрібні (feat/fix/docs).
Тести: **167 passed** (`pytest tests/ --ignore=tests/test_asr_regolo_live.py`),
смок-тест пройдено (ендпоінти + path traversal → 400/404 + статика).

### Що додано
- **Магазин на екрані** (`/screen → Застосунки → Магазин`): таби Додатки /
  Скіни (локальні пакети `store/packages/<id>/package.json`, встановлення =
  копія в `store/installed/`, стан = файлова система) + Скіли / Тулзи
  (показ і встановлення через наявний OpenClaw `/api/store`). Встановлені
  застосунки зʼявляються плитками в шухляді і відкриваються в iframe
  `/store-apps/<id>/`. Пакети-зразки: Метроном, Піксель-майстерня, скіни
  AMOLED / Захід / Термінал (скіни застосовуються миттєво, у iframe
  передаються postMessage `botSkin`). Код: `screen_store.py` + UI в
  `static/screen/screen.js`.
- **Now Playing** (бар знизу екрана): іконка джерела (тап — змінити
  YouTube/Радіо), назва біжить стрічкою якщо довга, прогрес, повний плеєр
  з перемоткою/чергою/станціями; ducking під час мови бота. Аудіо —
  `/api/music/stream` (проксі з Range → перемотка справжня): Invidious
  `latest_version?local=true` (гейт googlevideo 403-ить прямі ссилки) з
  автодискавері інстансів (api.invidious.io, кеш 24г), паралельними
  пробами й ретраями; радіо — SomaFM/Radio Paradise (без Range, браузерний
  UA — інакше icecast обриває). Транскрайб — youtube-transcript-api +
  фолбек на Invidious-капшени (VTT).
- **Застосунок YouTube у магазині**: пошук на екрані (клавіатура в браузері /
  голосом через бота), тап по відео → `POST /api/music/play` → SSE → грає в
  Now Playing (музика живе після закриття застосунку). Тули `play_music`/
  `listen_to_video` шлють ту саму подію; автоплей-блок після тапу в iframe
  знімається ретраєм на перший дотик по екрану.
- **Тули мозку**: `play_music`, `stop_music`, `listen_to_video` (SSE-подія
  `music` керує екраном); `open_screen` тепер знає `store`.
- **Доки для розробників**: `Virtual Bot/docs/SCREEN-PLATFORM.md` (формати
  пакетів, обмеження, API) і `docs/YOUTUBE-CLIENTS.md` (вибір стеку
  yt-dlp + Invidious + youtube-transcript-api; youtubei.js — варіант для
  React-панелі). README доповнено.

### Відомі межі / що далі
- Публічні Invidious-інстанси флапають хвилинами (502↔206): у «погану»
  хвилину стрім 502 → тапнути ще раз. Стабільне рішення для реального
  бота — свій Invidious у docker, вписати ПЕРШИМ у
  `config.yaml → music.invidious_instances` (`http://` дозволено).
- Іконки пакетів магазину — лише з наявних наборів екрана (свої SVG у
  шухляді поки не підвантажуються).
- Пакет «скіл» в екранному магазині ставить через openclaw CLI — якщо
  OpenClaw недоступний, таб показує чесну помилку.
- Смоук-тест через Fable-ревʼювер (розділ 5.3) на НОВИЙ код не проганявся.

## 8. Сесія 2026-09-02: Agent Talk і Watch

- **Agent Talk:** нова розмова тепер ізолюється лічильником версії; запізніле
  відновлення старої сесії або старий стрім не можуть повернути текст після
  натискання «+». Перемикання Чат/Код також очищає видимий потік.
- **Watch (`/console`):** початкові `/api/trace` і `/api/console` приймають
  `session_id`, тому Watch показує тільки активний діалог. Порожня нова сесія
  не підтягує стару глобальну історію; зміна сесії в Agent Talk синхронізує
  відкрите вікно Watch через `storage`.
- **Мобільний клієнт:** виправлено передачу `kind=chat|code` для списку,
  відкриття й видалення сесій; async-відповіді старого діалогу ігноруються.
- Перевірки: `PYTHONPATH=. .venv/bin/pytest -q` → **214 passed, 1 skipped**;
  `npm run build` у `Virtual Bot/chat-panel`, `npm run typecheck`
  у `blink-app` та `node --check` для Watch — чисті.

## 9. Сесія 2026-09-03: учасники розмови (в роботі)

- У гілці `feat/bot-tools-workspace-and-chat-ui` з HEAD `070ef2b` є
  незакомічена фіча явних учасників Agent Talk: `participants[]`, `events[]`,
  `participant_name` у чаті, рядок присутності та системні події в панелі.
- Базовий зріз цієї роботи перевірено: `.venv/bin/pytest -q` → **218 passed,
  1 skipped**; `npm run build` у `Virtual Bot/chat-panel` — чистий, assets
  синхронні з вихідниками.
- Узгоджений leave-контракт: `POST /api/sessions/{id}/participants/leave`
  з тілом `{"name":"…"}` повертає `{"left":true|false}` і HTTP 200;
  успішний leave прибирає ім’я з активних `participants` та додає подію
  `participant_left` до `events`. Повторний leave є ідемпотентним.
- До коміту ще потрібні: санітизація рамкових/керівних символів імені та
  відсікання імені бота, коректне очищення порожніх сесій без обходу `_prune`,
  UI-виправлення гарячої клавіші й системних бульбашок, повні тести та
  ручний smoke test.
- Учасники наразі свідомо належать режиму «Чат»; code mode не приймає
  `participant_name`. Документація має залишатися синхронною після фінального
  коміту фічі.

## 10. Сесія 2026-09-04: virtual device settings

- Закомічено ізольований шар `56b3536` (`feat(screen): add virtual device
  settings package`) без змін у зайнятих `main.py`, `screen.js`, `screen.css`,
  `i18n.js`, ASR або YouTube-файлах.
- `Virtual Bot/system_status.py` надає роутер із `/api/system/status`,
  `/api/system/audio/devices` і `/api/system/network`. У `virtual` snapshot є
  назва/заряд бота, Wi‑Fi, Bluetooth/навушники, мікрофон/динамік і маршрути
  гучності для бота, YouTube, будильника та сповіщень.
- `Virtual Bot/store/packages/device-settings/` — готовий 320×240 застосунок
  магазину: вкладки «Звʼязок»/«Звук», картки Wi‑Fi/Bluetooth, вибір аудіо,
  per-app повзунки та mute. Значення гучності зберігаються локально й
  передаються майбутньому native-мікшеру через `postMessage`.
- Перевірки ізольованої частини: `17 passed`, Python compile і JS syntax
  чисті. Для повної інтеграції потрібно додати в `main.py` імпорт
  `system_status` та один рядок `app.include_router(system_status.router)`;
  це робиться власником shared-ділянки після завершення його правок.
- 2026-09-04 зроблено візуальний pass після реального headless-рендеру
  320×240: `device-settings` переведено з синьої dashboard-палітри в токени
  Клод Бота (`#16181a`, `#1e2124`, мідний `#d17a58`, оливковий `#8ca879`),
  ущільнено header/tabs, виправлено злипання title/subtitle і залишено
  прокручувані touch-картки для малого екрану.
- 2026-09-04 комітами `2c242b2` і `931ba43` виправлено жести та тему:
  карусель і шари використовують Pointer Capture, застосунок у iframe має
  власний swipe-bridge, а `light` передається разом із skin-змінними. Білу
  палітру `device-settings` перевірено headless-рендером; bridge приймає
  повідомлення лише від батьківського `/screen` з тим самим origin, а
  вертикальний скрол списку не закриває застосунок випадково.
- 2026-09-04 комітом `070b096` swipe-зона розширена на всю сцену й store app:
  картки та кнопки можуть починати горизонтальний/вертикальний swipe, але
  tap по кнопках не губиться; input/select/textarea, повзунки та прокрутка
  залишаються інтерактивними. Для кнопок Pointer Capture не забирається, а
  завершення жесту добирається через `window`.
- Після fix: `pytest -q tests/test_screen_store.py tests/test_system_status.py`
  → **17 passed**; `node --check` для обох JS-контурів чистий; живий smoke
  на `8100`: `/screen`, статика застосунку, `/api/system/status` → 200,
  спроба `static/../main.py` → 404.

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

## 12. Session 2026-09-30: useful activities and native screen utilities

- Added **Guide** to the device drawer, with eight practical activities:
  morning, focus, kitchen, break, creativity, media, learning and device care.
  Each explains the purpose and offers real destinations with connection
  requirements. Missing local apps can be installed and opened explicitly.
- Added **Daily checklist 1.1.0**: localized task CRUD, completion progress,
  additive morning/work/evening starters, reset completion without deleting
  tasks, and guarded local storage. Starter tasks retain their locale keys
  after an unchanged edit, including a language change while editing.
- Added **Unit converter 1.1.0**: length, mass, temperature, volume and speed;
  decimal comma/point, negative values, unit swaps and saved settings when
  browser storage is available. Swap reuses the computed numeric result
  without display rounding and preserves invalid live input. US liquid units
  are labelled explicitly.
- Both apps use the native app kit, match Material You/Deep UI, dark/light
  themes and skins, and reserve the screen's gesture insets. Guide has its own
  drawer icon; Checklist and Converter have distinct drawer and store icons.
- Guide preserves its selected activity across Back/language rebuilding,
  with focus restored to the activity's Back control on remount.
  System apps keep the guide in their back stack; tiles retain normal carousel
  navigation. Language rebuilding no longer adds duplicate history entries.
  Shared pending operations suppress duplicate installs across remounts and
  never reopen an app after Home. Layer opening resets ancestor scroll offsets.
- Independent maximum-effort native reviewers approved Guide and both apps;
  Fable was not available in this runtime. Their fixes have regression tests
  for malformed storage, precision, keyboard bridge submission, picker focus,
  navigation and an actual Chromium install/remount race.
- Completed validation reported by the leader: full Virtual Bot suite
  **883 passed, 6 skipped, 131 subtests**;
  final isolated curl smoke **21 checks passed**, including memory/store
  traversal attempts returning **400**. Installed 1.1.0 HTML/model assets
  matched repository sources byte for byte. Browser checks cover both styles,
  themes, languages, safe insets and Back behavior. The leader's final repeat
  passed all 21 curl checks and all eight unique app/style/theme combinations;
  safe insets and return to Guide were verified, and processes were closed.
- Design contract: root `DESIGN.md`. Working scenarios, product use cases,
  connection limits and explicitly future ideas:
  `Virtual Bot/docs/SCREEN-USE-CASES.md`. Utility state is per browser;
  it is not synced to bot memory or other devices.
- Feature code and the root design contract were committed and pushed
  separately on the current `main` branch
  under the owner's required identity. Concurrent dashboard/analytics work
  and pre-existing workspace changes were preserved.


## OpenClaw inference dashboard (2026-09-30)

- Added the dashboard `#/inference` section in the existing warm Plex UI, with
  UTC ranges for today, 7, 30 and 90 days, provider filters, daily usage,
  model traffic and effective per-million API prices.
- Added authenticated `/api/openclaw/analytics` and
  `/api/openclaw/analytics/inferences` endpoints. Gateway RPCs provide usage,
  quota windows and resolved API estimates. Missing token counts and prices
  remain unknown; subscription estimates are labelled as API equivalents.
- Sessions use opaque transcript-instance ids; actual model usage governs
  provider matching, including fallback. The journal exposes only metadata,
  never conversation text, tool arguments, account addresses or credentials.
  History is the current transcript, bounded to 10,000 messages; resolved
  pricing logs cover at most 1,000 messages. Both limits appear in the UI.
- Localized all new labels in `dashboard/src/locales/inference.ts` (uk/en).
  Added unit and isolated browser regression checks, preserving concurrent
  integration/settings and device-screen changes.
- Separate adversarial backend and UI reviewers fixed attribution, metadata
  sanitization, reset guards, missing/zero distinctions, incomplete indexing,
  provider scope, modal focus restoration and keyboard access. Fable and the
  pinned reviewer model were unavailable; available native reviewers ran at
  maximum effort.
- Validation: full Virtual Bot suite 886 passed, 6 skipped, 131 subtests;
  dashboard 69 unit tests, typecheck and production build passed. Browser
  checks cover 320/390/768/1180/1440px, fallback filtering, journal focus,
  partial indexing and unavailable reports. Production panel axe audit had
  zero violations. Live RPC/HTTP smoke passed, including real journal data,
  referenced static assets and two traversal guards returning HTTP 400.
  Isolated smoke servers and browsers were shut down after verification.
- Implementation and generated production assets were committed and pushed
  to origin on main using the required owner identity. Existing unrelated
  working-tree changes were left for their owners.

## Usage modality and router comparison follow-up (2026-09-30)

- Restyled inference usage to the owner's reference: separate metric cards,
  local provider logos, a compact quota list, shared Text/Voice/All selection,
  and a Router comparison view reachable from the chat model picker.
- Added a metadata-only SQLite usage ledger under ignored runtime storage.
  Request-local ContextVars record exact owner, modality and actual Jev
  decisions; no prompt, reply, raw session id or credential is persisted.
  Voice includes voice input or spoken output; the report covers LLM usage,
  excluding ASR/TTS charges. Historical unclassified traffic remains in All.
- Comparison reprices identical measured token categories at a selectable fixed
  model and everyday Opus 5.5/4.8. Official price sources and the check date
  are visible. This is a pricing scenario, not a replay or quality comparison.
  Verified Jev traffic is separate from historical model-mix estimates and
  includes measured classifier overhead; missing usage does not become zero.
- `/api/openclaw/analytics` and its journal accept `modality`; new authenticated
  `/api/openclaw/analytics/comparison` accepts modality/provider/baseline/Opus
  and observed/verified-routing population. History matching is bounded and
  incomplete coverage is visible. Synthetic fixtures use isolated ledgers.
- Independent UI review fixed modality placeholders, scope changes and the
  API-equivalent disclaimer. Backend review supplied fixes and regression
  tests for missing token partitions, partial router costs, configured-agent
  matching, history bounds and request coalescing; the review service stopped
  before its final summary. Parent validation covered the resulting changes.
- Final checks: 910 Python tests passed, 6 skipped, 131 subtests; 69 dashboard
  tests, typecheck and isolated production build passed. Browser regressions
  passed at five widths; new comparison axe audits had zero violations in
  light and dark themes. Real read-only usage/comparison endpoints passed,
  along with static assets, no-store headers and traversal guards returning
  HTTP 400. Test servers and browsers were stopped. The local dashboard
  backend was restarted to load the new analytics routes.


## OpenClaw control pages (2026-10-01)

- Added Agents, Sessions, Automation and Channels to the dashboard, using the
  existing warm surfaces, Plex fonts, locale keys and lazy hash routes. Agents
  links filter sessions/jobs; model settings links select the Brain tab.
- Operator-only `/api/openclaw/control/*` endpoints expose allowlisted metadata
  from installed gateway RPCs. Conversation text, private session keys, paths,
  credentials, ownership metadata and raw errors never enter these responses.
  Direct local access requires loopback Host/Origin and no forwarding headers;
  authenticated operators are allowlisted by `VBOT_OPERATOR_USER_IDS`.
- Sessions and jobs paginate 50 rows, with agent filtering before pagination.
  Context freshness and unknown connectivity/status remain explicit. Channels
  reads cached health without probing, logging in or sending messages.
- Automation creates paused daily/interval jobs in isolated sessions, with
  delivery and failure alerts disabled. Enable/pause retains existing job
  payloads and uses configuration revision guards. Run history returns 20
  recent metadata entries. No real jobs were created or changed during testing.
- The enlarged dock remains usable at all four edges: constrained docks scroll,
  side targets retain 44px height, and top docking stays within the header.
- Separate native adversarial reviewers fixed operator proxy bypasses, stale
  cache races, filtering order, queued/unknown states, duplicate mutations,
  permission-loss display and dock reachability. Fable and the configured
  code-reviewer model were unavailable; available default native agents
  performed the independent reviews. Parent fixed definition-list semantics
  after axe identified invalid nested metadata groups.
- Validation: full Python suite **928 passed, 6 skipped, 150 subtests**;
  **77 dashboard tests**, typecheck and isolated production build passed.
  Browser checks cover both languages, five widths, metadata filters, context
  freshness, pagination, mocked create/pause, run history, focus restoration,
  permission loss and all 14 dock targets on four edges at 768/1180 x 600px.
  Scoped axe checks report zero violations across all four pages in light/dark
  themes; automated contrast checks still have incomplete results because of
  existing decorative/header overlays and animated labels.
- Live read-only HTTP smoke passed 22 checks, including the gateway inventories,
  run history, referenced production assets, screen assets, memory traversal
  returning 400, and operator-origin/proxy guards returning 403.
- Production assets were built from an isolated copy that includes only this
  task's settings deep-link edit. Pre-existing uncommitted integration settings,
  API changes, root instructions and unrelated artifacts remain untouched.
- Limits: agent configuration and channel setup remain in existing settings/
  OpenClaw; these pages do not edit arbitrary config or forward arbitrary RPCs.
  The shared RPC helper loses structured revision-conflict details, so failed
  writes return generic 502; refresh and inspect jobs before retrying an
  ambiguous create response. Docs: `Virtual Bot/docs/DASHBOARD-CONTROL.md`;
  design contract: `Virtual Bot/dashboard/DESIGN.md`.
- Backend, review fixes, UI, production assets and documentation were committed
  and pushed on `main` under the required owner identity. Test browsers and
  isolated servers were shut down; the existing OpenClaw gateway was preserved.

## Mobile dashboard navigation refresh (2026-10-01)

- Replaced the phone header's horizontally scrolling section tabs with a compact
  menu, wordmark and localized current-section title.
- Added a fixed, safe-area-aware bottom navigation rail for Overview, Chat,
  Memory and Settings. The full section drawer remains available from the
  header, so secondary panels stay reachable without crowding the phone shell.
- Chat content and the composer reserve the rail's height; desktop docking and
  the existing chat controls remain unchanged. Phone labels and section titles
  follow the selected Ukrainian/English locale.
- Validation: dashboard tests **78 passed**, typecheck and production build
  passed. Browser checks covered 320/390px phone layouts, English labels,
  desktop dock preservation, section switching, static assets and memory
  traversal returning HTTP 400. Test browsers and local servers were stopped.

## Secret scanning (2026-10-01)

- Installed Gitleaks 8.30.1, TruffleHog 3.97.9 and pre-commit 4.6.2 locally
  with Homebrew.
- Added a repository pre-commit configuration with Gitleaks as the primary
  staged scan and a TruffleHog staged-snapshot scan. The TruffleHog wrapper
  suppresses detector output so blocked credentials are not copied into logs.
- Added a TruffleHog full-history CI job alongside the existing Gitleaks job.
  The existing `.gitleaks.toml` and the new `.trufflehogignore` document the
  synthetic integration-test fixture excluded from both scanners.
- Verified the public GitHub repository has Secret Scanning and push
  protection enabled. GitHub non-provider patterns are unavailable to this
  user-owned public repository on the current plan, so local scanners cover
  generic patterns.
- Validation: Gitleaks full-history scan passed; TruffleHog full-history scan
  passed; both pre-commit hooks passed on the staged security configuration;
  a staged synthetic RSA key was blocked by the Gitleaks hook.


## 2026-10-01 — Editable Workbench documents

- Workbench Markdown now uses editable Tiptap with a localized compact toolbar,
  GFM tables, checklists, images, explicit Save and Cmd/Ctrl+S. Rich/source views
  share drafts. Pending saves compare against current parent state, retaining
  newer edits even when the initiating tab unmounts and reopens.
- Inline Excalidraw blocks use ordinary Markdown image references to separate
  scene files. Scene writes are serialized and update the exact query cache;
  reopening waits for a confirmed current file before mounting the canvas.
- Chat file links open inside Workbench. Older absolute/file URLs are normalized
  only within the authenticated workspace, including files at its root. Shared
  guidance teaches both local and MCP agents workspace-relative links and
  workspace_show for successful document delivery.
- GET /api/workspace/info adds reveal_available; POST /api/workspace/reveal
  selects an existing session file in Finder on the server's Mac. Authentication,
  user isolation, traversal and external symlink guards remain enforced.
- Two required source files (design tokens.css and token-count tokens.ts) were
  accidentally ignored by *token*. Exact source exceptions now make a clean
  checkout buildable; no credential files are included.
- Validation: Python suite 934 passed, 6 skipped, 150 subtests passed; dashboard
  typecheck and 80 unit tests passed; production build and both Workbench browser
  scenarios passed. The new browser check covers tables/tasks/drawing references,
  tab/source draft retention, delayed-save races, internal links and phone layout.
  Live smoke verified session reads, static assets, real Finder selection and
  traversal rejection (400).


## Chat send bubble (2026-10-01)

- The complete sent-message bubble flies from the stationary send button to
  its real position in the chat, then hands off to the readable message as
  the travelling copy dissolves with blur. Mouse and Enter share the effect;
  empty drafts and rejected attachment-only sends do not launch it.
- Appearance now separates Extra effects from the screen settings. Send bubble
  is enabled by default; its local preference survives reloads and synchronizes
  across tabs. Reduced motion suppresses the effect; the overlay never captures
  input or appears in the accessibility tree. Timers are released on unmount.
- New labels use English/Ukrainian locale keys in locales/effects.ts. The effect
  preserves the rendered message content, line breaks and theme in flight.
- Validation: dashboard unit tests, typecheck and isolated production build;
  tests/sendBubble.browser.mjs covers full-message content, multiline delivery,
  real destination/size, mouse/Enter, blur and cleanup, persistent/cross-tab
  toggles, reduced motion and desktop/phone layouts.
  Virtual Bot suite: 934 passed, 6 skipped, 150 subtests. HTTP smoke returned 200
  for panel/screen/static assets and 400 for memory path traversal.
- Independent native adversarial review fixed preference refresh after periods
  without subscribers and checked the empty-text gate. Fable was unavailable.


## 2026-10-01 — Chat navigation and live file creation

- The Workbench toggle is stationary in the chat toolbar and opens/closes from
  the same control. The conversation list can collapse from its header and
  reopen from the toolbar; keyboard focus returns to that toggle.
- Conversation selection lives only for the loaded page. Section navigation
  restores the selected chat; fresh visits/reloads start a new conversation.
  Visiting an untouched project leaves the last main selection intact.
- Sending while history restores waits for the exact request and session. The
  composer visibly queues the submission and retains further typed drafts.
- Workbench starts closed and automatically opens only for file creation/update
  calls from the current conversation. Old history, read/show calls and old
  open preferences do not auto-open it. A manual close stays respected for
  that reply; batched instant writes and updates still open on the next turn.
- File creation shows the pixel agent icon and a bounded reveal of real input
  or confirmed file content. Reduced motion skips the reveal. Active writes
  block partial file reads and editor writes; completed content opens normally.
- Drawing flushes respect active writer ownership. Inline serialized saves
  capture their revision and recheck it before dispatch, including after the
  agent has finished an intervening update. Read/show entries retain write locks.
- Shared agent guidance explains that show selects a file and only writes open
  the panel automatically; tool success alone does not prove panel visibility.
- Validation: 87 dashboard unit tests, TypeScript, clean production build,
  navigation/queued-send and real-SSE writing browser checks, existing Workbench
  and rich-document/drawing browser regressions. The writing test covers old
  canvas flush suppression and no reads while a replacement is active.
  Python suite: 934 passed, 6 skipped, 150 subtests passed; smoke verifies
  session reads, static assets and traversal rejection with 400.


## 2026-10-01 — Agent editing in formatted documents

- Markdown live writing now uses the same Tiptap document schema as human
  editing. Headings, emphasis, tasks and tables appear in the editor with a
  named agent cursor, changed-text selection and incremental insertion.
  Separate paragraph updates preserve untouched prose; all content comes from
  actual write inputs or confirmed file reads.
- Playback is read-only, suppresses update/history events and never saves,
  takes composer focus or fetches an actively written file. Missing input
  preserves confirmed prior prose. The editor stays mounted through delayed
  completion; native reduced motion shows the complete document immediately.
- Manual reloads and agent revisions use separate cache fields. Reopening
  Workbench retains the newest confirmed baseline even after a reload nonce
  resets. Partial frames insert required schema content when an embed is hidden.
- Independent adversarial review fixed invalid partial document structure and
  identified the reload baseline issue; the corrected integration was approved.
  Fable was unavailable, so a separate supported native agent reviewed at max
  effort. Typecheck/build passed; clean-source dashboard has 93 passing unit
  tests (92 with the owner's existing local test deletion). Virtual Bot:
  934 passed, 6 skipped, 150 subtests. Browser checks cover document round-trip,
  live SSE editing, selection/focus, delayed completion, reload/reopen, phone,
  reduced motion, navigation and the existing drawing autosave guards.
- Isolated HTTP smoke verified workspace reads, traversal rejection (400),
  dashboard entry/SW and all 274 release static assets (200). Release assets
  were built from current HEAD plus only these changes; the local live build
  retains the owner's unrelated pending UI/settings edits.


## 2026-10-01 — Chat toolbar and usable live documents

- Desktop now has one conversations toggle in a shared toolbar spanning the
  conversation and right panel. Workbench's toggle stays at the far right in
  both states; the sidebar no longer has a duplicate close button. Phone and
  tablet drawers retain their existing navigation.
- Live Markdown remains scrollable and selectable. Manual wheel/touch/keyboard
  navigation stops following until the reader chooses the localized return to
  the agent cursor. Visual transactions and confirmed-file completion wait for
  a reader's selection/drag to finish; the actual file write continues.
- Only the formatting toolbar remains inert during agent ownership. Inline
  drawings can expand and pan in view mode, labelled View drawing, without
  scene edits or autosaves. Existing stale-canvas and file-read guards remain.
- Independent max-effort review added native content capture for React node-view
  portals, pointer-release capture/window-blur handling, and destroyed-editor
  guards. Its harness verified final content, completion once, resuming and
  cleanup. Browser checks verified layout/toggle focus, native mouse selection
  through confirmed writes, scroll position, inline scenes and zero file saves.
  The CLI wheel command always dispatches at (0,0); the regression exercises
  DOM wheel handling plus targeted scrolling and native mouse dragging.
- Validation: typecheck/build, 93 clean-source dashboard unit tests (92 with the
  owner's existing local deletion); Python 934 passed, 6 skipped, 150 subtests.
  Navigation, interaction, writing, document/drawing and reduced-motion browser
  checks passed. Isolated HTTP smoke covers workspace reads, traversal 400,
  entry/SW and all release resources. Clean assets exclude unrelated pending
  owner changes; the live local build preserves those changes.


## 2026-10-01 — Night review: reliable model picking

- Validated saved model preferences and catalog snapshots, preventing malformed
  browser storage from crashing chat. Added factual vision/fast filters, combined
  search, unique counts and localized failed/empty/disconnected states with Retry.
- Settings reads bypass HTTP caching. Confirmed model/effort responses update
  shared state and the reload cache before refetch; pending state is shared with
  the composer, overlapping writes are blocked and rejected picks stay out of
  recents. Async failure feedback survives menu remounts.
- Independent maximum-effort code and architecture reviewers found no blocking
  issues (Fable and configured specialist models unavailable). Their persistence
  finding was fixed. Python: 934 passed, 6 skipped, 150 subtests; isolated dashboard:
  97 unit tests, typecheck and build passed. Picker and existing document/live
  Workbench browser regressions passed. Isolated HTTP smoke: 300 checks passed,
  including both traversal guards returning 400 and all release static files.
- Test browsers/server were stopped and Mac output remains muted. Concurrent
  agents' pending/staged edits and live build were preserved; release assets use
  committed sources plus only this change. Full scope, findings and evidence:
  `reports/night-agent-model-picker-2026-10-01.md`.


## Working chat uploads (2026-10-01)

- Desktop and phone share the + attachment sheet. File selections are copied
  before resetting the picker; uploads show progress, cap at eight files and
  cannot attach a delayed result to another conversation. File-only sends work.
- Numeric upload sizes now survive ChatRequest validation. Text/code, PDF and
  DOCX contents reach the brain from validated server files; filenames remain
  visible in the conversation. Partial extracts are disclosed. Documents allow
  20 MiB; images allow 10 MiB and must match their image signature.
- Uploads use unique owner-scoped names, reject symlinks/traversal and incomplete
  files, and authenticate downloads with no-store caching. Messenger uploads
  follow the same owner mapping. PDF and ZIP/XML work is bounded.
- Validation: 952 Python tests passed, 6 skipped, 150 subtests; dashboard
  typecheck, tests and production build passed. attachments.browser.mjs uses the
  real multipart endpoint on desktop/phone and isolates chat writes. Independent
  native adversarial review fixed quota/ownership and extraction edge cases.


## NotebookLM and chat connectors (2026-10-01)

- The + sheet now opens Connectors on desktop and phone. Its inventory reads
  the actual OpenClaw MCP configuration, with enabled/access state kept distinct
  from a verified connection. Existing MCP servers link to their native setup.
- NotebookLM uses the installed notebooklm-py 0.8.3 typed API inside its existing
  environment. The picker lists real notebooks/sources and imports selected
  source text as a private attachment. Unready/error sources cannot be selected.
- Settings → Connectors provides profile selection, passive authentication check,
  bounded Google browser login and agent setup. Setup registers the native
  NotebookLM MCP server and its five read/query tools in OpenClaw, preserving
  existing tool permissions with conditional configuration writes.
- Source/setup routes require the gateway operator. Cookie/token contents stay
  in the installed API profile; errors and CLI output never expose credentials.
  Child processes and login browsers are cleaned up, and imports publish
  complete files with 0600 permissions atomically with partial-extraction notices.
- Google sign-in was completed in Zen on 2026-10-02. Passive token verification
  succeeded, the API listed two real notebooks, and native OpenClaw agent access
  was enabled. The local login preference is Zen; its existing Google session is
  refreshed through the notebooklm-py cookies extra without launching Chrome.
- Validation: 963 Python tests passed, 6 skipped, 150 subtests; 96 dashboard tests,
  typecheck and production build passed. Connector browser fixtures cover the
  catalog, source readiness/import, expired login and phone layout; real upload
  browser checks exercise the actual multipart endpoint. Independent native
  adversarial review checked process/profile/config races and source publication.


## Zen NotebookLM login (2026-10-02)

- The login button uses the saved Zen browser preference on this Mac. It opens
  NotebookLM in the existing Zen session and refreshes only the chosen SDK
  profile through the installed cookies extra. It does not launch Chrome or
  close the owner's Zen browser. Waits and child-process cleanup are bounded.
- Login pins the resolved profile and rejects configuration changes before
  verification; automatic cookie/language metadata updates do not change it.
- Live validation: Google token fetch succeeded, two real notebooks were listed,
  and OpenClaw exposed/probed all five read/query tools with no diagnostics.
  The final full suite passed 967 tests with 6 skips and 150 subtests; 15
  connector regressions cover Zen, profile resolution and timeout cleanup.
  Independent native review and live API/static/traversal smoke passed.


## 2026-10-02 — Attachment previews and an adaptive source picker

- The + picker overlays the composer without shifting the draft. Desktop uses
  compact photo/file rows; phone/tablet keep camera/photo/file touch targets.
  Its scroll height fits the available space above a tall draft or keyboard.
  Opening focuses an action; Escape and closing Tools restore the + trigger.
- Drafts and saved user messages use matching preview cards. Images fetch owned
  uploads with bearer headers into revocable blob URLs; text/PDF/DOCX cards show
  real server extraction and open a bounded text preview with original download.
  Dialogs register their triggers for focus restoration. Private preview text
  stays in component state; requests abort on unmount and reject redirects.
- Authenticated GET /api/chat/attachment-preview returns text/truncated/type/size
  from validated server bytes, caps preview text at 4,000 characters and sends
  Cache-Control: no-store. Ownership, traversal and descriptor checks remain.
- The agent receives every attachment name, validated type and order as escaped
  reference metadata in both streaming and ordinary chat. Image MIME comes from
  server files, so missing or forged client MIME cannot shift filename/image
  correspondence. A failed expected image fails the turn instead of disappearing.
- Explicit conversation selection and New reset the composer generation,
  including a reset between two unsaved chats with empty ids. Late uploads cannot
  enter a different draft. A same-chat compaction refresh preserves unsent text
  and attachments; a real reply gaining its session id does not reset the draft.
- Independent max-effort review found and verified the focus, private-cache,
  draft-generation and MIME-order fixes. Fable was unavailable; a separate
  supported native reviewer approved the corrected scope. The preexisting gap
  between upload path validation and FileResponse remains a separate follow-up.
- Validation: 993 Python tests passed, 6 skipped, 150 subtests; 60 focused upload/
  image/stream tests; typecheck, production build and 100 clean-source dashboard
  unit tests (99 with the owner's existing local test deletion). Real multipart
  browser checks cover previews before/after send and history restoration,
  file-only sends, keyboard focus, source menus on desktop/phone, connector
  compatibility, removal, delayed uploads/reset and compaction draft retention.
- Isolated HTTP smoke verified workspace/attachment traversal rejection (400),
  dashboard entry/SW and all release resources (200). Release assets match the
  committed source; the live local build retains unrelated owner UI edits.


## 2026-10-02 — Attachment opening motion

- The + source picker opens from the bottom-left trigger origin in 240ms,
  with short staggered action rises. It keeps the composer stationary.
- Attachment previews open with a 240ms scale/rise on desktop and a 260ms
  bottom slide on phones. Scoped keyframes animate transform and opacity only,
  preserve Tailwind centering and avoid the generic popup's text blur.
  Closing takes 130ms and retains Radix focus restoration; reduced motion
  disables all scoped opening/closing/stagger effects completely.
- Independent max-effort native review approved the scoped CSS and test.
  Fable was unavailable. Browser animation samples verify start/middle/end
  opacity, no blur, viewport fit, centering, repeated menu opening and focus
  on desktop/phone with both motion preferences. Existing real upload/preview
  browser regressions verify interaction behavior on the release build.
- Validation: typecheck/build, 100 clean-source dashboard unit tests (99 with
  the owner's existing local deletion), Python 993 passed, 6 skipped and
  150 subtests. Isolated HTTP smoke checks traversal 400 and release assets200.
  Clean release assets match committed source; local owner UI edits remain.

## 2026-10-02 — Forwarded tool bridge hardening

- A tokenless OpenClaw tool call is still allowed from direct loopback, but a
  loopback socket carrying `Forwarded` or `X-Forwarded-*` is now treated as a
  proxied request and requires Clerk. Authenticated forwarded calls continue to
  work; rejected calls never execute a tool.
- Regression coverage includes direct loopback, remote, both forwarded-header
  spellings and an authenticated forwarded call. Targeted tests: 11 passed;
  full Virtual Bot suite: 995 passed, 6 skipped, 150 subtests. Dashboard tests
  (99), typecheck and live HTTP smoke passed. Traversal guards returned 400 and
  the server was stopped. Details: `reports/night-agent-tool-bridge-2026-10-02.md`.


## 2026-10-03 — Repository audit and recovery proposals

- New audit: `reports/repository-audit-2026-10-03/REPORT.md`, with eight
  findings, proposed solutions, and five additions focused on durable work.
- Companion probes reproduce cache, persistence, publishing, and coding-worker
  behavior with synthetic temporary data and mocked external services.
- Findings are proposals; product code and other agents' pending edits remain
  unchanged. Check `VALIDATION.md` for evidence, scope, and review limits.


## 2026-10-03 — Resilient web search

- Replaced DuckDuckGo HTML scraping as the primary `web_search` provider with
  Exa's official hosted MCP. It works without a key within Exa's free limits;
  an optional `EXA_API_KEY` uses the owner's account quota via an `x-api-key`
  header. Secrets are never added to URLs, fallback requests, or error logs.
- Preserved `query`/`results` and added provider metadata. JSON and SSE MCP
  responses normalize to title, URL and snippet. DuckDuckGo remains a fallback,
  including its Lite endpoint after an ordinary HTML endpoint failure.
- DuckDuckGo HTTP 202/challenge forms and provider rate limits get temporary
  cooldowns instead of false empty results. Each provider has a 10-second
  deadline and the complete fallback chain is bounded to 25 seconds. Exa
  redirects are disabled so an API key cannot follow a cross-host redirect.
- Input errors and service failures use tool locale keys in Ukrainian/English.
  The hosted Exa schema was checked live: query/objective are required, with
  optional numResults. The keyless quota is rate-limited, not unlimited.
- Separate adversarial reviewers fixed count validation, challenge false
  positives, RPC rate-limit classification, stalled-stream fallback, clock
  isolation in tests, and redirect header leakage. Fable was unavailable in
  this runtime; supported native reviewers completed the independent checks.
- Validation: full working-tree Python suite 1,027 passed, 6 skipped and 178
  subtests; final focused search suite 19 passed and 28 subtests. Secret scans
  passed. Six consecutive HTTP searches returned three Exa results each in
  1.03-1.43 seconds. Status/tools/screen/static assets returned 200 and memory
  traversal returned 400. The isolated smoke server was stopped. Only the live
  web backend was restarted; its search and browser search both returned results.


## Dashboard chat personalization (2026-10-03)

- New chats center the same mounted composer; the first message moves it to
  the bottom with a position-only spring. Drafts, attachments and recording
  state survive. Restored history uses the conversation layout.
- Chat's palette button and Settings > Appearance share browser-local
  wallpaper, accent, composer opacity and blur settings. Built-in sky, dusk,
  forest and plain backgrounds are available; custom PNG/JPEG/WebP images
  are validated, resized and re-encoded locally, without a server upload.
  Invalid files and storage errors preserve the prior saved appearance.
- Maker icons use theme-aware colours from the existing local SVG set. The
  maker is identified independently of the serving host; unknowns stay neutral.
- Independent adversarial review fixed keyboard crowding of tall drafts and
  attachments, and improved appearance helper-text contrast in both themes.
  Reduced motion skips the composer spring; reduced transparency uses solid
  controls. Touch controls retain 44px targets. Fable was unavailable; a
  separate supported native reviewer performed the maximum-effort review.
- Validation: 1,027 Python tests passed, 6 skipped, 178 subtests; 117 frontend
  tests in the isolated committed-source build (116 in the working tree);
  TypeScript and production build passed. Appearance, chat-navigation and
  model-picker browser checks passed with browser-only fixtures. Checks cover
  image upload/reload/rejection/reset, quota failures, draft identity, first
  send, saved history, phone layouts, software keyboard and reduced motion.
- Production assets are built from committed source plus this task's changes,
  preserving unrelated drafts. HTTP status, screen and dashboard returned
  200; a memory path-traversal attempt returned 400.


## 2026-10-03 — Codex image generation

- Codex is the first image provider, using the official local app-server and
  ChatGPT sign-in. API-key billing is never selected as an automatic fallback.
- Chat's + menu opens a localized Create image dialog with passive readiness
  and retry. Explicit creation requests also route directly to the provider,
  preserving operator authorization, upload ownership and the image deadline.
- Completed images remain in chat history as private uploads, with authenticated
  blob previews, the existing viewer and download. Bounded image references
  accompany follow-up text turns without presenting captions as pixel analysis.
- Shared tokenless MCP calls cannot establish a caller's ownership and are
  rejected when authentication is enabled. Allowlisting image_generate alone
  does not bypass this. Reference-image editing is outside this first provider.
- Independent native maximum-effort review fixed RPC ordering, inherited tool/
  model settings, process-group cleanup, bounded saved files and identity checks.
  Architecture review found no remaining blocker. Fable and the configured
  specialist model were unavailable; supported native agents reviewed.
- Follow-up image metadata is filtered by the authenticated upload owner,
  including two users sharing a cached session ID. Independent architecture
  review verified the filter and its regression and returned CLEAR.
- Validation: 1049 Python tests passed, 6 skipped, 178 subtests; 117 committed-
  source dashboard tests, TypeScript and production builds passed. Browser
  checks exercised real SSE/history/private publication with a fixture image,
  both locales, desktop/phone fit, focus, viewer and zero scoped axe violations.
  HTTP smoke covered 300 checks, including traversal rejection (400), operator
  proxy rejection, private uploads/history and every release resource.
- Live native generation succeeded through the running bot's Codex environment:
  one operator tool call returned a 1536x1024 PNG (995,173 bytes) in 22.6 seconds,
  with a matching private download. The standalone agent-shell CLI still needs
  sign-in; its environment does not determine the running bot's readiness.
- Backend and UI commits passed Gitleaks/TruffleHog and were pushed on main as
  the required owner identity. Release assets match committed sources; the live
  local build preserves other agents' pending UI edits. Test servers and browsers
  are stopped after verification. Setup: Virtual Bot/docs/IMAGE-GENERATION.md.


## Wallpaper media and quiet chat controls (2026-10-03)

- Model selection now always groups by maker without catalog sorting controls.
  A separate icon to its right selects server-reported thinking levels. Both
  menus keep search, availability feedback, serialized writes and keyboard
  support. Large catalogs and effort lists scroll within Radix's available
  viewport space so search and reset controls remain reachable.
- Navigation and icon actions use unfenced glyphs. The chat customization
  shortcut is removed; Settings > Appearance owns wallpaper, placement,
  colour, glass/solid material, opacity, blur and right-panel visibility.
  Closing the rail preserves selected pins and restores focus to its toggle.
- The new local chat-sky-v2.webp is an original generated painted cloudscape
  (1672x941, 213 KB), with provenance and its exact built-in ImageGen prompt
  beside it. The exact reference wallpaper was not identified. WebP joins
  the service-worker precache so the built-in sky remains available offline.
- One app-wide wallpaper serves selected chat, navigation, conversations,
  right-panel and other-page surfaces, or the whole app. MP4/WebM uploads up
  to 40 MB stay in IndexedDB, with bounded JPEG stills; localStorage stores
  only an opaque ID. Playback loops muted and pauses for hidden tabs, reduced
  motion, data saving and routes outside the selected areas. Settings uses
  a still preview rather than starting a second player.
- Hyalite refraction and rim are restored beneath the composer. Its blur
  responds to settings; reduced transparency and solid material use opaque
  controls. Exact old stock defaults migrate to glass; custom values remain.
- A separate supported maximum-effort adversarial reviewer approved the
  asset and UI, fixed oversized posters and clipped effort menus, and verified
  the root's model-menu clipping fix. Fable remains unavailable in this runtime.
- Validation: 1,049 Python tests passed, 6 skipped, 178 subtests; 128 frontend
  tests in the isolated committed-source build, 127 in the working tree;
  TypeScript and production build passed. Browser fixtures verified a
  63-model catalog, effort writes/rollback/reset, both locales and touch
  geometry, local image/video upload and reload, one silent player, scope
  controls, pin-preserving rail closure, keyboard crowding and reduced motion.
  Status, screen and dashboard HTTP checks returned 200; memory traversal
  returned 400. Unrelated drafts and the owner's staged changes are preserved.


## Clear composer and combined model controls (2026-10-03)

- Composer glass again has a clear centre and refractive rim: zero fill and
  zero blur by default. Former stock frosting (88/8 or 35/12) migrates once
  through glassRecipe=2; deliberate solid/custom settings and later edits
  survive. The same textarea and lens persist after the first submission.
- One model button opens a two-column picker: models on the left, thinking
  on the right. Sorting, capability filters, provider/trait/count badges and
  the comparison footer are removed. Maker icons, names, unavailable feedback
  and selection remain. Search is a compact magnifier that expands on hover,
  focus or tap; Escape clears/collapses it before closing the popup.
- Choice writes remain serialized. Recent picks move the model row instead
  of duplicating checked radios. A delayed acknowledgment retains focus in
  the effort column; a current model deep in a large catalog remains visible
  as available popup height changes. Both columns have independent scrolling.
- Appearance offers liquid glass for selected models/thinking, attachments,
  context details and other menus. claudeBotPopupGlassTargets is shared across
  mounted consumers and browser tabs; explicit empty/invalid values override
  the legacy all-or-none flag. Theme consumers no longer reset popup material.
  Storage failures retain prior choices with localized feedback.
- The controller uses one lens per selected surface, follows radius changes,
  releases filters on close/unmount, and supports CSS fallback and live
  reduced transparency. Standalone rims and no-backdrop readable fills remain.
- Separate supported maximum-effort code/architecture reviewers approved
  the final changes. They fixed delayed-focus theft, invisible current models,
  dark-theme heading contrast and popup fallback/rim overrides. A dedicated
  popup reviewer verified 12 Chromium lifecycle scenarios. Fable remains
  unavailable in this runtime; no requested-model certification is claimed.
- Validation: 1,049 Python tests passed, 6 skipped, 178 subtests; 139 frontend
  tests in the working tree and 140 in the isolated committed-source build;
  TypeScript and production build passed. Combined-picker browser fixtures
  cover keyboard, hover/touch search, both locales, 320/390/768/1440 layouts,
  large catalogs, pending writes, rollback and focus. Glass fixtures verify
  transparent fill before/after send, selective popups, fallback, reload and
  quota rollback. Existing image/video, scope, keyboard and rail checks pass.
  Scoped accessibility audits report zero violations; wallpaper contrast
  checks requiring manual inspection were reviewed visually. HTTP status,
  screen and dashboard returned 200; memory traversal returned 400.
- Unrelated drafts and existing staged owner changes remain untouched.


## Phone chat navigation and compact model picker (2026-10-03)

- Phones now have one safe-area-aware chat toolbar: Menu, Conversations,
  compact model selection and New conversation. The global chat band and
  labelled bottom rail are removed, including their reserved space. Other
  phone pages retain a compact header with the same shared sections menu.
  The related unfinished footer removal is now part of the committed source.
- The sections drawer uses the existing dialog focus trap and dismissal,
  restores focus across route changes and respects landscape notch insets.
  A left-edge gesture transfers an open Menu to Conversations rather than
  stacking drawers; focus stays inside the remaining active dialog.
- Phone Workbench access moves to its own '+' row, independently of pinned
  Panels, and closing it restores '+'. Tablets retain their header control.
  Desktop navigation/docking remain unchanged. Keeping dock normalization
  mounted prevents saved desktop side offsets from creating phone gutters.
- Phone model/effort popup height is bounded by 360px, 52dvh and available
  space; width is at most 360px. Typography is compact while touch targets
  remain 44px. Both columns scroll, keep their current choice visible, and
  retain reachable cached-catalog recovery/retry controls in short viewports.
- The removed footer's malformed leftover CSS comment is fixed. Chat input
  uses 16px type on phones to avoid small-input zoom, with safe top/side insets.
- Independent supported maximum-effort review fixed drawer stacking, escape
  focus, notch padding and transfer focus. A separate picker review fixed
  recovery content clipping; the existing model/effort browser suite passed.
  Fable remains unavailable in this runtime; supported reviewers supplied
  the independent validation without claiming that model's certification.
- Validation: 1,049 Python tests passed, 6 skipped and 178 subtests; 139
  frontend tests, TypeScript and isolated production build passed. Browser
  fixtures cover 320/390/430 portrait, 667 landscape, short keyboard areas,
  both locales, coarse-pointer targets, Menu/Conversations transfer, route
  return, separate Workbench/Panels, body-lock/focus cleanup, and desktop
  resize/dock restoration. Existing chat navigation and Workbench checks pass.
  Unrelated Settings/API/backend/security drafts and staged changes remain.


## Popup glass follows animation (2026-10-03)

- Fixed a reproduced popup ghost: Radix animated its inner content while
  the glass on the positioning wrapper remained full-size and fully visible.
  This left an empty lens during exit and separated the rim during entry.
- Selected wrapped popups now share one scale, offset and opacity fade with
  their lens. Motion composes after the unchanged inline Radix positioning
  transform, keeping the trigger anchor correct. The inner CSS animation
  remains as Radix Presence's exit clock without a second visual animation.
- Only wrapped u-pop glass surfaces use this path. Non-glass and unwrapped
  surfaces retain their existing motion. Filters are not animated or rebuilt
  every frame. Preference changes and unmount restore hook-owned attributes,
  custom positioning values, origin and priorities.
- Read-only composer first-send/resize probes did not reproduce a separate
  lens in Chromium 152, so composer motion and vendor Hyalite remain unchanged.
- Independent maximum-effort review approved the fix with no source changes.
  Actual screencast frames show content and lens fading together with no
  residual plate; rapid open/close/reopen, focus and body locks also pass.
  Idle wrapper attribute mutations are zero. Fable remains unavailable in
  this runtime; the supported reviewer supplied independent verification.
- Validation: 142 frontend tests, TypeScript and isolated production build
  passed. Dedicated browser frames show zero lens/content rectangle difference
  during native SVG and CSS fallback open/close, and one effective fade.
  Tests also cover unchanged/repositioned anchors, blank/none transforms,
  reduced motion/transparency, preference cleanup and prior style restoration.
  Read-only HTTP status/screen/dashboard checks returned 200 and memory
  traversal returned 400. Unrelated drafts and staged changes remain intact.


## 2026-10-03 — Animated image generation

- Added a beUI-inspired dither field to actual image generation activity in
  chat, followed by a gentle reveal of the decoded private image. The same
  surface remains mounted from pending work to completion, reserves a square
  layout and shows actual image dimensions. The existing viewer/download works.
- Generation, loading, failure and interruption use real outcomes and locale
  keys. Exact delivered Markdown images do not create a second preview or an
  empty bubble. File-load retry reloads the existing result without regenerating.
- Reduced motion is static; canvas work pauses offscreen/in hidden documents
  and releases observers, listeners and frames. Captions use solid themed
  surfaces so custom wallpapers do not reduce readability.
- Independent supported maximum-effort review fixed live motion-preference
  updates and titled/grouped Markdown delivery. It identified duplicate reply
  IDs on repeated/late cancellation; an idempotent settlement guard closes it.
  Final review approved; Fable is unavailable in this runtime.
- Validation: 1049 Python tests passed, 6 skipped and 178 subtests; 149 clean-
  source frontend tests, TypeScript and production build passed. Controlled
  SSE browser checks cover pending/loading/reveal, single private fetch, stable
  DOM identity, real Stop/double Stop, failure/reload, saved history, the viewer,
  both languages/themes and 320/390/1280 layouts, with zero scoped axe violations.
  Existing chat navigation regression passed. HTTP smoke: 300 checks including
  both traversal guards returning 400 and every release resource returning200.
- Provenance: https://beui.dev/components/agents/image-generation; its MIT
  notice is retained in dashboard/licenses/beui-MIT.txt. No paid image generation
  was required for this visual change. Changes and production assets are pushed
  on main as the required owner identity; unrelated drafts remain untouched.
- Separate existing follow-up: App.tsx changes its outer wrapper when the OS
  reduced-motion preference changes, remounting the current panel. This task
  leaves that application-wide behavior unchanged; initial reduced-motion
  mode and the new component's preference subscription are verified separately.


## New-chat welcome and compact toolbar (2026-10-03)

- The empty chat cycles four localized headings with a short flip every four
  seconds. Hidden tabs pause it; reduced motion keeps the original heading.
  Phrase measurement preserves composer position and drafts; assistive
  technology receives one stable heading rather than repeated announcements.
- The global header no longer repeats the model, connection dots or local-mode
  badge. Model selection stays in the composer; authenticated account controls
  and the token bridge remain mounted.
- One desktop right-panel menu selects Hidden, Panels or Workbench. Pins and
  saved visibility survive switching. An explicit non-workbench choice
  suppresses automatic file-write opening for the current reply; closing
  the workbench returns focus to the shared trigger. Phone controls remain.
- Independent supported maximum-effort review fixed that automatic-opening
  edge case and verified keyboard, focus, glass and stable layout. Fable is
  unavailable in this runtime; no Fable certification is claimed.
- Validation: 149 frontend tests, TypeScript, isolated production build,
  1,049 Python tests with 6 skipped and 178 subtests, appearance and workbench
  browser fixtures, and a scoped toolbar accessibility audit all passed.
  The dedicated welcome fixture covers rotation and right-panel selection.
  HTTP smoke checks verify dashboard/static assets and reject memory traversal.
- Unrelated owner drafts and staged changes are preserved.


## Readable settings and welcome typography (2026-10-03)

- Glass model headings share the popup lens, with clear group labels flowing
  with their rows. Solid and reduced-transparency modes retain opaque sticky
  labels. Default thinking is labelled Automatic in both locales; clearing an
  explicit override keeps its existing semantics, with cleaner confirmation copy.
- Every Settings section now encloses headings, descriptions and controls in
  one opaque near-white sheet, or its graphite counterpart in dark mode.
  Wallpaper remains behind it. The section sidebar uses stationary frosted
  glass with opaque reduced-transparency and unsupported-browser fallbacks.
- Welcome headings use self-hosted Lora italic with Latin and Cyrillic subsets;
  the redistribution license ships under dashboard/public/licenses/. Phrases
  change every two seconds. Stable layout, hidden-tab pause and static reduced
  motion continue to work.
- Independent supported maximum-effort review removed a nested-filter white
  strip and fixed narrow theme/language choices and Ukrainian MCP actions.
  Fable remains unavailable; no requested-model certification is claimed.
- Validation: 149 frontend tests, TypeScript and isolated production build;
  1,049 Python tests with 6 skipped and 178 subtests; welcome/model-effort
  browser regressions and every available Settings tab at 320/390/768/1440
  pixels in both locales/themes. Settings checks include loading, empty/error
  states, reduced transparency, shared model glass and Automatic reset.
- The release is built from committed source plus this change. Unrelated
  integration, activity-tree and customization drafts/staged work remain intact.


## Reference activity tree and sunset wallpaper (2026-10-03)

- Ordinary tool cards now form a compact, borderless tree within each existing
  reply group. Real operation names and redacted path/query chips connect to
  a thin trunk with rounded elbows; rows reveal actual parameters and results.
  Group/log folding retains focus and open state during streamed snapshots.
- Stable call IDs preserve concurrent same-name tools and repeated deliveries.
  Active, completed, failed and unconfirmed interruption remain distinct;
  finishing the reply never invents tool success. Dedicated image generation
  keeps its existing surface. No reasoning or synthetic progress is added.
- The sky preset uses the owner's exact original 1672x941 sunset/ocean JPEG,
  bundled locally with source and checksum provenance. Main wallpaper and
  Settings preview share it; JPEG files are precached offline. A stationary
  theme-specific wash improves reading over the image. Existing preference
  IDs, uploaded images/videos and placement choices continue to work.
- An independent supported maximum-effort reviewer fixed squeezed operation
  labels, an undefined connector token and measured caption contrast failures.
  The review also verified touch targets, unknown names, interruption labels
  and exact locally decoded image bytes. Fable is unavailable in this runtime;
  no Fable certification is claimed.
- Validation: 152 frontend tests, TypeScript, an isolated production build,
  1049 Python tests with 6 skipped and 178 subtests. The browser-only SSE suite
  passed on development and production builds: live concurrency/progress,
  snapshot replacement, keyboard folding, Stop, history, inert raw payloads,
  separate images, both locales, reduced motion and desktop/390/320px layouts.
  Scoped light/dark accessibility audits passed; native coarse-pointer controls
  measure 44px. Fixtures made no server mutations.
- Post-review HTTP smoke passed 7 endpoints and all 294 release resources,
  including the exact local JPEG and its precache entry. Both memory and
  workspace traversal attempts returned 400. Task-owned test servers and
  browser sessions are stopped; the existing dashboard server is preserved.

- The final integrated release also includes the preceding committed Settings
  readability and welcome-typography changes. Its clean-source checks retain
  152 passing frontend tests and successful TypeScript/build results. The
  browser SSE suite passes against the live /dash; all 297 final resources and
  7 smoke endpoints pass, with both traversal guards returning 400. The exact
  JPEG also returns 200 with matching original bytes over the phone LAN URL.
  The final PWA precache contains 102 entries. Temporary test servers and
  browser sessions are closed, and unrelated staged/source drafts are preserved.


## Expressive welcome typography (2026-10-03)

- The welcome heading now uses self-hosted Cormorant Garamond: 600 roman
  text with 500 italic emphasis, measured identically across all phrases.
  Latin/Cyrillic subsets and the original OFL ship locally. Type scales from
  32 to 54px; inline padding protects italic overhangs. The existing two-second
  flip, stable accessible name, hidden-tab pause and drafts remain intact.
- Validation: 152 frontend tests, TypeScript and isolated production build;
  1,049 Python tests with 6 skipped and 178 subtests; existing welcome browser
  fixtures in both locales/themes, plus independent maximum-effort visual
  review of glyph coverage, phone sizing and offline font delivery.
- Unrelated owner drafts and staged changes remain preserved.


## Kotlin Multiplatform mobile discovery (2026-10-03)

- Captured the mobile interview in `docs/mobile-app/DESIGN.md` and the
  companion `SCREEN_PLAN.md`; root `DESIGN.md` links the separate contract.
- The new Android/iOS client uses the existing backend, shared history and
  agent workspace through the owner's Cloudflare Tunnel API hostname.
  `api-bot.waveio.me` is a proposed address, not a verified deployment.
- Confirmed messenger bubbles, tool-tree summaries, the centered anchored
  model/effort picker, 65% menu, device-local wallpaper, ASR-to-composer,
  text-file autosave and private-by-default opt-in notifications.
- Final clarifications: model fallback stays within the same provider;
  Steer updates active work, Stop only stops, and Send later selects a
  date/time. Stop must not automatically start queued work.
- These are requirements, not shipped mobile features. No application code,
  native project, backend migration, or tunnel configuration was changed.
  Architecture must verify the backend gaps; remaining visual choices need
  the compact preview described in the screen plan, not another questionnaire.


## Automatic activity folding and website icons (2026-10-03)

- Tool activity opens while a reply runs and folds once that reply finishes
  or stops. Saved history starts folded; manual reopening stays open. A gap
  between calls does not close a live reply. Focus returns from an inner row
  to its own header before folding, while composer-owned focus is preserved.
- All tool-bearing replies now retain the existing stable reply ID through
  settlement, extending the generated-image mechanism to ordinary activity.
  This fixes remounting that skipped close animation and lost focused logs.
- One stationary CSS pseudo-element blurs the background behind the tree,
  following its layout during disclosure without blurring row text. Solid
  material, reduced transparency and unsupported filters use an opaque fill.
- Completed tool sources show real website favicons in the tree and source
  strip. Only public HTTPS origins are requested, without article paths,
  query strings, credentials, referrers or a third-party icon service. Missing
  icons and local/IP sources keep an 18px initial fallback. Website summaries
  deduplicate hosts while original source pages remain separate links.
- Independent supported maximum-effort review fixed the ordinary-reply ID
  remount bug and verified icon loading/fallback, focus, backing and native
  coarse-pointer 44px targets. Fable remains unavailable in this runtime.
- Validation: 156 frontend tests, TypeScript and clean-source production build;
  1049 Python tests with 6 skipped and 178 subtests. Controlled SSE browser
  checks pass on development and the production preview at 320/390/desktop
  widths, both languages and reduced preferences, with no external requests or
  server mutations. They verify folding, retained reopening, focus, stationary
  blur, icons, history and completed-image DOM identity with one download.
- Post-review smoke passes 7 endpoints and all 300 release resources. Memory
  and workspace traversal return 400; the exact local wallpaper is precached.
  The final PWA contains 104 entries. Task-owned servers and browsers are
  stopped; the existing live dashboard and other agents' preview are preserved.


## Frosted conversation sidebar (2026-10-03)

- Desktop conversations and the narrow drawer share one frosted reading layer
  when wallpaper is selected for conversations. Rows and date groups remain
  transparent; opaque fallback applies with no wallpaper, excluded placement,
  reduced transparency or unsupported backdrop filters.
- Conversation titles have a clear second line for date and message count,
  with a soft selected plate and an accent edge. Main row buttons support
  keyboard activation and identify the current conversation accessibly.
  Pin controls stay independent of opening a conversation; pointer events
  no longer enter the swipe surface's capture. Existing swipe actions remain.
- The phone/tablet drawer has one title with New and Close in its header.
  The desktop header remains; duplicate narrow labels no longer consume space.
- Independent maximum-effort review verified actual blur pixels, long lists,
  hover cards, keyboard/pointer selection, pinning, revealed swipe actions,
  full-swipe safety and opaque fallbacks. Fable remains unavailable.
- Validation: 156 frontend tests, TypeScript and isolated production build;
  1,049 Python tests with 6 skipped and 178 subtests; dedicated sidebar and
  existing navigation browser fixtures across both themes/locales and
  320/390/1100/1200/1440px layouts. HTTP smoke verifies static assets and
  memory traversal rejection. Unrelated owner drafts/staged work remain intact.

- Published the final integrated build with the preceding committed conversation
  sidebar improvements. Live /dash browser checks pass all activity folding,
  focus, blur and favicon cases; 156 frontend tests, TypeScript/build and all
  300 smoke resources pass. Temporary test servers and browser sessions are
  stopped. Original staged changes and unrelated source drafts are preserved.


## Guarded dashboard publication (2026-10-03)

- An actual HTTP probe reproduced the stale sidebar: the served dirty index
  referenced older ChatPanel chunks without the committed glass/list classes.
  Generated output changed during inspection; its writer is not identified.
  The /static/dash/ worker scope cannot control the owner's /dash/ route.
- npm run build now builds in isolation and publishes assets before an atomic
  shell switch, retaining old hashes for open tabs. Revision/input changes,
  earlier build timestamps and unsafe output paths are rejected. A short lock
  serializes publication; failed shell replacement rolls worker/stamp back.
- Explicit --outDir builds artifacts only. Snapshot builds pass the real repo
  through DASHBOARD_SOURCE_ROOT and captured DASHBOARD_EXPECTED_HEAD. Public
  build-info.json contains only revision and start time, never environment data.
- Independent maximum-effort review fixed duplicate flags, symlink/root output
  aliases and argument terminators. Fourteen focused publication tests passed.
  The new wrapper's real isolated production build, TypeScript and all 170
  frontend tests passed. Unrelated owner drafts and staged work remain intact.


## Compact model picker (2026-10-03)

- Desktop model/thinking selection is capped at 440x360px with 36px mouse
  rows and a 40px heading. Phones cap height at 320px; coarse pointers retain
  44px targets and 16px search input. Both columns scroll independently.
- Independent browser review verified 101 models, selected final model/effort,
  both actual themes/locales, 320/390/768/1440px and short landscape layouts.
  Existing model/effort and mobile fixtures passed, including writes, rollback,
  focus, glass and keyboard-reduced areas. The actual port-8100 sidebar fixture
  also passed. Python checks: 1,073 passed, 6 skipped and 178 subtests.


## Verified guarded dashboard release (2026-10-03)

- Published approved source bb20997 through the guarded publisher, then
  checked actual port8100 HTML, imported ChatPanel JavaScript and CSS. They
  contain the new glass/conversation metadata and compact440x360 picker.
  Source revision is recorded in public build-info.json.
- Fresh-browser sidebar and model/effort fixtures passed against /dash/ on
  the real port8100 server, rather than only an isolated dev preview.
  HTTP status/screen/dashboard/assets returned200; memory traversal returned400.
  Temporary smoke processes were stopped; unrelated owner work is preserved.


## Reliable source favicons (2026-10-03)

- Fixed missing source marks when the website blocks direct favicon requests
  or lacks the conventional file. Try the validated HTTPS origin, then Google
  S2 and DuckDuckGo's cached icon, retaining a fixed 18px initial if unavailable.
  Public HTTP citations get HTTPS icon requests. Only the hostname reaches
  these caches; article paths, queries, credentials and referrers are excluded.
  Custom-port applications keep their own origin icon rather than a guessed mark.
- A working cached mark survives same-origin article updates. Changing origin
  resets only its mark; stale image callbacks cannot advance a newer attempt.
  A visible stalled attempt advances after six seconds, with three bounded
  candidates and complete timer/observer cleanup.
- Independent supported maximum-effort review reproduced a cold lazy image
  at 20000px that exhausted attempts without making a request. Deadlines now
  wait for visible area; observer-less browsers use eager loading with bounded
  deadlines. Review also blocked local home.arpa names from cache lookups.
  Fable is unavailable in this runtime; the native reviewer verified both fixes.
- Real public probes decoded correct OpenAI and Wikipedia marks through both
  caches, while direct requests returned 403. No fake branding is generated.
- Validation: 175 frontend tests, TypeScript, guarded isolated production build;
  1080 Python tests with 6 skipped and 178 subtests. Controlled browser fixtures
  verify both fallback hops, exhaustion, HTTP citations, stale callbacks,
  preserved marks, timeout progression, hidden history, previous chat behavior,
  mobile widths and reduced preferences, without external requests or writes.
  Independent browser checks cover offscreen recovery and unsupported observers.
- Post-review smoke passes 7 endpoints and 301 release resources, including
  build metadata and the exact wallpaper. Both traversal guards return 400.
  Task-owned test browsers and servers are stopped; unrelated drafts and staged
  changes remain intact. Final artifacts use the guarded publication workflow.

- The current-revision guarded publisher installed the final favicon recovery
  build. Live /dash browser checks pass both cached-provider hops, exhaustion,
  stale callbacks, retained marks and existing chat/image/motion behavior.
  The final175 frontend tests, TypeScript/build and all301 release resources
  pass; original staged changes and unrelated drafts remain preserved.


## Conversation menu layers and menu-only pins (2026-10-03)

- Conversation cards now portal into the shared popup layer. This escapes the
  glass sidebar's stacking context and scroll clipping, so replies cannot
  paint over their actions. Existing glass, placement and hover caret remain.
- Removed persistent row stars and swipe Pin; Pin/Unpin lives in the card
  with actual pin glyphs. Rows gain title space. Shift+F10/ContextMenu opens
  keyboard actions; Escape returns to the invoking row.
- Native touch tests found and fixed release-focus dismissal, swallowed taps
  after cancellation and portal gestures reaching the underlying drawer.
  Timers clear on unmount. Pending mouse hover cannot open another card over
  keyboard-focused actions; closed exit layers do not block normal transfer.
- Separate maximum-effort review and trusted CDP tests passed both materials,
  locales/themes, real popup/reply hit-testing, actions, viewport collisions,
  native390/320 touch sequences, keyboard Pin/Unpin and hover/focus timing.
  Validation:180 frontend tests, TypeScript/guarded build;1,100 Python tests
  passed with6 skipped and178 subtests. Unrelated owner work is preserved.


## Verified conversation-menu release (2026-10-03)

- Published current committed source ca91cb3 through the guarded publisher,
  including the popup and menu-only pin fixes from 64dde96 plus parallel picker
  work. Actual port 8100 serves portaled cards and no row star/pin controls.
- The complete popup browser fixture passed against /dash/ on the actual
  server: real reply overlap and action hit-testing, both materials/locales,
  pin/project/delete, keyboard focus and native phone gestures.
- Fresh smoke checks returned 200 for status, screen, dashboard and referenced
  assets; memory traversal returned 400. Temporary processes were stopped.
  Approved source is pushed; unrelated owner drafts and staging remain intact.


## Smooth disclosures and bottom reply reactions (2026-10-03)

- Sources open and close through an accessible button-led disclosure with
  linked expanded state, inert hidden links and sharp inner opacity/translation.
  Explicit measured height also drives activity groups and nested tool logs.
  Capturing an in-flight height prevents reversal snaps; clipping wrappers do
  not acquire scroll offsets, and nested logs retain their read position.
- Reactions now share the bottom Copy/Speech/Retry row. One trigger targets the
  last visible non-note answer using its original server text-part index.
  Notes and suppressed image parts do not shift that address; older badges
  retain per-bubble removal. Persistence, emoji flight and eligibility remain.
- Independent supported maximum-effort review fixed source intrinsic-width
  overflow, manual fold focus loss, and touch reaction clipping beside a large
  Workbench. Touch menus retain four columns and complete 44px targets.
  Native reduced motion settles height directly, including preference changes;
  temporary global CSS transitions are distinguished from actual height motion.
  Fable is unavailable in this runtime; native fallback review is recorded.
- Validation: 184 clean-source frontend tests, TypeScript and guarded isolated
  build. Controlled development and production-preview fixtures cover long-log
  scrolling, reverse/reopen, preserved nested state, attached blur, long source
  title bounds, keyboard/focus, resize, native reduced motion, both locales,
  mobile widths, source links, exact reaction POST indices and emoji destination.
  All browser writes use fixtures; no real chat, reaction or speech writes occur.
- The shared-tree Python run encounters unrelated backend work in progress.
  Clean committed backend with a nonsecret synthetic Clerk issuer had 1057
  passing tests, 8 skipped and 178 subtests; two process-cleanup timing failures
  both passed their targeted rerun. No Python implementation is changed here.
- Post-review HTTP smoke checks 7 endpoints and every release resource,
  including both memory/workspace traversal guards returning 400. Test servers
  and browser sessions are stopped; unrelated source drafts are preserved.

- Final live verification passed disclosure and reaction browser fixtures,
  7 HTTP endpoints and all 301 release resources. The guarded integrated
  settings publication includes both feature commits, matches current source
  and is pushed to origin. No older captured build was republished.

## Phone pairing settings follow-up (2026-10-03)

Phone pairing and linked-phone revocation now live in Settings / Devices, with
English/Ukrainian labels and searchable phone/QR terms. Pairing failures identify
outdated backend routes, sign-in, operator access, invalid origins, or transport
errors. Device-list polling errors remain separate from successful QR creation.
The reported failure was a running pre-mobile backend returning 404; the refreshed
local server exposes the mobile routes and live QR issuance returns HTTP 200.
Scoped dashboard typecheck, 188 unit tests and production build passed. A real
browser verified QR rendering and the Devices destination, plus English/Ukrainian
search; no authentication gate was relaxed.

## Mobile public API connectivity (2026-10-03)

The phone connection failure was DNS NXDOMAIN for `api-bot.waveio.me`. Added
its CNAME to the existing `klodbot` Cloudflare Tunnel and an API ingress rule to
the same backend at `127.0.0.1:8100`, preserving the API Host header. Validated
rules, rolled the persistent tunnel service through a temporary replica, and
verified public HTTPS reaches the required-device-token 401 response. Existing
web routing and authentication gates are unchanged. Negative DNS caches may
outlast the record creation; use a fresh PC Settings / Devices pairing QR.
Operational details and verification boundaries: `docs/mobile-app/TUNNEL.md`.


## Wallpaper sources and ready media (2026-10-03)

- Settings > Appearance now separates Ready backgrounds, My files and Link.
  Browsing a source does not change the active wallpaper. Existing local
  images, IndexedDB videos, placements and glass preferences remain saved.
- Four bundled choices add coast and misty-forest photos plus muted looping
  clouds and starry-sky videos. Gallery previews are still JPEGs; the app
  retains one shared video player. Source/license records and dimensions
  live in dashboard/src/panels/chat/assets/wallpapers/README.md. Videos have
  no audio; matching first/last frames and offline MP4 caching are not claimed.
- Direct HTTP(S) photo/video links are checked in the browser before Apply.
  Unsupported schemes, credentials, invalid content and ten-second timeouts
  preserve the prior wallpaper with localized feedback. Checks cancel on
  unmount or saved-source changes. There is no backend proxy or upload.
  Paused remote videos use the bundled sky still; CSS URLs are escaped.
- Validation: isolated production build and 198 frontend tests passed;
  TypeScript passed; the full Python suite passed 1,130 tests, with 6 skipped
  and 178 subtests. New source and existing appearance browser fixtures
  passed, including real media decoding, failed links, persistence, keyboard
  access, both locales, 320/390px layouts and no backend writes. Smoke checked
  seven endpoints, all 307 release resources and both traversal guards (400).
- An independent native reviewer at maximum effort fixed source-check
  lifecycle, still fallback and URL escaping, then repeated the browser suite.
  Fable was unavailable; no Fable review is claimed. External media may change
  after a successful check; physical phones and Raspberry Pi were not tested.


## Chat file drag and drop (2026-10-03)

- Dropping OS files anywhere in the conversation adds them to the existing
  draft attachment flow. Protected file drags show a localized, inert drop
  invitation; nested movement keeps it stable. Leaving/cancelling or changing
  the composer clears feedback. Drops never automatically send a message.
- Composer registers its latest handler locally and reuses authenticated
  upload validation, the eight-file cap, upload serialization and stale-session
  checks. Text/URL drags, directory entries and portal/sidebar targets cannot
  become chat uploads. Unclaimed file drops cannot replace the browser tab;
  target widgets that already accept drops and other routes remain unchanged.
- Separate maximum-effort review passed real CDP file bytes, directory helper
  probes, focus/layout/320px feedback, outside-drop compatibility and cleanup.
  The full mocked browser fixture passed previews/order/errors/cap/busy/session
  isolation in EN/light desktop and UK/dark/reduced-motion phone layouts.
- Validation: 191 frontend tests, TypeScript/guarded build and 44 backend
  upload tests passed. Full Python: 1,129 passed, 6 skipped, one unrelated
  existing clock-boundary test failed comparing two generated timestamps one
  second apart; its isolated rerun passed. No Python change was made.
  Follow-up: freeze time in that prompt-join test in a separate task.
- Unrelated owner drafts and staging are preserved.


## Verified file-drop release (2026-10-03)

- Published source 7bb73b0 through the guarded publisher. The complete
  file-drop fixture passed against /dash/ on actual port 8100, including
  multipart bytes, previews, validation feedback, cap, ordering, cancellation,
  concurrent rejection and late-upload session isolation. All writes were mocks.
- Status, screen, dashboard and referenced assets returned 200; memory
  traversal returned 400. Temporary preview/smoke processes were stopped.
  Source and generated release are pushed; owner drafts/staging remain intact.

## Mobile 0.2.0 custom UI and live replies (2026-10-04)

Replaced the visible stock controls with custom model/effort panels, opaque
bubbles, attachment tiles, message actions, settings and a schedule picker.
The chat slides above a revealed menu; ASR partials render in its glowing
composer. Real installed skills insert references into the draft. Manrope and
a Lora-derived greeting font are bundled with license notices.

Mobile now consumes genuine replaceable gateway answer snapshots before HTTP
finalization, suppresses echoed prefixes, and keeps authoritative final text.
Stream errors/early EOF retain failure state and pause the queue. Stale list
responses cannot clear newer streamed activity; terminal history races and
cancelled dictation callbacks are covered. Blank host selections now resolve
the advertised default, and Off remains distinct from inherited effort.

Verification: 85 shared tests, 62 Android unit tests, 44 platform instrumentation
checks and seven Compose/controller flows passed. The final APK was built from
an immutable source snapshot. A paced 176-frame host-GPU emulator sample measured
median 17 ms / p95 20 ms, matching the old overlay's measured timing after cached
wallpaper layers and reused transition effects replaced moving blur work.
Focused streaming tests passed (152 tests plus 32 subtests); the broader backend
run is explicitly incomplete. Its one issuer-configuration failure passed when
rerun with a synthetic issuer, with zero network calls. See
`docs/mobile-app/RELEASE-0.2.md` for exact scope and remaining iOS/push/Steer limits.

The registered backend was restarted after confirming no active mobile jobs;
new streaming/skills code is loaded and public API authentication remains intact.

## Mobile 0.2.1 adaptive visual QA (2026-10-04)

Preserved the approved 0.2.0 custom appearance and corrected measured layout
failures: model results hidden by the keyboard, unreachable QR pairing on large
text, clipped calendar digits/time controls, missing wallpaper reset action, and
short-window attachment/composer controls. Android system icons now follow the
app surface instead of remaining white on plain light screens.

Added six regression scenarios and a reproducible emulator-only display matrix:
31 UI executions passed across 411x914dp/100%, 360x640dp/160%, 320x568dp/200%, and
640x360dp/100%. The existing seven UI scenarios remain. Shared tests passed 85/85;
Android unit tests passed 62/62. Screenshots checked independently of semantic
assertions; no exact Figma/pixel-diff score is claimed. See
`docs/mobile-app/UI-QA.md` for commands, evidence scope and limitations.

Android version code 3 / version 0.2.1 retains the previous signing identity.
iOS metadata is aligned, but Apple compilation/runtime remains unverified.
All UI host traffic was fixture-backed; production services and Cloudflare
configuration were unchanged. The earlier incomplete broad backend run remains
incomplete and is not counted as passing by this UI follow-up.


## 2026-10-04 — Queue and event-delivery audit

- Added `reports/repository-audit-2026-10-04/REPORT.md`, companion validation,
  and isolated replay probes for five queue/storage/event findings. Proposed
  fixes and five additions are recorded; product code is unchanged.
- Working-checkout validation: 1,189 Python tests passed, 7 skipped and 178
  subtests; 207 dashboard tests and TypeScript passed. Offline mobile unit
  tasks passed 85 shared and 62 Android tests; no device/iOS certification.
- See `VALIDATION.md` for independent review, smoke, and evidence limits.

## Chat markdown tables stay readable (2026-10-04)

- Fixed long assistant tables that showed an ellipsis with no way to read the
  hidden cell value. Markdown table cells now wrap their complete content;
  wide tables retain an inner horizontal scroll surface on narrow screens.
  Page-level horizontal overflow remains disabled.
- Added `tests/markdown-table.browser.mjs`, which sends a real fixture reply
  through the chat renderer and checks full text, wrapped cells, table scrolling,
  phone fit and local-only writes.
- Validation: 207 dashboard tests, TypeScript, whitespace checks and the new
  browser fixture passed. The focused change is limited to the shared table
  presentation CSS and its browser regression fixture.

## Mobile 0.3.0 streaming and interaction follow-up (2026-10-04)

Fixed discarded ordinary native gateway assistant events, HTTP/snapshot echo
ordering, inherited image routing and explicit capability errors. The actual
upload path was accepting files; its model eligibility check caused the reported
image failures. Added persisted human/bot reactions, image previews, day-grouped
history, guarded rename/delete, full readable tables, keyboard-preserving custom
menus, wider drawer gestures, blur/motion and measured-growth scroll following.
Removed native press rectangles and chat overscroll stretching. Auto-follow now
respects finger ownership, survives cancelled scrolls and rebinds per conversation.

Verification: 131 shared tests, 62 Android unit tests, 62 focused backend tests,
23 full phone UI scenarios and 18 adaptive viewport/font scenario executions,
plus five QA-runner tests passed. Real loopback HTTP/WS fixtures prove output
before completion and accepted image bytes; there were no paid provider calls.
Benchmark UI verification is non-debuggable without R8; the separately built
optimized release is installed and launch-tested. iOS remains runtime-unverified.
See `docs/mobile-app/RELEASE-0.3.md` for commands and exact limits.

The registered host was restarted only after confirming zero active mobile jobs.
Tunnel configuration was unchanged. Public OkHttp-like requests receive the
expected JSON authentication response. The configured image model is present,
available and marked vision-capable in the live catalog. Android version code4 /
version0.3.0 uses the previous signing identity. AGP is now8.13.2 for Kotlin2.3 R8
compatibility; the release uses code/resource shrinking. No full-backend-suite
pass is claimed; the prior interrupted broad run remains separate.

## Activity branch arrival motion (2026-10-04)

- New tool/search branches now enter with a small spring lift and a short
  accent glint under the row. Up to six adjacent branches stagger by 35 ms;
  stable step IDs prevent progress snapshots from replaying the arrival.
- The glint uses only opacity and transform, remains decorative for screen
  readers, and is removed with the existing reduced-motion media rule.
- Validation: TypeScript, whitespace checks and the full activity-tree browser
  fixture passed across desktop/phone, English/Ukrainian, reduced motion and
  reduced transparency. No chat writes or external source requests escaped
  the fixture.

## Mobile image dispatch matches the web client (2026-10-04)

The 0.3.0 mobile image gate incorrectly treated missing catalog `vision` metadata
as lack of image support. All three live Sol entries lacked this optional badge.
The phone now delegates image turns to the same `brains.chat_openclaw` image path
as the web: the configured gateway image model is used, or the gateway chooses
when unset. Selected text models, filtered catalog membership and vision badges
cannot block this path. Image fallback stays owned by the shared gateway, and
its actual model report is retained. Text-only mobile routing is unchanged.

The image path takes precedence over request-local text overrides. Mobile
thinking-level preferences remain session-local without pinning a text model.
This supersedes the image eligibility policy recorded for 0.3.0. No APK update is
needed; old failed image jobs can be retried through the installed app.

Validation: 103 focused Python tests passed, including 15 independent parity
regressions comparing web/mobile HTTP headers and full image payloads, configured
and unset image models, missing/false metadata, model receipts and failure cleanup.
Actual loopback transport tests also passed. No paid/live provider request was
made. The registered host was restarted after confirming zero running/stopping
mobile jobs; tunnel configuration was unchanged. Change: `381e495`.

## Mobile 0.3.1 bubble sizing and history edges (2026-10-04)

Assistant Markdown no longer fills the entire row for short replies. History
now occupies the complete safe viewport behind overlaid header/composer panels,
with measured content padding and content-only edge fading. Covered panels
shield underlying message taps. Existing gestures and note/table following are
preserved. No backend or provider-routing changes belong to this UI patch.

Validation: 14 existing UI scenarios and 11 interaction cases passed, including
new bubble/text geometry and panel/composer-growth tests. Actual dark-theme
screenshots were inspected. The streaming emulator sample remained p95 23ms;
this is not a physical-phone/120Hz claim. See `docs/mobile-app/RELEASE-0.3.1.md`
for exact verification boundaries. Android version code5/version0.3.1 retains
the prior signing identity; Apple metadata is aligned but runtime unverified.

## Mobile 0.3.2 late, gentle panel shading (2026-10-04)

Replaced the conspicuous early alpha fade with weak smootherstep shading and
narrow softening directly under panel controls. On supported renderers,
source-atop blending preserves the original bubble alpha; no broad washed-out
band erases readable text. One content recording feeds only small panel blur
buffers. History now reaches the physical window edges, while controls retain
system-bar and IME safe insets; the clipping line below the clock is removed.

Validation: 25 UI scenarios and 18 adaptive executions passed; actual screenshots
were inspected and independent compositing/inset review completed. Updated the
window-bound geometry assertion and let native IME animation settle before one
fixture Send tap. The optimized release is separately installed and launch-tested.
Version code6/version0.3.2 keeps the earlier signing identity. No backend changes;
iOS runtime and physical-phone refresh-rate guarantees remain unverified.
See `docs/mobile-app/RELEASE-0.3.2.md` for the exact scope.


## Staged activity branch reveal (2026-10-04)

- Live newcomers now reveal in three phases: the connector grows downward,
  normalized Lucide strokes draw the icon, then the description and follow-up
  text fade in. Each phase is bounded; adjacent starts stagger by at most
  125 ms. The backdrop stays stationary and no filter animates on the text.
- Persistent text wrappers keep late metadata, sources, errors and logs on
  the existing clock. Progress preserves animation identity; a late change
  from a plain row to a log button settles immediately instead of replaying.
  Restored history and closed groups skip entry. Closing, settlement and a
  native reduced-motion preference end the sequence permanently for that row.
- Validation: TypeScript and 208 dashboard unit tests passed; the offline
  Python suite passed 1,227 tests, with 7 skipped and 178 subtests. Browser coverage
  samples connector scale, SVG dash offsets and text opacity at intermediate
  frames, tests delayed metadata/log controls, progress identity and history,
  and exercises desktop/390/320px layouts, both locales and reduced preferences.
  Independent native adversarial review repaired the two late-data edge cases;
  Fable was unavailable and no Fable certification is claimed. SVG drawing
  gates live on each primitive so Chromium releases the effects on cleanup.
- Release build note: the committed Settings panel imports the existing
  untracked IntegrationsSection.tsx and locales/integrations.ts drafts. The
  isolated build freezes those required files without changing or staging them;
  a clean checkout still requires that separate integration work to be finished.


## Solar activity icons by 480 Design (2026-10-04)

- The activity tree now uses 18 genuine Solar Linear glyphs for tool calls,
  statuses and expand controls. Read/write/search imagery is distinct; tool
  IDs, labels and outcomes retain their meaning. Unknown tools use Settings.
- A local subset preserves upstream geometry at revision
  44017167688b49109d88ae6a98979b23d8950db0. Original SVGs and checksums live in
  dashboard/src/vendor/solar-icons/. The installed renderer is reused; no
  Solar wrapper dependency or runtime icon requests are added.
- The staged connector/icon/text animation and native reduced-motion fallback
  remain. Filled question/warning dots use currentColor without inherited
  stroke and stay out of dash drawing. All stroke gates still reset directly.
- Settings > Appearance carries localized Solar/480 Design credit and a local
  CC BY 4.0 notice with source, pinned revision and adaptation details. The
  PWA precaches the notice. Raw artwork licensing is separate from code.
- Verification includes pinned upstream byte/geometry review, rendering and
  attribution unit tests, TypeScript, the activity-tree browser regression
  and publication smoke. Independent native review found no blocking defect;
  Fable is unavailable, so no Fable certification is claimed.
- The first working-checkout Python run stalled at 15% and was terminated;
  it is not counted as passing. A clean-source, isolated-state rerun follows.


## Solar icon release verification (2026-10-04)

- Published the reviewed Solar activity subset. All 211 dashboard tests,
  TypeScript and the isolated production build passed. The full activity-tree
  fixture passed against both source and the published /dash build, including
  actual Solar identity, sampled stroke animation, cleanup and reduced motion.
- Localized attribution fits 320px in both languages and retains keyboard
  access. All 308 release resources and seven endpoints passed smoke; both
  traversal guards returned 400. The local Solar notice is served and precached.
- The clean-source Python run passed 1,241 tests with 8 skipped and 178
  subtests. Its single missing-issuer fixture failure passed a focused retry
  with a synthetic Clerk issuer; this is not an unqualified full-suite pass.
  The earlier working-checkout stalled run remains excluded from pass counts.

## Mobile 0.4.0 chat motion and media delivery (2026-10-04)

The Latest control is a glass arrow based on the rendered tail above the
composer; it smoothly reaches tall final replies and respects reading flings.
Stationary gesture coordinates enable short fast drawer swipes. Message entrance
identity survives server reconciliation; waiting dots appear after 850 ms without
holding real streamed output. Submit acknowledgment/outbox semantics are unchanged.

Native multi-pick, compact galleries, paged previews and original-byte Save/Share
are wired through the existing host. Completed workspace tools expose real files;
active writes remain unread. Large images use bounded thumbnails, decoded tiles
survive cache eviction without download loops, and navigation/account generations
reset view-owned media state. Exports retain the selected file, not preview text.
The new authenticated workspace download route uses descriptor-relative no-follow
reads and a 20 MiB bound. The registered web host was restarted with zero active
jobs; local capabilities returned 200 and public unauthenticated download 401.

Validation: 154 shared and 78 native unit tests, 36 phone UI scenarios, 18 existing
adaptive executions, four additional adaptive gallery/file scenarios, five QA
runner tests, 34 focused Python tests and a real TCP smoke passed. Independent
review findings were repaired. No paid bot-provider calls or production messages
were sent. The optimized Android APK is version 0.4.0/code 7 with the prior signing
identity; iOS source metadata is aligned but its framework/runtime remain untested.
See docs/mobile-app/RELEASE-0.4.0.md for scope and test/build distinctions.


## Solar icons throughout the dashboard (2026-10-04)

- All owned generic dashboard glyphs now use Solar Linear by 480 Design:
  navigation, chat controls, settings, files, editor tools and status controls.
  135 compatibility aliases use 119 pinned originals and documented genuine
  primitive composites. Existing names/classes, sizes, refs and accessibility
  labels remain compatible; explicit color props now color the actual geometry.
- ReactBits keeps its 39 raw tuple exports. Path-only consumers retain path
  data, filled outlines retain their source attributes, and animated send/stop,
  checks, bell and spinner now show genuine Solar geometry. Send/stop safely
  crossfades rather than interpolating incompatible original shapes.
- Brand/model logos, pixel bot, favicons and plotted/progress artwork keep their
  identities. Excalidraw's internal tool UI remains supplied by that editor;
  the surrounding dashboard editor controls use Solar.
- Validation: 215 dashboard tests, TypeScript and an isolated build passed.
  The isolated Python suite passed 1,242 tests, 8 skipped and 178 subtests.
  Broad browser coverage
  observed 2,030 Solar instances across 14 routes and 12 Settings tabs, both
  locales at desktop/phone widths, explicit color, keyboard access and layout.
  Phone navigation, model/effort and activity motion regression fixtures passed.
- Independent native adversarial review verified all mappings and geometry,
  preserved raw APIs/ref behavior, and found/fixed the color-prop regression.
  Fable was unavailable; no Fable certification is claimed. Existing owner
  FilesPanel drafts and untracked integration source remain separate.


## Global Solar release verification (2026-10-04)

- Published the global Solar migration from the guarded isolated snapshot.
  Broad route/Settings/editor checks passed on the compiled /dash build, with
  2,027 Solar observations; source checks additionally covered explicit-color
  rendering. The complete activity stroke/reveal regression passed live.
- All 296 candidate resources and seven endpoints passed smoke. The Solar
  attribution notice is served and precached; both traversal guards return
  400. Source and compiled artifacts are pushed with owner attribution.
- Owned source imports are committed as import-only deltas. Preexisting
  FilesPanel, shared documentation and integration drafts remain intact.


## Circular send and attachment trigger (2026-10-04)

- The chat send/Stop control now has an opaque circular background: muted
  surface for an empty draft and theme ink for ready/streaming states. Theme
  text/background inversion keeps contrast independent of chat accent choices.
- The attachment trigger now uses the existing Solar paperclip instead of a
  rotated plus. The menu callback, localized label, focus/expanded state and
  upload flow are retained; desktop targets stay compact and phones keep 44px.
- Added tests/composer-buttons.browser.mjs for idle/ready/Stop fill, paperclip,
  one local send, cancellation, keyboard menu/focus, both themes/locales,
  desktop and phone fit. Native adversarial source review found no blocker;
  Fable is unavailable and no Fable certification is claimed.
- Validation passed: 215 dashboard tests, TypeScript, isolated production
  build and the composer browser fixture. The isolated backend suite passed
  1,242 tests, 8 skipped and 178 subtests. Six idle/ready/Stop screenshots were
  inspected across desktop light and phone dark appearances.


## Composer button release verification (2026-10-04)

- Published the circular send/Stop and Solar paperclip composer controls.
  The focused browser fixture passed against the compiled /dash release,
  including one send, stop abort, empty draft and attachment keyboard focus.
- All 296 release resources and seven endpoints passed smoke; both traversal
  guards returned 400. Source and artifacts are pushed; existing owner drafts
  and services remain separate from this UI change.
## Mobile 0.4.1 model, motion and device metadata (2026-10-04)

Explicit mobile model selections now remain authoritative for image turns, while
inherited blank image intent continues through the gateway configured image route.
This prevents a selected GPT-6 Sol turn from silently switching to another image
model. The model picker is edge-to-edge without the old side frame, its arrow
rotates with open state, and picker entrance blur is disabled to avoid Android 16
scroll flicker. Sent bubbles rise from a small blurred seed and expand into place;
streaming bubbles retain a restrained live blur; reactions render below bubbles and
keep the conversation tail visible. Full-screen wallpaper is the default for new
preferences, and profile toggles animate track and thumb colors.

Android/iOS pairing now sends a human-readable device model and OS label. The
owner dashboard lists platform, QR pairing method, connection date and expiry
using the existing device records. Backend image routing, dashboard metadata and
mobile UI tests were run with fixtures; no provider calls or production messages
were used. Android 0.4.1/code 8 release APK was cold-launched after installation
with no crash entries. iOS source changes remain uncompiled on this Mac.


## Chat microphone stop and ASR lifecycle (2026-10-04)

- Second-press stop intent survives pending microphone permission/setup; both
  early and ready-recording presses finish through final recorder events and
  insert recognized text into the draft. The first focused fix is f5e6a87e.
- Generation guards and teardown prevent old microphone, partial and final
  ASR callbacks from reaching a replaced composer. Leaving chat or starting
  a new conversation releases recorder, tracks, RAF and audio context.
- Recorder/audio setup failures clean up and settle; microphone errors have
  locale keys. Composer observes error state after rendering, so ASR failures
  are visible instead of disappearing through an outdated async closure.
- Independent review and mocked-media browser checks cover early/normal stop,
  final audio event ordering, ASR error feedback, delayed permission after
  navigation, active recording on New, and AudioContext setup failure. No
  actual microphone or paid transcription was used for testing.
- Validation: 215 frontend tests, TypeScript and guarded production build;
  1,242 Python tests passed, 6 skipped and 178 subtests passed.
- The clean build requires the existing local integrations component/locale
  modules because committed Settings imports them. Their deployed UI was
  verified unchanged; they are build support only and are not staged here.
  Unrelated owner edits and staging remain intact.


## Cleaner attachment action menu (2026-10-04)

- The paperclip menu uses a quieter one-line header, borderless media tray,
  narrow single surface and icon/label actions without repeated chevrons.
  Desktop photos/files share one row; phones retain camera/photos/files.
  Solar artwork, all actions, context details and selected popup glass remain.
- The menu fits above/below the composer when possible, and inside the visible
  viewport when cramped. It refits during composer movement, viewport pan and
  resize, keeping keyboard focus visible. Pointer versus keyboard entry is
  captured from actual activation, so a previous keyboard focus cannot leave
  an unwanted ring on pointer opening. Compact resize retains keyboard entry.
- Desktop Panels now reveals the right column and closes Workbench; narrow
  layouts keep their dialog. Focus returns to the paperclip before nonmodal
  activation. Existing file inputs remain mounted and picker contracts stay.
- Validation: 215 dashboard tests and TypeScript passed. The isolated backend
  run passed 1,240 tests, 8 skipped and 178 subtests. New browser coverage passed
  all locale/theme/1440/390/320 combinations, all actions/mock upload, keyboard,
  pointer, resize/spring/short-window/tall-draft fit, glass/reduced motion and
  scoped accessibility. Composer and popup glass regressions passed.
- Independent native source/visual review repaired focus/placement edge cases
  and approved the final result. Fable is unavailable; no Fable certification
  is claimed. Unrelated microphone and file-editor drafts are kept separate.


## Chat dictation release verification (2026-10-04)

- The compiled /dash regression passed on port 8100: early and normal
  second-press completion, draft insertion, ASR error feedback, navigation
  cleanup and AudioContext failure cleanup. Media/ASR remained browser fakes.
- The source fix is 9bad2a30. The subsequent attachment-menu publication
  inherits it; retained hashed ASR assets keep already-open tabs functional.
- Fresh-server smoke returned 200 for status, ASR status, screen, dashboard
  and referenced JS/CSS. The memory parent-path guard returned 400. The
  temporary smoke server was stopped; existing owner services stayed running.


## Attachment menu release verification (2026-10-04)

- Published the attachment menu dashboard from source revision `fabc3a38`.
  The compiled browser fixture passed en/uk, light/dark and 1440/390/320px,
  all actions and mock upload, keyboard/pointer focus, cramped/tall-draft
  placement, selected glass, reduced motion and scoped accessibility.
- The fixture now waits for Workbench's required close-focus restoration
  before reopening. Twelve focused repetitions confirmed the runtime
  behavior; final compiled coverage passed after that readiness assertion.
- Smoke verification passed seven endpoints and all 296 release resources.
  Memory/workspace traversal attempts returned 400; the local reference
  JPEG remains in the service-worker precache. Independent native review
  approved source, fixture and guarded release preservation.

## Activity branch motion refinement (2026-10-04)

- Live activity branches now reveal in a deliberate order: the connector
  stroke and elbow draw first, the Solar tool mark follows, then the label and
  follow-up content arrive in one quick pass with a small blur that resolves
  to sharp text.
- Website evidence gets its own source zone. It grows with a smooth grid-track
  transition, source links enter with the same short lift, and loaded site
  marks fade in without changing the reserved icon size. Restored history rows
  keep their static state; reduced motion disables all of these effects.
- Browser coverage samples the intermediate stroke, icon and blurred-label
  phases, then verifies sharp settled text, source links, reduced motion,
  accessibility and responsive 1440/390/320px layouts. Dashboard typecheck,
  215 unit tests, activity browser coverage and disclosure-motion coverage pass.

## Activity branch motion release verification (2026-10-04)

- Rebuilt and published the dashboard after the activity branch motion change;
  the live build metadata points to the current source revision.
- Activity browser and disclosure-motion coverage passed against the source
  tree and the compiled dashboard, with typecheck and all 215 dashboard unit
  tests passing. The release contains 296 verified dashboard resources and
  retains the existing wallpaper precache and publication lock guarantees.
## Mobile inline reply images (2026-10-04)

Assistant Markdown images, linked images and direct raster URLs now render inside
reply bubbles without opening a browser. Source ranges retain surrounding prose,
complete Markdown references are supported, and unfinished streamed image URLs
wait for their closing syntax. Image nodes in code blocks remain literal examples.
Inline portraits retain their aspect ratio; tapping opens the existing paged
viewer with original-byte Save/Share. Image errors stay local with explicit Retry.

Foreign images use the authenticated `/api/mobile/images/fetch` route. The host
pins public DNS destinations, validates every redirect, forwards no device
credentials, and bounds bytes/types/time. Owned uploads and workspace image links
continue through their own authenticated routes. Navigation/account guards and
bounded caches cover late downloads and preserve current chat state.

Validation: 168 shared tests, 78 Android unit tests, four new inline-image UI
scenarios and four existing image/table/stream regressions passed. Backend checks
passed 102 tests and eight subtests. A live public Wikimedia reference image
returned 9022 original JPEG bytes after adding an identifying User-Agent. Other
provider traffic remained mocked; no paid model calls or production messages.
Independent reviews closed all concrete findings. Version 0.4.2/code 9 is built
with R8 and separately installed/launch-smoked; iOS remains source-only.


## 2026-10-05 — Privacy and operation-lifecycle audit

- Added `reports/repository-audit-2026-10-05/REPORT.md`, validation notes,
  and guarded probes for four reproduced stream/publication/reservation
  findings plus one source-based APK-size contract mismatch.
- Five proposed additions and fix examples are recorded; product code and
  published assets remain unchanged. Deployment/native-runtime limits are
  explicit in `VALIDATION.md`.
- Backend checks passed 1,317 tests, 8 skipped and 178 subtests; 215 dashboard
  tests and TypeScript passed. Mobile unit tasks succeeded from cache.
## Phone pairing codes and agent follow-ups (2026-10-05)

- Dashboard Devices creates both the existing QR and an eight-character,
  copyable `XXXX-XXXX` code. Both expire after five minutes and consume the
  same one-time pairing atomically. Short codes are stored only as hashes;
  a durable host-wide budget permits ten short-code attempts per five minutes,
  with `429 pairing_rate_limited` and `Retry-After`. QR exchange remains
  case-sensitive and available when the short-code budget is exhausted.
- Mobile connection offers Enter connection code, a prefilled HTTPS server,
  localized validation/retry feedback, and Back to QR. Input stays ephemeral.
  Editing or closing the form invalidates late responses, duplicate submit is
  suppressed synchronously, and successful pairing uses existing secure device
  credential storage. The existing QR flow remains covered.
- Android release 0.4.4 / code 11 built from the current absolute mobile source
  snapshot. SHA-256:
  `7c2f15161951f88b64837085865dc77d6e4238f902a08d1c9a828d3ff6a3be95`.
  The optimized APK was installed/launched on the Pixel 8 API 35 emulator;
  the code form opened successfully. iOS version metadata was aligned, but
  iOS compilation, TestFlight and physical-device checks were not performed.
- Validation: 1,350 Python tests passed, 8 skipped, 178 subtests passed in the
  offline run with repository dotenv reads blocked; 69 focused backend tests;
  180 shared and 78 Android unit tests; three new real Compose instrumentation
  cases; 215 dashboard tests, typecheck, isolated production build and the
  expanded mocked browser fixture. Actual loopback HTTP smoke covered issuance,
  typed exchange, authenticated capabilities and device listing using a temporary
  store. No production pairing credential or provider request was used.
- Independent workers reviewed mobile and backend pairing and the follow-up
  index. Dashboard screenshots in both locales and native screenshots were
  inspected. Normal Gitleaks/TruffleHog hooks passed for scoped commits; unrelated
  worktree/index edits were preserved without shared stashing.
- The registered backend was restarted after confirming zero running/stopping
  jobs. Tunnel configuration was untouched. Dashboard source revision
  `e87baa5837c0a0455ff426ac662225cc8675c25f` was published through the guarded
  publisher; the served entry and ten direct assets returned 200. The isolated
  build used committed dashboard sources plus the two pre-existing untracked
  integrations modules already imported by tracked SettingsPanel; those modules
  and other unrelated pending files were not committed by this task.
- Reviewed daily/night reports and added `reports/AGENT-FOLLOWUPS.md` with fix
  evidence, prioritized open bugs and proposed product work. These remaining
  items, including active-stream revocation and the updater size mismatch,
  are recorded follow-ups, not claimed fixed by code pairing.
## Tablet layout and the missing Sol image input (2026-10-05)

- The reported image loss was below the phone transport: a completed job carried
  one JPEG and the requested/effective `openai/gpt-6-sol` model. Its configured
  OpenClaw model entry omitted `input`. The installed gateway defaults that entry
  to text-only and silently drops images before provider serialization. The web
  image path instead used the configured Qwen image model, explaining the
  different results without blaming upload serialization or the Sol selection.
- Corrected only the local GPT Sol input declaration to `["text", "image"]`
  using the installed CLI's dry-run and a separate conditional absent-field
  write, after checking zero running/stopping mobile jobs. Compared other model,
  gateway, agent/default, auth and environment sections before/after: unchanged.
  The gateway logged hot-reload applied at 09:38:46.748 on 2026-10-05, and the
  host model catalog now reports Sol vision support. No provider request,
  credential change, gateway restart or fallback substitution was performed.
- Added twelve source-backed regression cases against the installed Gateway HTTP
  parser, prompt-image loader and Responses serializer. Missing/text-only input
  reproduces the loss; text+image preserves exact PNG/JPEG hashes. These tests
  verify transport preparation, not a paid model's real image interpretation.
  They skip when the installed OpenClaw/Node dependency is unavailable.
- Tablet windows at 840dp and above use a persistent 280dp conversation sidebar.
  The conversation/composer is capped at 760dp, forms at 640dp, and connection
  inputs and popups remain compact. Phone windows retain the reveal drawer and
  edge-to-edge attachment sheet. Window resizing preserves the same composition,
  draft, active conversation and middle-history reading position. Media uses the
  dialog's own constraints and keeps original aspect ratios.
- Broad native validation also caught generated file tiles opening an invisible
  editor state while still in Chat. Restored the shared preview and original-byte
  export; Files-screen editing remains available. Refreshed strict fixture routes
  for the existing intelligence endpoint and used native gesture/IME readiness
  where semantics scrolling or premature physical taps fought real UI behavior.
  Existing assertion coverage and streaming completion gates remain intact.
- Validation: 1,362 Python tests passed, 8 skipped, 178 subtests passed in the
  offline suite; 180 shared and 78 Android unit tests passed; final native runs
  passed all 87 phone cases and five tablet cases. The tablet fixture exercised
  1100dp, 720dp and 390dp host widths on an API 35 emulator. Screenshots were
  inspected. There was no physical tablet/phone or iOS runtime validation.
- Independent workers reviewed the tablet UI, gateway regression evidence,
  generated-file fix and strengthened UI assertions. Scoped commits used the
  required author/committer identity and normal secret-scanning hooks without
  stashing unrelated work. `reports/AGENT-FOLLOWUPS.md` records the cause and a
  separate rejected-edit recovery finding that was not folded into this fix.
- Android 0.4.5 / code 12 was built with R8 from the current isolated source
  snapshot. Final APK SHA-256:
  `24b74af9f205c38069a0b429a5ed1a1092e620cba7cac64985d98f125f7424e8`.
  iOS version metadata was aligned and its project regenerated only. The APK is
  delivered directly; this does not claim automatic update publication or iOS
  distribution. Previously lost images must be resent after the gateway repair.
## Streaming text tail reveal (2026-10-05)

- Replaced the infinite blur applied to an entire live assistant bubble with a
  220ms reveal on freshly appended visible glyphs only. Already-rendered text,
  the bubble surface, previous paragraphs and previous bubbles stay sharp.
  Pauses in provider output go fully idle instead of pulsing. Network events,
  cumulative snapshot replacement, note ordering and stored text are unchanged.
- The effect normally covers at most the last 16 UTF-16 units of the changed
  suffix, expanding a boundary to preserve combining marks, surrogate pairs,
  emoji modifiers, ZWJ sequences and one regional-indicator flag pair. It uses
  measured text paths, including line wraps and RTL layout, and records only
  the suffix crop. Crops above 524,288 RGBA pixels fall back to clear drawing.
- Markdown keeps its annotated text and links. Tail motion applies to prose,
  headings and the final table cell; images and code fences remain clear.
  Removed the renderer's default text-size animation so incoming words are not
  temporarily clipped by an older width/height. Reduced motion and completed
  messages bypass the effect. Human message entrance motion is retained.
- Validation: 183 shared and 78 Android unit tests passed. Three native pixel
  regressions confirm that only tail pixels change, unchanged text and surface
  pixels are identical, wrapped Markdown preserves bold formatting, live text
  stays settled after one second, and reduced/completed text does not pulse.
  These passed both focused and in the final phone run. Final tablet cases also
  passed at 1100dp, 720dp and 390dp host widths.
- The broad phone run completed 90 cases: 89 passed, with one timeout in the
  existing Ukrainian code-form-to-QR test. Its saved screenshot remained on the
  QR screen after a physical tap during native keyboard/layout transition; an
  isolated rerun passed unchanged. This is recorded as a timing limitation of
  the broad run, not claimed as a clean 90/90 run. No unrelated auth code or test
  assertion was changed to hide it.
- An independent reviewer authored the range regressions, found the regional
  indicator over-expansion and requested the crop budget; both were fixed and
  reviewed. Native pixel testing also caught default Markdown size animation
  under reduced motion, leading to the immediate-layout correction. No new
  dependency or user-visible string was introduced.
- Android 0.4.6 / code 13 built with R8 from the current absolute-source isolated
  snapshot. SHA-256:
  `f92833eab42fc8fdeb359842997e04573981ab0cad894c1b74a241add309e5f7`.
  iOS version metadata and its Foundation combining-mark counterpart are source
  aligned; the iOS compilation attempt was blocked because the pinned Kotlin
  Native compiler was not cached for offline mode. No iOS runtime claim is made.
  Backend, tunnel and provider configuration were untouched by this UI change.
## Model image delivery and picker cleanup (2026-10-05)

- Removed the mobile model picker image badge and badge-based image sorting.
  A fixture with `vision=true` now confirms the misleading icon is absent while
  model search, effort selection and image upload remain operational.
- Rechecked actual installed model resolution: seven non-authored GPT models
  already accept images, so their definitions were not rewritten. Luna's missing
  authored input field was corrected from explicit provider evidence. Sol's
  existing repair was preserved. Regolo text-only definitions were not changed.
- A real GPT-6.1 Sol trial exposed an unknown-model failure before inference;
  prior mobile text receipts confirmed fallback to Astra. Registered only that
  missing model from its exact provider-cache metadata and installed adapter
  conventions, including separate 872k maximum/272k runtime context budgets.
  Conditional provider merges and before/after comparisons preserved all other
  configuration. Gateway hot reload applied the corrections.
- Real synthetic recognition now passed on GPT-6 Sol, GPT-6 Luna and GPT-6.1 Sol:
  each read a random six-character code that existed only in the image, recorded
  the requested model, and used zero tools. A further actual mobile PNG upload,
  queued job and SSE completion also passed with GPT-6.1 Sol and unchanged image
  bytes. All owned temporary chats, gateway sessions and image files were
  cleaned up; the completed synthetic queue receipt remains diagnostic history.
  CLI-only incognito/idempotent/policy preflight attempts were rejected before
  any inference; normal temporary sessions used supported API parameters.
- Added a read-only-by-default operator reconciliation tool with exact modality
  evidence, no model-family guesses or automatic discovered-model additions.
  Apply requires the reviewed evidence fingerprint, separate CLI validation,
  a condition covering the full public provider, and preservation checks. A
  separate reviewer found and verified the fix for a provider-routing race;
  58 final synthetic tool tests passed. Usage: docs/mobile-app/MODEL-IMAGE-INPUTS.md.
- Final validation: 1,420 Python tests passed, 8 skipped, 178 subtests passed;
  183 shared and 78 Android unit tests; three targeted native picker/upload
  tests. An earlier full Python run encountered an existing empty-PID-file
  readiness race; its bounded rerun passed, and the final complete run was clean.
  Source changes passed independent review and normal secret-scanning hooks.
- Android release 0.4.7 / code 14 was built with R8 from the current isolated
  snapshot. SHA-256:
  `9866eb294068047765ec2e8ddc3470de7ea0a7839b58b98d0cbf3224bd65ff14`.
  iOS version metadata was aligned; iOS compilation and runtime were not checked.
  This is direct APK delivery, not a claim of automatic update publication.

### Requested GPT Astra verification (2026-10-05)

After the owner clarified GPT rather than Regolo, a real GPT-6 Astra check also
passed through mobile upload, the queued job and SSE completion. It recognized
a fresh image-only code, retained the exact requested model, used no tools, and
preserved upload bytes. The owned chat, gateway session and image were removed.
No Astra configuration change was needed. Live image recognition now covers
Sol, Luna, Astra and GPT-6.1 Sol; it is not a claim about every catalog entry.

## Mobile file editing and built app previews (2026-10-05)

- Owned file/preview/upload links now open inside the app. Markdown is formatted;
  text edits use revision-checked autosave and retain the linked conversation.
  Canonical file identities prevent an older alias save from erasing a newer
  draft opened through Files. Relative links use the server-resolved path.
- HTML and built Vite/React outputs run in an isolated native preview. Build or
  Rebuild with the bot submits a normal queued job, preserving both chat drafts
  and the target chat's model. The backend serves bounded original resources;
  it adds no subprocess or arbitrary command endpoint.
- Preview pages cannot access app credentials, host cookies, external networking,
  nested frames, workers, or browser storage. HTML partial fetches and live HMR
  are unsupported. Source-aligned iOS code remains uncompiled/runtime-unverified.
- Final validation: 1,551 Python tests passed, 8 skipped, 178 subtests passed;
  215 shared and 78 Android JVM tests passed. All 11 native-preview tests passed,
  including a real Vite/React bundle. Both integration cases passed on phone and
  tablet; all five existing tablet cases passed. Screenshots were inspected.
  The wider phone batch was 37/38; its existing note-follow test stopped before
  any submit/stream request, then passed its single unchanged isolated rerun.
  This is not a claim of a clean combined phone run. The new integration fixture
  now supports the tablet sidebar instead of requiring a phone menu button.
- Independent reviews resolved canonical draft recovery races, linked model and
  relative-path selection, reserved session IDs, native renderer termination,
  frame isolation, and failed script/style handling. Final post-review loopback
  smoke passed 31 checks and shut down its temporary server/workspace.
- Backend deployed via its existing launchagent after zero active jobs; readiness
  returned 200 and both new routes were registered. The tunnel was untouched.
- R8 Android release 0.4.8 / code 15 installed and cold-launched successfully,
  showed the pairing screen, and produced no crash-buffer entries. APK is
  2,876,935 bytes, SHA-256
  `4eed9c9be9f642f42abcf31fceb4be227a89d1ee6fc0c0b376373daa03f86bb3`.
  This is direct APK delivery, not automatic update publication. No paid provider
  calls or production chat submissions were made for this task.
- Reproduction, preview limits and detailed evidence:
  `docs/mobile-app/RELEASE-0.4.8.md`.

## 2026-10-05 — Night review: live paired-device revocation

- Revoked or expired phones now lose their already-open job stream after the
  next credential check. Checks run before each replay batch and event, including
  buffered events and the empty local owner. Other devices and accepted jobs
  continue; new revoked-device requests still return 401. Frames already handed
  to the HTTP transport cannot be recalled. Nonmobile policy is unchanged.
- Seven regression cases include real HTTP EOF and surviving-device delivery.
  Final Python suite: 1,559 passed, 7 skipped, 178 subtests; shared/Android JVM
  tests: 231/78; dashboard tests: 215 plus typecheck. An earlier known PID-file
  fixture race passed on isolated rerun and the final full suite was clean.
- Independent code and architecture reviews approved the fix. Post-review
  isolated curl smoke passed 26 checks, including traversal guards returning
  400; test servers stopped and Mac output remains muted. No real devices/jobs
  or provider settings changed. Other agents' staged/native release drafts
  remain preserved. Evidence: `reports/night-agent-device-revocation-2026-10-05.md`.

## Mobile question composer extension (2026-10-06)

`ask_question` now opens immediately inside the bottom composer surface, as the
owner requested, rather than as a centered dialog. Job-scoped native/local tool
SSE already carries the structured arguments, so no server production change or
new global event subscription is required. The panel offers every complete
option label and optional free text, respects its IME-reduced viewport, and
leaves the conversation header visible. Back respects drawer/modal precedence.
Dictation retains its controls until it ends, then the pending question appears.

Answers use a separate queued turn in the originating chat. Composer drafts,
attachments and edit targets survive success, rejection and declined offline
delivery. Questions are isolated by account, session, job and tool call;
replayed starts/completion cannot reopen answered/dismissed calls. Durable
outbox question IDs cover sequential iOS preference writes, while Android's
background acknowledgment atomically retains the handled-question receipt.
Unsupported/malformed tool events do not fabricate a prompt. Failed/stopped
turns clear unanswered questions; multiple calls remain in arrival order.

Validation: 246 shared and 78 Android JVM tests passed, including 15 focused
controller/decoder regressions. Native Compose coverage exercises choices,
custom/IME submission, long labels at 1.8x font scale, bottom-composer placement,
chat isolation, input reset, close/Back and drawer precedence. The native outbox
suite includes missing-receipt/background acknowledgment/restart coverage.
The focused Python activity/streaming suite passed 28 tests, including real
loopback HTTP proof that complete question arguments arrive before provider
completion, plus static-asset 200 and workspace-traversal 400 smoke checks.
Independent adversarial review used an available inherited model because the
installed reviewer model was unavailable; its concrete findings were fixed.
Tests used fake providers and isolated data, with no production messages or
server restart. APK builds retain the existing 0.4.9 metadata; this feature has
not been published as an automatic API update. Other pending mobile/dashboard
work remains outside this commit.

### Android update publication follow-up (2026-10-06)

The owner requested publication through the in-app updater. The question build
is now Android **0.4.10 / version code 17**, superseding the earlier unpublished
0.4.9 package. The optimized APK is staged in ignored
`Virtual Bot/runtime/releases/ClaudeBot-0.4.10.apk`; only Android update fields
were set in the host's ignored dotenv file, preserving other configuration.
Live checks: codes 15/16 see the update, code 17 is current, changelog exists,
and the downloaded package exactly matches its advertised SHA-256 and signing
identity. Public API authentication still returns 401 without a device token;
public dashboard returns 200. Only `me.waveio.klodbot-web` was restarted after
rechecking zero running/stopping mobile jobs. No emulator or provider call was
started. See `docs/mobile-app/RELEASE-0.4.10.md` for artifact details. The earlier
unpublished-update statement applies only to the previous 0.4.9 artifact.

### Mobile duplicate or wrong first answer fix (2026-10-06)

Mobile OpenClaw turns no longer render Gateway assistant snapshots as answer
text. The durable session subscription remains active for tool activity,
pre-answer notes and model/status events, but the current HTTP completion stream
is now the only answer-text source. This prevents a delayed preview from a stale
or parallel run on the same session from appearing first and then being replaced
by the current model's answer. The existing HTTP SSE path still delivers real
provider chunks before completion; no artificial typewriter is involved.

Focused transport, streaming and mobile integration validation passed 47 tests.
The transport regression now feeds misleading native snapshots while the HTTP
run is released independently and asserts no `reply_snapshot` reaches mobile,
while the authoritative HTTP delta and final answer still arrive. An independent
adversarial review found no additional issue in the bounded change. The fix is
backend-only; the previously published Android 0.4.10 package remains usable.

## Isolated Python calculation tool (2026-10-06)

Added `python_calculate` to the local registry and tools-MCP with one shared
schema. Each invocation uses a fresh Pydantic Monty 1.1.0 worker with a 32 MiB
memory limit, three-second feed limit, bounded output and at most two concurrent
workers per server event loop. No host functions, filesystem mounts, environment
or networking are exposed. Numeric Python supports loops, functions, complex
numbers and `math`; NumPy/SymPy are explicitly not advertised. Large results are
formatted and sliced inside the worker before crossing the host boundary.
Cancellation closes the worker scope; errors are returned truthfully.

Validation: 35 calculation, tool-event and tool-passthrough tests passed,
including actual worker execution, sandbox boundaries, limits, cancellation,
integer formatting and schema parity. Independent adversarial review findings
were resolved. Live `tools__python_calculate` allowlist activation and MCP reload
remain part of the coordinated math-feature deployment; registration alone is
not claimed as live model availability. Math rendering is being integrated
separately into the web and mobile clients.


## Mobile startup and parallel preload (2026-10-06)

Android 0.4.11 (version code 18) opens a localized loading surface while restoring
a paired connection and after QR/code exchange. Capabilities, model catalog and
chat history load concurrently; the chat opens only when these reads finish.
Profile/intelligence reads remain optional and approved outbox delivery starts
without waiting for profile data. Foreground refresh also loads chats/models
concurrently, without repeating the initial startup requests.

Persistent failures offer Retry and Disconnect. Credentials remain tied to their
paired server during failed-preload retries, secure-storage failures can recover,
and stale/cancelled work cannot replace a newer connection. Independent
adversarial review fixed those boundary cases; 15 startup regressions cover them.

Validation from an isolated snapshot of the staged change: 260 shared tests and
78 Android unit tests passed; six Android emulator scenarios covered startup,
restoration, localized retry and manual-code pairing. Debug/instrumentation and
optimized release builds passed. APK signature matches the previous distributed
build. A loopback server/curl smoke verified capabilities and queued messages,
401 without credentials, 400 for workspace traversal, and static assets; the
server and instrumentation apps were stopped. Backend: 1590 passed, 6 skipped
and 178 subtests passed with production updater settings cleared for the test
process. Shared iOS source changed, but Apple compilation/runtime is unverified.

The locally built APK is `mobile-app/build/ClaudeBot-0.4.11.apk` (2,877,555 bytes),
SHA-256 `aba90b04a4e66bf2434daf504934b4b310f13b7ea6f22d6e45a1452d11f2a8f5`.
The in-app update channel has not been changed by this task.

## Math rendering in mobile and web replies (2026-10-06)

Added native Compose LaTeX rendering and web remark-math/KaTeX integration while
preserving the existing Markdown pipeline. Inline/display delimiters, fractions,
roots, integrals, matrices, table cells and formula-only links are covered.
Source text remains unchanged for copying/sharing. Code, currency and link
contents are protected; incomplete formulas remain source until closed.
The web closing-delimiter check respects enclosing blockquotes. Large replies
retain bounded reveal work, avoiding repeated full-document parses.

The Python tool's readable label is localized in both clients; chat-channel
instructions explain math delimiters and `python_calculate` usage. The runtime
is numeric Python with `math`, not NumPy/SymPy. See `docs/mobile-app/MATH.md`.

Validation: isolated mobile build on committed startup work passed 270 shared
and 78 Android JVM tests. All five new native math cases passed, including actual
glyph rendering, inline/table placeholders and formula-only link annotations.
Thirty existing native interaction/streaming cases also passed. Two strict
suffix-blur pixel comparisons failed identically (1784/1513 outside-region
pixels) on the private generic AVD with both this branch and unchanged committed
renderer/reveal sources; these are recorded baseline limitations, not claimed
passes or repaired by weakening assertions. Web typecheck/build and the actual
component browser fixture passed; math parser/KaTeX tests passed six cases.
Backend calculation/tool/channel tests passed 39 cases and the real local HTTP
calculation/static/traversal smoke passed. No paid model calls were made.

Independent review fixes: bounded worker-side result formatting, preservation
of currency/indented code/link annotations, and complete/incomplete blockquote
math handling. Android publication and live calculator allowlist activation are
the remaining deployment steps and will be recorded after verification.

### Math deployment completed (2026-10-06)

`tools__python_calculate` is enabled in the live OpenClaw minimal allowlist and
`openclaw mcp reload` succeeded. The actual bot tool API returned the expected
square root, multiplication and factorial without a provider call. The web math
build is published atomically and its public entry assets return 200.

Android **0.4.12 / code 19** is published through the existing updater. Codes
16/17/18 see availability, code 19 is current, and the live download matches the
advertised SHA-256/size. The signing identity matches earlier APKs; optimized
release assembly and installation passed. Web restarts were gated on zero
running/stopping mobile jobs, and the existing tunnel remained running.
The separate math QA AVD and the originally started shared emulator were stopped
once no instrumentation was active. No production chat messages or paid model
calls were sent. Release details are in `docs/mobile-app/RELEASE-0.4.12.md`.


## 2026-10-07 — Search and release-contract audit

- Added `reports/repository-audit-2026-10-07/REPORT.md`, validation notes,
  and guarded probes covering page-fetch bounds/deadlines and mobile release
  metadata, asset naming, and shell-data handling. Signing compatibility is
  a source-based risk; no keys or deployed packages were inspected.
- Six findings include fix examples and acceptance criteria, with five proposed
  additions. Product code and publication state remain unchanged.
- Working-tree checks passed 1,591 Python tests, 8 skipped and 178 subtests;
  221 dashboard tests and TypeScript passed. Independent review/smoke evidence
  and limits are recorded in the companion `VALIDATION.md`.


## Blink product mark (2026-10-07)

- Added the owner's spiral logo as the product identity, with canonical artwork
  and reproducible SVG/native/raster exports in `assets/brand`. The primary
  dashboard is `/dash`, built from `Virtual Bot/dashboard`; the animated robot
  face and provider logos retain their own identities.
- Integrated the mark into dashboard/chat avatars, both landings, platform
  favicons and PWA icons, Compose onboarding/settings, Android/iOS launchers,
  legacy React Native/Capacitor shells and desktop launcher/bundle artwork.
  Text naming remains in the separately running Jude rebrand task.
- Validation: dashboard 221 tests, typecheck and build; landing 8 tests and
  build; 3D landing, setup wizard, display and panel builds; Expo typecheck/web
  export and legacy Android JS export; launcher Go tests and macOS build;
  Android debug/shared/native unit builds and instrumentation compilation.
  NativeLauncherIconTest passed on the isolated Pixel 8 emulator. Browser
  checks covered `/dash` light/dark contrast, 390px layout and landing marks.
  Local API/static smoke returned 200 and invalid note extension returned 400.
- Independent fallback review corrected raster resolution, 8-bit exports,
  adaptive safe zones, writing-indicator selectors and panel favicon paths.
  Fable was unavailable; no Fable review or full iOS runtime test is claimed.
  Runtime dashboard publication follows in a separate build commit.


### Blink runtime publication

The logo build is now served at the active `/dash/` route, including its new
PWA/Apple icons and service-worker precache. Standalone chat/memory panels use
base-aware favicon paths, including when opened without a trailing slash.
All published dashboard assets match the validated artifact; prior hashed
chunks are retained for already-open tabs.

A clean checkout build exposed existing missing tracked sources:
`SettingsPanel.tsx` imports the locally untracked `IntegrationsSection.tsx`
and its locale file. Runtime artifacts were built from the current working
sources, which pass the recorded checks; those unrelated source files were
not added to the logo commits. This clean-checkout limitation remains.


## Active dashboard renamed to Blink (2026-10-07)

- Corrected the missing source-level rename in `/dash`: brand wordmark,
  localized browser title, PWA install name/description, phone-pairing copy,
  error notifications and voice sample now use Blink. Product copy lives in
  the shared en/uk `src/locales/product.ts` catalog used by UI and Vite.
- Existing storage keys, API routes, provider/model names and custom bot
  profiles are preserved. The previously shipped spiral remains unchanged.
- Validation: all 221 dashboard tests, typecheck and isolated production build
  passed. Browser checks cover the brand and Ukrainian/English document titles.
  Independent fallback review found and fixed initial English-title handling;
  Fable is unavailable in this runtime.
- Jude's broader repository/landing/mobile rename is still in progress. This
  local correction publishes the primary dashboard without waiting for that PR.


## Blink landing without the pixel mascot (2026-10-07)

- Removed the pixel-crab hero, footer, marquee separator, device face/mood
  demo, glyph, animation wrapper and Vite import bridge from the primary
  `landing/` site. The Blink spiral is its decorative identity; device tabs
  now show clock, weather and apps. Updated en/uk copy to Blink Bot and
  removed character-specific promises and labels.
- Recaptured the actual dashboard chat, notes, welcome and mobile views
  against a throwaway backend with synthetic demo data. Captures use English
  and close the right panel; the photo pipeline verifies that panel state.
  Both landing locales load the same English full/half-width captures, with
  matching preload paths. Refreshed the social-preview image as well.
- The notes controls now use real en/uk locale keys, so English captures do
  not depend on edited image text. Those dashboard source/runtime changes
  were committed separately. Owner chats and preferences were untouched.
- Validation: 8 landing tests and production build; 221 dashboard tests,
  typecheck and production build; browser checks for en/uk switching, 390px
  layout, mascot absence, English image sources and rapid device-tab changes;
  real static/API smoke and invalid note extension rejected with HTTP 400.
  Independent fallback review corrected logo specificity and English copy;
  Fable was unavailable. Screenshot backend and test browsers were stopped.
  The requested landing preview remains available on port 5199.


## Native Android capture on the landing (2026-10-07)

- Replaced the responsive-dashboard phone picture with a capture of the real
  Kotlin Multiplatform/Compose Android app in `mobile-app/`. It uses English,
  the dark theme and a closed conversation drawer, with synthetic conversation
  data from an instrumentation-only fixture. No real bot or credentials are used.
- Added `landing/scripts/shots/capture-android.sh`: dedicated-emulator guard,
  native app/test builds, instrumentation success check, 1170x2532 capture and
  full/half-width WebP output. Display/font overrides are restored on exit.
  The web screenshot script now handles desktop/device views only and cannot
  overwrite the native phone image. Updated both landing locales and provenance.
- Validation: Android app/test builds and the native capture test passed;
  all 8 landing tests and final production build passed. Inspected the native
  PNG and the rendered 390px landing block, confirmed image dimensions and
  no horizontal overflow. Independent fallback review approved; Fable was
  unavailable. The dedicated emulator and test browser were stopped after QA.


## Local landing preview recovery (2026-10-07)

The temporary Vite development process had stopped; port 5199 had no listener.
The current `landing/dist` build is now served on loopback port 5199 by the
runtime launchd job `com.imlokzu.blink.landing-preview` (`launchctl submit`).
It is independent of the agent terminal; no login LaunchAgent plist was added.
After editing the landing, run `npm run build` in `landing/` to refresh this
preview. Use `launchctl list com.imlokzu.blink.landing-preview` to inspect it
and `launchctl remove com.imlokzu.blink.landing-preview` to stop it.
Browser smoke passed with no JavaScript errors; page, scripts, logo and native
Android screenshot returned HTTP 200. Independent verification confirmed
current asset bytes and the server's launchd parent (PID 1).


## Blink repository, public site and documentation (2026-10-07)

- Renamed GitHub to `Imlokzu/blinkbot` and updated local origin, landing clone
  links/commands, repository homepage and launcher module path. GitHub keeps
  redirects from the old repository address. Primary site: `https://waveio.me`.
- Deployed the static landing to Cloudflare Pages project `blinkbot`, with
  `waveio.me` and `www.waveio.me` both active and serving HTTP 200. Only the
  two website A/CNAME records changed; mail, API and tunnel records were
  preserved. Previous website DNS is saved in agents-vault item
  `Blink - waveio.me DNS rollback`.
- GitHub Actions now tests/builds/deploys landing changes on main. A Pages-only
  account token is stored in vault item `Cloudflare - Blink Pages CI` and
  encrypted repository secrets. No credentials are stored in source. Fixed
  explicit test-file selection for Node 24; run 37681581368 passed all steps.
- Sol agents renamed the root spec, development order and history documents
  to `blink-full-spec-v3.md`, `blink-dev-order.md`, and `blink-history.md`, and
  updated CONTRIBUTING plus current module/product documentation. All local
  links resolve; headings, tables, code fences and historical facts remain.
  Reserved tool filenames and actual module/provider identifiers are unchanged.
- Validation: 8 landing tests/build, launcher Go tests, live public browser
  and asset checks, active custom-domain verification and independent Sol
  documentation review. Voice Loop executable AST is unchanged by the updated
  docstring reference; the 3D config syntax check passed. Fable unavailable.


## Blink module directory names (2026-10-07)

Renamed the tracked top-level folders `claude-bot-app/` to `blink-app/` and
`claude-bot-display/` to `blink-display/`. Updated launcher and display config
paths, frontend auth-config discovery, brand generator/output inventory,
i18n baseline path keys, current docs and architecture diagram references.
All 81 tracked module files were preserved. Package names and installed
Android/iOS identifiers remain stable for compatibility.

Validation: both frontend builds, app typecheck, all 6 display backend tests,
3 auth tests and launcher Go tests passed. Brand output paths resolve and
independent Sol review found no remaining operational old-folder references.
Historical audit reports retain the paths they recorded. Owner work-in-progress
files were preserved; only the owned HANDOFF changes were staged.

## 2026-10-07 — Night review: platform-specific update metadata

- Fixed the beta resolver's Android-only namespace. iOS capabilities now use
  their own versions, URLs, notes, digests and flags; Android defaults and
  download routes retain their existing behavior. Channel-specific and legacy
  settings, explicit empty overrides and informational records remain compatible.
- Nine platform regressions reproduce eight failures before the fix and pass
  afterwards; 35 focused API cases pass. Final offline Python: 1,600 passed,
  eight skipped, 178 subtests. Dashboard: 221 plus typecheck/isolated build;
  landing: eight plus build; display: six; launcher Go tests; mobile JVM: 278/78.
- Independent code and architecture reviews approved the fix. Post-review
  isolated curl smoke passed 35 status checks plus actual platform/version
  comparisons and synthetic download-byte assertions. Invalid tokens returned
  401 and both traversal guards 400. Smoke server stopped; Mac remains muted.
  No release was published or provider/device configuration changed. Existing
  dirty APK-size/native/editor work stays outside this commit. Evidence:
  `reports/night-agent-update-platforms-2026-10-07.md`.
