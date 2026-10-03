# DESIGN.md — Claude Bot Dashboard

Every person or agent touching `dashboard/` reads this file. It describes
_why_ the dashboard looks the way it does. If a change contradicts something here — either the change
is wrong, or the file is outdated; there is no third option.

## What is this thing

Not an "AI product". It is an **instrument**: a control panel for a home robot, which stays
open for hours on a second monitor and which is reached for from the phone on the couch.
Therefore: readability is more important than flashiness, state is more important than decoration,
nothing blinks without a reason.

Character — **warm earth + terminal**. A lab technician's field notebook, not a
neon panel from a movie. The bot is a pixelated crab; the dashboard doesn't have to be
pixelated, but it must share its bloodline: warm, handcrafted, slightly technical.

## Five rules

1. **Earth, not glass.** Depth is built by contrasting surfaces and a 1px border.
   No `backdrop-filter` as a base, no large blurry shadows, no
   "glass" cards. One shadow is allowed — soft ambient under a pop-up layer.
   Exception — experimental liquid glass in chat (the plate under the input field and the circle
   "to latest") and, separately, popups: their glass is disabled until "Glass"
   is selected in "Appearance". The standard fill remains. The recipe and the ban
   on spreading it further until the owner says the look is fine, reside
   in `LIQUID-GLASS.md`.
2. **Monospaced = data.** Numbers, statuses, paths, IDs, logs, model names,
   hotkeys — `--font-mono`. Prose, labels, and buttons — `--font-sans`.
   This is not taste, this is navigation: the font shows where the fact is and where the interface is.
3. **One accent per screen.** The accent colour marks what is currently the main thing,
   and nothing more. If there are two accent spots — one of them is wrong. States
   (working / sleeping / error) have their own scale and do not belong to the accent.
4. **Movement is small and springy.** `spring`, not `ease`. 120–260 ms. Movement explains
   where the element came from, or confirms a press — and nothing more.
   `prefers-reduced-motion` disables everything except opacity changes.
5. **Emptiness is a material.** Generous margins, narrow line measure (62–70 characters
   in chat). When there is a lack of space — we cut the content, not the air.

## Forbidden

- Gradient fills as backgrounds for panels and buttons (accent gradient — only in the
  brand mark and the progress indicator). Owner-approved chat wallpapers are a
  stationary background layer, separate from panels and controls.
- Emojis as icons. Icons — Lucide, 1.75 stroke width.
- Shadows deeper than `--shadow-pop`.
- More than two radius sizes in a single composite element.
- A spinner where a skeleton or real progress can be shown.
- Hardcoded interface text. New surfaces use English and Ukrainian locale keys;
  labels follow the language selected in Settings.

## Typography

**IBM Plex Sans Variable** (text) + **IBM Plex Mono** (data). Both are
self-hosted via `@fontsource`, with Cyrillic — the dashboard must work without
a network. Plex was not chosen from scratch: the project's landing page is already on Plex Mono.

| Role | Font | Size / tracking |
|---|---|---|
| Screen header | Sans 600 | 22–26px, `-0.02em` |
| Section label | **Mono 500, UPPERCASE** | 11px, `+0.12em` |
| Text | Sans 400 | 14–15px, height 1.55 |
| Chat reply | Sans 400 | 15px, height 1.62, width ≤ 68ch |
| Data / log | Mono 400 | 12–13px |

Section label in monospaced small caps is a signature trick of the dashboard. It gives
an "instrumental" feel without a single image.

Ukrainian text is run through `glue()` (`src/lib/glue.ts`): short
prepositions and conjunctions (`у в з і й та на до не що як`), numbers with units and
the last pair of words in a paragraph are stitched with a non-breaking space. The idea is from Typehug from
"UI things"; the package itself only has rules for English and Polish, so
Ukrainian rules are custom-written.

## Colour

Two themes — **Light Desert** and **Dark Graphite** — and four accents
(terracotta / sage / ocean / amber). The `localStorage` keys are the same as in the old
dashboard (`claudeBotTheme`, `claudeBotAccent`), so that settings survive the migration.

The bases are warm: dark is not blue-black, but brown-black; light is sand, not
white paper. Each accent has two tones: a light variant in the dark, otherwise it
burns out.

The state scale is separate from the accent: `ok` (working), `warn` (attention), `err` (failed),
`idle` (sleeping). They are the same in all accents — the state should not depend on taste.

## Where ready-made pieces were taken from

From the "UI things" note:

- **React Bits · Micro** (`src/vendor/reactbits/`) — `VoicePill`, `ThoughtLine`,
  `HoldButton`, `StatusMark`, `SloshGauge`, `RubberSegment`, `SquishSwitch`,
  `SwipeToast`, `WarmTooltip`, `LatticeLoader`, `SlideCommit`, `ScrubField`,
  `SwipeRow`, `PromptBar`. Icons switched from `@hugeicons` to Lucide
  (`_icons.jsx`), the rest of the code is mostly untouched — updated from upstream with one
  `curl`.
- **bencho.dev** (`src/vendor/bencho/`) — radial menu on the "+" in chat (MIT).
  Open, select and confirm — one gesture: press, move towards the
  desired item, release. The code of their repository is closed, so it was assembled based on
  the API published on the site and CSS techniques (polar layout via
  `--i`/`--n`, trigonometry right in CSS).
- **voice-glow** — glow under the input field that reacts to the voice. Enabled ONLY
  while the microphone is listening (`active`, `idle=0`): constant "breathing" under the field
  would turn a recording indicator into an ornament.
- **metal-fx** — liquid metal on the name in the header. The only place where such an effect
  is appropriate: a logo is meant to be looked at, not read. Without
  WebGL there remains `ShinyText` — the same inscription without a shader.
- **Torph** (`torph/react`) — text morphing where a string changes in place:
  what the bot is busy with, model name, track name.
- **Typehug** — the idea of non-breaking spaces (custom implementation, see above).
- **Colorion Toggles** — the source of the shape for pure CSS toggles.
- **aicss.dev** (`src/vendor/aicss/`) — four "agentic" pieces from the shadcn registry:
  `ThinkingReasoning` (the «Думаю…» (Thinking...) block that collapses into «Думав N с» (Thought N s)),
  `Orb` (25 action indicators — one for each kind of work), `FileDiff`
  (card of changes in a file) and `DataTable` (table from the response).

  Unlike React Bits these files are COPIED into the project (that's how
  `npx shadcn add` works) and are intended for editing — they all arrived as demonstrations
  with hardcoded content, so here they accept data via props. What exactly was changed —
  is written in the header of each file.

  The content of these components is a crucial point. `ThinkingReasoning` in
  the original shows fake sentences "about the train of thought"; our models do not return a chain
  of reasoning (SSE only has `delta` and `tool_*`), so the block shows
  REAL actions — every tool call with its argument. When the bot just
  answered, only the duration remains. We did not start inventing reasoning that wasn't there.

The ready-made chat is **@assistant-ui/react**: headless primitives (stream, tool-calls,
attachments, branches) without an imposed look, so the design visible here is ours, not
the library's.

## Navigation

The dock floats over the content and can be moved: press and hold — it sticks to
the nearest edge (bottom, top, left, right). The choice lives in
`localStorage.claudeBotDockSide` alongside the theme and accent — it is the same
personal habit.

Dock clearance follows `data-dock` on `<html>`. In chat, only the conversation
column reserves bottom clearance; both sidebars extend to the window edge.
Vertical navigation occupies a continuous 72px surface with a dividing rule.

The vertical variant is not a rotation via CSS: an element rotated
returns a rotated bounding box from `getBoundingClientRect`, and scaling under the cursor
stops following it. Therefore, the axis became a parameter of the component itself
(see the header of `Dock.jsx`).

## Layout

Three breakpoints: `< 760px` phone (bottom navigation, one column, drawers
instead of side panels), `760–1180px` tablet, `> 1180px` desk (side rail
of sections + two-three columns). iOS safe zones via `env(safe-area-inset-*)` —
the dashboard is installed on the home screen as a PWA.

## Chat workspace (2026-09-20)

- Preserve the existing warm palette, Plex typography, and thin surface borders.
- Desktop chat's right rail offers opt-in Projects, Vision, and Screen pins from
  a bottom-anchored plus menu. Selection and order persist locally. Pins remain a desktop column;
  narrow layouts reach them through the "+" sheet as a dialog.
- The screen pin embeds the real same-origin `/screen` at its native 320x240 size.
  Removing a pin unmounts its iframe/stream. Pinning Vision does not start a camera.
- Standalone Markdown images in one reply share the existing React Bits accordion,
  even with prose between them. Captions and all prose remain; inline illustrations,
  links, tables, and code examples are not regrouped.
- Radial-menu taps toggle it; dragging selects once; Escape and outside clicks close
  it. Keyboard activation must retain trigger focus, not focus the composer.
- New labels use `src/locales/workspace.ts` (Ukrainian and English).
- Checks: `npm test`, `npm run typecheck`, `npm run build`; optional
  `npm run test:browser` uses an installed agent-browser and a running server with
  isolated browser-only fixtures, never real chat writes. Tests require Node 22.6+.

## Narrow chat (2026-09-23)

Below the desk breakpoint the chat is reorganised around the thumb, after the
owner's sketch:

- **Header:** conversations list | model name as the title | new conversation.
  The title opens `ModelMenu`: the model list and the thinking level as a row
  of stops. The compact face is gone from this header — at this width it was
  an ornament competing with the model name.
- **Prompt bar:** only what you type with — "+", the field, mic, send. The
  model and thinking pickers moved to the header; they squeezed the field to
  a few words.
- **"+" sheet** (`AttachSheet`), opening in place above the bar: camera,
  photos and files as thumb-sized tiles, then context, tools and panels as
  rows. The context meter moved here from under the bar.
- Escape peels one layer at a time; a tap inside the context popover does not
  count as a tap outside the sheet.
- The desktop layout is unchanged.

## Model picker (2026-09-24)

One `ModelMenu` for every layout — the phone chat header's title, and on the
desk the slot in the prompt bar where the vendor pickers were (PromptBar
edit 6, `modelSlot`). The catalog grew to ~60 models with the same model
often listed under three hosts, and a plain dropdown in catalog order meant
scrolling past all of it.

- **Maker logos** from lobe-icons (`src/vendor/lobe-icons/`, MIT), copied
  rather than installed: ~15 of the package's 950 icons. Owner-approved maker
  colours distinguish chat models in both themes; optional monochrome remains
  available to other surfaces. Brand identity is separate from the action accent.
  The maker comes from the model
  part of the id, never the host (`regolo/gpt-oss-120b` is OpenAI's). Unknown
  makers get a neutral mark rather than a guessed logo.
- **Search** matches the start of any word, in any order, ignoring the
  catalog's punctuation ("gpt6", "qwen 3.8", "regolo qwen", "xai").
- **Grouping**: always by maker, with sticky headings. The owner removed
  the catalog sorting row; old saved sort values no longer affect this menu.
- **Recent**: the last three picks lead the list while nothing is typed.
- The host is shown under each name, so the copies of one model can be told
  apart.
- One button opens the combined picker: models on the left, thinking on the
  right. Sorting and capability bars, host/trait badges and the comparison
  footer are removed. Model rows keep their maker icon, name, selection and
  unavailable feedback. Search starts as a magnifier and expands on hover,
  focus or tap; Escape clears/collapses search before closing the popup.
- Thinking choices come from the gateway's reported levels; clearing the
  setting uses the gateway default, independently of "off". Both columns
  retain serialized, acknowledged writes and localized errors. Recents move
  the chosen row without duplicating checked radios or stealing effort focus.


## Chat personalization (2026-10-03)

- New conversations place one mounted composer in the centre, with a quiet
  heading and suggested prompts. The first accepted message moves it to the
  bottom using a position-only spring; drafts, attachments and microphone
  state survive. Restored history uses the normal conversation layout.
- Settings → Appearance owns customization. The chat toolbar has no palette
  button. Options include an original generated cloud wallpaper, dusk,
  forest, plain, an uploaded image or a muted looping video; chat accent;
  glass or solid material, opacity and blur. No server upload is involved.
- Uploaded PNG, JPEG and WebP images are resized and re-encoded locally.
  Unsupported files, oversized images and unavailable storage leave the
  previous saved choice intact with localized feedback.
- One wallpaper layer serves the whole app or selected chat, navigation,
  conversation list, right panels and other pages. Uploaded MP4/WebM blobs
  and poster frames live in IndexedDB; localStorage retains only a stable
  ID. The sole muted player pauses for a hidden tab, reduced motion, data
  saving or routes outside selected areas. Settings previews use stills.
- Composer glass uses the existing Hyalite lens and rim with a clear centre:
  zero fill and zero blur by default. Former stock frosting migrates once,
  using a recipe version so subsequent custom opacity/blur choices survive.
  Chromium follows the blur control; other browsers use a blur fallback.
  Reduced transparency and unsupported backdrop filters use readable fills.
- Popup glass is selected independently in Settings → Appearance for models,
  attachments, context details and other menus. The shared local preference
  overrides the legacy all-or-none flag, including an explicit empty choice.
  Only selected surfaces carry one lens; other surfaces remain solid.
- Compact desktop controls retain 44px targets on touch screens. Microphone
  colour indicates its purpose and recording state; maker logos use their own
  theme-aware colours without changing the actual selected provider/model.
- Navigation and icon actions use unfenced glyphs with hover colour and
  keyboard focus. The right rail can close and reopen without changing pins;
  its visibility persists independently of the conversations list.
- Regression coverage: `tests/chatAppearance.test.mjs`,
  `tests/brandColors.test.mjs`, `tests/popupGlass.test.mjs`,
  `tests/chat-appearance.browser.mjs`, `tests/model-effort.browser.mjs` and
  `tests/glass-popup.browser.mjs`. Old model/effort browser entrypoints route
  to the combined fixture suite.

## Clock, chat usage and model accounts pins (2026-09-26)

- **Clock** is the /screen clock tile ported as-is: the same 3x5 pixel digits
  and blinking colon, in the accent colour with a darker drop, like the crab.
- **This chat** (pin id `usage`) shows this chat's real tokens from OpenClaw
  with the cache split, priced by OpenClaw at API rates, plus what the same
  traffic would cost without the cache. When OpenClaw never answered the chat,
  it falls back to the old text estimate and says so.
- **Model accounts** (pin id `openclaw`) shows one main account in full:
  quota windows where the provider reports them (ChatGPT Plus 5-hour and
  weekly), what its 30 days of traffic would cost at API prices, and the
  traffic itself. The account name is a menu for picking the main account
  (`localStorage.claudeBotMainAccount`; unset or disconnected falls back to the
  server's first, quota-reporting accounts first). Every other account OpenClaw
  holds is folded under "Other accounts · N" with the all-accounts total:
  usually only the main one matters. Accounts whose provider sends no token
  counts say that instead of showing a false zero; replies on models missing
  from the price table are counted as unpriced.
- The dollar figure is labelled as what the API would charge; on a
  subscription the real spend is the plan. The numbers were checked against
  the raw transcript and OpenAI's published price list.
- Quota bars use the state scale (`ok` / `warn` / `err` at 70% and 90%), not
  the accent: a limit running out is a state, not emphasis (rule 3).

## Workbench (2026-09-29)

Chat on the left, what the bot is making on the right — the owner's ask.

- **Where the files come from:** the tool steps already saved with every
  reply (`workspace_write` / `workspace_show` / `workspace_delete`, from the
  local brain or as `workspace__…` through OpenClaw's MCP). No store of its
  own, so it survives reloads and works on old chats. Failed writes never
  become a tab. The logic is `src/panels/chat/workFiles.ts` with its test.
- **Desk:** the column replaces the pins column, is dragged wider by its left
  edge (`localStorage.claudeBotWorkbenchWidth`), and opens by itself the first
  time a reply writes a file — unless it was closed during that reply.
  Open/closed persists in `claudeBotWorkbench`. Narrower: a sheet from the
  header button, never restored on load.
- **Follows the bot:** the newest write comes forward; a new revision of a
  file reloads its preview. `workspace_show` lands here instead of the
  floating preview dock while the chat is on screen.
- **Views:** HTML in a sandboxed iframe (scripts, no same-origin), images,
  Markdown through the memory panel's Tiptap view, code in CodeMirror with
  Save, and drawings in **Excalidraw** — `.excalidraw` files (the full format
  or the short skeleton a model can write) autosave on real edits only, and
  `.mmd` Mermaid is drawn as an Excalidraw sketch with "save as a drawing".
- **Weight:** Excalidraw and Mermaid are ~7 MB of lazy chunks in
  `assets/drawing/`, kept out of the PWA precache and cached on first use.
  Fonts are ours (`scripts/copy-excalidraw-fonts.mjs`, no CDN), minus the
  12 MB CJK font.
- Strings: `src/locales/workbench.ts`. Browser check:
  `tests/workbench.browser.mjs` (route fixtures only).

### Editable documents (2026-10-01)

- Markdown opens as an editable Tiptap document, with a compact toolbar using
  existing warm tokens and Plex typography. Tables, task lists and images
  round-trip to Markdown; Source uses the same unsaved draft in CodeMirror.
- Save and Cmd/Ctrl+S write the session file. Pending saves belong to the
  Workbench, so switching tabs and typing during a request cannot erase edits.
- Excalidraw is an inline image reference to a separate `.excalidraw` file.
  Its editor loads on expansion and autosaves; reopening waits for pending
  saves and fresh scene data. Heavy drawing chunks remain lazy.
  Canvas focus and keyboard events stay isolated from the text editor.
- Workspace links in chat open the document inside Workbench, including old
  absolute links within the authenticated workspace. External web links stay
  external. Agent tool descriptions teach relative links and `workspace_show`.
- The header offers Show in Finder when the backend reports macOS support.
  The authenticated endpoint uses existing user/session path guards.
- Regression checks: `tests/document-editor.browser.mjs` and
  `tests/workspaceLinks.test.mjs`, plus the existing Workbench browser check.

### Chat controls and live file creation (2026-10-01)

- A stationary chat toolbar carries the conversation-list toggle, current
  title, New conversation and Workbench toggle. The list can collapse from its
  own header; closing returns keyboard focus to the toolbar toggle.
- The selected conversation is remembered only within the loaded page.
  Returning from Settings restores it; fresh visits and reloads start empty.
  Merely visiting an untouched project cannot erase the last main selection.
- A submission during history restoration waits for that history. The composer
  shows a queued state and retains further typed drafts instead of consuming them.
- Workbench starts closed and automatically opens only for create/update calls
  from the current chat stream, including calls completed in a single chunk.
  Reading/showing and saved history do not open it. Closing stays respected for
  that response; manual opening remains available.
- The writing view uses the existing pixel agent icon and actual file text.
  Active writes never fetch incomplete files; successful text output reveals
  confirmed content before the editor appears. Reduced motion skips the reveal.
- Markdown playback uses the same Tiptap schema and formatting toolbar as human
  editing. A named agent cursor selects changed prose and inserts it into the
  formatted document; untouched paragraphs remain visible. Playback is read-only,
  never saves, never takes composer focus, and keeps its editor mounted while
  waiting for the confirmed file. Missing input retains the last confirmed text.
- Playback's document remains selectable and scrollable. Wheel/touch/navigation
  input stops automatic following, with an explicit return-to-agent control.
  Visual transactions and completion wait while a reader selects text; the real
  write proceeds. Inline drawings open in view mode without saving. Only the
  formatting toolbar is inert during agent ownership.
- Desktop has one conversations toggle in a toolbar spanning chat and the right
  panel. The Workbench toggle stays at the far right in both open/closed states;
  the conversations list has no duplicate close control.
- Agent revisions and manual reloads are separate cache-key fields. Playback
  starts from the most recently confirmed prior version, including a reload
  cached before Workbench was closed. Long documents skip the bounded animation.
- Active write ownership blocks old canvas flushes. Inline serialized saves
  capture the file revision before queuing and verify it before dispatch.
- Checks: `tests/chatNavigation.test.mjs`, `tests/chat-navigation.browser.mjs`,
  `tests/workbench-writing.browser.mjs`, and existing document/workbench checks.
  `tests/agentPlayback.test.mjs` verifies valid rich-document frames;
  `tests/agent-playback-reduced.browser.mjs` exercises native reduced motion.

## OpenClaw inference dashboard (2026-09-30)

- The `#/inference` section shares the warm surfaces, Plex fonts, thin borders
  and the existing theme/accent. Numbers, model ids and timestamps are mono.
- Reporting spans this OpenClaw gateway, with UTC calendar ranges (today, 7,
  30 or 90 days). Provider selection filters metrics, the daily chart and model
  rows; session matching also checks models used after a fallback. Session
  totals retain the full session scope and are labelled accordingly.
- Counts and API estimates come from `sessions.usage`; quota windows come from
  `usage.status`. Missing prices remain unpriced and missing token metadata
  is shown as absent. Subscription costs are explicitly API equivalents, not
  the subscription invoice. Effective per-million rates are averages of the
  recorded priced traffic, not a hardcoded provider catalog.
- Selecting a session opens a keyboard-accessible journal over metadata from
  the current transcript, with actual per-response provider/model attribution.
  Opaque ids replace session keys; prompts, replies, tool arguments, account
  addresses and credentials never appear in the reporting response.
- Session and transcript windows are bounded, with visible limits and actions
  to inspect more history. A partial index stays labelled; an unavailable
  gateway never becomes a zero-cost report.
- Strings: `src/locales/inference.ts` in English and Ukrainian. Regression
  checks: `tests/inference.test.mjs`, `tests/inference.browser.mjs`, and the
  backend `tests/test_openclaw_analytics.py`.

### Usage reference and router comparison (2026-09-30)

- The owner's reference replaces the combined metric strip with four separate
  flat cards: monochrome metric icons, large Plex data, a compact provider list
  with local logos and reported quota bars. Provider details expand on selection.
- The same section offers Usage and Router comparison views. The model picker
  links to comparison without changing the active model. Shared range, provider
  and All/Text/Voice controls scope both views.
- Voice means a turn with voice input or a spoken reply. Only LLM inference is
  counted here; transcription and speech synthesis need their own billing data.
  A private metadata ledger records new turns; historical activity with no
  modality evidence stays in All. Nothing guesses a modality from model names.
- Comparison uses identical measured input/output/cache token quantities for
  observed models, one fixed model and everyday Opus. It does not replay prompts
  or imply equivalent response quality. Opus 5.5 and 4.8 rates come from the
  official Anthropic price table checked on 2026-09-30; cache writes assume 5m.
- Verified Jev turns are a separate comparison population. Their classifier
  overhead uses actual TypeSafe input usage, including paid decisions that
  fall back to keyword rules. Unknown usage stays unknown. Unrecorded historical
  routing is disclosed and never claimed as measured Jev savings.
- New strings stay in `locales/inference.ts` and `locales/comparison.ts`.

## OpenClaw control pages (2026-09-30)

- Extend the existing dock, mobile section drawer and command palette with
  Agents, Sessions, Automation and Channels. A compact local navigation row
  connects these pages; each remains a bookmarkable hash route. Settings links
  select the intended tab through `#/settings?tab=brain`.
  Constrained desktop docks scroll at their resting size, retaining 44px side
  targets and keeping the top dock inside its header slot. Roomy docks preserve
  their existing magnification. Keyboard focus and mouse-wheel scrolling reach
  every section.
- Preserve warm surfaces, self-hosted Plex fonts, thin borders and all accents.
  Use flat metric strips, readable metadata rows and existing Panel/Field/Button
  components. Agent/channel rosters use two columns only on wide screens;
  session/job metadata stacks on phones. No new design library or imagery.
- Agents shows configured model routes and execution defaults, with links to
  that agent's sessions and automation. Defaults are distinct from the actual
  model shown in Usage after a fallback.
- Sessions shows bounded, paginated metadata across configured agents. Search
  only the visible metadata. Opaque ids replace private session keys; no titles,
  conversation previews, ownership details or transcripts enter the response.
  Current and stale context counts remain distinct; absent counts are unknown.
- Automation shows real schedules, scheduler state, outcomes and 20 recent run
  metadata entries. Create daily or interval agent jobs inline, paused, isolated,
  without automatic delivery or failure alerts. Enabling an existing job retains
  its authored task/delivery; changing enabled state uses a revision guard.
- Channels shows configured/running/connected as separate facts. No connection
  is inferred from a running process; unknown connectivity stays explicit.
  Read status without sending messages, probing or starting login flows.
  Channel setup links to the official guide, independent of unfinished local
  integration settings work.
- These gateway-wide pages are operator surfaces. Direct local access follows
  the launcher; authenticated installations explicitly allowlist operators.
  Loading uses skeletons; errors allow refresh, and retained data is marked stale.
- New labels: `src/locales/control.ts` in both languages. Keyboard focus stays
  visible; inline form completion/cancellation restores its trigger. Respect
  existing reduced-motion and touch behavior. Validate phone, tablet, desktop,
  both themes, both languages, mutations through isolated browser mocks, and
  real read-only HTTP endpoints. Never create live schedules for UI testing.


## Send feedback (2026-10-01)

Appearance groups optional visual feedback under Extra effects. The requested
send bubble is a 720ms one-shot exception to the usual micro-motion duration:
the full rendered message flies from the stationary button to its actual place
in the chat. Its copy tracks the destination through auto-scroll, then dissolves
with blur while the readable message remains. Line breaks, dimensions and the
existing message palette stay intact. It adds no background animation and is
disabled by reduced motion or its saved switch. The body overlay is decorative,
inert and never intercepts input.


## Chat sources (2026-10-01)

The + picker overlays the composer without moving its draft. Desktop uses
compact photo/file rows; phone and tablet use camera/photo/file touch targets.
Its height follows the available room above the composer, including a tall draft
or on-screen keyboard. Escape closes one layer and returns to the + trigger.
The picker opens from its bottom-left trigger origin over 240ms; its action rows
follow with short staggered rises. Attachment previews open over 240ms on
desktop and slide 22px over 260ms on phones. Scoped motion animates only opacity
and transform, preserving desktop centering and avoiding label blur. Reduced
motion disables these animations completely. Preview closing takes 130ms and
retains Radix focus restoration.
Connectors, Tools and Panels are secondary rows; touch context details remain
available in the sheet. Hidden file inputs stay mounted through OS selection.

Images and documents have the same preview cards in the draft and conversation.
Image thumbnails and full views fetch owned uploads with bearer headers and
revocable object URLs. Text/PDF/DOCX previews show up to 4,000 characters from
server extraction, with an explicit partial label and original-file download.
Private text remains in component state, never the shared query cache; requests
abort on unmount. External attachment URLs cannot create requests. Preview
dialogs return focus to their cards, and removal stays separate from preview.
The agent receives every filename, validated type and order as escaped reference
metadata, including image names; real document bytes remain its content source.

Connectors lists actual OpenClaw MCP servers and offers NotebookLM notebook/source
selection. Ready source text is imported as a real private document; failed or
unready sources stay disabled. Large extracts are labelled partial.
Settings → Connectors owns profile selection, Google sign-in/check and native
OpenClaw agent access. Configuration/access are separate from verified connection
state; an unchecked or expired login never enables the agent button. Use existing
warm tokens, standard Dialog/Field/Button controls and English/Ukrainian keys.
Checks: `tests/attachmentInfo.test.mjs`, `tests/attachment-previews.browser.mjs`,
existing attachment/connector browsers and backend `tests/test_chat_uploads.py`.
`tests/attachment-motion.browser.mjs` samples actual browser keyframes, viewport
fit and focus on desktop/phone with both motion preferences.
