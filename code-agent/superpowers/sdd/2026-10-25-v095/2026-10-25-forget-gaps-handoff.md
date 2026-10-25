# Handoff: Forget gaps (GAP-1 fix, residue oracle), 2026-10-25

Branch `feat/forget-gaps` (worktree ~/Desktop/rokid/pca-v095e), from v0.9.5 incl. the design doc `docs/superpowers/specs/2026-10-25-itemref-forget-design.md`. No PROTOCOL_VERSION, TRACE_SCHEMA or Room schema change; Default capture f5782e0d8610 and pinned plan bytes untouched; no new log call.

## User rulings that changed the scope (also in the design doc, section 6)
GAP-1 FIX. GAP-2 ACCEPTED (no unified watch deletion). GAP-3 ACCEPTED residue (aggregated sensor summary too coarse to be personal data).

## Per gap
| Gap | Verdict | Evidence | Fix | Files | Mutation |
|---|---|---|---|---|---|
| GAP-1 export JSON survives Forget | VERIFIED | RED: `forgetFactDeletesTheCachedMemoryExports` and `forgetMomentDeletesTheCachedMemoryExports` failed with the export file still present after `ForgetActions.forgetFact/forgetMoment` | `MemoryExport.deleteCached(context)` removes `proactive-context-memories-*` files (and an unfinished `.part`; the diagnostics export in the same folder is not ours and stays); called from `ForgetActions.apply` (every Forget AND the retention worker, which also goes through apply) and, for a memory edit, from `MemoryDetailViewModel.onTextEdited` wired in `MemoryDetailRoute`. Delete all already removed everything in the folder. | `MemoryExport.kt`, `ForgetActions.kt`, `MemoryDetailViewModel.kt`, `MemoryDetailRoute.kt`; tests `ForgetActionsTest` (2), `MemoryDetailViewModelTest` (1) | (a) apply without `deleteCached`: both ForgetActionsTest tests (and the oracle's fact and photo cases) fail; (b) edit hook not called: `aSavedEditRunsTheEditHookAndARefusedOneDoesNot` fails `expected:<1> but was:<0>` |
| GAP-2 watch | NOT FIXED by ruling (ACCEPTED) | I had implemented and verified it (watch fence, ring clear, range deletion; all RED then green with 4 mutations) in commit 53402423 and reverted it in a6d69eb2 when the ruling arrived; `git show 53402423` is the ready implementation if the ruling changes | none | none | none |
| GAP-3 Scene run rows keep the sensor summary | NOT FIXED by ruling (ACCEPTED). Verified before the ruling: RED tests showed the Scene rows survive a trace tombstone and a range sensor purge; the uncommitted attempt was discarded | none | none | none |

Plan for GAP-3 if the ruling changes: a `tr:<traceId>` lineage token per trace of the Scene's sensor rows (written in `CanvasRunner` next to the `mf:` tokens, collected in `DaoSceneRunSource`), a shared token deleter in `DerivedStaleDao` used by `purgeSensorOfTrace` and `purgeSensorBetween` (the latter must collect the traces of the rows before deleting them), and a `sensorTracesMissing` check in `insertRunItemsFenced` for the race. About 4 h.

## The residue oracle
`services/orchestration/src/test/.../orchestration/forget/ResidueOracleTest.kt`. It seeds one world (photo with JPEG and memory, derived memory with edge, ask citing the photo, two marks, Moments, Scenes, derived summary, sensor rows, cached export, Moment run and Scene run rows with their lineage tokens, watch run rows), runs ONE trigger and scans every table (all columns as text), every lineage token (they are text in `batchId`) and every file under files/ and cache/ for the ids and texts of the forgotten item. Only the `control` allow-list may keep names (forget_tombstone, cleanup_outbox, canvas_helper_cleanup, moment_tombstone, canvas_meta: control data a deletion creates on purpose). Covered triggers (all passing): Forget fact, Forget photo, mark undo (after the next Moment build), retention expiry (memory), Delete all (everything), watch-off (`deleteWatchRuns`). Each test also asserts the oracle SAW the world before (fact test) or that a kept item survives. Oracle mutations: GAP-1 call removed (fact and photo fail); `deleteCanvasOutputsForFrames` removed (photo fails); `deleteRunRowsOfLabels` removed from `deleteLabel` (mark undo fails).
- ACCEPTED residue (`@AcceptedResidue(gap, reason)`, passing, pins the behaviour): GAP-2 (watch run rows survive a photo Forget), GAP-3 (Scene run rows survive a trace tombstone).
- OPEN gaps (`@Ignore("GAP-n ...")`, each verified to FAIL when un-ignored): GAP-4 (Moment-run batch mates; seeded run rows), GAP-9 (`frame_cluster.repFrameIds` keeps the frame id), GAP-13 (new, below).
- NEW finding GAP-13 (low): right after a mark undo, `moment_part.refId` and `object_audit.refId` still hold the undone mark's id (ids only, no text) until the next Moment build (15 min cadence or a build trigger); the oracle's mark-undo test therefore rebuilds first. Fix would be to drop the Moment's mark part in `deleteLabel`'s transaction.

## Codex round (three Important defects, one check), commit after e2e5fa90
1. Export race: `MemoryExport.write` publication (rename + older-export cleanup) and `deleteCached` now share one lock, `deleteCached` lists the folder after taking it, and a generation counter (bumped by every `deleteCached`, captured before the library is read) makes an export that was in flight during a Forget fail with `IOException` (stale) instead of publishing. Seams `testAfterEnumerate` and `testBeforePublish`. Test `anExportPublishedWhileTheCleanupIsBetweenListingAndDeletingNeverSurvives` drives list, publish, delete deterministically. Mutation: no lock and no generation check: that test fails (a lock-only or generation-only variant still passes, either alone closes the race).
2. Oracle scans files of ANY size by streaming (64 KB chunks, needle-length overlap). The world's export is now over 1.2 MB with the forgotten text at the end. Mutation: deleteCached skips exports over 1 MB: Forget fact and Forget photo tests fail.
3. Retention test seeds an export holding the expired memory before the worker runs. Mutation: remove `ForgetActions.apply` from `RetentionWorker`: the retention test fails.
4. Hang check: `:services:orchestration:cleanTestDebugUnitTest :services:orchestration:testDebugUnitTest` run twice, both BUILD SUCCESSFUL (82 s and 66 s), no hang; not reproduced, cause unknown (no jstack taken). The new tests use no unbounded waits (latch wait 10 s, join 10 s).

## Decisions
- Cached exports are deleted on ANY apply (also retention), not only for USER Forgets: regenerable, simplest safe rule. Export file match is by the `proactive-context-memories-` prefix.
- The memory-edit hook lives in the ViewModel (the memory module has no Context); it runs only after a saved edit.
- Oracle is orchestration-module only (needs files and the harness); watch-off is exercised at the DAO level (`deleteWatchRuns`), the hub callback itself is pinned by hardening B's `ContextHubTest`.

## NOT done
GAP-4 through GAP-12 fixes (GAP-4, GAP-9, GAP-13 are only recorded as ignored oracle tests; GAP-5, 6, 7, 8, 10, 11, 12 are not in the oracle); a Delete all trigger for the in-memory watch ring (accepted); oracle cases for the range Forgets (no production caller), photo-expiry retention, and the glasses/helper outboxes; export written concurrently with a Forget (a race, regenerates after).

## Merge steps
In the integration worktree: `git merge --no-ff feat/forget-gaps` (history: GAP-1 fix, GAP-2 commit and its revert, oracle, docs). Likely conflicts: none expected (new test file; small edits in `ForgetActions.kt`, `MemoryExport.kt`, `MemoryDetailViewModel/Route`). Then :services:memory, :services:orchestration, :phone clean tests.
