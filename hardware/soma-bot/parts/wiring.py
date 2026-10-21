"""Everything plugged into the Pi: the two eye screens with their USB boards, the LED strip,
the USB sound card with its speaker, the power bank with its charging socket, and the cables.

The Pi's four USB ports are all used: left eye, right eye, LED strip, sound card. Cables are
swept along splines; tools/collide.py checks that they clear everything.
"""
import cadquery as cq

from .params import (BANK, BANK_MIN, BANK_PORTS_Y, BANK_USBA_DZ, BANK_USBC_DZ, BLACK, CABLE, DARK, EYE_COLS, FRONT_IN,
                     GLASS, GOLD, INNER_BOTTOM, INNER_X, LED_COLOURS, MCP, METAL, OLED_DEPTH, OLED_GLASS, OLED_PCB,
                     OUTER_X, PCB_BLACK, PI_FAR_EDGE, PI_USB_BY, PI_USB_PORT_BZ, QT_BLUE, SOUND, SOUND_MIN, SPEAKER_D,
                     SPEAKER_DEPTH, SPEAKER_XY, STRIP, STRIP_LEDS, STRIP_Y, STRIP_Z, WHITE, pi_to_world)
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
    STEMMA QT cable between them and a right-angle USB-C plug bent towards the nearer side."""
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
    my0 = back + 0.5
    mcp = box(ex - mw / 2, ex + mw / 2, my0, my0 + 1.6, ez - mh / 2, ez + mh / 2)
    chip = box(ex - 2.5, ex + 2.5, my0 + 1.6, my0 + 2.6, ez - 2.5, ez + 2.5)
    usbc = box(ex - 4.5, ex + 4.5, my0 + 1.6, my0 + mt, ez + mh / 2 - 7.5, ez + mh / 2)
    qt_from = (ex + mw / 2 - 3.0, my0 + 1.0, ez - mh / 2 - 0.5)
    qt_to = (ex + pw / 2 - 4.5, back + 0.2, ez - ph / 2 + 3.2)
    qt_cable = cable([qt_from, (qt_to[0] + 0.5, back + 2.5, qt_from[2] - 3.0), qt_to], 0.9, (0, 0, -1), (0, -1, 0),
                     pull=4)
    sgn = -1 if side == "left" else 1
    top = ez + mh / 2
    uy = my0 + 1.6 + (mt - 1.6) / 2
    tongue = box(ex - 4.1, ex + 4.1, uy - 1.2, uy + 1.2, top - 6.0, top)
    housing = box(ex - 5.0, ex + 5.0, uy - 3.2, uy + 3.2, top, top + 8.0).union(
        cyl(2.4, 4.0, (ex + sgn * 5.0, uy, top + 4.0), (sgn, 0, 0)))
    exit_point = (ex + sgn * 9.0, uy, top + 4.0)
    parts = [(f"OLED glass ({side} eye)", glass, GLASS), (f"OLED board ({side} eye)", pcb, PCB_BLACK),
             (f"OLED STEMMA QT sockets ({side} eye)", qt, WHITE), (f"MCP2221A board ({side} eye)", mcp, PCB_BLACK),
             (f"MCP2221A chip ({side} eye)", chip, BLACK), (f"MCP2221A USB-C ({side} eye)", usbc, METAL),
             (f"STEMMA QT cable ({side} eye)", qt_cable, QT_BLUE),
             (f"USB-C plug ({side} eye)", tongue, METAL), (f"USB-C plug housing ({side} eye)", housing, CABLE)]
    return parts, exit_point, (sgn, 0, 0)


# --- LED strip (BlinkStick Strip) -------------------------------------------------------------

def led_strip(state):
    sl, sw, st = STRIP
    pcb = box(-sl / 2, sl / 2, STRIP_Y - sw / 2, STRIP_Y + sw / 2, STRIP_Z, STRIP_Z + st)
    pitch = 10.0
    leds = cq.Workplane()
    lenses = cq.Workplane()
    for i in range(STRIP_LEDS):
        x = (i - (STRIP_LEDS - 1) / 2) * pitch
        leds = leds.add(box(x - 2.5, x + 2.5, STRIP_Y - 2.5, STRIP_Y + 2.5, STRIP_Z + st, STRIP_Z + st + 1.2).val())
        lenses = lenses.add(cq.Solid.makeCylinder(1.8, 0.4, cq.Vector(x, STRIP_Y, STRIP_Z + st + 1.2)))
    socket = box(sl / 2 - 5.0, sl / 2 + 0.6, STRIP_Y - 3.75, STRIP_Y + 3.75, STRIP_Z - 2.6, STRIP_Z)
    return [("LED strip PCB", pcb, (0.05, 0.05, 0.06)),
            ("WS2812 LEDs", leds, WHITE),
            ("LED glow", lenses, LED_COLOURS[state]),
            ("LED strip micro-USB socket", socket, METAL)]


# --- USB-A plugs --------------------------------------------------------------------------------

def usb_port_center(stack, upper=True):
    """World centre of a Pi USB port opening: x at the port face."""
    return pi_to_world(PI_FAR_EDGE, PI_USB_BY[stack], PI_USB_PORT_BZ[1 if upper else 0])


def right_angle_usb_a(port, up):
    """USB-A plug in a port facing +X (`port` = centre of the opening), its housing bent up (+Z)
    or down (-Z). The opening's long side runs along Z."""
    x, y, z = port
    shell = box(x - 8.5, x, y - 2.2, y + 2.2, z - 6.0, z + 6.0)
    shell = shell.cut(box(x - 9.0, x + 0.1, y + 0.1, y + 2.1, z - 5.7, z + 5.7))  # room for the port's tongue
    sgn = 1 if up else -1
    housing = box(x, x + 7.8, y - 3.8, y + 3.8, z - 7.5, z + 7.5)
    boot = cyl(2.6, 4.0, (x + 4.2, y, z + sgn * 7.5), (0, 0, sgn))
    exit_point = (x + 4.2, y, z + sgn * 11.5)
    return shell, housing.union(boot), exit_point


# --- sound: Waveshare USB TO AUDIO on a short USB extension, speaker on the floor ---------------

def sound_card():
    """Returns the parts, the cable end of the extension socket and the speaker header."""
    x0, y0, z0 = SOUND_MIN
    sl, sw, sh = SOUND
    cy, cz = y0 + sw / 2, z0 + sh / 2
    body = box(x0, x0 + sl - 12.0, y0, y0 + sw, z0, z0 + sh).edges("|X").fillet(2.0)
    plug = box(x0 + sl - 12.0, x0 + sl, cy - 6.0, cy + 6.0, cz - 2.2, cz + 2.2)
    header = box(x0 + 8.0, x0 + 14.0, y0 + sw, y0 + sw + 4.5, cz - 2.5, cz + 2.5)
    sx1 = x0 + sl
    socket = box(sx1 - 8.0, sx1 + 6.0, cy - 7.5, cy + 7.5, cz - 4.0, cz + 4.0)
    socket = socket.cut(box(sx1 - 9.0, sx1 - 0.5, cy - 6.4, cy + 6.4, cz - 2.6, cz + 2.6))
    parts = [("USB TO AUDIO sound card", body, (0.10, 0.25, 0.55)), ("Sound card USB plug", plug, METAL),
             ("Sound card speaker header PH2.0", header, WHITE), ("USB extension socket (sound)", socket, BLACK)]
    return parts, (sx1 + 6.0, cy, cz), (x0 + 11.0, y0 + sw + 4.5, cz)


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
    """Redmi 20000 mAh standing behind the Pi, ports on its right end."""
    x0, y0, z0 = BANK_MIN
    bx, by, bz = BANK
    x1 = x0 + bx
    body = box(x0, x1, y0, y0 + by, z0, z0 + bz).edges().fillet(4.0)
    y = BANK_PORTS_Y
    for dz in BANK_USBA_DZ:
        body = body.cut(box(x1 - 9.0, x1 + 1, y - 2.5, y + 2.5, z0 + dz - 6.0, z0 + dz + 6.0))  # long side vertical
    zc = z0 + BANK_USBC_DZ
    body = body.cut(box(x1 - 7.0, x1 + 1, y - 4.4, y + 4.4, zc - 1.6, zc + 1.6))
    dots = cq.Workplane()
    for i in range(4):
        dots = dots.add(cq.Solid.makeCylinder(0.8, 0.3, cq.Vector(x1 - 30 - i * 4, y0 + by, z0 + bz - 8),
                                              cq.Vector(0, 1, 0)))
    return [("Power bank Redmi 20000", body, (0.17, 0.18, 0.20)), ("Power bank level LEDs", dots, WHITE)]


def charge_extension():
    """Straight USB-C panel-mount extension: plug in the bank's USB-C port, socket in the right wall."""
    x1 = BANK_MIN[0] + BANK[0]
    y, z = BANK_PORTS_Y, BANK_MIN[2] + BANK_USBC_DZ
    plug = box(x1 - 6.5, x1, y - 4.1, y + 4.1, z - 1.3, z + 1.3)
    body = box(x1, INNER_X - 1.0, y - 4.0, y + 4.0, z - 4.0, z + 4.0).edges("|X").fillet(1.5)
    socket = box(INNER_X - 1.0, OUTER_X - 0.3, y - 4.4, y + 4.4, z - 1.6, z + 1.6)
    socket = socket.cut(box(INNER_X, OUTER_X + 1, y - 3.6, y + 3.6, z - 0.9, z + 0.9))
    return [("USB-C extension plug (bank)", plug, METAL), ("USB-C panel extension", body, BLACK),
            ("USB-C charging socket", socket, METAL)]


def micro_usb_plug():
    """Right-angle micro-USB plug in the Pi's power socket, the cable leaving towards the back."""
    x, y, z = pi_to_world(10.6, 0.0, 1.5)
    tongue = box(x - 3.4, x + 3.4, y - 0.9, y + 0.9, z - 4.5, z + 0.5)
    housing = box(x - 5.5, x + 5.5, y - 3.5, y + 5.0, z + 0.5, z + 8.5)
    boot = cyl(2.4, 3.5, (x, y + 5.0, z + 4.5), (0, 1, 0))
    return tongue, housing.union(boot), (x, y + 8.5, z + 4.5)


def parts(led_state):
    out = []
    out += led_strip(led_state)
    out += power_bank()
    out += charge_extension()
    spk, spk_end = speaker()
    out += spk
    snd, snd_socket_end, snd_header = sound_card()
    out += snd

    ends = {}
    for col, side in zip(EYE_COLS, ("left", "right")):
        p, exit_point, t = eye(col, side)
        out += p
        ends[side] = (exit_point, t)

    # left eye <- upper port of the middle stack, plug bent up, cable over the Pi
    shell, housing, a_exit = right_angle_usb_a(usb_port_center(0, upper=True), up=True)
    (lx, ly, lz), lt = ends["left"]
    eye_l_cable = cable([a_exit, (51.0, -2.5, 98.0), (46.0, -2.5, 110.0), (30.0, -2.5, 118.5), (0.0, -2.5, 119.5),
                         (-30.0, -2.5, 119.5), (-52.0, -5.0, 118.0), (lx - 7.0, ly + 7.0, lz + 3.0), (lx, ly, lz)],
                        1.75, (0, 0, 1), (-lt[0], 0, 0), pull=5)
    out += [("USB plug (left eye)", shell, METAL), ("USB plug housing (left eye)", housing, CABLE),
            ("Cable left eye", eye_l_cable, CABLE)]

    # right eye <- lower port of the middle stack, plug bent up
    shell, housing, b_exit = right_angle_usb_a(usb_port_center(0, upper=False), up=True)
    (rx, ry, rz), rt = ends["right"]
    eye_r_cable = cable([b_exit, (58.0, -16.0, 98.0), (rx + 3.0, ry + 2.0, rz - 3.0), (rx, ry, rz)], 1.75,
                        (0, 0, 1), (-rt[0], 0, 0), pull=8)
    out += [("USB plug (right eye)", shell, METAL), ("USB plug housing (right eye)", housing, CABLE),
            ("Cable right eye", eye_r_cable, CABLE)]

    # LED strip <- upper port of the bottom stack, plug bent down, cable up the right side
    shell, housing, c_exit = right_angle_usb_a(usb_port_center(1, upper=True), up=False)
    strip_plug = box(STRIP[0] / 2, STRIP[0] / 2 + 8.0, STRIP_Y - 4.4, STRIP_Y + 4.4, STRIP_Z - 4.6, STRIP_Z - 0.4)
    strip_tongue = box(STRIP[0] / 2 - 4.4, STRIP[0] / 2, STRIP_Y - 3.4, STRIP_Y + 3.4, STRIP_Z - 2.1, STRIP_Z - 0.6)
    sp_end = (STRIP[0] / 2 + 4.5, STRIP_Y, STRIP_Z - 4.6)
    led_cable = cable([c_exit, (57.0, -4.0, 38.0), (61.0, -4.0, 50.0), (61.0, -6.0, 100.0), (53.0, -10.0, 116.0),
                       sp_end], 1.75, (0, 0, -1), (0, 0, 1), pull=8)
    out += [("USB plug (LED strip)", shell, METAL), ("USB plug housing (LED strip)", housing, CABLE),
            ("Micro-USB plug (LED strip)", strip_plug, CABLE), ("Micro-USB tongue (LED strip)", strip_tongue, METAL),
            ("Cable LED strip", led_cable, CABLE)]

    # sound card <- lower port of the bottom stack, plug bent down, extension along the floor
    shell, housing, d_exit = right_angle_usb_a(usb_port_center(1, upper=False), up=False)
    snd_cable = cable([snd_socket_end, (0.0, -19.0, 38.0), (20.0, -18.5, 38.0), (38.0, -14.0, 38.0),
                       (48.0, -13.1, 38.5), d_exit], 1.75, (1, 0, 0), (0, 0, 1), pull=4)
    out += [("USB plug (sound card)", shell, METAL), ("USB plug housing (sound card)", housing, CABLE),
            ("Cable sound card", snd_cable, CABLE)]

    # speaker <- sound card amplifier
    spk_cable = cable([snd_header, (snd_header[0] + 3.0, 0.5, 35.0), (-55.0, 0.5, 34.0), (-40.0, 0.5, 34.0),
                       (-25.0, 0.5, 34.0), (-8.0, -1.0, 36.0), (spk_end[0] - 2.0, -9.0, 39.0), spk_end], 1.1,
                      (0, 1, 0), (0, 0, -1), pull=2)
    out += [("Cable speaker", spk_cable, (0.55, 0.12, 0.10))]

    # power: bank USB-A (bent up) -> over the bank -> Pi micro-USB
    tongue, housing, pwr_exit = micro_usb_plug()
    bank_port = (BANK_MIN[0] + BANK[0], BANK_PORTS_Y, BANK_MIN[2] + BANK_USBA_DZ[0])
    shell, bank_housing, bank_exit = right_angle_usb_a(bank_port, up=True)
    pwr_cable = cable([pwr_exit, (-27.0, -2.0, 111.0), (-20.0, 2.0, 114.0), (0.0, 6.0, 115.0), (25.0, 9.0, 115.0),
                       (50.0, 12.0, 115.0), (64.0, 14.0, 116.0), (73.0, 15.5, 115.0), (bank_exit[0], bank_exit[1], bank_exit[2] + 14.0),
                       bank_exit], 1.75, (0, 1, 0), (0, 0, -1), pull=4)
    out += [("Micro-USB plug (power)", tongue, METAL), ("Micro-USB plug housing (power)", housing, CABLE),
            ("USB plug (power bank)", shell, METAL), ("USB plug housing (power bank)", bank_housing, CABLE),
            ("Cable power", pwr_cable, CABLE)]
    return out
