---
name: screen-app-native
description: Make a bot-screen store app (Virtual Bot/store/packages/<id>/) look and feel native — full screen, in the screen's current interface style (Material You or Deep UI, dark or light, any skin) — by building it on the app kit (static/screen/app-kit.css + app-kit.js). Use when writing a new store app, adapting or restyling an existing one, when an app "looks like a web page in a frame", ignores the screen's theme/style, or when test_builtin_app_is_native fails.
---

# Native store apps (app kit v1)

A store app runs in an iframe that fills the whole 320×240 panel. It has
no title bar and no frame. The way out is the screen's gesture strips
(`gesture-nav.js`): a pill at the bottom and 12 px strips along the
sides.

CSS does not cross into an iframe. The **app kit** carries the screen's
look across:
- `app-kit.js` receives the screen's resolved tokens (colours, font,
  theme, `ui` = `material` | `deep`, language, safe insets) and sets
  them on `<html>`;
- `app-kit.css` builds native parts from those tokens.

Read `Virtual Bot/static/screen/app-kit.css` once before you start. Its
header explains the contract. The reference app is
`store/packages/metronome/index.html`: copy its structure.

## Skeleton

```html
<!DOCTYPE html>
<html lang="uk">
<head>
<meta charset="UTF-8">
<title>…</title>
<link rel="stylesheet" href="/static/screen/app-kit.css">
<script src="/static/screen/app-kit.js"></script>
<style>/* only what is this app's own */</style>
</head>
<body class="kit">
  <main class="kit-app">
    <header class="kit-bar"><span class="kit-title" data-i18n="title"></span> …actions</header>
    <section class="kit-fill kit-center">…the app…</section>
    <footer class="kit-row center">…main actions…</footer>
  </main>
<script>
  const STR = { uk: {…}, en: {…} };
  let lang = window.BotApp ? BotApp.lang : "uk";
  …
  if (window.BotApp) BotApp.onChange((s) => { if (s.lang !== lang) { lang = s.lang; applyI18n(); } });
</script>
</body>
</html>
```

## The parts (all in app-kit.css)

| Need | Use |
|---|---|
| page, safe insets | `body.kit` > `main.kit-app` (padding keeps taps out of the gesture strips) |
| the app's name | `header.kit-bar` > `.kit-title` (+ `.kit-sub`, actions on the right) |
| layout | `.kit-fill`, `.kit-center`, `.kit-row` (`.center` `.spread` `.wrap`), `.kit-scroll` |
| surface | `.kit-card` (`.hi` for a raised one) |
| big number | `.kit-display` (+ `<small>` unit), `.kit-label` for captions |
| buttons | `.kit-btn` + `.primary` (THE action, one per screen), `.tonal`, `.ghost`, `.danger`, `.icon` (round, inline SVG `viewBox="0 0 24 24"`, stroke only), `.big`, `.block` |
| choices | `.kit-chips` > `.kit-chip.on`, `.kit-segment` > `.kit-chip` |
| slider | `<input type="range" class="kit-slider">`; after setting `.value` from code call `BotApp.paint(el)` |
| switch | `<button class="kit-switch" aria-pressed="true|false">` |
| list | `.kit-list` > `.kit-item` (`.kit-grow` for the text) |
| keypad / board cell | `.kit-key` (`.accent`, `.tonal`) |
| search / text field | `<label class="kit-input"><svg…/><input></label>` |
| media progress | `.kit-progress` with `--p` (0…1) |
| read-only pill, stat | `.kit-pill` (= `.kit-badge`), `.kit-stat` > `b` + `span` |
| toast | `.kit-snackbar` (+ `.show`) |
| a page over the app | `.kit-page` (the app's ground, aurora in Deep UI) |
| small icon button, filled icon | `.kit-btn.icon.sm`, `<svg class="fill">` |
| canvas colours | `BotApp.color("--accent")` → a plain `rgb()`/`rgba()` a canvas accepts |
| colours in your own CSS | `var(--bg --panel --line --text --muted --accent --ok --off)`, and `--kit-surface`, `--kit-surface-hi`, `--kit-tonal`, `--kit-on-tonal`, `--kit-primary` (a gradient in Deep UI: use it as `background`, not `color`) |
| style-specific tweaks | `:root[data-ui="deep"] …` / `:root[data-ui="material"] …` / `:root[data-theme="light"] …` |

## Rules

1. **Don't redefine the screen's tokens.** No `--bg`, `--panel`,
   `--text`, `--accent`, `--line` or `--muted` in your own `:root`. The
   kit owns them; defining them pins your theme over the screen's.
   `test_builtin_app_is_native` checks this.
2. **No title bar with a back button, no "✕".** The screen's gestures
   exit. An in-app "back" is fine when the app has pages of its own (a
   player → the list). `BotApp.close()` leaves the app.
3. **Delete the old skin plumbing.** No `botSkin` listener, no
   `dataset.theme`, no light palette of your own. `app-kit.js` does all
   of it. Keep only the language follow-up through `BotApp.onChange`.
4. **Draw game boards and art from tokens.** A canvas game reads colours
   with `BotApp.color("--accent")` (it also resolves `color-mix()` values).
   Read them again in `BotApp.onChange`, so the board follows a style
   switch. Never `scrollIntoView()` inside an app: it scrolls the screen
   itself; scroll the list element (`list.scrollTop = …`).
5. **Deep UI gradients stay in ONE hue**, light at the top and deep at
   the bottom, as on the app icons. Never blend two hues (blue → violet
   reads as a generic "AI" look). `--kit-primary` already does this;
   copy it.
6. **Everything the old app did keeps working:** same features, same
   storage keys, same i18n dictionaries (uk and en), same messages to
   the parent (`botKeyboard`, `botVideo*`, `botMusic*` …). You are
   restyling, not rewriting the logic.
7. **The platform rules stand** (docs/SCREEN-PLATFORM.md §2.3):
   - offline only, no external URLs;
   - no emoji — use inline SVG icons;
   - no `alert`, `confirm` or `prompt`;
   - animate only `transform` and `opacity`, no `filter`, no shadows on
     moving things;
   - buttons at least 24 px, 6 px apart;
   - no hardcoded Cyrillic in the HTML (use `data-i18n`).
8. **Bump the version** in `package.json` (a minor or major step). The
   screen re-copies an installed built-in app when its version changes
   (`screen_store.refresh_builtin_apps`). Without a bump, screens keep the
   old copy.

## Check

```bash
osascript -e "set volume output muted true"
cd "Virtual Bot" && PYTHONPATH="$PWD" .venv/bin/pytest tests/test_store_packages.py -q -k "<id>"
```

Then look at it. Use a test server with the messengers off, never the
owner's :8100:
```bash
CLERK_DISABLED=1 TELEGRAM_DISABLED=1 DISCORD_DISABLED=1 VBOT_INTEGRATIONS_DIR=/tmp/vb-empty-integrations .venv/bin/python -m uvicorn main:app --port 8111
```
1. Refresh the installed copy:
   `curl -s -X POST localhost:8111/api/screen-store/install -H 'Content-Type: application/json' -d '{"id":"<id>"}'`.
2. Open `http://127.0.0.1:8111/screen` in agent-browser (viewport
   640×480) and launch the app from the drawer (ArrowUp, then click
   `.wd-app[data-id="app:<id>"]`).
3. Look at it in both styles
   (`localStorage.botScreenUiStyle = "material"` / `"deep"`) and in the
   light theme (`botScreenTheme = "light"`). Reload after each change.
4. Check that nothing sits in the bottom 16 px or the outer 12 px.
