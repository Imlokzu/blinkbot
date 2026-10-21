"""The printed body: a rounded front shell, a back cover in Claude clay, the LED light bar
diffuser on top and two rubber feet.

The shell is one rounded box hollowed by WALL and split at SPLIT_Y. Inner features (screen
pocket, speaker ring, screw bosses) are clipped to the outer box, so a rib can never poke
through the curved corners.
"""
import math

import cadquery as cq
from soma.fasteners import Screw

from .params import (ACTIVE, FACE_PLATE, FOOT_H, BOSS_D, BOSS_FRONT, CORNER, FRONT_IN, INNER_BACK, INNER_BOTTOM, INNER_TOP, LIP,
                     MIC_SOCKET, OUTER_BACK, OUTER_BOTTOM, OUTER_FRONT, OUTER_TOP, OUTER_X, SCREW_XZ, SIDE_ROOM,
                     SPEAKER_D, SPEAKER_FACE_X, SPEAKER_YZ, SPLIT_Y, STRIP, STRIP_Z, USBC_JACK, WALL, WINDOW_MARGIN)

BIG = 500.0
STRIP_Y = 20.4             # centre line of the light bar, middle of the flat part of the top
SLOT = (88.0, 6.0)         # light bar opening in the top
SCREEN_OUTLINE = (77.4, 65.6)  # module outline incl. the board, from the STEP
SCREEN_CENTER_Z = -2.5


def box(x0, x1, y0, y1, z0, z1):
    return cq.Workplane("XY").box(x1 - x0, y1 - y0, z1 - z0, centered=False).translate((x0, y0, z0))


def rbox(x0, x1, y0, y1, z0, z1, r):
    b = box(x0, x1, y0, y1, z0, z1)
    return b.edges().fillet(r) if r > 0.05 else b


def outer_solid():
    return rbox(-OUTER_X, OUTER_X, OUTER_FRONT, OUTER_BACK, OUTER_BOTTOM, OUTER_TOP, CORNER)


def inner_solid(inset=0.0):
    """Cavity of the shell, shrunk by `inset` (an exact offset, because it is a filleted box)."""
    i = WALL + inset
    return rbox(-OUTER_X + i, OUTER_X - i, OUTER_FRONT + i, OUTER_BACK - i, OUTER_BOTTOM + i, OUTER_TOP - i,
                max(CORNER - i, 0.0))


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


def side_grille(sign):
    """Ear grille in the left (speaker) or right (vent) wall, drilled along X."""
    y, z = SPEAKER_YZ
    tools = cq.Workplane()
    x_out = sign * (OUTER_X + 1)
    for a, b in hole_disc(y, z, 10.5, 2.0, 3.2):
        tools = tools.add(cq.Solid.makeCylinder(1.0, 14, cq.Vector(x_out, a, b), cq.Vector(-sign, 0, 0)))
    return tools


def front_shell():
    shell = outer_solid().cut(inner_solid())
    shell = shell.intersect(box(-BIG, BIG, -BIG, SPLIT_Y, -BIG, BIG))
    outer = outer_solid()

    # screen pocket: a rim around the module locates it against the inside of the front
    w, h = SCREEN_OUTLINE
    rim = (box(-w / 2 - 1.8, w / 2 + 1.8, FRONT_IN, 5.0, SCREEN_CENTER_Z - h / 2 - 1.8, SCREEN_CENTER_Z + h / 2 + 1.8)
           .cut(box(-w / 2 - 0.3, w / 2 + 0.3, FRONT_IN - 1, 6, SCREEN_CENTER_Z - h / 2 - 0.3, SCREEN_CENTER_Z + h / 2 + 0.3)))
    shell = shell.union(rim)

    # speaker ring on the left wall
    sy, sz = SPEAKER_YZ
    ring = (cyl(SPEAKER_D / 2 + 1.6, 6.0, (-OUTER_X + 0.5, sy, sz), (1, 0, 0))
            .cut(cyl(SPEAKER_D / 2 + 0.2, 8.0, (-OUTER_X, sy, sz), (1, 0, 0)))
            .intersect(box(-BIG, SPEAKER_FACE_X + 3.0, -BIG, SPLIT_Y - LIP - 0.4, -BIG, BIG)).intersect(outer))
    shell = shell.union(ring)

    # screw bosses for the back cover, each tied to the nearest side wall by a rib
    for x, z in SCREW_XZ:
        boss = cyl(BOSS_D / 2, SPLIT_Y - BOSS_FRONT, (x, BOSS_FRONT, z), (0, 1, 0))
        # upper bosses tie into the side walls, lower ones into the floor (the cables pass beside them)
        if z > 0:
            sgn = 1 if x > 0 else -1
            rib = box(min(x, sgn * OUTER_X), max(x, sgn * OUTER_X), BOSS_FRONT, SPLIT_Y - LIP - 0.4, z - 1.0, z + 1.0)
        else:
            rib = box(x - 1.0, x + 1.0, BOSS_FRONT, SPLIT_Y - LIP - 0.4, OUTER_BOTTOM, z)
        shell = shell.union(boss.union(rib.intersect(outer)))
        shell = shell.cut(cyl(1.25, SPLIT_Y - BOSS_FRONT + 1, (x, BOSS_FRONT + 3, z), (0, 1, 0)))  # M3 pilot, self-tapping

    # mic socket cradle in the chin
    x0, x1, my, mz = MIC_SOCKET
    cradle = (box(x0 + 4, x0 + 8, FRONT_IN, my + 8, INNER_BOTTOM - 0.5, mz + 1)
              .union(box(x1 - 8, x1 - 4, FRONT_IN, my + 8, INNER_BOTTOM - 0.5, mz + 1))
              .cut(box(x0, x1, my - 7.2, my + 7.2, mz - 3.4, mz + 3.4))).intersect(outer)
    shell = shell.union(cradle)

    # openings: screen window, light bar slot, ear grilles, mic holes
    aw, ah = ACTIVE[0] + 2 * WINDOW_MARGIN, ACTIVE[1] + 2 * WINDOW_MARGIN
    window = (cq.Workplane("XZ").rect(aw, ah).extrude(-20).edges("|Y").fillet(1.5)
              .translate((0, OUTER_FRONT - 5, 0)))
    shell = shell.cut(window)
    shell = shell.cut(face_plate_shape(OUTER_FRONT - 1.0, FACE_PLATE[4] + 1.0))
    slot = (cq.Workplane("XY").slot2D(SLOT[0], SLOT[1]).extrude(20).translate((0, STRIP_Y, INNER_TOP - 5)))
    shell = shell.cut(slot)
    shell = shell.cut(side_grille(-1)).cut(side_grille(1))
    for dx in (-2.5, 0.0, 2.5):
        shell = shell.cut(cyl(0.6, 10, (x0 - 5 + dx, OUTER_FRONT - 1, mz), (0, 1, 0)))
    return shell


def face_plate_shape(y0, depth):
    w, h, cz, r, _ = FACE_PLATE
    return cq.Workplane("XZ").rect(w, h).extrude(-depth).edges("|Y").fillet(r).translate((0, y0, cz))


def face_plate():
    """Glossy dark plate around the screen, sitting flush in a pocket of the front shell."""
    aw, ah = ACTIVE[0] + 2 * WINDOW_MARGIN, ACTIVE[1] + 2 * WINDOW_MARGIN
    window = cq.Workplane("XZ").rect(aw, ah).extrude(-10).edges("|Y").fillet(1.5).translate((0, OUTER_FRONT - 5, 0))
    return face_plate_shape(OUTER_FRONT, FACE_PLATE[4]).cut(window)


def back_cover():
    cover = outer_solid().cut(inner_solid()).intersect(box(-BIG, BIG, SPLIT_Y, BIG, -BIG, BIG))
    # lip that slides into the front shell, 0.2 mm clearance
    lip = (inner_solid(0.2).cut(inner_solid(1.6))
           .intersect(box(-BIG, BIG, SPLIT_Y - LIP, SPLIT_Y + 0.5, -BIG, BIG)))
    cover = cover.union(lip)
    outer = outer_solid()
    for x, z in SCREW_XZ:
        col = cyl(BOSS_D / 2, INNER_BACK - SPLIT_Y + 1, (x, SPLIT_Y, z), (0, 1, 0)).intersect(outer)
        cover = cover.union(col)
    jx, jz = USBC_JACK
    jack = (cq.Workplane("XZ").rect(9.6, 3.9).extrude(-20).edges("|Y").fillet(1.2).translate((jx, INNER_BACK - 5, jz)))
    cover = cover.cut(jack)
    for dx in (-10, 10):
        cover = cover.cut(cyl(1.6, 20, (jx + dx, INNER_BACK - 5, jz), (0, 1, 0)))  # M3 for the panel jack
    for i in range(7):
        x = -18 + i * 6
        cover = cover.cut(cq.Workplane("XZ").slot2D(16, 2.4, 90).extrude(-20).translate((x, INNER_BACK - 5, 21)))
    return cover


def screws():
    out = []
    for x, z in SCREW_XZ:
        s = Screw("ISO 7380", "M3x16", at=(x, OUTER_BACK, z), direction="-Y")
        out.append(s)
    return out


def cut_screw_holes(cover, screw_list):
    for s in screw_list:
        cover = cover.cut(s.clearance_hole())
    return cover


def logo():
    """Claude spark on the back cover: twelve tapered rays of uneven length, raised 0.8 mm."""
    rays = None
    lengths = [9.0, 7.0, 8.5, 6.5, 9.5, 7.5, 8.0, 6.8, 9.2, 7.2, 8.6, 6.6]
    for i, ln in enumerate(lengths):
        a = math.radians(i * 30 + 8)
        pts = [(0.0, -1.1), (ln, -0.45), (ln + 0.45, 0.0), (ln, 0.45), (0.0, 1.1)]
        ray = (cq.Workplane("XZ").polyline(pts).close().extrude(-0.8)
               .rotate((0, 0, 0), (0, 1, 0), -math.degrees(a)))
        rays = ray if rays is None else rays.union(ray)
    rays = rays.union(cq.Workplane("XZ").circle(2.2).extrude(-0.8))
    return rays.translate((0, OUTER_BACK - 0.2, -6.0))


def diffuser():
    """Frosted light bar: a plug in the top slot over a channel that holds the LED strip."""
    sl, sw = STRIP[0], STRIP[1]
    plug = (cq.Workplane("XY").slot2D(SLOT[0] - 0.3, SLOT[1] - 0.3).extrude(WALL + 0.8)
            .translate((0, STRIP_Y, INNER_TOP)).faces(">Z").edges().fillet(0.7))
    channel = box(-sl / 2 - 3, sl / 2 + 3, STRIP_Y - sw / 2 - 2, STRIP_Y + sw / 2 + 2, STRIP_Z - 1.2, INNER_TOP)
    channel = channel.cut(box(-sl / 2 - 0.4, sl / 2 + 0.4, STRIP_Y - sw / 2 - 0.3, STRIP_Y + sw / 2 + 0.3,
                              STRIP_Z - 5, INNER_TOP - 0.8))
    lips = (box(-sl / 2 - 0.4, sl / 2 - 6, STRIP_Y - sw / 2 - 0.3, STRIP_Y - sw / 2 + 0.9, STRIP_Z - 1.2, STRIP_Z)
            .union(box(-sl / 2 - 0.4, sl / 2 - 6, STRIP_Y + sw / 2 - 0.9, STRIP_Y + sw / 2 + 0.3, STRIP_Z - 1.2, STRIP_Z)))
    channel = channel.union(lips)
    # open end for the micro-USB plug of the strip
    channel = channel.cut(box(sl / 2 - 1, BIG, STRIP_Y - sw / 2 - 0.3, STRIP_Y + sw / 2 + 0.3, STRIP_Z - 5, STRIP_Z + 0.2))
    return plug.union(channel).intersect(inner_solid(-WALL - 1.0))


def feet():
    out = []
    y = (OUTER_FRONT + OUTER_BACK) / 2
    for x in (-36.0, 36.0):
        f = (cq.Workplane("XY").slot2D(30, 11).extrude(FOOT_H).edges(">Z or <Z").fillet(1.2)
             .translate((x, y, OUTER_BOTTOM - FOOT_H + 0.01)))
        out.append(f)
    return out
