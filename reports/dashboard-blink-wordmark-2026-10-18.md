# Blink-name typography correction

The final owner instruction limits the concept's Helvetica to the Blink name.
The dashboard's IBM Plex Sans interface, monospace section labels/technical
readouts and Cormorant welcome heading are restored. The four interface
typography files exactly match their versions before the superseded global
Helvetica change. Both wordmark rendering paths use Helvetica 700 at 17px,
normal tracking and a line height of one.

## Verification and publication

- Source commit: `56215594`. Independent supported review approved the
  restored scope and the explicit Tailwind fallback font declaration.
- Dashboard unit suite: 240 passed. Full TypeScript check passed.
- Production build passed using frozen committed inputs; all 529 tracked
  build inputs matched the source revision.
- Browser checks exercised English/Ukrainian, light/dark and 1440px/390px
  layouts. Actual computed styles retain Plex for body/input and Cormorant for
  the welcome heading; desktop Blink-name text reports Helvetica. No horizontal
  overflow or clipped heading/input was found.
- Seven combinations passed in the initial run. The last preview reload hit
  a transient dynamic-import failure while the preview service worker was
  active; that Ukrainian dark phone case passed in a fresh browser with
  preview service-worker registration blocked. No application change was made
  for that preview failure. The production build retains its normal PWA worker.
- Guarded publication completed from `56215594`; all 368 artifact files match
  the served runtime byte for byte. Previous hashed chunks remain for open tabs.
- Post-review actual-app HTTP smoke passed 13 expectations including new
  logo/static delivery and memory traversal rejection with HTTP 400.
- Temporary browser and HTTP servers were stopped. The failed earlier global
  Helvetica publication was rejected before runtime files were changed.

Only the font correction and its built runtime are part of this change.
Other working-tree drafts were preserved. Fable was unavailable; no Fable
review or live model conversation is claimed.
