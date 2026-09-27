"""
The weather tool's parsing: one Open-Meteo answer → what the brain reads
and what the screen's weather tile draws. Tested on a canned answer, so no
network is needed.
"""

from __future__ import annotations

from tools.weather import HOURS_AHEAD, shape_forecast


def _answer() -> dict:
    hours = [f"2026-09-27T{h:02d}:00" for h in range(24)] + [f"2026-09-28T{h:02d}:00" for h in range(24)]
    return {
        "current": {
            "time": "2026-09-27T14:15", "temperature_2m": 19.6, "apparent_temperature": 17.8,
            "relative_humidity_2m": 41, "weather_code": 2, "wind_speed_10m": 6.4,
            "wind_direction_10m": 43, "is_day": 1, "precipitation": 0.0,
        },
        "hourly": {
            "time": hours,
            "temperature_2m": [10 + (i % 24) / 2 for i in range(48)],
            "weather_code": [61 if i == 20 else 2 for i in range(48)],
            "precipitation_probability": [70 if i == 20 else 0 for i in range(48)],
            "is_day": [1 if 7 <= i % 24 < 19 else 0 for i in range(48)],
        },
        "daily": {
            "time": ["2026-09-27", "2026-09-28"],
            "weather_code": [3, 61],
            "temperature_2m_max": [20.4, 18.6],
            "temperature_2m_min": [9.7, None],
            "sunrise": ["2026-09-27T06:51", "2026-09-28T06:53"],
            "sunset": ["2026-09-27T18:45", "2026-09-28T18:43"],
            "uv_index_max": [3.84, 2.1],
            "precipitation_probability_max": [0, 80],
        },
    }


def test_current_conditions_carry_the_code_for_the_picture():
    w = shape_forecast(_answer())
    assert w["temperature"] == 20 and w["feels_like"] == 18
    assert w["code"] == 2 and w["is_day"] is True and w["condition"] == "Частково хмарно"
    assert w["wind_dir"] == 43


def test_hours_start_at_the_current_hour_and_cross_midnight():
    hours = shape_forecast(_answer())["hourly"]
    assert hours[0]["time"] == "14:00" and len(hours) == HOURS_AHEAD + 1
    assert hours[-1]["time"] == "02:00"                        # into tomorrow
    rain = next(h for h in hours if h["time"] == "20:00")
    assert rain["code"] == 61 and rain["pop"] == 70 and rain["is_day"] is False


def test_days_have_sun_times_uv_and_survive_gaps():
    days = shape_forecast(_answer())["forecast"]
    assert days[0] == {
        "day": "2026-09-27", "code": 3, "icon": "☁️", "max": 20, "min": 10,
        "pop": 0, "uv": 3.8, "sunrise": "06:51", "sunset": "18:45",
    }
    assert days[1]["min"] is None and days[1]["pop"] == 80


def test_an_empty_answer_does_not_crash():
    w = shape_forecast({})
    assert w["hourly"] == [] and w["forecast"] == [] and w["temperature"] is None
