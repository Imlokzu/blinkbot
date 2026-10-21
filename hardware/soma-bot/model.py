"""Claude Bot, phase 1 prototype at real size, shaped like a crab.

A wide clay carapace with the 3.5" screen as its face, an LED light bar on top that shows
what the bot is doing, eyes on stalks, two claws resting on the desk and six legs. Inside,
the Raspberry Pi 3 Model B is screwed to the back of the screen, a 20000 mAh power bank stands
behind it, and every plug and cable is modelled.

Layout and sources: parts/params.py. The screen comes from the maker's STEP in inputs/.
"""
import cadquery as cq
from soma import add_part

from parts import body, crab, pi3, screen, wiring
from parts.params import (ACTIVE, CLAWS, CLAY, CLAY_DEEP, CLAY_LIGHT, CREAM, EXPLODE, EYE_STALKS, FACE, FRONT_IN,
                          GLOSS, HEATSINK, LED_COLOURS, LED_STATE, LEGS, LIFT, SCREEN_BG, SCREEN_TEXT, VIEW, WHITE)

CUT_AWAY = cq.Workplane("XY").box(400, 400, 400, centered=(False, True, True))  # removes x > 0


def lifted(shape, dx=0.0, dy=0.0, dz=0.0):
    """Screen frame -> model: feet on Z = 0, plus the explode offset."""
    if isinstance(shape, cq.Workplane):
        vals = shape.vals()
        shape = vals[0] if len(vals) == 1 else cq.Compound.makeCompound(vals)
    return shape.translate(cq.Vector(dx, dy, LIFT + dz))


def shell_part(wp):
    if VIEW == "cut-away":
        return wp.cut(CUT_AWAY)
    return wp


def mix(a, b, t):
    return tuple(a[i] * (1 - t) + b[i] * t for i in range(3))


# --- body --------------------------------------------------------------------------------------
screws = body.screws()
cover = body.cut_screw_holes(body.back_cover(), screws)
if VIEW != "hidden":
    add_part("Carapace (front shell)", lifted(shell_part(body.front_shell()), dy=-0.5 * EXPLODE), color=CLAY,
             group="Body")
    add_part("Face plate", lifted(shell_part(body.face_plate()), dy=-0.5 * EXPLODE), color=GLOSS, group="Body")
    add_part("Back cover", lifted(shell_part(cover), dy=EXPLODE), color=CLAY_DEEP, group="Body")
    add_part("Claude spark", lifted(shell_part(body.logo()), dy=EXPLODE), color=CREAM, group="Body")
    add_part("Light bar diffuser", lifted(shell_part(body.diffuser()), dz=0.6 * EXPLODE),
             color=mix(WHITE, LED_COLOURS[LED_STATE], 0.45), group="Body")
    for i, s in enumerate(screws, 1):
        add_part(f"Back cover screw {i}", s.translate((0, EXPLODE, LIFT)), group="Body/Screws")

# --- crab limbs ---------------------------------------------------------------------------------
cavity = body.inner_solid()
if LEGS:
    for name, leg in crab.legs(cavity):
        add_part(name, lifted(leg), color=CLAY, group="Crab/Legs")
if CLAWS:
    for name, shape, kind in crab.claws(cavity):
        add_part(name, lifted(shape), color=CLAY if kind == "arm" else CLAY_LIGHT, group="Crab/Claws")
if EYE_STALKS:
    for name, shape, kind in crab.eye_stalks():
        add_part(name, lifted(shape, dz=0.6 * EXPLODE), color=CLAY if kind == "stalk" else kind, group="Crab/Eyes")

# --- what the screen shows, colours from Virtual Bot/static/screen -------------------------------
if FACE != "off":
    glass = FRONT_IN - 0.06
    picture = cq.Workplane("XZ").rect(*ACTIVE).extrude(0.05).translate((0, FRONT_IN - 0.02, 0))
    if FACE == "eyes":
        mark = cq.Workplane("XZ").pushPoints([(-14.0, 3.0), (14.0, 3.0)]).slot2D(17.0, 10.0, 90).extrude(0.05)
    else:
        # the crab already has eyes on stalks, so the screen shows a smile with two blush dots
        ring = cq.Workplane("XZ").circle(15.0).circle(11.5).extrude(0.05)
        mark = ring.cut(cq.Workplane("XZ").center(0, 6.0).rect(40, 30).extrude(0.05, both=True)).translate((0, 0, 4.0))
        mark = mark.union(cq.Workplane("XZ").pushPoints([(-24.0, -2.0), (24.0, -2.0)]).ellipse(4.5, 2.6).extrude(0.05))
    add_part("Screen picture", lifted(picture, dy=-0.2 * EXPLODE), color=SCREEN_BG, group="Electronics/Screen face")
    add_part("Screen face", lifted(mark.translate((0, glass, 0)), dy=-0.2 * EXPLODE),
             color=SCREEN_TEXT, group="Electronics/Screen face")

# --- electronics -------------------------------------------------------------------------------
for name, shape, colour in screen.parts():
    add_part(name, lifted(shape, dy=-0.2 * EXPLODE), color=colour, group="Electronics/Screen 3.5in HDMI (E)")

for name, shape, colour in pi3.parts(with_heatsink=HEATSINK):
    add_part(name, lifted(shape, dy=0.3 * EXPLODE), color=colour, group="Electronics/Raspberry Pi 3 Model B")

wires = wiring.parts(LED_STATE)
for name, shape, colour in wires:
    sub = "Cables and plugs" if name.startswith(("Cable", "USB plug", "Micro-USB", "HDMI", "Speaker plug")) else "Peripherals"
    add_part(name, lifted(shape, dy=0.3 * EXPLODE), color=colour, group=f"Electronics/{sub}")
