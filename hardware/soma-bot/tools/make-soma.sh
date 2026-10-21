#!/bin/sh
# Package this folder as a SOMA model file (dist/claude-bot.soma) that opens with File > Open.
#
# SOMA keeps every model in its own working folder with a git history and opens .soma files
# (a ZIP of that folder). This script builds the same thing: the model sources, the app's
# AGENTS.md / CLAUDE.md / .gitignore, .soma/project.json with a fixed ID (so a newer file
# updates the same model in the app), a first `soma build`, and one git commit.
#
# Usage: tools/make-soma.sh [output.soma]
set -eu

HERE=$(cd "$(dirname "$0")/.." && pwd)
SOMA_RES=${SOMA_RES:-/Applications/SOMA.app/Contents/Resources}
PY="$SOMA_RES/python/bin/python3"
TEMPLATE="$SOMA_RES/app/resources/template"
PROJECT_ID=e6c960d8-20aa-4b40-aa73-b3ffd42b24cb
STEP="$HERE/inputs/waveshare-35-hdmi-lcd-e.stp"
STEP_URL="https://files.waveshare.com/upload/b/b3/3.5inch_HDMI_LCD_%28E%29_3D_Drawing.zip"
OUT=${1:-"$HERE/dist/claude-bot.soma"}
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"

[ -x "$PY" ] || { echo "SOMA not found at $SOMA_RES (set SOMA_RES)" >&2; exit 1; }

# The maker's STEP is not committed (not ours to redistribute): fetch it once.
if [ ! -f "$STEP" ]; then
  dl=$(mktemp -d)
  curl -fsSL "$STEP_URL" -o "$dl/drawing.zip"
  unzip -q -o "$dl/drawing.zip" -d "$dl/x"
  cp "$dl/x/3_5inch-hdmi-lcd_e.stp" "$STEP"
  rm -rf "$dl"
fi

work=$(mktemp -d)
ws="$work/claude-bot"
mkdir -p "$ws/.soma"
rsync -a --exclude tools --exclude dist --exclude output --exclude .soma --exclude renders --exclude exports \
  --exclude __pycache__ --exclude .gitignore --exclude README.md --exclude .DS_Store "$HERE/" "$ws/"
cp "$TEMPLATE/AGENTS.md" "$TEMPLATE/CLAUDE.md" "$TEMPLATE/.gitignore" "$ws/"

version=$("$PY" -I -c "import soma; print(soma.__version__)")
created=$(date -u +%Y-%m-%dT%H:%M:%S.000Z)
cat > "$ws/.soma/project.json" <<EOF
{
  "id": "$PROJECT_ID",
  "name": "Claude Bot",
  "api_version": 1,
  "app_version": "$version",
  "entry": "model.py",
  "main_output": "output/model.step",
  "created": "$created"
}
EOF

(cd "$ws" && "$PY" -m soma build --project .)

git -C "$ws" init -q
git -C "$ws" add -A
GIT_AUTHOR_NAME='Imlokzu' GIT_AUTHOR_EMAIL='lokzuhd@gmail.com' \
GIT_COMMITTER_NAME='Imlokzu' GIT_COMMITTER_EMAIL='lokzuhd@gmail.com' \
  git -C "$ws" commit -q -m "Claude Bot prototype"

rm -f "$OUT"
# Same exclusions as the app's own save (viewer cache and renders are rebuilt on open)
(cd "$ws" && zip -q -r -X "$OUT" . -x '.soma/geometry/*' '.soma/model.glb' '.soma/model.map.json' \
  '.soma/build.json' 'renders/*' '*/__pycache__/*' '__pycache__/*' '.soma/env/*')
rm -rf "$work"
echo "wrote $OUT"
