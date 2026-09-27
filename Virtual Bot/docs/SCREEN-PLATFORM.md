# Екран бота як платформа: магазин, застосунки, скіни, музика

Документ для тих, хто робить **додатки для екрана бота** (2.4" 320×240,
`/screen`). Экран — не просто вивід: у нього є власний магазин, встановлені
застосунки, скіни і Now Playing. Все, що тут описано, працює на реальному
бекуенді і перевірене тестами (`tests/test_screen_store.py`,
`tests/test_music.py`).

---

## 1. Як влаштований екран (30 секунд)

- Сторінка `/screen` — сцена рівно **320×240** (CSS-пікселі), на десктопі
  масштабується, на Raspberry Pi — піксель-в-піксель.
- Навігація як у смартгодинника: карусель тайлів (вліво/вправо), **свайп
  вгору — шухляда застосунків**, вниз — швидкі дії, довгий дотик — теж
  шухляда. Резистивний тач: жодних drag-and-drop, тільки тапи й свайпи.
- Шари поверх: `layerApps` (шухляда), `layerQuick` (шторка), `layerApp`
  (застосунок: камера, сервіси, **магазин**, встановлені додатки).
- Код: `static/screen/screen.js` (усі екрани й застосунки), `screen.css`,
  `pixel-ui.js` (піксельна графіка), `icons.js` (векторні іконки).
  Без збірки — vanilla ES-модулі, бо так їх правиться без toolchain.

Продуктивність (це правила, а не поради): анімуємо тільки `transform` і
`opacity`; canvas замість DOM-анімацій; затемнення — окремий шар з
`opacity`. Ціль — Raspberry Pi 3 на A53.

## 2. Магазин (`Застосунки → Магазин`)

Магазин показує чотири таби:

| Таб | Звідки | Куди встановлюється |
|---|---|---|
| **Додатки** | локальний каталог `store/packages/` | `store/installed/apps/<id>/` |
| **Скіни** | той самий каталог | `store/installed/skins/<id>.json` |
| **Скіли** | ClawHub через OpenClaw (`/api/store`) | `openclaw skills install` |
| **Тулзи** | кураторський MCP-каталог (`/api/store`) | OpenClaw bridge |

Стан встановлення = **файлова система**: пакет встановлений, якщо існує
його тека/файл у `store/installed/`. Жодних реєстрів у коді — видалити
пакет = видалити теку. Логіка: `screen_store.py`.

### 2.1. Формат пакета-застосунка (тип `app`)

```
store/packages/<id>/
├── package.json     # маніфест
└── index.html       # вхідна точка (усе, що потрібно — поруч)
```

`package.json`:

```json
{
  "id": "metronome",
  "type": "app",
  "label": "Метроном",
  "icon": "clock",                 // імʼя іконки з наборів екрана (див. 4c)
  "tint": "#d7a65b",               // колір для старих стилів іконок (опційно)
  "version": "1.0.0",
  "author": "Клод Бот",
  "description": "Одне речення — воно покажеться в каталозі",
  "entry": "index.html"
}
```

Правила:
- `id` — `[a-z0-9][a-z0-9_-]{0,31}`; має збігатися з іменем теки (інакше
  пакет вважається зіпсованим і не показується).
- Пакет копіюється ВЦІЛКО (до 200 файлів) — ассети їдуть разом, застосунок
  мусить працювати без мережі.
- Після встановлення застосунок зʼявляється плиткою в шухляді і відкривається
  в **iframe** `/store-apps/<id>/index.html` (це StaticFiles на
  `store/installed/apps/`, захищений від path traversal).

### 2.2. Що може застосунок всередині iframe

- Середовище — той самий origin: можна `fetch('/api/...')` до бекенда
  (статус, памʼять, музика — див. API нижче). localStorage свій на пакет
  (ключі префіксуй `id пакета.`).
- **Скіни крізь iframe не проходять** (CSS-змінні не успадковуються), тому
  батько шле їх повідомленням після завантаження:

```js
window.addEventListener("message", (e) => {
  if (e.data && e.data.type === "botSkin" && e.data.vars) {
    for (const k in e.data.vars) {
      document.documentElement.style.setProperty(k, e.data.vars[k]);
    }
  }
});
```

  У CSS використовуй змінні з фолбеком: `var(--bg, #16181a)`. У тому ж
  повідомленні приходить `theme` (`"light"`/`"dark"`) — став його в
  `dataset.theme`, інакше застосунок не побачить перемикання теми екрана.
- Використовуй готові стилі-орієнтири з пакетів-зразків
  (`store/packages/metronome`, `store/packages/pixel-paint`) — вони ж і
  шаблони для копіювання.

### 2.3. Обмеження (перевір список перед публікацією)

- [ ] Вміщається в 320×240; скрол — вертикальний, `touch-action: pan-y`.
- [ ] Ніяких зовнішніх CDN (Pi може бути офлайн).
- [ ] Ніяких `alert/confirm` — повідомлення текстом у своєму UI.
- [ ] Анімації — тільки transform/opacity; є режим «мінімальні анімації».
- [ ] Кнопки ≥ 24px, відступи між ними ≥ 6px (резистивний тач промахується).
- [ ] Немає секретів у коді пакета (пакети публічні, в репозиторії).
- [ ] **Іконки — свої векторні, не emoji.** Інлайн-SVG `viewBox="0 0 24 24"`,
      лише обведення `stroke: currentColor`, без заливки — той самий контракт,
      що в `static/screen/icons.js`. Emoji браузер малює КОЛЬОРОВИМИ растровими
      наліпками: вони не успадковують колір теми (у світлій темі лишаються
      темними), не тоншають разом зі `stroke-width`, не масштабуються під
      розмір кнопки. Якщо іконка вже є в `icons.js` — копіюй її шлях ДОСЛІВНО,
      інакше на екрані житимуть дві схожі-але-різні іконки одного й того ж.
      Словник тримай у пакеті (не імпортуй `icons.js`): пакет копіюється в
      `installed/` цілком і не має ламатись від правки модуля екрана.
- [ ] **Світла тема застосована.** Батько шле її разом зі скіном
      (`{type:"botSkin", vars, theme}`), тож потрібні `:root[data-theme="light"]`
      з палітрою і `document.documentElement.dataset.theme = …` у слухачі.
      Без цього застосунок лишається темним на світлому екрані.

Перевіряється тестами: `test_no_emoji_icons_in_ui`,
`test_icons_match_screen_icon_set`, `test_package_supports_light_theme`.

### 2.4. Формат скіна (тип `skin`)

```json
{
  "id": "skin-sunset",
  "type": "skin",
  "label": "Захід",
  "icon": "sun",
  "version": "1.0.0",
  "description": "Теплий вечірній",
  "vars": {
    "--bg": "#2a1d18", "--panel": "#3a2a20", "--line": "#553b2c",
    "--text": "#f5e3d3", "--muted": "#b59a86", "--accent": "#e8825a",
    "--ok": "#a8b879", "--off": "#8a7364"
  }
}
```

Дозволені лише значення `#rrggbb` — список змінних закритий (8 штук),
інакше скін міг би зламати читабельність. «Взяти» в магазині = встановити
й одразу застосувати; «Зняти» — повернути тему за замовчуванням.

### 2.5. Як додати свій пакет у каталог

1. Створи теку `store/packages/<id>/` з `package.json` (+ файлами для app).
2. Перевір локально: `pytest tests/test_screen_store.py` і магазин на
   екрані — пакет зʼявиться в табі одразу (каталог читається з диска на
   кожен запит, перезапуск сервера не потрібен).
3. Закоміть пакет — він у репозиторії, тож у інсталяцій бота зʼявиться сам.

## 3. Відео на екрані: плеєр, адблок і пульт бота

Застосунок `youtube` (пакет у магазині) має два режими: **Звук** — трек іде в
Now Playing і живе далі після закриття застосунку; **Відео** — плеєр на весь
застосунок, з картинкою. Ботом керується саме плеєр відео.

### 3.1. Реклама: чому її тут не видно

Дві різні речі, і плутати їх не варто:

| Що | Як прибрано |
|---|---|
| Реклама, яку вставляє **сам YouTube** (прероли, банери, оверлеї) | Не доходить взагалі: відео йде проксі-потоком `/api/music/video` у звичайний `<video>`, без плеєра YouTube. Блокувати нічого — рекламу просто нікому показати. |
| Реклама, **вклеєна в саме відео** («цей ролик спонсує…») | [SponsorBlock](https://sponsor.ajay.app): плеєр стрибає через розмічені спільнотою сегменти. |

Запит до SponsorBlock **приватний**: беремо не id відео, а перші 4 символи
`sha256(videoID)` — сервер віддає всі відео з таким префіксом (сотні), і своє
ми вибираємо локально. Тобто з запиту не видно, що дивиться людина. Це кілька
десятків кілобайт замість двох, і ця ціна свідома: бот стоїть у когось на
столі й не має зливати історію переглядів третій стороні.

Відкинуто на вході (`sponsorblock.py`): `actionType` крім `skip` (mute/poi/full —
по них автоматично стрибати нельзя, плеєр вилітав би в кінець), сегменти з
негативними голосами (спірні — виріжуть зміст, не рекламу), коротші за
секунду (стрибок читається як заїкання) і перетини (склеюємо, інакше два
стрибки на місці).

Прев'ю теж не тягнуться з Google: `/api/video/thumb?id=` проксіює їх через
бота, тож пристрій не робить жодного запиту до `i.ytimg.com`. Вимикається
перемикачем — тоді `<img>` іде напряму.

### 3.2. Налаштування (одне джерело правди)

`runtime/video-settings.json`, ендпоінти `/api/video/settings`:

```json
{
  "sponsorblock": true,
  "categories": ["sponsor", "selfpromo", "interaction", "music_offtopic"],
  "proxy_thumbnails": true,
  "notify_skips": true
}
```

Категорії: `sponsor`, `selfpromo`, `interaction`, `intro`, `outro`, `preview`,
`filler`, `music_offtopic`. Заставки й титри типово НЕ пропускаємо — люди
часто хочуть бачити інтро каналу, а «пропустив початок» читається як
поламаний плеєр.

Ті самі налаштування читає і екран (⚙ у застосунку), і бот (тул
`video_settings`) — тому перемикач пальцем і фраза «вимкни пропуск реклами»
роблять ОДНЕ І ТЕ САМЕ. Аркуш ⚙ перечитує їх при кожному відкритті: бот міг
перемкнути щось голосом, поки застосунок був відкритий.

Порожній список категорій = «пропускати нічого», тобто той самий вимкнений
адблок, тільно непомітний. Такий стан не приймаємо молча: `sponsorblock`
гаситься явно.

### 3.3. Як команда бота доходить до плеєра

```
ти («перемотай на дві хвилини»)
  → тул video_control                      (tools/video_tools.py)
    → SSE {"type":"video","action":"seek","position":120}
      → screen.js: відкриває застосунок youtube, ЯКЩО він закритий,
        і шле команду в iframe postMessage'ем {type:"botVideo", …}
        → застосунок робить це з <video> і постить стан назад
          POST /api/video/state
```

Три речі, без яких це не працювало б:

- **Батько відкриває застосунок сам.** Інакше «перемотай вперед» діяло б
  тільно тоді, коли людина вже стоїть у потрібному застосунку.
- **Команда, що прийшла до завантаження iframe, чекає в `videoPending`** і
  йде одразу після `load`. Без цього перше ж «покажи відео» відкривало б
  порожній пошук — застосунок ще не встиг підписатися.
- **Зворотний канал стану.** SSE — в одну сторону, тож без `POST
  /api/video/state` бот на «а де ми зупинились?» міг би тільно вигадати.
  Стан має TTL 40 с: екран заснув — і бот чесно каже «нічого не грає»,
  замість описувати минуле. Плеєр звітує і при ВІДКРИТТІ (навіть на паузі,
  коли autoplay заблокований), бо інакше до кінця TTL бот описував би
  попереднє відео як поточне.

### 3.4. Тули мозку (в `/api/tools` зʼявляються самі)

| Тул | Що робить |
|---|---|
| `play_video {query\|url, start?}` | знайти й показати відео з КАРТИНКОЮ; ставить пакет `youtube`, якщо його ще не встановлено |
| `video_control {action, seconds?, position?, rate?}` | `pause`, `resume`, `stop`, `forward`, `back`, `seek`, `restart`, `end`, `speed`, `mute`, `unmute` |
| `video_status {}` | що грає, позиція, скільки лишилось, скільки реклами пропущено |
| `video_settings {…}` | показати/змінити адблок, категорії, проксі прев'ю |

`video_control` розуміє українські синоніми («стоп», «кінець», «спочатку»),
позиції у вигляді «2:30» і «1:05:00», а швидкість підганяє до тієї, що плеєр
справді має кнопкою (просити 1.6 можна, показати — ні).

Крок перемотки за замовчуванням — **симетричний**, `DEFAULT_STEP_S = 10` у
`video_control.py`, і той самий, що на кнопках плеєра (`SEEK_STEP_S` у пакеті).
Тримати їх рівними обов'язково: інакше «перемотай вперед» голосом і тап по
кнопці стрибали б на різне (тест `test_seek_step_matches_player_buttons`).
Асиметрія «назад коротше, вперед довше» виглядає розумно на папері, але дає
пару однакових на вигляд кнопок, які роблять різне. Скільно завгодно бот усе
одно вміє: «перемотай на хвилину» → `seconds=60`.

Якщо екран нічого не грав, `video_control` не бреше «перемотав»: у відповіді
зʼявляється `warning`, і бот мусить сказати, що спершу треба ввімкнути відео.

### 3.5. MCP: той самий пульт для OpenClaw

`tools/registry.py` бачить лише ЛОКАЛЬНИЙ мозок. Коли активний мозок —
OpenClaw, потрібен місток:

```bash
openclaw mcp add youtube --command python3 \
  --arg "$(pwd)/youtube_mcp.py" \
  --env VBOT_URL=http://127.0.0.1:8100
```

`youtube_mcp.py` — stdio-MCP без залежностей (як `emotions_mcp.py`), віддає
ті самі чотири інструменти й проксює їх на `/api/video/*`. Бекенд офлайн →
інструмент повертає `isError` з людським текстом «екран недосяжний», а не
валить зʼєднання: агент мусить це сказати, а не вдавати, що показав.

### 3.6. Якщо змінюєш пакет youtube

`store/installed/apps/youtube/` — це КОПІЯ, зроблена при встановленні. Зміни в
`store/packages/youtube/` самі туди не потрапляють:

```bash
curl -X POST localhost:8100/api/screen-store/install \
  -H 'Content-Type: application/json' -d '{"id":"youtube"}'
```

Потім онови сторінку `/screen` — iframe візьме нову копію.

## 4. Now Playing (музика)

### 4.1. Як це працює

```
ти («Клод, увімкни Crab Rave»)
  → мозок викликає тул play_music        (tools/music_tools.py)
      → yt-dlp шукає трек (неофіційний YouTube, без ключів)
      → SSE подія {"type":"music","action":"play","track":{...}}
          → екран: бар знизу, <audio> грає /api/music/stream
              → бекенд тягне аудіо через Invidious (local=true) і проксіює
                з підтримкою Range — тому перемотка справжня
```

- Бар зʼявляється, лише коли є трек; він замінює крапки-індикатори, тайли
  піднімають вміст (`.stage.np`). Ліва іконка — джерело, тап відкриває
  плеєр: провайдери YouTube/Радіо, перемотка, черга, станції.
- Назва, що не влазить, біжить стрічкою (дві копії тексту + `translateX`
  ключ, швидкість 22 px/с) — «як стрічка над магазином».
- Радіо — живі icecast-потоки (SomaFM, Radio Paradise): перемотки нема,
  повзунок ховається, у списку час показує `LIVE`.
- Поки бот говорить, музика притихає до 25% (ducking) і повертається після.
- Екран «спить» — музика грає далі (радіобудильник).

### 4.2. Чому аудіо через Invidious (і це чесно працює)

Прямі googlevideo-ссилки з багатьох IP віддають 403 (PO-токен-гейт YouTube),
а публічні Invidious-інстанси флапають хвилинами. Тому `music.py`:

1. Збирає кандидатів: інстанси з `config.yaml → music.invidious_instances`
   + автодискаверері з `api.invidious.io` (кеш 24 год) + yt-dlp як запасний.
2. Пробує всіх **паралельно** (1-байтна перевірка «це справді аудіо»),
   до 3 кол з паузами; результат кешується на 30 хв.
3. Стрім проксіюється з передачею Range → `<audio>` перемотує як звичайний
   файл. 5xx/обрив = ретрай з новим кандидатом.

Для реального бота найстабільніше — **свій Invidious** у локальній мережі
(docker, ~200 МБ RAM): впиши його ПЕРШИМ у `music.invidious_instances`
(`http://127.0.0.1:8343` — схема `http://` дозволена тільки для свого).
Публічні інстанси — це «воно працює зараз», не гарантія.

### 4.3. Тули мозку (зʼявляються автоматично в /api/tools)

| Тул | Що робить |
|---|---|
| `play_music {query}` | пошук + увімкнути на екрані; повертає «грає …» і альтернативи |
| `stop_music` | зупинити |
| `listen_to_video {url, lang?}` | транскрайб відео (субтитри) текстом + увімкнути його звук; бот «дивиться» відео через текст |

Транскрайб — `youtube-transcript-api` (безкоштовно, без ключа, автоматичні
субтитри теж). Якщо прямий timedtext закритий для IP — фолбек на
Invidious-капшени (WebVTT парситься своїм кодом).

## 4a. Live tiles, conversation and wake word

### Carousel the owner arranges

Settings → Screens lists every tile with up/down arrows and a show/hide
switch (no drag-and-drop: a resistive panel misreads drags). The face is
always first — it is home. Stored per device in `localStorage`
(`botScreenTiles` = `{order, hidden}`), so a tile added in a later version
appears at the end instead of vanishing. A hidden tile asked for by name
(`open_screen`, the drawer) rejoins the carousel. In code, look tiles up by
`data-tile`, never by index: `tiles` changes at runtime (`chatTile()`).

### Timer tile

State is shared by the bot and the screen (`screen_widgets.py`, stored in
`runtime/screen-widgets.json`):

| Who | How |
|---|---|
| bot | tools `set_timer {hours, minutes, seconds, label}`, `timer_control {action: cancel\|cancel_all\|pause\|resume\|add}`, `timer_status {}` |
| screen | `GET /api/screen/timers`, `POST /api/screen/timers {action: set\|cancel\|pause\|resume\|add, id\|label, seconds}` |
| push | SSE `{"type": "timer", "action", "timer", "timers"}` |

The server only stores when a timer ends; the screen counts down and rings
(a generated chirp — heard even with TTS off — plus the spoken label and a
caption; any touch silences it). One clock, not two.

### Weather tile

`GET /api/screen/weather` (cached 20 min), `POST /api/screen/weather/city`.
Loads only while the tile is on screen. When the bot's `weather` tool runs,
its answer is published as SSE `{"type": "weather"}` and the tile shows it.

The tile follows the One UI look:
- **The whole tile is the sky.** Its colour comes from the WMO `code` and
  `is_day`, and every kind has a darker night version.
- **Drawn pictures** come from `weather-icons.js`: sun, moon, clouds,
  drops, flakes, bolt. The same kit is used at every size.
- **The middle card** shows the next twelve hours in two-hour steps, with
  the chance of rain when it is 30 % or more. A tap flips it to details:
  feels like, humidity, wind with its direction, rain, UV, sunrise and
  sunset.
- **The bottom card** shows five days.
- **A tap on the temperature** fetches a fresh forecast.

The condition is worded by the screen (`wx.c.<kind>` keys). The server's
Ukrainian `condition` string is only a fallback.

`tools/weather.py` answers both readers at once. The brain gets
`condition`, `feels_like`, `hourly` (12 h) and `forecast` (sunrise, UV,
chance of rain). The tile also reads `code` and `is_day` there. The
parsing is the pure `shape_forecast()`, tested in
`tests/test_weather_tool.py`.

### Replies as messenger bubbles

The chat stream carries `delta`, `break` (next bubble), `note` (narration
while working, full snapshot) and `reaction`; `done.bubbles` is the
authority. `static/screen/reply.js` turns that into bubbles without any DOM
(tested through node in `tests/test_screen_js.py`). With voice on, the face
caption follows the voice one message at a time; with voice off it shows the
whole reply. The `reply` SSE event also carries `bubbles`.

### Wake word (`static/screen/wake.js`)

Words are reduced to a rough phonetic key, so recognition spellings of the
name wake the bot — "Клоде" (vocative), "Claude", "клауд", "клот" — while
everyday near-misses ("код", "кіт") and mentions deep in a sentence do not.
The name counts near the start (after fillers like "хей") or as the last
word. "Клод, стоп" silences the voice mid-sentence (the only thing heard
while the bot talks — its own voice must not stop it). After only the name
the bot waits 8 s for the command; after each answer it keeps listening 8 s
without the name, so a back-and-forth does not need "Claude" every time.

## 4a½. Shades: notifications (left) and quick settings (right)

As on Android: swipe down on the **left half** → notification shade, on the
**right half** → quick settings. Swipe up closes either; when the list is
long enough to scroll, the pull handle at the bottom still closes it. A bell
with the unread count sits on the face (red when an error is among them).

The shade shows timers and reminders, then notices. Contract
(`screen_widgets.py`):

| What | How |
|---|---|
| reminders | tool `set_reminder {text, minutes\|hours\|at: "18:30"\|"2026-10-02 09:00"}` — a timer of `kind: "reminder"`, up to 7 days, kept 12 h after it fires |
| system notices | `screen_widgets.notify(key, code=…, params=…, level=…)` — same key = one notice with a `count`; `resolve(key)` removes it once fixed. Raised today for `brainOffline`, `ttsFallback` (`reason: quota\|error`), `ttsFail`, `asrFail` |
| the bot's notices | tool `post_notification {title, text, level}` |
| screen-only | `linkLost` — with the link down the server cannot say so itself |
| API | `GET /api/screen/notices`, `POST /api/screen/notices/dismiss {id\|"all"}`, SSE `{"type": "notice", "notices"}` |

System notices carry `code` + `params` and the screen words them through
`notice.<code>` / `notice.<code>.<reason|body>` keys in `i18n.js`, so a
new code needs its two keys in both languages.

## 4b. On-screen keyboard (`keyboard.js`)

Voice cannot do everything: a search query, a name, a word recognition
keeps mishearing. So there is a keyboard — small, because the screen is:
keys are ~24×28 px at 320×240, twelve across.

- Layouts: `uk`, `en`, `sym`. The Ukrainian apostrophe (ʼ, U+02BC — the
  letter the rest of the project uses) has its own key; ґ hides on a long
  press of г. Double space ends a sentence (". ").
- Shift is one-shot; tapping it twice is caps lock; it arms itself at the
  start of a sentence while `autocap` is on (off for search fields).
- Opening: mode in **Settings → Behaviour** — `auto` (touch panels only,
  on a desktop with a real keyboard it would only get in the way),
  `always`, `off`. The chat has a keyboard button in every mode.
- **Chat**: typed messages are sent with `voice: false`, so the brain does
  not expect ASR mistakes and does not apply the ASR caveat.
- **Catalogue apps** (same origin): the parent watches `focusin` inside the
  iframe and types straight into the field — `input` events on every key,
  a real `keydown` Enter on done, plus `form.requestSubmit()` when there is
  one. No package has to change. It also sets `inputmode="none"` in auto
  mode, so a tablet does not show a second, system keyboard.
- **Sandboxed apps** (imported `.cbp`, opaque origin) ask the parent:

```js
// app → parent
parent.postMessage({type: "botKeyboard", action: "open",
                    value: "…", placeholder: "…", enter: "search"}, "*");
parent.postMessage({type: "botKeyboard", action: "close"}, "*");
// parent → app
window.addEventListener("message", (e) => {
  const d = e.data || {};
  if (d.type !== "botKeyboardInput") return;
  if (d.cancelled) return;                  // the person closed it
  input.value = d.value;                    // live on every key
  if (d.done) search();                     // Enter pressed
});
```

  The logic (`LAYOUTS`, `applyKey`, `initialState`) has no DOM and is
  checked through node in `tests/test_screen_js.py`.

## 4c. The app drawer (`drawer.js`) and app icons (`app-icons.js`)

Swipe up (or long-press, or "покажи застосунки") opens a watch-style
drawer: a honeycomb of 48 px discs panned in both directions, with a
fisheye that keeps the middle full size and shrinks the rim. A tap opens
the app. A drag pans it, with momentum and a snap onto the nearest icon.
Pulling past the top edge closes the drawer. The pill at the bottom names
the icon in the middle. The top-left button switches to a list (icon +
name rows, same lens at the edges). The choice is kept in `localStorage`
as `botScreenDrawerView`.

The drawer takes the pointer for itself (`stopPropagation`), so a pan
never becomes a carousel swipe or the stage's long press.

**App icons.** Every icon in the drawer and the store is drawn in
`app-icons.js`: a coloured gradient disc with one white glyph, viewBox
48×48. The icon settings (pixel / line / colour / white) now style only
the small controls. The "Pixel pack" style is gone, because the drawer
was the only thing it changed. An app's design is picked in this order:

1. its **package id** (`BY_ID`), so the crab game is a crab even though
   its manifest says `icon: "face"`;
2. the manifest's **`icon` name** (`BY_ICON`), for third-party packages;
3. a grey disc with the first **letter** of the label.

A new built-in package should get a design and a `BY_ID` entry. How to
draw one that fits the set is in the skill
`.agents/skills/screen-app-icons/SKILL.md`.
`test_every_screen_and_package_has_its_own_design` fails until it does.
The layout maths (`honeycomb`, `fisheye`, `listLens`, `rubber`) is pure
and checked from node in `tests/test_screen_js.py`.

## 4d. Getting out of an app: Android gestures (`gesture-nav.js`)

While an app is open (camera, settings, the store, any store app), the
screen lays Android's edges over it. They lie above the app, iframes
included, so they work in every app, even a full-screen one:

- **Swipe up from the pill at the bottom → home.** Every layer closes and
  the carousel goes to its first screen. The app shrinks and rises under
  the finger.
- **Swipe in from the left or right edge → back.** Back goes to the
  previous app (store → app → back to the store). If the app was
  launched from the drawer, it goes back to the drawer. Otherwise it
  closes the app. An arrow grows at the edge and turns the accent colour
  once letting go will count.
- Letting go early cancels either gesture. The title bar's "Назад ✕",
  a horizontal swipe on a non-iframe app and an app's own
  `storeAppSwipe` message all mean **back** too. On a desktop, Escape
  means home and Backspace means back.
- The pill hops once when an app opens, to show where the way out is.

**For app authors:** the strips take the outer **12 px on each side**
and the bottom **16 px** of the 320×240 panel. Keep buttons out of them.
Content may run underneath, as it does on a phone.

## 5. API довідник (нові ендпоінти)

### Магазин екрана

| Метод/шлях | Що повертає |
|---|---|
| `GET /api/screen-store/catalog` | `{packages:[маніфест + installed]}` |
| `GET /api/screen-store/installed` | `{apps:[...], skins:[...]}` |
| `POST /api/screen-store/install` `{id}` | маніфест пакета (400/404 при помилці) |
| `POST /api/screen-store/uninstall` `{id}` | `{ok:true}` |
| `GET /store-apps/<id>/...` | статика встановленого застосунка (iframe) |

### Музика

| Метод/шлях | Що повертає |
|---|---|
| `GET /api/music/status` | `{youtube, transcript}` — що доступно |
| `GET /api/music/search?q=&limit=&sort=` | `{tracks:[{id,title,uploader,duration}]}`; `limit` up to 48, `sort` = `relevance` (default) or `date` (newest first) |
| `GET /api/music/radio` | `{stations:[{id,title,genre,url}]}` |
| `GET /api/music/stream?provider=youtube|radio&id=` | аудіо-потік з Range (206/Content-Range) |
| `GET /api/music/transcript?id=&lang=&text=` | сегменти субтитрів або склеєний текст |
| `POST /api/music/play` | `{"id","title","uploader","duration"}` або `{"query"}` → SSE `music` (так застосунки вмикають музику в барі) |
| `POST /api/music/stop` | зупинити Now Playing |
| `GET /api/ytm/synced?id=&title=&artist=&duration=` | `{kind:"synced", lines:[{t,text}], source}` from LRCLIB, or `{kind:"plain", text, source}` from YouTube Music, or `{kind:"none"}` |

Архітектурне правило: застосунок у iframe НІКОЛИ не грає аудіо сам —
він передає трек у Now Playing через `POST /api/music/play`, і музика
живе далі після закриття застосунку. Якщо тап стався всередині iframe і
браузер заблокував автоплей — бар показує ▶ і дограє на першому дотику
по екрану (ретрай в `musicPlayTrack`).

An app can still be the *player* for that sound (YT Music does it): the
screen pushes the Now Playing state to the open trusted app, and the app
sends controls back. Imported (sandboxed) apps get neither.

```js
// parent → app, on every state change and ~2x a second while playing
{type: "botMusic", track: {id, title, uploader, duration, provider} | null,
 position, duration, playing, loading, failed, live, hasPrev, hasNext}
// app → parent
parent.postMessage({type: "botMusicControl", action: "toggle"}, "*");  // also next, prev, state
parent.postMessage({type: "botMusicControl", action: "seek", position: 42}, "*");
```

`playing` is true only while sound is actually coming (`failed` covers a
dead stream), so an app can interpolate the position between messages.

### The island (`static/screen/island.js`)

A pill at the top of the screen, like iOS's Dynamic Island, replaced the
Now Playing bar and the timer chip on the face. It shows what is going on
now: a ringing or running timer, the song, a video that went on as sound.

- **Order:** a ringing timer, then whatever runs or plays, then what is
  paused. The pill shows the first; a second one gets its own dot.
- **Tap:** the island opens with controls (timer: pause/resume, +1 min,
  cancel; music: prev / play-pause / next and a seek bar; video as sound:
  ±10 s). With more than one activity, tabs on top pick which.
- **Tap on the open island's title:** back into the app — the timer tile,
  YT Music at `#player`, or the YouTube player at the second the sound
  reached. A tap anywhere else, or 8 s without touching, closes it.
- **Where:** centred on the face and over apps; in the top-right corner on
  tiles with a heading (the heading is cut short, not covered). Hidden over
  the shades and the drawer, in full-screen apps, and for an activity whose
  own app is in front (iOS does the same).
- **A video goes on as sound:** the YouTube app reports its state to the
  screen (`{type: "botVideoState", video_id, title, uploader, position,
  duration, paused}`); closing the app while a video plays hands it to Now
  Playing from that second. The island shows it as a video, and its title
  opens the picture again.
- **Landing inside an app:** the screen opens a package at
  `/store-apps/<id>/index.html#player`; YT Music opens its player on that
  hash. Other apps may read `location.hash` the same way.

The ordering rules have no DOM and are checked through node in
`tests/test_island_js.py`.

### Full screen for an app

`parent.postMessage({type: "storeAppFullscreen", on: true}, "*")` drops the
app layer's title bar and padding, so the iframe gets the whole 320×240
panel; `on: false` brings them back, and closing the app always does. The
app must then offer its own way back (the YouTube and YT Music players do).

### Відео на екрані

| Метод/шлях | Що повертає |
|---|---|
| `POST /api/video/play` `{id\|query, title?, start?}` | `{ok, track}` → SSE `video` (екран сам відкриє застосунок) |
| `POST /api/video/control` `{action, seconds?, position?, rate?}` | `{ok, command, done}`; 400 на невідому дію |
| `GET /api/video/state` | останній стан плеєра (з TTL 40 с) або `{playing:false}` |
| `POST /api/video/state` | звіт ВІД застосунку; `{closed:true}` гасить стан |
| `GET /api/video/segments?id=` | `{enabled, segments:[{category,label,start,end}], skipped_seconds}` |
| `GET/POST /api/video/settings` | `{settings, categories}`; POST — частковий патч |
| `GET /api/video/thumb?id=&size=mq\|hq\|default` | прев'ю через бота (jpeg); недоступне → прозорий 1×1 |

Порожній список сегментів — нормальна відповідь (у відео немає розмітки), а не
помилка: плеєр грає без пропусків. Мережевий збій SponsorBlock теж дає 200 і
порожній список — показ відео не мусить залежати від чужого сервісу.

### SSE (`/api/events`), подія музики

```json
{"type":"music","action":"play","track":{"provider":"youtube","id":"…","title":"…","uploader":"…","duration":213}}
{"type":"music","action":"stop"}
```

### SSE, подія відео

```json
{"type":"video","action":"play","track":{"id":"…","title":"…","duration":213},"position":120}
{"type":"video","action":"forward","seconds":30}
{"type":"video","action":"seek","position":150}
{"type":"video","action":"pause"}
```

## 6. Розробка: швидкий старт

```bash
cd "Virtual Bot"
CLERK_DISABLED=1 .venv/bin/uvicorn main:app --port 8100   # бекенд
open http://127.0.0.1:8100/screen                          # екран
pytest tests/test_screen_store.py tests/test_music.py \
       tests/test_video_control.py tests/test_youtube_mcp.py   # тести цих частин
```

Дрібні зміни екрана не потребують перезбірки — онови сторінку. Новий пакет
магазину — просто нова тека в `store/packages/`.

## 7. Відомі межі (чесно)

- Публічні Invidious-інстанси флапають: у «погану» хвилину стрім віддасть
  502 — тапни ще раз або підніми свій інстанс (3.2).
- Транскрайб залежить від наявності субтитрів у відео (ручних чи
  автоматичних); відео без жодних — «Субтитри недоступні».
- Клік станції/треку поза жестом користувача (наприклад, перший запуск
  сторінки) може бути заблокований автоплеєм браузера — у kiosk-режимі Pi
  ставиться `--autoplay-policy=no-user-gesture-required`.
- Іконки пакетів — тільки з наявних наборів екрана (`pixel-ui.js`/`icons.js`);
  свої SVG-ассети в пакеті поки не підвантажуються в шухляду.
- SponsorBlock знає лише те, що розмітила спільнота: у свіжому або
  малопопулярному відео сегментів не буде, і це не збій. Реклама, яку
  вставляє сам YouTube, від цього не залежить — її тут немає взагалі.
- Прямі ефіри плеєр не грає (у них лише HLS, а ми проксимо окремі доріжки):
  `play_video` каже це відразу, замість чорного екрана.
