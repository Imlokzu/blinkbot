# Daily and night agent follow-ups

Reviewed on 2026-10-05 while adding manual phone pairing. This is the shared
triage index: a proposal in an audit is not a shipped fix. Prior test counts
belong to the linked reports and are not fresh validation of this index.

## Fixes already present

| Work | Status and evidence |
| --- | --- |
| Model picker cache validation, confirmed selections, error recovery and capability filters | Implemented; [night review](night-agent-model-picker-2026-10-01.md). Reuse these behaviors in mobile instead of building another intelligence scale. |
| Forwarded tool-bridge identity | Fixed in `9a331394`; [night review](night-agent-tool-bridge-2026-10-02.md). |
| Dashboard editor draft loss on refresh or delayed save | Fixed in `60dd4c82`; [night review](night-agent-files-editor-2026-10-03.md). |
| Out-of-range benchmark values | Fixed in `a206f388`; model scores and leaders are bounded before display. |
| Missing APK checksum accepted by installer flow | Fixed in `443d1d3a`; [night review](night-agent-mobile-update-2026-10-04.md). This does not imply automatic update publication or installation is complete. |

## Prioritized open bugs

The latest reports contain synthetic reproduction evidence. This triage used
read-only source checks; it ran no vulnerability reproduction scripts or live
authentication probes. Deployment-specific exposure is not established here.

| Priority | Finding | Current status | Completion criterion |
| --- | --- | --- | --- |
| 1 | Owner content on general event streams ([L-01](repository-audit-2026-10-05/REPORT.md)) | Open; separate from the dedicated mobile host's existing route restrictions | Explicit content ownership; permitted screen state remains available without exposing private replies or diagnostics. |
| 1 | Revocation leaves an existing mobile stream subscribed ([L-02](repository-audit-2026-10-05/REPORT.md)) | Open; pairing codes do not themselves fix stream lifetime | Revalidate before delivering later batches; other authorized devices continue normally. |
| 1 | Scheduler does not recover from a failed storage claim ([M-01](repository-audit-2026-10-04/REPORT.md)) | Source still starts only when the scheduler field is null; claim failure remains unhandled | Supervised recovery with bounded retry and visible readiness; never replay work already owned by a runner. |
| 1 | Owner-independent history cache and hidden disk-save failures ([F-01/F-03](repository-audit-2026-10-03/REPORT.md)) | Remain reported open; cache keys still use the raw session ID | Owner-scoped cache; persistence failure reported truthfully without a successful receipt. |
| 2 | Update download allows 100 MiB but Android handoff allows 20 MiB ([L-05](repository-audit-2026-10-05/REPORT.md)) | Source mismatch still present | One update-specific size contract across metadata, transport and installer, preserving digest checks. |
| 2 | Cancelled image publication can outlive its reserved slot ([L-03](repository-audit-2026-10-05/REPORT.md)) | Source still releases the slot after cancellation of the thread await | Track publication until settlement and retain a recoverable result or accounted cleanup. |
| 2 | Rename/delete bypass file save reservations ([L-04](repository-audit-2026-10-05/REPORT.md)) | Open in the latest audit | Shared ordered reservations for source, target and affected descendants. |
| 2 | Scheduled work after chat deletion, blocking SQLite waits, unbounded job retention, late audience filtering ([M-02–M-05](repository-audit-2026-10-04/REPORT.md)) | Open in the latest audit; the mobile delete guard is only one client boundary | Server-side lifecycle policy, responsive async paths, bounded retention and filtering before queue capacity is spent. |

The [October 1 review](repository-review-2026-10-01.md) and
[October 2 audit](repository-audit-2026-10-02/REPORT.md) retain older platform
findings. They are not all revalidated or silently marked fixed by this index.
Recheck the owning source and deployment assumptions before scheduling a fix.

## Proposed product ideas for follow-up

1. **Device privacy and connection details.** Show the device, pairing date,
   last activity, expiry and access scope together. Revocation must close live
   listeners before the UI promises that access has ended.
2. **Recoverable generated media.** Store an owner-scoped receipt so finished
   images survive a disconnect and can be recovered without generating again.
3. **File conflict recovery.** Reuse the existing draft baseline for a
   local/remote comparison, Copy draft and Save as new file. Never discard a
   draft merely to make Retry appear successful.
4. **A complete update manifest.** Include byte size, digest, immutable build
   identity, compatibility and changelog. Distinguish available, downloaded and
   installed versions; use a file-backed verified download for larger packages.
5. **Visible background-work health.** Explain queued, paused, scheduled,
   retryable and terminal states, including whether the runner can accept work.

Manual one-time code pairing is the current implementation task. These items
remain follow-up work unless their status above links to a verified fix.
