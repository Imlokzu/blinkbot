# Mobile 0.4.0

Android version code 7, version name 0.4.0. The optimized APK keeps the owner's
existing development signing identity so it can update earlier direct installs.
The iOS project has matching source metadata; no iOS binary is supplied.

## Chat motion and navigation

- The compact glass Latest arrow depends on the rendered tail above the composer,
  not a stale following flag. Trailing content padding does not trigger it.
- Latest animates to the measured end of oversized final messages. Reading drags
  and flings retain control; following resumes only after scrolling settles.
- The drawer measures gestures in stationary coordinates. A short fast gesture
  opens/closes it, while tiny nudges, vertical scrolling and table gestures remain
  independent.
- Sent messages have a distinct entrance and keep their animation identity when
  server IDs arrive. Submission acknowledgment semantics are unchanged.
- A waiting typing bubble appears after 850 ms. Real notes and answer text remain
  immediate; completed output is never replayed as synthetic typing.

## Files and images

- Native photo/document picking accepts up to 10 files and 20 MiB per selection.
  The host retains its own per-file upload limits. Successful files survive a
  failure elsewhere in the batch.
- Uploaded images form a contact sheet, showing up to six images in two rows.
  The full viewer supports paging, Save and Share of the selected original.
  Its system icons follow the viewer surface rather than the phone theme.
- Successful workspace tool results expose real generated files. Active writes
  are labeled but not read; edits invalidate earlier thumbnail revisions.
- Workspace exports use the authenticated original-byte download route. Text,
  image and PDF exports never substitute extracted preview text.
- Encoded thumbnail caching remains capped at 8 MiB per entry and 16 MiB total.
  Large originals get a 512-pixel cache image; visible gallery decoding is bounded
  to 384 pixels. Cache eviction preserves decoded tiles without repeated downloads;
  chat/account navigation resets that view memory through a distinct generation.
- Save supports empty files. Android share files retain temporary read grants
  with a 64-file / 80 MiB / 24-hour bound; chooser handoff does not claim delivery.

## Verification and limits

Validation logs, XML and screenshots accompany the owner build:

- 154 shared unit tests and 78 native Android unit tests passed.
- 36 phone UI scenarios passed, including the real FileProvider contract and a
  valid PNG larger than 8 MiB encoded into a 512 by 384 thumbnail.
- 18 existing adaptive executions passed across compact, 200% font and landscape.
- Four additional gallery/file-viewer executions passed at 200% font and landscape.
- Five host QA-runner tests passed. The gallery fixture now scrolls the actual
  drawer in a short viewport and applies production system-bar appearance.
- Independent reviewers closed the concrete scroll-ownership, cache-eviction,
  export-selection, upload-guard and original-byte delivery findings.

Host validation
includes 34 focused Python tests and an actual loopback TCP smoke test covering
original bytes, authentication, traversal rejection and static assets. The
registered host was restarted only after confirming zero running/stopping jobs;
local capabilities returned 200 and unauthenticated public download returned 401.

Android functional tests use the non-debuggable benchmark variant, because R8
removes APIs required by external Compose instrumentation. The separately built
optimized release is installed and launch-smoked. Real provider calls, physical
phone refresh rate, iOS framework compilation and iOS runtime behavior are not
certified by these fixtures. Native Swift syntax/Foundation checks are narrower
than a full Xcode build. No paid provider requests or production chat messages
were sent by validation.

## Owner artifact

`ClaudeBot-0.4.0-android.apk`: 2,792,894 bytes.
SHA-256: `90e67c88d39545a88fc10338f878b3d5d8d9c667d9d58ae6191d51ed9baa0d9b`.
The optimized release installed over the benchmark build and cold-launched to
its connection screen with an empty crash buffer. A final two-scenario viewer
check and inspected screenshot confirm readable system icons on the light
viewer surface. The 20-scenario chat suite also passed after the adaptive fixture
scroll/system-bar corrections.
