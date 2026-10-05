# Mobile 0.4.8: file editing and built app previews

Tap an owned `/file/`, `/preview/`, or `/uploads/` link in a reply to open it
inside the app. Absolute links must match the paired server; normal external
HTTPS links still open in the browser. Relative links inside Markdown previews
resolve against the canonical file path returned by the server.

Text files have an Edit action and the existing revision-checked autosave.
Switching back retains the conversation draft. File recovery uses a canonical
file identity so opening the same file through chat and Files cannot lose a
newer edit. Session aliases retain the linked conversation throughout reads,
saves, exports, and build requests.

Standalone HTML and built Vite/React projects run in a native embedded preview.
Projects use `dist/index.html` or `build/index.html`. Build or Rebuild with the
bot submits an ordinary chat job with the existing project's path and keeps
unsent drafts intact. After the bot completes, reopen its preview link or press
Refresh. Build success is established by the resulting files; the app does not
start a development server or add a command-execution API.

The host reads preview resources through authenticated, bounded API requests.
The embedded page receives only original asset bytes and public HTTP statuses.
Device tokens, host cookies, headers, and backend diagnostic bodies never enter
the page. Each preview has a disposable origin; external networking, nested
frames, workers, native access, and persistent browser storage are disabled.
Use a relative Vite asset base (`--base ./`). This supports static interactive
apps; it does not provide HMR, external API integrations, or browser storage.
HTML is served only for document navigation; fetching HTML partials as
subresources is unsupported and reports a preview error.

Android renderer loss and missing script/style resources display a recoverable
preview error. Refresh creates a new browsing context. Native preview code also
exists for iOS, but iOS compilation and runtime remain unverified on this Mac.

## Reproduction

Build the [React fixture](../../mobile-app/test-fixtures/react-preview/README.md)
before running `WebAppPreviewTest`. `FilePreviewIntegrationTest` drives the real
App/controller with a fixture API, including measured Markdown-link taps,
formatted preview, editing, autosave, and the interactive native web view.

Backend routes and static asset restrictions are documented in
[MOBILE-API.md](../../Virtual%20Bot/docs/MOBILE-API.md). Backend tests use temporary
owner workspaces and databases. No live user projects or paid provider calls
are needed to reproduce these checks.

## Validation

- Final full Python suite: 1,551 passed, 8 skipped, 178 subtests passed.
- Shared suite: 215 passed. Android JVM suite: 78 passed.
- Native preview suite: all 11 cases passed, including a real Vite/React
  production bundle, opaque frame isolation, cleanup, and renderer-exit handling.
- Both App/controller integration cases passed on phone and tablet. Five existing
  tablet layout tests passed. Phone/tablet preview screenshots were inspected.
- The broader phone run passed 37 of 38 cases. The existing note-follow test
  timed out before any send or stream request (its draft remained unsent); its
  single isolated rerun passed unchanged. The combined run is not reported as
  clean. The new tablet fixture initially expected a phone menu button; it now
  handles the existing persistent sidebar, and both cases passed again.
- Final live loopback smoke: 31 checks passed, including original React bundle
  bytes, authentication, session boundaries, traversal 400, and server cleanup.
- Independent reviews addressed alias/canonical draft identity, relative links,
  linked-chat model selection, reserved session names, renderer termination,
  failed assets, and engine-created blank frames. Normal secret scans passed.

The optimized Android APK is version 0.4.8 / code 15, 2,876,935 bytes. SHA-256:
`4eed9c9be9f642f42abcf31fceb4be227a89d1ee6fc0c0b376373daa03f86bb3`.
This is direct APK delivery, not automatic update publication. The registered
backend was restarted only after observing zero running/stopping jobs; readiness
returned 200 and both preview routes were present. The tunnel was unchanged.
