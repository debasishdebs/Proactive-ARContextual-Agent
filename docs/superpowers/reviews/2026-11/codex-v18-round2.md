VERDICT: NOT READY

1. [P1] [ModelNodeExecutors.kt:77](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/ModelNodeExecutors.kt:77) - With global privacy Auto, a node requesting `backend=offline` can send stored text to a remote fallback after its local primary fails; only the primary binding is checked. - Fix: enforce the node’s backend restriction inside every provider attempt, including fallbacks.

2. [P1] [DumpReader.kt:164](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/dump/DumpReader.kt:164) - With `forgetV2` OFF, partially forgetting a Scene hides its old derived summary; dump still exports its complete `value`, including forgotten content. Invalidated memories are also returned without filtering. - Fix: exclude hidden, obsolete and invalidated content from dump responses.

3. [P1] [SceneCentroidProducer.kt:74](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/SceneCentroidProducer.kt:74) - A Scene can borrow a duplicate representative’s vector from another Scene; forgetting that representative leaves the borrowing Scene’s revision unchanged, so `deleteStale()` retains its contribution. Production dedupe spans ten minutes and can cross Scene boundaries. - Fix: select vectors only from Scene members, or track and invalidate external dependencies.

4. [P1] [DumpRoutes.kt:76](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/canvas/api/DumpRoutes.kt:76) - Forget or Delete saved locations can commit during a dump read, after sensitive rows were loaded; the final check detects only Delete all, so the response can still disclose removed data. - Fix: coordinate dump reads with deletion and reject responses whose deletion epoch changed.

5. [P1] [RetentionWorker.kt:108](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/orchestration/forget/RetentionWorker.kt:108) - With `retentionP2` ON, a pin linked after the pass caches protection remains unprotected; later expiry can delete its photo. Pin updates do not share this retention lock, and expiry does not recheck exemptions. - Fix: synchronize protection changes with expiry and revalidate exemptions before committing deletion.

6. [P1] [ActivityMapViewModel.kt:56](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/ui/activity/ActivityMapViewModel.kt:56) - A location read completing after Forget/Delete saved locations can publish old coordinates into map state; no deletion fence or table observer invalidates them until another load. - Fix: observe location invalidations and fence publication against deletion changes, clearing selected cells too.

7. [P2] [OnlineMapGraph.kt:49](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/privacy/zones/online/OnlineMapGraph.kt:49) - Opening either map synchronously reads the encrypted vault during composition; Save/Clear also perform synchronous encrypted preference commits on Main. Cold Keystore access can freeze the UI. - Fix: load and mutate keys on IO, exposing ready/loading state to Compose.

8. [P2] [SceneCentroidProducer.kt:69](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/moment/SceneCentroidProducer.kt:69) - Production dedupe can form `f2 → f1 → f0`; one-hop grouping counts this connected duplicate group twice and biases the centroid. - Fix: resolve duplicate roots with cycle protection before grouping.

9. [P2] [DumpReaderTest.kt:99](/Users/<user>/Desktop/rokid/proactive-context-agent/android-companion/services/orchestration/src/test/java/com/rokid/context/canvas/dump/DumpReaderTest.kt:99) - `photo("f5").let { null }` makes the assertion pass regardless of the photo result; manually inserted tombstones also miss actual Forget cascades. - Fix: assert the returned value directly and exercise real Forget operations with both flag states.

In-memory checks passed for migration column compatibility and SQLite integrity. Hidden-text exposure and duplicate grouping were reproduced with SQL/logic checks. Release map-key configuration is empty; dump pairing, Developer gating, rate limiting and vectors removal remain present. No files edited; no Gradle, adb or tools scripts run.

Recommended device checks after fixes:

- Upgrade a disposable v17/places-v2 copy, including SQLCipher; verify counts, area names, restart recovery and failure rollback. Avoid downgrading the migrated real-phone databases.
- Exercise photo, fact, Moment, Scene and range Forget with both flag states; verify every new table, dump response and open map, including concurrent reads.
- Race retention against pin/link creation; verify protected JPEGs survive.
- Cold-open maps with 20,000 locations; test no-key networking, key save/clear, responsiveness and deletion while loading.
- Verify glasses wake spacing, switch OFF, user opens, withdrawal, expiry and destruction; confirm the screen returns to sleep.
- Inspect the release APK, logs, diagnostics, exports and backup rules for keys and private location content.