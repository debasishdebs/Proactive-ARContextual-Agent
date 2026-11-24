VERDICT: NOT READY

1. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/ForgetActions.kt:304 - Ordinary Forget returns the outcome computed at :349 before `explicitStore()` performs its pin cascade (PinMomentLinks.kt:94–106); failed deletion of that pin’s history can therefore return DONE with private rows remaining - Recheck cleanup after `explicit()` returns for committed outcomes.

2. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/MemoryWipe.kt:187 - History deletion failure is swallowed, the wipe marker clears at :201, and MemorySettings.kt:130 displays “Everything was deleted” while private history remains - Return a cleanup-pending result while retaining the independent retry obligation.

3. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceCardHistory.kt:253 - Persistent deletion failures accumulate distinct frame/range/`a:<timestamp>` obligations without compaction; every startup and Forget retries the growing set, and one permanently failing token keeps unrelated Forgets reporting cleanup failure - Compact subsumed obligations while preserving deletion scope and retry durability.

| Earlier finding | Status | Trace at c8e0c845 |
|---|---|---|
| 1 Whole-wipe restart loop | CLOSED | c8e0c845 → MemoryWipe.kt:187–201 excludes HistoryDeletionPending from wipe leftovers; separate history retry remains. New success-reporting defect: finding 2. |
| 2 Forget DONE before pin-history cleanup | STILL OPEN | c8e0c845 → cleanupFailed at ForgetActions.kt:349 remains inside the block; cascade follows in PinMomentLinks.kt:94–106. Range path :253 checks afterward. |
| 3 Fence/arm after mirror suspension | CLOSED | c8e0c845 → PlaceGraph.kt:139–156 fences and arms before mirror; deletion runs despite mirror failure/cancellation. |
| 4 Unchecked obligation persistence | CLOSED | c8e0c845 → PlaceCardHistory.kt:102–114, :250–259 checks commit, retains memory fallback, and throws pending when deletion fails. |
| 5 Photo/fallback bypass reservation | CLOSED | 43a3b0c4 → PlaceSampler.kt:542–546 and :594–598 reserve with unconditional finally release. |
| 6 PIN_FIRST report whitelist | CLOSED | 50d959cc → tools/place-attempt-report.sh:43 includes PIN_FIRST; :290–299 adds single/cluster fixtures. |
| 7 Shadow scoring uses redacted ID | CLOSED | 43a3b0c4 → PlaceSampler.kt:488 retains private chosen ID; :299–309 logs scoring aggregates only. |

A failing history obligation no longer reruns the whole wipe or prevents later deletions from executing; it can indefinitely prevent a clean Forget result. Delete all currently reports success regardless, as finding 2 describes.

No additional concrete deadlock, reservation leak, pause-zone bypass, prohibited photo/model call, or private-ID log/report leak established. NonCancellable cleanup at PlaceGraph.kt:156 has no deadline and retains the pin lock while awaiting history cleanup; no production lock cycle was found.

Static review only at branch feat/v101-ask-eval, tip c8e0c845. No files edited; no Gradle, adb or tools scripts run.