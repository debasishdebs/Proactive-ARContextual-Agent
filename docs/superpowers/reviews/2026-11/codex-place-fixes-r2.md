VERDICT: NOT READY

1. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/MemoryWipe.kt:187 - A persistent history-delete failure increments `leftovers`, retaining `wipePending`; `resumeIfPending()` reruns the entire wipe at every startup and deletes newer memories - Retry the bounded history obligation independently of the completed wipe.

2. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceGraph.kt:141 - Forget computes DONE before its pin cascade; failed deletion of that pin’s history on other frames is swallowed here, so DONE returns while private rows remain - Check history obligations after the cascade and propagate a cleanup-pending outcome.

3. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceGraph.kt:139 - After the pin prefs are written, `replaceUserPlaces()` suspends before fencing; if it throws, attempts remain live and history deletion is never armed - Fence immediately after the successful pin-store write and ensure cleanup obligations survive mirror failure.

4. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceCardHistory.kt:229 - `PrefsHistoryOwed.add()` ignores `commit()==false`; a subsequent failed history deletion can lose its only retry obligation on process death - Verify persistence and handle failure explicitly.

5. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:539 - Photo-match and fallback (`:587`) bypass `reserve()`; text can publish during their suspending publication checks, followed by a second card for the same pin - Use the shared reservation across every automatic card path.

6. [P3] tools/place-attempt-report.sh:43 - Successful new PIN_FIRST attempts become `(unknown)` because the outcome whitelist omits PIN_FIRST - Add the outcome and fixtures covering single and cluster cards.

7. [P3] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:488 - Redaction makes `shadowChoice` equal `"pin"`; scorer rankings use actual IDs, so pin agreement becomes `na` - Retain the private ID internally for scoring and redact only log output.

No additional pause-zone bypass or lock-order deadlock found in the traced retryRecent, fix-reuse, and show-again paths. Persistent history exceptions return cleanup failure for ordinary Forget; the whole-wipe restart loop is finding 1. Older log pin IDs are suppressed by the report.

Static review only; no files edited, Gradle, adb, or tools scripts run.

| Earlier finding | Status | Trace at c07ff400 |
|---|---|---|
| 1 Cluster photo/model call | CLOSED | c07ff400 → PlaceSampler.kt:439 returns clusterCard; :626 publishes without match/shadow. |
| 2 Pin IDs in attempt logs | CLOSED | c07ff400 → PlaceSampler.kt:453 filters IDs; :600 redacts choice; cluster IDs stay internal. |
| 3 Show-again authority | CLOSED | cc5ceae4 → PlaceShowAgain.kt:26 captures before reads; :28–37 revalidates row, generation and epoch. |
| 4 Pin-removal fencing | STILL OPEN | cc5ceae4 → PlaceGraph.kt:138–141 fences after suspending mirror write; finding 3. |
| 5 Failed history deletion | STILL OPEN | cc5ceae4 → obligations added, but post-cascade failure is swallowed and persistence unchecked; findings 1–4. |
| 6 Atomic card reservation | STILL OPEN | c07ff400 → pin/cluster/text reserve; photo and fallback bypass it; finding 5. |
| 7 Restart dedupe seeding | CLOSED | c07ff400 → PlaceSampler.kt:710–720 excludes POSSIBLE and NOT_SENT_LATE TEXT. |
| 8 Uncapped ambiguity | CLOSED | c07ff400 → PlacePack.kt:191 supplies uncapped pins; PlaceSampler.kt:433 uses their union. |
| 9 Calibration reporting | CLOSED | ded7b1bf → ReplayReport.kt:150 separates legacy calibration; TripReplayRealDataTest.kt:32 asserts production violations. |