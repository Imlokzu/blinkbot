"""
The weather tool: Nominatim (geocoding) + Open-Meteo (forecast).
Free and keyless; Nominatim only asks for a User-Agent.

One answer serves two readers: the brain (the `weather` tool) and the
screen's weather tile (screen_widgets). So besides the words the brain
speaks ("condition"), it carries the raw WMO `code` and `is_day`: the tile
draws its own picture and background from them, in the screen's language.
"""

from __future__ import annotations

import logging
from typing import Any, Optional

import httpx

log = logging.getLogger("virtual_bot.tools.weather")

_NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
_OPEN_METEO_URL = "https://api.open-meteo.com/v1/forecast"
_USER_AGENT = "KlodBot/1.0 (virtual bot assistant)"

# How far the hourly forecast reaches: enough for "will it rain tonight?",
# short enough not to flood the brain's context with numbers.
HOURS_AHEAD = 12

# WMO weather interpretation codes: the words the brain reads, in Ukrainian
# (the bot's language), and an emoji for plain-text channels (Telegram).
_WMO_MAP: dict[int, tuple[str, str]] = {
    0: ("Чисте небо", "☀️"),
    1: ("Переважно ясно", "🌤"),
    2: ("Частково хмарно", "⛅"),
    3: ("Хмарно", "☁️"),
    45: ("Туман", "🌫"),
    48: ("Туман із інеєм", "🌫"),
    51: ("Легка мжичка", "🌦"),
    53: ("Помірна мжичка", "🌦"),
    55: ("Сильна мжичка", "🌧"),
    56: ("Легка мжичка, що замерзає", "🌧"),
    57: ("Сильна мжичка, що замерзає", "🌧"),
    61: ("Легкий дощ", "🌦"),
    63: ("Помірний дощ", "🌧"),
    65: ("Сильний дощ", "🌧"),
    66: ("Легкий дощ, що замерзає", "🌨"),
    67: ("Сильний дощ, що замерзає", "🌨"),
    71: ("Легкий сніг", "🌨"),
    73: ("Помірний сніг", "❄️"),
    75: ("Сильний сніг", "❄️"),
    77: ("Снігові зерна", "❄️"),
    80: ("Невелика злива", "🌦"),
    81: ("Помірна злива", "🌧"),
    82: ("Сильна злива", "⛈"),
    85: ("Невеликий сніговий дощ", "🌨"),
    86: ("Сильний сніговий дощ", "🌨"),
    95: ("Гроза", "⚡"),
    96: ("Гроза з невеликим градом", "⛈"),
    99: ("Гроза з сильним градом", "⛈"),
}


def _interpret(code: Optional[int]) -> tuple[str, str]:
    if code is None:
        return "Невідомо", "❓"
    return _WMO_MAP.get(code, ("Невідомі умови", "🌡"))


def _num(values: list, i: int, digits: int = 0) -> Optional[float]:
    """values[i] rounded, or None when the series is short or has a gap."""
    try:
        value = values[i]
    except (IndexError, TypeError):
        return None
    if value is None:
        return None
    return round(value) if digits == 0 else round(value, digits)


def _at(values: Optional[list], i: int) -> Any:
    return values[i] if isinstance(values, list) and i < len(values) else None


def _clock(iso: Optional[str]) -> str:
    """ "2026-09-27T06:52" → "06:52" (local time of the place, as sent)."""
    return iso[11:16] if isinstance(iso, str) and len(iso) >= 16 else ""


def shape_forecast(data: dict[str, Any]) -> dict[str, Any]:
    """Open-Meteo's answer → the fields the brain and the tile use.

    Pure, so the parsing is tested without the network."""
    current = data.get("current") or {}
    code = current.get("weather_code")
    condition, icon = _interpret(code)

    daily = data.get("daily") or {}
    forecast: list[dict] = []
    for i, day in enumerate((daily.get("time") or [])[:5]):
        d_code = _num(daily.get("weather_code") or [], i)
        forecast.append({
            "day": day,                                  # ISO date, the screen names the weekday
            "code": d_code,
            "icon": _interpret(d_code)[1],
            "max": _num(daily.get("temperature_2m_max") or [], i),
            "min": _num(daily.get("temperature_2m_min") or [], i),
            "pop": _num(daily.get("precipitation_probability_max") or [], i),
            "uv": _num(daily.get("uv_index_max") or [], i, 1),
            "sunrise": _clock(_at(daily.get("sunrise"), i)),
            "sunset": _clock(_at(daily.get("sunset"), i)),
        })

    # The hourly series starts at midnight; the forecast starts at the
    # current hour. Times are local to the place, so compare as strings.
    hourly = data.get("hourly") or {}
    times = hourly.get("time") or []
    now_hour = str(current.get("time") or "")[:13]
    start = next((i for i, tm in enumerate(times) if str(tm)[:13] >= now_hour), 0) if now_hour else 0
    hours: list[dict] = []
    for i in range(start, min(len(times), start + HOURS_AHEAD + 1)):
        h_code = _num(hourly.get("weather_code") or [], i)
        hours.append({
            "time": _clock(times[i]),
            "temp": _num(hourly.get("temperature_2m") or [], i),
            "code": h_code,
            "pop": _num(hourly.get("precipitation_probability") or [], i),
            "is_day": bool(_num(hourly.get("is_day") or [], i)),
        })

    temperature = current.get("temperature_2m")
    feels = current.get("apparent_temperature")
    return {
        "temperature": round(temperature) if temperature is not None else None,
        "feels_like": round(feels) if feels is not None else None,
        "condition": condition,
        "icon": icon,
        "code": code,
        "is_day": bool(current.get("is_day", 1)),
        "humidity": current.get("relative_humidity_2m"),
        "wind_speed": current.get("wind_speed_10m"),
        "wind_dir": current.get("wind_direction_10m"),
        "precipitation": current.get("precipitation"),
        "hourly": hours,
        "forecast": forecast,
    }


async def get_weather(city: str) -> dict:
    """The weather for a city as a dict (an error comes as the 'error' key)."""
    city = city.strip()
    if not city:
        return {"error": "Вкажи місто"}

    try:
        async with httpx.AsyncClient(timeout=12.0) as client:
            geo_resp = await client.get(
                _NOMINATIM_URL,
                params={"q": city, "format": "json", "limit": "1", "accept-language": "uk,en"},
                headers={"User-Agent": _USER_AGENT},
            )
            geo_resp.raise_for_status()
            geo = geo_resp.json()
            if not geo:
                return {"error": f"Місто «{city}» не знайдено"}
            lat = float(geo[0]["lat"])
            lon = float(geo[0]["lon"])
            display_name = geo[0].get("display_name", city)
            country = ""
            if "," in display_name:
                country = display_name.split(",")[-1].strip()
    except httpx.HTTPError as exc:
        log.warning("Nominatim error: %s", type(exc).__name__)
        return {"error": "Не вдалося знайти координати міста (сервіс недоступний)"}
    except Exception as exc:
        log.warning("Nominatim parse error: %s", type(exc).__name__)
        return {"error": "Помилка геокодування"}

    try:
        async with httpx.AsyncClient(timeout=12.0) as client:
            weather_resp = await client.get(
                _OPEN_METEO_URL,
                params={
                    "latitude": lat,
                    "longitude": lon,
                    "current": "temperature_2m,apparent_temperature,relative_humidity_2m,"
                               "weather_code,wind_speed_10m,wind_direction_10m,is_day,precipitation",
                    "hourly": "temperature_2m,weather_code,precipitation_probability,is_day",
                    "daily": "weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,"
                             "uv_index_max,precipitation_probability_max",
                    "timezone": "auto",
                    "forecast_days": 6,
                },
            )
            weather_resp.raise_for_status()
            data = weather_resp.json()
    except httpx.HTTPError as exc:
        log.warning("Open-Meteo error: %s", type(exc).__name__)
        return {"error": "Не вдалося отримати прогноз погоди (сервіс недоступний)"}
    except Exception as exc:
        log.warning("Open-Meteo parse error: %s", type(exc).__name__)
        return {"error": "Помилка прогнозу погоди"}

    return {
        "city": city,
        "display": display_name,
        "country": country,
        **shape_forecast(data),
    }
