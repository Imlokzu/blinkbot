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
    "meta.title": "Blink Bot — відкритий AI-компаньйон на твоєму комп’ютері",
    "meta.description": "Відкритий AI-компаньйон для твого комп’ютера: локальний голос, пам’ять у Markdown, вебпанель, Telegram, Discord і застосунок для Android.",
    "nav.screens": "Екрани",
    "brand.name": "Blink Bot",
    "brand.home": "Blink Bot — на початок сторінки",
    "brand.wordmark": "blink",
    "nav.lang": "Мова сторінки",
    "nav.github": "GitHub",
    "nav.skip": "Перейти до вмісту",
    "nav.english": "EN",
    "nav.ukrainian": "UK",
    "nav.primary": "Основна навігація",
    "nav.openMenu": "Відкрити меню",
    "nav.closeMenu": "Закрити меню",
    "device.viewsLabel": "Що показати на екрані",
    "device.viewClock": "Годинник",
    "device.viewWeather": "Погода",
    "device.viewApps": "Застосунки",
    "device.clockAlt": "Екран бота: великий піксельний годинник і дата.",
    "device.weatherAlt": "Екран бота: погода на сьогодні, погодинний графік і прогноз на тиждень.",
    "device.appsAlt": "Екран бота: шухляда застосунків у вигляді стільників.",
    "start.terminalTitle": "Термінал",
    "start.copy": "Копіювати",
    "start.copied": "Скопійовано",
    "start.copyFailed": "Не вдалося скопіювати",
    "start.copyHelp": "Не вдалося скопіювати. Виділи й скопіюй команду вручну.",
    "footer.top": "Нагору",
    "footer.repository": "github.com/Imlokzu/blinkbot",
    "nav.devices": "Пристрої",
    "nav.start": "Встановлення",
    "hero.label": "Відкритий AI-компаньйон",
    "hero.titleA": "Працює на твоєму",
    "hero.titleB": "комп’ютері",
    "hero.lead": "Голос, пам’ять у Markdown та інструменти працюють на твоїй машині. Пиши йому в панелі, Telegram, Discord або з Android.",
    "hero.cta": "Код на GitHub",
    "hero.deviceCaption": "Настільний екран на Raspberry Pi з годинником, погодою та застосунками",
    "reel.label": "Попроси його",
    "reel.item1": "дізнатися погоду в Києві",
    "reel.item2": "увімкнути музику з YouTube",
    "reel.item3": "записати щось про друзів",
    "reel.item4": "надіслати ранковий дайджест",
    "reel.item5": "намалювати схему",
    "reel.item6": "помітити, коли ти сідаєш поруч",
    "reel.item7": "прочитати файл у робочій теці",
    "reel.item8": "відповісти в Telegram чи Discord",
    "reel.note": "Інструменти, пам’ять і голос працюють у процесі Blink на твоєму комп’ютері. Мовну модель обираєш в OpenClaw.",
    "screens.title": "Справжні екрани, демо-дані",
    "screens.lead": "Знімки вебпанелі, застосунку для Android і екрана на Raspberry Pi.",
    "screens.hint": "Тягни вбік",
    "screens.chatTitle": "Чат у панелі",
    "screens.chatText": "Відповідь із деревом викликів інструментів і посиланнями на джерела.",
    "screens.memoryTitle": "Нотатки Markdown",
    "screens.memoryText": "Звичайні файли на твоєму диску. Відкривай, виправляй або видаляй.",
    "screens.styleTitle": "Вигляд",
    "screens.styleText": "Ім’я, характер, тема, акцентний колір і шпалери.",
    "screens.phoneTitle": "Blink Chat",
    "screens.phoneText": "Нативний застосунок для Android: чати, файли й голосове введення.",
    "screens.clockTitle": "Годинник",
    "screens.clockText": "Час і дата на сенсорному екрані 320×240.",
    "screens.weatherTitle": "Погода",
    "screens.weatherText": "Прогноз на сьогодні, погодинний графік і тиждень наперед.",
    "screens.appsTitle": "Застосунки",
    "screens.appsText": "Невеликі програми та ігри на настільному екрані.",
    "surfaces.title": "Де працює Blink і як до нього дістатись",
    "surfaces.lead": "Твій комп’ютер зберігає пам’ять і запускає інструменти. Решта списку — способи до нього достукатися.",
    "surfaces.computerName": "Твій комп’ютер",
    "surfaces.computerDetail": "Ядро на Python, файли пам’яті, інструменти, Whisper і Piper",
    "surfaces.computerStatus": "Потрібен",
    "surfaces.piName": "Raspberry Pi",
    "surfaces.piDetail": "Сенсорний екран, мікрофон, динамік і камера на столі",
    "surfaces.piStatus": "За бажанням",
    "surfaces.dashboardName": "Вебпанель",
    "surfaces.dashboardDetail": "Чати, нотатки пам’яті й налаштування в браузері",
    "surfaces.dashboardStatus": "Вбудована",
    "surfaces.androidName": "Blink Chat",
    "surfaces.androidDetail": "Застосунок для Android: повідомлення, файли й голос",
    "surfaces.androidStatus": "За бажанням",
    "surfaces.telegramName": "Telegram",
    "surfaces.telegramDetail": "Пиши боту, розмова з’явиться і в панелі",
    "surfaces.telegramStatus": "За бажанням",
    "surfaces.discordName": "Discord",
    "surfaces.discordDetail": "Так само, як Telegram, з історією в панелі",
    "surfaces.discordStatus": "За бажанням",
    "surfaces.modelName": "Шлюз OpenClaw",
    "surfaces.modelDetail": "Передає запити мовній моделі, яку ти обрав у налаштуваннях",
    "surfaces.modelStatus": "Потрібен",
    "paths.title": "Два способи запустити Blink",
    "paths.computerLabel": "Сьогодні",
    "paths.computerTitle": "На твоєму комп’ютері",
    "paths.computerText": "Клонуй репозиторій і запусти скрипт на macOS або Linux. Потрібні Python і доступ до мовної моделі.",
    "paths.piLabel": "Згодом",
    "paths.piTitle": "Додай настільний екран",
    "paths.piText": "Налаштуй Raspberry Pi з сенсорним екраном 320×240. Мікрофон, динамік і камера додадуть голос і зір.",
    "start.step1": "Клонуй репозиторій",
    "start.step2": "Запусти скрипт",
    "start.step3": "Відкрий панель",
    "start.note": "Як підключити мовну модель, описано в README.",
    "footer.title": "Запусти Blink на своєму залізі",
    "footer.lead": "Переглянь код, клонуй репозиторій і запусти власного бота.",
    "footer.cta": "Репозиторій GitHub",
    "footer.license": "Відкритий код під GPL-3.0",
    "footer.by": "Зробив Imlokzu",
    "screens.chatAlt": "Розмова в панелі: дерево дій агента з погодою та пошуком і відповідь бота з джерелами.",
    "screens.memoryAlt": "Панель пам’яті: список нотаток і відкрита нотатка про подругу Олю.",
    "screens.styleAlt": "Нова розмова на тлі намальованого неба з привітанням бота.",
    "screens.phoneAlt": "Розмова в нативному Android-застосунку Blink, із закритим меню.",
  },

  en: {
    "meta.title": "Blink Bot — a self-hosted AI companion",
    "meta.description": "An open-source AI companion that runs on your computer: local voice, Markdown memory, a web dashboard, Telegram, Discord and an Android app.",
    "nav.screens": "Screens",
    "brand.name": "Blink Bot",
    "brand.home": "Blink Bot — back to the top",
    "brand.wordmark": "blink",
    "nav.lang": "Page language",
    "nav.github": "GitHub",
    "nav.skip": "Skip to content",
    "nav.english": "EN",
    "nav.ukrainian": "UK",
    "nav.primary": "Primary navigation",
    "nav.openMenu": "Open menu",
    "nav.closeMenu": "Close menu",
    "device.viewsLabel": "What to show on the screen",
    "device.viewClock": "Clock",
    "device.viewWeather": "Weather",
    "device.viewApps": "Apps",
    "device.clockAlt": "The bot's screen: a large pixel clock and the date.",
    "device.weatherAlt": "The bot's screen: today's weather, an hourly graph and the week ahead.",
    "device.appsAlt": "The bot's screen: the app drawer laid out as a honeycomb.",
    "start.terminalTitle": "Terminal",
    "start.copy": "Copy",
    "start.copied": "Copied",
    "start.copyFailed": "Copy failed",
    "start.copyHelp": "Copy failed. Select the command to copy it manually.",
    "footer.top": "Back to top",
    "footer.repository": "github.com/Imlokzu/blinkbot",
    "nav.devices": "Devices",
    "nav.start": "Setup",
    "hero.label": "Open-source AI companion",
    "hero.titleA": "Runs on your",
    "hero.titleB": "own computer",
    "hero.lead": "Voice, Markdown memory and tools run on your machine. Message it from the dashboard, Telegram, Discord or Android.",
    "hero.cta": "View on GitHub",
    "hero.deviceCaption": "Optional Raspberry Pi desk display with a clock, weather and apps",
    "reel.label": "Ask it to",
    "reel.item1": "check the weather in Kyiv",
    "reel.item2": "play music from YouTube",
    "reel.item3": "keep notes on your friends",
    "reel.item4": "send a morning digest",
    "reel.item5": "draw an editable diagram",
    "reel.item6": "notice when you sit down",
    "reel.item7": "read a file in its workspace",
    "reel.item8": "reply in Telegram or Discord",
    "reel.note": "Tools, memory and voice run in Blink’s process on your computer. The language model is the one you pick in OpenClaw.",
    "screens.title": "Real screens, demo data",
    "screens.lead": "Captured from the web dashboard, the Android app and the Raspberry Pi display.",
    "screens.hint": "Drag to explore",
    "screens.chatTitle": "Dashboard chat",
    "screens.chatText": "Replies with the tree of tool calls and links to sources.",
    "screens.memoryTitle": "Markdown notes",
    "screens.memoryText": "Plain files on your disk. Open, edit or delete them here.",
    "screens.styleTitle": "Appearance",
    "screens.styleText": "Name, personality, theme, accent color and wallpaper.",
    "screens.phoneTitle": "Blink Chat",
    "screens.phoneText": "Native Android app with chats, files and voice input.",
    "screens.clockTitle": "Desk clock",
    "screens.clockText": "Time and date on the 320×240 touchscreen.",
    "screens.weatherTitle": "Weather",
    "screens.weatherText": "Today’s forecast, hourly chart and the week ahead.",
    "screens.appsTitle": "App drawer",
    "screens.appsText": "Small touchscreen apps and games on the desk unit.",
    "surfaces.title": "Where Blink runs and connects",
    "surfaces.lead": "Your computer keeps the memory and runs the tools. Everything else on this list is a way to reach it.",
    "surfaces.computerName": "Your computer",
    "surfaces.computerDetail": "Python core, memory files, tools, Whisper and Piper",
    "surfaces.computerStatus": "Required",
    "surfaces.piName": "Raspberry Pi",
    "surfaces.piDetail": "Touchscreen, microphone, speaker and camera on your desk",
    "surfaces.piStatus": "Optional",
    "surfaces.dashboardName": "Web dashboard",
    "surfaces.dashboardDetail": "Chats, memory notes and settings in the browser",
    "surfaces.dashboardStatus": "Built in",
    "surfaces.androidName": "Blink Chat",
    "surfaces.androidDetail": "Android app for messages, files and voice",
    "surfaces.androidStatus": "Optional",
    "surfaces.telegramName": "Telegram",
    "surfaces.telegramDetail": "Message the bot; the chat also appears in the dashboard",
    "surfaces.telegramStatus": "Optional",
    "surfaces.discordName": "Discord",
    "surfaces.discordDetail": "Same as Telegram, with history in the dashboard",
    "surfaces.discordStatus": "Optional",
    "surfaces.modelName": "OpenClaw gateway",
    "surfaces.modelDetail": "Sends requests to the language model you choose in settings",
    "surfaces.modelStatus": "Required",
    "paths.title": "Two ways to run Blink",
    "paths.computerLabel": "Today",
    "paths.computerTitle": "On your computer",
    "paths.computerText": "Clone the repository and run the script on macOS or Linux. You need Python and access to a language model.",
    "paths.piLabel": "Later",
    "paths.piTitle": "Add the desk display",
    "paths.piText": "Set up a Raspberry Pi with a 320×240 touchscreen. Add a microphone, speaker and camera for voice and vision.",
    "start.step1": "Clone the repository",
    "start.step2": "Run the start script",
    "start.step3": "Open the dashboard",
    "start.note": "The README walks through connecting a language model.",
    "footer.title": "Run Blink on your hardware",
    "footer.lead": "Read the code, clone the repository and start your own bot.",
    "footer.cta": "GitHub repository",
    "footer.license": "Open source under GPL-3.0",
    "footer.by": "by Imlokzu",
    "screens.chatAlt": "A conversation in the dashboard: the agent activity tree with weather and search, and the bot's answer with sources.",
    "screens.memoryAlt": "The memory panel: a list of notes and an open note about a friend called Olya.",
    "screens.styleAlt": "A new conversation over a painted sky, with the bot's greeting.",
    "screens.phoneAlt": "A conversation in the native Blink Android app, with its drawer closed.",
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
