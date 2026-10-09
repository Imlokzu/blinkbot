# Repairs for the October 8–9 repository audits

This change fixes the nine reproduced findings in
`repository-audit-2026-10-08/REPORT.md` and
`repository-audit-2026-10-09/REPORT.md`. The proposed new product features in
those reports remain outside this task.

| Finding | Repair |
| --- | --- |
| S-01 | Unknown installed apps retain the sandbox. Only a server-owned receipt matching all installed built-in bytes grants an exception. Built-in IDs remain reserved after removal; imports cannot change package type. Removal cleans both runtime kinds. |
| S-02 | Source swaps have an atomic recovery marker, rollback on promotion failure, and catalog recovery before directory enumeration. Failed rollback preserves old bytes and both errors; unrecorded backups are preserved. |
| S-03 | Invalid ZIP metadata, CRCs, encodings and compression failures return `bad_archive` before installation. Filesystem failures remain operational errors. |
| S-04 | Export enforces the unpacked budget before allocation and while reading opened descriptors. Every export passes the receiver's archive/manifest/entry validation. |
| D-01 | Encoding-aware parser callbacks reject DOCX DTD/entity declarations before XML expansion, including UTF-16 LE/BE. |
| U-01 | Valid free-text questions render an answer input. Questions without any usable answer control are rejected. |
| U-02 | Submission returns an explicit persisted-message receipt. Busy/failed/stale answers stay editable; no originating work is cancelled. Duplicate clicks are blocked. |
| U-03 | Checklist changes use owner/conversation/call-scoped SQLite state with revision checking. Accepted answers are linked atomically to the real user message in the transcript; lost acknowledgments cannot create duplicate answer turns. |
| U-04 | Live events and history share a versioned, bounded UI descriptor with option/item IDs. Transport wrappers preserve it; redaction occurs before publication. Legacy replay mirrors handler limits and Python whitespace/code-point semantics. |

Independent review also found and closed noncanonical package/session-ID
aliases, catalog recovery ordering, route ordering, tool namespace mismatches,
receipt redaction/Unicode budget differences, and old overlay callbacks erasing
replacement questions.

## Validation boundaries

Checks use synthetic conversations, packages, uploads and configuration, with
repository dotenv and owner profile/runtime reads blocked. The Mac is muted.
The browser regression compiles the actual card, action hook, chat runtime and
extracted message bridge, with real React/TanStack Query and mocked transports.
Backend tests exercise real routes, SQLite revision races, owner/kind isolation,
concurrent answer retries, streamed receipts and archive failures.

The dashboard build freezes committed source plus this task's changes. Two
pre-existing untracked integration files already imported by committed Settings
are included as build support. They remain outside this task; the earlier
clean-checkout integration-source limitation remains.

Two independent supported native agents apply the installed code-reviewer and
architect instructions at maximum effort. Fable and the installed specialist
model are unavailable; no Fable certification is claimed. AST/compile checks,
TypeScript, actual browser regressions and pytest supply diagnostic evidence.

## Fresh results

- Full sanitized backend suite: **1,668 passed, eight skipped, 178 subtests**.
  The earlier run had one harness-only failure because the synthetic default
  config supplied one Omni model while an existing test requires two; a
  synthetic second model fixed the harness, without product changes.
- Dashboard: **231 unit tests**, TypeScript and isolated production build
  passed. The frozen build matches this task's current UI byte for byte.
- Actual React browser regressions passed: free text, inactive previews,
  busy/rejected/stale and duplicate answers, persisted answer restoration,
  checklist remounts/failed saves and overlay question replacement.
- Actual-app smoke: **35 HTTP/static checks**, including 14 entry assets,
  traversal 400, orphan sandbox CSP, noncanonical IDs, tick persistence,
  revision conflict 409, real answer receipt, duplicate answer 409 and
  corrupted archive 400. Lifespan was disabled and all state was temporary.

Normal Gitleaks and TruffleHog commit hooks passed for the scoped repairs.
Other agents' unfinished tracked and untracked changes are preserved.

## Publication and review

Both independent reviewers returned **APPROVE / CLEAR** for all nine repairs.
Additional redaction boundary probes verified limits and live/history parity.
The source fixes are pushed on `main`; the final chat source is `afc2ebe3`.
Dashboard release `faec658b` was published through the guarded builder;
all 155 newly published resources returned HTTP 200, along with the entry,
service worker, 14 direct assets and status/screen routes.

The registered local backend was reloaded after verifying zero running/stopping
mobile jobs. The new interaction route is loaded, memory/workspace traversal
returns 400, and the mobile API host still requires authentication with 401.
The tunnel and credential settings were unchanged. Validation made no paid
provider requests or real chat submissions. Test browsers and the temporary
smoke server were shut down; the requested production service remains running.
