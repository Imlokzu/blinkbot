"""Raspberry Pi 3 Model B, detailed enough to see every port.

Positions follow the official mechanical drawing RPI-3B-V1_2. Built in the board frame
(bx along the 85 mm edge from the micro-USB end, by from the HDMI edge, bz up from the
component side) and moved onto the screen studs by `place()`.
"""
import cadquery as cq

from .params import (BLACK, GOLD, METAL, PCB_GREEN, PI_BOARD, PI_FAR_EDGE, PI_HOLES, PI_ORIGIN, PI_USB_BY,
                     PI_USB_PORT_BZ, WHITE)

L, W, T = PI_BOARD


def box(x0, x1, y0, y1, z0, z1):
    return cq.Workplane("XY").box(x1 - x0, y1 - y0, z1 - z0, centered=False).translate((x0, y0, z0))


def place(wp):
    """Board frame -> screen frame: by turns into -Z, bz into +Y (the component side faces the back)."""
    shape = wp.val() if isinstance(wp, cq.Workplane) else wp
    if isinstance(wp, cq.Workplane) and len(wp.vals()) > 1:
        shape = cq.Compound.makeCompound(wp.vals())
    return shape.rotate((0, 0, 0), (1, 0, 0), -90).translate(PI_ORIGIN)


def board():
    b = (cq.Workplane("XY").rect(L, W, centered=False).extrude(-T)
         .edges("|Z").fillet(3.0))
    for x, y in PI_HOLES:
        b = b.cut(cq.Workplane("XY").center(x, y).circle(2.75 / 2).extrude(-T - 1).translate((0, 0, 0.5)))
    return b


def usb_stack(cy):
    x0, x1 = PI_FAR_EDGE - 17.4, PI_FAR_EDGE
    s = box(x0, x1, cy - 6.65, cy + 6.65, 0, 16.0)
    for pz in PI_USB_PORT_BZ:
        s = s.cut(box(x1 - 9.0, x1 + 1, cy - 6.0, cy + 6.0, pz - 2.6, pz + 2.6))
    return s


def usb_tongues():
    out = []
    for cy in PI_USB_BY:
        for pz in PI_USB_PORT_BZ:
            # the plastic tongue sits in the upper half of the opening
            out.append(box(PI_FAR_EDGE - 9.0, PI_FAR_EDGE - 1.0, cy - 5.6, cy + 5.6, pz + 0.2, pz + 2.0))
    return out


def ethernet():
    cy = 10.25
    x0, x1 = PI_FAR_EDGE - 21.3, PI_FAR_EDGE
    e = box(x0, x1, cy - 7.95, cy + 7.95, 0, 13.5)
    # RJ45 opening with the latch notch
    e = e.cut(box(x1 - 15, x1 + 1, cy - 5.85, cy + 5.85, 2.0, 9.0))
    e = e.cut(box(x1 - 15, x1 + 1, cy - 3.0, cy + 3.0, 9.0, 11.0))
    return e


def hdmi():
    h = box(32 - 7.5, 32 + 7.5, -1.75, 9.75, 0, 6.5)
    return h.cut(box(32 - 7.0, 32 + 7.0, -2.0, 7.0, 1.0, 5.5))


def micro_usb():
    m = box(10.6 - 3.85, 10.6 + 3.85, -1.4, 4.2, 0, 3.0)
    return m.cut(box(10.6 - 3.45, 10.6 + 3.45, -1.6, 3.0, 0.6, 2.4))


def audio_jack():
    body = box(53.5 - 3.0, 53.5 + 3.0, 0.5, 12.0, 0, 6.0)
    down = cq.Vector(0, -1, 0)
    barrel = cq.Solid.makeCylinder(3.0, 3.25, cq.Vector(53.5, 0.5, 3.0), down)
    bore = cq.Solid.makeCylinder(1.8, 8.0, cq.Vector(53.5, 4.0, 3.0), down)
    return body.union(cq.Workplane().add(barrel)).cut(cq.Workplane().add(bore))


def gpio_header():
    base = box(32.5 - 25.4, 32.5 + 25.4, 52.5 - 2.54, 52.5 + 2.54, 0, 2.5)
    return base


def gpio_pins():
    pins = cq.Workplane("XY")
    for i in range(20):
        for dy in (-1.27, 1.27):
            pins = pins.add(box(0, 0.64, 0, 0.64, 0, 8.5).translate((32.5 + (i - 9.5) * 2.54 - 0.32, 52.5 + dy - 0.32, 0))
                            .val())
    return pins


def ribbon_connector(cx, cy):
    """CSI / DSI: white body with a black latch on top, long side along by."""
    body = box(cx - 1.4, cx + 1.4, cy - 11.0, cy + 11.0, 0, 4.4)
    latch = box(cx - 1.6, cx + 1.6, cy - 11.4, cy + 11.4, 4.4, 5.5)
    return body, latch


def chips():
    soc = box(29 - 7, 29 + 7, 32 - 7, 32 + 7, 0, 1.2)      # BCM2837, metal lid
    lan = box(59 - 4.5, 59 + 4.5, 31 - 4.5, 31 + 4.5, 0, 1.0)  # LAN9514 USB hub + Ethernet
    ram = box(29 - 7, 29 + 7, 32 - 6, 32 + 6, -T - 1.0, -T)  # LPDDR2 on the underside
    return soc, lan, ram


def heatsink():
    hs = box(29 - 7, 29 + 7, 32 - 7, 32 + 7, 1.2, 3.2)
    for i in range(5):
        x = 29 - 7 + 0.6 + i * 3.1
        hs = hs.union(box(x, x + 1.2, 32 - 7, 32 + 7, 3.2, 9.2))
    return hs


def sd_card():
    slot = box(0.0, 12.0, 28 - 5.75, 28 + 5.75, -T - 1.4, -T)
    card = box(-2.4, 12.5, 28 - 5.5, 28 + 5.5, -T - 1.2, -T - 0.4)
    return slot, card


def leds():
    pwr = box(1.0, 2.6, 7.4, 8.4, 0, 0.6)
    act = box(1.0, 2.6, 10.4, 11.4, 0, 0.6)
    return pwr, act


def parts(with_heatsink=True):
    """(name, shape in screen frame, colour) for every piece of the Pi."""
    soc, lan, ram = chips()
    csi_body, csi_latch = ribbon_connector(45.0, 11.5)
    dsi_body, dsi_latch = ribbon_connector(4.4, 28.0)
    slot, card = sd_card()
    pwr, act = leds()
    out = [
        ("Pi board", board(), PCB_GREEN),
        ("USB 2.0 stack (middle)", usb_stack(PI_USB_BY[0]), METAL),
        ("USB 2.0 stack (bottom)", usb_stack(PI_USB_BY[1]), METAL),
        ("USB tongues", cq.Workplane("XY").add([t.val() for t in usb_tongues()]), BLACK),
        ("Ethernet jack", ethernet(), METAL),
        ("HDMI socket", hdmi(), METAL),
        ("Micro-USB power socket", micro_usb(), METAL),
        ("3.5 mm audio jack", audio_jack(), BLACK),
        ("GPIO header", gpio_header(), BLACK),
        ("GPIO pins", gpio_pins(), GOLD),
        ("CSI camera connector", csi_body, WHITE),
        ("CSI latch", csi_latch, BLACK),
        ("DSI display connector", dsi_body, WHITE),
        ("DSI latch", dsi_latch, BLACK),
        ("BCM2837 SoC", soc, METAL),
        ("LAN9514", lan, BLACK),
        ("LPDDR2 RAM (underside)", ram, BLACK),
        ("microSD slot", slot, METAL),
        ("microSD card", card, (0.15, 0.25, 0.55)),
        ("PWR LED", pwr, (0.9, 0.1, 0.1)),
        ("ACT LED", act, (0.1, 0.8, 0.2)),
    ]
    if with_heatsink:
        out.append(("SoC heatsink", heatsink(), (0.55, 0.58, 0.62)))
    return [(n, place(s), c) for n, s, c in out]
