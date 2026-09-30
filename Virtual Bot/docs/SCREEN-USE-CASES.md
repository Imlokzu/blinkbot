# Useful things to do with the bot screen

Open `/screen`, swipe up, and choose **Guide**. It groups working capabilities
by activity, explains their purpose, and opens the right screen. A missing
local app has a **Get & open** action; this installs it from the bot's local
catalogue. Nothing starts playing or sending a chat message just by opening
an activity.

## Working scenarios

| Context | Practical use | Guide activity and tools |
|---|---|---|
| Morning at home | Prepare a short task list, check the forecast before leaving, compare local and city time | Start the day: Checklists, Forecast, Clock |
| Work or study desk | Choose a priority, work in focus sessions, check off what was finished | Focus: Pomodoro, Checklists |
| Kitchen | Name separate cooking timers, convert weights and volumes, scale recipe quantities | In the kitchen: Timers, Converter, Calculator |
| A short pause | Follow a quiet breathing rhythm or play a quick reaction game | Take a break: Breathe, Reaction |
| Music practice or making art | Sketch pixel art or practise a tempo with a metronome | Make something: Pixel studio, Metronome |
| Listening and watching | Find a track or video, then use the island for playback controls | Music and video: YT Music, Video |
| Learning and remembering | Ask for an explanation or browse saved notes | Learn something: Conversation, Memory |
| Maintaining the bot | See which connections work, adjust the display, manage vision/display services | My bot: Status, Settings, Services |

The guide offers destinations, not bundled automations. For example, the
kitchen activity opens the timer editor; it does not create a timer until
the user chooses a duration. The focus activity opens Pomodoro; it does not
start a session on its own.

## New local utilities

### Daily checklist

- Add, edit, complete and delete your own tasks.
- Add morning, work or evening starter tasks to the existing list.
- A repeated starter adds no duplicates; custom tasks and edited starter
  tasks stay in place.
- **Reset done** clears completion without deleting tasks.
- Saved tasks and completion survive closing, reopening and reloading the app
  when browser storage is available.
- This browser stores up to 24 tasks, with titles up to 80 characters.
- If browser storage is blocked or full, the app explains that state and
  continues working in memory. Clearing browser data removes saved tasks.
- This is a manual checklist, not an automatically scheduled habit tracker.

### Unit converter

- Convert length, mass, temperature, volume and speed using local formulas.
- Choose source and destination units, enter a number, and swap the result
  back into the input for the reverse conversion.
- Use negative temperatures and decimal points or commas.
- Category, units and valid input values survive reopening when browser
  storage is available. If storage is blocked or full, new changes last only
  until the app closes.
- Gallons and fluid ounces are explicitly **US** units. Weight ounces and
  fluid ounces belong to different categories.
- No currency rates, remote lookups, account or API key are needed.
- Readout precision is limited for the small display. Values outside the
  supported numeric range show an error instead of a misleading result.

## What the connection badges mean

| Badge | Meaning |
|---|---|
| No internet needed | The loaded app works locally. Opening or installing an app still needs the bot server for its catalogue and files. |
| Needs the bot server | The feature reads or writes state on the local bot server; this does not itself require internet. |
| Needs internet | Forecasts and streamed media need their external service. |

Conversation requires a configured brain for useful answers. The demo mode is
not a replacement for a connected model. Memory and shared timers use existing
server state; Checklists and Converter use this browser's local storage and
are not synced with the bot's brain or other devices.

Back from a store app, Settings, Memory or Services opened by Guide returns
to the selected Guide activity. Forecast, Timers, Conversation and Status
are carousel tiles and use their normal navigation; open Guide again from
the drawer after using a tile. Swipe from an app's
outer edge to go back; swipe up from the bottom pill to go home. The new apps
keep these gesture strips clear. With a desktop keyboard, Escape goes home
and Backspace goes back in the parent screen. Keys pressed inside an app
iframe follow that app's own behaviour; Converter uses Backspace to delete
a digit or close its picker.

## Product use cases

These are ways to apply the features above, not additional integrations:

- **Personal desk companion:** combine a task list, focused work and music
  without opening a second full-size computer interface.
- **Study station:** keep a short learning checklist, practise unit conversion,
  and use timed work sessions. A teacher can prepare example tasks manually.
- **Maker or music corner:** keep a preparation checklist, check measurements,
  sketch a small design or practise a rhythm with local tools.
- **Kitchen display:** use labelled timers and measurement conversion while
  keeping the main computer elsewhere. No recipe service is required.
- **Demo or workshop device:** show eight actual activities through Guide;
  choose the local tools when internet is unreliable. These apps store state
  per browser and do not provide shared user accounts or team task syncing.

## Ideas for a later iteration

The items below are proposals, not shipped controls. Each should keep the
same 320x240, offline, i18n and theme contracts.

| Idea | Why it helps | Concrete completion criterion |
|---|---|---|
| Pin favourite apps | The first drawer ring becomes personal | Pin/unpin without drag-and-drop; survive reload; removing an app removes its pin |
| A small capture inbox | Save a thought before it interrupts work | Add/edit an offline note; optional explicit save to bot memory; never lose drafts on connection failure |
| Focus in the island | See a Pomodoro session after leaving its app | One shared state source, accurate deadline, pause/resume from the island; no competing countdown |
| Reminder editor | Create reminders without memorising a voice command | Reuse the existing reminder API; show due time, edit/cancel and failed-save feedback |
| First-use navigation practice | Make gestures and keyboard easier to discover | A skippable local walkthrough using real controls; remember completion; respect reduced motion |
| Optional checklist sync | Use the same list on phone and bot | Explicit user choice, clear conflict handling and offline retry; keep browser-only lists private by default |

## Implementation and verification

Design decisions live in the repository's `DESIGN.md`. Activity definitions
and catalogue checks are in `static/screen/guide.js`. Native utility sources
live in `store/packages/daily-checklist/` and `store/packages/unit-converter/`.
Behaviour tests are `tests/test_screen_guide.py`, `test_screen_checklist.py`
and `test_screen_converter.py`; the shared package and icon contracts apply
to them too.
