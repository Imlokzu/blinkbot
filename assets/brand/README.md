# Blink artwork

The approved base is **01 / Cube + Flash**, selected by the owner on 2026-10-18.
`blink-cube-flash-reference.jpg` preserves the supplied study sheet; only its
left-hand cube is selected. The second folded mark is not part of the identity.

`blink-master.svg` is the canonical vector: six closed polygon subpaths form
three diagonal upper-left bands, an upper-right plane, and two lower planes.
The open center and narrow separating gaps retain the reference's proportions.
The lowercase `blink` wordmark stays separate from the symbol.

Production exports are generated directly from this geometric master, never
retraced from a raster. `blink-source.png` is now a generated white-on-dark
preview; it is not the source of truth. `blink-reference.jpg` retains the
superseded 2026-10-07 spiral reference for history only.

## Rebuild

Run `node scripts/sync_brand_assets.mjs` from the repository root with Node
and ImageMagick 7 installed. macOS `iconutil` also refreshes the checked-in
ICNS files; other platforms preserve those existing files. Runtime apps use
bundled assets and need no network access or image processing tools.
`generated-files.json` inventories generated files. Do not edit copies by hand.
The rebuild also refreshes only brand files in the backend's existing
`static/dash`, `static/chat-panel`, and `static/memory-panel` bundles. Dashboard
service-worker revisions are updated for those brand files so installed PWAs
pick up the new artwork without rebuilding unrelated panel code.

- `blink-mark.svg`: transparent vector, suitable for a CSS alpha mask with
  `currentColor` so it remains visible in light and dark themes.
- `blink-mark-white.svg`: white vector for dark surfaces.
- `blink-icon.svg` / `blink-icon.png`: white on near-black for app icons.
- `blink-maskable.svg`: extra space for launcher masking.

Android foreground and monochrome layers use a 0.68 scale to keep the cube's
sharp corners inside the 66dp safe circle. iOS has an opaque RGB 1024px app icon.
PWA installation icons include 192px and 512px PNGs and a separate maskable
icon; Apple touch icons are opaque 180px PNGs. Desktop bundles include ICNS,
ICO and PNG files.

This is the product identity for Blink and the Blink Bot robot landing pages.
The animated robot face, screen-app glyphs, model-provider trademarks and
third-party icon sets keep their own roles and are not replaced by the mark.
Naming/localization work is handled separately by the ongoing Jude task.
