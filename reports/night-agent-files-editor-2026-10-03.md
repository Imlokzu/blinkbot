# Night agent review: workspace editor draft safety

The 2026-10-03 repository audit reproduced two data-loss races in the dashboard
Files panel:

- a same-file refresh replaced unsaved text and cleared its dirty marker;
- a delayed save response cleared the dirty marker after newer typing, including
  a switch away from and back to the same file.

The editor now keeps a server baseline and draft state per selected path. Clean
refreshes follow the server. Dirty refreshes preserve the editor text, update
the baseline, and show a localized external-change notice. Save completion only
clears the dirty state when the current path and draft still match the submitted
snapshot; otherwise it keeps the current text dirty. Reload is disabled while a
save is pending. A successful save cancels matching stale reads and updates the
React Query file cache before invalidating the directory listing.

Validation:

- `fileDraft.test.mjs`: 5 tests, including same-file refresh, delayed save,
  old-file completion, and A→B→A switching.
- Dashboard: 206 unit tests passed and TypeScript typecheck passed.
- Isolated Vite production build passed with the current source and generated
  108-entry PWA asset graph; only existing large-chunk warnings remain.
- Virtual Bot with `VBOT_OFFLINE=1`: 1,129 passed, 7 skipped, 178 subtests.
  The non-offline run was stopped after an external/network-dependent stall at
  34 completed tests; the offline run completed successfully.
- Independent adversarial reviewer approved the scoped fix. No browser-level
  Files panel fixture exists yet; the reducer tests cover the acceptance races.
- The Mac was muted before tests. Other agents' wallpaper, appearance, mobile,
  and generated asset changes remain outside this commit.
