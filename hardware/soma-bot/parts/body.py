"""Clawd's printed body: the box (front shell and back cover), the two arms, the eight feet
and the frosted light bar on top.

Everything is cut from Clawd's pixel grid (see params.py), so the outside matches the sprite
pixel for pixel. Only EDGE rounds the edges a little for printing. Inner features (OLED
frames, Pi standoffs, screw bosses, power bank cradle) are clipped to the outer box.
"""
import math

import cadquery as cq
from soma.fasteners import Screw

from .params import (ARM_COLS, ARM_Y, ARM_Z, BANK, BANK_MIN, BODY_Z0, BOSS_D, BOSS_FRONT, CHARGE_SOCKET, EDGE,
                     EYE_COLS, EYE_Z, FOOT_COLS, FRONT_IN, INNER_BACK, INNER_BOTTOM, INNER_TOP, INNER_X, LIP, OLED_PCB,
                     OUTER_BACK, OUTER_BOTTOM, OUTER_FRONT, OUTER_TOP, OUTER_X, PI_BOARD, PI_HOLES, PI_ORIGIN, PX_W,
                     SCREW_XZ, SHELF_NOTCH_Y, SHELF_T, SHELF_X, SHELF_Y, SHELF_Z, SOUND, SOUND_MIN, SPEAKER_D,
                     SPEAKER_YZ, SPLIT_Y, STRIP, STRIP_Y0, STRIP_Z, WALL, col_x, pi_to_world)

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

    # shelf over the power bank, tied to the right wall; the Pi sits on it on four standoffs
    shelf = box(SHELF_X[0], SHELF_X[1] + 0.5, SHELF_Y[0], SHELF_Y[1], SHELF_Z, SHELF_Z + SHELF_T)
    shelf = shelf.cut(box(-BIG, SHELF_X[0] + 3.0, SHELF_NOTCH_Y[0], SHELF_NOTCH_Y[1], -BIG, BIG))  # speaker cable
    shell = shell.union(shelf)
    board_bottom = PI_ORIGIN[2] - PI_BOARD[2]
    for bx, by in PI_HOLES:
        x, y, _ = pi_to_world(bx, by, 0)
        shell = shell.union(cyl(3.0, board_bottom - SHELF_Z - SHELF_T + 0.5, (x, y, SHELF_Z + SHELF_T - 0.5), (0, 0, 1)))
        shell = shell.cut(cyl(1.05, 8.0, (x, y, board_bottom - 7.0), (0, 0, 1)))  # M2.5 self-tapping

    # stops that keep the sound card in place on the shelf
    x0, y0, z0 = SOUND_MIN
    for sy0 in (y0 - 2.0, y0 + SOUND[1]):
        shell = shell.union(box(x0 + 6.0, x0 + 26.0, sy0, sy0 + 2.0, z0 - 0.5, z0 + 4.0))

    # speaker ring on the left wall, beside the power bank
    sy, sz = SPEAKER_YZ
    ring = (cyl(SPEAKER_D / 2 + 1.6, 5.0, (-OUTER_X + 0.5, sy, sz), (1, 0, 0))
            .cut(cyl(SPEAKER_D / 2 + 0.2, 8.0, (-OUTER_X, sy, sz), (1, 0, 0))))
    shell = shell.union(ring.intersect(outer))

    # two low rails on the floor for the power bank
    bx0, by0, bz0 = BANK_MIN
    for cx in (bx0 + 15.0, bx0 + BANK[0] - 15.0):
        shell = shell.union(box(cx - 4, cx + 4, by0 + 5, behind_lip, INNER_BOTTOM - 0.5, bz0))

    # screw bosses for the back cover, each tied by a vertical rib to the top or the floor
    for x, z in SCREW_XZ:
        boss = cyl(BOSS_D / 2, SPLIT_Y - BOSS_FRONT, (x, BOSS_FRONT, z), (0, 1, 0))
        rib = (box(x - 1.0, x + 1.0, BOSS_FRONT, behind_lip, z, OUTER_TOP) if z > 0.5 * (OUTER_TOP + OUTER_BOTTOM)
               else box(x - 1.0, x + 1.0, BOSS_FRONT, behind_lip, OUTER_BOTTOM, z))
        shell = shell.union(boss.union(rib.intersect(outer)))
        shell = shell.cut(cyl(1.25, SPLIT_Y - BOSS_FRONT + 1, (x, BOSS_FRONT + 3, z), (0, 1, 0)))  # M3 pilot

    # openings: eyes, light bar slot, top vents, speaker grille, mic holes, charging socket
    for col in EYE_COLS:
        shell = shell.cut(eye_window(col))
    if light_bar:
        shell = shell.cut(cq.Workplane("XY").slot2D(SLOT[0], SLOT[1], 90).extrude(20)
                          .translate((0, STRIP_Y0 + STRIP[0] / 2, INNER_TOP - 5)))
    for x in (-27.0, -21.0, -15.0, 15.0, 21.0, 27.0):
        shell = shell.cut(cq.Workplane("XY").slot2D(16, 2.4, 90).extrude(20).translate((x, 60.0, INNER_TOP - 5)))
    for a, b in hole_disc(sy, sz, 10.5, 2.0, 3.2):
        shell = shell.cut(cyl(1.0, 12, (-OUTER_X - 1, a, b), (1, 0, 0)))
    # the sound card's microphones listen through the left side, so the front stays plain like the sprite
    for i in range(5):
        shell = shell.cut(cyl(0.7, 12, (-OUTER_X - 1, y0 + 6 + i * 3.0, z0 + SOUND[2] / 2), (1, 0, 0)))
    cy_, cz_ = CHARGE_SOCKET
    usbc = (cq.Workplane("YZ").rect(9.4, 3.8).extrude(20).edges("|X").fillet(1.2)
            .translate((INNER_X - 6, cy_, cz_)))
    return shell.cut(usbc)


def back_cover():
    cover = outer_solid().cut(inner_solid()).intersect(box(-BIG, BIG, SPLIT_Y, BIG, -BIG, BIG))
    # lip that slides into the front shell, 0.2 mm clearance
    lip = (inner_solid(0.2).cut(inner_solid(1.6))
           .intersect(box(-BIG, BIG, SPLIT_Y - LIP, SPLIT_Y + 0.5, -BIG, BIG)))
    for x, z in SCREW_XZ:  # the lip steps around the screw bosses of the front shell
        lip = lip.cut(cyl(BOSS_D / 2 + 0.4, LIP + 2, (x, SPLIT_Y - LIP - 1, z), (0, 1, 0)))
    bx0, _, bz0 = BANK_MIN  # and over the end of the power bank on the floor
    lip = lip.cut(box(bx0 - 0.5, bx0 + BANK[0] + 0.5, -BIG, BIG, -BIG, bz0 + BANK[2] + 0.5))
    cover = cover.union(lip)
    for x, z in SCREW_XZ:
        cover = cover.union(cyl(BOSS_D / 2, INNER_BACK - SPLIT_Y + 1, (x, SPLIT_Y, z), (0, 1, 0)))
    for i in range(7):
        x = -18 + i * 6
        cover = cover.cut(cq.Workplane("XZ").slot2D(14, 2.4, 90).extrude(-20)
                          .translate((x, INNER_BACK - 5, INNER_TOP - 13)))
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
    """Frosted light bar along the top, front to back: a plug flush with the top over a channel
    that holds the LED strip; the strip's micro-USB socket is at the back end."""
    sl, sw = STRIP[0], STRIP[1]
    yc = STRIP_Y0 + sl / 2
    plug = cq.Workplane("XY").slot2D(SLOT[0] - 0.3, SLOT[1] - 0.3, 90).extrude(WALL).translate((0, yc, INNER_TOP))
    channel = box(-sw / 2 - 2, sw / 2 + 2, STRIP_Y0 - 3, STRIP_Y0 + sl + 3, STRIP_Z - 1.2, INNER_TOP)
    channel = channel.cut(box(-sw / 2 - 0.3, sw / 2 + 0.3, STRIP_Y0 - 0.4, STRIP_Y0 + sl + 0.4,
                              STRIP_Z - 5, INNER_TOP - 0.8))
    lips = (box(-sw / 2 - 0.3, -sw / 2 + 0.9, STRIP_Y0 - 0.4, STRIP_Y0 + sl - 6, STRIP_Z - 1.2, STRIP_Z)
            .union(box(sw / 2 - 0.9, sw / 2 + 0.3, STRIP_Y0 - 0.4, STRIP_Y0 + sl - 6, STRIP_Z - 1.2, STRIP_Z)))
    channel = channel.union(lips)
    # open end for the micro-USB plug of the strip
    channel = channel.cut(box(-sw / 2 - 0.3, sw / 2 + 0.3, STRIP_Y0 + sl - 1, BIG, STRIP_Z - 5, STRIP_Z + 0.2))
    return plug.union(channel)
