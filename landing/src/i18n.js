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
 * visitor's own language is applied before the intro animation starts.
 */

export const LANGS = ["uk", "en"];
export const DEFAULT_LANG = "en";
export const LANG_KEY = "claudeBotLandingLang";

const DICT = {
  uk: {
    "meta.title": "Клод Бот — AI-компаньйон, який живе на твоєму столі",
    "meta.description": "Відкритий AI-компаньйон: чат, пам’ять, голос, зір і обличчя піксельного краба. Сьогодні працює на твоєму комп’ютері, а коли будеш готовий — переїде в робота на Raspberry Pi.",
    "brand.name": "Клод Бот",
    "brand.home": "Клод Бот — на початок сторінки",

    "nav.product": "Продукт",
    "nav.device": "Пристрій",
    "nav.how": "Як це працює",
    "nav.start": "Почати",
    "nav.lang": "Мова сторінки",
    "nav.github": "GitHub",
    "nav.skip": "Перейти до вмісту",
    "nav.menu": "Меню",

    "hero.eyebrow": "Відкритий AI-компаньйон",
    "hero.titleA": "Бот, який живе",
    "hero.titleB": "на твоєму столі.",
    "hero.lead": "Чат, пам’ять, голос, зір і власне обличчя. Сьогодні він живе на твоєму комп’ютері, а коли будеш готовий — переїде в маленького робота-краба.",
    "hero.ctaPrimary": "Забрати з GitHub",
    "hero.ctaSecondary": "Подивитися в ділі",
    "hero.metaLangs": "українська й англійська",
    "hero.crabLabel": "Піксельний краб бота. Натисни на нього.",
    "hero.scroll": "Гортай",

    "reveal.alt": "Панель Клод Бота: чат, у якому бот перевірив погоду й пошукав у мережі, а під відповіддю — джерела.",
    "callout.activityTitle": "Показує, що робить",
    "callout.activityText": "Кожен інструмент видно наживо: погода, пошук, пам’ять.",
    "callout.sourcesTitle": "Каже, де дивився",
    "callout.sourcesText": "Посилання з пошуку лежать просто під відповіддю.",
    "callout.modelTitle": "Обирай мозок",
    "callout.modelText": "Будь-яка модель через один шлюз — і стільки «думання», скільки треба.",
    "callout.faceTitle": "Обличчя завжди поруч",
    "callout.faceText": "Живий краб у кутку показує, чим бот зайнятий.",

    "statement.text": "Більшість асистентів живуть у вкладці браузера. Цей має обличчя на твоєму столі, пам’ять, яку можна відкрити й прочитати, і власну теку. А ще він каже, що саме робить, поки працює.",

    "tour.eyebrow": "Панель",
    "tour.titleA": "Усе, що він знає, —",
    "tour.titleB": "в одному місці.",
    "tour.chatTitle": "Чат, який показує роботу",
    "tour.chatText": "Відповідь приходить так, як пише людина: коротка репліка, інструменти, якими він скористався, а тоді сама відповідь — із джерелами внизу.",
    "tour.chatAlt": "Розмова в панелі: дерево дій агента з погодою та пошуком і відповідь бота з джерелами.",
    "tour.memoryTitle": "Пам’ять, яку можна прочитати",
    "tour.memoryText": "Усе, що він дізнається про тебе й твоїх людей, лежить у звичайних Markdown-нотатках на твоєму диску. Відкривай, виправляй, видаляй.",
    "tour.memoryAlt": "Панель пам’яті: список нотаток і відкрита нотатка про подругу Олю.",
    "tour.styleTitle": "Під себе",
    "tour.styleText": "Ім’я, характер, теми, акценти й шпалери. Із коробки — темний графіт і теракота.",
    "tour.styleAlt": "Нова розмова на тлі намальованого неба з привітанням бота.",
    "tour.phoneTitle": "І з дивана теж",
    "tour.phoneText": "Панель ставиться на телефон як застосунок, тож ті самі розмови завжди під рукою.",
    "tour.phoneAlt": "Та сама розмова в панелі на екрані телефона.",

    "device.eyebrow": "Пристрій",
    "device.titleA": "А це —",
    "device.titleB": "його обличчя.",
    "device.lead": "Маленький екран на столі: піксельний краб, який слухає, думає й реагує, а ще годинник, погода і шухляда застосунків.",
    "device.moodLabel": "Спробуй настрій",
    "device.mood.happy": "Радіє",
    "device.mood.love": "Закохався",
    "device.mood.thinking": "Думає",
    "device.mood.web": "Шукає",
    "device.mood.writing": "Пише",
    "device.mood.sleepy": "Сонний",
    "device.mood.celebrating": "Святкує",
    "device.viewsLabel": "Що показати на екрані",
    "device.viewFace": "Обличчя",
    "device.viewClock": "Годинник",
    "device.viewWeather": "Погода",
    "device.viewApps": "Застосунки",
    "device.faceLabel": "очікування",
    "device.clockAlt": "Екран бота: великий піксельний годинник і дата.",
    "device.weatherAlt": "Екран бота: погода на сьогодні, погодинний графік і прогноз на тиждень.",
    "device.appsAlt": "Екран бота: шухляда застосунків у вигляді стільників.",
    "device.statApps": "застосунків у магазині",
    "device.statSkins": "скіни для екрана",
    "device.statScreen": "пікселів характеру",
    "device.hardware": "Raspberry Pi · екран · мікрофон · динамік · камера",

    "bento.eyebrow": "І ще",
    "bento.titleA": "Дрібниці,",
    "bento.titleB": "зроблені як слід.",
    "bento.voiceTitle": "Говорить і слухає",
    "bento.voiceText": "Розпізнавання мовлення на Whisper і живий голос на Piper — обидва працюють на твоїй машині.",
    "bento.chatsTitle": "Живе в месенджерах",
    "bento.chatsText": "Пиши йому в Telegram чи Discord — розмова з’явиться і в панелі.",
    "bento.chatsAsk": "що в мене сьогодні?",
    "bento.chatsReply": "о 12:00 дзвінок, о 18:30 тренування",
    "bento.visionTitle": "Бачить через камеру",
    "bento.visionText": "Розпізнає обличчя й рух через вебкамеру — помічає, коли ти сідаєш поруч.",
    "bento.musicTitle": "Вмикає музику на екрані",
    "bento.musicText": "YouTube і YouTube Music просто на пристрої — достатньо попросити.",
    "bento.scheduleTitle": "Працює за розкладом",
    "bento.scheduleText": "Ранкові дайджести, нагадування й регулярні завдання запускаються самі.",
    "bento.filesTitle": "Має власну теку",
    "bento.filesText": "Читає й пише файли у своїй робочій теці та малює схеми, які можна редагувати.",

    "how.eyebrow": "Як це працює",
    "how.titleA": "Три шари,",
    "how.titleB": "один характер.",
    "how.edgeName": "Тіло",
    "how.edgeWhere": "Raspberry Pi",
    "how.edgeText": "Екран, мікрофон, динамік і камера. Рефлекси — швидше ніж за 50 мс.",
    "how.fogName": "Дім",
    "how.fogWhere": "Твій комп’ютер або домашній сервер",
    "how.fogText": "Пам’ять, інструменти, голос і панель. Відповідь — за 100–500 мс.",
    "how.cloudName": "Розум",
    "how.cloudWhere": "Мовні моделі",
    "how.cloudText": "Будь-яка модель через шлюз OpenClaw — міняй коли завгодно.",

    "start.eyebrow": "Почати",
    "start.titleA": "Три команди",
    "start.titleB": "до живого бота.",
    "start.lead": "Для старту залізо не потрібне: спершу весь бот працює на твоєму комп’ютері, а робот може зачекати.",
    "start.step1": "Клонуй репозиторій",
    "start.step2": "Запусти бота — середовище він налаштує сам",
    "start.step3": "Відкрий панель у браузері",
    "start.copy": "Копіювати",
    "start.copied": "Скопійовано",
    "start.note": "Знадобиться Python і доступ до мовної моделі. README проведе через обидва кроки.",

    "final.titleA": "Збери",
    "final.titleB": "свого краба.",
    "final.lead": "Безкоштовно, з відкритим кодом — і вже чекає на GitHub.",
    "final.cta": "Відкрити на GitHub",

    "footer.license": "Відкритий код під GPL-3.0",
    "footer.by": "Зробив Imlokzu",
    "footer.top": "Нагору",
  },

  en: {
    "meta.title": "Claude Bot — an AI companion that lives on your desk",
    "meta.description": "An open-source AI companion with chat, memory, voice, vision and a pixel-crab face. It runs on your computer today and moves into a Raspberry Pi robot when you are ready.",
    "brand.name": "Claude Bot",
    "brand.home": "Claude Bot — back to the top",

    "nav.product": "Product",
    "nav.device": "Device",
    "nav.how": "How it works",
    "nav.start": "Get started",
    "nav.lang": "Page language",
    "nav.github": "GitHub",
    "nav.skip": "Skip to content",
    "nav.menu": "Menu",

    "hero.eyebrow": "Open-source AI companion",
    "hero.titleA": "An AI that lives",
    "hero.titleB": "on your desk.",
    "hero.lead": "Chat, memory, voice, eyes and a face of its own. It runs on your computer today and moves into a little crab robot when you are ready.",
    "hero.ctaPrimary": "Get it on GitHub",
    "hero.ctaSecondary": "See it in action",
    "hero.metaLangs": "Ukrainian & English",
    "hero.crabLabel": "The bot's pixel crab. Click it.",
    "hero.scroll": "Scroll",

    "reveal.alt": "The Claude Bot dashboard: a chat where the bot checked the weather and searched the web, with its sources under the answer.",
    "callout.activityTitle": "Shows its work",
    "callout.activityText": "Every tool it calls appears live: weather, search, memory.",
    "callout.sourcesTitle": "Cites where it looked",
    "callout.sourcesText": "Links from the search sit right under the answer.",
    "callout.modelTitle": "Pick the brain",
    "callout.modelText": "Any model behind one gateway, with as much thinking as you like.",
    "callout.faceTitle": "Its face, pinned",
    "callout.faceText": "A live crab in the corner shows what the bot is busy with.",

    "statement.text": "Most assistants live in a browser tab. This one has a face on your desk, a memory you can open and read, and a folder of its own. And it tells you what it is doing while it works.",

    "tour.eyebrow": "The dashboard",
    "tour.titleA": "Everything it knows,",
    "tour.titleB": "in one place.",
    "tour.chatTitle": "A chat that shows its work",
    "tour.chatText": "Replies arrive the way a person texts: a quick note, the tools it used, then the answer — with sources underneath.",
    "tour.chatAlt": "A conversation in the dashboard: the agent activity tree with weather and search, and the bot's answer with sources.",
    "tour.memoryTitle": "A memory you can read",
    "tour.memoryText": "What it learns about you and your people lives in plain Markdown notes on your own disk. Open them, fix them, delete them.",
    "tour.memoryAlt": "The memory panel: a list of notes and an open note about a friend called Olya.",
    "tour.styleTitle": "Make it yours",
    "tour.styleText": "A name, a personality, themes, accents and wallpapers. Dark graphite and terracotta out of the box.",
    "tour.styleAlt": "A new conversation over a painted sky, with the bot's greeting.",
    "tour.phoneTitle": "From the couch, too",
    "tour.phoneText": "The dashboard installs as an app on your phone, so the same chats are always within reach.",
    "tour.phoneAlt": "The same conversation in the dashboard on a phone screen.",

    "device.eyebrow": "The device",
    "device.titleA": "And this",
    "device.titleB": "is its face.",
    "device.lead": "A small screen on the desk: a pixel crab that listens, thinks and reacts — plus a clock, the weather and a drawer full of apps.",
    "device.moodLabel": "Try a mood",
    "device.mood.happy": "Happy",
    "device.mood.love": "In love",
    "device.mood.thinking": "Thinking",
    "device.mood.web": "Searching",
    "device.mood.writing": "Writing",
    "device.mood.sleepy": "Sleepy",
    "device.mood.celebrating": "Celebrating",
    "device.viewsLabel": "What to show on the screen",
    "device.viewFace": "Face",
    "device.viewClock": "Clock",
    "device.viewWeather": "Weather",
    "device.viewApps": "Apps",
    "device.faceLabel": "idle",
    "device.clockAlt": "The bot's screen: a large pixel clock and the date.",
    "device.weatherAlt": "The bot's screen: today's weather, an hourly graph and the week ahead.",
    "device.appsAlt": "The bot's screen: the app drawer laid out as a honeycomb.",
    "device.statApps": "apps in its store",
    "device.statSkins": "screen skins",
    "device.statScreen": "pixels of personality",
    "device.hardware": "Raspberry Pi · screen · mic · speaker · camera",

    "bento.eyebrow": "And more",
    "bento.titleA": "Small things,",
    "bento.titleB": "done properly.",
    "bento.voiceTitle": "Talks and listens",
    "bento.voiceText": "Speech recognition with Whisper and a natural voice with Piper — both run on your own machine.",
    "bento.chatsTitle": "Lives in your messengers",
    "bento.chatsText": "Write to it in Telegram or Discord — the conversation shows up in the dashboard too.",
    "bento.chatsAsk": "what's on today?",
    "bento.chatsReply": "a call at 12:00, a workout at 18:30",
    "bento.visionTitle": "Sees through a camera",
    "bento.visionText": "Face and motion detection over a webcam, so it notices when you sit down.",
    "bento.musicTitle": "Plays music on its screen",
    "bento.musicText": "YouTube and YouTube Music right on the device — just ask.",
    "bento.scheduleTitle": "Works on a schedule",
    "bento.scheduleText": "Morning digests, reminders and recurring jobs run on their own.",
    "bento.filesTitle": "Has a folder of its own",
    "bento.filesText": "It reads and writes files in its workspace and draws diagrams you can edit.",

    "how.eyebrow": "How it works",
    "how.titleA": "Three layers,",
    "how.titleB": "one personality.",
    "how.edgeName": "The body",
    "how.edgeWhere": "Raspberry Pi",
    "how.edgeText": "Screen, mic, speaker and camera. Reflexes in under 50 ms.",
    "how.fogName": "The home",
    "how.fogWhere": "Your computer or a home server",
    "how.fogText": "Memory, tools, voice and the dashboard. Answers in 100–500 ms.",
    "how.cloudName": "The thinking",
    "how.cloudWhere": "Language models",
    "how.cloudText": "Any model through the OpenClaw gateway — swap it whenever you like.",

    "start.eyebrow": "Get started",
    "start.titleA": "Three commands",
    "start.titleB": "to a living bot.",
    "start.lead": "No hardware needed to begin: the whole bot runs on your computer first, and the robot can wait.",
    "start.step1": "Clone the repository",
    "start.step2": "Start the bot — it sets up its own environment",
    "start.step3": "Open the dashboard in your browser",
    "start.copy": "Copy",
    "start.copied": "Copied",
    "start.note": "You will need Python and access to a language model. The README walks you through both.",

    "final.titleA": "Build your",
    "final.titleB": "own crab.",
    "final.lead": "Free, open source, and already waiting on GitHub.",
    "final.cta": "Open on GitHub",

    "footer.license": "Open source under GPL-3.0",
    "footer.by": "Made by Imlokzu",
    "footer.top": "Back to top",
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

export function getLang() {
  return current;
}

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
 * data-i18n-attr="aria-label:hero.crabLabel;title:hero.crabLabel".
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
