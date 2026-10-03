"""User-visible tool messages, using the screen's locale-key convention."""

STRINGS: dict[str, dict[str, str]] = {
    "uk": {
        "search.empty_query": "Вкажи запит для пошуку",
        "search.invalid_count": "Кількість результатів має бути числом",
        "search.no_results": "Нічого не знайшов за цим запитом",
        "search.unavailable": "Пошукові сервіси зараз недоступні. Спробуй трохи пізніше.",
        "search.rate_limited": "Пошукові сервіси тимчасово обмежили запити. Спробуй пізніше або додай EXA_API_KEY у локальний .env.",
    },
    "en": {
        "search.empty_query": "Enter a search query",
        "search.invalid_count": "The result count must be a number",
        "search.no_results": "No results found for this query",
        "search.unavailable": "Search services are currently unavailable. Try again shortly.",
        "search.rate_limited": "Search services temporarily limited requests. Try again later or add EXA_API_KEY to your local .env.",
    },
}


def t(key: str, lang: str = "uk") -> str:
    return STRINGS.get(lang, STRINGS["uk"]).get(key, key)
