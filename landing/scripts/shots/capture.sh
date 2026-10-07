#!/usr/bin/env bash
# Capture the dashboard and device-screen pictures the landing shows.
#
# Runs against a throwaway Virtual Bot (see README.md in this folder): it seeds
# the demo content for each language, drives the real UI with agent-browser,
# and writes WebP files to landing/public/shots/<lang>/.
#
#   BOT_ROOT="/tmp/cb-shots/Virtual Bot" BOT_URL=http://127.0.0.1:8199 ./capture.sh
#
# Needs: agent-browser, cwebp, python3. Mutes the Mac first, like every test
# here, because the dashboard and the screen can both make sound.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LANDING="$(cd "$HERE/../.." && pwd)"
REPO="$(cd "$LANDING/.." && pwd)"
BOT_ROOT="${BOT_ROOT:?set BOT_ROOT to the throwaway Virtual Bot folder}"
BOT_URL="${BOT_URL:-http://127.0.0.1:8199}"
LANGS="${LANGS:-en}"
PARTS="${PARTS:-dashboard screen}"
for part in $PARTS; do
  case "$part" in dashboard|screen) ;; *) echo "Use capture-android.sh for native mobile screenshots." >&2; exit 1 ;; esac
done
RAW="$HERE/raw"
SKY="$REPO/Virtual Bot/dashboard/src/panels/chat/assets/chat-sky-v2.webp"
export AGENT_BROWSER_SESSION="landing-shots-$$"
trap 'agent-browser close >/dev/null 2>&1 || true' EXIT

osascript -e "set volume output muted true" 2>/dev/null || true
mkdir -p "$RAW"
CAPTURED=()

ab() { agent-browser "$@" >/dev/null; }
js() { agent-browser eval --stdin >/dev/null; }
pause() { agent-browser wait "$1" >/dev/null; }

# Sections live in the URL hash (#/chat, #/memory, ...), which works the same
# on the desktop dock and behind the phone's menu, in either language.
go() {
  js <<EOF
(() => { location.hash = '#/$1'; return 'ok'; })()
EOF
  pause 1400
}

# No keyboard focus ring, no "jump to latest" arrow: a still picture of a calm UI.
tidy() {
  js <<'EOF'
(() => {
  document.activeElement && document.activeElement.blur();
  if (!document.getElementById('shot-tidy')) {
    const style = document.createElement('style');
    style.id = 'shot-tidy';
    style.textContent = '.chat-scroll-latest { display: none !important; } *:focus-visible { outline: none !important; }';
    document.head.append(style);
  }
  return 'ok';
})()
EOF
  pause 300
}

shot() {
  # The marketing capture must never expose the optional mascot panel.
  if [[ "$1" == *-chat || "$1" == *-welcome ]]; then
    ab wait --fn '!document.querySelector("[data-right-panel-trigger]") || document.querySelector("[data-right-panel-trigger]").dataset.panelMode === "hidden"'
  fi
  agent-browser screenshot "$RAW/$1.png" >/dev/null
  CAPTURED+=("$RAW/$1.png")
  echo "  captured $1"
}

title_of() {
  python3 -c "import json,sys; print(json.load(open('$HERE/demo-data.json'))['$1']['sessions'][0]['title'])"
}

note_of() {
  python3 -c "import json,sys; print(json.load(open('$HERE/demo-data.json'))['$1']['openNote'])"
}

prefs() {
  local lang="$1" appearance="$2"
  js <<EOF
(() => {
  localStorage.setItem('claudeBotTheme', 'dark');
  localStorage.setItem('claudeBotAccent', 'terracotta');
  localStorage.setItem('claudeBotChatPins', '[]');
  localStorage.setItem('claudeBotLang', '$lang');
  localStorage.setItem('claudeBotChatAppearance', JSON.stringify({ ...$appearance, sidebarVisible: false }));
  location.reload();
  return 'ok';
})()
EOF
  ab wait --load networkidle
  pause 1500
}

open_main_chat() {
  local lang="$1"
  go chat
  ab find text "$(title_of "$lang")" click
  pause 1800
  ab press Escape
  # Open the agent-activity tree of the first reply, then show the thread from its start.
  js <<'EOF'
(() => {
  const toggle = document.querySelector('.chat-activity-toggle[aria-expanded="false"]');
  toggle && toggle.click();
  return !!toggle;
})()
EOF
  pause 700
  js <<'EOF'
(() => { const thread = document.querySelector('.chat-thread-viewport'); if (thread) thread.scrollTop = 0; return 'ok'; })()
EOF
  pause 500
  tidy
}

capture_dashboard() {
  local lang="$1"
  echo "dashboard ($lang)"
  ab set viewport 1440 1000 2
  ab open "$BOT_URL/dash/"
  prefs "$lang" "{\"background\":\"none\"}"

  open_main_chat "$lang"
  shot "$lang-chat"

  go memory
  ab find text "$(basename "$(note_of "$lang")" .md)" click
  pause 1500
  tidy
  shot "$lang-memory"

  # The welcome screen wears the project's own painted sky, set as a custom wallpaper.
  local sky
  sky="data:image/webp;base64,$(base64 < "$SKY" | tr -d '\n')"
  prefs "$lang" "{\"background\":\"custom\",\"image\":\"$sky\"}"
  go chat
  # The two labels are the dashboard's own "New conversation" in uk and en.
  js <<'EOF'
(() => {
  const fresh = [...document.querySelectorAll('button')].find(b => /нова розмова|new chat|new conversation/i.test(b.getAttribute('aria-label') || b.title || ''));
  fresh && fresh.click();
  return !!fresh;
})()
EOF
  pause 1500
  tidy
  shot "$lang-welcome"
  prefs "$lang" "{\"background\":\"none\"}"
}

capture_screen() {
  local lang="$1"
  echo "screen ($lang)"
  ab set viewport 320 240 4
  ab open "$BOT_URL/screen"
  js <<EOF
(() => { localStorage.setItem('botScreenLang', '$lang'); location.reload(); return 'ok'; })()
EOF
  ab wait --load networkidle
  pause 2500
  ab press ArrowRight
  pause 1200
  shot "$lang-screen-clock"
  ab press ArrowRight
  ab press ArrowRight
  pause 2500
  shot "$lang-screen-weather"
  ab press Escape
  pause 800
  ab press ArrowUp
  pause 1500
  shot "$lang-screen-apps"
  ab press ArrowUp
  pause 600
}

for lang in $LANGS; do
  python3 "$HERE/seed.py" --root "$BOT_ROOT" --url "$BOT_URL" --lang "$lang"
  for part in $PARTS; do "capture_$part" "$lang"; done
done

# Two widths per picture: the page asks for the one that fits the slot.
for png in "${CAPTURED[@]}"; do
  name="$(basename "$png" .png)"
  lang="${name%%-*}"
  base="${name#*-}"
  out="$LANDING/public/shots/$lang"
  mkdir -p "$out"
  width="$(sips -g pixelWidth "$png" | awk '/pixelWidth/ {print $2}')"
  cwebp -quiet -q 82 -m 6 "$png" -o "$out/$base.webp"
  cwebp -quiet -q 80 -m 6 -resize "$((width / 2))" 0 "$png" -o "$out/$base-half.webp"
done
echo "WebP files written to $LANDING/public/shots"
