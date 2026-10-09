# Blink workspace editor pack

This is the trusted local editor used inside Blink's native workspace. It bundles
Tiptap Markdown, CodeMirror source editing, Excalidraw and Mermaid conversion.
It does not load the dashboard, contact the backend or receive credentials.
Workspace HTML is source text here; interactive HTML belongs to the separate
untrusted web-preview surface.

## Build and verify

```sh
pnpm install --frozen-lockfile --ignore-scripts
pnpm run typecheck
pnpm test
pnpm run build
pnpm run test:browser
```

Use Node 26 and pnpm 10. The browser suite uses the installed `agent-browser`
CLI with an isolated loopback fixture server. It never opens the owner's server
or workspace. It checks real editor serialization, drawing interactions, native
bridge generations, local resources and outgoing network requests.

The build replaces only
`../shared/src/commonMain/composeResources/files/workspace-editor/`. Commit this
production pack along with its sources and `pnpm-lock.yaml`. Runtime does not
require npm or an internet connection. `manifest.json` maps relative asset paths
to MIME types and includes SHA-256 digests. Its `entry` is `index.html`; the
manifest itself is not an executable resource. No build timestamp is included.

Direct dependencies are exact versions. All transitive Tiptap packages are also
overridden to 3.31.3 to keep the ProseMirror schema and Markdown serializer aligned.
Manrope comes from the existing mobile resources; Excalidraw fonts come from the
pinned package. The large optional Xiaolai font is excluded, matching the web app.
The Vite configuration removes Excalidraw's unconditional CDN font fallback with
a narrow, verified transform of the pinned package. A changed upstream source
shape fails the build, so a dependency update cannot silently restore requests.
Another verified transform empties the dependency's unused hosted-app Firebase
configuration. The original config is never logged or included in the mobile
pack; dependency source changes fail this check rather than bypassing scans.

## Host contract

After installing the editor host interface, JavaScript emits `{type:"ready"}`.
Android receives JSON strings through `BlinkNative.postMessage`; iOS receives
the same strings through `webkit.messageHandlers.BlinkNative.postMessage`.

`BlinkWorkspace.openDocument` accepts:

```ts
{
  id: string;       // New native generation for each open/reload/account switch.
  path: string;     // Canonical workspace-relative path, matching action replies.
  kind: 'markdown' | 'code' | 'text' | 'html' | 'drawing' | 'mermaid';
  content: string;
  readOnly: boolean;
  theme: 'light' | 'dark';
  language: 'en' | 'uk';
  saveState: string;
}
```

`updateHost({id, readOnly?, theme?, language?, saveState?})` updates metadata.
Content echoes are deliberately ignored: native save acknowledgements must not
replace newer typing or reset selection. Reloaded file contents require a new ID.

Edits emit `{type:"change",id,sequence,content}` immediately. Actions share the
same monotonic per-document sequence and emit `{type:"action",id,sequence,
action,path?,content?}`. Native owns revisions, autosave, recovery and all writes.
Supported actions are:

- `openWorkspace`: canonical workspace-relative `path`.
- `openExternal`: explicit HTTPS `path`; the editor never navigates there itself.
- `convertMermaid`: interoperable scene JSON in `content`; native creates a
  collision-safe sibling and preserves the source. Available in read-only mode.
- `createDrawing`: native allocates a new drawing. It replies through
  `resolveAction({id,sequence,path?,error?})`. Only the matching live editor
  inserts the returned drawing reference, then emits an ordinary Markdown edit.
- `exportDrawing`: a PNG data URL in `content`, with no destination. Native
  validates the decoded PNG, allocates its filename and opens the OS save picker.
  Available for drawing and Mermaid documents in either edit or read-only mode.
  Both canvas dimensions are capped at 4096 pixels and the encoded message at
  2,000,000 UTF-8 bytes. Export never rewrites the source or embeds its scene JSON.
  Size/render failures are localized, nonfatal alerts inside the editor.
- `retry`: asks the host to reopen the failed editor.

Markdown uses the same deterministic path convention as the web workbench:
bare filenames and `./...` paths resolve against the document's parent; other
slash-containing paths are workspace-root paths (including `session/...`,
`sessions/...`, `notes/...`, and `projects/...`). There is no existence-based
fallback. `/workspace/`, `/file/` and `/preview/` explicitly refer to the owned
workspace root. New drawings inside the document's directory use a relative
link with a `./` prefix when it contains subdirectories. A drawing in another
directory uses `/workspace/<canonical-path>`. Raw `..`, absolute disk paths,
URL schemes and malformed encodings never become workspace requests.
Local GET requests encode each canonical path segment separately under
`/workspace/`. Native validates the path and serves allowed image/scene bytes.
Embedded drawings have a real read-only canvas plus an Open drawing action;
editing occurs in the native-owned drawing document with normal revision checks.

`flush(token)` drains the active editor, then emits `{type:"flushed",id,token}`.
Oversized unsent content or serialization failures withhold that acknowledgement,
so native can retain the editor rather than closing over an unsaved draft.
Content is limited to 2,000,000 UTF-8 bytes. Old generation callbacks, action
replies and unmount cleanup cannot mutate or flush a later document.

## Behavior worth preserving

- Opening, switching view modes and receiving metadata never create a save.
- Markdown remains ordinary Markdown, including GFM tables and nested tasks.
- Existing complete Excalidraw elements preserve bindings; only AI skeleton
  elements go through the skeleton converter.
- Canvas zoom, pan and initial fitting never save. Element edits are emitted
  immediately; native owns the debounce.
- Mermaid conversion produces a separate Excalidraw document.
- External Markdown images become explicit link actions, without network loads.
- CSP excludes external network, frames, workers, forms and object embeds.
- Both themes and locales are local assets; toolbar controls remain touchable
  and horizontally scrollable on a narrow phone.
