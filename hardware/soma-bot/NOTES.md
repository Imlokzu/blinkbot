# Notes

Claude Bot, phase 1 prototype. Real-size model of a desk bot built from parts that plug
together with no soldering and no loose GPIO wires.

## What it is

- Face: Waveshare 3.5inch HDMI LCD (E), 640×480 IPS, capacitive touch. The model uses the
  maker's STEP (`inputs/waveshare-35-hdmi-lcd-e.stp`).
- Brain: Raspberry Pi 3 Model B, screwed to the four M2.5 studs on the back of the screen.
  This is how Waveshare designed this screen: the Pi's HDMI sits right under the screen's HDMI,
  and a U-shaped HDMI adapter joins them. The screen's pogo pins press on the underside of the
  Pi's GPIO header, so no wires are plugged into the GPIO pins.
- Light bar on top: BlinkStick Strip (8 × WS2812, micro-USB, 85 × 9.5 mm) under a frosted
  diffuser. It shows what the bot is doing (parameter "LED colour": idle, listening, thinking,
  speaking, error).
- Left "ear": 28 mm 8 Ω speaker on the screen's built-in amplifier (PH2.0 4-pin header).
- Right "ear": vent grille for the Pi.
- Chin: USB mini microphone on a short USB extension, behind three pinholes.
- Back: panel-mount USB-C for power (a magnetic USB-C tip works here), vents, the Claude spark.

## Size

124 × 47 × 110 mm (W × D × H, with the 4 mm feet). Shell wall 2.5 mm.

## Parameters

Shell view (closed / cut-away / hidden) and Explode show the inside. Feet, heatsink,
the face on the screen and the LED colour can be switched.

## Not verified yet (measure the real part before printing)

- Pi 3 board thickness and USB port heights inside the stack.
- Speaker: a generic 28 mm 8 Ω speaker is assumed.
- USB mini microphone: Adafruit #3367 size assumed (22.2 × 18.3 × 7 mm).
- Panel-mount USB-C jack: Adafruit #4053 style assumed (M3 screws 20 mm apart).
- Right-angle USB-A and micro-USB plug housings are typical sizes, not a specific product.

## Decisions

- No battery in phase 1 (a power bank makes the body much deeper). Power comes in through the
  USB-C jack on the back.
- Back cover: 4 × ISO 7380 M3×16 button-head screws, self-tapping into printed bosses.
