---
name: screen-app-icons
description: Draw or redraw an app icon for the bot's screen (the watch-style app drawer and the store) in its One UI-style look — a gradient disc with one bold white glyph, in Virtual Bot/static/screen/app-icons.js. Use whenever a new screen, a new built-in store package, or a skin needs an icon; when an app shows the grey letter fallback; when someone asks to "draw an icon", "make an app icon", "icon in the same style", or to fix, recolour or restyle one of the existing app icons.
---

# Screen app icons

Every icon in the drawer (`drawer.js`) and the store is drawn in code in
`Virtual Bot/static/screen/app-icons.js`. There are no image files. An icon
is one entry in `DESIGNS` plus a line in `BY_ID` or `BY_ICON`. This skill is
how to add one that looks like the rest of the set.

## The look

The style is One UI / watch: a **full coloured disc** with a top-to-bottom
gradient and **one bold white glyph** in the middle. The colour tells you
which app it is; the glyph only has to read at a glance.

Every icon also has to survive the drawer's fisheye. At the rim of the
honeycomb it shrinks to about 30 % (roughly 15 px on the device). A thin
line or a small detail disappears at that size. Design for 15 px and check
at 96 px.

## Hard rules

| Rule | Why |
|---|---|
| viewBox `0 0 48 48`; `appIconSvg` draws the disc, so you draw only the glyph | one frame for every icon |
| Keep the glyph within about x/y 10–38 (radius about 15 around 24,24) | the glyph must not touch the rim of the disc |
| Draw the shape in **white (`W`)**. Cut-outs (eyes, pips, lens) use the disc's dark tone **`D`**, which is passed to `glyph(D)` | cut-outs then look like holes, not a second colour |
| Use **at most one accent colour** (e.g. `#ffd60a`, `#ff9f0a`, `#64d2ff`, `#30d158`, `#ff453a`) | two accents make the icon look busy |
| Prefer filled shapes. Strokes must be at least 2.2 wide with round caps; use the `line()` helper | anything thinner vanishes at 15 px |
| No text, unless the text **is** the app (`2048`) | text is unreadable at the rim |
| No emoji, no copied brand logos, no `filter`, no shadows | emoji ignore the theme, filters cost frames on the Pi, and the repo is going public |
| No `id` attributes of your own. `appIconSvg` gives each gradient a unique id | SVG ids are global to the page, so a shared id paints every disc in one icon's colours |

## Colour

- `bg: [top, bottom]`. The bottom is 15–25 % darker than the top and has
  the same hue. Keep it saturated and mid-bright: white must stand out
  clearly on both stops.
- Set `d:` only when the bottom colour is too light for cut-outs, as on
  the yellow discs (`notebook`, `tile2048`).
- **Pick a hue the set does not already use a lot.** Before choosing one,
  look through `DESIGNS` for colours already taken:
  - greens: `bubble`, `snake`, `leaf`, `puzzle`;
  - blues: `bag`, `weather`, `server`, `globe`;
  - oranges: `hourglass`, `pencil`, `grid`;
  - greys and blacks: `clock`, `calc`, `camera`, `monitor`, `power`.

  An app that sits next to its twin in the honeycomb is hard to find.
- Dark discs (`#3a3a3c → #141416` and similar) suit "system" things such as
  the clock, the calculator, the terminal and the panel.

## Helpers in app-icons.js

- `line(d, color, width)` draws a round-capped, round-joined stroke path.
- `gearPath(cx, cy, teeth, tip, root)` builds the outline of a gear.
- `rays(cx, cy, count, from, to, color, width, skip)` draws sun rays;
  `skip(angle)` leaves out the ones hidden behind something.
- `crescent(cx, cy, r, bx, by, br)` returns a crescent path, computed
  exactly.
- `pixels(rows, x0, y0, cell, color, accent)` draws pixel art from rows of
  `"X"`.

## Steps

1. **Draw the idea small first.** Ask what the thing would look like as a
   15 px silhouette: a crab, a tomato, a metronome. Pick the one object
   that names the app, not a scene.
2. **Add a design** to `DESIGNS`:
   ```js
   myapp: {
     bg: ["#5e5ce6", "#3634a3"],
     glyph: (D) =>
       `<rect x="14" y="14" width="20" height="20" rx="5" fill="${W}"/>` +
       `<circle cx="24" cy="24" r="4" fill="${D}"/>`,
   },
   ```
3. **Map it.**
   - A built-in store package goes in `BY_ID` under its **package id**. A
     screen goes in `BY_ID` under its id in `SCREENS` in `screen.js`.
   - A new generic manifest `icon` name goes in `BY_ICON`. That name must
     also exist in `icons.js`, because `test_icons_match_screen_icon_set`
     checks manifests against it.
   - The package id wins over the manifest icon. That is why
     `flappy-crab`, whose manifest says `icon: "face"`, is still drawn as a
     crab.
4. **Test.** Mute the Mac first, then run:
   ```bash
   osascript -e "set volume output muted true"
   cd "Virtual Bot" && PYTHONPATH="$PWD" .venv/bin/pytest tests/test_screen_js.py -q -k "AppIcons or Drawer"
   ```
   - `test_every_screen_and_package_has_its_own_design` fails if any
     screen or package still falls back to the grey letter disc.
   - `test_every_design_renders_as_well_formed_svg` fails on broken
     markup, `NaN` or `undefined`.
5. **Look at it.** A passing test does not mean the icon looks right.
   1. Start a test server with the messengers off. A second Telegram
      poller steals the live bot's messages:
      ```bash
      CLERK_DISABLED=1 TELEGRAM_DISABLED=1 DISCORD_DISABLED=1 .venv/bin/python -m uvicorn main:app --port 8111
      ```
   2. Open `http://127.0.0.1:8111/screen` with agent-browser and render the
      whole set on one sheet:
      ```js
      import('/static/screen/app-icons.js').then(m => {
        const d = document.createElement('div');
        d.style.cssText = 'position:fixed;inset:0;z-index:99999;background:#111;display:flex;flex-wrap:wrap;gap:8px;padding:8px;align-content:flex-start';
        for (const k of Object.keys(m.DESIGNS)) {
          const c = document.createElement('div');
          c.style.cssText = 'width:104px;text-align:center;color:#aaa;font:10px sans-serif';
          c.innerHTML = m.appIconSvg(k, {label: 'Zed'}).replace('<svg ', '<svg width="96" height="96" ') + '<br>' + k;
          d.appendChild(c);
        }
        document.body.appendChild(d);
      })
      ```
   3. On that sheet, check that the new icon:
      - reads as its object;
      - is not a colour twin of its neighbours;
      - carries about the same visual weight as the others.
   4. Then open the drawer (ArrowUp):
      - drag the new icon to the rim and check that it still reads there;
      - check it in the light theme too:
        `document.documentElement.dataset.theme = 'light'`.
   5. Stop the test server afterwards.
6. **Commit** `app-icons.js` together with the package or screen that needed
   the icon. Follow `AGENTS.md`: author `Imlokzu <lokzuhd@gmail.com>`,
   English message, push.

## Restyling an existing icon

Keep its key and its mapping. Change only `bg`, `d` or `glyph`, so no
drawer or store entry changes. Check it on the sheet next to its old
neighbours. A restyle that makes one icon louder than the rest is a
regression, even when that icon alone looks better.
