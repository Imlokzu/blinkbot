# Git-only Blink review prompt

You are an independent reviewer of Blink Wave 1. Everything you need is in Git.
Clone https://github.com/Imlokzu/blinkbot.git, use `main`, and report the actual
HEAD SHA before starting. GitHub confirmed the rename from Imlokzu/claude-bot
while the coordinator pushed the changes.
The inspected base was `24915d9fc053fa3ac8f27975e8ca73918ee38d8e`.

Read `AGENTS.md`, `CLAUDE.md`, and `reports/wave1-2026-10-25/VALIDATION.md` first.
Wave 1 is already implemented; do not repeat implementation or expand scope.
You cannot access the owner's local files, credentials, memory, running bot,
ZCode account, or services. Treat committed reports as evidence with stated
limits, never as proof of a physical-device test.

Review these boundaries and return concrete bugs with file/line, reproducer,
impact and a small proposed fix:

- `Virtual Bot/brains.py`: pixel-crab identity and optional greeting style seed;
  the configured temperament/custom persona wins, name/profile are preserved,
  prompt-part order and runtime model/provider routing are unchanged.
- `Virtual Bot/memory.py`: conservative Ukrainian noun-ending retrieval that
  fills slots after exact matches; verify root/link isolation and ranking.
  Short names such as Mia's Ukrainian inflections are deliberately unsupported.
- `Virtual Bot/vision_watcher.py`: day/night weights over the same five moods;
  existing guards, durations and spontaneous flag remain authoritative.
- `Virtual Bot/screen_widgets.py`, `tools/timer_tools.py`, `main.py`:
  calendar recurrence, shared tool/API path, atomic advance/notice persistence,
  pause/resume/cancel and graceful loop cleanup. Read clock-design.md and
  lane-D-backend.md for the full delivery and timezone contract.
- `Virtual Bot/static/screen/{screen.js,timer-delivery.js,i18n.js}` and
  `store/packages/clock/`: occurrence fences, stale requests, notice clearing,
  localization, safe text rendering and 320x240 controls.
- `scripts/filter_trufflehog_public_fixtures.py`: exact reviewed detector/full
  digest/path matching, verified/unknown blocking and staged-policy consistency.
  Never print raw matched values or suggest bypassing secret checks.

Useful checks from `Virtual Bot`:
`VBOT_OFFLINE=1 REGOLO_ASR_API_KEY='' PYTHONPATH="$PWD" .venv/bin/pytest tests/ -q`
The empty ASR variable disables the opt-in paid live test. Use your own clean
config, never the owner's. The smoke and browser fixtures under this report
folder isolate services and state; inspect their requirements before running.
The browser fixture uses a dedicated headless session and a loopback server.

Do not recursively delegate or mutate Git/services. Report first, with tested
versus inferred claims separated. The coordinator owns integration. If asked to
implement a later fix, agree explicit file ownership, preserve others' changes,
and follow repository commit/review rules.

Deferred: phone fallback alarms, APNs/FCM, iOS upgrades, music alarms, physical
hardware, office changes, deeper memory architecture, voice turn-taking and
rewrites. The push/iOS audit is `docs/mobile-app/PUSH-AND-IOS-AUDIT.md`.
