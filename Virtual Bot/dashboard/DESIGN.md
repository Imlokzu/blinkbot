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
  brand mark and the progress indicator).
- Emojis as icons. Icons — Lucide, 1.75 stroke width.
- Shadows deeper than `--shadow-pop`.
- More than two radius sizes in a single composite element.
- A spinner where a skeleton or real progress can be shown.
- Placeholder text in English. The interface is entirely Ukrainian.

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
  rather than installed: ~15 of the package's 950 icons. Monochrome, filled
  with `currentColor` — they take the text colour and follow both themes; a
  column of brand colours would break rule 3. The maker comes from the model
  part of the id, never the host (`regolo/gpt-oss-120b` is OpenAI's). Unknown
  makers get a neutral mark rather than a guessed logo.
- **Search** matches the start of any word, in any order, ignoring the
  catalog's punctuation ("gpt6", "qwen 3.8", "regolo qwen", "xai").
- **Sort**: by maker (grouped, sticky headings), A–Z, or by context window.
  The choice persists in `localStorage.claudeBotModelSort`.
- **Recent**: the last three picks lead the list while nothing is typed.
- The host is shown under each name, so the copies of one model can be told
  apart.


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
