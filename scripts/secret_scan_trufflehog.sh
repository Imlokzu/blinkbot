#!/bin/sh
set -eu

mode="${1:-staged}"
root="$(git rev-parse --show-toplevel)"
cd "$root"

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/claude-bot-trufflehog.XXXXXX")"
report="$tmp_dir/report.jsonl"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT HUP INT TERM

run_scan() {
  set +e
  trufflehog "$@" \
    --exclude-paths .trufflehogignore \
    --results=verified,unknown \
    --fail \
    --no-update \
    --json >"$report" 2>&1
  status=$?
  set -e

  case "$status" in
    0)
      return 0
      ;;
    183)
      echo "TruffleHog blocked this change: potential secrets were found." >&2
      echo "Inspect the staged files and rotate any exposed credentials before retrying." >&2
      return 1
      ;;
    *)
      echo "TruffleHog scan failed (exit $status); install or update TruffleHog and retry." >&2
      return "$status"
      ;;
  esac
}

case "$mode" in
  staged)
    tree="$(git write-tree)"
    archive="$tmp_dir/staged.tar"
    worktree="$tmp_dir/worktree"
    mkdir "$worktree"
    git archive "$tree" >"$archive"
    tar -xf "$archive" -C "$worktree"
    run_scan filesystem "$worktree"
    ;;
  history)
    run_scan git file://.
    ;;
  *)
    echo "Usage: $0 [staged|history]" >&2
    exit 2
    ;;
esac
