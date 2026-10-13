# Interactive answers in Blink

Desktop and web chat can now display generated calculators, diagrams,
comparisons and small tools inside a reply. Try the new-chat suggestion
**Build an interactive bill splitter**, or ask for a visual explanation with
controls. The selected model decides when to call `render_interactive_ui`.
Plain replies and existing question, choice and checklist tools still work.

This adapts the interactive-answer pattern from
[OpenIntelligentUI](https://github.com/CopilotKit/OpenIntelligentUI) to Blink's
existing assistant-ui, FastAPI and OpenClaw transport. It does not install a
second CopilotKit/LangGraph chat runtime, change providers, or require Jev.
The reference's streaming custom UI architecture is described in its
[documentation](https://github.com/CopilotKit/OpenIntelligentUI/blob/main/docs/generative-ui.md).

## Behavior

- The model supplies a title, accessible text summary, HTML body fragment,
  CSS and plain JavaScript. SVG, canvas and standard form controls work;
  external libraries and assets are not part of this environment.
- A loading state appears during the tool call. The complete validated
  artifact becomes interactive after generation; partial source is not run.
- The frame inherits Blink's colors and responds to theme changes without
  discarding local inputs. It has a bounded height and internal scrolling.
- Reset restores generated defaults. Pause unmounts the frame; Open starts it
  again. Input state is temporary, while the generated artifact is saved.
- `BlinkUI.requestFollowUp(text)` proposes an editable draft outside the
  generated view. Only the host's Send button submits it to that conversation.
  Busy or rejected sends preserve the draft.
- Ukrainian and English labels, keyboard focus and phone layouts use the
  dashboard's existing conventions. Native mobile has no new renderer yet.

## Data and routing

`intelligent_ui_schema.py` owns one schema used by `tools/registry.py` and
`tools_mcp.py`. The handler writes an immutable-by-convention JSON artifact to
`sessions/<session>/interactive/<random-id>.json` in the owner's workspace.
Existing workspace path and symlink guards apply. The aggregate model input
is capped at 64,000 Unicode code points.

The tool result contains a small versioned receipt: title, summary, artifact
path, SHA-256 digest and initial height. This receipt survives the normal
8,000-character activity limit, MCP wrappers, SSE and saved chat history.
The authenticated workspace API serves the source. The renderer verifies the
digest and schema before execution; changed, missing or corrupt artifacts
keep their readable summary and offer retry. `@noble/hashes` supplies SHA-256
on both secure origins and the existing LAN HTTP dashboard.

No separate endpoint, API key, migration or model service is required. Restart
the backend and refresh the OpenClaw tools MCP process/catalog after updating
Python source, then rebuild the dashboard with its guarded build script.
An existing OpenClaw process may cache its tool list until refreshed.

## Browser boundary and limits

Generated code runs in an opaque `allow-scripts` iframe inside another opaque
frame whose code is owned by Blink. Neither frame gets same-origin access,
host storage, auth tokens, popups, forms, top navigation or direct host API
callbacks. The host validates the message source, channel and payload; a
generated message never directly submits a chat turn. CSP blocks HTTP fetches,
external subresources and frame navigation. No browser dependency is downloaded
by the generated view.

This is **not a complete network or resource sandbox**. In the tested Chromium
152 browser, WebRTC can still send STUN packets despite CSP. The proposed
WebRTC CSP directive and iframe connection-allowlist attribute were unsupported.
The source limit and startup timeout do not impose CPU, memory or message-rate
quotas. Do not use this renderer as a boundary for hostile third-party programs
or claim complete network isolation. The model is instructed to generate
self-contained, local controls. Complete network containment would require a
separate execution architecture or supported browser-level enforcement.

## Verification

Backend coverage includes schema parity, real tool/workspace routes, traversal,
owner and session separation, symlink rejection, large source persistence,
failed wrappers and streamed-to-saved receipt replay. Frontend checks cover
receipt validation, checksums, bridge bounds, script escaping and LAN hashing.

The real Thread browser fixture exercises calculator inputs, editable follow-ups,
busy retention, reset, pause, reopening, corrupted source/retry, session changes,
locales, themes, narrow layout and blocked frame navigation:

```sh
cd "Virtual Bot/dashboard"
npm run dev -- --host 127.0.0.1 --port 5319 --strictPort
# In another terminal, after muting the Mac:
node tests/intelligent-ui.browser.mjs
```

These checks use synthetic content and no paid provider. They establish the
integration and execution contract, not the selected model's output quality.
