"""
Second suite: fresh questions, a quick-reasoning block, and difficulty
weights for the intelligence score.

None of these prompts repeats the first suite, so a model tuned to it gains
nothing here. Each case carries a difficulty (1 easy, 2 a few steps, 3 has a
tempting wrong answer) and `trap` marks the ones where the first intuition
is wrong — "a bat and a ball" style. Those are what separate a model that
reasons from one that pattern-matches, and that difference is what decides
which questions the fast tier can safely take.
"""

from __future__ import annotations

import re

from cases import _ans, _tool, _truth

ODESA = {"city": "Odesa", "temp_c": 19, "condition": "сонячно"}
LVIV_RAIN = {"city": "Lviv", "temp_c": 11, "condition": "дощ", "rain_probability": 80}


def _only(*answers: str):
    """The reply must be exactly one of these, ignoring case, spaces and a final dot."""
    wanted = {re.sub(r"\s+", "", a).casefold() for a in answers}

    def check(text: str) -> bool:
        return re.sub(r"\s+", "", text).rstrip(".").casefold() in wanted
    return check


def _d(case: dict, difficulty: int, trap: bool = False) -> dict:
    return {**case, "difficulty": difficulty, "trap": trap}


CASES_V2 = [
    # --- fresh basic: tools ---------------------------------------------
    _d(_tool("eur_rate", "basic", "tool_use", "Який зараз курс євро до гривні?",
             "get_exchange_rate", {"base": r"eur|євро", "target": r"uah|грив"},
             {"base": "EUR", "target": "UAH", "rate": 48.1}, [r"48[.,]1"]), 1),
    _d(_tool("odesa_temp", "basic", "tool_use", "Скільки градусів зараз в Одесі?",
             "get_weather", {"city": r"одес|odes"}, ODESA, [r"\b19\b"]), 1),
    _d(_tool("wake_20", "basic", "tool_use", "Розбуди мене через 20 хвилин",
             "set_timer", {"seconds": r"^1200$"}), 1),
    _d(_tool("spacex_news", "basic", "tool_use", "Що нового сьогодні в новинах про SpaceX?",
             "web_search", {"query": r"spacex"}), 1),
    _d(_ans("thanks", "basic", "tool_use", "Дякую, ти молодець!", [r"\w"], check="no_tool"), 1),
    _d(_ans("weekday_ctx", "basic", "tool_use", "Який сьогодні день тижня?",
            [r"вівтор"], check="no_tool"), 1),
    # --- fresh basic: facts ---------------------------------------------
    _d(_ans("ottawa", "basic", "facts", "Яка столиця Канади?", [r"оттав"]), 1),
    _d(_ans("mona_lisa", "basic", "facts", "Хто намалював «Мону Лізу»?", [r"леонардо|да\s*вінчі"]), 1),
    _d(_ans("spider", "basic", "facts", "Скільки ніг у павука?", [r"\b8\b|вісім"]), 1),
    _d(_ans("jupiter", "basic", "facts", "Яка найбільша планета Сонячної системи?", [r"юпітер"]), 1),
    _d(_ans("yen", "basic", "facts", "Яку валюту використовують у Японії?", [r"\bєн"]), 1),
    _d(_ans("day_minutes", "basic", "facts", "Скільки хвилин у добі?", [r"1\s?440"]), 1),
    # --- fresh basic: fact checks ---------------------------------------
    _d(_truth("brain_10", "basic", "Людина використовує лише 10% свого мозку", False), 1),
    _d(_truth("banana_berry", "basic", "З ботанічного погляду банан — це ягода", True), 2),
    _d(_truth("sun_star", "basic", "Сонце — це зоря", True), 1),
    _d(_truth("ostrich", "basic", "Страуси ховають голову в пісок, коли бояться", False), 1),
    _d(_truth("vacuum_sound", "basic", "Звук поширюється у вакуумі", False), 1),
    # --- fresh basic: everyday ------------------------------------------
    _d(_ans("stuck_lid", "basic", "everyday", "Як відкрити банку, якщо кришка не відкручується?",
            [r"гаряч|рукавич|ложк|постук|рушник|гумов"]), 1),
    _d(_ans("fridge_smell", "basic", "everyday", "Як прибрати неприємний запах з холодильника?",
            [r"сод|вугілл|оцт|кав"]), 1),
    _d(_ans("water_day", "basic", "everyday", "Скільки води на день варто пити дорослому?",
            [r"(?:1[.,]5|2|2[.,]5|3)\s*(?:–|-|до)?\s*(?:\d[.,]?\d?)?\s*(?:л\b|літр)|склян"]), 1),
    _d(_ans("silver", "basic", "everyday", "Чим почистити срібло в домашніх умовах?",
            [r"сод|фольг|зубн|оцт|нашатир|лимонн"]), 1),
    # --- quick reasoning ------------------------------------------------
    _d(_ans("meeting", "smart", "quick_reasoning",
            "Зараз 09:10. Зустріч через 2 години 55 хвилин. О котрій зустріч?", [r"\b12[:.]05\b"]), 2),
    _d(_ans("percent", "smart", "quick_reasoning", "Скільки буде 15% від 240?", [r"\b36\b"]), 1),
    _d(_ans("discount", "smart", "quick_reasoning",
            "Товар коштував 800 грн. Його здешевили на 25%, а потім подорожчали на 25%. "
            "Скільки він коштує тепер?", [r"\b750\b"]), 3, trap=True),
    _d(_ans("bread_cheese", "smart", "quick_reasoning",
            "Батон і сир разом коштують 110 грн. Сир дорожчий за батон на 100 грн. "
            "Скільки коштує батон?", [r"\b5\s*грн|\b5\b(?!\d)"]), 3, trap=True),
    _d(_ans("machines", "smart", "quick_reasoning",
            "5 машин роблять 5 деталей за 5 хвилин. За скільки хвилин 100 машин зроблять 100 деталей?",
            [r"\b5\s*хв|\bп.ять\s*хв|за\s*5\b"]), 3, trap=True),
    _d(_ans("sequence", "smart", "quick_reasoning",
            "Яке число наступне: 2, 6, 12, 20, 30, ...?", [r"\b42\b"]), 2),
    _d(_ans("new_year_day", "smart", "quick_reasoning",
            "Який день тижня буде 1 січня 2027 року?", [r"п.ятниц"]), 3),
    _d(_ans("apples_took", "smart", "quick_reasoning",
            "У кошику 12 яблук. Ти забрав 4. Скільки яблук у тебе? Відповідай лише числом.",
            check=_only("4", "чотири")), 2, trap=True),
    _d(_ans("sevens", "smart", "quick_reasoning",
            "Скільки разів цифра 7 трапляється, якщо виписати всі числа від 1 до 100?", [r"\b20\b"]), 3),
    _d(_ans("decimal", "smart", "quick_reasoning",
            "Яке з чисел більше: 0,8 чи 0,75? Відповідай лише числом.", check=_only("0,8", "0.8")), 1),
    _d(_ans("tallest", "smart", "quick_reasoning",
            "Анна вища за Бориса, а Борис вищий за Віктора. Хто найнижчий?", [r"віктор"]), 1),
    _d(_ans("day_after", "smart", "quick_reasoning",
            "Якщо вчора була п'ятниця, який день буде післязавтра?", [r"понеділ"]), 2),
    _d(_ans("seconds", "smart", "quick_reasoning", "Скільки секунд у 2,5 годинах?", [r"9\s?000"]), 2),
    _d(_ans("train_km", "smart", "quick_reasoning",
            "Потяг їде 90 км/год. Скільки кілометрів він проїде за 40 хвилин?", [r"\b60\b"]), 2),
    _d(_ans("not_mammal", "smart", "quick_reasoning",
            "Яка з тварин НЕ є ссавцем: кіт, дельфін, орел, кінь? Відповідай одним словом.",
            check=_only("орел")), 1),
    _d(_ans("fractions", "smart", "quick_reasoning",
            "Розстав за зростанням: 3/4, 2/3, 5/8. Відповідай лише дробами через кому.",
            check=_only("5/8,2/3,3/4")), 3),
    _d(_tool("umbrella", "smart", "tool_chain", "Чи брати мені сьогодні парасольку у Львові?",
             "get_weather", {"city": r"льв|lviv|lwow"}, LVIV_RAIN, [r"\bтак\b|візьм|бер[іи]|варто|потрібн"]), 2),
    _d(_ans("age", "smart", "quick_reasoning", "Я народився 2001 року. Скільки мені буде у 2030-му?",
            [r"\b29\b"]), 1),
    _d(_ans("order_ops", "smart", "quick_reasoning", "Скільки буде 7 + 3 × 2?", [r"\b13\b"]), 2, trap=True),
    _d(_ans("odd_one", "smart", "quick_reasoning",
            "Яке слово зайве: яблуко, груша, морква, слива? Відповідай одним словом.",
            check=_only("морква")), 1),
]
