# Notes

Claude Bot, phase 1 prototype, shaped like a crab. Real-size model of a desk bot built from
parts that plug together with no soldering and no loose GPIO wires.

## What it is

- Body: a wide clay-coloured carapace (front shell) and a darker back cover, held by four
  M3 screws. Eyes on stalks on top, two claws resting on the desk in front of the face,
  three legs per side that hold the belly 12 mm above the desk.
- Face: Waveshare 3.5inch HDMI LCD (E), 640×480 IPS, capacitive touch, from the maker's STEP
  (`inputs/waveshare-35-hdmi-lcd-e.stp`). The screen picture shows a smile (parameter
  "Screen picture": smile, eyes, off).
- Brain: Raspberry Pi 3 Model B, screwed to the four M2.5 studs on the back of the screen.
  This is how Waveshare designed this screen: the Pi's HDMI sits right under the screen's HDMI,
  and a U-shaped HDMI adapter joins them. The screen's pogo pins press on the underside of the
  Pi's GPIO header, so no wires are plugged into the GPIO pins.
- Battery: Redmi 20000 mAh power bank (PB200LZM, 154 × 73.6 × 27.3 mm), standing behind the
  Pi on two cradle blocks. Its manual documents output while charging, so the bot keeps running
  on the charger. USB-A out → Pi micro-USB (cable over the top). Its USB-C port takes a short
  straight panel-mount extension whose socket sits in the right wall: that is the charging port.
- Light bar on top: BlinkStick Strip (8 × WS2812, micro-USB, 85 × 9.5 mm) under a frosted
  diffuser. It shows what the bot is doing (parameter "LED colour": idle, listening, thinking,
  speaking, error).
- Speaker: 28 mm 8 Ω on the screen's built-in amplifier (PH2.0 4-pin header), lying on the
  floor and firing down through the belly grille between the legs.
- Chin: USB mini microphone on a short USB extension, behind three pinholes.
- Vents on top (behind the light bar) and on the back.

## Size

Body 181 × 76 × 106 mm (W × D × H). With legs and claws 236 mm wide, 138 mm deep
(the claws reach 60 mm in front of the face) and 118 mm tall without the eyes, 144 mm with
the eye stalks.

## Parameters

Shell view (closed / cut-away / hidden) and Explode show the inside. Legs, claws, eye stalks,
heatsink, screen picture and LED colour can be switched.

## Not verified yet (measure the real part before printing)

- Power bank port positions on its end (only the outer size is published).
- Pi 3 board thickness and USB port heights inside the stack.
- Speaker: a generic 28 mm 8 Ω speaker is assumed.
- USB mini microphone: Adafruit #3367 size assumed (22.2 × 18.3 × 7 mm).
- USB-C panel-mount extension: a generic straight male-to-female one is assumed.
- Right-angle USB-A and micro-USB plug housings are typical sizes, not a specific product.

## Decisions

- The power bank stands behind the Pi, so the body is a deep box, not a thin frame.
- Legs, claws and eye stalks are separate prints that plug into the shell.
- Back cover: 4 × ISO 7380 M3×16 button-head screws, self-tapping into printed bosses.
