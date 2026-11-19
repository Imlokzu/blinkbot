# Blink Bot — landing page

The landing carries the owner's portfolio style into Blink: a centered
Geist heading, matching light and dark palettes, and a row-based product
tour. Its previews show the real dashboard and Android app. The Raspberry
Pi section switches between clock, weather and app-drawer captures.

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
| Interactions | `src/main.js` | Language, mobile navigation, device views and copy buttons |
| Motion | `src/title-motion.js`, `src/motion.js` | Proximity lettering, scroll lighting, entrances and pointer-following previews |
| Screenshots | `public/shots/en/` | Responsive English captures in the HTML |
| Type | `@fontsource` | Self-hosted Geist Variable and IBM Plex Mono, including Cyrillic |

Native navigation, setup instructions and inline tour images work without
JavaScript. Touch screens and reduced-motion users get the inline previews.
With `prefers-reduced-motion`, the headline, scroll copy and menus stay
static. The headline animation pauses when it leaves the viewport.

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
The phone image comes from the native Kotlin Multiplatform Android app in
`mobile-app/`, with its conversation drawer closed.
The visitor's language is picked from `?lang=`, then the saved
choice, then the browser when the page module loads.
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

Capture the phone separately from the real Android Compose app on a dedicated
running emulator (JDK17, Android SDK, `adb`, `cwebp`, and Python are required):

```bash
./scripts/shots/capture-android.sh emulator-5580
```

The script builds and installs the Android app/test APKs, runs
`LandingScreenshotTest` with an English conversation fixture, and exports the
native screenshot at 1170x2532 plus a half-width WebP. The fixture uses the real
app shell/controller/UI with an in-memory mock host and silent platform bridge;
it never pairs with a real bot. Display and font settings are restored on exit.
`capture.sh` handles desktop and device-screen captures only and cannot overwrite
the phone image with a mobile browser screenshot.

`seed.py` refuses to write into the real `Virtual Bot/`. For the screen's app
drawer, install a few store apps in the copy first
(`POST /api/screen-store/install {"id": "flappy-crab"}`).

The welcome screenshot uses the dashboard's original painted sky
(`chat-sky-v2.webp`), not the reference image behind the `sky` preset, whose
author and licence are unknown.

## Deploying

The production site is `https://waveio.me`, served by the Cloudflare Pages
project `blinkbot`. `wrangler.jsonc` declares the static output directory.
The repository is `https://github.com/Imlokzu/blinkbot`.

`.github/workflows/landing-deploy.yml` tests, builds and deploys changes to
`landing/` on pushes to `main`. It uses repository secrets
`CLOUDFLARE_ACCOUNT_ID` and `CLOUDFLARE_API_TOKEN`; the token is restricted to
Pages deployment in the Cloudflare account. Credentials live in the agents'
vault, never in this repository.

For a manual deployment with those environment variables already configured:

```bash
npm ci
npm test
npm run build
npx --yes wrangler@4.148.0 pages deploy dist --project-name blinkbot --branch main
```

This is a Direct Upload project with GitHub Actions handling continuous
updates. Changing website DNS must preserve the independent bot API, tunnel,
email and other subdomains in the zone.
