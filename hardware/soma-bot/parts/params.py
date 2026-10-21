"""Shared dimensions of the Claude Bot prototype, built as Clawd, the Claude Code mascot.

Units are millimetres. Z is up, the front faces -Y, X points right (SOMA convention). The
desk is Z = 0 and the body is centred on X = 0 and Y = 0.

Clawd's shape comes straight from the pixel art in Claude Code (default pose):

     ▐▛███▛█        row 1: body, the two missing pixels are the eyes
    ▝▜██████▀       row 2: body with an arm on each side
     ▝▝   ▝▝        row 3: four feet

Each character holds 2 × 2 pixels and a terminal cell is twice as tall as wide, so one
pixel is U wide and 2U tall. That gives a body 13 pixels wide and 4 rows tall, arms of
2 × 1 pixels, feet of 1 × 1 pixel, and 1-pixel eyes, so the front is 13U × 8U. The side view
in Claude Code (the "edge" turn) has feet at the front and the back edge, so there are four
feet at the front and four at the back. The body is deeper than the sprite's 5 pixels: it
runs back far enough for the power bank lying flat, which keeps the front small.

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
# 8 mm pixels: the smallest front that still holds the power bank lying flat (73.6 wide)
# with the Pi on a shelf above it. The cable routes in wiring.py are laid out for this size.
U = 8.0
PX_W = U                 # one pixel wide
PX_H = 2 * U             # one pixel (row) tall
CENTER_COL = 9.5         # the body spans sprite columns 3..15
EYE_COLS = (5, 13)
ARM_COLS = ((1, 3), (16, 18))
FOOT_COLS = (3, 5, 13, 15)


def col_x(c):
    """Left edge of sprite column c, in mm, the body centred on X = 0."""
    return (c - CENTER_COL) * PX_W


BODY_W = 13 * PX_W
BODY_H = 4 * PX_H
BODY_D = 173.0           # front zone for the eyes, then the power bank's length
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
ARM_Y = (OUTER_FRONT + 1.5 * PX_W, OUTER_FRONT + 3.5 * PX_W)  # where the sprite's 5-pixel side has them
SPLIT_Y = OUTER_BACK - 10.0   # front shell / back cover joint
LIP = 3.0

# --- eye screens ---------------------------------------------------------------------------
OLED_PCB = (26.7, 29.2, 1.6)  # portrait: x, z, thickness
OLED_GLASS = (19.0, 26.6, 1.5)
OLED_DEPTH = 6.2
MCP = (17.7, 27.0, 5.0)       # MCP2221A standing behind the OLED: x, z, y; USB-C at the top
MCP_DROP = 4.0                # MCP centre below the eye centre, so its plug clears the top

# --- power bank: Redmi 20000 mAh PB200LZM, lying flat, ports at the front ---------------------
BANK = (73.6, 154.0, 27.3)     # x, y, z as it lies in the body
BANK_MIN = (-BANK[0] / 2, FRONT_IN + 13.0, INNER_BOTTOM + 0.5)
BANK_PORTS_Z = BANK_MIN[2] + BANK[2] / 2
# port centres across the front end, x; positions UNVERIFIED
BANK_USBA_X = (-12.0, 2.0)
BANK_USBC_X = 16.0
CHARGE_SOCKET = (BANK_MIN[1] - 7.0, INNER_BOTTOM + 7.5)  # right wall, y and z: under the right arm

# --- shelf over the power bank and the Raspberry Pi 3 Model B on it ----------------------------
SHELF_Z = BANK_MIN[2] + BANK[2] + 0.7   # underside
SHELF_T = 2.0
SHELF_X = (-INNER_X + 4.5, INNER_X)     # open on the left for the speaker cable
SHELF_Y = (BANK_MIN[1], SPLIT_Y - LIP - 0.4)
SHELF_NOTCH_Y = (30.0, 62.0)            # notch in the open left edge where the speaker cable goes down
# Board frame: bx along the 85 mm edge from the micro-USB end, by from the HDMI edge, bz up
# from the top (component) surface. The board lies flat on 4 mm standoffs, micro-USB end at
# the front, USB and Ethernet facing the back, HDMI edge to the right.
PI_BOARD = (85.0, 56.0, 1.4)   # thickness UNVERIFIED
PI_STANDOFF = 4.0
PI_ORIGIN = (7.5, BANK_MIN[1] + 1.0, SHELF_Z + SHELF_T + PI_STANDOFF + PI_BOARD[2])
PI_HOLES = [(3.5, 3.5), (61.5, 3.5), (3.5, 52.5), (61.5, 52.5)]
PI_USB_BY = (29.0, 47.0)
PI_USB_PORT_BZ = (4.5, 12.3)   # centres of the lower / upper port in a stack, UNVERIFIED
PI_FAR_EDGE = 85.0 + 2.1       # USB / Ethernet fronts
PI_ROTATION = 90.0             # about Z: bx -> +Y, by -> -X, bz -> +Z


def pi_to_world(bx, by, bz):
    """Board frame -> world."""
    return (PI_ORIGIN[0] - by, PI_ORIGIN[1] + bx, PI_ORIGIN[2] + bz)


# --- peripherals --------------------------------------------------------------------------------
STRIP = (85.0, 9.5, 1.6)       # BlinkStick Strip PCB; LEDs 5 x 5 x 1.6 on top (WS2812)
STRIP_LEDS = 8
STRIP_Y0 = -50.0               # front end of the strip; it runs back along the top, socket at the back
STRIP_Z = INNER_TOP - 7.2      # bottom of the strip PCB
SOUND = (53.0, 23.5, 14.6)     # Waveshare USB TO AUDIO lying on the shelf behind the Pi, plug to the right
SOUND_MIN = (-INNER_X + 4.5, 51.0, SHELF_Z + SHELF_T)
SPEAKER_D = 28.0               # UNVERIFIED: generic 28 mm 8 ohm speaker on the sound card's amp
SPEAKER_DEPTH = 5.0
SPEAKER_YZ = (40.0, (INNER_BOTTOM + SHELF_Z) / 2)  # stands beside the bank, fires out of the left side

SCREW_XZ = [(-40.0, INNER_TOP - 5.5), (40.0, INNER_TOP - 5.5),  # back cover screws
            (-43.0, INNER_BOTTOM + 3.5), (43.0, INNER_BOTTOM + 3.5)]
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
