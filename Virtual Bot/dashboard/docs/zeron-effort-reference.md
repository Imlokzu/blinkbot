# Zeron compact effort reference

The owner requested the compact effort UI from Zeron, with Blink's orange
`#E6532F` replacing the purple accent. Two Antigravity source-analysis tasks
checked the upstream metrics independently; the local implementation was
verified against the actual Rust constants and renderer, not screenshot pixels.
Only one Antigravity account was available, so the tasks ran sequentially.

Upstream revision: `2e8f02ec2dfc521a20a5c43fcdbf4fae2381853a` (2026-10-18 checkout).

## Source measurements

| Element | Upstream logical pixels |
| --- | --- |
| Compact effort card | 256 wide, 12 corner radius, 1 border |
| Card inset | 4 |
| Header with effort | 48 high, 8 horizontal inner padding |
| Effort title | 14 font, 17 line height, semibold |
| Model subtitle | 12 font, 15 line height |
| Subtitle chevron | 10 square, 3 gap |
| Slider block | 36 high, 2 top and 4 bottom padding |
| Slider horizontal padding | 8 inside the card inset |
| Rail | 28 high, pill radius 14 |
| Thumb | 44 wide, 32 high, pill radius 16 |
| Thumb centre inset | 22 from each rail end |
| Stops | 4 diameter, evenly spaced between the thumb endpoints |
| Fill | Ends 14 beyond the thumb centre, concealed by the handle |
| Card without extra options | 100 high including border |

These values are GPUI logical units. Screenshot scaling, font rendering and
transitional layout states can change measured image pixels.

Sources:
- [Compact picker constants, header and slider renderer](https://github.com/zeronsh/zeron/blob/2e8f02ec2dfc521a20a5c43fcdbf4fae2381853a/crates/ui/src/pickers/compact.rs)
- [Popover radius and inset](https://github.com/zeronsh/zeron/blob/2e8f02ec2dfc521a20a5c43fcdbf4fae2381853a/crates/ui/src/popover.rs#L307)
- [Neutral rail, accent fill and opaque thumb materials](https://github.com/zeronsh/zeron/blob/2e8f02ec2dfc521a20a5c43fcdbf4fae2381853a/crates/ui/src/glass.rs#L82)

## Surrounding composer audit

The source uses a 26px composer radius, a 768px maximum width, a 120px empty
composer height and 16px horizontal text padding. Its compact height is 49px;
expanded input is capped at 304px. The model control can occupy up to 45% of
the available surface. These are reference measurements, not an instruction
to replace Blink's existing layout. The previously approved 28px Blink composer
radius, wallpaper and navigation remain unchanged.

See [composer constants and layout](https://github.com/zeronsh/zeron/blob/2e8f02ec2dfc521a20a5c43fcdbf4fae2381853a/crates/ui/src/composer.rs#L70).

## Web adaptation

- The effort value and model name form one compact change-model button. The
  old heading, separator, provider mark and endpoint captions are removed
  from this view; the model-list view retains its existing design.
- Use Blink orange as the base of a quiet vertical highlight, a neutral rail
  and a white pill thumb in both themes. No continuous shimmer is introduced.
- Pointer travel, stop centres and thumb travel share the same 22px inset.
  Radix's extra inbounds correction is compensated with a static translation;
  it must remain active under reduced motion because it positions the thumb.
- Keep the gateway's advertised levels, Automatic versus Off, keyboard control,
  save-on-release behavior, rollback and gesture cancellation.
- Upstream fast tier is conditional on a supported option. Blink's gateway
  does not expose that option, so no nonfunctional lightning button is added.
- New user-visible text is unnecessary; existing English/Ukrainian keys remain.

## Upstream license

The dimensions, coordinate mapping and material treatment are adapted from
Zeron. Its upstream MIT notice is retained below.

MIT License

Copyright (c) 2026 Wing

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
