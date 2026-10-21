"""Everything plugged into the Pi and the screen: LED strip, speaker, microphone, power jack,
the HDMI U-adapter and the cables between them.

Cables are swept along splines. Their routes keep clear of the screw bosses and ribs, which
`soma build` cannot check by itself, so the collision check in checks.py covers them.
"""
import cadquery as cq

from .params import (BLACK, CABLE, DARK, GOLD, INNER_BACK, LED_COLOURS, METAL, MIC_SOCKET,
                     PI_FAR_EDGE, PI_USB_BY, PI_USB_PORT_BZ, SPEAKER_D, SPEAKER_DEPTH, SPEAKER_FACE_X, SPEAKER_YZ,
                     STRIP, STRIP_LEDS, STRIP_Z, USBC_JACK, WHITE, pi_to_world)
from .body import STRIP_Y, box, cyl


def cable(points, r, t0, t1):
    """Round cable through `points`, leaving along t0 and arriving along t1."""
    vs = [cq.Vector(*p) for p in points]
    edge = cq.Edge.makeSpline(vs, tangents=[cq.Vector(*t0).normalized(), cq.Vector(*t1).normalized()])
    profile = cq.Wire.makeCircle(r, vs[0], edge.tangentAt(0))
    return cq.Solid.sweep(profile, [], cq.Wire.assembleEdges([edge]), True, False)


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


# --- Pi USB plugs ------------------------------------------------------------------------------

def usb_port_center(stack, upper=True):
    """World centre of a Pi USB port opening: x at the port face."""
    return pi_to_world(PI_FAR_EDGE, PI_USB_BY[stack], PI_USB_PORT_BZ[1 if upper else 0])


def right_angle_usb_a(stack, up):
    """USB-A plug in the upper port of a stack, its housing bent up (+Z) or down (-Z)."""
    x, y, z = usb_port_center(stack)
    shell = box(x - 8.5, x, y - 2.2, y + 2.2, z - 6.0, z + 6.0)
    shell = shell.cut(box(x - 9.0, x + 0.1, y + 0.1, y + 2.1, z - 5.7, z + 5.7))  # room for the port's tongue
    sgn = 1 if up else -1
    housing = box(x, x + 7.8, y - 3.8, y + 3.8, z - 7.5, z + 7.5)
    boot = cyl(2.6, 4.0, (x + 4.2, y, z + sgn * 7.5), (0, 0, sgn))
    exit_point = (x + 4.2, y, z + sgn * 11.5)
    return shell, housing.union(boot), exit_point


# --- speaker -----------------------------------------------------------------------------------

def speaker():
    y, z = SPEAKER_YZ
    r = SPEAKER_D / 2
    x0 = SPEAKER_FACE_X
    frame = cyl(r, 1.2, (x0, y, z), (1, 0, 0)).cut(cyl(r - 1.6, 2, (x0 - 0.5, y, z), (1, 0, 0)))
    cone = cq.Workplane().add(cq.Solid.makeCone(r - 1.6, 5.5, SPEAKER_DEPTH - 2.0, cq.Vector(x0 + 0.4, y, z),
                                                cq.Vector(1, 0, 0)))
    magnet = cyl(8.0, 3.0, (x0 + SPEAKER_DEPTH - 3.0, y, z), (1, 0, 0))
    terminals = box(x0 + 3.6, x0 + 5.0, y - 1.5, y + 1.5, z + r - 6.0, z + r - 2.0)
    return [("Speaker frame", frame, METAL), ("Speaker cone", cone, DARK), ("Speaker magnet", magnet, (0.35, 0.35, 0.36)),
            ("Speaker terminals", terminals, GOLD)]


# --- microphone on a USB extension in the chin -------------------------------------------------

def microphone():
    x0, x1, y, z = MIC_SOCKET
    socket = box(x0, x1, y - 7.0, y + 7.0, z - 3.2, z + 3.2)
    socket = socket.cut(box(x0 - 1, x0 + 10, y - 6.2, y + 6.2, z - 2.4, z + 2.4))
    boot = box(x1, x1 + 5.0, y - 4.0, y + 4.0, z - 3.0, z + 3.0)
    dongle_plug = box(x0 - 0.5, x0 + 10.0, y - 6.0, y + 6.0, z - 2.0, z + 2.0)
    dongle = box(x0 - 11.5, x0 - 0.5, y - 9.15, y + 9.15, z - 3.5, z + 3.5).edges("|Z").fillet(2.5)
    return [("Mic USB extension socket", socket.union(boot), BLACK),
            ("USB mic plug", dongle_plug, METAL),
            ("USB mini microphone", dongle, (0.12, 0.12, 0.13))], (x1 + 5.0, y, z)


# --- power: panel-mount USB-C jack on the back cover -------------------------------------------

def power_jack():
    jx, jz = USBC_JACK
    shell = box(jx - 4.6, jx + 4.6, INNER_BACK - 1.0, INNER_BACK + 2.4, jz - 1.6, jz + 1.6)
    flange = box(jx - 14.0, jx + 14.0, INNER_BACK - 2.0, INNER_BACK, jz - 4.5, jz + 4.5)
    block = box(jx - 7.0, jx + 7.0, INNER_BACK - 9.0, INNER_BACK - 2.0, jz - 4.0, jz + 4.0)
    return [("USB-C panel jack", shell, METAL), ("USB-C jack body", flange.union(block), BLACK)], \
        (jx, INNER_BACK - 5.5, jz + 4.0)


def micro_usb_plug():
    """Right-angle micro-USB plug in the Pi's power socket, the cable leaving towards the back."""
    x, y, z = pi_to_world(10.6, 0.0, 1.5)
    tongue = box(x - 3.4, x + 3.4, y - 0.9, y + 0.9, z - 4.5, z + 0.5)
    housing = box(x - 5.5, x + 5.5, y - 3.5, y + 5.0, z + 0.5, z + 8.5)
    boot = cyl(2.4, 3.5, (x, y + 5.0, z + 4.5), (0, 1, 0))
    return tongue, housing.union(boot), (x, y + 8.5, z + 4.5)


# --- HDMI U-adapter between the Pi and the screen ----------------------------------------------

def hdmi_adapter():
    body = box(-15.4, 2.2, 5.0, 25.0, 23.8, 33.8).edges("|Y").fillet(1.5)
    plugs = (box(-13.6, 0.4, 18.3, 22.8, 15.5, 23.8)
             .union(box(-14.0, 0.0, 7.7, 12.2, 18.0, 23.8)))
    return [("HDMI U-adapter", body, BLACK), ("HDMI adapter plugs", plugs, METAL)]


# --- speaker plug on the screen's PH2.0 header -------------------------------------------------

def speaker_plug():
    plug = box(20.6, 30.2, 13.9, 19.0, 23.3, 27.5)
    return plug, (25.4, 19.0, 25.4)


def parts(led_state):
    out = []
    out += led_strip(led_state)
    out += speaker()
    mic, mic_end = microphone()
    out += mic
    jack, jack_end = power_jack()
    out += hdmi_adapter()

    # LED strip <- middle USB stack, plug bent up
    shell, housing, led_exit = right_angle_usb_a(0, up=True)
    strip_plug = box(STRIP[0] / 2, STRIP[0] / 2 + 8.0, STRIP_Y - 4.4, STRIP_Y + 4.4, STRIP_Z - 4.6, STRIP_Z - 0.4)
    strip_tongue = box(STRIP[0] / 2 - 4.4, STRIP[0] / 2, STRIP_Y - 3.4, STRIP_Y + 3.4, STRIP_Z - 2.1, STRIP_Z - 0.6)
    sp_end = (STRIP[0] / 2 + 4.5, STRIP_Y, STRIP_Z - 4.6)
    led_cable = cable([led_exit, (53.5, 32.4, 14.0), (53.0, 32.4, 31.0), (50.5, 27.0, 36.0), sp_end],
                      1.75, (0, 0, 1), (0, 0, 1))
    out += [("USB plug (LED strip)", shell, METAL), ("USB plug housing (LED strip)", housing, CABLE),
            ("Micro-USB plug (LED strip)", strip_plug, CABLE), ("Micro-USB tongue (LED strip)", strip_tongue, METAL),
            ("Cable LED strip", led_cable, CABLE)]

    # microphone <- bottom USB stack, plug bent down
    shell, housing, mic_exit = right_angle_usb_a(1, up=False)
    mic_cable = cable([mic_exit, (53.0, 32.4, -42.0), (50.0, 22.0, -47.5), (51.0, 13.0, -45.5), mic_end],
                      1.75, (0, 0, -1), (-1, 0, 0))
    out += [("USB plug (microphone)", shell, METAL), ("USB plug housing (microphone)", housing, CABLE),
            ("Cable microphone", mic_cable, CABLE)]

    # power: back jack -> Pi micro-USB
    tongue, housing, pwr_exit = micro_usb_plug()
    jx, jz = USBC_JACK
    pwr_cable = cable([pwr_exit, (-29.0, 34.0, 25.0), (-27.0, 36.5, 8.0), (-23.0, 35.5, -18.0), jack_end],
                      1.75, (0, 1, 0), (0, 0, -1))
    out += [("Micro-USB plug (power)", tongue, METAL), ("Micro-USB plug housing (power)", housing, CABLE),
            ("Cable power", pwr_cable, CABLE)]

    # speaker -> screen amplifier header
    plug, sp_start = speaker_plug()
    y, z = SPEAKER_YZ
    spk_end = (SPEAKER_FACE_X + 4.3, y, z + SPEAKER_D / 2 - 2.0)  # onto the terminals, from above
    spk_cable = cable([sp_start, (20.0, 25.0, 35.5), (0.0, 23.0, 36.5), (-28.0, 22.0, 37.0), (-44.0, 18.0, 32.0),
                       spk_end], 1.1, (0, 1, 0), (0, 0, -1))
    out += [("Speaker plug PH2.0", plug, WHITE), ("Cable speaker", spk_cable, (0.55, 0.12, 0.10))]
    return out, jack
