"""Return a bounded native-map receipt; no generated code or map credentials."""

import math
import uuid

from map_schema import SCHEMA
from tool_activity import bounded_ui_text
from tools.locales import t


async def show_map(title: str, latitude: float, longitude: float, zoom: int = 11) -> dict:
    valid_coordinate = lambda value, limit: (
        isinstance(value, (int, float)) and not isinstance(value, bool)
        and -limit <= value <= limit and math.isfinite(value)
    )
    if (not isinstance(title, str) or not title.strip() or len(title) > 120
            or not valid_coordinate(latitude, 85) or not valid_coordinate(longitude, 180)
            or isinstance(zoom, bool) or not isinstance(zoom, int) or not 2 <= zoom <= 18):
        return {"error": t("ui.invalid_map"), "code": "invalid_map"}
    return {"ok": True, "ui": {"version": 1, "kind": "map", "id": uuid.uuid4().hex,
        "data": {"title": bounded_ui_text(title, 120), "latitude": latitude,
                 "longitude": longitude, "zoom": zoom}}}


SCHEMAS = [{"type": "function", "function": {
    "name": SCHEMA["name"], "description": SCHEMA["description"], "parameters": SCHEMA["inputSchema"],
}}]
HANDLERS = {"show_map": show_map}
