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

## Status (2026-11-24)

- WP1 (no text expiry, demotion), WP2 (expiry marker, photo-side fixes), WP4 (pause / quota split, bounded quota eviction) and WP5 (settings, copy, one-time notice) are implemented on `ret-v3`. WP3 (retrieval) was merged from `feat/v101-ask-eval` (`a1df00ca`) and `RecallWindow.of` now reads `recallHours`.
- The four WPs share `RetentionWorker`, `ForgetService` and `ForgetExecutor`, so they were committed together (see the log of `ret-v3`), not one commit per WP.
- Full unit suites green on this branch: `:services:memory:testDebugUnitTest`, `:services:orchestration:testDebugUnitTest`, `:phone:testDebugUnitTest` (rc=0). No emulator, adb or install.
- Pause clean-up stays default ON (K7). It is flipped by a later switch after the device test.

## Not done

- K4 Auto-delete details (design placeholder only; no key, no UI, default Off). The notice and the settings copy do not promise it.
- K7 (Pause default OFF).
- The device test of the design (section 8): restore the 24 Nov dump on the emulator, advance the clock, run retention, check 0 memories deleted, 0 redactions, 0 `lin:` keys, about 3.4k demoted, Moments for 23-24 Nov present. Then the same on the phone after a fresh dump.
- `RetrievalScaleBench` (instrumented).
- The memory detail screen still labels a fact's tier "Short-term" (the tier is now a recall hint; the label was not renamed).
- `ProvenanceGate`'s "marker read failed => fail closed" branch has no injected-failure test (the DAO cannot be faulted from a unit test without a fake).
- A late text vector written for a fact that is already past the window (an embedding job that raced the demotion) is not refused by `EmbeddingDao.putChecked`; the next retention run demotes it again.

## Device-test checklist additions

- `canvas_meta`: 0 rows with key `lin:%` after the first retention run; `retention_v3_done` present; the `rx:f:` count equals the photos expired after the install plus the earlier ones with metadata and no `frame` row.
- Settings, Memory: one "Quick recall" row (12 / 24 / 48 / 72 h / 7 d, 48 h kept), no Medium / Long / Captions / Scene summaries rows, a "Storage cap" row with 2000 MB, the notes of section 2. Home shows the one-time banner once.
- With Pause ON and the cap exceeded: photos are evicted oldest first (log line `quota evicted quotaEvicted=N`), text untouched.

## Round 2 (branch `ret-v3-r2`, Codex v3 r1: `docs/superpowers/reviews/2026-11/codex-retention-v3-r1.md`, 9 P1, 3 P2, 2 P3)

Interim safety (own commit): with Pause clean-up ON the worker deletes NOTHING, quota eviction included; only the file-retry sweep of already-queued files runs. Ruling K6 changed: quota runs only while Pause is OFF. Test `theWorkerEvictsNothingUnderPauseAndEvictsWhenPauseIsOff`.

| # | Fix | Test (RetentionNeverLosesTest unless noted) |
|---|---|---|
| P1-1 | `expirePhotos` / `expireOrphanFrames` (service, executor) and the planner refuse a photo that already carries a tombstone, re-checked inside the expiry's own transaction (so a quota selection made before a concurrent Forget is safe) | `expiryNeverWritesTheMarkerOverAUserTombstone` |
| P1-2 | Migration backfills only PROVEN expiries (tombstone + metadata + no row + a memory / non-CARRIED source still cites it); ambiguous tombstones stay forgotten | `migrationMarksOnlyProvenExpiriesAndKeepsTheLegacyLineage` |
| P1-3 | `lin:` keys are no longer purged. `ExpiredLineage` (reach / consume / prune) is restored read-side: service, executor and planner cascades consult it in a user Forget (cited set + live derived memories invalidated), a Forget consumes what it used, the worker prunes keys with no surviving dependent (unredacted answer / nudge, Moment holding, live derived memory). Never written by this build | `aUserForgetStillReachesWhatSurvivedALegacyExpiryThroughTheKeptLineage`, `theLegacyLineageIsDroppedOnlyWhenNoDependentSurvives` |
| P1-4 | `MomentForget.discover` includes expiry-marker ids by capture time (`ExpiryMarks.idsBetween`) and, for the scoped Forget, the expired FRAME parts of projected Moments; preview, hash, planner and executor share it | `aScopedForgetReachesAnExpiredLegacyPhotoThroughItsMarker` |
| P1-5 | `InvalidMomentPurge`: expiry-backed Moments (marker, or tombstone + metadata + citing memory) are never invalid; the orphan-memory Forget at startup is REMOVED. Preserved: the 19 Nov cleanup of Moments with no timestamp / dead photo (Moments only, tombstoned second, Scenes shrink). Dropped: deleting memories whose source frames are gone | `InvalidMomentPurgeTest.noMemoryIsDeletedAndNothingIsRedactedByTheStartupPurge`, `aMomentOfAnExpiredPhotoIsNeverClassifiedInvalid` (marker-count log now `memories=0`) |
| P1-6 | `purgeLowSignalMemories` and `purgeNonRokidMemories` deleted; replaced by read-only `lowSignalMemoryIds` / `nonPipelineMemoryIds`; Reprocess and MainActivity no longer delete text; Diagnostics chip says "Deletes nothing: old details stay until you Forget them" | `PipelineRecordsTest` (`nonPipelineMemoriesAreListedAndNeverDeleted`, `lowSignalMemoriesAreListedAndNeverDeleted`, phase 5 / orphan tests) |
| P1-7 | `QuotaProtection.frames` throws when the pin store is corrupt, so quota evicts nothing | `corruptPinPreferencesFailTheProtectionReadAndQuotaEvictsNothing` |
| P1-8 | A protected set above 20,000 ids = fail closed (nothing evicted, counts-only line); no silent `take` | `aProtectionSetAboveTheBindLimitEvictsNothing` |
| P1-9 | `quotaPhotosDue` resolves accepted-proposal citations through `memory.frameId` OR any `frame_source` row | `anAcceptedProposalProtectsTheSecondaryPhotosOfABatchFact` |
| P2-10 | `EmbeddingDao.putChecked(v, size, recallSince)` re-checks the demotion rule inside its transaction; `EmbeddingSpaces.put` passes the live window | `EmbeddingFenceTest.putCheckedRefusesAVectorForADemotedFactButNotForOtherRows` |
| P2-11 | Time-window questions run the windowed lexical search in SEMANTIC mode and add the facts that have no vector | `DemotedRetrievalTest.aTimeWindowQuestionReachesDemotedFactsInSemanticModeToo` |
| P2-12 | `FrameSweep.pruneWithMarkers` keeps tombstone and markers of a photo a stored Moment still holds; the sweep also keeps the markers of the second of a pinned Moment (unreadable pins = no prune) | `aRetainedMomentKeepsTheExpiryEvidenceOfItsPhoto` (pin side is code only, no test) |
| P3-13 | Diagnostics Pause copy, `expiredSourcesText` ("n older detail(s) no longer available; the summary is kept"), memory detail tier label "Quick recall" | `MemoryDetailTest.aShortFactIsLabelledQuickRecall`, `PreviewScreenTest` |
| P3-14 | `ForgetPolicyConformanceTest.aUserForgetOfAFactPlansTheDerivedMemoryAsRebuild` restores the user REBUILD assertion (both modes) | same |

Not done: the pin half of P2-12 has no test (needs a pin store fixture); no device test; the quota concurrency is covered by the in-transaction tombstone check, not by an interleaving test.

## Round 3 (branch `ret-v3-r3`, Codex v3 r2: `docs/superpowers/reviews/2026-11/codex-retention-v3-r2.md`, 6 P1)

Fact base: the installed phone build (19 Nov, 18c1b76a) never ran a branch that wrote `lin:` lineage keys or `rx:` markers. On every real install there are no `lin:` keys and no `rx:` markers; the only legacy state is plain tombstones in `forget_tombstone`. Round 3 is therefore a simplification, not more machinery.

| Finding | Fix | Proof |
|---|---|---|
| P1-2, P1-3, P1-4 (lineage prune, one-direction reach, seeding from unrelated photos) | Removed structurally: `ExpiredLineage` is deleted, with every read, seed, consume and prune of `lin:` keys in `ForgetService`, `ForgetExecutor`, `ForgetPlanner`, `RetentionWorker` and the `lineageFrames` parameter. The migration only purges stray `lin:` keys once (expected 0, counts-only log `strayLineagePurged=N`). Delete all still wipes `lin:` and `rx:` keys. | `noMemoryExpiryEntryPointExists` (class gone), `migrationMarksNothingAndAPreExistingTombstoneIsNeverReadAsExpired`, `deleteAllWipesTheMarkers`; the explicit-Forget tests are unchanged |
| P1-1 (backfill turns a forgotten photo into expired) | Removed: `RetentionMigration` writes no marker and `ForgetDao.legacyExpiredFrameIds` is deleted. A tombstone without an `rx:` marker is FORGOTTEN (ambiguous = forgotten, fail closed). Only a new expiry of this build carries a marker, written in the same transaction as its tombstone. | same migration test (user Forget of one description of f1, a metadata + no row + cited tombstone, a metadata-only tombstone and a bare tombstone are all forgotten, zero `rx:` keys); `expiryNeverWritesTheMarkerOverAUserTombstone`, `forgottenVersusExpiredIsDataNotAnInferenceFromMetadata` |
| P1-5 (20,000 silent cap in `ExpiryMarks.idsBetween`) | The read pages by key until a short page: no cap, no truncation; the page size is an injectable parameter (default 1,000). Range and scoped Forget (`MomentForget.discover`) and the pin sweep use it. | `theMarkerRangeReadReturnsEveryMarkerAboveTheOldCap` (20,150 markers, page sizes 997, 1,000, 4,000 and 20,150 return the full set, and the two markers just outside the range are excluded) |
| P1-6 (retry with fewer facts deletes trailing facts) | NOT changed: pre-existing since 92e8a943 (15 Oct), present in the installed build. Logged as P2 in `open-backlog-2026-11-21.md` with the scenario and the proposed fix (keep facts, mark superseded). | n/a |

Consequence: a photo that old builds expired and whose descriptions survive is, after this build, treated as forgotten (never as expired). That only ever makes a later Forget broader, never narrower. LogContentScan SAFE_NAMES: `lineageKept`, `lineageDropped` replaced by `strayLineagePurged`.
