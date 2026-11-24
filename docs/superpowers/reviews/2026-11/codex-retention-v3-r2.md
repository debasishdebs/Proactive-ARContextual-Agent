VERDICT: NOT READY

Static review of `feat/v101-ask-eval` at `c5d74c59`, including all four Round-2 commits and the handoff table. No files edited; no Gradle, adb, tools scripts, or runtime tests run.
Paths relative to `android-companion/`: **M** = `services/memory/src/main/java/com/rokid/context/memory`; **O** = `services/orchestration/src/main/java/com/rokid/context`; **P** = `phone/src/main/java/com/rokid/context`.

1. [P1] M/db/ForgetDao.kt:307 - Expired photo P has descriptions A/B; Forget A clears its marker but leaves B and metadata, satisfying the supposedly “proven” backfill and restoring P as expired; migration also selects outside its marking transaction - Require affirmative expiry evidence and recheck it inside the marking transaction.
2. [P1] M/forget/ExpiredLineage.kt:75 - With `lin:d:A=[B]`, expired B, and `lin:d:B=[C]` for surviving C, prune drops A’s key because B has no row; later Forget cannot reach C - Preserve keys according to transitive dependent reachability.
3. [P1] M/forget/ExpiredLineage.kt:46 - Forget photo P reaches expired A, but `lin:d:A=[B]` only adds B to `derived`; if B subsequently expired, `derivedLive` discards it and answers citing B survive; the eight-round cutoff also silently returns partial closure - Traverse both lineage directions to completion or refuse the operation without consuming keys.
4. [P1] M/forget/lifecycle/ForgetExecutor.kt:230 - In v2, Forget photo A deletes a shared A/B fact while preserving photo B, yet seeds `lin:f:B` through all `citedFrames`, redacting unrelated legacy B dependents; planner mirrors this - Seed photo lineage only from photos actually forgotten.
5. [P1] M/forget/ExpiryMarks.kt:67 - A range containing over 20,000 expired legacy photos silently omits the remainder; without metadata/projections, their markers and photo-citing answers survive a successful Forget - Paginate completely or throw `ForgetTooLarge` before deletion.
6. [P1] O/orchestration/PipelineRecords.kt:179 - A queued extraction/Remember retry returning fewer facts deletes previously saved trailing facts through `dropFactsFrom` (`QueueDrainWorker.kt:271,395`) without a Forget - Preserve committed facts across retries and require explicit Forget for removal.

The 14 findings in `codex-retention-v3-r1.md`:

| Finding | Status | Trace |
|---|---|---|
| 1 Tombstone overwritten by expiry | CLOSED | Service :161; executor :174/:194; planner :197 recheck tombstones transactionally. |
| 2 Unsafe marker backfill | STILL OPEN | Finding 1 above; surviving descriptions do not prove absence of Forget. |
| 3 Legacy lineage destroyed/unreachable | STILL OPEN | Keys retained, but findings 2–3 above break pruning and sequential reach. |
| 4 Scoped Forget misses expired legacy photos | STILL OPEN | `MomentForget`:191 adds markers; finding 5 silently truncates discovery. |
| 5 Startup purge deletes retained content/redacts | CLOSED | `InvalidMomentPurge`:58–65 exempts expiry-backed Moments; :76 removes memory cascade. |
| 6 Launch/Reprocess memory purges | CLOSED | Both named APIs and callers removed; separate automatic deletion remains in finding 6 above. |
| 7 Corrupt pins lose quota protection | CLOSED | `QuotaProtection`:19–21 throws; `RetentionWorker`:160–161 aborts eviction. |
| 8 Protection set truncated | CLOSED | Worker :162 refuses sets above 20,000; :190 no longer truncates. |
| 9 Secondary proposal-source photos unprotected | CLOSED | `ForgetDao`:291–293 checks `frame_source` as well as `memory.frameId`. |
| 10 Late vector restores demoted fact | CLOSED | `EmbeddingDao`:103–108 checks recall eligibility transactionally; `EmbeddingSpaces`:64 supplies cutoff. |
| 11 SEMANTIC window misses demoted facts | CLOSED | `MemoryRetriever`:46/:55–59 includes windowed lexical candidates without vectors. |
| 12 Marker pruning breaks Moment/pin evidence | CLOSED | `FrameSweep`:57–61 excludes held refs; :66–96 preserves pinned seconds and aborts unreadable-pin pruning. |
| 13 Stale retention copy | CLOSED | Diagnostics :407/:442; HomeScreen :73; MemoryDetailModel :58 updated. |
| 14 Explicit-Forget assertion weakened | CLOSED | `ForgetPolicyConformanceTest`:55 restores descendant `mD` REBUILD in both modes. |

The earlier-findings table embedded in that review:

| Earlier finding | Status | Trace |
|---|---|---|
| r1-1 Photo expiry cascades | CLOSED | Service :155–173; executor :168–184 remain storage-only. |
| r1-2 Memory expiry deletes outputs | CLOSED | No memory-expiry policy/entry point; executor :154–157 deletes vectors only. |
| r1-3 Orphan CARRIED cascade | CLOSED | Service :183; executor :188–202 use dedicated storage-only expiry. |
| r1-4 Later Forget loses provenance | STILL OPEN | Findings 2–3 above. |
| r1-5 Expiry withdraws suggestions | CLOSED | Service :271–276 separates expiry callbacks from Forget. |
| r1-6 Repair overwrites retained Moments | CLOSED | `MomentRepair`:44–48 builds missing seconds only. |
| r1-7 Scene repair cannot resume | CLOSED | `MomentRepair`:52–56 independently discovers Scene gaps. |
| r1-8 Unbounded repair transactions | CLOSED | `MomentRepair`:48 chunks Moments; :56 processes individual hours. |
| r1-9 Missing nudge expiry section | CLOSED | `CitedPreview`:81 retains the section when sources are missing. |
| r2-1 Sequential expiry loses closure | STILL OPEN | Finding 3 above. |
| r2-2 Unrelated-photo lineage seeds | STILL OPEN | Reintroduced by finding 4 above. |
| r2-3 Forgotten photo mistaken for expired | STILL OPEN | Finding 1 above. |
| r2-4 Forget after expiry misses radius | CLOSED | Service :71/:292 and executor :95 read marker capture time before clearing. |
| r2-5 History loses frame lineage | CLOSED | History expiry preserves lineage; `PlaceGraph`:41–44 separates Forget and expiry handling. |
| r2-6 New legacy expiry mistaken for Forget | CLOSED | Service :165 and executor :197 write expiry markers without requiring metadata. |
| r2-7 Pause skips retry/storage handling | CLOSED | Worker :50 retries files; :52–65 gates quota, demotion and expiry behind Pause OFF, per interim ruling. |
| r2-8 Unbounded lineage accumulation | CLOSED | No new lineage writes; pruning exists, although its unsafe deletion is finding 2 above. |
| r2-9 Pin sweep clears expired anchor | CLOSED | `PinMomentLinks`:71 uses expiry-aware liveness; `FrameSweep`:66–96 preserves pin evidence. |

No additional explicit-Forget test weakening found in these four commits. The new lineage tests cover direct dependencies, not the chained cases above.