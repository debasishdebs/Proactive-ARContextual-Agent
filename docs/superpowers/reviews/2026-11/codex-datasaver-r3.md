VERDICT: NOT READY

Reviewed `feat/v101-ask-eval` at `c573c05e`, including all five round-2 commits. Read-only; no Gradle, adb, tools scripts, or tests run. Paths below are relative to `android-companion/`.

1. [P1] services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:275 - FREE-Wi-Fi admission followed by a costly-network connection retains `costlyAtStart=false`, takes no reservation, and records zero costly usage; embeddings repeat this at `EmbeddingClient.kt:165`. `FencedCall.kt:51` samples before `execute()`, without binding the network or guarding internal retries - Bind admission to the transport network and reserve/count costly transitions before sending.
2. [P1] services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:277 - LAN calls allowed by `DataPolicy` on Wi-Fi still reserve against the network’s global cost; unvalidated/metered Wi-Fi plus unreadable counters or failed persistence blocks LAN Ask/capture. Embeddings have the same path - Make reservations respect the request’s LAN/Wi-Fi exemption.
3. [P1] services/ai/src/main/java/com/rokid/context/ai/datasaver/DataSaverStore.kt:108 - Synchronous `commit()` runs on the caller’s thread: `PromptEditor.kt:181–189` → `TestBed.kt:88` → router reservation, before the provider switches to IO; slow storage can freeze Main - Perform durable admission on IO before transport.
4. [P1] services/orchestration/src/main/java/com/rokid/context/data/frames/RokidFrameInbox.kt:292 - Low-storage refusal leaves phone captures permanently unindexed: `PhoneCapture.kt:94–96` writes/enqueues and reports success, `FrameIngestWorker.kt:25,58` accepts null and returns success, while reconciliation scans only v2 receipts (`ReceiptFiles.kt:84`). Wi-Fi return cannot recover these legacy archive files - Persist a retryable receipt/job and surface the capture’s pending status.
5. [P2] services/orchestration/src/main/java/com/rokid/context/data/frames/RokidFrameInbox.kt:292 - “Backpressure” occurs after JPEG storage; glasses continue writing and acknowledging receipts (`FrameIngestor.kt:184–205`), so rejected files accumulate outside the parked-row count and disk growth continues - Apply pressure before receipt writes and propagate a recoverable storage hold to capture.

| Earlier round-2 finding | Status | Trace |
|---|---|---|
| 1 · transport/network race | STILL OPEN | `FencedCall.kt:48–58` now cancels sockets through body consumption; network binding/retry and accounting gaps remain in finding 1. |
| 2 · routing loses parking | CLOSED | `FrameDaos.kt:250` refuses parked transitions; `SceneIndexer.kt:202–203,222–225` respects zero moved rows and leaves cluster/dedupe metadata clear. |
| 3 · unreadable switches break FREE Wi-Fi | CLOSED | `DataSaverStore.kt:42` → `DataSaver.kt:41`; unreadable hotspot rules preserve independently FREE Wi-Fi. Separate LAN regression: finding 2. |
| 4 · stale settings before counter validation | CLOSED | `DataSaverGraph.kt:84–89` validates counters before taking settings and deciding. |
| 5 · asynchronous reservations | CLOSED | `DataSaverStore.kt:107–110` commits; `DataSaverGraph.kt:118–121`, router and embeddings reject FAILED. Main-thread regression: finding 3. |
| 6 · embedding transport bypass | STILL OPEN | `EmbeddingClient.kt:163–217` adds reservation, guard, watcher and settlement; FREE→costly traffic remains uncounted (finding 1). |
| 7 · download cost latched | CLOSED | `LocalModelDownloadWorker.kt:159–165,196–213,229` watches policy and reclassifies/counts each interval. |
| 8 · own reservation cancels request | CLOSED | `DataSaverGraph.kt:87` subtracts the same-day reservation; router/embedding guards and watchers use `decideExcluding`. |
| 9 · parked-storage bound/status | STILL OPEN | Threshold clears when space returns or the gate opens, but indexing refusal does not bound storage and can orphan phone captures (findings 4–5). |

The storage notice appears only in Data saver settings (`DataSaverSettings.kt:200`); phone capture can still report “Saved” for the orphaned shot.

The three current `executeFenced` consumers close responses with `use`; no current response-wrapper registration leak found. Typed pre-connect refusal releases reservations; ambiguous cancellation conservatively retains usage. FREE Wi-Fi bypasses caps/unreadable settings, and on-device calls bypass remote transport.

Tests cover socket cancellation, typed pre-connect refusal, parking preservation and interval classification. Missing coverage includes real FREE→costly connections/retries, LAN with failed reservations, Main-thread StrictMode, and end-to-end phone-capture recovery after low-storage refusal.