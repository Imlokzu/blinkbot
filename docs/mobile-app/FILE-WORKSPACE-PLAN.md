# Blink mobile file workspace

Status: implemented and verified for Android 0.4.16, 2026-10-09.

All five checkpoints below are complete. Evidence, screenshots, retries and
platform/format limits are recorded in [RELEASE-0.4.16.md](RELEASE-0.4.16.md).
The iOS bridge is source-only on this host; an iOS release is not certified.

The owner selected the complete file workspace as this goal's scope. Other web
panels (memory, integrations and device administration) remain separate work.
The native Codex goal tracks this plan; the older `.omx/ultragoal` dashboard
plan belongs to earlier work and must not be overwritten.

## Outcome and acceptance

Files, chat attachments, Markdown links and AI-produced workspace artifacts
must open the appropriate reader. Editing must use the same file formats as
the web workbench and preserve drafts, revision checks and account/session
boundaries. The UI must remain useful on a small phone with the keyboard open.

| Content | Reader | Editor / actions |
| --- | --- | --- |
| HTML and built web apps | Existing isolated interactive preview with relative assets | CodeMirror source, reload, original-byte export |
| Markdown | Formatted document with tables, tasks and workspace links | Tiptap, source mode, undo/redo, mobile formatting tools |
| Text and code | Readable wrapping and syntax highlighting | CodeMirror with search, selection and undo/redo |
| Excalidraw JSON | Real canvas, fit and zoom, including AI skeleton scenes | Excalidraw, persisted interoperable scene JSON, bounded PNG export through the OS save picker |
| Mermaid | Rendered diagram | Source editor, conversion to a sibling Excalidraw file, and bounded PNG export |
| Raster images and SVG | Visual preview, fit/zoom where supported | Original-byte Save and Share |
| Unsupported binary formats | Explicit format/size information | Preserve and export the original; never silently convert it to text |

Embedded drawings and images in Markdown must resolve within the same
workspace/session. Creating a note or drawing must not overwrite an existing
file. Native improvements include file search/sort/type information, reachable
preview/source controls, visible save status, offline draft recovery and an
explicit conflict workflow. Rich editors never rewrite a file merely because
it was opened. Switching source/rich views must not silently discard content.

## Implementation checkpoints

1. Audit and contract: compare `Workbench.tsx` with the native Files and preview
   paths; record the supported-format matrix and editor trust boundary.
2. Bundle editors: pinned Tiptap 3.31.3, CodeMirror and Excalidraw 0.18.1 using
   the existing web schemas/scene behavior, local assets and English/Ukrainian
   catalogs. Add browser tests for real editing and serialization.
3. Native integration: separate trusted editor WebViews on Android/iOS, bounded
   messages and resources, instance checks, deterministic cleanup and close
   flushing. Arbitrary workspace HTML retains the existing untrusted preview.
4. Workspace behavior: unify routing, improve file navigation, wire editor
   changes into existing autosave/recovery, handle conflicts, new files and
   diagram conversion, and keep AI artifact/session identity intact.
5. Verify and ship: independent adversarial review, native interaction tests,
   hostile bridge/preview cases, real isolated HTTP smoke, release build and
   emulator checks. Commit and push each verified logical change. Document
   actual platform/format limits rather than claiming universal parity.

## Editor bridge contract

The bundled editor is a trusted application asset, not user-authored HTML.
Its unique native origin serves bundled files and validated workspace image
or drawing resources only. No token, cookie, backend URL or generic native
write/file/network API is exposed to JavaScript.

The host passes a document containing `id`, `path`, `kind`, `content`,
`readOnly`, `theme`, `language`, and `saveState`. Native owns the document ID
and associates it with a captured account, canonical path, session and editor
generation. JavaScript reports readiness, monotonically sequenced content
changes, explicit document actions, errors and flush acknowledgements. Native
rejects wrong-instance, wrong-frame/origin, malformed and oversized messages.

The browser entry exposes `window.BlinkWorkspace.openDocument(document)`,
`updateHost(document)` and `flush(token)`. It posts JSON messages through
`window.BlinkNative.postMessage(...)` on Android and
`window.webkit.messageHandlers.BlinkNative.postMessage(...)` on iOS. Message
types are `ready`, `change` (`id`, `sequence`, `content`), `action` (`id`,
`sequence`, `action`, optional `content`/`path`), `error` (`id`, `code`), and
`flushed` (`id`, `token`). Native handles saves; the browser never supplies a
destination for an ordinary change. Opening or receiving an acknowledged edit
must not reset selection or emit another edit.

Changes are delivered promptly, with native autosave retaining its existing
debounce. Native navigation waits for a flush acknowledgement before destroying
an editable view. A failed flush leaves the document open with recovery. Late
callbacks after file/account switches cannot save into a different document.

## Validation boundaries

Preserve existing revision compare-and-swap, local recovery and HTML-preview
isolation. Test Markdown/table/task round trips, drawing edit/reopen and AI
updates, source switching, stale bridge messages, external URL/frame blocking,
closing immediately after typing, offline recovery and conflicting PC edits.
Check English/Ukrainian, light/dark themes and a compact enlarged-text phone.
Use synthetic workspace data; keep the owner's active backend and files intact.
The current Mac has Android tooling but no full Xcode installation; iOS runtime
verification must be reported separately from source/compile evidence.
