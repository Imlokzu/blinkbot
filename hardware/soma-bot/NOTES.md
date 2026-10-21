# Notes

Claude Bot, phase 1 prototype, built as **Clawd**, the Claude Code mascot. Real-size model
of a desk bot built from parts that plug together with no soldering and no GPIO wires.

## Shape

Taken from Clawd's pixel art in Claude Code (default pose, and the "edge" turn for the
feet). One pixel is 8 mm wide and 16 mm tall (terminal cells are twice as tall as wide).

- Front: 13 × 4 pixels → 104 × 64 mm. The body runs 173 mm back (deeper than the sprite's
  5 pixels) so the power bank can lie flat and the front stays small.
- Eyes: one pixel each (8 × 16 mm), in the second row, two pixels in from each side.
- Arms: 2 × 1 pixels on each side of the third row, 2 pixels deep, near the front.
- Feet: one pixel each, one row tall, under columns 3, 5, 13 and 15 of the sprite, at the
  front edge and at the back edge (eight feet; four show from the front, two from the side).
- Colour: Claude Code's orange, rgb(215, 119, 87).

Overall 136 × 175 × 80 mm with arms, feet and the screw heads on the back: a little
shorter than a phone standing up, and about one and a half phones wide with the arms.

## Inside

- Eyes are screens: an Adafruit 0.96" 128×64 OLED (#326) behind each eye pixel. Each OLED
  connects by a STEMMA QT cable to its own Adafruit MCP2221A USB-to-I2C board (#4471), which
  plugs into the Pi over USB-C. Two boards, so the two OLEDs can keep the same I2C address.
  Off pixels are black, so a dark OLED looks exactly like Clawd's eye; lit pixels can make the
  eyes look around, blink or wink. The 8 × 16 mm eye shows the middle of the OLED's
  21.7 × 10.9 mm picture (about 94 × 47 of its pixels, turned upright).
- Battery: Redmi 20000 mAh power bank (PB200LZM) lying flat on the floor, ports at the
  front. Its manual documents output while charging. USB-A → up between the eye boards →
  Pi micro-USB. Its USB-C port goes by a short cable to a panel-mount socket in the right
  wall, just under the right arm: that is the charging port.
- Brain: Raspberry Pi 3 Model B lying flat on 4 mm standoffs on a shelf over the power bank,
  USB and Ethernet facing the back. All four USB ports are used: left eye, right eye, LED
  strip, sound card.
- Sound: Waveshare USB TO AUDIO (onboard dual microphones, 2.6 W amplifier on PH2.0) on the
  shelf behind the Pi, on a short USB extension; the microphones listen through holes in the
  left side. A 28 mm 8 Ω speaker stands beside the power bank and fires out of the left side.
- Light bar: BlinkStick Strip (8 × WS2812, micro-USB) running front to back along the top,
  under a frosted diffuser flush with the top (parameter "LED light bar on top").
- Vents in the top and the back cover. The front stays plain like the sprite.

## Parameters

Shell view (closed / cut-away / hidden) and Explode show the inside. Light bar, heatsink,
wall, edge rounding and LED colour can be changed.

## Not verified yet (measure the real part before printing)

- OLED glass position on its board (assumed centred) and the board's thickness.
- Waveshare USB TO AUDIO: where its microphones and speaker header sit.
- Power bank port positions on its end (only the outer size is published).
- Pi 3 board thickness and USB port heights inside the stack.
- Speaker: a generic 28 mm 8 Ω speaker is assumed.
- USB-C panel-mount extension and right-angle plug housings: generic sizes.

## Decisions

- No 3.5" HDMI screen: Clawd has no screen, so the eyes are the screens.
- The eye pixels are exactly 1 × 2 pixels; the OLED glass (26.6 mm tall) is 0.4 mm shorter
  than the eye, the black board shows in that gap.
- Arms and feet are separate prints glued to the body.
- Back cover: 4 × ISO 7380 M3×16 button-head screws, self-tapping into printed bosses.
