# Rounded solid logo tile

The owner requested a rounded square behind the logo so its interior is solid.
The selected orange-and-white cube now sits on charcoal `#151613`, with a
60-unit corner radius on a 256-unit square and 82% centered artwork scale.
Its open center and gaps reveal the charcoal tile rather than the page.
Only the outer rounded corners are transparent in the rounded export.

## Implementation

- The canonical cube symbol is unchanged. The existing generator owns the tile
  geometry and new `blink-tile.svg` / `blink-tile.png` outputs.
- In-app dashboard, native Compose, landing and legacy logo placements use
  the solid tile. The separate Blink wordmark keeps its approved typography.
- Existing compiled dashboard avatars are updated through generated brand CSS
  that replaces their old alpha mask and background. New tile files, artwork
  hashes and the stylesheet/index revisions are in the PWA precache.
- Desktop PNG/ICO/ICNS use the rounded source. iOS opaque square and Android
  foreground/monochrome inputs retain platform-owned launcher masking.

## Verification

- 240 dashboard unit tests passed; full TypeScript check passed.
- Android shared Kotlin compilation passed with the generated native tile.
- Validated 157 generated outputs, parseable SVG/XML, unchanged master geometry,
  native tile parity, runtime copy parity and updated cache hashes.
- PNG pixel checks confirm a fully opaque charcoal center and transparent
  outer rounded corners. Desktop exports match the rounded source.
- Browser proof and actual served runtime CSS were inspected on light and dark
  surfaces. Computed styles confirm old masks are removed and the tile is used.
- Post-review actual-app HTTP smoke passed 15 expectations, including tile
  delivery and memory traversal rejection with HTTP 400. Temporary browser and
  server were stopped.
- Independent supported native review found the desktop square-export mismatch;
  it was fixed and rechecked. Final review: approved, no remaining blockers.
  Fable was unavailable. No native app release or iOS runtime test is claimed.

Only logo-related source/assets are included. Existing unrelated drafts and
concurrent effort-control changes are preserved.
