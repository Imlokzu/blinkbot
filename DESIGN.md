# Design

## Source of truth
- Status: Active
- Last refreshed: 2026-09-30
- Primary product surfaces: `/screen`, its native apps and local app store.
- The dashboard has its own contract in `Virtual Bot/dashboard/DESIGN.md`.
- Evidence reviewed: `AGENTS.md`, `HANDOFF.md`,
  `Virtual Bot/docs/SCREEN-PLATFORM.md`, `static/screen/screen.js`,
  `screen.css`, `deep.css`, `drawer.js`, `app-icons.js`, `app-kit.css`,
  `app-kit.js`, existing packages and screen tests (paths under Virtual Bot).

## Brand
- Personality: a quiet, friendly desk companion with a distinctive crab face.
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
- Keep the face and configurable tile carousel as home.
- Keep the watch drawer and its remembered honeycomb/list views.
- Add Guide as an always-available native drawer app, before system tools.
- Guide overview: eight activity groups, each with a short useful description.
- Activity details: purpose, two or three relevant actions, connection badges.
- An explicit tap installs a missing local package and opens it. Back returns
  to the guide. Failed requests stay on the guide with a retryable message.
- Checklists and Converter remain installable local store apps.

## Design principles
- One understandable action per row; explain why before asking for a tap.
- Reveal details on demand instead of squeezing everything onto one screen.
- Preserve user input and completion state; do not silently overwrite it.
- Tradeoff: vertical scrolling is preferable to smaller type or touch targets.

## Visual language
- Color: existing resolved screen tokens, including custom skins.
- Typography: the screen's existing font, 11-14 px body and labels; numbers
  use tabular figures. No external fonts.
- Spacing/layout: 6 px minimum between adjacent controls, 24 px minimum
  targets, two-column overview, single-column details and task rows.
- Shape/elevation: reuse existing native surfaces and rounded controls.
- Motion: transform and opacity only; respect reduced motion.
- Icons: one bold glyph per gradient disc in Deep UI, re-inked tonally in
  Material You. New app IDs have their own designs in app-icons.js.

## Components
- Reuse native app layer, screen navigation, on-screen keyboard and app kit.
- Add guide overview/detail rows; reuse existing tokens and icon helpers.
- App kit owns store app colours, insets, theme, language and style.
- Screen CSS owns Guide; no parallel design system or new dependency.

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
