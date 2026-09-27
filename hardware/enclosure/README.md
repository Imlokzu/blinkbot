# Bot enclosure (phase 1)

A parametric enclosure for the desktop bot: Raspberry Pi 3, a 3.5" HDMI screen,
USB microphone, USB sound card with speaker, powered USB hub and a pass-through
power bank. Everything connects over USB/HDMI — nothing is wired to GPIO.

One file, `bot-enclosure.scad`, produces the cardboard templates, the 3D model
and the 3D-print parts. Every opening is declared once, so the templates and the
model cannot disagree.

| Assembled | Exploded |
|---|---|
| ![assembled](preview-assembled.png) | ![exploded](preview-exploded.png) |

Default outer size: **192 × 129 × 87 mm** (cardboard, 3 mm walls).

## Modes

```sh
openscad -D 'mode="assembled"' bot-enclosure.scad          # 3D view with ghost parts
openscad -o net.svg    -D 'mode="net"'    bot-enclosure.scad # one foldable net (~69 × 45 cm)
openscad -o front.svg  -D 'mode="pieces"' -D 'only="front"' bot-enclosure.scad  # one A4 piece
openscad -o bot.stl    -D 'mode="print"'  bot-enclosure.scad # body + back panel + shelf
```

`only` takes `front`, `back`, `top`, `bottom`, `left`, `right` or `shelf`; each
piece fits on A4. Templates are line drawings in real millimetres: **print at
100 %**. Solid lines are cuts, dashed lines are folds, dotted lines mark where
the shelf is glued. Pieces are drawn as seen from the outside.

## Checks

Rendering fails with a named error when:

- a cutout is closer than 5 mm to its panel edge or 3 mm to another cutout;
- a part does not fit inside the walls;
- two parts collide.

The console also prints the outer size and every cutout's centre and size.

## Before cutting

All sizes are defaults from shop listings. Measure the real parts and edit the
parameters at the top of the file, especially:

- `screen_size`, `screen_t`, `screen_view` — the 3.5" module and its visible area;
- `bank_size` — the power bank drives the depth;
- `speaker_d` — drives the width (`symmetric = true` keeps the screen centred;
  a 28 mm speaker makes the box ~30 mm narrower);
- `usbc_cut`, `usba_cut`, `port_screw_pitch` — the panel-mount extension cables;
- `mic_size`, `key_cap`, `power_d`.

## Notes

- The Pi's only HDMI port feeds the screen, so the back has no HDMI opening. The
  screen stands behind the front panel and the Pi lies on a shelf behind it: use
  a short flexible HDMI cable, not the U-shaped adapter meant for stacking.
- The back ports are panel-mount extensions from the powered hub. A phone
  plugged in there draws charge from the hub, not from the Pi.
- Cardboard: the back is a door hinged on the top panel and held by a tuck flap,
  so the inside stays reachable. Intake slots are on the sides below the shelf,
  exhaust slots on the top above the Pi.
- Print: the body prints standing on its front (no bridges); the back panel
  screws into two corner columns (M3 self-tapping) and the shelf rests on ribs,
  with M2.5 standoffs for the Pi.

Verified with OpenSCAD 2026.09.23: all modes render and every check passes.
