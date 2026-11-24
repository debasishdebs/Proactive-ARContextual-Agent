VERDICT: NOT READY

Reviewed `6137b37c`; checkout HEAD is `55944cce`, with no Android code differences. Read-only; no Gradle, adb, or tools scripts run. Paths below are relative to `android-companion/`.

1. [P1] services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:351 - Wi-Fi admission can precede a roaming upload; polling leaves a 1 s gap, and `work.cancel()` does not cancel ordinary blocking OkHttp calls (`model/provider/FencedCall.kt:17`), allowing uploads until timeout - Bind admission to the actual network and wire cancellation to `Call.cancel()` through response consumption.
2. [P1] services/orchestration/src/main/java/com/rokid/context/work/frames/scene/SceneIndexer.kt:202 - Routing runs before parking (`KeyframePipeline.kt:401`) and accepts parked rows (`FrameDaos.kt:366`); semantic dedupe overwrites `data_saver`, permitting undescribed-photo deletion through `ForgetDao.kt:383` - Preserve parking protection across routing and every retention transition.
3. [P1] services/ai/src/main/java/com/rokid/context/ai/datasaver/DataSaverStore.kt:39 - Wrong-typed abroad/hotspot settings become `true`; validated home Wi-Fi becomes ROAMING (`DataSaver.kt:54`), then unreadable state blocks remote Ask/capture - Preserve independently established free Wi-Fi while refusing costly traffic.
4. [P1] services/ai/src/main/java/com/rokid/context/ai/datasaver/DataSaverGraph.kt:77 - Settings are captured before `mobile.today()` discovers a corrupt counter; that admission uses stale `unreadable=false`, allowing single-check callers such as embeddings to transmit - Validate counters before snapshotting settings and deciding.
5. [P1] services/ai/src/main/java/com/rokid/context/ai/datasaver/DataSaverStore.kt:92 - Reservations use asynchronous `apply()`; process death after transmission but before disk persistence can erase admission usage - Durably commit reservations before sending and refuse costly sends if persistence fails.
6. [P1] services/ai/src/main/java/com/rokid/context/ai/embed/EmbeddingClient.kt:90 - Admission precedes IO dispatch; no send-time network check, cancellation watcher, or reservation protects batches against network changes or process death - Use the shared durable admission and cancellable transport path.
7. [P1] services/orchestration/src/main/java/com/rokid/context/work/LocalModelDownloadWorker.kt:159 - Cost is latched for the whole transfer; enabling hotspot metering during an unmetered-Wi-Fi download with Anywhere selected leaves subsequent bytes uncounted, so the cap never closes - Reclassify/count each transfer interval and cancel when policy closes.
8. [P2] services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:350 - A request’s own reservation can reach the cap; the watcher then cancels that already-admitted request although the network never changed - Exclude its reservation when revalidating the active request.
9. [P2] services/orchestration/src/main/java/com/rokid/context/work/frames/KeyframePipeline.kt:628 - Parked storage has no proactive bound; capture continues until storage failure, despite the waiting-count/size display - Add free-space capture backpressure and an explicit storage decision without deleting parked photos.

| Earlier finding | Status | Fix trace and remaining evidence |
|---|---|---|
| 1 · send-slot/network race | STILL OPEN | `232bcbba`: slot recheck added, but finding 1 remains. |
| 2 · embedding bypass | CLOSED | `4a6e3846`: `EmbeddingClient.kt:72–101` gates/classifies/counts batches; new transport holes in finding 6. |
| 3 · parked-photo shedding/loss | STILL OPEN | `d061089f`: `FrameDaos.kt:195` excludes all parked rows from shedding; finding 2 bypasses preservation. |
| 4 · VPN trusts unrelated Wi-Fi | CLOSED | `9061b3a1`: `NetworkCostMonitor.kt:98–103,124–128` uses declared underlying networks; reflection/security failures become UNKNOWN. |
| 5 · model-download gate/accounting | STILL OPEN | `60459e7b`, `8c15461e`: BULK_OTHER gate, chunk counting, Wi-Fi replacement/backoff exist; finding 7 remains. |
| 6 · stalled-upload cap evasion | STILL OPEN | `232bcbba`: router reserves/settles at `ModelRouter.kt:276–293`; finding 5 defeats crash durability. |
| 7 · unreadable state | STILL OPEN | `232bcbba`: typed-read exceptions caught; findings 3–4 violate Wi-Fi availability/fail-closed admission. |
| 8 · Endpoint Test bypass | CLOSED | `4a6e3846`, `aaca794a`: `EndpointTester.kt:51–70` gates SMALL traffic and counts calls. |
| 9 · partial tile responses | CLOSED | `60459e7b`: `OnlineTileSource.kt:248–256` counts received chunks from `finally`. |

Reservations normally replace—not add to—the settled usage; interrupted usage remains conservatively counted until the next local day. Caps/reservations do not block genuinely FREE Wi-Fi (`DataSaver.kt:158,217`; `DataSaverGraph.kt:105`).

Embedding failures propagate out of batch filling; `EmbeddingWorker.kt:153–158` retries with WorkManager backoff, capped at five runs. Night maintenance catches failures and defers (`NightIndexWorker.kt:161–164`): no tight retry loop found, though Wi-Fi return has no dedicated embedding wake after retries expire.

VPN reflection failures are caught, so home Wi-Fi + VPN may safely park photos as UNKNOWN without crashing or spinning. Clean-settings home Wi-Fi policy remains unrestricted. Reviewed saver logs use fixed tokens/counts; no hosts, addresses, or content found.

Four home roaming dry-run device checks:

1. Exercise Ask/capture on home Wi-Fi, then home VPN; repeat with exhausted caps and unreadable settings. Confirm free Wi-Fi/LAN/on-device availability and conservative VPN parking.
2. Hold a photo behind a permit, switch to simulated roaming, then release; also switch during a throttled upload. Verify server-side bytes stop and cancellation releases the slot.
3. Reach the byte/photo cap with a slow response; kill/restart immediately after upload, then cross midnight. Verify durable usage, settlement, Wi-Fi availability, and no self-abort.
4. Park >350 photos with scene indexing active; reconnect and run retention/quota. Verify every undescribed parked photo survives, waiting count/size stays accurate, embedding retries back off, and downloads stop/count when hotspot policy changes.