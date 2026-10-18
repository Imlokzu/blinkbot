# Cube + Flash base mark

The owner selected option 01 from the supplied Blink study sheet on 2026-10-18.
The canonical artwork is `assets/brand/blink-master.svg`; its six polygon
subpaths retain the open faceted cube, three diagonal bands, and two separated
lower planes. The lowercase wordmark remains separate. The reference sheet is
preserved at `assets/brand/blink-cube-flash-reference.jpg`.

## Implementation

- The existing generator now exports from the geometric SVG master instead of
  tracing a raster. The opaque `blink-source.png` is a derived preview.
- Updated the existing web, PWA, Android, iOS, React Native, Capacitor and desktop
  artwork. Runtime dashboard/chat/memory copies are synchronized without
  rebuilding unrelated JavaScript or CSS.
- Updated only artwork revisions in the existing dashboard service worker,
  including duplicate precache entries, to invalidate the prior spiral mark.
- Root `DESIGN.md` and the brand README record the approved direction.

## Verification

- `node scripts/sync_brand_assets.mjs`: 115 artwork/runtime files plus the
  generated inventory written successfully.
- Validated all 41 generated SVG files and native vector XML; path geometry
  matches the master. Runtime copies match their source assets byte for byte.
- All 16 affected dashboard precache entries carry correct content hashes.
- Android extreme-corner radius is 28.61dp inside the 33dp safe circle. The PWA
  maskable version also fits its safe circle. iOS and launcher PNG dimensions
  and opaque RGB export were checked.
- `npm test` in `Virtual Bot/dashboard`: 240 passed, zero failed.
- Inspected a browser-rendered proof on light/dark backgrounds and at
  48px, 32px and 24px sizes. The wordmark shown in that proof is presentation
  text, not a newly approved outlined wordmark asset.
- Post-review isolated actual-app HTTP smoke: 13 dashboard/panel/shared static
  routes returned 200 with the new artwork; memory path traversal returned 400.
  The temporary server and isolated browser were stopped. No model calls,
  chat submissions, deployment, or native application release was performed.
- Independent supported native reviewer found and fixed stale runtime copies
  and PWA revisions. It also checked fixture idempotence and preservation of
  unrelated bundles/cache entries. Fable and the configured specialist model
  were unavailable; no Fable review is claimed.

Other pre-existing working-tree edits are outside this branding change.
