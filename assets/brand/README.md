# Blink artwork

`blink-reference.jpg` is the owner's supplied spiral mark (2026-10-07).
`blink-source.png` is a square, opaque ImageGen adaptation of that reference.
The production outlines are derived from this source so web and native assets
share one silhouette. The original attachment is retained for comparison.

The final generation prompt requested the same irregular white spiral and
horizontal spikes, centered at 76% width on near-black, without lettering,
borders, shadows or a redesign. Two transparent extraction attempts were
rejected because they introduced speckles and were not shipped.

## Rebuild

Run `node scripts/sync_brand_assets.mjs` from the repository root with Node
and ImageMagick 7 installed. macOS `iconutil` also refreshes the checked-in
ICNS files; other platforms preserve those existing files. Runtime apps use
bundled assets and need no network access or image processing tools.
`generated-files.json` inventories generated files. Do not edit copies by hand.

- `blink-mark.svg`: transparent vector, suitable for a CSS alpha mask with
  `currentColor` so it remains visible in light and dark themes.
- `blink-mark-white.svg`: white vector for dark surfaces.
- `blink-icon.svg` / `blink-icon.png`: white on near-black for app icons.
- `blink-maskable.svg`: extra space for launcher masking.

Android foreground and monochrome layers keep the complete mark inside the
66dp safe circle. iOS has an opaque RGB 1024px app icon. PWA installation icons
include 192px and 512px PNGs and a separate maskable icon; Apple touch icons
are opaque 180px PNGs. Desktop bundles include ICNS, ICO and PNG files.

This is the product identity for Blink and the Blink Bot robot landing pages.
The animated robot face, screen-app glyphs, model-provider trademarks and
third-party icon sets keep their own roles and are not replaced by the mark.
Naming/localization work is handled separately by the ongoing Jude task.
