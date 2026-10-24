Read-only; no files changed.

1. **Important — c5 #2 still permits trace notes outside the fenced store.**  
   **File:** [00-core-plan.md:49](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md:49), WP8b and WP9.

   The new `ti<N>` status rows satisfy the existing `CanvasDao.upsertScoreFenced()` parent-item check. `CanvasBackendImpl.score()` can therefore store a normal user annotation in legacy `canvas_score`, without trace provenance. Trace deletion removes the new result/source rows but leaves that annotation; the legacy fence checks frame tombstones against `ti<N>`. This contradicts the new storage policy and WP9’s `canvas_score` deletion assertion.

   **Exact edit:** Have WP8b explicitly refuse scoring/notes for trace runs, permit its corresponding `CanvasBackendImpl.score()` edit, and return no trace scores. Have WP9 hide trace score controls and test `PUT runs/:id/scores` through the production route chain before and after Forget, asserting no `canvas_score` insertion. This closes the gap without changing WP3’s frozen v8 schema.

2. **Minor — The authoritative merge order disagrees with the regenerated schedule.**  
   **File:** [00-core-plan.md:131](/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md:131).

   Section 7 requires `WP3, WP11, WP12, WP4, WP5, WP6`; section 10 instead merges WP11 at 8.25 before WP3 at 8.75, and WP5 at 11.75 before WP4 at 12.0.

   **Exact edit:** Change the `v0.9.2` authoritative order to `WP11, WP3, WP12, WP5, WP4, WP6`. These swaps respect dependencies and preserve the stated schedule.

The remaining c5 resolutions check out: storage-backed item identity and memory-owned provenance DTO; captured provenance accepted transactionally; result retrieval and run delete/prune ownership; route-shadowing correction, estimate/start binding and trace executor dispatch; and Python-only fixtures with NL contracts deferred. No other blocking module-direction or v8-freeze contradiction found.

Arithmetic independently reconciles: **53.0 WPs = 38.0 + 3.0 + 12.0; planned 69.25; reserve 6.75; cap 76.0; CP1 6.75/8.1 trigger; CP2 38.75/46.5 trigger; CP3 83.1 trigger subject to cap; burn 63.5; escalation 69.85; remaining 5.75; dependency path 30.5; scheduled 31.5; wall-clock 45.0; worktrees 18.** Backlog subtotals also reconcile.

**Safe to start:** PF, WP1 and WP10 now; WP2–WP7, WP8a and WP11–WP13 after their declared prerequisites. Hold WP8b/WP9’s trace result integration until finding 1 is settled.

VERDICT: NOT READY