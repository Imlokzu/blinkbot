# Blink Wave 1 validation

Base: `24915d9fc053fa3ac8f27975e8ca73918ee38d8e`, branch `main`.
Date: 2026-10-25. Coordinator integrated worker output and independently ran
checks. The owner's bot and OpenClaw were not restarted. Runtime model/provider
routing and the configured bot name are unchanged.

## Delivered

- Pixel-crab identity, preserving the chosen persona, and an optional greeting
  style seed that refers to the existing local-time prompt context.
- Conservative Ukrainian noun-ending retrieval after exact matches; existing
  ranking, linked-note bounds and per-user roots remain intact.
- Day/night ambient mood weights with characterization and guard coverage.
- Clock 1.1.0: shared bot/API alarms, daily/weekdays recurrence, calendar/DST
  handling, pause/resume/cancel, missed notices and occurrence delivery fences.
- Corrected emotion status and a bounded push/iOS readiness audit.
- Reviewed exact public-fixture secret filtering, with staged policy and raw
  Git blob extraction. Existing owner's stricter scan flag remains unstaged.

See `REVIEWS.md`, `lane-D-backend.md`, `clock-design.md` and
`docs/mobile-app/PUSH-AND-IOS-AUDIT.md` for contracts and scope limits.
`NEXT-AGENT.md` is a self-contained prompt for a reviewer with Git-only access.

## Executed evidence

- Backend: **2,125 passed, 489 subtests passed, 8 skipped**. The opt-in
  paid live ASR test used an empty `REGOLO_ASR_API_KEY`. Real speech-model
  startup warmup was replaced with a no-op inside that test process; production
  code is unchanged. This was the working checkout with the owner's existing
  local changes preserved, not a clean-clone hardware/release test.
- Focused timer/alarm checks: 71 passed after review fixes.
- Secret policy/exporter checks: 13 passed with synthetic fixtures, including
  staged/worktree drift, archive attributes, line endings and symlink blobs.
- Actual HTTP smoke: 23 checks passed. Setup persistence, context, timer/alarm
  actions, installed Clock byte equality and screen static assets were checked;
  memory/workspace traversal attempts returned 400. The server stopped and its
  ephemeral loopback port closed. See `smoke.py`.
- Browser: eight 320x240 combinations (uk/en, material/deep, light/dark), with
  screenshots and bounds in `browser-observations.json`. Create, pause, resume,
  delete, failed-save draft retention, literal label rendering and a delayed
  stale-poll response passed without page errors. See `clock-browser.mjs`.
- Strict TruffleHog scans include verified, unverified and unknown results.
  Exact staged tree `7261bc4da57d1703b5fbf84b2a1dacdccb5cb372` passed with
  157 reviewed public fixtures and zero other candidates before the first
  commit. Later feature trees are scanned before their commits too. Normal
  Gitleaks/TruffleHog commit hooks are not skipped.

## Limits and observed failures

The first broad run hit disk exhaustion in temporary test storage and a live
Regolo ASR request returned HTTP 402. Disposable task archives/exports were
removed and the live paid check was disabled. A newly added assertion assumed
an alarm event was the first published message; the full suite's logging bridge
publishes log events too. The assertion now selects the alarm batch explicitly.
Later reruns were interrupted by disk exhaustion or stalled during real model
warmup. After free space recovered and warmup was disabled in the test process,
the full run passed with the result above.

Reproduce the full regression run from `Virtual Bot`:

```sh
VBOT_OFFLINE=1 REGOLO_ASR_API_KEY='' PYTHONPATH="$PWD" .venv/bin/python - <<'PYTEST'
import asr_whisper
import pytest
asr_whisper.warm = lambda: None
raise SystemExit(pytest.main(['tests/', '-q', '--tb=short']))
PYTEST
```

Prompt assembly and simulation tests do not prove conversational taste, physical
microphone/speaker behavior, Pi performance or guaranteed audio during a network
outage. One-shot reminders keep their existing delivery contract. Recurring
alarms persist missed notices and do not replay old sound bursts. Phone fallback,
APNs/FCM implementation, alarm music and hardware work remain deferred.

At 22:12 UTC, ZCode Start Plan reported 295,833,049/300M Flash and
192,808,627/200M GLM-5.3 tokens remaining, expiring October 26 at 01:00 UTC
(02:00 Europe/Berlin). Three concurrent requests worked; a fourth was rejected.
This allowance is shared with the owner's tasks. RPM was not verified. The model
picker advertised 1M context, while task snapshots reported 200k.

ZCode's C worker completed mood changes. A/B/D streams repeatedly failed; an
additional background task submission later failed with an internal foreign-key
constraint. No credentials or app databases were modified to bypass that. The
working free OpenCode GLM worker handled remaining implementation/review tasks.
Those free-model calls should not be counted as ZCode promotional consumption.

## Publication status

Changes are published in separate logical commits on `main` at
https://github.com/Imlokzu/blinkbot. Strict scans and normal commit hooks pass
before each push. The full staged tree IDs are retained in the coordinator's
private publication ledger; no raw reports or matched values are committed.

Only the Wave 1 retrieval hunks of memory.py were staged. The owner's existing
profile-preservation fix, configuration, dashboard/mobile changes, stricter
scan flag and corresponding introductory documentation remain separate.
This task's disposable scan archive, scan exports and completed pytest temporary
folders were removed; no unrelated files or running services were removed.


Feature commits: `a27b561d` (scanner), `fb1696c0` (identity), `de017c72`
(retrieval), `08092f9d` (moods/status), `83e1fdb8` (alarms), and `6c604763`
(mobile audit). Evidence and the Git-only handoff follow in a documentation
commit. The earlier optional follow-up timeouts are disclosed in REVIEWS.md.
