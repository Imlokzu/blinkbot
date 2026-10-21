"""Clawd's printed body: the box (front shell and back cover), the two arms, the eight feet
and the frosted light bar on top.

Everything is cut from Clawd's pixel grid (see params.py), so the outside matches the sprite
pixel for pixel. Only EDGE rounds the edges a little for printing. Inner features (OLED
frames, Pi standoffs, screw bosses, power bank cradle) are clipped to the outer box.
"""
import math

import cadquery as cq
from soma.fasteners import Screw

from .params import (ARM_COLS, ARM_Y, ARM_Z, BANK, BANK_MIN, BANK_PORTS_Z, BANK_USBC_Y, BODY_Z0, BOSS_D, BOSS_FRONT,
                     EDGE, EYE_COLS, EYE_Z, FOOT_COLS, FRONT_IN, INNER_BACK, INNER_BOTTOM, INNER_TOP, INNER_X, LIP,
                     OLED_PCB, OUTER_BACK, OUTER_BOTTOM, OUTER_FRONT, OUTER_TOP, OUTER_X, PI_BOARD, PI_HOLES, PI_ORIGIN,
                     PX_W, SCREW_XZ, SHELF_T, SHELF_X, SHELF_Y, SHELF_Z, SOUND, SOUND_MIN, SPEAKER_BOX, SPEAKER_MIN,
                     SPLIT_Y, STRIP, STRIP_Y, STRIP_Z, WALL, col_x, pi_to_world)

BIG = 500.0
SLOT = (88.0, 6.0)         # light bar opening in the top


def box(x0, x1, y0, y1, z0, z1):
    return cq.Workplane("XY").box(x1 - x0, y1 - y0, z1 - z0, centered=False).translate((x0, y0, z0))


def rounded(b, r):
    return b.edges().fillet(r) if r > 0.05 else b


def outer_solid():
    return rounded(box(-OUTER_X, OUTER_X, OUTER_FRONT, OUTER_BACK, OUTER_BOTTOM, OUTER_TOP), EDGE)


def inner_solid(inset=0.0):
    """Cavity of the shell, shrunk by `inset`."""
    i = WALL + inset
    return box(-OUTER_X + i, OUTER_X - i, OUTER_FRONT + i, OUTER_BACK - i, OUTER_BOTTOM + i, OUTER_TOP - i)


def cyl(r, h, p, d):
    return cq.Workplane().add(cq.Solid.makeCylinder(r, h, cq.Vector(*p), cq.Vector(*d)))


def hole_disc(center_a, center_b, radius, hole_d, pitch):
    """Hex pattern of hole centres inside a circle."""
    pts = []
    rows = int(radius / (pitch * 0.866)) + 1
    for j in range(-rows, rows + 1):
        b = j * pitch * 0.866
        off = (pitch / 2) if j % 2 else 0.0
        for i in range(-rows - 1, rows + 2):
            a = i * pitch + off
            if math.hypot(a, b) <= radius - hole_d / 2:
                pts.append((center_a + a, center_b + b))
    return pts


def eye_window(col):
    """One eye pixel, open through the front wall."""
    return box(col_x(col), col_x(col + 1), OUTER_FRONT - 1, FRONT_IN + 1, EYE_Z[0], EYE_Z[1])


def eye_center(col):
    return (col_x(col) + PX_W / 2, (EYE_Z[0] + EYE_Z[1]) / 2)


def front_shell(light_bar):
    shell = outer_solid().cut(inner_solid())
    shell = shell.intersect(box(-BIG, BIG, -BIG, SPLIT_Y, -BIG, BIG))
    outer = outer_solid()
    behind_lip = SPLIT_Y - LIP - 0.4

    # a frame behind each eye locates its OLED against the inside of the front wall
    pw, ph, _ = OLED_PCB
    for col in EYE_COLS:
        ex, ez = eye_center(col)
        frame = (box(ex - pw / 2 - 1.8, ex + pw / 2 + 1.8, FRONT_IN, FRONT_IN + 4.0, ez - ph / 2 - 1.8, ez + ph / 2 + 1.8)
                 .cut(box(ex - pw / 2 - 0.3, ex + pw / 2 + 0.3, FRONT_IN - 1, FRONT_IN + 5, ez - ph / 2 - 0.3,
                          ez + ph / 2 + 0.3)))
        shell = shell.union(frame.intersect(inner_solid(-0.5)))

    # Pi standoffs on the floor, M2.5 self-tapping
    board_bottom = PI_ORIGIN[2] - PI_BOARD[2]
    for bx, by in PI_HOLES:
        x, y, _ = pi_to_world(bx, by, 0)
        shell = shell.union(cyl(3.0, board_bottom - INNER_BOTTOM + 0.5, (x, y, INNER_BOTTOM - 0.5), (0, 0, 1)))
        shell = shell.cut(cyl(1.05, 8.0, (x, y, board_bottom - 7.0), (0, 0, 1)))

    # shelf over the Pi, tied to the right wall; open on the left for the cables beside the Pi's ports
    shell = shell.union(box(SHELF_X[0], SHELF_X[1] + 0.5, SHELF_Y[0], SHELF_Y[1], SHELF_Z, SHELF_Z + SHELF_T))

    # a stop on the shelf in front of the power bank; the sound card stands between the front
    # wall and the Pi
    bx0, by0, bz0 = BANK_MIN
    top = SHELF_Z + SHELF_T
    shell = shell.union(box(bx0 + 20, bx0 + 40, by0 - 2.0, by0, top - 0.5, top + 3.0))
    x0, y0, z0 = SOUND_MIN

    # screw bosses for the back cover, each tied by a vertical rib to the top or the floor
    for x, z in SCREW_XZ:
        boss = cyl(BOSS_D / 2, SPLIT_Y - BOSS_FRONT, (x, BOSS_FRONT, z), (0, 1, 0))
        rib = (box(x - 1.0, x + 1.0, BOSS_FRONT, behind_lip, z, OUTER_TOP) if z > 0.5 * (OUTER_TOP + OUTER_BOTTOM)
               else box(x - 1.0, x + 1.0, BOSS_FRONT, behind_lip, OUTER_BOTTOM, z))
        shell = shell.union(boss.union(rib.intersect(outer)))
        shell = shell.cut(cyl(1.25, SPLIT_Y - BOSS_FRONT + 1, (x, BOSS_FRONT + 3, z), (0, 1, 0)))  # M3 pilot

    # openings: eyes, light bar slot, top vents, mic holes, charging socket
    for col in EYE_COLS:
        shell = shell.cut(eye_window(col))
    if light_bar:
        shell = shell.cut(cq.Workplane("XY").slot2D(SLOT[0], SLOT[1]).extrude(20).translate((0, STRIP_Y, INNER_TOP - 5)))
    for x in (6.0, 12.0, 18.0, 24.0, 30.0, 36.0):
        shell = shell.cut(cq.Workplane("XY").slot2D(14, 2.4, 90).extrude(20).translate((x, 12.0, INNER_TOP - 5)))
    # the sound card's microphones listen through the belly, so the front stays plain like the sprite
    for i in range(5):
        shell = shell.cut(cyl(0.7, 12, (-6.0 + i * 3.0, y0 + SOUND[1] / 2, OUTER_BOTTOM - 1), (0, 0, 1)))
    usbc = (cq.Workplane("YZ").rect(9.4, 3.8).extrude(20).edges("|X").fillet(1.2)
            .translate((-INNER_X - 14, BANK_USBC_Y, BANK_PORTS_Z)))
    return shell.cut(usbc)


def back_cover():
    cover = outer_solid().cut(inner_solid()).intersect(box(-BIG, BIG, SPLIT_Y, BIG, -BIG, BIG))
    # lip that slides into the front shell, 0.2 mm clearance
    lip = (inner_solid(0.2).cut(inner_solid(1.6))
           .intersect(box(-BIG, BIG, SPLIT_Y - LIP, SPLIT_Y + 0.5, -BIG, BIG)))
    for x, z in SCREW_XZ:  # the lip steps around the screw bosses of the front shell
        lip = lip.cut(cyl(BOSS_D / 2 + 0.4, LIP + 2, (x, SPLIT_Y - LIP - 1, z), (0, 1, 0)))
    for bx, by in PI_HOLES:  # and around the Pi's standoffs
        x, y, _ = pi_to_world(bx, by, 0)
        lip = lip.cut(cyl(3.4, 20.0, (x, y, OUTER_BOTTOM - 1), (0, 0, 1)))
    lip = lip.cut(box(-46.0, -10.0, -BIG, BIG, -BIG, OUTER_BOTTOM + 12.0))  # and over the cables on the floor
    cover = cover.union(lip)
    for x, z in SCREW_XZ:
        cover = cover.union(cyl(BOSS_D / 2, INNER_BACK - SPLIT_Y + 1, (x, SPLIT_Y, z), (0, 1, 0)))
    for i in range(7):
        x = -18 + i * 6
        cover = cover.cut(cq.Workplane("XZ").slot2D(14, 2.4, 90).extrude(-20)
                          .translate((x, INNER_BACK - 5, INNER_TOP - 13)))
    # the box speaker sits in two clips on the inside of the cover and fires out through a grille
    sx0, sy0, sz0 = SPEAKER_MIN
    sw, sd, sh = SPEAKER_BOX
    for zc in (sz0 - 1.5, sz0 + sh):
        cover = cover.union(box(sx0 + 4, sx0 + sw - 4, sy0 - 0.5, INNER_BACK + 0.5, zc, zc + 1.5))
    for i in range(5):
        for j in range(3):
            cover = cover.cut(cyl(1.0, 10, (sx0 + 5 + i * 5.0, INNER_BACK - 2, sz0 + 5 + j * 5.0), (0, 1, 0)))
    return cover


def screws():
    return [Screw("ISO 7380", "M3x16", at=(x, OUTER_BACK, z), direction="-Y") for x, z in SCREW_XZ]


def cut_screw_holes(cover, screw_list):
    for s in screw_list:
        cover = cover.cut(s.clearance_hole())
    return cover


def arms():
    """Row 2 of the sprite: two pixels sticking out on each side, near the front."""
    out = []
    for (c0, c1), side in zip(ARM_COLS, ("left", "right")):
        arm = rounded(box(col_x(c0), col_x(c1), ARM_Y[0], ARM_Y[1], ARM_Z[0], ARM_Z[1]), EDGE)
        out.append((f"Arm {side}", arm))
    return out


def feet():
    """Row 3 of the sprite: four feet, one pixel each, at the front and at the back edge."""
    out = []
    for c in FOOT_COLS:
        side = "left" if c < 9 else "right"
        for y0, where in ((OUTER_FRONT, "front"), (OUTER_BACK - PX_W, "back")):
            foot = rounded(box(col_x(c), col_x(c + 1), y0, y0 + PX_W, 0.0, BODY_Z0), EDGE)
            out.append((f"Foot {side} {c} {where}", foot))
    return out


def diffuser():
    """Frosted light bar across the top: a plug flush with the top over a channel that holds the
    LED strip; the strip's micro-USB socket is at the left end."""
    sl, sw = STRIP[0], STRIP[1]
    plug = cq.Workplane("XY").slot2D(SLOT[0] - 0.3, SLOT[1] - 0.3).extrude(WALL).translate((0, STRIP_Y, INNER_TOP))
    channel = box(-sl / 2 - 3, sl / 2 + 3, STRIP_Y - sw / 2 - 2, STRIP_Y + sw / 2 + 2, STRIP_Z - 1.2, INNER_TOP)
    channel = channel.cut(box(-sl / 2 - 0.4, sl / 2 + 0.4, STRIP_Y - sw / 2 - 0.3, STRIP_Y + sw / 2 + 0.3,
                              STRIP_Z - 5, INNER_TOP - 0.8))
    lips = (box(-sl / 2 + 6, sl / 2 + 0.4, STRIP_Y - sw / 2 - 0.3, STRIP_Y - sw / 2 + 0.9, STRIP_Z - 1.2, STRIP_Z)
            .union(box(-sl / 2 + 6, sl / 2 + 0.4, STRIP_Y + sw / 2 - 0.9, STRIP_Y + sw / 2 + 0.3, STRIP_Z - 1.2, STRIP_Z)))
    channel = channel.union(lips)
    # open end for the micro-USB plug of the strip, at its left end
    channel = channel.cut(box(-BIG, -sl / 2 + 1, STRIP_Y - 5.0, STRIP_Y + 5.0, STRIP_Z - 5, STRIP_Z + 0.4))
    return plug.union(channel)
