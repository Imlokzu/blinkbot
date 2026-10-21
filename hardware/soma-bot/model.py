"""Claude Bot, phase 1 prototype at real size, built as Clawd, the Claude Code mascot.

From the front Clawd is pixel for pixel: a 13 × 4 pixel orange body, two arms of 2 × 1
pixels, eight feet, and two eye pixels. Each eye is a 0.96" OLED screen, so the eyes can look
around and blink. The body is about as deep as it is wide, a rough cube: a Raspberry Pi 3
Model B lies on the floor, a compact 10000 mAh power bank on a shelf above it, an LED light bar
runs across the top, and every plug and cable is modelled.

Layout and sources: parts/params.py.
"""
import cadquery as cq
from soma import add_part

from parts import body, pi3, wiring
from parts.params import CLAWD, CLAWD_DEEP, EXPLODE, HEATSINK, LED_COLOURS, LED_STATE, LIGHT_BAR, VIEW, WHITE

CUT_AWAY = cq.Workplane("XY").box(400, 400, 400, centered=(False, True, False)).translate((0, 0, -50))  # x > 0


def solid(shape, dx=0.0, dy=0.0, dz=0.0):
    """Workplane -> shape, plus the explode offset."""
    if isinstance(shape, cq.Workplane):
        vals = shape.vals()
        shape = vals[0] if len(vals) == 1 else cq.Compound.makeCompound(vals)
    return shape.translate(cq.Vector(dx, dy, dz))


def shell_part(wp):
    return wp.cut(CUT_AWAY) if VIEW == "cut-away" else wp


def mix(a, b, t):
    return tuple(a[i] * (1 - t) + b[i] * t for i in range(3))


# --- Clawd's body --------------------------------------------------------------------------------
screws = body.screws()
cover = body.cut_screw_holes(body.back_cover(), screws)
if VIEW != "hidden":
    add_part("Body (front shell)", solid(shell_part(body.front_shell(LIGHT_BAR)), dy=-0.5 * EXPLODE), color=CLAWD,
             group="Clawd")
    add_part("Back cover", solid(shell_part(cover), dy=EXPLODE), color=CLAWD_DEEP, group="Clawd")
    if LIGHT_BAR:
        add_part("Light bar diffuser", solid(shell_part(body.diffuser()), dz=0.6 * EXPLODE),
                 color=mix(WHITE, LED_COLOURS[LED_STATE], 0.45), group="Clawd")
    for i, s in enumerate(screws, 1):
        add_part(f"Back cover screw {i}", s.translate((0, EXPLODE, 0)), group="Clawd/Screws")
for name, arm in body.arms():
    add_part(name, solid(arm, dx=(0.5 if "right" in name else -0.5) * EXPLODE), color=CLAWD, group="Clawd/Arms")
for name, foot in body.feet():
    add_part(name, solid(foot, dz=-0.4 * EXPLODE), color=CLAWD, group="Clawd/Feet")

# --- electronics -------------------------------------------------------------------------------
for name, shape, colour in pi3.parts(with_heatsink=HEATSINK):
    add_part(name, solid(shape, dy=0.3 * EXPLODE), color=colour, group="Electronics/Raspberry Pi 3 Model B")

for name, shape, colour in wiring.parts(LED_STATE):
    if "eye" in name and not name.startswith(("Cable", "USB plug (")):
        group = "Electronics/Eye screens"
    elif name.startswith(("Cable", "USB plug", "Micro-USB", "STEMMA")):
        group = "Electronics/Cables and plugs"
    else:
        group = "Electronics/Peripherals"
    add_part(name, solid(shape, dy=0.3 * EXPLODE), color=colour, group=group)
