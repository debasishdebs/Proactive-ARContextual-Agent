VERDICT: NOT READY

Reviewed HEAD `b43696ca`. No listed unit files changed after `07e27bcd`; I also inspected `5b7e9946`, which changed Place Guide callers. Static review only: no files edited, Gradle, adb, or tools scripts run.

Paths below are relative to `android-companion/`. Build labels identify affected supplied builds; findings also remain at HEAD.

1. **[P1] services/connectors/src/main/kotlin/com/rokid/context/connectors/place/PlaceHttp.kt:71** — Start a Place Guide cell fetch or seed in Automatic → switch to Offline only while its first request runs → `PlaceCellSource.fetchCell()` continues with subsequent Wikipedia/Wikidata/Overpass requests; Overpass sends the cell’s bounding box. `send()` checks the zone ticket but never the current privacy mode. These are new requests after the tap, beyond the documented already-on-wire exception. — **Fix:** enforce the live privacy decision at the HTTP seam before every request, after any wait. — **affects: train7-ccfb0b62**

2. **[P1] services/ai/src/main/java/com/rokid/context/ai/resolve/OfflineOnlyNet.kt:48** — Configure a private-address model reachable through an Android VPN → enable Offline only → the router classifies it LAN, and the route guard accepts `Proxy.Type.DIRECT` plus its private destination address → content follows the VPN to the remote host. Neither `Http.modelClient()` nor this guard selects or verifies a non-VPN phone network. This violates the requested physical-network invariant; the documented address-range limitation does not establish that invariant. — **Fix:** bind permitted calls to an eligible non-VPN network and fail closed when that route cannot be established. — **affects: train7-ccfb0b62**

3. **[P2] services/ai/src/main/java/com/rokid/context/ai/embed/Embedders.kt:34** — Enable Offline only with a remote EMBED binding → invoke `forBinding()` → `endpoints.keyFor(e)` reads the vault before `revision()` or `embed()` reaches `OfflineOnlyGuard`. `forSpace()` likewise reads the key at line 56 before returning an embedder. Transmission is refused, but the before-key-read invariant fails. — **Fix:** check privacy in both factory paths before calling `keyFor()`. — **affects: train7-ccfb0b62**

4. **[P2] services/orchestration/src/main/java/com/rokid/context/work/frames/KeyframePipeline.kt:543** — Use a remote primary and repeatedly failing LAN fallback under Offline only → cluster extraction receives `fallbackUnavailable=true` from `ExtractOutcome.classify()` → conversion to `ClusterOutcome.Failed` drops that flag → `settleClusters()` releases the claims without incrementing attempts indefinitely. The cap in `settle()` covers individual frames only. — **Fix:** retain the fallback flag and apply the cap to cluster IDs before settlement. — **affects: train7-ccfb0b62**

5. **[P2] services/orchestration/src/main/java/com/rokid/context/work/FallbackParkCap.kt:15** — A queue job or frame repeatedly encounters a failing LAN fallback → the process dies before accumulating seven failures → restart recreates the empty map → repeated restarts prevent any counted attempt and the work can park indefinitely. The code comment documents this accurately. — **Fix:** persist the count with the work item if termination across restarts remains required. — **affects: train7-ccfb0b62**

6. **[P3] services/ai/src/main/java/com/rokid/context/ai/datasaver/DataSaverGraph.kt:206** — Open Settings before graph initialization finishes → `SettingsDestinations.kt:100` calls `DataSaverGraph.get()` on main → `create()` synchronously calls `refreshState()` → `heldBack()` → `decide()` → `privacy.current()` → `ModelPrivacyStore.load()`. That path can perform the first preference read, or block main behind the IO load. `379fff33` fixes the flow getters, not this enforcement caller. — **Fix:** initialize and refresh the graph off main before exposing it to the screen. — **affects: train7-ccfb0b62**

7. **[P3] services/ai/src/main/java/com/rokid/context/ai/resolve/OfflineOnlyDns.kt:17** — Configure `box.local` → enable Offline only → start a model call → `delegate.lookup(hostname)` runs before addresses are checked → a system resolver using unicast DNS receives the hostname even when the resulting public address is subsequently refused. — **Fix:** use local-only resolution under Offline only, or explicitly document and accept this hostname exposure. — **affects: train7-ccfb0b62**

| id | Disposition |
|---|---|
| I-P2-a | **FIXED** (`166840d4`, `services/ai/src/main/java/com/rokid/context/ai/resolve/ModelPrivacyStore.kt:102`): durable writers serialize and re-check versions before each commit. |
| I-P2-b | **FIXED** (`166840d4`, `services/ai/src/main/java/com/rokid/context/ai/resolve/ModelPrivacyStore.kt:99`): superseded writes return no callback. |
| I-P2-c | **CONFIRMED-OPEN** — finding #5. Documented in `96f3e0d2`; documentation does not satisfy unconditional termination. |
| I-P2-d | **FIXED** (`96f3e0d2`, `services/memory/src/main/java/com/rokid/context/memory/forget/DeletionGraph.kt:113`): inventory includes `last_choice`. |
| I-P2-e | **FIXED** for the listed omissions: `96f3e0d2`, `services/orchestration/src/test/java/com/rokid/context/work/QueueDrainWorkerRetryTest.kt:141` and `services/orchestration/src/test/java/com/rokid/context/work/frames/KeyframePipelineTest.kt:647`; `166840d4`, `phone/src/test/java/com/rokid/context/phone/ui/settings/modelprivacy/ModelPrivacyControlTest.kt:40` and `services/ai/src/test/java/com/rokid/context/ai/resolve/ModelPrivacyDurabilityTest.kt:42`. Tests inspected, not executed; cluster coverage still misses #4. |
| I known window | **CONFIRMED-OPEN** — `ModelPrivacyStore.kt:90`: `apply()` provides no durability before the first flush. A kill can restore the previous choice. Documented at `PRIVACY.md:211`; not an unconditional persistence guarantee. |
| J-P3 — Saving | **CONFIRMED-OPEN** — `ModelPrivacyStore.kt:99` can finish its final version check before a newer tap; its delayed callback can then clear the newer screen’s Saving state at `ModelPrivacySettings.kt:87`. |
| J-P3 — no_link | **CONFIRMED-OPEN** — `phone/.../hub/Hub.kt:306` catches a throwing push through `guarded()`, leaves `pushed=false`, then throws `ZonePublishDeferred`; `phone/.../zone/ZoneController.kt:366` labels it `no_link`. |
| audit P3-2 | **CONFIRMED-OPEN** — finding #7; the `.local` DNS exposure is not explicitly described in PRIVACY’s limits. |
| audit P3-4 | **FIXED** narrowly (`379fff33`, `services/ai/src/main/java/com/rokid/context/ai/resolve/ModelPrivacyStore.kt:59`): flow getters load asynchronously. Absent from train7; the separate main-thread path in #6 remains at HEAD. |
| Model download unguarded | **OVERRULED** as an undocumented defect — explicitly disclosed at `PRIVACY.md:207` and `ModelPrivacySettings.kt:55`; `Http.downloadClient()` is deliberately separate and keyless. |

| Invariant | Result |
|---|---|
| 1. No non-LAN model/embedding/test/place/tile requests; guard before key read | **BROKEN (findings #1, #3)** |
| 2. No system proxy; phone’s own network route | **BROKEN (finding #2)**. HTTP/SOCKS proxy exclusion itself holds under Offline only. |
| 3. Guard exceptions stay inside synchronous execution | **HOLDS**. Guarded clients: OpenAI-compatible/Azure provider clients; Ollama clients; `EmbeddingClient`’s client; `EndpointTester.testClient` and its embedded `EmbeddingClient`. Chat providers use `executeFenced()`; embeddings/revision use direct synchronous `execute()` on IO. No guarded production `enqueue()` path found. |
| 4. Lost/unreadable/failed mode write resets safely | **HOLDS within the recorded-intent design**, excluding the separately documented pre-flush window. Loss of both files is indistinguishable from a fresh installation. |
| 5. Immediate tap, survives screen exit, last tap wins on disk | **BROKEN (finding #1)** for immediate outbound enforcement. In-memory mode and store-owned writes survive screen exit; restart durability retains the known pre-flush exception. |
| 6. Offline-only parking is a standing hold, not Blocked today | **HOLDS** |
| 7. FallbackParkCap ends a failing LAN fallback | **BROKEN (findings #4, #5)** |
| 8. Both preference files excluded in all three backup sections and kept by Delete all | **HOLDS** |
| 9. Resolver labels are fixed tokens | **HOLDS** |
| 10. Nothing reads disk on main | **BROKEN (finding #6)** |