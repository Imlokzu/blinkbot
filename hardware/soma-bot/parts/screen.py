"""Waveshare 3.5inch HDMI LCD (E) from the maker's STEP file.

The STEP merges into one shape, so its 20 solids are sorted by position and size into named
pieces with their own colours. The file is drawn glass-up (+Z); here it turns so the glass
faces the front (-Y) and its top edge stays at the top.
"""
import cadquery as cq
from soma import import_step

from .params import BLACK, GOLD, METAL, NICKEL, PCB_BLUE, SCREEN_STEP, WHITE


def _kind(bb):
    """Name and colour of one solid of the STEP, from its bounding box (STEP frame)."""
    if bb.zmax > 0.5:
        return "LCD panel and glass", (0.04, 0.05, 0.07)
    if bb.xlen > 70 and bb.zmax > -2:
        return "LCD backlight frame", (0.55, 0.56, 0.58)
    if bb.xlen > 70:
        return "Screen driver board", PCB_BLUE
    if bb.ylen > 60:
        return "Screen mounting tape", BLACK
    if bb.xlen < 1.5 and bb.ylen < 1.5:
        return "Screen pogo pins", GOLD
    if 5 < bb.xlen < 6 and 5 < bb.ylen < 6:
        return "Screen M2.5 studs", NICKEL
    if bb.xlen > 14:
        return "Screen HDMI socket", METAL
    if bb.ylen > 14:
        return "Screen audio jack", BLACK
    if bb.xlen < 3:
        return "Screen small parts", (0.3, 0.3, 0.3)
    if bb.zlen < 5:
        return "Screen USB-C (touch + power)", METAL
    return "Screen speaker header", WHITE


def parts():
    shape = import_step(SCREEN_STEP)
    groups = {}
    for s in shape.Solids():
        name, colour = _kind(s.BoundingBox())
        groups.setdefault(name, (colour, []))[1].append(s)
    out = []
    for name, (colour, solids) in groups.items():
        c = solids[0] if len(solids) == 1 else cq.Compound.makeCompound(solids)
        # STEP frame -> screen frame: glass (+Z) to the front (-Y), top edge (+Y) to the top (+Z)
        out.append((name, c.rotate((0, 0, 0), (1, 0, 0), 90), colour))
    return out
