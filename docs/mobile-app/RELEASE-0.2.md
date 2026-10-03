# Mobile 0.2.0 verification

Date: 2026-10-04. Android package `me.waveio.claudebot`, version code 2.
The APK is signed with the same debug identity as 0.1.0 and can update that
installation while retaining its connection and local preferences.

## Changes

- Custom solid model picker and a separate effort page, keyboard-aware bounds,
  real host default selection, and distinct Off versus inherited/default effort.
- Opaque dark user bubbles and themed assistant bubbles; white waiting dots,
  per-bubble copy/share/selection, and inline actual-model disclosure.
- Manrope body/UI typography and a Lora-derived greeting font with retained
  licenses. Solar Linear icons and manufacturer logos remain bundled offline.
- A gesture drawer underneath the foreground conversation, custom attachment
  tiles, an actual installed-skills picker, and a custom date/time schedule.
- Dictation within the composer: actual amplitude controls its border glow and
  bars, partial ASR text is visible, and Stop inserts the editable transcript.
- Genuine mobile gateway answer snapshots before HTTP finalization, echo
  suppression, authoritative final text, failure-aware queues and awaited cleanup.
- Stale job lists cannot clear newer streamed state. A completion racing a
  history load is reconciled, and cancelled dictation ignores late results.

## Verification

| Check | Result |
| --- | --- |
| Shared state and HTTP tests | 85 passed |
| Android native unit tests | 62 passed |
| Android platform instrumentation | 44 passed on API 35 |
| Compose/controller UI scenarios | 7 passed; pairing, picker/effort/keyboard, dictation, files, attachments/skills/actions, scheduling, pre-completion text and drawer motion |
| Stream/backend focused coverage | 152 tests and 32 subtests passed |
| Skills router/integration subset | 60 passed |
| Independent review | State/UI issues fixed; separate streaming review found no high-impact blocker |
| Android build | Built from an immutable committed source snapshot, outside concurrent worktree edits |

UI fixtures are deterministic and do not make paid provider calls or record a
real microphone. Gated ByteChannels and actual loopback HTTP tests prove visible
first output before completion. Native storage/recording/picker contracts run
separately. The registered host was restarted after confirming no active mobile
jobs; the new skills route is loaded and the public HTTPS API remains reachable.

## Rendering measurements

The motion test paces Compose frames against real wall time. On the same API 35
Pixel 8 emulator with Apple M2 Pro host graphics, 176 recorded frames in the final
candidate measured median 17 ms and p95 20 ms. The 0.1.0 baseline measured 155
frames, median 17 ms and p95 20 ms. An intermediate moving-backdrop implementation
had p95 68 ms; replacing coordinate-tracked blur with cached local layers and
reusing stepped transition effects removed that regression in this test.
These are debug emulator measurements, not physical-phone or release-build guarantees.
The earlier software-renderer/auto-advanced-clock sample is not a valid comparison.

## Broader suite and release boundaries

A separate sanitized backend run did not complete: 1,058 passed, 1 failed,
7 skipped, 55 deselected and 142 subtests passed before an idle run was interrupted.
The single failure was an invalid-token test expecting a configured Clerk issuer;
with a synthetic test issuer, that exact test passed in 1.37 seconds with zero
network attempts. No source fix was required. The full suite is not counted as
passing, and the waiting test could not be identified from the interrupted report.

Full Xcode is still unavailable, so iOS source/project updates have not been
compiled or exercised on an Apple device. Remote APNs/FCM push and a strict
current-task Steer API remain unavailable. The Agents destination remains the
owner-requested future placeholder. No production model call is counted as part
of the streaming verification above.
