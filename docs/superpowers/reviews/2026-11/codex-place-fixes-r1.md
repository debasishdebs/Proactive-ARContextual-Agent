VERDICT: NOT READY

1. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:431 - Cluster decisions continue into photo upload/model matching before fallback, violating your no-photo/no-model requirement despite matching the written S5 spec - Return a fenced cluster card immediately.

2. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:284 - Attempt logs expose pin UUIDs through `ids` and `chosen`, including cluster members - Remove user-pin identifiers from logs and update assertions that require them.

3. [P1] android-companion/phone/src/main/java/com/rokid/context/phone/place/AndroidPlaceGuideControl.kt:157 - Forget/Delete all during `placesOf()` can be bypassed because replay obtains a new epoch afterward, republishes the stale card and recreates history - Capture authority before suspension; revalidate history, provenance and generation before fenced publication/insertion.

4. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceGraph.kt:107 - Pin deletion removes existing history without cancelling attempts holding that pin; an outstanding model/text attempt can subsequently recreate its history and card - Fence pin removal and serialize history insertion against deletion with active-pin revalidation.

5. [P1] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceCardHistory.kt:45 - History deletion failures are swallowed, allowing Forget/Delete all to finish while private rows remain - Propagate cleanup failure and retain a durable deletion obligation.

6. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceTextMatch.kt:100 - Text matching and the sampler independently check visits before publishing; overlapping attempts can both card the same pin - Share an atomic visit/card reservation across both paths.

7. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:679 - Restart seeding includes POSSIBLE and unsent late TEXT rows, suppressing a later confident card for 30 minutes although no card was published - Seed card dedupe only from actual card events.

8. [P2] android-companion/services/orchestration/src/main/java/com/rokid/context/work/frames/place/PlaceSampler.kt:426 - Ambiguity is judged from the capped model candidate list; crowded pins can omit an eligible neighbour and permit a single-pin card - Evaluate pin-first against every eligible nearby active pin before candidate truncation.

9. [P3] android-companion/services/orchestration/src/test/java/com/rokid/context/work/frames/place/replay/ReplayReport.kt:170 - Historical calibration disables dedupe v2, retry, reuse and pin-first, so TRUSTED does not validate the reviewed configuration - Report legacy calibration separately and assert acceptance against the production flags.

Two distinct Antera pins producing two cards is a safe **P2 acceptance deviation**, not P1. Several-room cluster wording is acceptable; its model/photo execution is P1 under your requirement.

Static review only; no files edited and no Gradle, adb or tools scripts run.