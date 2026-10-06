#!/usr/bin/env bash
# Publish a built APK into `Virtual Bot/.env` and restart the backend so the
# mobile app sees the update. Usage:
#   scripts/mobile_publish.sh --apk <path> --version-name 0.4.13 \
#       --version-code 20 --changelog "Fix streaming" [--restart] [--public]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENV_FILE="$ROOT/Virtual Bot/.env"
RELEASES_DIR="$ROOT/Virtual Bot/runtime/releases"
SERVICE="me.waveio.klodbot-web"
DB="$ROOT/Virtual Bot/runtime/mobile.sqlite3"

APK="" VERSION_NAME="" VERSION_CODE="" CHANGELOG="" CHANNEL="stable"
RESTART=0 PUBLIC=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --version-name) VERSION_NAME="$2"; shift 2 ;;
        --version-code) VERSION_CODE="$2"; shift 2 ;;
        --changelog) CHANGELOG="$2"; shift 2 ;;
        --channel) CHANNEL="$2"; shift 2 ;;
        --restart) RESTART=1; shift ;;
        --public) PUBLIC=1; shift ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done
[[ "$CHANNEL" == "stable" || "$CHANNEL" == "beta" ]] || { echo "release aborted: channel must be stable or beta" >&2; exit 2; }
[[ -f "$APK" && -s "$APK" && -n "$VERSION_NAME" && -n "$VERSION_CODE" ]] || {
    echo "release aborted: --apk, --version-name and --version-code are required" >&2; exit 2; }

SHA256="$(shasum -a 256 "$APK" | awk '{print $1}')"
if [[ "$CHANNEL" == "beta" ]]; then
    PUBLISHED="$RELEASES_DIR/ClaudeBot-$VERSION_NAME-beta.apk"
else
    PUBLISHED="$RELEASES_DIR/ClaudeBot-$VERSION_NAME.apk"
fi
mkdir -p "$RELEASES_DIR"
if [[ ! -f "$PUBLISHED" || "$(shasum -a 256 "$PUBLISHED" | awk '{print $1}')" != "$SHA256" ]]; then
    cp "$APK" "$PUBLISHED"
fi

PREFIX="MOBILE_UPDATE_ANDROID"
if [[ "$CHANNEL" == "beta" ]]; then
    PREFIX="MOBILE_UPDATE_BETA_ANDROID"
fi

set_env() { # key value — keep the file's existing quoting by rewriting whole lines
    python3 - "$ENV_FILE" "$1" "$2" <<'PY'
import re, sys
path, key, value = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path, encoding="utf-8").read()
line = f"{key}={value}"
pattern = re.compile(rf"^[ \t]*{re.escape(key)}=.*$", re.MULTILINE)
text = pattern.sub(line, text) if pattern.search(text) else text.rstrip("\n") + f"\n{line}\n"
open(path, "w", encoding="utf-8").write(text)
PY
}
set_env "${PREFIX}_VERSION" "$VERSION_NAME"
set_env "${PREFIX}_VERSION_CODE" "$VERSION_CODE"
set_env "${PREFIX}_CHANGELOG" "${CHANGELOG//[$'\n']/ | }"
set_env "${PREFIX}_FILE" "$PUBLISHED"
set_env "${PREFIX}_SHA256" "$SHA256"

if [[ "$RESTART" == "1" ]]; then
    if [[ -f "$DB" ]]; then
        ACTIVE=$(sqlite3 "$DB" "SELECT COUNT(*) FROM jobs WHERE state IN ('running','stopping')" 2>/dev/null || echo 0)
        [[ "$ACTIVE" == "0" ]] || { echo "release aborted: $ACTIVE mobile jobs are running" >&2; exit 1; }
    fi
    launchctl kickstart -k "gui/$(id -u)/$SERVICE"
    sleep 3
    probe() { curl -fsS -H "User-Agent: okhttp/4.12.0" "$1/api/mobile/capabilities" 2>/dev/null \
        | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if d.get('update',{}).get('version_code')==$VERSION_CODE else 1)"; }
    probe "http://127.0.0.1:8100" || { echo "release aborted: local probe did not confirm $VERSION_CODE" >&2; exit 1; }
    [[ "$PUBLIC" == "1" ]] && { probe "https://api-bot.waveio.me" \
        || { echo "release aborted: public probe did not confirm $VERSION_CODE" >&2; exit 1; }; }
    echo "published $VERSION_NAME ($VERSION_CODE) sha256=${SHA256:0:12}…"
else
    echo "staged $VERSION_NAME ($VERSION_CODE); run with --restart to go live"
fi
