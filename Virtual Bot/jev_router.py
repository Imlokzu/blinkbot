"""
Jev: the automatic model pick.

Jev is not a model. It is an entry in the brain picker that, once chosen,
decides per chat turn which OpenClaw model answers:

* fast  — weather, time, date, exchange rates, greetings, plain arithmetic.
          A small model with low thinking: these want an answer in a second,
          and the global thinking level (often `xhigh`) would spend five.
* build — "make me a site / write a script / build a game": Sol.
* smart — everything else: Luna.

Why keywords and not a classifier model. The fast route exists to save
seconds; asking a model which model to ask costs one extra round trip on
every turn, which is exactly what the fast tier is there to avoid. A wrong
guess is cheap: the worst case is a simple question answered by Luna.

Measured through the gateway on 2026-09-29 ("what is 2+2?"): gpt-oss-20b
1–2 s, GPT-6 Luna ~4 s, GPT-6 Sol ~6 s. gpt-5.4-nano would be the obvious
fast pick, but the OpenAI route here is a ChatGPT subscription, and that
refuses nano outright.
"""

from __future__ import annotations

import re

import app_config as cfg

JEV_ID = "jev/auto"
TIERS: tuple[str, ...] = ("fast", "smart", "build")

# A long message is rarely a quick question even when it mentions the
# weather — it is a plan, a story, or a request with details.
_FAST_MAX_CHARS = 160

# Thinking directive OpenClaw strips from the body and applies to that one
# message only (docs/tools/thinking.md, "Inline directive"). The session and
# the global default stay untouched.
_DIRECTIVE_RE = re.compile(r"^\s*/(?:t|think|thinking)\b", re.IGNORECASE)

# Words are matched as prefixes so Ukrainian case endings still hit:
# "погода", "погоду", "погоди" all start with "погод".
_FAST_PATTERNS = [
    # weather
    r"\bпогод", r"\bдощ", r"\bсніг", r"\bтемператур", r"\bпрогноз", r"\bпарасол",
    r"\bweather\b", r"\bforecast\b", r"\btemperature\b", r"\brain(?:ing|y)?\b", r"\bumbrella\b",
    # time and date
    r"\bкотра\b", r"\bскільки\s+(?:зараз\s+)?час", r"\bяк(?:ий|е)\s+(?:сьогодні\s+)?(?:день|число|дата)",
    r"\bсьогодні\s+(?:день|число)", r"\bдень\s+тижня", r"\bщо\s+за\s+день",
    r"\bwhat\s+time\b", r"\btime\s+is\s+it\b", r"\bwhat(?:'s|\s+is)\s+(?:the\s+)?(?:date|time|day)\b",
    r"\bwhat\s+day\b", r"\btoday'?s\s+date\b",
    # money
    r"\bкурс\b", r"\bexchange\s+rate\b",
    # timers and alarms
    r"\bтаймер", r"\bбудильник", r"\btimer\b", r"\balarm\b",
]
_FAST_RE = re.compile("|".join(_FAST_PATTERNS), re.IGNORECASE)

# A message that is nothing but a greeting or a thank-you.
_SMALL_TALK_RE = re.compile(
    r"^(?:привіт\w*|хай|здоров\w*|добр(?:ий|ого|ої)\s+(?:ранку?|дня|день|вечора?|ночі)|"
    r"дякую|спасибі|дяки|ок|окей|добре|бувай|на\s+добраніч|"
    r"hi|hello|hey|yo|thanks|thank\s+you|thx|ok|okay|good\s+(?:morning|night|evening))"
    r"[\s!.,)]*$",
    re.IGNORECASE,
)

# Arithmetic and nothing else: "2+2", "15 * 7 = ?".
_MATH_RE = re.compile(r"^[\d\s+\-*/().,=?^%×÷x]+$")

_BUILD_VERBS = re.compile(
    r"\b(?:make|build|create|write|code|develop|generate|design|implement|scaffold|program|"
    r"fix|refactor|debug|"
    r"зроб|створ|напиш|написа|збуд|розроб|згенеру|запрограму|накида|сверста|"
    r"виправ|пофікс|перепиш|сдела|созда|разработ)",
    re.IGNORECASE,
)
_BUILD_THINGS = re.compile(
    r"\b(?:app|application|site|website|web\s*page|page|landing|game|script|program|code|"
    r"function|class|bot|api|component|widget|extension|plugin|tool|dashboard|html|css|"
    r"python|javascript|typescript|react|bug|"
    r"застосун|додат|сайт|сторінк|лендінг|гр[уаи]\b|іграш|скрипт|програм|код|функці|клас|"
    r"бот|компонент|віджет|розширенн|плагін|панел|баг|"
    r"игр|приложени|сайт)",
    re.IGNORECASE,
)


def tier_model(tier: str) -> str:
    """The OpenClaw model id behind one tier."""
    return {
        "fast": cfg.JEV_FAST_MODEL,
        "smart": cfg.JEV_SMART_MODEL,
        "build": cfg.JEV_BUILD_MODEL,
    }[tier]


def classify(message: str) -> str:
    """Which tier a message belongs to: "fast", "build" or "smart"."""
    text = (message or "").strip()
    if not text:
        return "smart"
    if "```" in text or (_BUILD_VERBS.search(text) and _BUILD_THINGS.search(text)):
        return "build"
    if len(text) <= _FAST_MAX_CHARS and (
        _SMALL_TALK_RE.match(text) or _MATH_RE.match(text) or _FAST_RE.search(text)
    ):
        return "fast"
    return "smart"


def route(message: str) -> tuple[str, str, str]:
    """
    (tier, model, message to send) for one chat turn.

    Only the fast tier rewrites the message, prefixing a one-message
    thinking directive. A message that already carries its own directive is
    left alone: the owner asked for that level explicitly.
    """
    tier = classify(message)
    model = tier_model(tier)
    thinking = cfg.JEV_FAST_THINKING
    if tier == "fast" and thinking and not _DIRECTIVE_RE.match(message or ""):
        return tier, model, f"/think:{thinking} {message}"
    return tier, model, message


def catalog_entry() -> dict:
    """Jev as one row of the brain picker, listed above the real models."""
    return {
        "id": JEV_ID,
        "label": "Jev",
        "provider": "jev",
        "available": True,
        "auto": True,
        "tiers": {tier: tier_model(tier) for tier in TIERS},
    }
