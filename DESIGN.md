# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-10-28
- Primary product surfaces: `/screen`, its native apps and local app store.
- The dashboard has its own contract in `Virtual Bot/dashboard/DESIGN.md`.
- The new Kotlin Multiplatform mobile client has a separate interview-derived
  contract in `docs/mobile-app/DESIGN.md` and `docs/mobile-app/SCREEN_PLAN.md`.
- Evidence reviewed: `AGENTS.md`, `HANDOFF.md`,
  `Virtual Bot/docs/SCREEN-PLATFORM.md`, `static/screen/screen.js`,
  `screen.css`, `deep.css`, `drawer.js`, `app-icons.js`, `app-kit.css`,
  `app-kit.js`, existing packages and screen tests (paths under Virtual Bot).

## Brand
- Personality: a quiet, friendly desk companion with a distinctive crab face.
- Approved product identity: option **01 / Cube + Flash** from the owner's
  2026-10-18 reference, retained at `assets/brand/blink-cube-flash-reference.jpg`.
  The open faceted cube, three diagonal upper-left bands, and separated lower
  planes are the base mark. Keep the lowercase `blink` wordmark separate.
- Master artwork: `assets/brand/blink-master.svg`; generated web and native
  assets share its precise geometry through `scripts/sync_brand_assets.mjs`.
  Approved color treatment: the three upper-left flash bands use orange
  `#E6532F`; cube planes and the separate wordmark remain dark on light
  surfaces or white on dark surfaces. Preserve a monochrome silhouette for
  masks, OS-themed launcher icons, and notification icons.
  Default logo placement: a solid charcoal `#151613` rounded square, with
  a 60/256 corner radius and the orange-and-white cube centered at 82% scale.
  Use `blink-tile.svg` for in-app logos and the rounded icon export. The open
  center is filled by the tile background; only the outer rounded corners
  have transparency. Native installed app icons retain OS-owned masking.
  The fold mark (option 02), earlier spiral, and offset-box proposals are not
  the approved base. The robot's expressive face remains a separate character.
- Trust signals: actual saved state, clear connection requirements, reversible
  actions, useful error messages, and consistent icons across store and drawer.
- Avoid: a marketing page inside a small device, emoji controls, unnecessary
  animation, competing accent colours, and fabricated live information.

## Product goals
- Help someone find a useful activity without knowing an app's name.
- Add everyday offline utilities: editable checklists and unit conversion.
- Explain supported use cases beside actions that open the actual feature.
- Success signals: a guide activity opens the right app; checklist edits survive
  reopening; conversion works offline; both languages and styles remain usable.
- Non-goals: dashboard changes, cloud services, account changes, deployment,
  automatic playback, medical advice, and a replacement navigation system.

## Personas and jobs
- Primary persona: someone using a desk bot through a small touch display.
- Jobs: start the day, plan work, focus, cook, take a break, make something,
  play music, learn, and understand device status.
- Contexts: a 320x240 Raspberry Pi display, desktop simulator, interrupted
  sessions, unreliable internet, and quiet nighttime use.

## Information architecture
- Default home is the living office, with a persistent Office / Classic face
  choice. Keep the existing face tile identity and configurable carousel.
- The office is a bright white top-down floor plan with walls, opening doors,
  a central hallway, bedroom, kitchen, library, computer desk and lounge.
  The camera follows the expressive crab by default; zoom, overview and an
  optional visitor avatar let the owner explore.
- Travel uses full-sprite-safe diagonal shortcuts around furniture; only
  doors on an actor's intended passage open. Ground shadows are translucent.
- The visitor can teleport to validated open floor with a touch or keyboard;
  the chosen home position persists independently of temporary wake visits.
- Idle life is mostly long naps, interspersed with unhurried reading, food,
  ball play, stretching and plant care. Foreground messages can stage a
  visitor approach, silent call wave and surprised hop; reduced motion and
  hidden views skip this choreography. Backend work never waits for it.
- Actual chat/tool/background memory activity drives the office. Idle
  wandering is decorative; it never claims that a memory job is running.
  Sleep-to-work animation must not delay a real response.
- Office details and the Opus 5.5 design live in
  `Virtual Bot/docs/SCREEN-OFFICE.md`.
- Keep the watch drawer and its remembered honeycomb/list views.
- Add Guide as an always-available native drawer app, before system tools.
- Guide overview: eight activity groups, each with a short useful description.
- Activity details: purpose, two or three relevant actions, connection badges.
- An explicit tap installs a missing local package and opens it. Back from a
  store or system app returns to the selected activity. Carousel tiles keep
  their normal navigation. Failed requests stay on the guide with a retryable message.
- Checklists and Converter remain installable local store apps.

## Design principles
- One understandable action per row; explain why before asking for a tap.
- Reveal details on demand instead of squeezing everything onto one screen.
- Preserve user input and completion state; do not silently overwrite it.
- Tradeoff: vertical scrolling is preferable to smaller type or touch targets.

## Visual language
- Color: existing resolved screen tokens, including custom skins.
- Typography: the screen's existing font, 11-14 px main text and labels;
  secondary captions and connection metadata use 10 px. Numbers use
  tabular figures. No external fonts.
- Spacing/layout: 6 px minimum between adjacent controls, 24 px minimum
  targets, two-column overview, single-column details and task rows.
- Shape/elevation: reuse existing native surfaces and rounded controls.
- Motion: transform and opacity only; respect reduced motion.
- Icons: one bold glyph per gradient disc in Deep UI, re-inked tonally in
  Material You. New app IDs have their own designs in app-icons.js.

## Components
- Reuse native app layer, screen navigation, on-screen keyboard and app kit.
- Native scrolling panels fill the stage behind a compact floating header and
  the home indicator. Content padding keeps first/last actions reachable;
  the viewport itself has no top/bottom gutters. Fixed-tab store and iframe
  apps retain their own insets. Optional edge dimming defaults on, persists
  per browser, and resets with screen preferences. Scrims never capture input.
- Store apps inherit the same contract through `app-kit.css` and `app-kit.js`:
  scrolling pages use `kit-edge` with measured top/bottom controls over a
  full-height viewport. Fixed boards/keypads retain compact safe touch insets.
  Each page isolates its stacking context so players and sheets cover it.
  The host passes dimming with resolved theme/style tokens; the host alone
  shades the gesture pill, avoiding a second bottom scrim inside fixed apps.
- Add guide overview/detail rows; reuse existing tokens and icon helpers.
- App kit owns store app colours, insets, theme, language and style.
- Screen CSS owns Guide; no parallel design system or new dependency.
- Reuse the existing PixelCrab artwork and animation engine unchanged in
  identity: no parallel Claude sprite or replacement face design.
- Furniture actions use native body poses plus contact: folded seated legs,
  hands at the real keyboard, a book on the lap and a duvet over the lower
  sleeping body. Front furniture stays anchored when Claude stands up.
  The follow camera keeps the face clear of captions in every pose.
- Office modules own the local canvas world, state machine and localized
  controls. The existing screen owns transport, captions, voice and settings.

## Accessibility
- Target: keyboard operation and legible contrast within the device limits.
- Buttons have translated names, visible focus and disabled/loading states.
- Task completion uses checked semantics; selected views use pressed semantics.
- Announce errors and results without stealing focus or opening browser dialogs.
- Preserve quiet operation; no sound in the new utilities.

## Responsive behavior
- The screen remains a 320x240 scene scaled by the existing simulator.
- Store apps fill the panel and reserve outer 12 px and bottom 16 px for gestures.
- Long localized labels wrap or scroll vertically, never overflow horizontally.
- Touch and keyboard reach the same actions; no hover-only control.

## Interaction states
- Loading: localized feedback; disable duplicate requests.
- Empty: explain how to add a task or retry a missing catalogue.
- Error: localize the failure; keep the activity and user input available.
- Success: update completion/result immediately; retain app state after closing.
- Disabled: retain the action label and explain the loading state nearby.
- Offline: utilities need no internet once loaded. A bot-server badge means
  local server access, not internet access. Weather and streamed media require
  internet; Guide itself uses static local content.

## Content voice
- Short, concrete, friendly, and accurate about what the bot can do.
- All human-facing text uses locale keys in Ukrainian and English.
- Labels name an activity or action; descriptions explain its practical use.
- Future ideas in developer docs are distinct from working capabilities.

## Implementation constraints
- Vanilla ES modules; local store packages use the public app kit API.
- No CDN, external fonts, network dependencies in the new utilities, secrets,
  eval, browser alerts, confirm or prompt.
- Preserve existing API contracts and other ongoing work in the repository.
- Verify real state logic, package contracts, guide links, both themes/styles,
  both languages, back/home gestures, and HTTP/path traversal handling.
- Commit and push each verified logical change using the owner's identity.

## Open questions
- [ ] Owner: should frequently used apps eventually be pinned in the drawer?
  This is a future choice; the present work preserves the existing ordering.
