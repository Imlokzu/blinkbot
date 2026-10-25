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
  policy_root="$root"
  if [ "$1" = "filesystem" ]; then
    policy_root="$2"
  fi
  if [ ! -f "$policy_root/.trufflehogignore" ]; then
    echo "TruffleHog scan policy is missing from the scanned snapshot." >&2
    return 1
  fi
  set +e
  trufflehog "$@" \
    --exclude-paths "$policy_root/.trufflehogignore" \
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
      scan_root="$root"
      if [ "$1" = "filesystem" ]; then
        scan_root="$2"
      fi
      if python3 "$policy_root/scripts/filter_trufflehog_public_fixtures.py" "$report" "$scan_root"; then
        return 0
      fi
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
    worktree="$tmp_dir/worktree"
    mkdir "$worktree"
    # Read exact blob bytes: git archive honors export-ignore/export-subst.
    exporter="$tmp_dir/export-tree.py"
    git show "$tree:scripts/export_git_tree_for_scan.py" >"$exporter"
    python3 "$exporter" "$tree" "$worktree"
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
