# Blink mobile: screen plan

This is the screen/flow inventory derived from [DESIGN.md](DESIGN.md). It records
product requirements, not a Kotlin module layout or an approved pixel mockup.
Version 0.2.0 follows the owner’s 2026-10-04 refinement and verified Android previews.

## Primary destinations

| ID | Destination | Content and entry |
| --- | --- | --- |
| S01 | Connect to bot | First use or reauthentication; scan the PC-issued mascot QR and show actual connection state. |
| S02 | New chat | Persistent Menu / Model / New chat row, prepared greeting, wallpaper, composer. Fresh launch destination after an explicit close. |
| S03 | Conversation | Shared server history, messenger replies, tool trees/reports, attachments, active work and pending messages. |
| S04 | Menu and history | Approximately 65% width, revealed beneath the foreground chat as it slides right; the remaining chat is dimmed. Chats, Search, Agents, Files, Profile. |
| S05 | Search | Search the same conversation collection as PC and open the selected conversation. Search depth/pagination depend on the verified API. |
| S06 | Files | Existing agent workspace, folder navigation, file selection and previews. |
| S07 | File preview/editor | View supported files; edit text/Markdown with autosave and visible write state. |
| S08 | Agents | Empty destination reserved for later; no simulated agents or management controls. |
| S09 | Profile | Entry point for settings and secondary sections, with the same destinations reached by any quick links. |

## Settings reached through Profile

These are functional groups, not a decision to create one tab for every row.
Keep the exact grouping in the preview rather than copying the desktop sidebar.

| Group | Required content |
| --- | --- |
| Appearance | System/light/dark, wallpaper, half/full coverage, scope by screen, blur/dimming; device-local. |
| New-chat defaults | Fixed model or last globally used model; never overwrite existing chat preferences. |
| Language | System default with Ukrainian/English override; extensible locale resources. |
| Input and feedback | ASR-related controls and haptics, including answer appearance feedback. |
| Notifications | User consent, private notice by default, optional reply preview. |
| Connection | Actual backend connection and authorization state; reauthentication/recovery. |
| Bot preferences | Relevant genuine server-backed personalization/settings; no separate mobile bot identity. |

Additional secondary tabs, pairing/device management, and memory navigation
should be represented in the preview only where supported by the agreed scope
and verified backend. Admin services/logs are not assumed as new mobile features.

## Contextual surfaces

| ID | Surface | Required behavior |
| --- | --- | --- |
| P01 | Model / effort | Solid themed panel anchored below the top-center model control; model selection opens its separate effort page. |
| P02 | Dictation | Inline composer with voice-responsive edge glow and live transcript; manual Stop commits recognized text to the draft. |
| P03 | Attachments | Large Camera / Photos / Files tiles, followed by genuine skills, workspace and scheduling actions; preserve the active draft. |
| P04 | Message actions | Long press: copy, select text, edit own message, regenerate, share. |
| P05 | Tool details | Completed short report opens the real branching action tree, with correct failure/stop outcomes. |
| P06 | Send mode | Long press Send: Steer, Queue, Send later. Keep Stop reachable during active work. |
| P07 | Schedule | Select date/time, show time zone and backend acknowledgement; retain unsent state if delivery fails. |
| P08 | Pending work | Visible queued/scheduled items. Exact placement and management affordances belong in the preview. |
| P09 | Offline delivery question | When the app remains open after a failed Send, ask whether to send when possible; preserve explicit refusal. |
| P10 | Permission/recovery | Camera/mic/notifications, expired authorization, inaccessible files and unavailable services; preserve user input. |

## Flow: connect and start

1. Open S01 when the phone has no valid app access.
2. Scan the mascot QR displayed by the existing PC application.
3. Verify the real host/session before indicating that connection succeeded.
4. Open S02 with the applicable new-chat model preference.
5. First submitted message transitions into S03 and the shared server history.

No provider credential entry is part of this flow. Manual token entry was only
proposed and is not an approved alternative yet. The exact QR/token contract is
an architecture prerequisite.

## Flow: conversation and active work

1. The typing indicator animates from the current response position.
2. A short thought appears as a bubble; further thoughts continue below it.
3. Tool work uses the branching tree rather than one card per call.
4. Completed work becomes a compact factual report with a details action.
5. New messages submitted during active work are visibly queued by default.
6. Steer updates the active task; Stop only stops it and leaves pending work
   retained for explicit continuation. Normal completion can advance the queue.
7. Long-press Send can schedule a new message for a chosen date and time.

Scrolling up preserves the reading position. The new-message control returns
the reader to the current content without requiring a gesture-only action.

## Flow: dictation and files

- Composer microphone -> P02 -> manual stop -> recognized text in the composer
  -> optional editing -> ordinary Send. No automatic send or spoken reply.
- Menu Files -> S06 -> S07 -> edit -> automatic shared-backend save. Navigation
  cannot silently discard a pending write; failed/offline saves remain visible
  and recoverable. Conflicting PC/agent changes require safe reconciliation.

## Flow: leave, resume, and delayed delivery

- Ordinary background/resume restores the prior chat or section.
- Explicit full close/fresh launch opens New chat and preserves prior history.
- Submitted work can still belong to an earlier chat after a new screen opens;
  late results must never be inserted into the newly selected conversation.
- Offline submitted messages survive closure and retry when execution is
  permitted. A foreground user is asked about queueing; explicit refusal wins.
- A host-accepted schedule is distinct from a locally pending submission.
- After notification consent, an answer notification is private by default.
  Its exact navigation/deep-link behavior should be verified with the same
  backend conversation identity.

## Preview set before UI implementation

Show a small, coherent set that resolves the remaining visual choices:

1. New chat and populated chat in both themes, with the supplied half-screen sky.
2. The 65% menu and the anchored model/effort panel.
3. A live tool tree, completed report, and active composer with queue/Stop/Steer.
4. Dictation with keyboard return, scheduling, and the file editor's save state.
5. Profile/settings grouping and appearance controls.

Each preview must identify any proposed detail not previously selected by the
owner. Do not treat a desktop reference, old native app, or library default as
approval of an otherwise unspecified mobile design.

## Next work, in order

1. Review this requirements/screen inventory for contradictions with the interview.
2. Audit shared-backend contracts and platform constraints; record gaps and scoped additions.
3. Define architecture using the owner's stack decisions and current official documentation.
4. Present the compact visual preview for remaining design choices.
5. Implement the approved flows, with real backend integration and platform checks.

The discovery helper website is not the mobile UI prototype and does not set
the application's typography, layout, architecture, or storage design.
