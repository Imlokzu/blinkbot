# Blink 0.4.15: motion and interaction feedback

The emulator initially had 0.4.12 installed. GitHub main and the local checkout
both contained Blink 0.4.14; that build was installed and exercised before this
follow-up. Android 0.4.15 uses version code 22 and preserves the application ID
and existing signing identity so it updates installed copies.

## Changes

- Clickable controls share subtle press and release feedback, including rows,
  icon actions, attachment/send controls, and settings. Keyboard focus remains
  visible. Cancelling a gesture restores the control without activating it.
- Panels enter in 180 ms and exit in 100 ms; new bubbles arrive in 220 ms with
  less scaling and travel. Brief blur belongs to panels, dialogs and message
  arrivals; settled content stays sharp. Full-page navigation avoids an extra
  entrance animation and full-screen blur.
- Composer height changes ease into place while preserving the text field,
  keyboard, selection and draft. Choice colors and the model chevron animate.
- The model picker keeps its list position through effort/back navigation and
  retains its visible page while closing. Exiting panels and pages reject
  further touch, keyboard and accessibility actions.
- Outgoing settings pages and the chat header retain their own screen state
  during navigation. Notices retain their text through their exit animation.
- New-chat default models expose radio selection state and disable unavailable
  choices. Settings links use forward chevrons.
- Reduced motion removes spatial/size/blur transitions and preserves immediate
  static press and focus feedback.

## Validation

The dedicated `MobileMotionTest` covers press pixels, gesture cancellation,
reduced motion, interrupted navigation, stale page input, picker scroll/exit,
and unavailable model defaults. Existing controller/UI fixtures exercise the
real Compose app with synthetic host responses and silent native services.

All nine motion scenarios, 19 main UI scenarios and 20 chat regressions passed
across the final focused runs. Six additional compact-phone cases passed at
720x1280, density 320 and 160% font size; display/font overrides were restored.
Screenshots were inspected for the chat, dark appearance, model picker and
Ukrainian attachment menu. Native tests used API 35 on the Pixel 8 emulator.

Earlier runs exposed two genuine interrupted-navigation bugs, fixed and covered
by the new motion tests. They also had timing-sensitive native UI failures:
the streamed-table Stop check and Ukrainian pairing flow passed on retry. The
paired model-picker-to-composer flow gained assertions for accepted draft,
preserved draft and exactly one submission; it then passed four consecutive
runs. The exact cause of its original timeout is unproven. No assertion was
removed or timeout increased to obtain those passes.

Build and unit verification:

```sh
cd mobile-app
./gradlew --max-workers=2 --no-parallel \
  :shared:testDebugUnitTest :androidApp:testDebugUnitTest \
  -PuiTestBuildType=benchmark \
  :androidApp:assembleBenchmark :androidApp:assembleBenchmarkAndroidTest \
  :androidApp:assembleRelease
```

All 278 shared and 78 Android unit tests passed. The full backend run had 1,669
passes, six skips, 178 passing subtests and one unrelated process-cleanup timing
failure; all 18 tests in that module passed on retry. Isolated live HTTP smoke
passed 28 expectations, including static assets and traversal rejection with
HTTP 400, and verified that its temporary server stopped.

An independent adversarial review corrected input handling on outgoing pages
and reduced-motion popup transforms. Fable was unavailable; the review used
the supported session model. iOS compilation/runtime, physical-phone frame
rates, real providers, and real microphone recognition are outside this run.

The optimized APK is `mobile-app/build/Blink-0.4.15.apk`. The release variant
is installed and launch-checked separately from the instrumentation variant.

One existing visual follow-up remains: action-tree captions can lose contrast
over bright wallpaper. This release preserves that layout; it does not claim a
complete visual redesign of every surface.
