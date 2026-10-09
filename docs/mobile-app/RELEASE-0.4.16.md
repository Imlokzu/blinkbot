# Blink 0.4.16: native file workspace

Status: Android implementation verified; optimized APK built and installed.

Android version code 23. This work completes the file-workspace scope selected
by the owner; it does not port the unrelated administration panels.

Artifact: `mobile-app/build/Blink-0.4.16.apk` (approximately 17 MiB), built with
R8 and resource shrinking. The embedded editor manifest matches the checked-in
source. SHA-256:
`b431ff77d38e034e6c78bf145bbfdde28cecce7df462bc19714d4bc6450391a4`.
This owner-distributed APK retains the existing development signing setup;
it is not a signed store release.
The optimized install reports version 0.4.16 / code 23. Cold launch, opening
the connection-code form and returning to QR were checked on the dedicated
emulator. The full workspace checks use the non-debuggable benchmark build
with test fixture entry points; they are not claimed as release instrumentation.

## File readers and editors

Files and chat artifacts now use the same type-aware readers. HTML opens in
the existing isolated interactive preview; images retain original-byte export
and native zoom, and self-contained SVG has a visual reader. Markdown opens as
a formatted document with working workspace images and embedded drawings.

The local editor pack provides real Tiptap Markdown editing, CodeMirror source
editing and Excalidraw. Markdown supports tables, tasks, headings, lists and
links, with a source view for direct editing. HTML is edited as source so its
scripts/layout are preserved. Drawing files retain standard Excalidraw JSON;
AI skeleton scenes are expanded without rewriting a file simply on opening.
Mermaid renders as a diagram and can create a distinct Excalidraw sibling.
Drawings can be exported as PNG through the operating system's save picker.

The file browser adds folder breadcrumbs, search, sorting, file types/sizes,
and new note/drawing actions. File dialogs scroll on short screens. Save status,
copy-source, preview/export and save-copy actions stay reachable. Recognized
PDF, Office and archive formats retain original Save/Share; they are not
presented as editable text merely because their bytes decode as UTF-8.

## Persistence and concurrent AI changes

- The native controller owns saves, account/session identity, revisions and
  recovery. JavaScript receives document content but no credentials.
- Closing or navigating away waits for the editor to flush. Late callbacks
  cannot navigate or modify a later document. A failed flush keeps the editor
  open and offers the last native-received text as explicit recovery.
- Recovery is available before a successful network read. Storage errors retain
  the in-memory buffer and have persistent feedback with copying available.
- Conflicts preserve both versions and offer comparison, keeping local content,
  loading the server version or saving a separate copy. A second server change
  still goes through revision checks.
- Active AI writes keep the previous complete text, Markdown, code or drawing
  reader visible. Successful writes refresh the matching reader; dirty editor
  content is not replaced. HTML and image previews close during active writes.
- Temporary AI interaction locks are distinct from permanent read-only access.
  Last edits already in flight are retained locally while server saves pause.
  When writing ends, ordinary baseline/revision checks resume.

## Trust and packaging

Trusted editors use a separate local WebView origin and a bounded, typed native
bridge. Arbitrary workspace HTML keeps its untrusted preview and receives no
editor bridge. Static editor resources come from the bundled manifest; workspace
resources are restricted to validated images/drawing JSON in the captured
document context. Wrong-frame, stale-ID, malformed and oversized messages are
rejected. Network/file access, credentials and generic native writes are not
exposed to the editor page.

The pack is reproducible from pinned dependencies under `mobile-app/editor-web`.
Fonts are local. Narrow verified transforms remove Excalidraw's external font
fallback and unused hosted-app Firebase configuration. The original configuration
was caught by the staged secret scan and removed before publication; scanning
remains enabled. Third-party licenses and notices accompany the sources.

Android WebViews explicitly fill their native container. This prevents
percentage-height document roots from collapsing even when DOM nodes exist,
which had produced invisible, unhittable drawing controls.
The untrusted preview also clips native drawing to its own rectangle: the SVG
reader had painted over part of the native header despite correct layout bounds.
SVG images use a block layout without document overflow, keeping fitted images
free of unnecessary scrollbars.

## Verification

The shared and Android unit suites pass: 322 shared and 78 Android tests.
The editor pack passes 23 unit tests and 22 distinct browser scenarios (the
21-case full run plus a focused final pending-drawing-reply scenario). External
requests, missing bundled resources, CSP violations and browser page errors
were zero. Rebuilding the editor pack produced the same manifest and assets;
the installed benchmark APK's manifest matched the source exactly.

Native checks cover eight complete file-workspace flows, two chat-link preview
flows, the main chat/Files/appearance flow, four trusted bridge cases, and three
HTML sizing/isolation/renderer-lifecycle cases. Compact dark-mode reruns cover
rich edits, Mermaid conversion and the file browser at 140% text size on a
360-by-720 dp viewport. Screenshots were inspected for actual rendering and
reachable controls: [Markdown](screenshots/0.4.16-markdown.png),
[dark drawing](screenshots/0.4.16-drawing-dark.png), and
[enlarged file browser](screenshots/0.4.16-files-large-text.png). The final
[SVG capture](screenshots/0.4.16-svg.png) confirms the header is fully visible
after native clipping. Its format/sizing/isolation regression run passed all
three cases after the final production change, and the 400 unit tests passed
again.

Failures and retries are retained in the validation record: a full 12-case
workspace run passed 11 cases, while the format test's global Close selector
matched both the preview and a save notice. Scoping it to the preview fixed the
test; the full format case passed, including SVG and original PDF bytes. An
earlier emulator run stalled during renderer lifecycle testing, and a subsequent
WebView startup exceeded the existing three-second JavaScript callback bound.
Restarting the dedicated Pixel_8 with hardware graphics and Vulkan disabled
allowed the unchanged renderer, isolation and sizing checks to pass. This is
an emulator observation, not a proven production GPU defect; no timeout or
assertion was weakened. Earlier native drawing startup/fixture failures and the
blank viewport led to the fixes described above.
The optimized-release screenshot pass later hit another emulator display stall
(`bad color buffer handle`). A software-rendered emulator restart also showed
a transient Android System UI not-responding dialog; after choosing Wait, Blink's
cold start and pairing controls passed. These environment failures remain part
of the record rather than being presented as a clean uninterrupted run.

Separate adversarial source and architecture reviews returned APPROVE/CLEAR;
Fable and the installed role models were unavailable, so supported independent
reviewers were used. The final simplification review found no unnecessary
abstraction requiring changes. Isolated live HTTP smoke met 28 expectations,
including static assets and traversal responses of 400, then closed its server.
The full backend run had 1,669 passes, six skips and 178 subtests, with one
unrelated connector-process timing failure; that 15-test module passed on retry.
The backend run is not represented as an entirely clean first pass.

The checks exercise real bundled browser editors, the native message bridge,
the full App/controller against synthetic authenticated host responses, original
file bytes, conflict/recovery behavior and actual pointer interaction. They do
not substitute a DOM-exists assertion for visible editor controls.

Reproduction commands:

```sh
cd mobile-app/editor-web
pnpm install --frozen-lockfile --ignore-scripts
pnpm run typecheck
pnpm test
pnpm run build
pnpm run test:browser

cd ..
./gradlew --max-workers=2 --no-parallel \
  :shared:testDebugUnitTest :androidApp:testDebugUnitTest \
  -PuiTestBuildType=benchmark \
  :androidApp:assembleBenchmark :androidApp:assembleBenchmarkAndroidTest
# Install both benchmark APKs on a dedicated emulator, then run the native
# workspace, preview and controller integration instrumentation classes.
./gradlew --max-workers=2 --no-parallel :androidApp:assembleRelease
```

## Limits

- iOS has a source implementation, but this Mac has no full Xcode installation;
  an iOS build/runtime is not certified by the Android checks.
- PDF/Office/binary files are preserved for original-file export. This release
  does not add an Office converter or PDF editor that the web workbench lacks.
- Interactive HTML uses bundled/relative assets. Remote assets, network services
  and filesystem access outside its preview scope remain blocked. The bot build
  prompt now states those constraints.
- Existing backend limits remain: text reads up to 1,000,000 bytes, writes up to
  2,000,000 UTF-8 bytes and bounded original-file downloads. Rich editing is not
  a byte-preserving transformation of every Markdown extension; source mode
  remains available, and opening alone never serializes/replaces the file.
- Emulator results do not certify physical-device frame rates or real-provider
  execution. The original owner workspace was not used as test data.
