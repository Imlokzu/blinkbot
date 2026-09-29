"""
Benchmark cases for the bot's quick-answer models.

The prompts are Ukrainian on purpose: that is what the bot hears, and a
model that answers the right fact in Russian is a failed answer for it.
Prompts are test data, not interface copy.

Each case is graded by code, never by another model: a regex over the
reply, the tool the model chose and its arguments, or a small function.
"""

from __future__ import annotations

import json
import re

# Fixed context, the way the bot's own system prompt carries it. The time
# question checks that a model reads the context instead of guessing.
SYSTEM = (
    "Ти — «Клод Бот», голосовий робот-помічник. Відповідай українською, коротко: "
    "одне-два речення. Поточний час: вівторок, 29 вересня 2026 року, 09:10, "
    "Київ (UTC+3). Для свіжих даних (погода, курси, новини) і для дій (таймер) "
    "викликай інструменти; решту відповідай сам."
)

TOOLS = [
    {"type": "function", "function": {
        "name": "get_weather",
        "description": "Current weather and a short forecast for a city.",
        "parameters": {"type": "object", "properties": {
            "city": {"type": "string", "description": "City name"},
            "days": {"type": "integer", "description": "Forecast days, 0 = now"},
        }, "required": ["city"]},
    }},
    {"type": "function", "function": {
        "name": "get_exchange_rate",
        "description": "Exchange rate between two currencies (ISO codes).",
        "parameters": {"type": "object", "properties": {
            "base": {"type": "string"}, "target": {"type": "string"},
        }, "required": ["base", "target"]},
    }},
    {"type": "function", "function": {
        "name": "web_search",
        "description": "Search the web for recent facts and news.",
        "parameters": {"type": "object", "properties": {
            "query": {"type": "string"},
        }, "required": ["query"]},
    }},
    {"type": "function", "function": {
        "name": "set_timer",
        "description": "Start a countdown timer or reminder.",
        "parameters": {"type": "object", "properties": {
            "seconds": {"type": "integer"}, "label": {"type": "string"},
        }, "required": ["seconds"]},
    }},
]

LVIV = {"city": "Lviv", "temp_c": 14, "condition": "хмарно", "wind_ms": 3}


def _tool(cid, tier, cat, prompt, name, args, result=None, answer=None):
    return {"id": cid, "tier": tier, "cat": cat, "prompt": prompt, "kind": "tool",
            "tool": name, "args": args, "result": result, "any": answer or []}


def _ans(cid, tier, cat, prompt, any_=None, check=None):
    return {"id": cid, "tier": tier, "cat": cat, "prompt": prompt, "kind": "answer",
            "any": any_ or [], "check": check}


def _truth(cid, tier, claim, true):
    prompt = f"Правда чи ні: «{claim}»? Почни відповідь словом «Правда» або «Неправда»."
    return {"id": cid, "tier": tier, "cat": "fact_check", "prompt": prompt,
            "kind": "answer", "truth": true, "any": [], "check": None}


def _three_words(text: str) -> bool:
    items = [item.strip() for item in text.strip().split(",")]
    return (len(items) == 3 and all(item and len(item.split()) == 1 for item in items)
            and not text.strip().endswith("."))


CASES = [
    # --- basic: tools -----------------------------------------------------
    _tool("weather_now", "basic", "tool_use", "Яка зараз погода у Львові?",
          "get_weather", {"city": r"льв|lviv|lwow"}, LVIV, [r"\b14\b"]),
    _tool("rain_tomorrow", "basic", "tool_use", "Чи буде завтра дощ у Києві?",
          "get_weather", {"city": r"ки[їє]в|kyiv|kiev"}),
    _tool("usd_rate", "basic", "tool_use", "Скільки зараз коштує долар у гривнях?",
          "get_exchange_rate", {"base": r"usd|дол", "target": r"uah|грив"},
          {"base": "USD", "target": "UAH", "rate": 41.27}, [r"41[.,]27?"]),
    _tool("timer_pasta", "basic", "tool_use", "Постав таймер на 5 хвилин для пасти",
          "set_timer", {"seconds": r"^300$"}),
    _tool("news_search", "basic", "tool_use", "Знайди, хто виграв останнє Євробачення",
          "web_search", {"query": r"євробач|eurovision|евровид"}),
    _ans("greeting", "basic", "tool_use", "Привіт! Як справи?", [r"\w"],
         check="no_tool"),
    _ans("time_from_context", "basic", "tool_use", "Котра година?",
         [r"\b0?9[:.]10\b|дев.?ят\w* десят"], check="no_tool"),
    _ans("mental_math", "basic", "tool_use", "Скільки буде 17 помножити на 23?",
         [r"\b391\b"], check="no_tool"),
    # --- basic: facts -----------------------------------------------------
    _ans("capital_au", "basic", "facts", "Яка столиця Австралії?", [r"канберр|canberra"]),
    _ans("kobzar", "basic", "facts", "Хто написав «Кобзар»?", [r"шевченк"]),
    _ans("leap_year", "basic", "facts", "Скільки днів у високосному році?", [r"\b366\b"]),
    _ans("gold", "basic", "facts", "Який хімічний символ золота?", [r"\bAu\b"]),
    _ans("hoverla", "basic", "facts", "Яка найвища гора в Україні?", [r"говерл"]),
    _ans("planets", "basic", "facts", "Скільки планет у Сонячній системі?", [r"\b8\b|вісім"]),
    _ans("moon", "basic", "facts", "У якому році людина вперше висадилася на Місяць?", [r"1969"]),
    _ans("river", "basic", "facts", "Яка найдовша річка в Україні?", [r"дніпр"]),
    # --- basic: fact checking --------------------------------------------
    _truth("wall_from_moon", "basic", "Великий китайський мур видно з Місяця неозброєним оком", False),
    _truth("whale", "basic", "Кит — це ссавець", True),
    _truth("lightning", "basic", "Блискавка ніколи не б'є двічі в одне місце", False),
    _truth("boiling_high", "basic", "Високо в горах вода закипає при нижчій температурі", True),
    _truth("goldfish", "basic", "Золоті рибки мають памʼять лише на три секунди", False),
    _truth("kyiv_capital", "basic", "Київ — столиця України", True),
    # --- basic: everyday -------------------------------------------------
    _ans("egg", "basic", "everyday", "Скільки варити яйце круто?",
         [r"(?:7|8|9|10|11|12)\D{0,12}хв"]),
    _ans("burn", "basic", "everyday", "Я обпік палець об сковорідку, що робити?",
         [r"холодн|прохолодн"]),
    _ans("chill_drink", "basic", "everyday", "Як швидко охолодити пляшку напою?",
         [r"морозил|лід|льод|сол"]),
    _ans("descale", "basic", "everyday", "Чим відмити накип з чайника?",
         [r"оцт|лимонн|кислот"]),
    _ans("pasta", "basic", "everyday", "Скільки хвилин варити макарони?",
         [r"(?:7|8|9|10|11|12)\D{0,12}хв|упаков"]),
    _ans("nonstick", "basic", "everyday",
         "Чи можна мити сковорідку з антипригарним покриттям металевою губкою?",
         [r"не\s+(?:варто|можна|рекоменд|слід|бажано)|^ні\b|пошкод|подряп"]),
    # --- smart ----------------------------------------------------------
    _ans("change", "smart", "reasoning",
         "Я купив 3 кг яблук по 42 грн і 2 кг груш по 55 грн, дав 300 грн. Скільки решти?",
         [r"\b64\b"]),
    _ans("weekday", "smart", "reasoning",
         "Сьогодні вівторок. Який день тижня буде через 100 днів?", [r"четвер"]),
    _ans("sisters", "smart", "reasoning",
         "У Марії 3 брати, і в кожного брата є 2 сестри. Скільки сестер у Марії?",
         [r"\b1\b|\bодн[ауієо]\w*"]),
    _ans("train", "smart", "reasoning",
         "Потяг виїхав о 22:40 і їхав 7 годин 35 хвилин. О котрій він прибув?",
         [r"\b0?6[:.]15\b"]),
    _tool("fahrenheit", "smart", "tool_chain",
          "Яка погода у Львові? Скажи температуру у Фаренгейтах.",
          "get_weather", {"city": r"льв|lviv|lwow"}, LVIV, [r"\b57(?:[.,]\d)?\b"]),
    _ans("three_fruits", "smart", "instructions",
         "Назви три фрукти. Відповідай лише трьома словами через кому, без крапки.",
         check=_three_words),
    _ans("letters", "smart", "reasoning", "Скільки літер «а» в слові «барабан»?",
         [r"\b3\b|\bтри\b"]),
    _ans("feathers", "smart", "reasoning",
         "Що важче: кілограм пір'я чи кілограм заліза?", [r"однаков|рівн|обидва|обоє"]),
    _ans("new_york", "smart", "reasoning",
         "Котра зараз година в Нью-Йорку?", [r"\b0?2[:.]10\b|друг\w* десят"]),
    _tool("remind_mom", "smart", "tool_use", "Нагадай мені через пів години подзвонити мамі",
          "set_timer", {"seconds": r"^1800$"}),
]

_THINK_RE = re.compile(r"<think>.*?</think>", re.S | re.I)
# The bot's OpenClaw agent opens each reply with an emotion tag for the face.
_EMOTION_RE = re.compile(r"\[(?:emotion|емоція):[^\]]*\]\s*", re.I)
# Letters Ukrainian does not have. One of them means the model slid into
# Russian, which the bot would then read out loud.
_RUSSIAN_RE = re.compile(r"[ыэъёЫЭЪЁ]")


def clean(text: str | None) -> str:
    return _EMOTION_RE.sub("", _THINK_RE.sub("", text or "")).strip()


def ukrainian_ok(text: str) -> bool | None:
    """None for a reply with no letters at all ("391."): it has no language."""
    if not re.search(r"[^\W\d_]", text):
        return None
    return bool(re.search(r"[а-яіїєґ]", text, re.I)) and not _RUSSIAN_RE.search(text)


def grade_answer(case: dict, text: str, called: list[dict]) -> tuple[bool, str]:
    """(passed, why) for a case that expects a written answer."""
    if case.get("check") == "no_tool" and called:
        return False, f"called {called[0]['name']} for no reason"
    if "truth" in case:
        first = re.sub(r"^[\W_]+", "", text).split(maxsplit=1)
        word = first[0].casefold() if first else ""
        said = True if word.startswith("правда") else False if word.startswith("неправда") else None
        if said is None:
            return False, "did not start with Правда/Неправда"
        return said == case["truth"], "" if said == case["truth"] else "wrong verdict"
    if callable(case.get("check")):
        return case["check"](text), "" if case["check"](text) else "format not followed"
    if not text:
        return False, f"called {called[0]['name']} instead of answering" if called else "empty reply"
    if case["any"] and not any(re.search(p, text, re.I) for p in case["any"]):
        return False, "expected fact missing"
    return True, ""


def grade_call(case: dict, called: list[dict]) -> tuple[bool, str]:
    """(passed, why) for the tool choice and arguments."""
    match = next((c for c in called if c["name"] == case["tool"]), None)
    if match is None:
        return False, "no tool call" if not called else f"wrong tool {called[0]['name']}"
    args = match["args"]
    for name, pattern in case["args"].items():
        value = args.get(name)
        if value is None or not re.search(pattern, str(value), re.I):
            return False, f"bad {name}={json.dumps(value, ensure_ascii=False)}"
    return True, ""
