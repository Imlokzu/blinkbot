# Blink Bot — landing page

A dark, screenshot-led page for the project: the real dashboard rises out of
the hero as you scroll, a tour walks through its panels, and the device
section cycles through clock, weather and app-drawer views. The Blink spiral
is the only decorative product mark; there is no character mascot.

```bash
npm install
npm run dev      # http://localhost:5199
npm test         # catalogue parity, prerender, language detection
npm run build    # static site in dist/
```

The old 3D version (CRT intro, asteroid tools) lives in `../landing-3d/`.

## How it is put together

| Piece | Where | Notes |
|---|---|---|
| Markup | `index.html` | Keys only, no visible text (see i18n below) |
| Motion | `src/hero.js`, `src/tour.js`, `src/device.js`, `src/reveal.js`, `src/extras.js` | GSAP with ScrollTrigger, ScrollSmoother and SplitText — the default in `~/stack/ui-effects.md`, and already the dashboard's |
| Screenshots | `public/shots/en/` | English captures, see below |
| Type | `@fontsource` | IBM Plex Sans and Mono like the dashboard, Cormorant Garamond italic like its welcome heading. Self-hosted, Cyrillic included |

Everything moving is `transform` or `opacity`. With
`prefers-reduced-motion`, smooth scrolling, pinning, scrubbing and the
entrances are all off, and the page reads top to bottom.

## Languages

`src/i18n.js` holds one `DICT` with `uk` and `en`, the same shape as the
screen's catalogue. In `index.html` text is a key:

```html
<h3 data-i18n="tour.chatTitle"></h3>
<img data-i18n-attr="alt:tour.chatAlt" />
```

Elements with `data-i18n` are written empty; the build fills them in English
(`scripts/prerender-i18n.js`), so the page reads before scripts run and to
crawlers. Product screenshots stay in English, with the optional right panel closed.
The visitor's language is picked from `?lang=`, then the saved
choice, then the browser, before the hero makes its entrance.
`scripts/i18n_check.py` at the repo root checks key parity and that every key
the page uses exists.

## Screenshots

The pictures are the real dashboard and device screen, but never the owner's
data: they come from a throwaway copy of the bot filled with the demo content
in `scripts/shots/demo-data.json`. The default capture language is English.

```bash
# 1. a clean checkout of the bot (user_data/ is git-ignored, so it starts empty)
git worktree add --detach /tmp/cb-shots HEAD
ln -s "$PWD/../Virtual Bot/.venv" "/tmp/cb-shots/Virtual Bot/.venv"

# 2. run it on its own port, with messengers off
cd "/tmp/cb-shots/Virtual Bot"
CLERK_DISABLED=1 TELEGRAM_DISABLED=1 DISCORD_DISABLED=1 \
  .venv/bin/python -m uvicorn main:app --host 127.0.0.1 --port 8199

# 3. seed English demo data and capture with the right panel closed
BOT_ROOT="/tmp/cb-shots/Virtual Bot" BOT_URL=http://127.0.0.1:8199 \
  ./scripts/shots/capture.sh
```

`seed.py` refuses to write into the real `Virtual Bot/`. For the screen's app
drawer, install a few store apps in the copy first
(`POST /api/screen-store/install {"id": "flappy-crab"}`).

The welcome screenshot uses the dashboard's original painted sky
(`chat-sky-v2.webp`), not the reference image behind the `sky` preset, whose
author and licence are unknown.

## Deploying

Nothing is deployed yet. `dist/` is a plain static site, so Cloudflare Pages
or any static host will do (see `~/stack/hosting.md`).
