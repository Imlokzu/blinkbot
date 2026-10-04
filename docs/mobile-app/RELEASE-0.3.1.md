# Claude Bot Mobile 0.3.1

Android version code 5. This patch addresses the owner's screenshot of an
oversized greeting bubble and the rectangular clipping above/below the history.

- Assistant bubbles wrap their rendered Markdown content. A short greeting now
  occupies its text width plus the existing padding; longer replies still wrap
  within the chat width. Bold, italic, tables, copy and reactions are retained.
- History extends behind the header and composer. Padding keeps resting messages
  readable, while a content-only alpha gradient softens both scrolling edges.
  It does not paint an opaque rectangle over the wallpaper or add another full
  backdrop blur. The covered panels intercept taps intended for their controls.
- Measured composer height updates the history padding when drafts, attachments
  or dictation expand the panel. Manual reading position and live auto-follow
  retain their existing behavior.

Verification uses the real Compose app/controller with a deterministic host:
14 existing UI scenarios and 11 interaction regressions passed. Two new tests
measure the bubble Surface against its rendered text and verify history/panel
overlap, composer growth and an actual vertical swipe. The existing note-growth
test starts gestures within the newly exposed reading region; its assertions
are unchanged. Actual screenshots were inspected in the dark wallpaper theme.
The existing adaptive matrix also passed all 18 executions across compact,
200% text and landscape configurations.

The streaming sample recorded 126 frames, median 18 ms, p95 23 ms and maximum
34 ms on the API 35 host-GPU emulator. The drawer sample recorded 176 frames,
median 17 ms and p95 22 ms. These are benchmark-variant emulator observations,
not physical-phone refresh-rate guarantees. Release R8 verification is a separate
installation/launch smoke check; Apple runtime verification remains unavailable.

The backend is unchanged by this patch. The earlier shared web-image routing fix
remains deployed and does not depend on this APK update.
