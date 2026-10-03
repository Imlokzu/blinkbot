# Claude Bot mobile: product and design specification

## Source of truth

- Status: confirmed product requirements; visual details still need a preview.
- Updated: 2026-10-03, after the product interview, saved survey, and final clarifications.
- Platforms: a new Kotlin Multiplatform application for Android and iOS.
- Screen inventory and review points: [SCREEN_PLAN.md](SCREEN_PLAN.md).
- This document governs the new mobile client. The root design contract governs
  the small bot screen; the dashboard keeps its own contract.
- The older Expo client and Android wrapper are not this implementation.
- Private interview answers remain in the owner's notes. This document contains
  product requirements, not raw transcripts, account data, or credentials.

The owner requested a specification and screen plan before architecture or
application code. This documentation does not select implementation libraries,
claim that required backend capabilities already exist, or approve unshown
visual choices.

## Product goals and boundaries

Provide a personal, uncluttered phone interface to the **same existing backend**
as the current PC/web app. Conversations, messages, the agent workspace, and
server-owned bot settings belong to that backend, not a second mobile service.

All application API traffic uses the owner's API hostname through Cloudflare
Tunnel to the host. `api-bot.waveio.me` was supplied as an example; its final
deployment and DNS status are not verified. Model providers are accessed by
the backend, not directly by the mobile client. Model branding and the default
wallpaper should be available locally rather than fetched by every screen.

The primary user is the owner moving between a phone and the PC, including
interrupted sessions, unreliable connectivity, and private notifications.
ChatGPT-style chat usability is the starting point. The mobile identity comes
from the existing mascot, messenger-like replies, wallpaper, and motion.

In scope: chat, shared history/search, model and effort selection, tool activity,
attachments, dictation, workspace viewing/text editing, profile/settings,
notifications, queueing, steering, stopping, and scheduled messages.

Explicit non-goals for this version:

- Agent creation or management: the Agents destination is reserved and empty.
- Live voice conversation or spoken assistant replies: voice input is ASR only.
- Biometric application locking.
- Rebuilding the PC/web application, migrating the older mobile clients, or
  exposing every developer/admin panel as a new mobile screen.

Necessary changes to the shared backend or PC pairing surface must be scoped
against these requirements, preserving existing clients and contracts.

## Brand, visual language, and accessibility

- Clean, spacious surfaces following the owner's two desktop references.
- Progressive blur and smooth blur/reveal transitions are part of the direction.
- Use the existing mascot; do not invent a new bot personality on the client.
- Model identities have their real logos and colors. Distinguish the model maker
  from the backend provider that serves it.
- The reference project's principal icon family is Solar Icons Linear by
  480 Design, supplemented by its own glyphs. Preserve asset attribution.
- Light and dark themes follow the phone by default, with a manual override.
- The exact typeface, spacing, radii, accent treatment, and motion timings are
  not settled by the interview. Present them in the visual preview.
- The owner delegated wallpaper blur/dimming strength and transition tuning.
- Haptics are wanted for interactions and while an answer appears. Their cadence
  needs a preview; this is not permission to vibrate continuously per token.

Quality requirements include readable content over wallpaper, system text size,
accessible control names, reachable touch targets, keyboard/screen-reader support,
and respect for reduced motion/transparency and system haptic preferences.
Gestures supplement reachable controls and must not steal text selection,
editor input, table scrolling, or platform back navigation.

## Navigation and lifecycle

- Chat's persistent top row is **Menu / centered model control / New chat**.
- The side menu exposes Chats/history, Search, Agents, Files, and Profile.
- Settings and other secondary destinations are reached through Profile.
- Quick access from menu/profile resolves to the same underlying destinations.
- The open menu occupies approximately **65% of the viewport width**; the
  remaining chat is visible and dimmed. Exact drag/translation behavior needs
  a preview; do not substitute an unrelated full-screen menu.
- Returning after ordinary backgrounding restores the current chat or section.
- A fresh launch after explicitly closing the app opens New chat. Saved history,
  pending messages, and unsaved local recovery data are not deleted.
- Platform process reclamation must be considered separately from intentional
  closure when defining restoration; it cannot be used as a reason to lose data.

The New chat surface uses prepared greeting text, not synthetic history messages.
The owner requested phrases changing every two seconds and subsequently clarified
that they are a greeting. Preserve this as the current direction in the preview;
do not turn it into a stream of greeting bubbles or assistant notifications.

## Chat presentation and actions

- Short conversational thoughts arrive as separate messenger-like bubbles.
- An animated typing indicator gives way to a text bubble; if more is coming,
  the next indicator appears beneath it. Use genuine task state.
- Long explanations must remain readable and structurally coherent, including
  paragraphs, code, lists, and tables. Exact long-response framing is a preview
  item; splitting every paragraph mechanically is not an approved algorithm.
- During tool execution, show a compact branching activity tree with a vertical
  trunk, connectors, icons, and actual action names.
- At completion, replace the expanded activity with a factual short report and
  a details control that reopens the tree. Completion is not proof every tool
  succeeded; failed or interrupted actions retain their real outcomes.
- Long-press message actions include Copy, Select text, Edit own message,
  Regenerate assistant reply, and Share. Editing/version retention must follow
  a verified backend contract, not an unapproved destructive rewrite.
- Attachments include photo library, camera, and documents.
- Reading older content is never interrupted by forced scrolling. Show a
  control to reach new messages while an answer continues below.

## Model and effort selection

- The centered top control opens a compact panel immediately below itself.
- The panel contains model selection with branding and a separate effort control.
- Every conversation retains its own model/effort and permits changes.
- A global **new-chat** preference selects a fixed default model or the last
  model used globally. Changing it does not rewrite existing conversations.
- Supported effort values come from the actual model/backend capabilities.
  Initial effort inheritance and the unset preference state need verification.

If the selected model is unavailable, disclose the problem and automatically
route to **another model of the same provider**. Display the actual answering
model; do not falsely retain the failed model's identity on the response.
Provider membership must come from backend metadata, not matching brand logos.
Automatic cross-provider substitution is outside the authorized rule; ask before
such a change. Retry/fallback must not create duplicate turns or repeat completed
tool side effects. Candidate ordering is a backend feasibility question.

## Queue, Steer, Stop, and Send later

| Action | Confirmed meaning |
| --- | --- |
| Send while a reply is active | Queue by default; visibly identify the pending message. |
| Queue | Wait for the active work to complete normally before executing pending work. |
| Steer | Supply an update to the current task rather than starting a separate queued task. |
| Stop | Stop the current task. It is not a shortcut that starts the next queued message. |
| Send later | Select a specific date and time for future submission. |

Long-pressing Send exposes Steer, Queue, and Send later. A Stop control is available
while work is active. The exact arrangement that keeps submission and Stop
reachable with a nonempty draft must be resolved in the composer preview.
After Stop, retain queued items and require an explicit action to continue them;
do not discard them or start one as an implicit consequence of stopping.

Show scheduled messages as confirmed only after the backend accepts the schedule.
Make the chosen date/time and time zone unambiguous. A phone-only timer is not
evidence of a durable schedule. Resuming, changing, cancelling, or listing queued
and scheduled work requires real backend support; corresponding controls must
not imply capabilities that have not been implemented.

## Offline delivery and interruption

- Retain the exact submitted message and attachments after a connection failure.
- If the user has pressed Send and subsequently closes/backgrounds the app,
  deliver when the application is able to do so.
- If the app remains open, ask whether to send when the connection returns.
- An explicit refusal to queue is not undone by later closing the app.
- Distinguish draft, pending delivery, server-accepted, running, completed,
  failed, scheduled, and stopped states. A lost acknowledgement is not proof
  that the backend never received a message.
- Reconnection and process restoration must not duplicate messages or move
  delayed results into a different chat. Cancellation acknowledges real server
  state; do not claim a backend task stopped solely because the UI closed.

On iOS, force-quitting prevents normal background relaunch until the next manual
launch. The owner was informed that immediate background delivery cannot be
guaranteed. Preserve the queue and retry at an allowed opportunity; distinguish
this unsent state from a schedule already accepted by the host.

## Dictation

- ASR only; microphone activation opens a dedicated animated dictation popup.
- A line responds to actual voice activity and the recognized text is visible.
- Recording ends with an explicit button, not automatically after silence.
- Stopping puts recognized text into the composer. The user can edit and send it.
- Desired incremental transcription must be checked against backend support.
  Do not simulate a live transcript with invented or prerecorded content.
- Permission denial, cancellation, ASR failure, and interrupted recording need
  usable recovery without replacing an existing draft silently.

## Files, profile, and settings

Files use the existing agent workspace. Provide browsing/preview and an editor
for supported text files, including Markdown. Edits **save automatically** to
the shared backend. Distinguish saving, saved, offline/pending, and failed writes;
retain recovery data through navigation. Concurrent edits from the PC or agent
must not be silently overwritten by a stale phone buffer.

Profile hosts genuine settings from the current web product, adapted to mobile.
Required categories include appearance, new-chat model defaults, language,
dictation, haptics, notifications, connection, and relevant server-owned bot
personalization. Exact grouping and additional secondary tabs need a preview;
the interview did not approve every administrative panel.

Appearance preferences are **per device**, including wallpaper. The supplied
image is the built-in starting image, with half-screen coverage by default.
Offer half/full-screen coverage and all/chosen-screen application, custom images,
and useful controls for blur/dimming. The initial enabled screen set is not
specified; the preview should make its proposed default explicit.

Notifications are opt-in after asking the user. Support both private and content
preview modes; the default reveals only that the bot replied. No biometric app
lock is required. Notification content must respect the selected privacy mode.

## Connection and QR pairing

The mobile client authenticates to the existing host using a token obtained
through the PC app. The primary transfer method is a QR composed of dots in
the visual shape of the existing mascot. Actual scanning on both platforms is
an acceptance condition; a decorative unscannable result is not sufficient.

Manual token entry was suggested but not separately confirmed. Pairing issuance,
expiry, revocation, phone identity, and the PC-side presentation need a concrete
contract. Earlier inspection did not establish an existing mobile-token issuer.
An OpenClaw gateway credential or internal Clerk token UI is not automatically
that contract. Never expose provider credentials as a phone pairing token.

## Localization and responsive behavior

Initially support Ukrainian and English, follow the phone language by default,
and allow manual selection. All visible strings, states, accessibility labels,
greetings, notifications, validation, and plural forms must use localization
resources. Adding another locale must not require rewriting screen logic.

Code identifiers, comments, documentation, and commit messages remain English.
Format dates, numbers, schedules, and accessibility announcements for the active
locale. Account for the keyboard and platform safe areas. Portrait phone UX is
the interview's primary surface; broader device layouts and minimum OS versions
are architecture/validation decisions, not implied by the desktop references.

## Existing evidence and implementation checks

Read-only findings are not claims that the new mobile features already work:

| Area | Evidence / next check |
| --- | --- |
| Current web UX | `Virtual Bot/dashboard/src/panels/chat/` and `panels/settings/`. |
| Shared conversations | `Virtual Bot/chat_store.py`; verify ownership, streaming, and mutation contracts. |
| Workspace | `FilesPanel.tsx` calls existing `/api/workspace/list` and `/api/workspace/file`, including text writes. |
| Bot settings | `Virtual Bot/profile_store.py` and `openclaw_settings.py`; distinguish server preferences from process-only settings. |
| Appearance | Current `appearancePreferences.ts` stores browser-local choices; mobile needs its own device-local persistence. |
| Authentication | Existing Clerk/dashboard auth and launcher inspection did not establish mobile token issuance. |
| Queue/Steer/schedules | Required behavior; complete native-client-facing contracts have not yet been verified. |
| Model defaults/fallback | Verify per-chat state, provider membership, effective-model reporting, and compatibility with web routing. |
| ASR/push | Verify incremental transcription, app background opportunities, and real device delivery. |

Do not reopen settled UX questions to discover code facts. Investigate these
contracts during architecture, identify necessary shared-backend changes, and
return to the owner only for material product tradeoffs.

## Acceptance checklist

- [ ] Phone and PC open the same server conversation and workspace content.
- [ ] App API calls use the configured tunnel hostname; pairing works on Android and iOS.
- [ ] Header, anchored model popup, 65% menu, themes, and wallpaper match the approved preview.
- [ ] Existing chats retain their model/effort when the new-chat default changes.
- [ ] Same-provider fallback is disclosed and the actual answering model is shown.
- [ ] Messenger bubbles and tool-tree summaries preserve real content and state.
- [ ] Queue, Steer, Stop, and scheduled send exhibit their distinct documented behavior.
- [ ] Stop does not automatically launch queued work; reconnect never duplicates a submitted turn.
- [ ] Voice activity drives the dictation line; manual stop inserts text into the composer.
- [ ] All attachment entry points work; message actions operate on the correct message.
- [ ] Workspace edits autosave, preserve failure recovery, and handle concurrent changes.
- [ ] Resume restores the prior surface; explicit fresh launch starts New chat without deleting data.
- [ ] Notification consent and private-by-default content work on real devices.
- [ ] Both locales, system text size, reduced motion, and software keyboards are usable.
- [ ] Agents remains an intentionally empty future destination.

## Visual decisions still to show

The owner delegated wallpaper tuning, not unrestricted design decisions. Before
implementing those choices, show the exact typography/spacing, drawer motion,
long-answer treatment, greeting presentation, busy composer/queue controls,
profile grouping, and default wallpaper screen scope in a compact preview.
These are visual review items, not another broad questionnaire.

## References

- [Owner's reference post](https://x.com/winglee/status/2105712328272675109)
- [Exact supplied wallpaper](https://pbs.twimg.com/media/HTkKqtcakAAF3P7?format=jpg&name=large)
- [Reference icon source](https://github.com/zeronsh/zeron/blob/main/crates/ui/src/icons.rs)
- [Apple background execution limits](https://developer.apple.com/forums/thread/685525)
- [Android background scheduling guidance](https://developer.android.com/develop/background-work/background-tasks/persistent)

## Owner refinement, 2026-10-04

The owner authorized replacing stock Material controls with a custom interface.
Keep Solar Linear and manufacturer logos. Use the web client's warm surface and
ink colors, solid dark user bubbles and distinct opaque bot bubbles. Bundle
Manrope for UI/body text and retain an italic Lora greeting. Picker panels are
solid themed surfaces; motion blur is transient rather than persistent frosting.

Prioritize the centered model popup. A selected model has a separate effort
page/control with only its actual supported levels. Keep selection/change easy.
Attachments use three large Camera / Photos / Files tiles, then genuine Skills
and workspace/scheduling actions. Long-press actions use the same custom panels.
Dictation lives inside the composer, with a voice-reactive edge glow, live
transcript and explicit stop/cancel; its result remains an editable draft.

The menu is behind the foreground chat: opening it moves the chat right,
revealing approximately 65% of the menu. Gestures, direct controls, keyboard
focus, safe areas and reduced motion must remain usable. Use short spring/reveal
motion and brief blur on opening surfaces; do not run expensive idle effects.

Remove stock snackbars and reply-time banners. Model fallback remains disclosed
inline with the actual answering model. Typing uses a compact white dot bubble
without a repeated writing label. Replies must stream as backend events arrive;
an animation of a fully completed response does not count as streaming.
