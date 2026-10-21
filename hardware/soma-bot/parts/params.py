"""Shared dimensions of the Claude Bot prototype, built as Clawd, the Claude Code mascot.

Units are millimetres. Z is up, the front faces -Y, X points right (SOMA convention). The
desk is Z = 0 and the body is centred on X = 0 and Y = 0.

Clawd's shape comes straight from the pixel art in Claude Code (default pose):

     ▐▛███▛█        row 1: body, the two missing pixels are the eyes
    ▝▜██████▀       row 2: body with an arm on each side
     ▝▝   ▝▝        row 3: four feet

Each character holds 2 × 2 pixels and a terminal cell is twice as tall as wide, so one
pixel is U wide and 2U tall. That gives a body 13 pixels wide and 4 rows tall, arms of
2 × 1 pixels, feet of 1 × 1 pixel, and 1-pixel eyes. The side view in Claude Code (the
"edge" turn) is 5 pixels deep with feet at the front and the back edge, so the body is
13U × 5U × 8U and there are four feet at the front and four at the back.

The eyes are screens: a 0.96" OLED sits behind each eye pixel, each driven over USB by its
own MCP2221A board, so nothing is wired to the Pi's GPIO header.

Sources:
- Raspberry Pi 3 Model B: official mechanical drawing RPI-3B-V1_2.
- OLED: Adafruit #326, 0.96" 128x64, STEMMA QT, PCB 29.2 x 26.7 x 6.2, glass 26.6 x 19.
- USB to I2C: Adafruit MCP2221A #4471, USB-C, STEMMA QT, 27.0 x 17.7 x 5.0.
- Sound: Waveshare USB TO AUDIO, onboard dual microphones, 2.6 W speaker amp (PH2.0),
  53 x 23.5 x 14.6 (Waveshare size drawing).
- BlinkStick Strip: blinkstick.com, 85 x 9.5 x 4.5 mm, 8 WS2812 LEDs, micro-USB socket.
- Power bank: Redmi 20000 mAh PB200LZM, mi.com specs, 154 x 73.6 x 27.3.
Values marked UNVERIFIED are not published by the maker: measure the real part.
"""
from soma import param

# --- user parameters (Parameters panel in SOMA) -------------------------------------------
WALL = param(2.5, "Shell wall", min=1.6, max=4.0, step=0.1, unit="mm", group="Clawd")
EDGE = param(1.0, "Edge rounding", min=0.0, max=3.0, step=0.25, unit="mm", group="Clawd")
LIGHT_BAR = param(True, "LED light bar on top", group="Clawd")
VIEW = param("closed", "Shell view", options=["closed", "cut-away", "hidden"], group="View")
EXPLODE = param(0.0, "Explode", min=0, max=60, unit="mm", group="View")
HEATSINK = param(True, "Heatsink on the SoC", group="Electronics")
LED_STATE = param("thinking", "LED colour", options=["idle", "listening", "thinking", "speaking", "error"],
                  group="Electronics")

# --- Clawd's pixel grid --------------------------------------------------------------------
# 13.5 mm is the smallest pixel that fits the power bank behind the Pi (depth 5U) with room
# for the plugs at its end (width 13U). The cable routes in wiring.py are laid out for it.
U = 13.5
PX_W = U                 # one pixel wide
PX_H = 2 * U             # one pixel (row) tall
CENTER_COL = 9.5         # the body spans sprite columns 3..15
EYE_COLS = (5, 13)
ARM_COLS = ((1, 3), (16, 18))
FOOT_COLS = (3, 5, 13, 15)
DEPTH_PX = 5


def col_x(c):
    """Left edge of sprite column c, in mm, the body centred on X = 0."""
    return (c - CENTER_COL) * PX_W


BODY_W = 13 * PX_W
BODY_D = DEPTH_PX * PX_W
BODY_H = 4 * PX_H
BODY_Z0 = PX_H           # the feet are one row tall
OUTER_X = BODY_W / 2
OUTER_FRONT = -BODY_D / 2
OUTER_BACK = BODY_D / 2
OUTER_BOTTOM = BODY_Z0
OUTER_TOP = BODY_Z0 + BODY_H
INNER_X = OUTER_X - WALL
FRONT_IN = OUTER_FRONT + WALL
INNER_BACK = OUTER_BACK - WALL
INNER_BOTTOM = OUTER_BOTTOM + WALL
INNER_TOP = OUTER_TOP - WALL


def row_z(r):
    """Bottom of body row r (0 = top row, 3 = bottom row)."""
    return BODY_Z0 + (3 - r) * PX_H


EYE_Z = (row_z(1), row_z(1) + PX_H)
ARM_Z = (row_z(2), row_z(2) + PX_H)
SPLIT_Y = OUTER_BACK - 10.0   # front shell / back cover joint
LIP = 3.0

# --- eye screens ---------------------------------------------------------------------------
OLED_PCB = (26.7, 29.2, 1.6)  # portrait: x, z, thickness
OLED_GLASS = (19.0, 26.6, 1.5)
OLED_DEPTH = 6.2
MCP = (17.7, 27.0, 5.0)       # MCP2221A standing behind the OLED: x, z, y; USB-C at the top

# --- Raspberry Pi 3 Model B ---------------------------------------------------------------------
# Board frame: bx along the 85 mm edge from the micro-USB end, by from the HDMI edge, bz up
# from the top (component) surface. The board stands behind the front wall on four standoffs,
# components facing the back, HDMI edge at the top, USB and Ethernet to the right.
PI_BOARD = (85.0, 56.0, 1.4)   # thickness UNVERIFIED
# world position of board (0, 0, 0): 12.25 mm behind the front wall, top edge 72.5 mm above the floor
PI_ORIGIN = (-39.5, FRONT_IN + 12.25 + PI_BOARD[2], INNER_BOTTOM + 72.5)
PI_HOLES = [(3.5, 3.5), (61.5, 3.5), (3.5, 52.5), (61.5, 52.5)]
PI_USB_BY = (29.0, 47.0)
PI_USB_PORT_BZ = (4.5, 12.3)   # centres of the lower / upper port in a stack, UNVERIFIED
PI_FAR_EDGE = 85.0 + 2.1       # USB / Ethernet fronts


def pi_to_world(bx, by, bz):
    """Board frame -> world."""
    return (PI_ORIGIN[0] + bx, PI_ORIGIN[1] + bz, PI_ORIGIN[2] - by)


# --- peripherals --------------------------------------------------------------------------------
STRIP = (85.0, 9.5, 1.6)       # BlinkStick Strip PCB; LEDs 5 x 5 x 1.6 on top (WS2812)
STRIP_LEDS = 8
STRIP_Y = -12.0
STRIP_Z = INNER_TOP - 7.2      # bottom of the strip PCB
SOUND = (53.0, 23.5, 14.6)     # Waveshare USB TO AUDIO lying on the floor, USB plug to the right
SOUND_MIN = (-INNER_X + 5.25, FRONT_IN + 2.25, INNER_BOTTOM + 0.5)
SPEAKER_D = 28.0               # UNVERIFIED: generic 28 mm 8 ohm speaker on the sound card's amp
SPEAKER_DEPTH = 5.0
SPEAKER_XY = (2.0, -14.0)      # on the floor, firing down between the feet

# Power bank: Redmi 20000 mAh PB200LZM, documented to keep its output on while charging.
# It stands behind the Pi with its ports to the right.
BANK = (154.0, 27.3, 73.6)     # x, y, z as it stands in the body
BANK_MIN = (-INNER_X + 3.25, INNER_BACK - 1.5 - BANK[1], INNER_BOTTOM + 7.0)
BANK_PORTS_Y = BANK_MIN[1] + BANK[1] / 2
# port centres on the right end, height above the bank's bottom; positions UNVERIFIED
BANK_USBA_DZ = (42.5, 28.5)
BANK_USBC_DZ = 14.5

SCREW_XZ = [(-60.0, INNER_TOP - 8.5), (60.0, INNER_TOP - 8.5),  # back cover screws
            (-60.0, INNER_BOTTOM + 3.25), (60.0, INNER_BOTTOM + 3.25)]
BOSS_D = 7.0
BOSS_FRONT = SPLIT_Y - 10.0

# --- colours ------------------------------------------------------------------------------------
CLAWD = (0.843, 0.467, 0.341)  # Claude Code's "claude" orange, rgb(215, 119, 87)
CLAWD_DEEP = (0.78, 0.42, 0.30)
CREAM = (0.94, 0.92, 0.87)
DARK = (0.16, 0.16, 0.17)
PCB_GREEN = (0.08, 0.42, 0.22)
PCB_BLACK = (0.08, 0.08, 0.09)
METAL = (0.78, 0.79, 0.80)
NICKEL = (0.70, 0.70, 0.68)
GOLD = (0.85, 0.68, 0.30)
BLACK = (0.06, 0.06, 0.07)
GLASS = (0.02, 0.02, 0.03)
WHITE = (0.93, 0.93, 0.92)
CABLE = (0.10, 0.10, 0.11)
QT_BLUE = (0.15, 0.35, 0.75)
LED_COLOURS = {"idle": (0.95, 0.93, 0.85), "listening": (0.20, 0.55, 1.00), "thinking": (0.95, 0.55, 0.25),
               "speaking": (0.25, 0.90, 0.45), "error": (1.00, 0.15, 0.15)}
