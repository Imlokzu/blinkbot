# Night agent review: model picker and recent Workbench work

This pass reviewed the existing model picker and exercised the recent document
editor and live agent-writing features. It improves the picker within the
existing design and API contracts. It is not a repository-wide security audit.

## Findings addressed

- Malformed recent-model, sort and catalog-cache JSON could crash chat. Stored
  values are now validated before use; invalid caches trigger a fresh read.
- React Query invalidation could reuse an old HTTP-cached settings response.
  Catalog requests now bypass browser caching. Confirmed model and effort writes
  update shared state and the reload cache before awaiting a fresh catalog.
- Separate hook instances could disagree about pending settings; rapid changes
  could overlap. Keyed mutation state is shared with the composer, and a live
  mutation guard blocks overlapping writes. Async errors survive menu unmounts.
- Failed or empty catalogs could appear to load indefinitely, and rejected
  choices entered recents. Recovery states now offer Retry; only confirmed
  selections are remembered.

The picker also offers All, vision and fast filters, combined with search and
existing sorting. Filters use only reported model capabilities, exclude automatic
routing when a fixed capability is requested, and count unique matching models.
Known unavailable rows cannot write settings. New copy has English and Ukrainian
locale keys; keyboard composition and cancelled effort drags remain safe.

## Verification

- Virtual Bot suite: 934 passed, 6 skipped, 150 subtests passed.
- Isolated committed-source dashboard plus these changes: 97 unit tests passed;
  TypeScript and production build passed. Existing large-chunk warnings remain.
- Model-picker browser regression: malformed storage, filters/search, unavailable
  models, pending and rejected writes, confirmed recents, uncached reads,
  failed-refresh recovery and persisted confirmations; 320/390/768/1440px and
  localized labels. The phone screenshot was visually inspected.
- Existing Workbench browser checks: rich document/table/task round-trip,
  delayed-save retention, keyboard save, internal links, inline drawing reopen,
  phone layout, live agent selection/typing and canvas write guards passed.
- Isolated loopback HTTP smoke: 300 checks passed, including dashboard and screen,
  read-only APIs, all release dashboard files, and memory/workspace traversal
  rejection with HTTP 400. Server lifespan was disabled to avoid background jobs.
- Separate code/spec/security and architectural reviewers found no blocking
  issues; architectural verdict CLEAR. Their persistence finding was fixed and
  independently rechecked. Fable and the configured specialist models were
  unavailable, so supported native agents at maximum effort read the installed
  role instructions. Compiler diagnostics substituted for unavailable LSP tools.

Model mutations and file writes in browser checks are isolated fixtures. No real
OpenClaw model, thinking setting, scheduled job or user conversation was changed.
The Mac was muted and remains muted. Test browsers and server were shut down.
Other agents' pending source, build and staged changes are preserved. Release
assets were built from committed sources plus only this pass's changes.
