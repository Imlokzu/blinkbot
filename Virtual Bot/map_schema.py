"""Native map contract shared by local models and OpenClaw MCP."""

CHAT_GUIDANCE = """
This is Blink desktop/web chat, which renders interactive answers inline.
When the user asks to show a place on a map or where a place is, call show_map
with accurate coordinates. Look up uncertain coordinates first. Prefer the
native interactive map over an image-search screenshot or an external-link-only
answer; do not invent a geographic outline in generated HTML. The user need not
say 'interactive'. For calculators, adjustable comparisons, charts and explorable
explanations, prefer render_interactive_ui when interaction helps. Respect an
explicit request for plain text or a static image. Keep simple answers as text.
After a UI tool succeeds, add a short useful text summary, not a duplicate image.
""".strip()

SCHEMA = {
    "name": "show_map",
    "description": (
        "Show a real interactive map with a location marker INSIDE Blink desktop/web chat. "
        "Use whenever the user asks to see a place on a map or asks where a place is. "
        "This provides pan and zoom controls, not a static image or an external link. "
        "Supply accurate latitude/longitude; use web_search if uncertain. "
        "Use zoom 5-7 for country context, 10-12 for a city, 15-18 for a street. "
        "On native mobile, messenger or voice-only surfaces, provide coordinates and a map link instead."
    ),
    "inputSchema": {
        "type": "object",
        "properties": {
            "title": {"type": "string", "minLength": 1, "maxLength": 120},
            "latitude": {"type": "number", "minimum": -85, "maximum": 85},
            "longitude": {"type": "number", "minimum": -180, "maximum": 180},
            "zoom": {"type": "integer", "minimum": 2, "maximum": 18, "default": 11},
        },
        "required": ["title", "latitude", "longitude"],
        "additionalProperties": False,
    },
}
