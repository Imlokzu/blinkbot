# Notes

Claude Bot, phase 1 prototype, built as **Clawd**, the Claude Code mascot. Real-size model
of a desk bot built from parts that plug together with no soldering and no GPIO wires.

## Shape

Taken from Clawd's pixel art in Claude Code (default pose, and the "edge" turn for the
feet). One pixel is 8.5 mm wide and 17 mm tall (terminal cells are twice as tall as wide).

- Front: 13 × 4 pixels → 110.5 × 68 mm. The body is 83 mm deep, about as deep as the bot is
  tall on its feet: a chunky box (the sprite's side view is only 5 pixels deep, too thin for
  the parts).
- Eyes: one pixel each (8.5 × 17 mm), in the second row, two pixels in from each side.
- Arms: 2 × 1 pixels on each side of the third row, 2 pixels deep, near the front.
- Feet: one pixel each, one row tall, under columns 3, 5, 13 and 15 of the sprite, at the
  front edge and at the back edge (eight feet; four show from the front, two from the side).
- Colour: Claude Code's orange, rgb(215, 119, 87).

Overall 144.5 × 85 × 85 mm with arms, feet and the screw heads on the back: a bit more
than half as tall as a phone standing up, and about two phones wide with the arms.

## Inside

- Eyes are screens: an Adafruit 0.96" 128×64 OLED (#326) behind each eye pixel. Each OLED
  connects by a STEMMA QT cable to its own Adafruit MCP2221A USB-to-I2C board (#4471), which
  plugs into the Pi over USB-C. Two boards, so the two OLEDs can keep the same I2C address.
  Off pixels are black, so a dark OLED looks exactly like Clawd's eye; lit pixels can make the
  eyes look around, blink or wink. The 8.5 × 17 mm eye shows the middle of the OLED's
  21.7 × 10.9 mm picture (about 100 × 50 of its pixels, turned upright).
- Brain: Raspberry Pi 3 Model B lying across the floor on 4.5 mm standoffs, right behind
  the sound card; USB and Ethernet face the left side, the micro-USB power socket the back.
  All four USB ports are used: left eye, right eye, LED strip, sound card.
- Battery: a compact 10000 mAh power bank (92 × 60 × 22 mm, Anker PowerCore 10000 size
  class) on a shelf over the Pi, ports on its left end. USB-A → down the left side and along
  the back → Pi micro-USB. Its USB-C port takes a straight panel-mount extension; the socket
  in the left wall, behind the left arm, is the charging port. UNVERIFIED: pick a bank whose
  maker documents pass-through charging (the bot runs while charging); Anker's PowerCore
  10000 itself is not documented for that.
- Sound: Waveshare USB TO AUDIO (onboard dual microphones, 2.6 W amplifier on PH2.0)
  standing on its edge under the eyes, on a short USB extension; the microphones listen
  through holes in the belly. A 2030 box speaker (8 Ω, UNVERIFIED size) sits in clips on the
  back cover and fires out of the back.
- Light bar: BlinkStick Strip (8 × WS2812, micro-USB) across the top under a frosted
  diffuser flush with the top (parameter "LED light bar on top").
- Vents in the top and the back cover. The front stays plain like the sprite.

## Parameters

Shell view (closed / cut-away / hidden) and Explode show the inside. Light bar, heatsink,
wall, edge rounding and LED colour can be changed.

## Not verified yet (measure the real part before printing)

- OLED glass position on its board (assumed centred) and the board's thickness.
- Waveshare USB TO AUDIO: where its microphones and speaker header sit.
- The power bank: size, port positions and pass-through (see above).
- Pi 3 board thickness and USB port heights inside the stack.
- Speaker: a generic 2030 box speaker (30 × 20 × 5 mm, 8 Ω) is assumed.
- USB-C panel-mount extension and right-angle plug housings: generic sizes.

## Decisions

- No 3.5" HDMI screen: Clawd has no screen, so the eyes are the screens.
- The eye pixels are exactly 1 × 2 pixels and smaller than the OLED glass, so the whole eye
  is screen.
- The 20000 mAh Redmi bank (154 mm long) made the body either very big or very long; the
  compact 10000 mAh bank lets it be a small chunky box.
- Arms and feet are separate prints glued to the body.
- Back cover: 4 × ISO 7380 M3×16 button-head screws, self-tapping into printed bosses.
