/*
 * Landing locale catalogue.
 *
 * Same shape as the screen's catalogue (Virtual Bot/static/screen/i18n.js):
 * one DICT with a `uk` and an `en` object holding the same keys, `t(key)` in
 * code and `data-i18n` attributes in HTML. scripts/i18n_check.py checks that
 * both languages carry the same keys and placeholders.
 *
 * English is what the built HTML ships with (see scripts/prerender-i18n.js),
 * so the page reads correctly before any script runs and to crawlers. The
 * visitor's own language is applied when the page module loads.
 */

export const LANGS = ["uk", "en"];
export const DEFAULT_LANG = "en";
export const LANG_KEY = "claudeBotLandingLang";

const DICT = {
  uk: {
    "meta.title": "Blink Bot — AI-компаньйон, який живе на твоєму столі",
    "meta.description": "Відкритий AI-компаньйон: чат, пам’ять, голос і зір. Сьогодні працює на твоєму комп’ютері, а коли будеш готовий — переїде в робота на Raspberry Pi.",

    "brand.name": "Blink Bot",
    "brand.home": "Blink Bot — на початок сторінки",
    "brand.wordmark": "blink",

    "nav.product": "Продукт",
    "nav.how": "Як це працює",
    "nav.start": "Почати",
    "nav.lang": "Мова сторінки",
    "nav.github": "GitHub",
    "nav.skip": "Перейти до вмісту",
    "nav.english": "EN",
    "nav.ukrainian": "UK",
    "nav.primary": "Основна навігація",
    "nav.openMenu": "Відкрити меню",
    "nav.closeMenu": "Закрити меню",

    "hero.lead": "Спілкуйся з Blink голосом і в чаті, переглядай його пам’ять і підключай камеру. Запусти його на комп’ютері або збери настільного робота на Raspberry Pi.",
    "hero.ctaPrimary": "Відкрити на GitHub",
    "hero.ctaSecondary": "Огляд панелі",
    "hero.metaLangs": "Українська й англійська",
    "hero.metaLicense": "GPL-3.0",
    "hero.metaPlatforms": "macOS · Linux · Raspberry Pi",
    "hero.titleA": "Blink Bot",
    "hero.titleB": "На твоєму столі.",
    "hero.eyebrow": "Відкритий AI-компаньйон",
    "hero.statPlatforms": "Працює на",
    "hero.statLangs": "Мови",
    "hero.statLicense": "Ліцензія",


    "statement.text": "Пам’ять Blink зберігається в Markdown на твоєму диску. Робоча тека й дії інструментів доступні через панель.",
    "statement.title": "Працює на твоїй машині",

    "callout.activityTitle": "Дії інструментів",
    "callout.activityText": "Переглядай пошук, погоду й звернення до пам’яті під час відповіді.",
    "callout.sourcesTitle": "Джерела відповідей",
    "callout.sourcesText": "Відкривай джерела пошуку прямо під відповіддю.",
    "callout.modelTitle": "Вибір моделі",
    "callout.modelText": "Перемикай мовні моделі через один шлюз OpenClaw.",

    "tour.eyebrow": "Огляд панелі",
    "tour.chatTitle": "Чат",
    "tour.chatText": "У чаті видно відповідь, виклики інструментів і посилання на джерела.",
    "tour.chatAlt": "Розмова в панелі: дерево дій агента з погодою та пошуком і відповідь бота з джерелами.",
    "tour.memoryTitle": "Пам’ять",
    "tour.memoryText": "Відкривай, виправляй і видаляй звичайні Markdown-нотатки на своєму диску.",
    "tour.memoryAlt": "Панель пам’яті: список нотаток і відкрита нотатка про подругу Олю.",
    "tour.styleTitle": "Вигляд",
    "tour.styleText": "Обирай ім’я, характер, тему, акцентний колір і шпалери.",
    "tour.styleAlt": "Нова розмова на тлі намальованого неба з привітанням бота.",
    "tour.phoneTitle": "Blink Chat",
    "tour.phoneText": "Окремий застосунок для Android: розмови, файли й голосове введення, підключені до того самого бота.",
    "tour.phoneAlt": "Розмова в нативному Android-застосунку Blink, із закритим меню.",
    "tour.count": "04 екрани",
    "tour.chatKind": "Панель Blink",
    "tour.chatDetail": "Інструменти й джерела",
    "tour.memoryKind": "Панель Blink",
    "tour.memoryDetail": "Нотатки Markdown",
    "tour.styleKind": "Панель Blink",
    "tour.styleDetail": "Теми й характер",
    "tour.phoneKind": "Застосунок для Android",
    "tour.phoneDetail": "Розмови, файли й голос",

    "device.lead": "Годинник, прогноз погоди й застосунки на маленькому сенсорному екрані.",
    "device.viewsLabel": "Що показати на екрані",
    "device.viewClock": "Годинник",
    "device.viewWeather": "Погода",
    "device.viewApps": "Застосунки",
    "device.clockAlt": "Екран бота: великий піксельний годинник і дата.",
    "device.weatherAlt": "Екран бота: погода на сьогодні, погодинний графік і прогноз на тиждень.",
    "device.appsAlt": "Екран бота: шухляда застосунків у вигляді стільників.",
    "device.statScreen": "Роздільна здатність",
    "device.title": "Настільний екран Blink",
    "device.resolution": "320 × 240",

    "bento.voiceTitle": "Голос",
    "bento.voiceText": "Розпізнавання мовлення на Whisper і живий голос на Piper — обидва працюють на твоїй машині.",
    "bento.chatsTitle": "Месенджери",
    "bento.chatsText": "Пиши йому в Telegram чи Discord — розмова з’явиться і в панелі.",
    "bento.visionTitle": "Камера",
    "bento.visionText": "Розпізнає обличчя й рух через вебкамеру — помічає, коли ти сідаєш поруч.",
    "bento.musicTitle": "Музика",
    "bento.musicText": "YouTube і YouTube Music просто на пристрої — достатньо попросити.",
    "bento.scheduleTitle": "Розклад",
    "bento.scheduleText": "Ранкові дайджести, нагадування й регулярні завдання запускаються самі.",
    "bento.filesTitle": "Файли й схеми",
    "bento.filesText": "Читає й пише файли у своїй робочій теці та малює схеми, які можна редагувати.",
    "bento.title": "Що ще вміє Blink",

    "how.eyebrow": "Як це працює",
    "how.edgeWhere": "Raspberry Pi",
    "how.edgeText": "Керує екраном, мікрофоном, динаміком і камерою настільного робота.",
    "how.fogWhere": "Комп’ютер або домашній сервер",
    "how.fogText": "Зберігає пам’ять і запускає інструменти, голосові функції та панель.",
    "how.cloudWhere": "Мовні моделі",
    "how.cloudText": "Запити до мовної моделі проходять через OpenClaw. Модель можна змінити в налаштуваннях.",
    "how.title": "Де працює кожна частина",

    "start.eyebrow": "Встановлення",
    "start.lead": "Для початку достатньо комп’ютера. Raspberry Pi можна підключити пізніше.",
    "start.step1": "Клонуй репозиторій",
    "start.step2": "Запусти скрипт налаштування",
    "start.step3": "Відкрий панель у браузері",
    "start.terminalTitle": "Термінал",
    "start.copy": "Копіювати",
    "start.copied": "Скопійовано",
    "start.note": "Знадобиться Python і доступ до мовної моделі. README проведе через обидва кроки.",
    "start.title": "Запусти Blink на комп’ютері",
    "start.copyFailed": "Не вдалося скопіювати",
    "start.copyHelp": "Не вдалося скопіювати. Виділи й скопіюй команду вручну.",

    "final.lead": "Вихідний код та інструкції зі встановлення є на GitHub.",
    "final.cta": "Відкрити на GitHub",
    "final.title": "Збери свій Blink Bot",

    "footer.license": "Відкритий код під GPL-3.0",
    "footer.by": "Зробив Imlokzu",
    "footer.top": "Нагору",
    "footer.repository": "github.com/Imlokzu/blinkbot",
  },

  en: {
    "meta.title": "Blink Bot — an AI companion that lives on your desk",
    "meta.description": "An open-source AI companion with chat, memory, voice and vision. It runs on your computer today and moves into a Raspberry Pi robot when you are ready.",

    "brand.name": "Blink Bot",
    "brand.home": "Blink Bot — back to the top",
    "brand.wordmark": "blink",

    "nav.product": "Product",
    "nav.how": "How it works",
    "nav.start": "Get started",
    "nav.lang": "Page language",
    "nav.github": "GitHub",
    "nav.skip": "Skip to content",
    "nav.english": "EN",
    "nav.ukrainian": "UK",
    "nav.primary": "Primary navigation",
    "nav.openMenu": "Open menu",
    "nav.closeMenu": "Close menu",

    "hero.lead": "Talk to Blink, read its memory, and connect a camera. Run it on your computer or build a desk robot with a Raspberry Pi.",
    "hero.ctaPrimary": "View on GitHub",
    "hero.ctaSecondary": "Explore the dashboard",
    "hero.metaLangs": "Ukrainian and English",
    "hero.metaLicense": "GPL-3.0",
    "hero.metaPlatforms": "macOS · Linux · Raspberry Pi",
    "hero.titleA": "Blink Bot",
    "hero.titleB": "On your desk.",
    "hero.eyebrow": "Open-source AI companion",
    "hero.statPlatforms": "Runs on",
    "hero.statLangs": "Languages",
    "hero.statLicense": "License",


    "statement.text": "Blink keeps its memory in Markdown on your disk. Open its workspace and follow tool activity from the dashboard.",
    "statement.title": "Runs on your own machine",

    "callout.activityTitle": "Tool activity",
    "callout.activityText": "See searches, weather lookups and memory tools as they run.",
    "callout.sourcesTitle": "Sources",
    "callout.sourcesText": "Open search results directly below the answer.",
    "callout.modelTitle": "Model choice",
    "callout.modelText": "Switch language models through one OpenClaw gateway.",

    "tour.eyebrow": "Product tour",
    "tour.chatTitle": "Chat",
    "tour.chatText": "See replies, tool calls and source links in the same conversation.",
    "tour.chatAlt": "A conversation in the dashboard: the agent activity tree with weather and search, and the bot's answer with sources.",
    "tour.memoryTitle": "Memory",
    "tour.memoryText": "Open, edit and delete the plain Markdown notes stored on your disk.",
    "tour.memoryAlt": "The memory panel: a list of notes and an open note about a friend called Olya.",
    "tour.styleTitle": "Appearance",
    "tour.styleText": "Choose a name, personality, theme, accent color and wallpaper.",
    "tour.styleAlt": "A new conversation over a painted sky, with the bot's greeting.",
    "tour.phoneTitle": "Blink Chat",
    "tour.phoneText": "A native Android app for your chats, files and voice input, connected to the same bot.",
    "tour.phoneAlt": "A conversation in the native Blink Android app, with its drawer closed.",
    "tour.count": "04 previews",
    "tour.chatKind": "Blink dashboard",
    "tour.chatDetail": "Tool activity and sources",
    "tour.memoryKind": "Blink dashboard",
    "tour.memoryDetail": "Markdown notes",
    "tour.styleKind": "Blink dashboard",
    "tour.styleDetail": "Themes and personality",
    "tour.phoneKind": "Native Android app",
    "tour.phoneDetail": "Chat, files and voice",

    "device.lead": "A clock, weather and apps on a small touchscreen.",
    "device.viewsLabel": "What to show on the screen",
    "device.viewClock": "Clock",
    "device.viewWeather": "Weather",
    "device.viewApps": "Apps",
    "device.clockAlt": "The bot's screen: a large pixel clock and the date.",
    "device.weatherAlt": "The bot's screen: today's weather, an hourly graph and the week ahead.",
    "device.appsAlt": "The bot's screen: the app drawer laid out as a honeycomb.",
    "device.statScreen": "Display resolution",
    "device.title": "The Blink desk display",
    "device.resolution": "320 × 240",

    "bento.voiceTitle": "Voice",
    "bento.voiceText": "Speech recognition with Whisper and a natural voice with Piper — both run on your own machine.",
    "bento.chatsTitle": "Messaging",
    "bento.chatsText": "Write to it in Telegram or Discord — the conversation shows up in the dashboard too.",
    "bento.visionTitle": "Camera",
    "bento.visionText": "Face and motion detection over a webcam, so it notices when you sit down.",
    "bento.musicTitle": "Music",
    "bento.musicText": "YouTube and YouTube Music right on the device — just ask.",
    "bento.scheduleTitle": "Schedules",
    "bento.scheduleText": "Morning digests, reminders and recurring jobs run on their own.",
    "bento.filesTitle": "Files and diagrams",
    "bento.filesText": "It reads and writes files in its workspace and draws diagrams you can edit.",
    "bento.title": "What Blink can do",

    "how.eyebrow": "How it works",
    "how.edgeWhere": "Raspberry Pi",
    "how.edgeText": "Runs the desk robot’s screen, mic, speaker and camera.",
    "how.fogWhere": "Computer or home server",
    "how.fogText": "Stores memory and runs the tools, voice features and dashboard.",
    "how.cloudWhere": "Language models",
    "how.cloudText": "OpenClaw connects Blink to the language model you choose in settings.",
    "how.title": "Where each part runs",

    "start.eyebrow": "Setup",
    "start.lead": "Start on the computer you have. You can connect a Raspberry Pi later.",
    "start.step1": "Clone the repository",
    "start.step2": "Run the setup script",
    "start.step3": "Open the dashboard in your browser",
    "start.terminalTitle": "Terminal",
    "start.copy": "Copy",
    "start.copied": "Copied",
    "start.note": "You will need Python and access to a language model. The README walks you through both.",
    "start.title": "Run Blink on your computer",
    "start.copyFailed": "Copy failed",
    "start.copyHelp": "Copy failed. Select the command to copy it manually.",

    "final.lead": "Find the source code and setup instructions on GitHub.",
    "final.cta": "Open on GitHub",
    "final.title": "Build your Blink Bot",

    "footer.license": "Open source under GPL-3.0",
    "footer.by": "Made by Imlokzu",
    "footer.top": "Back to top",
    "footer.repository": "github.com/Imlokzu/blinkbot",
  },
};

/*
 * Ukrainian typography: a one-letter preposition or conjunction must not end
 * a line ("у / вкладці"). The dashboard solves the same thing with glue();
 * here the rule is small enough to live next to the strings it serves.
 */
// The lookbehind does not consume the space before a word, so a run of short
// words ("й на столі") is glued in one pass. \u00a0 is a no-break space.
const UK_SHORT = /(?<=^|[\s(«—])([уваізйоУВАІЗЙО]|та|на|до|не|що|як|по|за|від|для|із|зі) /g;

export function glue(text, lang) {
  if (lang !== "uk") return text;
  return text.replace(UK_SHORT, "$1\u00a0");
}

let current = DEFAULT_LANG;

export function setCurrentLang(lang) {
  current = LANGS.includes(lang) ? lang : DEFAULT_LANG;
}

export function t(key, lang = current) {
  const table = DICT[lang] || DICT[DEFAULT_LANG];
  const value = table[key] ?? DICT[DEFAULT_LANG][key];
  return value === undefined ? key : glue(value, lang);
}

/** Every key, for tests and the build-time prerender. */
export function catalogue(lang) {
  return DICT[lang];
}

/**
 * The language a visitor should see: an explicit ?lang= wins, then a saved
 * choice, then the browser's own preference.
 */
export function detectLang(search, stored, languages) {
  const asked = new URLSearchParams(search).get("lang");
  if (LANGS.includes(asked)) return asked;
  if (LANGS.includes(stored)) return stored;
  const ukrainian = (languages || []).some((code) => /^uk\b/i.test(code));
  return ukrainian ? "uk" : DEFAULT_LANG;
}

/**
 * Fill every `data-i18n` element and `data-i18n-attr` attribute under root.
 * Attributes use "attr:key" pairs separated by ";", e.g.
 * data-i18n-attr="aria-label:brand.home;title:brand.home".
 */
export function applyStatic(root = document, lang = current) {
  for (const el of root.querySelectorAll("[data-i18n]")) {
    el.textContent = t(el.dataset.i18n, lang);
  }
  for (const el of root.querySelectorAll("[data-i18n-attr]")) {
    for (const pair of el.dataset.i18nAttr.split(";")) {
      const [attr, key] = pair.split(":").map((s) => s.trim());
      if (attr && key) el.setAttribute(attr, t(key, lang));
    }
  }
}
