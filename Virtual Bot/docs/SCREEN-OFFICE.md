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
Door leaves open only for a planned passage and close after it. Passing
along the hallway leaves neighboring doors closed. Routes use diagonal
shortcuts that clear the full sprite, walls and furniture; known bed/chair
contacts are allowed only at route endpoints.

The camera follows Claude by default. Plus/minus zoom; the house button
switches between overview and follow. The person button adds a green visitor
labelled “You” in the lounge, and remembers the choice. The target button
opens teleport mode in the overview: tap clear floor, or use arrows and
Enter; Escape cancels. Walls, furniture and door channels are rejected.
The selected position is saved and is restored after temporary wake visits. Chat opens the existing
screen keyboard and returns to the office after sending. Settings, voice,
app drawer, captions, photos and the old carousel remain available.

## What behavior means

- Foreground chat: walk to the study and work at the computer.
- Actual memory retrieval and local memory tools: read at the library cart.
- Actual background consolidation: sort memories in the library.
- Error/offline response: a help cue; emotional tags still control the face.
- Idle: mostly long naps, with longer stops for eating, reading, ball play,
  stretching, plant care and sofa rest. These are ambient actions and do not
  imply that an AI job is running.
- Sleep: a foreground message leaves Claude dozing briefly while your
  visitor approaches the bedside, then a silent visual sound wave precedes
  a startled hop with an exclamation mark. He goes to the current task.
  The visitor returns to the saved home afterward; if normally hidden, it
  appears only for this sequence and return. Background consolidation wakes
  Claude without impersonating a user visit. A fast answer may finish during
  the choreography: backend responses are never held for animation.
- Automatic display sleep starts or preserves a finite nap, allowing routines
  to continue. Explicit Quick Settings Sleep lasts until interaction/work.
  Opening the office keyboard wakes the display while preserving the nap
  until Send. Camera/teleport inspection does not wake the character.

The library's leisure chair and memory cart are separate destinations.
Foreground work takes priority over background consolidation; a surviving
background task becomes visible again when foreground work finishes.

## Furniture actions

The original PixelCrab gains optional contact parameters in manual mode;
classic callers retain their original drawing. `office-actions.js` blends
visual poses without changing navigation or delaying a bot request:

- Settle into the desk chair and alternate the claws on the real keyboard.
- Fold the existing legs into a seated pose and unfold when leaving.
- Sit in the library chair with the native book on the lap and turn pages.
- Sit at the kitchen table, take bites and occasionally lift a cup for a sip.
- Sit on the lounge sofa and use the native wave animation to greet a visitor.
- Lie on the pillow with the lower body covered by the duvet; rise and use
  the existing jump when waking.

Actual memory work stays at the cart and uses different book-sorting motions
from leisure reading. Chair fronts, armrests and bedding are drawn after the
mascot so contact is visible. They remain anchored while Claude leaves.
The camera follows the presented body position to keep a seated or lying
face above captions. Reduced motion snaps contact poses and freezes the
page, bite, sip and greeting cycles. Returning to a hidden view restores the
current pose directly instead of replaying an old entrance.

## Integration

`screen.js` owns the existing SSE connection and calls `mountOffice` in
`office.js`. Its methods are `setActivity`, `setEmotion`, `setSleeping`,
`setVisible`, `setConnected`, `setReducedMotion`, `refreshLanguage`, `destroy`.
`office-state.js` owns motion and interruption; `office-world.js` owns the
floor plan and drawing; `office-i18n.js` owns English/Ukrainian labels.
`office-host.css` integrates captions and classic-screen chrome.

`office-navigation.js` supplies bounded visibility-graph routes and door
intent. `office-visitor.js` owns placement, approach and return; teleport
preferences never contain chat data. The floor plan is the same for both
actors. The ground shadow uses 18% opacity (8% while hopping), with no blur.

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
  tests/test_office_mascot.py tests/test_office_actions.py \
  tests/test_office_navigation.py tests/test_office_life.py \
  tests/test_office_visitor.py -q
```

Tests cover sleep/wake, short replies, interrupted movement, full-sprite wall
clearance, reduced motion, localization, saved home selection, chat handoff,
activity overlap, cancellation, snapshots and audience isolation. Browser
QA uses synthetic events and chat responses; it does not submit live AI jobs.
