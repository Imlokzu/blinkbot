# Orange flash logo treatment

The owner selected partial orange on the Cube + Flash logo: the three
upper-left bands use `#E6532F`; the remaining cube planes and wordmark use
neutral theme ink. This is a logo color decision, not an interface palette change.

## Changes

- The canonical SVG separates `flash` and `body` paths. Their combined
  geometry is identical to the approved Cube + Flash master.
- The existing generator exports colored marks, launcher icons and splashes;
  monochrome silhouettes remain available for alpha masks, themed launcher
  icons and OS notifications.
- Dashboard and landing masks overlay the orange flash while their cube
  planes follow theme ink. The Compose mark similarly overlays the flash
  on its theme-tinted silhouette.
- Existing served dashboard artwork gains a dedicated brand stylesheet and
  one tagged link. Its changed index/artwork hashes and new brand files are
  included in the existing PWA cache. Unrelated compiled chunks are preserved.

## Verification

- Generator syntax check and full generation passed. All 143 generated
  outputs exist; SVG/XML files parse; original geometry, colored foregrounds,
  monochrome OS fallbacks, runtime copy parity and PWA hashes were checked.
- Dashboard unit tests: 240 passed. The changed BotIcon passes a scoped
  TypeScript check, and the final full-project TypeScript check also passed.
  Concurrent model-picker work remains outside this branding change.
- `:shared:compileDebugKotlinAndroid` passed, including the new Compose vector
  resource and layered native mark. No native app release or iOS runtime test.
- Post-review actual-app HTTP smoke passed 13 expectations, including new
  colored SVG/CSS delivery and memory traversal rejection with HTTP 400.
- A browser rendering of the actual served alpha mask and flash stylesheet
  shows orange bands with dark/light cube planes. The selected standalone
  artwork was also inspected and delivered. Temporary browser/server stopped.
- Independent supported native review corrected runtime stylesheet and cache
  integration, checked copy parity and fixture idempotence, and identified the
  legacy home mask, which now uses the same orange overlay. Review also fixed
  Compose minimum-size propagation so the base and flash stay aligned at 76dp;
  Android shared compilation passed again after that correction. Fable unavailable.

Pre-existing drafts and concurrent model-picker work are outside this change.
