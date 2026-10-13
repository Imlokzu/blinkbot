"""Shared generative UI contract for local models and the OpenClaw MCP bridge."""

GUIDANCE = """
For an explanation, comparison, calculator or small interactive tool that benefits
from controls, use render_interactive_ui to place a working answer inside the chat.
Use ordinary text for simple answers, ask_question/show_choice for questions, and
todo_list for checklists. Do not create an interactive UI for every reply.
For geographic maps, use the native show_map tool instead of generated HTML or
an image-search screenshot; it supplies real map tiles with pan/zoom controls.
The UI runs in an isolated browser frame: HTML body fragment, CSS and plain
JavaScript. Keep it self-contained. Do not use network, imports, external assets,
storage, host APIs or credentials.
Use SVG, canvas and semantic controls with stable IDs. Blink automatically renders
range inputs with its React Bits WakeSlider, checkboxes with SquishSwitch, selects
with GlideSelect, and buttons/text inputs with the same React components as Settings.
Use data-variant="solid|quiet|outline|ghost" on buttons; default is quiet. Do not
redesign these controls or import React. Use CSS for content layout, not control skins.
Read/write the original elements by ID and listen to input/change/click as usual.
Attach listeners from javascript (no inline
onclick or script tags). Use accessible labels and the user's language. Theme
variables: --background, --surface, --text, --muted, --border, --accent, --font-sans.
Fit 280px-wide layouts; allow vertical scrolling; respect prefers-reduced-motion.
Keep all text fields together under 64,000 Unicode characters.
Use real sourced values or clearly label illustrative assumptions. Provide a short
plain-text summary so the answer is useful if the interactive view cannot load.
For a user-requested follow-up, call BlinkUI.requestFollowUp(text) from a click
handler. The host shows an editable draft; only the user's Send submits it.
Each call creates a separate saved artifact. Local input changes are temporary;
reopening or resetting restores the generated defaults. Do not claim a file, setting
or external service changed just because a control changed in the frame.
""".strip()

SCHEMA = {
    "name": "render_interactive_ui",
    "description": GUIDANCE,
    "inputSchema": {
        "type": "object",
        "properties": {
            "title": {"type": "string", "maxLength": 120},
            "summary": {"type": "string", "maxLength": 600},
            "html": {"type": "string", "maxLength": 64000, "description": "HTML body fragment, without scripts."},
            "css": {"type": "string", "maxLength": 64000},
            "javascript": {"type": "string", "maxLength": 64000, "description": "Plain browser JavaScript; DOM is ready."},
            "height": {"type": "integer", "minimum": 180, "maximum": 720, "description": "Initial frame height; defaults to 360."},
        },
        "required": ["title", "summary", "html"],
        "additionalProperties": False,
    },
}
