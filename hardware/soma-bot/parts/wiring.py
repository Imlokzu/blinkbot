"""Everything plugged into the Pi: the two eye screens with their USB boards, the LED strip,
the USB sound card with its speaker, the power bank with its charging socket, and the cables.

The Pi lies on the floor with its USB ports facing the back; all four are used: left eye,
right eye, LED strip, sound card. Cables are swept along splines; tools/collide.py checks that
they clear everything.
"""
import cadquery as cq

from .params import (BANK, BANK_MIN, BANK_PORTS_Z, BANK_USBA_Y, BANK_USBC_Y, BLACK, CABLE, DARK, EYE_COLS, FRONT_IN,
                     GLASS, GOLD, INNER_BOTTOM, INNER_X, LED_COLOURS, MCP, MCP_DROP, METAL, OLED_DEPTH, OLED_GLASS,
                     OLED_PCB, OUTER_X, PCB_BLACK, PI_FAR_EDGE, PI_USB_BY, PI_USB_PORT_BZ, QT_BLUE, SOUND, SOUND_MIN,
                     SPEAKER_D, SPEAKER_DEPTH, SPEAKER_XY, STRIP, STRIP_LEDS, STRIP_Y, STRIP_Z, WHITE, pi_to_world)
from .body import box, cyl, eye_center


def cable(points, r, t0, t1, pull=None):
    """Round cable through `points`, leaving along t0 and arriving along t1. `pull` (mm) fixes
    how far the end directions reach; without it they scale with the cable length, which can
    make a long cable loop near a short last segment."""
    vs = [cq.Vector(*p) for p in points]
    if pull:
        tangents = [cq.Vector(*t0).normalized() * pull, cq.Vector(*t1).normalized() * pull]
        edge = cq.Edge.makeSpline(vs, tangents=tangents, scale=False)
    else:
        edge = cq.Edge.makeSpline(vs, tangents=[cq.Vector(*t0).normalized(), cq.Vector(*t1).normalized()])
    profile = cq.Wire.makeCircle(r, vs[0], edge.tangentAt(0))
    return cq.Solid.sweep(profile, [], cq.Wire.assembleEdges([edge]), True, False)


# --- eye screens: 0.96" OLED behind each eye pixel, MCP2221A behind it ------------------------

def eye(col, side):
    """OLED with its glass against the front wall, the MCP2221A board standing behind it, the
    STEMMA QT cable between them and a right-angle USB-C plug on top, bent towards the back."""
    ex, ez = eye_center(col)
    pw, ph, pt = OLED_PCB
    gw, gh, gt = OLED_GLASS
    y0 = FRONT_IN
    glass = box(ex - gw / 2, ex + gw / 2, y0, y0 + gt, ez - gh / 2, ez + gh / 2)
    pcb = box(ex - pw / 2, ex + pw / 2, y0 + gt, y0 + gt + pt, ez - ph / 2, ez + ph / 2)
    back = y0 + OLED_DEPTH
    qt = (box(ex - pw / 2 + 1.5, ex - pw / 2 + 7.5, y0 + gt + pt, back, ez - ph / 2 + 1.0, ez - ph / 2 + 5.5)
          .union(box(ex + pw / 2 - 7.5, ex + pw / 2 - 1.5, y0 + gt + pt, back, ez - ph / 2 + 1.0, ez - ph / 2 + 5.5)))
    mw, mh, mt = MCP
    mz = ez - MCP_DROP
    my0 = back + 0.5
    mcp = box(ex - mw / 2, ex + mw / 2, my0, my0 + 1.6, mz - mh / 2, mz + mh / 2)
    chip = box(ex - 2.5, ex + 2.5, my0 + 1.6, my0 + 2.6, mz - 2.5, mz + 2.5)
    usbc = box(ex - 4.5, ex + 4.5, my0 + 1.6, my0 + mt, mz + mh / 2 - 7.5, mz + mh / 2)
    qt_from = (ex + mw / 2 - 3.0, my0 + 1.0, mz - mh / 2 - 0.5)
    qt_to = (ex + pw / 2 - 4.5, back + 0.2, ez - ph / 2 + 3.2)
    qt_cable = cable([qt_from, (qt_to[0] + 0.5, back + 1.2, min(qt_from[2], qt_to[2]) - 2.5), qt_to], 0.9,
                     (0, 0, -1), (0, -1, 0), pull=2)
    top = mz + mh / 2
    uy = my0 + 1.6 + (mt - 1.6) / 2
    tongue = box(ex - 4.1, ex + 4.1, uy - 1.2, uy + 1.2, top - 6.0, top)
    housing = box(ex - 5.0, ex + 5.0, uy - 3.2, uy + 7.0, top, top + 8.0).union(
        cyl(2.4, 4.0, (ex, uy + 7.0, top + 4.0), (0, 1, 0)))
    exit_point = (ex, uy + 11.0, top + 4.0)
    parts = [(f"OLED glass ({side} eye)", glass, GLASS), (f"OLED board ({side} eye)", pcb, PCB_BLACK),
             (f"OLED STEMMA QT sockets ({side} eye)", qt, WHITE), (f"MCP2221A board ({side} eye)", mcp, PCB_BLACK),
             (f"MCP2221A chip ({side} eye)", chip, BLACK), (f"MCP2221A USB-C ({side} eye)", usbc, METAL),
             (f"STEMMA QT cable ({side} eye)", qt_cable, QT_BLUE),
             (f"USB-C plug ({side} eye)", tongue, METAL), (f"USB-C plug housing ({side} eye)", housing, CABLE)]
    return parts, exit_point


# --- LED strip (BlinkStick Strip) across the top, socket at the right end ----------------------

def led_strip(state):
    sl, sw, st = STRIP
    x1 = sl / 2
    pcb = box(-sl / 2, x1, STRIP_Y - sw / 2, STRIP_Y + sw / 2, STRIP_Z, STRIP_Z + st)
    pitch = 10.0
    leds = cq.Workplane()
    lenses = cq.Workplane()
    for i in range(STRIP_LEDS):
        x = (i - (STRIP_LEDS - 1) / 2) * pitch
        leds = leds.add(box(x - 2.5, x + 2.5, STRIP_Y - 2.5, STRIP_Y + 2.5, STRIP_Z + st, STRIP_Z + st + 1.2).val())
        lenses = lenses.add(cq.Solid.makeCylinder(1.8, 0.4, cq.Vector(x, STRIP_Y, STRIP_Z + st + 1.2)))
    socket = box(x1 - 5.0, x1 + 0.6, STRIP_Y - 3.75, STRIP_Y + 3.75, STRIP_Z - 2.6, STRIP_Z)
    plug = box(x1 + 0.6, x1 + 8.0, STRIP_Y - 4.4, STRIP_Y + 4.4, STRIP_Z - 4.6, STRIP_Z - 0.4)
    tongue = box(x1 - 4.4, x1 + 0.6, STRIP_Y - 3.4, STRIP_Y + 3.4, STRIP_Z - 2.1, STRIP_Z - 0.6)
    boot = cyl(2.2, 3.5, (x1 + 4.0, STRIP_Y, STRIP_Z - 4.6), (0, 0, -1))
    return [("LED strip PCB", pcb, (0.05, 0.05, 0.06)),
            ("WS2812 LEDs", leds, WHITE),
            ("LED glow", lenses, LED_COLOURS[state]),
            ("LED strip micro-USB socket", socket, METAL),
            ("Micro-USB plug (LED strip)", plug.union(boot), CABLE),
            ("Micro-USB tongue (LED strip)", tongue, METAL)], (x1 + 4.0, STRIP_Y, STRIP_Z - 8.1)


# --- USB-A plugs --------------------------------------------------------------------------------

def usb_port_center(stack, upper=True):
    """World centre of a Pi USB port opening, at the port face (facing +Y)."""
    return pi_to_world(PI_FAR_EDGE, PI_USB_BY[stack], PI_USB_PORT_BZ[1 if upper else 0])


def pi_usb_a(port, up):
    """Right-angle USB-A plug in a Pi port facing +Y (long side along X), bent up or down."""
    x, y, z = port
    shell = box(x - 6.0, x + 6.0, y - 8.5, y, z - 2.2, z + 2.2)
    shell = shell.cut(box(x - 5.7, x + 5.7, y - 9.0, y + 0.1, z + 0.1, z + 2.1))  # room for the port's tongue
    sgn = 1 if up else -1
    housing = box(x - 7.5, x + 7.5, y, y + 7.8, z - 3.8, z + 3.8)
    boot = cyl(2.6, 4.0, (x, y + 3.9, z + sgn * 3.8), (0, 0, sgn))
    return shell, housing.union(boot), (x, y + 3.9, z + sgn * 7.8)


def bank_usb_a_down(y):
    """Right-angle USB-A plug in a power bank port (right end, facing +X, long side along Y), bent down."""
    x = BANK_MIN[0] + BANK[0]
    z = BANK_PORTS_Z
    shell = box(x - 8.5, x, y - 6.0, y + 6.0, z - 2.2, z + 2.2)
    housing = box(x, x + 7.8, y - 7.5, y + 7.5, z - 3.8, z + 3.8)
    boot = cyl(2.6, 4.0, (x + 3.9, y, z - 3.8), (0, 0, -1))
    return shell, housing.union(boot), (x + 3.9, y, z - 7.8)


# --- sound: Waveshare USB TO AUDIO standing behind the bank, speaker on the floor ----------------

def sound_card():
    """Returns the parts, the cable end of the extension socket and the speaker header."""
    x0, y0, z0 = SOUND_MIN
    sl, sw, sh = SOUND
    cy, cz = y0 + sw / 2, z0 + sh / 2
    body = box(x0, x0 + sl - 12.0, y0, y0 + sw, z0, z0 + sh).edges("|X").fillet(2.0)
    plug = box(x0 + sl - 12.0, x0 + sl, cy - 2.2, cy + 2.2, cz - 6.0, cz + 6.0)
    header = box(x0 + 16.0, x0 + 22.0, y0 + sw, y0 + sw + 4.5, z0 + 3.0, z0 + 8.0)  # between the eye cables
    sx1 = x0 + sl
    socket = box(sx1 - 8.0, sx1 + 6.0, cy - 4.0, cy + 4.0, cz - 7.5, cz + 7.5)
    socket = socket.cut(box(sx1 - 9.0, sx1 - 0.5, cy - 2.6, cy + 2.6, cz - 6.4, cz + 6.4))
    parts = [("USB TO AUDIO sound card", body, (0.10, 0.25, 0.55)), ("Sound card USB plug", plug, METAL),
             ("Sound card speaker header PH2.0", header, WHITE), ("USB extension socket (sound)", socket, BLACK)]
    return parts, (sx1 + 6.0, cy, cz), (x0 + 19.0, y0 + sw + 4.5, z0 + 5.5)


def speaker():
    """Lies on the floor ring, cone facing down through the belly grille."""
    x, y = SPEAKER_XY
    r = SPEAKER_D / 2
    z0 = INNER_BOTTOM + 0.6
    up = (0, 0, 1)
    frame = cyl(r, 1.2, (x, y, z0), up).cut(cyl(r - 1.6, 2, (x, y, z0 - 0.5), up))
    cone = cq.Workplane().add(cq.Solid.makeCone(r - 1.6, 5.5, SPEAKER_DEPTH - 2.0, cq.Vector(x, y, z0 + 0.4),
                                                cq.Vector(*up)))
    magnet = cyl(8.0, 3.0, (x, y, z0 + SPEAKER_DEPTH - 3.0), up)
    terminals = box(x + r - 6.0, x + r - 2.0, y - 1.5, y + 1.5, z0 + 3.6, z0 + 5.0)
    return [("Speaker frame", frame, METAL), ("Speaker cone", cone, DARK), ("Speaker magnet", magnet, (0.35, 0.35, 0.36)),
            ("Speaker terminals", terminals, GOLD)], (x + r - 4.0, y, z0 + 5.0)


# --- power bank and charging --------------------------------------------------------------------

def power_bank():
    """Compact 10000 mAh pack lying on the shelf over the Pi, ports on its right end."""
    x0, y0, z0 = BANK_MIN
    bx, by, bz = BANK
    x1 = x0 + bx
    body = box(x0, x1, y0, y0 + by, z0, z0 + bz).edges().fillet(4.0)
    z = BANK_PORTS_Z
    for y in BANK_USBA_Y:
        body = body.cut(box(x1 - 9.0, x1 + 1, y - 6.0, y + 6.0, z - 2.5, z + 2.5))
    body = body.cut(box(x1 - 7.0, x1 + 1, BANK_USBC_Y - 4.4, BANK_USBC_Y + 4.4, z - 1.6, z + 1.6))
    dots = cq.Workplane()
    for i in range(4):
        dots = dots.add(cq.Solid.makeCylinder(0.8, 0.3, cq.Vector(x0 + 20 + i * 4, y0 + 8, z0 + bz),
                                              cq.Vector(0, 0, 1)))
    return [("Power bank 10000 mAh", body, (0.17, 0.18, 0.20)), ("Power bank level LEDs", dots, WHITE)]


def charge_extension():
    """Straight USB-C panel-mount extension: plug in the bank's USB-C port, socket in the right wall."""
    x1 = BANK_MIN[0] + BANK[0]
    y, z = BANK_USBC_Y, BANK_PORTS_Z
    plug = box(x1 - 6.5, x1, y - 4.1, y + 4.1, z - 1.3, z + 1.3)
    body = box(x1, INNER_X - 1.0, y - 4.5, y + 4.5, z - 4.0, z + 4.0).edges("|X").fillet(1.5)
    socket = box(INNER_X - 1.0, OUTER_X - 0.3, y - 4.4, y + 4.4, z - 1.6, z + 1.6)
    socket = socket.cut(box(INNER_X, OUTER_X + 1, y - 3.6, y + 3.6, z - 0.9, z + 0.9))
    return [("USB-C extension plug (bank)", plug, METAL), ("USB-C panel extension", body, BLACK),
            ("USB-C charging socket", socket, METAL)]


def micro_usb_plug():
    """Right-angle micro-USB plug in the Pi's power socket (right edge, facing +X), bent up."""
    x, y, z = pi_to_world(10.6, -1.4, 1.5)
    tongue = box(x - 4.5, x + 0.5, y - 3.4, y + 3.4, z - 0.9, z + 0.9)
    housing = box(x + 0.5, x + 8.5, y - 5.5, y + 5.5, z - 1.5, z + 6.5)
    boot = cyl(2.4, 3.5, (x + 4.5, y, z + 6.5), (0, 0, 1))
    return tongue, housing.union(boot), (x + 4.5, y, z + 10.0)


def parts(led_state):
    out = []
    strip_parts, strip_end = led_strip(led_state)
    out += strip_parts
    out += power_bank()
    out += charge_extension()
    spk, spk_end = speaker()
    out += spk
    snd, snd_socket_end, snd_header = sound_card()
    out += snd

    ends = {}
    for col, side in zip(EYE_COLS, ("left", "right")):
        p, exit_point = eye(col, side)
        out += p
        ends[side] = exit_point

    # eyes <- the upper ports, plugs bent up; the cables rise behind the sound card and run
    # forward over the power bank, under the light bar
    shell, housing, a = pi_usb_a(usb_port_center(1, upper=True), up=True)
    lx, ly, lz = ends["left"]
    eye_l_cable = cable([a, (a[0] - 0.5, a[1] + 0.5, 64.0), (a[0] - 0.5, a[1] - 1.5, 70.5), (-37.0, 30.0, 71.0),
                         (-35.5, -10.0, 71.0), (lx, ly + 8.5, 71.5), (lx, ly, lz)], 1.75, (0, 0, 1), (0, -1, 0),
                        pull=4)
    out += [("USB plug (left eye)", shell, METAL), ("USB plug housing (left eye)", housing, CABLE),
            ("Cable left eye", eye_l_cable, CABLE)]

    shell, housing, b = pi_usb_a(usb_port_center(0, upper=True), up=True)
    rx, ry, rz = ends["right"]
    eye_r_cable = cable([b, (b[0] + 1.0, b[1] + 0.5, 64.0), (-8.0, 38.0, 70.5), (15.0, 15.0, 71.0),
                         (30.0, -14.0, 71.0), (rx, ry + 8.5, 71.5), (rx, ry, rz)], 1.75, (0, 0, 1), (0, -1, 0),
                        pull=4)
    out += [("USB plug (right eye)", shell, METAL), ("USB plug housing (right eye)", housing, CABLE),
            ("Cable right eye", eye_r_cable, CABLE)]

    # LED strip <- lower port of the inner stack, plug bent down; along the back wall and up the right side
    shell, housing, c = pi_usb_a(usb_port_center(0, upper=False), up=False)
    led_cable = cable([c, (c[0] + 0.2, c[1] + 1.5, 21.6), (c[0] + 2.0, c[1] + 2.8, 22.0), (-10.0, 45.3, 23.0),
                       (5.0, 45.3, 27.0), (25.0, 45.3, 33.0), (40.0, 45.0, 40.0), (47.0, 38.0, 50.0),
                       (48.5, 28.0, 58.0), (48.5, 10.0, 63.0), (47.0, -4.0, 64.0), strip_end],
                      1.75, (0, 0, -1), (0, 0, 1), pull=1.5)
    out += [("USB plug (LED strip)", shell, METAL), ("USB plug housing (LED strip)", housing, CABLE),
            ("Cable LED strip", led_cable, CABLE)]

    # sound card <- lower port of the outer stack, plug bent down; along the back wall to its socket
    shell, housing, d = pi_usb_a(usb_port_center(1, upper=False), up=False)
    sx, sy_, sz = snd_socket_end
    snd_cable = cable([d, (d[0] + 0.2, d[1] + 2.0, 21.6), (d[0] + 2.0, d[1] + 5.0, 22.0), (-30.0, 48.8, 23.0),
                       (-15.0, 48.8, 25.0), (0.0, 48.8, 30.0), (12.0, 48.0, 37.0), (20.0, 46.5, 44.0),
                       (sx + 10.0, sy_ + 4.0, sz), (sx, sy_, sz)], 1.75, (0, 0, -1), (-1, 0, 0), pull=1.5)
    out += [("USB plug (sound card)", shell, METAL), ("USB plug housing (sound card)", housing, CABLE),
            ("Cable sound card", snd_cable, CABLE)]

    # speaker <- sound card amplifier, behind the rising eye cables and down the right side
    spk_cable = cable([snd_header, (snd_header[0] + 2.0, 47.0, snd_header[2] + 3.0), (0.0, 47.0, 58.0),
                       (30.0, 47.0, 57.0), (44.0, 30.0, 38.0), (spk_end[0], 6.0, 28.0), spk_end], 1.1,
                      (0, 1, 0), (0, 0, -1), pull=3)
    out += [("Cable speaker", spk_cable, (0.55, 0.12, 0.10))]

    # power: bank USB-A (right end, bent down) -> down past the shelf -> Pi micro-USB
    tongue, housing, pwr_exit = micro_usb_plug()
    shell, bank_housing, bank_exit = bank_usb_a_down(BANK_USBA_Y[0])
    pwr_cable = cable([bank_exit, (bank_exit[0], bank_exit[1] - 4.0, 38.0), (28.0, -40.0, 38.0),
                       (pwr_exit[0] + 2.0, pwr_exit[1], pwr_exit[2] + 2.5), pwr_exit],
                      1.75, (0, 0, -1), (0, 0, -1), pull=4)
    out += [("Micro-USB plug (power)", tongue, METAL), ("Micro-USB plug housing (power)", housing, CABLE),
            ("USB plug (power bank)", shell, METAL), ("USB plug housing (power bank)", bank_housing, CABLE),
            ("Cable power", pwr_cable, CABLE)]
    return out
