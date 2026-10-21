"""Everything plugged into the Pi: the two eye screens with their USB boards, the LED strip,
the USB sound card with its speaker, the power bank with its charging socket, and the cables.

The Pi lies on a shelf over the power bank with its USB ports facing the back. All four are
used: left eye, right eye, LED strip, sound card. Cables are swept along splines;
tools/collide.py checks that they clear everything.
"""
import cadquery as cq

from .params import (BANK, BANK_MIN, BANK_PORTS_Z, BANK_USBA_X, BANK_USBC_X, BLACK, CABLE, CHARGE_SOCKET, DARK,
                     EYE_COLS, FRONT_IN, GLASS, GOLD, INNER_X, LED_COLOURS, MCP, MCP_DROP, METAL, OLED_DEPTH,
                     OLED_GLASS, OLED_PCB, OUTER_X, PCB_BLACK, PI_FAR_EDGE, PI_USB_BY, PI_USB_PORT_BZ, QT_BLUE, SOUND,
                     SOUND_MIN, SPEAKER_D, SPEAKER_DEPTH, SPEAKER_YZ, STRIP, STRIP_LEDS, STRIP_Y0, STRIP_Z, WHITE,
                     pi_to_world)
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


# --- LED strip (BlinkStick Strip) along the top, socket at the back end ------------------------

def led_strip(state):
    sl, sw, st = STRIP
    y0, y1 = STRIP_Y0, STRIP_Y0 + sl
    pcb = box(-sw / 2, sw / 2, y0, y1, STRIP_Z, STRIP_Z + st)
    pitch = 10.0
    leds = cq.Workplane()
    lenses = cq.Workplane()
    for i in range(STRIP_LEDS):
        y = (y0 + y1) / 2 + (i - (STRIP_LEDS - 1) / 2) * pitch
        leds = leds.add(box(-2.5, 2.5, y - 2.5, y + 2.5, STRIP_Z + st, STRIP_Z + st + 1.2).val())
        lenses = lenses.add(cq.Solid.makeCylinder(1.8, 0.4, cq.Vector(0, y, STRIP_Z + st + 1.2)))
    socket = box(-3.75, 3.75, y1 - 5.0, y1 + 0.6, STRIP_Z - 2.6, STRIP_Z)
    plug = box(-4.4, 4.4, y1 + 0.6, y1 + 8.0, STRIP_Z - 4.6, STRIP_Z - 0.4)
    tongue = box(-3.4, 3.4, y1 - 4.4, y1 + 0.6, STRIP_Z - 2.1, STRIP_Z - 0.6)
    boot = cyl(2.2, 3.5, (0, y1 + 8.0, STRIP_Z - 2.5), (0, 1, 0))
    return [("LED strip PCB", pcb, (0.05, 0.05, 0.06)),
            ("WS2812 LEDs", leds, WHITE),
            ("LED glow", lenses, LED_COLOURS[state]),
            ("LED strip micro-USB socket", socket, METAL),
            ("Micro-USB plug (LED strip)", plug.union(boot), CABLE),
            ("Micro-USB tongue (LED strip)", tongue, METAL)], (0, y1 + 11.5, STRIP_Z - 2.5)


# --- USB-A plugs --------------------------------------------------------------------------------

def usb_port_center(stack, upper=True):
    """World centre of a Pi USB port opening, at the port face (facing +Y)."""
    return pi_to_world(PI_FAR_EDGE, PI_USB_BY[stack], PI_USB_PORT_BZ[1 if upper else 0])


def straight_usb_a(port):
    """Short straight USB-A plug in a Pi port facing +Y; the cable leaves towards the back."""
    x, y, z = port
    shell = box(x - 6.0, x + 6.0, y - 8.5, y, z - 2.2, z + 2.2)
    shell = shell.cut(box(x - 5.7, x + 5.7, y - 9.0, y + 0.1, z + 0.1, z + 2.1))  # room for the port's tongue
    housing = box(x - 7.5, x + 7.5, y, y + 16.0, z - 3.7, z + 3.7)
    boot = cyl(2.6, 4.0, (x, y + 16.0, z), (0, 1, 0))
    return shell, housing.union(boot), (x, y + 20.0, z)


def bank_usb_a_up(x):
    """Right-angle USB-A plug in a power bank port (front end, facing -Y), bent up."""
    y = BANK_MIN[1]
    z = BANK_PORTS_Z
    shell = box(x - 6.0, x + 6.0, y, y + 8.5, z - 2.2, z + 2.2)
    housing = box(x - 7.5, x + 7.5, y - 7.8, y, z - 3.8, z + 3.8)
    boot = cyl(2.6, 4.0, (x, y - 3.9, z + 3.8), (0, 0, 1))
    return shell, housing.union(boot), (x, y - 3.9, z + 7.8)


# --- sound: Waveshare USB TO AUDIO on the shelf behind the Pi, speaker in the left side ---------

def sound_card():
    """Returns the parts, the cable end of the extension socket and the speaker header."""
    x0, y0, z0 = SOUND_MIN
    sl, sw, sh = SOUND
    cy, cz = y0 + sw / 2, z0 + sh / 2
    body = box(x0, x0 + sl - 12.0, y0, y0 + sw, z0, z0 + sh).edges("|X").fillet(2.0)
    plug = box(x0 + sl - 12.0, x0 + sl, cy - 6.0, cy + 6.0, cz - 2.2, cz + 2.2)
    header = box(x0 + 3.0, x0 + 9.0, y0 + 6.0, y0 + 10.5, z0 + sh, z0 + sh + 5.0)
    sx1 = x0 + sl
    socket = box(sx1 - 8.0, sx1 + 6.0, cy - 7.5, cy + 7.5, cz - 4.0, cz + 4.0)
    socket = socket.cut(box(sx1 - 9.0, sx1 - 0.5, cy - 6.4, cy + 6.4, cz - 2.6, cz + 2.6))
    parts = [("USB TO AUDIO sound card", body, (0.10, 0.25, 0.55)), ("Sound card USB plug", plug, METAL),
             ("Sound card speaker header PH2.0", header, WHITE), ("USB extension socket (sound)", socket, BLACK)]
    return parts, (sx1 + 6.0, cy, cz), (x0 + 6.0, y0 + 8.25, z0 + sh + 5.0)


def speaker():
    """Stands beside the power bank, cone facing out through the left wall."""
    y, z = SPEAKER_YZ
    r = SPEAKER_D / 2
    x0 = -INNER_X + 1.0
    right = (1, 0, 0)
    frame = cyl(r, 1.2, (x0, y, z), right).cut(cyl(r - 1.6, 2, (x0 - 0.5, y, z), right))
    cone = cq.Workplane().add(cq.Solid.makeCone(r - 1.6, 5.5, SPEAKER_DEPTH - 2.0, cq.Vector(x0 + 0.4, y, z),
                                                cq.Vector(*right)))
    magnet = cyl(6.0, 3.0, (x0 + SPEAKER_DEPTH - 3.0, y, z), right)
    terminals = box(x0 + 3.6, x0 + 5.0, y - 1.5, y + 1.5, z + r - 6.0, z + r - 2.0)
    return [("Speaker frame", frame, METAL), ("Speaker cone", cone, DARK), ("Speaker magnet", magnet, (0.35, 0.35, 0.36)),
            ("Speaker terminals", terminals, GOLD)], (x0 + 4.3, y, z + r - 2.0)


# --- power bank and charging --------------------------------------------------------------------

def power_bank():
    """Redmi 20000 mAh lying flat under the shelf, ports at its front end."""
    x0, y0, z0 = BANK_MIN
    bx, by, bz = BANK
    body = box(x0, x0 + bx, y0, y0 + by, z0, z0 + bz).edges().fillet(4.0)
    z = BANK_PORTS_Z
    for x in BANK_USBA_X:
        body = body.cut(box(x - 6.0, x + 6.0, y0 - 1, y0 + 9.0, z - 2.5, z + 2.5))
    body = body.cut(box(BANK_USBC_X - 4.4, BANK_USBC_X + 4.4, y0 - 1, y0 + 7.0, z - 1.6, z + 1.6))
    dots = cq.Workplane()
    for i in range(4):
        dots = dots.add(cq.Solid.makeCylinder(0.8, 0.3, cq.Vector(x0 + 20 + i * 4, y0 + 12, z0 + bz),
                                              cq.Vector(0, 0, 1)))
    return [("Power bank Redmi 20000", body, (0.17, 0.18, 0.20)), ("Power bank level LEDs", dots, WHITE)]


def charging():
    """Right-angle USB-C plug in the bank, a short cable, and a panel-mount socket in the right wall."""
    x, y, z = BANK_USBC_X, BANK_MIN[1], BANK_PORTS_Z
    tongue = box(x - 4.1, x + 4.1, y, y + 6.5, z - 1.3, z + 1.3)
    housing = box(x - 5.0, x + 5.0, y - 8.0, y, z - 3.5, z + 3.5).union(cyl(2.3, 3.5, (x + 5.0, y - 4.0, z), (1, 0, 0)))
    start = (x + 8.5, y - 4.0, z)
    sy, sz = CHARGE_SOCKET
    body = box(INNER_X - 11.0, INNER_X - 1.0, sy - 5.0, sy + 5.0, sz - 4.0, sz + 4.0).edges("|X").fillet(1.5)
    socket = box(INNER_X - 1.0, OUTER_X - 0.3, sy - 4.4, sy + 4.4, sz - 1.6, sz + 1.6)
    socket = socket.cut(box(INNER_X, OUTER_X + 1, sy - 3.6, sy + 3.6, sz - 0.9, sz + 0.9))
    end = (INNER_X - 11.0, sy, sz)
    lead = cable([start, (start[0] + 8.0, y - 5.0, z - 2.0), end], 1.6, (1, 0, 0), (1, 0, 0), pull=6)
    return [("USB-C plug (power bank)", tongue, METAL), ("USB-C plug housing (power bank)", housing, CABLE),
            ("Cable charging", lead, CABLE), ("USB-C panel socket body", body, BLACK),
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
    out += charging()
    spk, spk_end = speaker()
    out += spk
    snd, snd_socket_end, snd_header = sound_card()
    out += snd

    ends = {}
    for col, side in zip(EYE_COLS, ("left", "right")):
        p, exit_point = eye(col, side)
        out += p
        ends[side] = exit_point

    # left eye <- upper port of the outer stack; the cable turns over its plug and runs forward
    shell, housing, a_exit = straight_usb_a(usb_port_center(1, upper=True))
    lx, ly, lz = ends["left"]
    eye_l_cable = cable([a_exit, (a_exit[0] - 1.0, a_exit[1] + 8.0, 70.5), (a_exit[0] + 3.0, a_exit[1] - 2.0, 73.5),
                         (-34.0, 0.0, 74.0), (-33.0, -35.0, 73.5), (lx, ly + 10.0, lz + 1.5), (lx, ly, lz)],
                        1.75, (0, 1, 0), (0, -1, 0), pull=5)
    out += [("USB plug (left eye)", shell, METAL), ("USB plug housing (left eye)", housing, CABLE),
            ("Cable left eye", eye_l_cable, CABLE)]

    # right eye <- upper port of the inner stack; behind the strip's plug, then forward on the right
    shell, housing, b_exit = straight_usb_a(usb_port_center(0, upper=True))
    rx, ry, rz = ends["right"]
    eye_r_cable = cable([b_exit, (b_exit[0] + 2.0, b_exit[1] + 10.0, 71.0), (-4.0, 55.0, 73.5), (15.0, 52.0, 74.0),
                         (27.0, 32.0, 74.0), (30.0, -20.0, 73.5), (rx, ry + 10.0, rz + 1.5), (rx, ry, rz)],
                        1.75, (0, 1, 0), (0, -1, 0), pull=5)
    out += [("USB plug (right eye)", shell, METAL), ("USB plug housing (right eye)", housing, CABLE),
            ("Cable right eye", eye_r_cable, CABLE)]

    # LED strip <- lower port of the inner stack, to the strip's plug at its back end
    shell, housing, c_exit = straight_usb_a(usb_port_center(0, upper=False))
    led_cable = cable([c_exit, (c_exit[0] + 5.0, c_exit[1] + 6.0, c_exit[2] + 2.0), (-4.0, strip_end[1] + 4.0, 64.5),
                       strip_end], 1.75, (0, 1, 0), (0, -1, 0), pull=4)
    out += [("USB plug (LED strip)", shell, METAL), ("USB plug housing (LED strip)", housing, CABLE),
            ("Cable LED strip", led_cable, CABLE)]

    # sound card <- lower port of the outer stack, round the front of the card to its socket
    shell, housing, d_exit = straight_usb_a(usb_port_center(1, upper=False))
    sx, sy_, sz = snd_socket_end
    snd_cable = cable([d_exit, (d_exit[0] + 6.0, d_exit[1] + 8.0, 57.0), (-10.0, 48.5, 56.5), (14.0, 48.5, 56.5),
                       (sx + 6.0, sy_ - 2.0, sz), (sx, sy_, sz)], 1.75, (0, 1, 0), (-1, 0, 0), pull=4)
    out += [("USB plug (sound card)", shell, METAL), ("USB plug housing (sound card)", housing, CABLE),
            ("Cable sound card", snd_cable, CABLE)]

    # speaker <- sound card amplifier, down past the open left edge of the shelf
    spk_cable = cable([snd_header, (snd_header[0] - 1.5, snd_header[1] - 4.0, snd_header[2] + 1.5),
                       (spk_end[0] + 1.2, SOUND_MIN[1] - 4.0, snd_header[2] - 4.5), (spk_end[0], spk_end[1] + 4.0, 52.0),
                       spk_end], 1.1, (0, 0, 1), (0, 0, -1), pull=3)
    out += [("Cable speaker", spk_cable, (0.55, 0.12, 0.10))]

    # power: bank USB-A (front, bent up) -> up between the eye boards -> Pi micro-USB
    tongue, housing, pwr_exit = micro_usb_plug()
    shell, bank_housing, bank_exit = bank_usb_a_up(BANK_USBA_X[0])
    pwr_cable = cable([bank_exit, (bank_exit[0], bank_exit[1] - 1.0, 52.0), (bank_exit[0] + 2.0, bank_exit[1], 64.0),
                       (0.0, -70.0, 70.0), (pwr_exit[0], pwr_exit[1] - 3.0, pwr_exit[2] + 6.0), pwr_exit],
                      1.75, (0, 0, 1), (0, 0, -1), pull=5)
    out += [("Micro-USB plug (power)", tongue, METAL), ("Micro-USB plug housing (power)", housing, CABLE),
            ("USB plug (power bank)", shell, METAL), ("USB plug housing (power bank)", bank_housing, CABLE),
            ("Cable power", pwr_cable, CABLE)]
    return out
