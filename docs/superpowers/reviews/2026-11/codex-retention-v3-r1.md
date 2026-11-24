VERDICT: NOT READY

Paths below are relative to `android-companion/`: **M** = `services/memory/src/main/java/com/rokid/context/memory`; **O** = `services/orchestration/src/main/java/com/rokid/context`; **P** = `phone/src/main/java/com/rokid/context`.

1. [P1] M/forget/ForgetService.kt:163 - Forget one fact while another still describes its photo; deadline expiry subsequently writes `rx:` over the user tombstone, making `forgottenFrames` return “expired”; quota can do this after concurrent selection because `DataOpGate.kt:157` admits concurrent deletes - Refuse existing tombstones inside both expiry implementations and mirror that check in the planner.
2. [P1] O/orchestration/forget/RetentionMigration.kt:28 - A previously expired photo subsequently forgotten through a fact still has metadata and no frame; migration re-adds its cleared expiry marker, allowing forgotten sources to remain eligible for suggestions - Backfill only proven expiries; ambiguous tombstones must remain forgotten.
3. [P1] O/orchestration/forget/RetentionMigration.kt:22 - Upgrade after an earlier build expired fact A: deleting `lin:f:`/`lin:u:`/`lin:d:` destroys the remaining connection to A-citing answers and proposals; later photo Forget cannot reach them - Preserve and consult legacy lineage until its surviving dependents are gone.
4. [P1] M/forget/MomentForget.kt:188 - Scoped Forget discovers photos only through `frame`/`frame_meta`; an expired legacy photo has neither, and bounded discovery skips projection sources at :192, leaving its `rx:` marker and photo-citing suggestions/history intact - Include expiry-marker IDs by capture time in discovery, preview and execution.
5. [P1] O/canvas/moment/InvalidMomentPurge.kt:55 - When its one-time marker is absent, startup classifies retained photo-less Moments as invalid, removes them and shrinks Scenes; :69 also runs orphan facts through Forget, redacting dependents - Remove this implicit destructive migration or make retained expiry-backed content exempt.
6. [P1] O/orchestration/PipelineRecords.kt:224 - Diagnostics “Reprocess old photos” calls `purgeLowSignalMemories` (`OrchestrationScheduler.kt:203`), deleting matching text and provenance without a Forget; startup also deletes non-pipeline IDs (`MainActivity.kt:42` → :194) - Remove automatic text purges and require a separately previewed explicit Forget.
7. [P1] O/orchestration/forget/QuotaProtection.kt:18 - Unreadable/corrupt pin preferences return an empty list (`M/place/UserPlace.kt:75`), so protection collection succeeds and quota can delete pinned photos - Check the store’s corruption result and abort eviction on unreadable pins.
8. [P1] O/orchestration/forget/RetentionWorker.kt:185 - More than 20,000 protected photo IDs silently loses protection for the remainder through `take(MAX_PROTECT)` - Use complete chunked exclusion or fail closed when the protection set exceeds capacity.
9. [P1] M/db/ForgetDao.kt:291 - An accepted proposal cites a batch fact whose secondary photo appears only in `frame_source`; quota checks only `memory.frameId`, so that protected source photo is evicted - Resolve proposal memory citations through complete photo provenance.
10. [P2] M/db/EmbeddingDao.kt:104 - An embedding selected before demotion can commit afterward because `putChecked` checks only row liveness, restoring the vector and everyday retrieval until another cleanup run - Re-check recall eligibility atomically at vector insertion.
11. [P2] M/retrieval/MemoryRetriever.kt:45 - With SEMANTIC mode available, time-window questions skip lexical retrieval; demoted facts have no vectors and remain unreachable (`RagCore.kt:310` uses this path) - Include windowed lexical retrieval for demoted facts regardless of semantic mode.
12. [P2] O/orchestration/forget/FrameSweep.kt:56 - Eight days after expiry, an uncited photo’s tombstone and markers are pruned despite a retained Moment/pin; `MomentDao.kt:123` then reports it dead and the next pin sweep clears its link - Preserve expiry evidence while retained Moment/pin dependencies require it.
13. [P3] P/phone/ui/settings/Diagnostics.kt:442 - Pause copy promises “expires nothing” and says details are removed when resumed, although quota runs under Pause and text is retained; `HomeScreen.kt:73` still announces expired short-term details and `MemoryDetailModel.kt:58` says “Short-term” - Update all three surfaces to v3 semantics.
14. [P3] services/memory/src/test/java/com/rokid/context/memory/forget/lifecycle/ForgetPolicyConformanceTest.kt:55 - Replacing `retentionDoesNotRebuildDescendantsAndTheUserCascadeDoes` removes its explicit assertion that user Forget plans descendant `mD` as REBUILD - Retain that user-plan assertion independently of removed retention behavior.

| Earlier finding | Status | Trace |
|---|---|---|
| r1-1 Photo expiry cascades | CLOSED | `ForgetService`:155–172; executor :167–182 use storage-only deletion. |
| r1-2 Legacy-memory expiry deletes outputs | CLOSED | Memory `expire` and `Policy.Retention` removed; executor :153 deletes vectors only. |
| r1-3 Orphan CARRIED cascade | CLOSED | Worker :234 → dedicated `expireOrphanFrames`; executor :192–198 has no cascade. |
| r1-4 Later Forget loses provenance | STILL OPEN | Existing pre-v3 expired rows remain absent; migration deletes their lineage: finding 3. |
| r1-5 Expiry withdraws suggestions | CLOSED | Service :264–267 separates callbacks; `forgottenFrames`:127 recognizes markers for new expiries. |
| r1-6 Repair overwrites retained Moments | CLOSED | `MomentRepair`:44–48 builds only missing seconds. |
| r1-7 Scene repair cannot resume | CLOSED | `MomentRepair`:52–56 independently discovers Scene gaps. |
| r1-8 Unbounded repair transactions | CLOSED | `MomentRepair`:48 chunks Moments; :56 rebuilds one hour per call. |
| r1-9 Missing nudge expiry section | CLOSED | `CitedPreview`:81 passes missing count and keeps the section. |
| r2-1 Sequential expiry loses closure | STILL OPEN | Upgrade retains already-broken/missing rows and removes remaining lineage: finding 3. |
| r2-2 Unrelated-photo lineage seeds | CLOSED | Lineage seeding removed; executor :220–224 scopes photo Forget survivors. |
| r2-3 Forgotten photo mistaken for expired | STILL OPEN | Marker clearing exists, but later expiry/migration can restore it; scoped legacy discovery misses it: findings 1–4. |
| r2-4 Forget after expiry misses radius | CLOSED | Service :71/:285 and executor :94 read retained metadata/marker capture time before clearing. |
| r2-5 History loses frame lineage | CLOSED | `PlacesDb`:139 preserves `frameId`; `PlaceCardHistory`:109–116 distinguishes Forget from expiry. |
| r2-6 New legacy expiry mistaken for Forget | CLOSED | Executor :194 writes marker without requiring metadata; ambiguous historical tombstones intentionally fail closed. |
| r2-7 Pause skips retry/storage handling | CLOSED | Worker :49 always sweeps, :52 runs bounded quota before Pause; protection defects are findings 7–9. |
| r2-8 Unbounded lineage accumulation | CLOSED | No new lineage writes; one-time purge removes old keys, but does so unsafely: finding 3. |
| r2-9 Pin sweep clears expired anchor | STILL OPEN | Immediate sweep fixed by `momentsAliveOrExpired`; later marker pruning reopens it: finding 12. |

Explicit-Forget test changes found beyond removed expiry-only tests:

- **Weakened:** the conformance test in finding 14 loses its concrete descendant-rebuild assertion.
- **Modified mixed controls:** `ForgetServiceTest.aFactForgetThatDeletesThePhotoRedactsAnswersCitingThePhotoItself` removes its expiry tail; `ResummarizeWorkerTest.aRebuiltSummaryInheritsItsSourcesPhotosAndGoesWithThem` removes the intervening expiry; their Forget assertions remain.
- **PinMomentLinksTest:** changed `aMomentOutsideTheTargetThatGoesForAnotherReasonDuringTheOperationKeepsItsPin`, `aLeftoverCommittedArmingDeletesOnlyItsDeadSetNotAMomentThatWentLaterForAnotherReason`, `anArmingThatNeverCommittedIsDroppedNotCarriedIntoTheNextForget`, and `deleteAllThatAbortsAtTheEpochCommitThenExpiryThenAnUnrelatedForgetNeverDeletesAPin` to use raw photo loss instead of expiry; `aRangeResumeKeepsItsExactSetEvenThoughNoRangeFilterIsLeft` changes commentary.
- **Delete-all-adjacent pin tests:** `aCrashBeforeTheEpochCommitThenASweepDropsTheStaleWipeArmingAndClearsTheLinks` and `aStaleWipeArmingThenRetentionThenAFreshDeleteAllDeletesOnlyPinsOfThatDeleteAll` likewise substitute raw loss.
- **Strengthened/rewritten controls:** `retentionExpiryKeepsTheLinkAndThePin`, `ResidueOracleTest.retentionKeepsAnOldFactAndAnExplicitForgetStillLeavesNothingThatNamesIt`, the place-history/DAO expiry-then-Forget tests, and `RetentionNeverLosesTest`’s user-Forget controls. User golden delete sets remain unchanged.

Old legal retention preferences remain readable; `recall_hours` leaves `short_hours` unchanged for downgrade. `retention_notice` is explicitly classified as restorable in `BackupRulesTest`:144. No Auto-delete feature is promised in Memory settings.

Static review at `4f540116`, including the requested commits and WP3 `386edd86`. No files edited; no Gradle, adb, tools scripts, or runtime tests run.