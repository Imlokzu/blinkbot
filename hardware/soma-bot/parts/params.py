"""Shared dimensions of the Claude Bot prototype.

Units are millimetres. Z is up, the front faces -Y, X points right (SOMA convention).
Everything is laid out around the screen: the origin of the "screen frame" is the centre of
the screen glass, and the whole bot is lifted by LIFT at the end so the feet stand on Z = 0.

Sources:
- Screen: Waveshare 3.5inch HDMI LCD (E), official STEP (inputs/waveshare-35-hdmi-lcd-e.stp).
  The Pi mounts on its four M2.5 studs and its pogo pins touch the Pi's GPIO header from
  below, so no wires go to the GPIO header.
- Raspberry Pi 3 Model B: official mechanical drawing RPI-3B-V1_2.
- BlinkStick Strip: blinkstick.com, 85 x 9.5 x 4.5 mm, 8 WS2812 LEDs, micro-USB socket.
Values marked UNVERIFIED are not published by the maker: measure the real part.
"""
from soma import param

# --- user parameters (Parameters panel in SOMA) -------------------------------------------
WALL = param(2.5, "Shell wall", min=1.6, max=4.0, step=0.1, unit="mm", group="Body")
CORNER = param(16.0, "Corner radius", min=6, max=20, step=0.5, unit="mm", group="Body")
SIDE_ROOM = param(59.5, "Inner half width", min=58, max=70, step=0.5, unit="mm", group="Body")
FEET = param(True, "Feet", group="Body")
VIEW = param("closed", "Shell view", options=["closed", "cut-away", "hidden"], group="View")
EXPLODE = param(0.0, "Explode", min=0, max=60, unit="mm", group="View")
HEATSINK = param(True, "Heatsink on the SoC", group="Electronics")
FACE = param(True, "Face on the screen", group="View")
LED_STATE = param("thinking", "LED colour", options=["idle", "listening", "thinking", "speaking", "error"],
                  group="Electronics")

# --- body (screen frame) ---------------------------------------------------------------------
FRONT_IN = -0.7          # inner face of the front wall = front of the screen glass
INNER_TOP = 47.0
INNER_BOTTOM = -54.0
INNER_BACK = 41.5
SPLIT_Y = 34.0           # front shell / back cover joint
LIP = 3.0                # back cover lip that slides into the front shell
WINDOW_MARGIN = 0.6      # around the active area
FACE_PLATE = (88.0, 66.0, -4.0, 10.0, 1.0)  # dark front plate: width, height, centre z, corner radius, thickness
FOOT_H = 4.0

OUTER_X = SIDE_ROOM + WALL
OUTER_TOP = INNER_TOP + WALL
OUTER_BOTTOM = INNER_BOTTOM - WALL
OUTER_FRONT = FRONT_IN - WALL
OUTER_BACK = INNER_BACK + WALL
BODY_W = 2 * OUTER_X
BODY_H = OUTER_TOP - OUTER_BOTTOM
BODY_D = OUTER_BACK - OUTER_FRONT
BODY_MID_Y = (OUTER_FRONT + OUTER_BACK) / 2
LIFT = -OUTER_BOTTOM + (FOOT_H if FEET else 0.0)

# --- screen: Waveshare 3.5inch HDMI LCD (E) ----------------------------------------------------
SCREEN_STEP = "inputs/waveshare-35-hdmi-lcd-e.stp"
ACTIVE = (70.68, 53.36)  # active area, centred on the glass in the STEP
STUD_TIP_Y = 15.9        # back end of the M2.5 studs (screen frame)
# screen HDMI receptacle (from the STEP), screen frame
SCREEN_HDMI = dict(x=(-14.4, 0.6), y=(5.77, 14.15), z=(11.4, 23.55))

# --- Raspberry Pi 3 Model B, placed on the studs -----------------------------------------------
# Board frame: bx along the 85 mm edge from the micro-USB end, by from the HDMI edge, bz up
# from the top (component) surface. The component side faces the back of the bot.
PI_BOARD = (85.0, 56.0, 1.4)   # thickness UNVERIFIED
PI_ORIGIN = (-38.6, STUD_TIP_Y + PI_BOARD[2], 21.5)  # world position of board (0, 0, 0)
PI_HOLES = [(3.5, 3.5), (61.5, 3.5), (3.5, 52.5), (61.5, 52.5)]
PI_USB_BY = (29.0, 47.0)
PI_USB_PORT_BZ = (4.5, 12.3)   # centres of the lower / upper port in a stack, UNVERIFIED
PI_FAR_EDGE = 85.0 + 2.1       # USB / Ethernet fronts


def pi_to_world(bx, by, bz):
    """Board frame -> screen frame."""
    return (PI_ORIGIN[0] + bx, PI_ORIGIN[1] + bz, PI_ORIGIN[2] - by)


# --- peripherals --------------------------------------------------------------------------------
STRIP = (85.0, 9.5, 1.6)       # BlinkStick Strip PCB; LEDs 5 x 5 x 1.6 on top (WS2812)
STRIP_LEDS = 8
STRIP_Z = 41.5                 # bottom of the strip PCB
SPEAKER_D = 28.0               # UNVERIFIED: generic 28 mm 8 ohm 2 W speaker on the screen's amplifier
SPEAKER_DEPTH = 5.0
SPEAKER_FACE_X = -55.8         # inside the left wall: clear of the curved corners and the back cover lip
SPEAKER_YZ = (20.4, 8.0)
USBC_JACK = (-22.0, -36.0)     # panel-mount USB-C on the back cover, x and z
MIC_SOCKET = (12.0, 40.0, 11.0, -44.0)  # USB-A extension socket in the chin: x from, x to, y, z
SCREW_XZ = [(-40.0, 28.0), (40.0, 28.0), (-40.0, -36.0), (40.0, -36.0)]  # back cover screws
BOSS_D = 7.0
BOSS_FRONT = 24.0

# --- colours ------------------------------------------------------------------------------------
CREAM = (0.94, 0.92, 0.87)
CLAY = (0.85, 0.47, 0.34)
DARK = (0.16, 0.16, 0.17)
PCB_GREEN = (0.08, 0.42, 0.22)
PCB_BLUE = (0.10, 0.30, 0.62)
METAL = (0.78, 0.79, 0.80)
NICKEL = (0.70, 0.70, 0.68)
GOLD = (0.85, 0.68, 0.30)
BLACK = (0.06, 0.06, 0.07)
GLOSS = (0.10, 0.11, 0.12)
SCREEN_BG = (0.086, 0.094, 0.10)  # --bg of Virtual Bot/static/screen
SCREEN_TEXT = (0.91, 0.89, 0.86)  # --text
WHITE = (0.93, 0.93, 0.92)
CABLE = (0.10, 0.10, 0.11)
LED_COLOURS = {"idle": (0.95, 0.93, 0.85), "listening": (0.20, 0.55, 1.00), "thinking": (0.95, 0.55, 0.25),
               "speaking": (0.25, 0.90, 0.45), "error": (1.00, 0.15, 0.15)}
