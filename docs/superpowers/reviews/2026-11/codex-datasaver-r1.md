VERDICT: NOT READY

Static review of `feat/v101-ask-eval` at `310cd184`. Paths below are relative to `android-companion/`. No files edited; no Gradle, adb, or tools scripts run.

1. [P1] services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:265 - A photo passes admission on Wi-Fi, waits for its permit, then uploads on roaming; only counting rechecks the network - Recheck policy and caps inside the permit/upload slot, and bind the connection to the admitted network or cancel on changes.

2. [P1] services/ai/src/main/java/com/rokid/context/ai/embed/EmbeddingClient.kt:71 - Remote embedding batches bypass DataGate and byte accounting; night maintenance can upload batches of stored text while roaming or capped - Gate every batch by its actual endpoint reach and traffic kind, and count request/response bytes.

3. [P1] services/memory/src/main/java/com/rokid/context/memory/db/FrameDaos.kt:191 - After a sufficiently large parked backlog reconnects, older `data_saver` frames become `shed_backlog`, making undescribed photos eligible for retention deletion - Exclude every parked frame from shedding; bound disk growth through capture backpressure and an explicit storage decision.

4. [P1] services/ai/src/main/java/com/rokid/context/ai/datasaver/NetworkCostMonitor.kt:97 - VPN classification trusts any connected validated Wi-Fi found in `allNetworks`, without proving it carries the VPN; a cellular-backed VPN can be classified FREE - Verify the VPN’s actual underlying networks; otherwise classify UNKNOWN.

5. [P1] services/orchestration/src/main/java/com/rokid/context/work/LocalModelDownloadWorker.kt:53 - Downloads consult only abroad/metered state and WorkManager constraints; they bypass DailyCaps, the hotspot override, and the ledger - Apply the shared BULK_OTHER gate and accounting before each transfer, with cancellation/replacement when policy closes.

6. [P1] services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:269 - A server can receive a complete photo upload then stall: timeout/cancellation records zero bytes and zero photos; repeated retries or process death evade caps - Persist an admission reservation and reconcile it using actual transmitted bytes, retaining conservative usage after interruption.

7. [P1] services/ai/src/main/java/com/rokid/context/ai/datasaver/DataSaverStore.kt:24 - Wrong-typed persisted settings throw during graph construction; Settings and other graph initialization paths can crash instead of entering a protective state - Validate reads and expose an explicit unreadable state that refuses costly traffic.

8. [P2] services/ai/src/main/java/com/rokid/context/ai/EndpointTester.kt:64 - Endpoint Test calls providers directly, ignoring “Small requests on roaming” and contributing no usage - Route tests through SMALL admission and accounting.

9. [P2] phone/src/main/java/com/rokid/context/phone/ui/settings/privacy/zones/online/OnlineTileSource.kt:236 - A partial tile response followed by IOException skips accounting entirely; repeated failed downloads undercount mobile usage - Count received chunks and report them from `finally`, including cancellation and failure.

Recommended device checks:

1. Queue a photo behind a blocked permit, switch Wi-Fi to roaming, then release it; verify no photo leaves without Send once.
2. Test dual-SIM/eSIM data switching and a cellular-backed VPN with Wi-Fi still connected; verify conservative classification.
3. Exercise Android Data Saver and a hotspot with “I’m abroad” plus metered-Wi-Fi enabled, including running model downloads.
4. Reach byte/photo caps, stall responses after upload, kill/restart the process, and cross midnight; verify usage and admission.
5. Accumulate over 350 parked frames, reconnect, run retention, and approach disk exhaustion; verify preservation and capture backpressure.