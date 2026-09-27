# Claude Bot — full project history

*Consolidated document. Combines technical specification, development order, current code state, memory and search architecture, made decisions, and open questions. Written as a continuous narrative, not as a reference manual.*

---

## Part 1. What is this exactly

Claude Bot is a personal AI companion that has a physical body. A camera, a microphone, a speaker, a small screen instead of a face, and later wheels. Inside — the Claude language model as a "personality", a home server as working memory, and a Raspberry Pi as a nervous system that touches the real world.

The idea grew from the video «I Gave ChatGPT a Body» and its main thought: divide the robot into a fast system and a slow one. The fast one is responsible for reflexes — not crashing into a wall, blinking on time, reacting to a sound. The slow one thinks, talks, and plans. In humans, this is called System 1 and System 2, and it turns out that for a robot, this division is just as natural.

The question that immediately arises: why, if there is Alexa and Google Home? The answer lies in three things they do not have. First — physical presence: the bot can turn its head, drive up, look. Second — personal memory about specific people in the house, not a faceless profile. Third — tasks that are impossible without a body: «знайди мене і нагадай» (find me and remind me) requires walking around the apartment, recognizing a face, and making a decision on the spot.

And the fourth, most honest: it's an educational project. Each phase is independently useful, even if the next ones are never done.

---

## Part 2. The main principle — software first, hardware later

The most important decision in the project is not technical, but organizational. The owner has not yet bought a single part. Neither a camera, nor a chassis, nor a screen. And yet, is building the entire brain of the robot on a regular laptop.

It sounds strange, but this is why the project is alive. The classic trap of DIY robotics looks like this: a person orders a box of parts from AliExpress, waits three weeks, solders, connects, and only then finds out that the conversation logic doesn't work and the architecture is flawed. The parts lie around, enthusiasm fades, the project dies.

Here, the order is reversed. First, everything that doesn't depend on hardware is built and tested to a «щоденно користуюсь» (use daily) state. Only then are parts bought — for an already working system, with an understanding of exactly what is needed.

The development order in six steps:

1. Base service with vision — on the laptop webcam
2. Memory — RAG across notes
3. Voice — speech recognition and synthesis
4. Emotions — state layer, for now in the console
5. Cameras — real CSI camera on Pi
6. Interface — screen, face, apps

The first four steps do not require a single purchased part. The fifth and sixth do. That's why they are last.

---

## Part 3. Three tiers of computing

The architecture is divided by the principle: the more often and faster a reaction is needed, the closer to the hardware it is executed.

**Edge — Raspberry Pi 3, under 50 milliseconds.** Sensors, local interface, obstacle-avoidance reflexes. There should be nothing here waiting for the network.

**Fog — home server on an Intel i5, from a hundred to half a second.** Orchestration, scheduler, memory, light machine processing. A brain that is always at home.

**Cloud — Anthropic and Groq, from a third of a second to three.** Language understanding, conversation, image analysis, decision-making.

From this division follows a rule worth repeating out loud: **a clock waiting for an alarm should not depend on the availability of a cloud API at the moment it triggers.** Claude determines the intent — "set an alarm for seven" — and its role ends there. Then the scheduler on the server works, which no longer needs the internet. Claude is an understanding layer, not a real-time executor.

---

## Part 4. What already works

The project ceased to be a document and became code. Now it's six separate modules that run on macOS without a single physical part.

**Vision Agent** — a FastAPI service with OpenCV. It takes a frame, finds faces and motion, returns the result. Works with a laptop webcam.

**Voice Loop** — ears and mouth. Whisper recognizes speech locally, the text goes to the agent, the response is voiced by a synthesizer.

**Display** — the face. FastAPI backend, React frontend, a virtual 320 by 240 pixel screen inside a drawn device frame. Pixel eyes with a dozen and a half emotions, four swipeable screens, a weather widget, a clock, alarms, and streaming text output while the model is talking. All this can already be played with in the browser — and it looks exactly how it will look on a real SPI screen when it arrives.

**Remote Control** — a module for the Raspberry Pi: a listener for a wireless USB remote and a status line on a text I2C screen.

**Device Setup Wizard** — an Electron app called Claude Bot Studio to configure all this.

**Vision plugin** for the agent gateway — so the brain can look into the camera itself.

So out of the six steps of the plan, the first and third are done, and partially the sixth. The main thing is not done: memory and the emotion layer.

---

## Part 5. Memory — the most interesting part

A tempting and flawed idea: keep the entire conversation history and pass it to the model every time. It doesn't work. Context bloats, cost rises, and quality drops because the only thing that matters right now gets lost among thousands of lines.

The chosen approach resembles Obsidian: a structured database of text notes, where every person, topic, and place is a separate file. Memory is divided into three layers with different purposes.

**Hot layer — raw sessions, three days.** This is what answers the question «пам'ятаєш, ми вчора говорили?» (remember we talked yesterday?). Verbatim records, unprocessed.

**Long-term layer — structured notes.** People, topics, places. This is the answer to «що ти про мене знаєш» (what do you know about me).

**Cold layer — journal.** Three to five lines a day, stored forever. This is the answer to «а що було місяць тому» (what happened a month ago).

Three different questions — three different storages. Attempting to get by with one always ends up with it answering all three poorly.

### Nightly consolidation

The most beautiful idea in the project. Every night, when the house is quiet, a separate agent wakes up, rereads the raw sessions from the last days, and decides what is worth keeping in long-term memory. It removes duplicates, summarizes, updates outdated info, adds a line to the journal, and rebuilds the table of contents.

This job doesn't need the smartest model — it needs a cheap and patient one. The choice fell on DeepSeek V4 Flash: fourteen cents per million input tokens, a million-token context, meaning three days of conversations fit in one call. The cost of one night is about half a cent. About two dollars a year.

### Why a cheap model cannot be given an eraser

And here is the most important rule of the entire system. A cheap model hallucinates. If you give it the right to overwrite memory files, one morning you will find that the bot «пам'ятає» (remembers) things that never happened, and establishing when it started will be impossible.

Therefore, the night agent's rights are intentionally truncated. It can append a line. It can replace a line it quoted verbatim. It can create a new file. It cannot overwrite a file entirely. It cannot delete anything. Conflicts — when a new fact contradicts an old one — it does not resolve itself, but extracts into a separate file for human review.

Every line of memory has an origin mark: when it was written and with what confidence. Without this, it's impossible to resolve a contradiction or roll back an error.

And a separate rule that relates not to tech, but to what life will be like next to this thing: the agent records what was said, but does not draw conclusions about people. «Сказала, що втомилась» (She said she was tired) — can be written down. «Схоже, у неї вигорання» (It seems she has burnout) — is forbidden. The difference between a pleasant companion and a creepy overseer lies right here.

### Navigation

To find what's needed in memory, there is a temptation to immediately install a vector database. This is premature. For an apartment with four people, a simple auto-generated table of contents — forty lines where each note is described by one sentence — works better: it's cheaper, more accurate, and most importantly, it can be read with human eyes to understand what the bot thinks of you. Vector search kicks in later when the files exceed a hundred and fifty.

## Part 6. Access to knowledge

A home assistant should answer everyday questions, and for that it needs access to the world: search, encyclopedia, weather, news.

Here the project made a decision worth a separate discussion. Instead of a paid search API, SearXNG is spun up on the server — a metasearch engine that aggregates results from dozens of engines and returns them in machine-readable form. It's free, lives locally, doesn't keep query history, and has no limits.

The temptation to go another route was there. There are open alternatives to Perplexity that perform the search and immediately summarize the result. But here they do not fit for a simple reason: the system already has a model that can summarize, and that is Claude. Passing results through an additional model means paying twice, waiting twice, and losing details twice. Claude must see raw results with numbered sources, not someone else's notes.

Weather is taken from Open-Meteo — free, no key, from national meteorological services data.

And another decision that is easy to miss. In the search tool's description, half the text is dedicated to **when not to search**. Because an assistant with twenty tools starts searching the internet for «скільки буде сім на вісім» (what is seven times eight). Recipes, explanations, translation, advice, math — the model answers these itself, instantly. Search triggers only when today's state of affairs is needed.

## Part 7. Voice, vision, and face

**Voice.** Speech recognition is done locally on the server — this eliminates network latency and per-request fees. Synthesis is the simplest for now, with a plan to replace it with something more pleasant.

**Vision.** Here the difference between two tasks, which are easy to confuse, is important. Face detection answers the question «чи є тут людина» (is there a person here) — it is light and can run directly on the Raspberry Pi so the eyes on the screen look at the interlocutor. Recognition answers the question «чия це людина» (whose person is this) — it is much heavier and runs on the server, only for those frames where something was found.

Recognizing specific people brings a question that is formulated in one line in the technical specification, but weighs more than the rest: a privacy policy is needed. What the bot can retold to an unfamiliar person and what it cannot. A robot that happily tells a guest when the owner returns from work is a problem, not a feature.

**Face and emotions.** A set of states: idle, listening, thinking, speaking, happy, confused. The model returns a state tag along with the response text, and then this tag controls both the pixel eyes on the screen and the color of the LED strip. Notably, this layer is built and verified **before** the screen appears — at first, just a state in the console. Logic separately, visualization later.

## Part 8. Conscious rejections

The most mature part of the project is the list of things decided not to be done.

**There will be no full SLAM.** Building a metric map of an apartment without lidar is a classic pit where amateur projects drown. Instead, reactive navigation is chosen: an ultrasonic rangefinder for reflexes, visual markers across rooms instead of a map, bypassing by a list of rooms. The robot does not know where it is in centimeters. It knows it is currently in the kitchen because it sees the kitchen marker. For an apartment, this is enough.

**There will be no separate motor controller.** Direct control from the Raspberry Pi — one layer less, and for non-real-time critical movement at home, this is sufficient.

**There will be no hardcoding of model names.** Cloud providers change their lineup several times a year and turn off old models with a month's notice. The name lives in the configuration, not in the code. This has already been proven in practice twice in recent months.

**There will be no full conversation archive.** Sessions live for three days.

## Part 9. The honest state of affairs

The project has a practice that deserves a separate mention: every agent that writes code is checked by another agent that intentionally looks for holes in what was written. And finds them.

The last such pass yielded a list of very real problems: the speech synthesizer on macOS silently hangs upon reuse and throws no error, so a try/except wrapper doesn't save it; the image size check is placed after decoding, so a specially crafted small file with a gigantic picture inside can eat almost a gigabyte of memory; in dev mode, React opens two live connections instead of one, and half the messages are lost; two timers share a single variable and interrupt each other.

None of these problems are visible during normal testing. They were all found by someone intentionally looking. This is probably the most useful habit from the entire project — and it carries over to any agent-based development.

## Part 10. What's next

The nearest — three things. Close the found bugs. Make an internal project wiki so the map doesn't live in someone else's head. And build a "virtual bot": a web app that shows a face, chat, vision, memory, and service launch buttons — a complete embodiment before the physical one appears.

Next on the plan — memory and the emotion layer, the two steps that are still skipped.

And only then AliExpress.

## Topics to argue about

*These are not questions with answers — they are tensions built into the project.*

- Is it right to give the personality to a cloud model when everything else is local? What happens to the bot if the provider raises prices or turns off the model?
- A cheap model editing the memory about your family every night — is that elegant savings or a ticking time bomb?
- A robot that recognizes everyone in the house is convenient. But who decides what it can tell a guest? And should there even be a camera in the apartment, even your own?
- "Software first" saved the project from dying while waiting for a package. But doesn't it turn into an endless postponement of the moment when you finally have to solder something?
- Conscious rejection of SLAM — is this engineer maturity or lowering the bar?
- An assistant that remembers more about you than you do yourself — at what point does it stop being a convenience?

## Section N+1 — OpenClaw as the real brain (2026-07-26, take 2)

The owner sharply course-corrected: **do NOT build ANYTHING around OpenClaw**. First, the assistant started cobbling together its own tools.py + agent loop + memory in Python on the omni router — and the owner stopped it: «openclaw has much of support already so no point building around when someone alr did». Decision: OpenClaw is the backend/brain, everything goes through it, Virtual Bot only outputs.

### What was found (and why it was slow before ~35s)
A logging proxy between OpenClaw and the omni router showed the exact reason, and it was NOT what we thought:
- Not cold-start, not the key, not headers, not the `codex` provider.
- **omni router cuts HUGE requests to Claude**: `HTTP 400 «Third-party apps now draw from your extra usage… Add more at claude.ai/settings/usage»`. The Claude account behind omni has $0 "extra usage", so anything over ~10-11K prompt tokens gets rejected.
- The default OpenClaw agent was a **coding profile** (~19K tokens of system prompt) → always 400 on Claude → fell back to the slow `opencode-go/kimi-k3` (~9-14s stream). Even after maximum slimming (minimal profile, no MCP, terse workspace files) the request was ~10.3K tokens — still slightly over Claude's budget.

### What was done (working text prototype)
- **The persona lives in OpenClaw**, not in our prompt (the agent ignores the per-request system_prompt). Rewrote `~/.openclaw/workspace/{IDENTITY,SOUL,USER,AGENTS}.md` for "Claude Bot" in Ukrainian, with the `[емоція:X]` tag rule. Deleted `BOOTSTRAP.md` (that was the one saying «я щойно прокинувся, без імені» (I just woke up, without a name)).
- `tools.profile: coding → minimal`; removed heavy MCPs (playwright/sequential-thinking/youtube).
- Model: `opencode-go/minimax-m3` (fastest working), left Claude at the tail of the fallback chain — it will come alive itself as soon as the budget is topped up.
- Virtual Bot chat switched TO OpenClaw first (omni — fast fallback).

Result: web chat → `mode=openclaw`, ~8s, Claude Bot answers in Ukrainian with a live crab emotion (tag is parsed, face animates "greeting" etc.). Prototype works.

### Open decision for the owner
For Claude to be EXACTLY the brain of OpenClaw (and not minimax) — extra-usage needs to be topped up at claude.ai/settings/usage. Otherwise, we stay on opencode-go (free, but a weaker model — sometimes hallucinates, e.g., «я в демо-режимі» (I'm in demo mode)). Emotions-MCP, skills, and web/fetch/weather-MCP — is the next phase, and logically it waits for this decision (agent tools = larger requests = hitting Claude's budget again).

### Take 2 (2026-07-27): emotions-MCP + skill + live reaction
Continuation the same day. Added EXACTLY as the owner requested — not "around" OpenClaw, but ON its mechanisms:
- **Emotions-MCP** (`Virtual Bot/emotions_mcp.py`): a tiny stdio-MCP server with no dependencies gives the OpenClaw agent the `set_emotion` tool. When the agent calls it during work — the crab animates this activity LIVE (test: in one turn `greeting→searching→thinking→speaking`). Plus a new endpoint `POST /api/emotion` in Virtual Bot. Registered via `openclaw mcp add`. Nuance: the tool is namespaced `emotions__set_emotion`, and the `minimal` profile cuts it without `tools.alsoAllow`. Price: each call ~doubles tokens/latency, so in `SOUL.md` — a rule to call it only for a real multi-step action, while the simple `[емоція:X]` tag remains a cheap always-on mechanism.
- **Skill `prompt-craft`** on the OpenClaw skill system (`Virtual Bot/skills/prompt-craft/SKILL.md`, installed `openclaw skills install`): a prompt writing/improvement workshop for our stack. Now the bot can write and fix prompts itself.
- **Provider state:** Claude via omni is completely banned today (`401 upstream banned` — needs re-auth on omni's side, not money). omni is also rate-limiting under load (403 HTML). We hold on with the free `opencode-go/minimax-m3`; Claude in the fallback tail — will revive itself when omni is fixed.

Day's summary: OpenClaw = brain (`mode=openclaw`), "Claude Bot" persona + emotions live inside it (workspace files + emotions-MCP + prompt-craft skill), Virtual Bot only outputs. Ready text prototype with live crab mimicry.

## Section N+2 — Remote for video on screen: bot turns on, controls, cuts ads (2026-09-04)

Owner's request: «дай боту MCP для ютуба — конкретно на віртуальному екрані вмикати відео, стопати, перемотувати вперед/назад, у кінець і т.д., з адблокером, і в налаштуваннях SponsorBlock».

### What was there and what was missing
The `youtube` app in the store already had a player with video (`/api/music/video` → `<video>`), tap seek, and speed. But **the bot didn't control it**: it only had `play_music` (audio in Now Playing) and `listen_to_video` (transcribe). The bot could only describe "fast forward" with words.

### Two different ads (and why it matters)
- **YouTube's own ads** (prerolls, banners) **don't reach us at all**: the video goes as a proxy stream into a plain `<video>`, without the YouTube player. Nothing to block — no one to show it to.
- **Ads glued into the video itself** («цей ролик спонсує…» (this video is sponsored by...)) — only the community database: **SponsorBlock** (`sponsorblock.py`).

The request to SponsorBlock is made **private**: not `videoID`, but the first 4 characters of `sha256(videoID)` — the server returns hundreds of videos with such a prefix, we choose ours locally. Thus, what the person is watching is not visible from the request. A few dozen KB instead of two — a conscious price: the bot stands on someone's desk. Previews are also not pulled from Google (`/api/video/thumb` proxies through the bot).

Dropped at input: `actionType` other than `skip` (can't automatically jump on `mute`/`poi`/`full` — player would skip to the end), segments with negative votes, shorter than a second, overlaps are merged.

### How the command reaches the player
`tool → SSE {"type":"video"} → screen.js → postMessage {type:"botVideo"} → <video> in iframe → POST /api/video/state back`

Three things without which it doesn't work: the parent **opens the app itself** if it's closed; the command waits in `videoPending` until the iframe loads; there is a **return state channel** (SSE — one way, so without it the bot would guess on «а де ми?» (where are we?)). State with a 40s TTL.

### What was added
- `sponsorblock.py`, `video_control.py` (state + settings + command validation), 7 endpoints `/api/video/*`
- Tools: `play_video`, `video_control` (11 actions, Ukrainian synonyms, "2:30" as position), `video_status`, `video_settings`
- **`youtube_mcp.py`** — stdio-MCP (like `emotions_mcp.py`) with the same four tools for OpenClaw: `openclaw mcp add youtube --command python3 --arg .../youtube_mcp.py --env VBOT_URL=…`
- App `youtube` 1.1.0: accepts bot commands, cuts ads, segment marks on the seek bar, ⚙ settings sheet, «−10»/«+30» step buttons, state reporting
- `runtime/video-settings.json` — **single source of truth**: a finger toggle and "turn off ad skip" by voice do the same thing
- 74 tests (`tests/test_video_control.py`, `tests/test_youtube_mcp.py`); full run 308 passed

### What was caught on live verification (browser + real YouTube)
1. **`mute` survived a video change** — the next clip started in silence, and this looked like broken audio, not memory of an old command. Resetting in `openVideo`.
2. **The player reported only when PLAYING.** After a screen reload, the player was paused (autoplay blocked), said nothing about itself — and the bot described the PREVIOUS video as current until the end of TTL. Now the report also goes when opened.
3. **The ⚙ sheet showed outdated toggles** if the bot changed settings by voice while the app was open. We re-read on every open.
4. **Lost two lines when rewriting the package** — `go.addEventListener("click", search)` and Enter: search stopped working. Found by diffing with `git show HEAD:…` (there also appeared a dead `$("list")` — the element was never in the markup).
5. The ⏪/⏩ buttons the browser drew as **colored** emojis — blue stickers on a pixel screen.

### Take 2 (same day): «чому емодзі — наше все?» (why emojis — our everything?)
The owner asked about the look — and the question turned out to be systemic. Verification showed: **no other screen package uses emoji** (metronome, pixel-paint, device-settings — all on inline-SVG), and in `static/screen/icons.js` there already were `play`, `pause`, `next`, `settings`, `music`. That is, youtube fell out of the system style — partly inherited from the first version of the package (⏸ ▶ ♪ ←), partly added by me (⚙ ⏭).

Done:
- Custom `ICONS` dictionary in the package, `viewBox 24×24`, only `stroke: currentColor`. Paths `play`/`pause`/`settings`/`music` are copied **verbatim** from `icons.js` (test `test_icons_match_screen_icon_set` keeps them in sync), the "back" arrow — from `device-settings`, where such a convention has already formed. The dictionary is in the package, not an import of `icons.js`: the package is copied to `installed/` in its entirety and shouldn't break from editing the screen module.
- The live broadcast circle is CSS `::before`, not the `●` symbol (in some fonts it shifts off the baseline).
- The seek step was initially left as **numbers** «−10»/«+30» — the owner rejected this too, and was right twice: a pair of identical-looking buttons doing different things is not informativeness, but a puzzle; and numbers among vector icons also fell out of style. Now a mirrored pair ◀◀/▶▶ (drawn in the style of `icons.js`, because there is no such pair in the set) and a **symmetric 10s step**. Asymmetry was also removed from the backend: was `DEFAULT_FORWARD_S=30`/`DEFAULT_BACK_S=10`, became a single `DEFAULT_STEP_S=10`. The button and voice must be equal — test `test_seek_step_matches_player_buttons` verifies `SEEK_STEP_S` in the package against the backend constant. The bot can still do any amount: «перемотай на хвилину» (fast forward a minute) → `seconds=60`.
- **Light theme.** The parent has long sent `theme` in `botSkin`, but youtube ignored it (like metronome and pixel-paint) — the app remained dark on a light screen. Added `:root[data-theme="light"]` and `dataset.theme`. This was no longer cosmetic: icons on `currentColor` in a light theme would have become light on light.
- Also fixed: **the play icon lied** — with blocked autoplay it showed «пауза» (pause) (meaning «воно грає» (it's playing)), even though the video was stopped. Now the source of truth is `video.paused`.
- Forgot `data-icon` on the gear → the button came out empty. Found by audit «усі порожні кнопки мусять мати» (all empty buttons must have) `data-icon`" (only those populated by JS and CSS are left).

Why this is not a small thing: emojis do not inherit theme color, do not thin out with `stroke-width`, and do not scale to fit button size. The requirement is documented in the package checklist (`docs/SCREEN-PLATFORM.md` 2.3) and by three tests.

Verified live: sponsored segment 0–4.08 skipped itself (3.9s), the second at 16:42 too (total 66.2s of the promised SponsorBlock 66.6); all 11 remote actions; settings by finger ↔ by bot in both directions.

### Exploitation nuance
`store/installed/apps/youtube/` is a COPY made during installation. Changes in `store/packages/youtube/` do not go there by themselves: after editing the package, you need `POST /api/screen-store/install {"id":"youtube"}` and refresh `/screen`.
