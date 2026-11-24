# Retention v3 build handoff (2026-11-24, branch `ret-v3`, from `feat/v101-ask-eval`)

Design: `docs/superpowers/plans/2026-11-24-retention-v3-keep-facts-design.md`. Reviews: `docs/superpowers/reviews/2026-11/codex-retention-r1.md`, `codex-retention-r2.md`.
Scope of this branch: WP1, WP2, WP4, WP5. WP3 (retrieval) is a separate agent, merged here as `a1df00ca`.

## Rulings applied (delegated to the proposed defaults)

| Ruling | What this branch does |
|---|---|
| K1 | Retention never deletes a memory row, edge or text. `ForgetService.expire`, `ForgetExecutor.expire`, `Policy.Retention`, `ExpiredLineage` and every `redact=false` path are deleted. Only an explicit Forget (and Delete all, suspect-description removal) deletes text. |
| K2 | "Short-term" is now "Quick recall" (12 / 24 / 48 / 72 h / 7 d, key `recall_hours`, falls back to `short_hours`; the user's 48 h survives). After the window a SHORT fact (never a caption or cluster summary) loses its TEXT VECTOR only (the demote step); the backfill and the space swap never re-embed it (`NOT_DEMOTED` in `EmbeddingDao`). |
| K3 | Medium-term / Long-term rows removed; Caption / Scene summary rows removed too (they would have no effect: design 2, "ignored until Auto-delete"). The old prefs are still read, harmlessly. |
| K4 | Auto-delete details is NOT in this build. Design placeholder only, default Off. The Memory settings page says "There is no automatic text deletion in this version." No `auto_forget_days` key exists. |
| K5 | One-time notice: a Home banner (`RETENTION_NOTICE`, action "Got it", stored in prefs `retention_notice`). The wording drops the clause "you can turn on Auto-delete in Settings", because Auto-delete is not built (K4): "Clean-up no longer deletes the details of your photos. Forget removes them." |
| K6 | Hard cap = the Storage cap setting (max 2000 MB). Over it, `Retention.runQuota` evicts the oldest eligible photos (described, >= 14 days, not tombstoned) until usage <= 90% of the cap; never text; never a place memory, a mark within the radius, an accepted proposal's source, a place card photo or a pinned Moment's photo. Bounded: chunks of 200, 50 rounds. It runs even while Pause is ON. Unreadable protection set = nothing evicted (fail closed). |
| K7 | NOT done: "Pause clean-up" stays default ON. |
| K8 | `RetentionMigration.runOnce` (first retention run, `canvas_meta` key `retention_v3_done`): purges every `lin:` key, then marks the photos that expired before the marker existed (tombstone + `frame_meta`, no `frame` row). |

## Design notes that differ from the doc

- Photo expiry marker: `rx:f:<frameId>` as designed, plus a second key `rx:t:<capture ms, 13 digits>:<frameId>` so a pin sweep can ask "was a photo of this second expired" with one range read (`ExpiryMarks.anyExpiredBetween`, `MomentDao.momentsAliveOrExpired`). Both are cleared together and pruned with the tombstone (`FrameSweep.pruneWithMarkers`, same transaction) and wiped by Delete all.
- Legacy tombstones with no metadata and no row cannot be told from a Forget and stay "forgotten" (fail closed).
- Quota "importance" exclusions for the photos named by place cards and pins live outside `context.db`: `QuotaProtection` collects them (history `photoIds()`, pins' Moment seconds) and `Retention` takes them as a lambda.
- The legacy orphan pass (frames with no metadata that nothing cites) now follows the Photos setting (it used the old `longDays`); with Photos = Forever it never runs.

## Codex findings, each with its proof

Test names are in `services/orchestration/src/test/java/com/rokid/context/orchestration/forget/RetentionNeverLosesTest.kt` (the golden world, both `forget_v2` modes) unless another file is named.

| # | Finding | Status | Proof |
|---|---|---|---|
| r1-1 | Photo expiry cascades | closed (kept) + marker | `photoAndSkippedPhotoExpiryDropNoMomentSceneMarkAndNotifyNobody`; `GoldenDeleteSetTest.retentionExpirePhoto` (the exact delete set, now with `rx:f:` / `rx:t:`) |
| r1-2 | Legacy-memory expiry drops outputs | structurally impossible (no memory expiry) | `aFactOnALegacyFrameIsKeptWithItsFrameMomentSceneAndMark`; `noMemoryExpiryEntryPointExists` (no `expire` in service or executor, no `Policy.Retention`, no `ExpiredLineage`) |
| r1-3 | Legacy orphan CARRIED cascade | closed (own path) + marker | `legacyOrphanExpiryDoesNotCascadeCarriedProvenanceAndIsMarked` |
| r1-4 | Later Forget loses provenance | structurally impossible (rows and edges never deleted) | `retentionKeepsEveryTextRowAndCitationByteEqual` (10 tables byte-equal, 0 `lin:` keys); `aLaterUserForgetOfThePhotoStillFindsEveryDependent` (3 retention runs, then Forget) |
| r1-5 | Expiry withdraws suggestions | memory half impossible; photo half closed by the marker | `retentionKeepsEveryTextRowAndCitationByteEqual` (no listener told); `anExpiredPhotoKeepsItsSuggestionAndAUserForgetWithdrawsIt` (keyframe, legacy frame and fact-forget cases) |
| r1-6 / 7 / 8 | MomentRepair overwrite / scene resume / bounds | closed, unaffected | `MomentRepairTest` (`repairNeverOverwritesAMomentThatIsRetained`, `sceneGapsAreRepairedIndependentlyAndAFailedScenePassIsResumed`, `chunkedRepairBuildsEverySecondAndEveryHour`) |
| r1-9 | Nudge preview hides missing | closed, unaffected | `PreviewViewModelTest` (nudge with every source expired keeps the section and the count) |
| r2-1 | Sequential expiry loses closure | impossible (no lineage written) | `aLaterUserForgetOfThePhotoStillFindsEveryDependent`; `retentionKeepsEveryTextRowAndCitationByteEqual` (0 `lin:`) |
| r2-2 | Photo lineage seeded from unrelated photo B | impossible (no lineage seeds) | `forgettingPhotoAWithABatchFactNeverRedactsWhatCitesPhotoBsOtherFacts` |
| r2-3 | Forget fact keeps `frame_meta`, read as expired | closed (marker) | `forgottenVersusExpiredIsDataNotAnInferenceFromMetadata`; `theMarkerIsAtomicWithTheTombstoneAndTheRowDelete` (injected failure leaves no marker and no tombstone); `everyUserPathThatTombstonesAPhotoClearsTheMarker` (fact, facts, moment, selection, range, moments; `removeDescriptions` clears nothing; fails on a new unclassified `ForgetService` entry point) |
| r2-4 | Forget after expiry has no capture time | closed | `forgetAfterExpiryStillRunsTheRadiusCleanup` (mark deleted, range told, planner preview names the same mark, legacy frame via the marker's `ts`) |
| r2-5 | Place history `frameId` nulled | closed | `placeHistoryKeepsItsLineageThroughExpiryAndAForgetStillDeletesTheRow`; `PlaceCardHistoryTest.realForgetService_expiryKeepsRowAndLineage_userForgetDeletesRow`; `PlaceCardLogDaoTest`; phone `PlaceGuideHistoryTest.aGoneRowThatKeepsItsFrameIdNeverLoadsAnImage` |
| r2-6 | Legacy expiry (no metadata) read as Forget | closed (marker) | `forgottenVersusExpiredIsDataNotAnInferenceFromMetadata`; `anExpiredPhotoKeepsItsSuggestion...` (legacy record kept) |
| r2-7 | Pause skips the file retry; no bounded storage | closed | `pauseSkipsTheDeletingStepsButNeverTheFileRetry`; `theWorkerEvictsUnderPause`; `quota*` tests |
| r2-8 | Lineage never compacted | impossible (nothing recorded; old keys purged once) | `migrationPurgesLineageAndMarksEarlierExpiries`; `deleteAllWipesTheMarkers`; `pruningATombstoneRemovesItsMarker` |
| r2-9 | Pin sweep clears the link after expiry | closed | `aPinKeepsItsMomentAfterExpiryAndLosesItAfterAForget` (`MomentDao.momentsAliveOrExpired`, used by `PinMomentLinks`) |

Explicit Forget tests are unweakened: `ForgetServiceTest`, `GoldenDeleteSetTest` (all user cases), `userForgetStillRedactsAfterRetentionRan`, the `RetentionWorkerTest.aUserForgetOfTheSameSourceStillCancelsIt` control. The only deleted tests are the ones that tested the removed memory-expiry path (`ForgetServiceTest.expireLeavesDescendantsAlone`, `expireLeavesAcceptedProposalsAlone`, the tier-window cases of `RetentionWorkerTest`, `GoldenDeleteSetTest.retentionExpireFact`, `ForgetPlannerShadowTest.retentionExpireFact`, `PhotoExpiryTest.expireOf...`), each replaced by a "kept" assertion.

## Status

(updated after each WP, see the commit list in the final report)
