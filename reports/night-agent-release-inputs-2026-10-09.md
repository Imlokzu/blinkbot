# Night-agent release input hardening — 2026-10-09

Starting revision: `3efe3aa1`. This pass reviewed recent mobile/file-workspace
work and repaired the still-open R-01 finding from the October 7 repository
audit. Unrelated work in the shared tree was preserved.

## Reproduction and repair

The original workflow's exact `NOTES="${{ inputs.changelog }}"` assignment,
rendered with a synthetic `$(printf fixture > "$AUDIT_MARKER")`, created a
marker inside a temporary directory. This proves Bash execution after expression
substitution; it is not evidence of an exploited GitHub run. The workflow only
accepts mobile tags and manual dispatch, so influence over those inputs is
required. No release or signing action was used for the reproduction.

The workflow now passes expressions through step environment variables. Its
inline scripts contain no expressions. Version names accept three decimal
components with an optional `-beta` suffix; stable and beta are the only
channels. Version codes accept decimal integers, must exceed the checked-out
Gradle code, and are capped at 2,100,000,000. Empty or zero codes increment the
current code. Invalid inputs fail before Gradle edits or step-output writes.
The manual form now defaults to zero instead of the stale code 20.

Publication independently validates the version/channel and checks for a
nonempty APK before invoking `gh`. Notes and the push-event commit-message
fallback stay literal, including quotes, backticks, command substitutions,
Unicode and trailing newlines. `printf '%s'` writes a temporary notes file,
`gh --notes-file` consumes it, and an exit trap removes it on success or failure.
The argument array always contains the repository option, preserving compatibility
with macOS Bash 3.2 under `set -u`.

This follows [GitHub's script-injection guidance](https://docs.github.com/en/actions/concepts/security/script-injections).
The notes-file option is documented in the [GitHub CLI manual](https://cli.github.com/manual/gh_release_create),
and the version-code cap follows [Android's versioning guidance](https://developer.android.com/studio/publish/versioning).

## Fresh validation

- **52 release-workflow tests passed** on the final code, with zero skips.
  The tests parse the actual YAML and execute its actual Bash/Python steps in
  temporary workspaces. Each release subprocess receives only fixture/system
  variables, a fixture Python executable and a recording fake `gh`, without a
  token. No GitHub API or installable APK is involved.
- **1,719 backend tests passed, eight skipped, 178 subtests passed**, with five
  existing deprecation warnings. The full run began before review added the
  dispatch-default test; the final 52-test focused run covers that correction.
  The guarded runner clears inherited provider/updater settings, blocks
  repository dotenv and owner-data reads, uses synthetic configuration and
  redirects runtime paths to temporary storage with `VBOT_OFFLINE=1`.
- The recently added file editor passed **23 unit tests**, TypeScript, and
  **22 packaged browser scenarios**. Coverage includes Markdown/source changes,
  queued drafts under AI-write locks, flush acknowledgement, detached callbacks,
  drawing/Mermaid export, local images, phone layout and packaged fonts. The
  fixture found no external resource loads, CSP violations or uncaught errors.
  It uses an empty browser configuration, fresh namespace/session, loopback-only
  allowlist and synthetic native bridge; its browser/server were closed.
- YAML parsing, Python AST checks and `bash -n` passed. The first focused run
  had six failures caused by this patch's empty-array expansion under Bash 3.2
  with `set -u`; the nonempty argument array fixes the cause. All final focused
  and independent reruns passed.

Re-run the focused regression from `Virtual Bot/`:

```bash
PYTHONPATH="$PWD" .venv/bin/pytest tests/test_mobile_release_workflow.py -q
```

Re-run the editor checks from `mobile-app/editor-web/` with the Mac muted and an
isolated browser configuration/namespace:

```bash
npm test
npm run typecheck
npm run test:browser
```

## Independent review and post-review HTTP smoke

The configured code-reviewer and architect models failed with unsupported-model
errors. Fable was unavailable. Two separate supported native agents inherited
the session model and reviewed at maximum effort using the installed role
instructions. This is native independent review, not Fable certification.

Code/spec/security verdict: **APPROVE**. Architectural status: **CLEAR** for
R-01. Each reviewer independently ran the final 52 tests and verified YAML,
Python AST and Bash syntax. LSP/ast-grep were unavailable; those diagnostics and
executable probes were explicitly authorized as the fallback. Architecture
review identified the stale manual default, which was corrected and tested.
The code lane also checked connected stable/beta resolve/publication flows with
flag-like, CRLF and heredoc-like notes and exact APK/repository arguments.

After review, the actual `main.app` ran temporarily on `127.0.0.1:18139` with
lifespan disabled, synthetic configuration, temporary runtime paths and blocked
owner dotenv/data and external network access. **28 curl checks passed**:
app/dashboard/screen/status/auth/inventory routes and directly referenced assets
returned 200; memory, workspace and mobile-preview traversal attempts returned
400; the synthetic mobile host returned the expected 401/404 boundaries.
The server exited with code zero and the port was confirmed closed.

## Limits and separate follow-ups

No GitHub Actions run, real release publication, APK build/install, signing
change or production-service restart was performed. Unit/browser checks exercise
the working source and packaged editor under fixture boundaries, including
unrelated existing work; they do not certify a clean mobile release, physical
device, iOS runtime, full application lifespan or public deployment.

The earlier audit's signing-identity uncertainty, APK asset label/name mismatch,
pull-script embedded-code handling and delete-before-create publication risk
remain separate follow-ups. This repair closes R-01 only. Existing release
replacement behavior is preserved, including its failure risk after deletion.
The Mac remains muted.
