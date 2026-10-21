# Notes

Claude Bot, phase 1 prototype, built as **Clawd**, the Claude Code mascot. Real-size model
of a desk bot built from parts that plug together with no soldering and no GPIO wires.

## Shape

Taken from Clawd's pixel art in Claude Code (default pose, and the "edge" turn for the
depth). One pixel is 13.5 mm wide and 27 mm tall (terminal cells are twice as tall as wide).

- Body: 13 × 4 pixels front, 5 pixels deep → 175.5 × 67.5 × 108 mm.
- Eyes: one pixel each (13.5 × 27 mm), in the second row, two pixels in from each side.
- Arms: 2 × 1 pixels on each side of the third row, 2 pixels deep, centred.
- Feet: one pixel each, one row tall, under columns 3, 5, 13 and 15 of the sprite, at the
  front edge and at the back edge (eight feet; four show from the front, two from the side).
- Colour: Claude Code's orange, rgb(215, 119, 87).

Overall 229.5 × 69 × 135 mm with arms, feet and the screw heads on the back.

## Inside

- Eyes are screens: an Adafruit 0.96" 128×64 OLED (#326) behind each eye pixel. Each OLED
  connects by a STEMMA QT cable to its own Adafruit MCP2221A USB-to-I2C board (#4471), which
  plugs into the Pi over USB-C. Two boards, so the two OLEDs can keep the same I2C address.
  Off pixels are black, so a dark OLED looks exactly like Clawd's eye; lit pixels can make the
  eyes look around, blink or wink.
- Brain: Raspberry Pi 3 Model B on four standoffs behind the front wall, components facing
  the back. All four USB ports are used: left eye, right eye, LED strip, sound card.
- Sound: Waveshare USB TO AUDIO (onboard dual microphones, 2.6 W amplifier on PH2.0) on a
  short USB extension, lying on the floor; the microphones listen through holes in the belly.
  A 28 mm 8 Ω speaker lies on the floor and fires down between the feet.
- Battery: Redmi 20000 mAh power bank (PB200LZM), standing behind the Pi on two cradle
  blocks. Its manual documents output while charging. USB-A → Pi micro-USB, cable over the
  bank. Its USB-C port takes a straight panel-mount extension; the socket in the right wall,
  just under the right arm, is the charging port.
- Light bar: BlinkStick Strip (8 × WS2812, micro-USB) under a frosted diffuser flush with
  the top (parameter "LED light bar on top"; switch it off for the plain sprite silhouette).
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
