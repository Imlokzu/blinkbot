#!/usr/bin/env bash
# Pull the newest mobile-v* GitHub Release asset and publish it through
# scripts/mobile_publish.sh so the local backend serves it. Usage:
#   scripts/mobile_pull_release.sh [--tag mobile-v0.4.13] [--restart] [--public]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"
TAG="" RESTART="" PUBLIC=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tag) TAG="$2"; shift 2 ;;
        --restart) RESTART="--restart"; shift ;;
        --public) PUBLIC="--public"; shift ;;
        *) echo "unknown flag: $1" >&2; exit 2 ;;
    esac
done

if [[ -z "$TAG" ]]; then
    TAG="$(gh release list --repo "$REPO" --limit 100 --json tagName --jq \
        '[.[] | select(.tagName | startswith("mobile-v"))] | first | .tagName // empty')"
fi
[[ -n "$TAG" ]] || { echo "no mobile-v* release found" >&2; exit 1; }

CHANNEL="stable"
NAME="${TAG#mobile-v}"
if [[ "$NAME" == *-beta ]]; then
    CHANNEL="beta"
    NAME="${NAME%-beta}"
fi
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
gh release download "$TAG" --repo "$REPO" --pattern "ClaudeBot-*.apk" --dir "$DIR" --clobber
APK="$(find "$DIR" -name 'ClaudeBot-*.apk' -print -quit)"
[[ -f "$APK" ]] || { echo "release asset missing in $TAG" >&2; exit 1; }

GRADLE="$ROOT/mobile-app/androidApp/build.gradle.kts"
CURRENT_CODE="$(sed -n 's/.*versionCode *= *\([0-9][0-9]*\).*/\1/p' "$GRADLE")"
CODE="${NAME##*.}"
if [[ "$CODE" =~ ^[0-9]+$ ]] && (( CODE > CURRENT_CODE )); then
    NEXT="$CODE"
else
    NEXT="$((CURRENT_CODE + 1))"
fi

NOTES="$(gh release view "$TAG" --repo "$REPO" --json body --jq .body | head -1)"
"$ROOT/scripts/mobile_publish.sh" --apk "$APK" --version-name "$NAME" \
    --version-code "$NEXT" --channel "$CHANNEL" --changelog "${NOTES:-Mobile app update}" $RESTART $PUBLIC
