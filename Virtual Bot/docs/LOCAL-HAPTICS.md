# Local Mac trackpad feedback

The effort slider uses native AppKit alignment ticks when the dashboard and
backend run directly on the same Mac. The web Vibration API does not provide
Mac trackpad feedback, so the browser requests a fixed native action through
the local backend. No extension, new Python package or compiled helper is
required: macOS ships the JavaScript for Automation runtime used by the bridge.

## Availability and limits

- A top-level desktop Mac browser must open the dashboard using localhost,
  127.0.0.1 or ::1. Non-loopback URLs and requests marked as forwarded are
  excluded. An explicitly configured localhost port forward is not detectable
  as a different client machine; the bridge is intended for the host Mac.
- The backend must run on macOS with /usr/bin/osascript available. Physical
  delivery depends on a supported Force Touch trackpad and system settings.
- Capability detection is silent. An accepted request means the AppKit call
  completed, not that hardware delivery was measured.
- Only trusted user input crossing an effort stop requests a tick. Opening the
  picker, server updates, unchanged clicks and cancellation do not request one.
- Both client and server limit requests to one per 100ms and one in flight.
  Extra detents are dropped rather than queued. Request/auth lookup is bounded
  to 1.5s; native execution has a 1s limit. Failures never interrupt the setting.

## API

GET /api/local/haptics returns:

```json
{"available": true, "kind": "macos-trackpad"}
```

On unsupported hosts, available is false and kind is null. It does not produce
feedback. POST /api/local/haptics/tick returns {"accepted": true} or a quiet
false on unsupported hardware bridges, throttling or process failure. It accepts
no action name, script, pattern or other user-controlled execution input.

Both routes require the existing operator authorization and direct loopback
peer/Host with no forwarded headers. POST additionally requires an exact
same-origin Origin header and X-Blink-Haptic: 1. Opaque and cross-site origins
are rejected. Capability and tick responses are marked no-store.

The process runs only this public AppKit action with fixed arguments:
NSHapticFeedbackManager.defaultPerformer, NSHapticFeedbackPatternAlignment,
and NSHapticFeedbackPerformanceTimeNow. Stdout/stderr are discarded; timeouts
kill and reap the helper. No private trackpad API is used.

## Motion and validation

The effort value briefly settles into place. During dragging or saving, a
highlight sweeps across the orange fill; the pill has a small press response
and halo. Only visual children and pseudo-elements animate transform/opacity;
slider hit geometry stays unchanged. Idle controls do not animate, and
prefers-reduced-motion removes decorative animation in all states.

Automated tests mock the native performer and both browser endpoints, so tests
never produce physical ticks. Backend coverage includes origin/auth/proxy
boundaries, unsupported platforms, throttling, concurrency and process cleanup.
Browser coverage includes trusted input, silent passive/synthetic updates,
failed/unavailable bridges, request deadlines and reduced motion.

Sources: [AppKit haptic feedback](https://developer.apple.com/documentation/appkit/nshapticfeedbackmanager),
[web Vibration API implementation report](https://w3c.github.io/vibration/reports/implementation.html).
