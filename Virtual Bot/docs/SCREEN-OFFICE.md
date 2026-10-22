# Living office home screen

The default `/screen` home is a bright white office seen from above. Choose
**Office** or **Classic face** in Settings → Appearance → Home screen. The
classic face also has an Office shortcut. The choice stays in this browser.

Claude Opus 5.5 supplied the visual direction through mirasim. The owner's
revision specifies a top-down, single-floor office with walls and doors.
The main agent implemented it; Fable 5.1 reviewed the integration at maximum
effort. The office reuses the existing `PixelCrab` animations from `static/crab.js`.
`office-mascot.js` adapts the same eyes, legs, reading/work props, particles
and squash/air/landing jump; it does not redraw the character. Palette and
audio level come from the existing screen mascot. A manual frame mode lets
the office drive that renderer at 24 fps while pausing the covered classic
canvas. The office uses local canvas artwork, without a build step, CDN,
external font, or extra rendering dependency.

## Layout and controls

The 640×440 world has a bedroom, library and study above a central hallway,
with a kitchen and lounge below. White walls, warm gray floor tiles, pale
wood and sparse sage/blue-gray furniture keep the coral crab easy to see.
Doorways are 56 world units wide, giving the full 54-unit claw span clearance.
Door leaves open when Claude approaches and close after he passes.

The camera follows Claude by default. Plus/minus zoom; the house button
switches between overview and follow. The person button adds a green visitor
labelled “You” in the lounge, and remembers the choice. Chat opens the existing
screen keyboard and returns to the office after sending. Settings, voice,
app drawer, captions, photos and the old carousel remain available.

## What behavior means

- Foreground chat: walk to the study and work at the computer.
- Actual memory retrieval and local memory tools: read at the library cart.
- Actual background consolidation: sort memories in the library.
- Error/offline response: a help cue; emotional tags still control the face.
- Idle: visit the kitchen, lounge or leisure reading chair. This is ambient
  animation and does not imply an AI job is running.
- Sleep: walk to bed. A message triggers a short sleepy beat and a hop, then
  a walk through the bedroom door to the computer. The backend never waits
  for this animation. A quick answer can finish before the walk ends.

The library's leisure chair and memory cart are separate destinations.
Foreground work takes priority over background consolidation; a surviving
background task becomes visible again when foreground work finishes.

## Integration

`screen.js` owns the existing SSE connection and calls `mountOffice` in
`office.js`. Its methods are `setActivity`, `setEmotion`, `setSleeping`,
`setVisible`, `setConnected`, `setReducedMotion`, `refreshLanguage`, `destroy`.
`office-state.js` owns motion and interruption; `office-world.js` owns the
floor plan and drawing; `office-i18n.js` owns English/Ukrainian labels.
`office-host.css` integrates captions and classic-screen chrome.

The server event is deliberately content-free:

```json
{"type":"office_activity","activity":"work","active":true}
```

Activities are `idle`, `work`, `read`, `organize`, `chat`, `help`.
`office_activity.py` tracks live scopes with per-audience snapshots. Hooks
cover `brains.chat`, local tool execution, `/api/tools/call`, and dream-cycle
consolidation. Reconnection and quiet keepalives carry the current snapshot;
a finished error is not retained indefinitely. Private activity is delivered
only to the matching authenticated stream. The tokenless device screen sees
local activity; it does not expose another signed-in user's private work.
Unknown private brain roots remain silent until an authenticated subscription
or chat establishes their audience. External gateway work without local tool
execution is shown as general work, with the existing emotional cues.

## Performance and verification

Static furniture is cached once per language/theme. Visible animation is
capped at 24 fps; inactive screens stop the animation loop. Reduced motion
snaps travel and camera, stops idle wandering and decorative bobbing. The
white office remains white in either surrounding interface theme.

Run from `Virtual Bot` after muting the Mac:

```sh
PYTHONPATH="$PWD" .venv/bin/pytest tests/test_screen_office.py \
  tests/test_office_integration.py tests/test_office_activity.py \
  tests/test_office_mascot.py -q
```

Tests cover sleep/wake, short replies, interrupted movement, full-sprite wall
clearance, reduced motion, localization, saved home selection, chat handoff,
activity overlap, cancellation, snapshots and audience isolation. Browser
QA uses synthetic events and chat responses; it does not submit live AI jobs.
