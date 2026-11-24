VERDICT: NOT READY

1. [P1] android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/ExpiredLineage.kt:64 - Expire A while derived B is live, then expire B; later Forget of A’s photo finds B only in `derived`, and the cascade filters B out because its row is gone, leaving B-citing answers/proposals untouched - Traverse both durable edge directions to complete closure, including missing rows.

2. [P1] android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/lifecycle/ForgetExecutor.kt:223 - Forget photo A whose batch memory also cites surviving photo B; lineage seeds include B and redact answers citing unrelated expired facts of B (planner repeats this at :103) - Seed photo lineage only from photos this operation forgets.

3. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/provenance/ProvenanceGate.kt:126 - Explicit Forget fact tombstones its photo but retains `frame_meta` (`ForgetService`:228); `forgottenFrames` therefore calls it expired-and-live, preserving suggestions citing the forgotten photo - Persist an explicit expiry/Forget distinction instead of inferring intent from metadata.

4. [P1] android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/ForgetService.kt:86 - Expiry retains nearby marks/traces but removes `frame`; later photo Forget supplies no timestamp, so radius cleanup and its device commands never run (executor :106 has the same hole) - Read retained capture times before deleting metadata and use them in planner/executor cleanup.

5. [P1] android-companion/services/memory/src/main/java/com/rokid/context/memory/place/PlacesDb.kt:139 - Expiry nulls a history row’s `frameId`; later Forget cannot match it through `deleteCardLogForFrames` (:138), so photo-derived history survives - Preserve private source lineage separately from the display photo link.

6. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/provenance/ProvenanceGate.kt:127 - Legacy photo expiry has no `frame_meta`, so the next suggestion sweep classifies its retention tombstone as Forget and withdraws its records/surfaces (`ProactiveForget`:48) - Recognize retention tombstones independently of metadata presence.

7. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/RetentionWorker.kt:47 - Default-ON/error pause skips all expiry and the file-retry sweep (:59); startup still sweeps, but continuous capture has no independent quota eviction or storage bound - Keep storage retries active and provide bounded storage handling independently of retention pause.

8. [P2] android-companion/services/memory/src/main/java/com/rokid/context/memory/forget/ExpiredLineage.kt:42 - Every expiry accumulates lineage; only a matching user Forget or Delete all removes it, including records whose dependents no longer exist - Compact unreachable lineage while preserving every surviving dependent’s Forget path.

9. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PinMomentLinks.kt:71 - Expiry now retains the Moment, but the next pin sweep tests surviving photo rows (`MomentDao`:105), clears its link and invalidates the new test’s “keeps its anchor” expectation - Align pin liveness with retained Moments and exercise the sweep in that test.

| Earlier finding | Status | Trace |
|---|---|---|
| 1. Photo expiry cascades | CLOSED | `ForgetService`:162–178; executor :158–177; planner :202: storage-only path. |
| 2. Legacy-memory expiry drops outputs | CLOSED | Service :223/:229 and executor :235/:239 require `redact`. |
| 3. Legacy orphan CARRIED cascade | CLOSED | `RetentionWorker`:205 → dedicated `expireOrphanFrames`; no nested Forget. |
| 4. Later Forget loses provenance | STILL OPEN | Lineage added, but sequential expiry loses closure; finding 1 above. |
| 5. Expiry withdraws suggestions | STILL OPEN | Forget callbacks suppressed, but legacy expiry still fails liveness; finding 6 above. |
| 6. Repair overwrites retained Moments | CLOSED | `MomentRepair`:44–48 builds missing seconds only. |
| 7. Scene failure cannot resume | CLOSED | `MomentRepair`:52–56 detects Scene gaps independently. |
| 8. Unbounded repair transactions | CLOSED | Moment chunks of 200; Scene transactions cover one hour. |
| 9. Missing nudge expiry wording | CLOSED | `CitedPreview`:81 passes missing count and retains the section. |

Delete all covers every `lin:` prefix (`WipeDao`:56/:87). No lineage serialization found in memory/config exports; backup and transfer rules exclude `context.db`. Preview thumbnails observe frame removal (`PreviewSection`:273), and HTTP image serving rechecks liveness (`CanvasBackendImpl`:585).

Changed assertions concern expiry; no explicit Forget assertion was weakened. New coverage misses sequential expiry, retained-metadata user tombstones, and Forget-after-expiry radius cleanup.

Reviewed with `git show`: `0da373c9`, `3f2d2ace`, `bd6b1249`, `6979f441`, plus current listener change `543f1031`. Static review only; no files edited, Gradle, adb, or tools scripts run.