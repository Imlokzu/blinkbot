"""
Jev: the automatic model pick.

Jev is not a model. It is an entry in the brain picker that, once chosen,
decides per chat turn which OpenClaw model answers:

* fast  — weather, time, date, exchange rates, greetings, plain arithmetic.
          A small model with low thinking: these want an answer in a second,
          and the global thinking level (often `xhigh`) would spend five.
* build — "make me a site / write a script / build a game": Sol.
* smart — everything else: Luna.

Who decides. The real Jev — TypeSafe AI's "System One" model, launched
2026-09-15 — when TYPESAFE_API_KEY is set. It does not write text: it gets
the message and a list of options and returns the likeliest option with a
confidence, in 70–500 ms, at $0.042 per million input tokens (output is not
billed). That is the one kind of classifier cheap and fast enough to sit in
front of every turn; asking an LLM which LLM to ask would cost the seconds
the fast tier exists to save.

The keyword rules below stay as the fallback: no key, a network error, a
timeout, or an answer Jev itself is unsure of. The chat never waits on the
router for longer than `jev.api_timeout_s`.

Measured through the gateway on 2026-09-29 ("what is 2+2?"): gpt-oss-20b
1–2 s, GPT-6 Luna ~4 s, GPT-6 Sol ~6 s. gpt-5.4-nano would be the obvious
fast pick, but the OpenAI route here is a ChatGPT subscription, and that
refuses nano outright.
"""

from __future__ import annotations

import asyncio
import logging
import re

import httpx

import app_config as cfg

log = logging.getLogger("virtual_bot.jev")

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
    """Keyword rules: which tier a message belongs to — "fast", "build" or "smart"."""
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


# The message is all Jev needs; a pasted article does not route better for
# being sent whole, and input is the part that is billed.
_API_MAX_CHARS = 2000

_QUESTION = {
    "type": "choice",
    "instructions": (
        "A user sent this message to a voice companion robot. Which assistant "
        "tier should answer it?"
    ),
    "criteria": {
        "fast": (
            "A quick everyday question or small talk with a short factual answer: "
            "time, date, weather, exchange rate, a timer, a greeting or thanks, "
            "simple arithmetic."
        ),
        "build": (
            "A request to make something: write or fix code, build an app, website, "
            "game, script, widget or tool, design a page."
        ),
        "smart": (
            "Anything that needs thought: explanations, advice, opinions, planning, "
            "writing, stories, comparisons, questions about the user or the past."
        ),
    },
}

# One client per event loop: a pooled TLS connection saves a handshake on
# every turn, and a client must not outlive the loop it was made in.
_client: httpx.AsyncClient | None = None
_client_loop: asyncio.AbstractEventLoop | None = None


def _http() -> httpx.AsyncClient:
    global _client, _client_loop
    loop = asyncio.get_running_loop()
    if _client is None or _client_loop is not loop or _client.is_closed:
        _client = httpx.AsyncClient(trust_env=cfg.httpx_trust_env(cfg.JEV_API_URL))
        _client_loop = loop
    return _client


async def ask_jev(message: str) -> tuple[str, float] | None:
    """
    (tier, confidence) from the real Jev, or None when it cannot be asked.

    Never raises: a router that can break the chat is worse than no router.
    """
    key = cfg.get_typesafe_key()
    if not key or not (message or "").strip():
        return None
    body = {
        "model": cfg.JEV_API_MODEL,
        "state": (message or "")[:_API_MAX_CHARS],
        "questions": {"tier": _QUESTION},
    }
    try:
        response = await asyncio.wait_for(
            _http().post(
                cfg.JEV_API_URL,
                json=body,
                headers={"Authorization": f"Bearer {key}"},
                timeout=cfg.JEV_API_TIMEOUT_S,
            ),
            timeout=cfg.JEV_API_TIMEOUT_S,
        )
        if response.status_code != 200:
            # The body may echo the request; the status is enough to debug.
            log.warning("Jev API: HTTP %d, using keyword rules", response.status_code)
            return None
        answer = response.json()["answers"]["tier"]
        tier = str(answer["choice"])
        confidence = float(answer.get("confidence", 0.0))
    except (asyncio.TimeoutError, httpx.HTTPError, ValueError, KeyError, TypeError) as exc:
        log.warning("Jev API: %s, using keyword rules", type(exc).__name__)
        return None
    if tier not in TIERS:
        log.warning("Jev API: unknown tier %r, using keyword rules", tier[:20])
        return None
    return tier, confidence


async def decide(message: str) -> tuple[str, str]:
    """(tier, who decided) — "jev 0.93" when TypeSafe answered, else "keywords"."""
    answer = await ask_jev(message)
    if answer is not None:
        tier, confidence = answer
        if confidence >= cfg.JEV_MIN_CONFIDENCE:
            return tier, f"jev {confidence:.2f}"
        log.info("Jev unsure (%s %.2f), using keyword rules", tier, confidence)
    return classify(message), "keywords"


async def route(message: str) -> tuple[str, str, str, str]:
    """
    (tier, model, message to send, who decided) for one chat turn.

    Only the fast tier rewrites the message, prefixing a one-message
    thinking directive. A message that already carries its own directive is
    left alone: the owner asked for that level explicitly.
    """
    tier, source = await decide(message)
    model = tier_model(tier)
    thinking = cfg.JEV_FAST_THINKING
    if tier == "fast" and thinking and not _DIRECTIVE_RE.match(message or ""):
        return tier, model, f"/think:{thinking} {message}", source
    return tier, model, message, source


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
