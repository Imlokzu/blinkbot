# Blink Android 0.7.0

Android version code 34. Published to the stable in-app update channel on
2026-11-05. It ships two features that had not reached the channel: the phone
session recovery prepared as 0.6.0 (see RELEASE-0.6.0.md; that APK was never
published and keeps its own record), and the owner's annotated UI review of
2026-11-05.

## Review changes

- Profile: the host name has an opaque backing over the wallpaper, setting icons
  sit on solid tiles, Haptic feedback is in the main group, and the redundant
  new-chat action is gone from the Profile top bar.
- Appearance: Everywhere explains that it covers every screen, settings
  included, and that dimming and blur follow the wallpaper.
- New-chat model: separated cards, plus only the facts the host reports
  (overall score, context window, reply time, image support). Raw lowercase
  GPT ids such as `gpt-5.5-pro` display as `GPT-5.5 Pro`; ids sent to the host
  are unchanged.
- Notifications: the real state (off, blocked by the phone, ready in the
  background, closed app waits in the chat) replaces the host-service sentence.
- Queue: each item shows its cause from the host job error or the phone outbox
  refusal code, leads with the step that fixes it, and says what Cancel does.
  Cancel on an ended host job used to call stop(), which the host ignores, so the
  row never left; it is now a persisted Clear.
- Haptics: a continuous strength slider that taps at the chosen strength as it
  moves; older Light/Medium/Strong choices map onto it. The agent event switches
  are indented under Agent activity.
- Effort: Automatic (no level sent, the host default applies) is its own mode
  instead of the first slider dot; levels and both ends of the scale are named.
- Rating details: bars now fill the same share as the printed percent (they used
  score ÷ best score); the best score is a tick. One scroll for the whole card.
- Voice input: a rolling waveform of the microphone level, a breathing record
  dot, and fixed slots so the controls no longer shift between states.
- History and files: last-activity dates and a one-line latest-message preview
  (new host field `preview`), and folder change dates from the existing `mtime`.

## Build and publication

Built from committed source `7b6d0ef5` in a clean detached worktree, so
unrelated uncommitted edits in the shared checkout are not included.

APK `mobile-app/build/Blink-0.7.0.apk`, 7,734,676 bytes, signed with the same
certificate as 0.4.18–0.5.2 (`dd6356d2…ff89a3`).

SHA-256: `142ea462cc81f1a92898a173b17d019c09057c9badb842c4d10a42d4a02353f5`.

Published with `scripts/mobile_publish.sh --restart --public` (no running mobile
jobs). Live metadata reports 0.7.0 / 34 on stable; the local download route
serves bytes matching the checksum; the public download route requires a device
token (401 without one). After the restart the host returns `preview` for all
listed chats.

## Verification

- 358 shared and 78 Android unit tests pass in the clean worktree; 87 focused
  backend tests (chat list, sessions, mobile API) pass.
- Emulator (Pixel 8 AVD): every changed screen was inspected in English and
  Ukrainian through a temporary screenshot test against a mock host, including a
  simulated voice level. The optimized release APK cold-starts without crashes.
- An independent adversarial review found eight issues; the material ones were
  fixed before release (steer retry, a dismissed-job cap, live notification
  permission, desktop test selectors, haptic pulse length, label and type nits).
- **Not verified:** haptic strength and voice with real hardware, pairing of the
  release build, iOS, and desktop Compose tests (`:shared:desktopTest` does not
  compile on this Mac because of unresolved JCEF references). The full backend
  suite exceeded 25 minutes and was not completed; the live Regolo ASR test fails
  on the expired trial.
- Known limit: the host advertises the same effort levels for every model, so
  the slider cannot narrow them per model.
