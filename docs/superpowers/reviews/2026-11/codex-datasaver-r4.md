VERDICT: NOT READY

Static review at `65e1e34f`; paths below are relative to `android-companion/`. No edits, Gradle, adb, tools scripts, or tests run.

1. [P1] `services/ai/src/main/java/com/rokid/context/model/provider/FencedCall.kt:51` - Guard precedes `execute()` without network binding or retry checks. FREE→costly→FREE between samples still uploads uncounted; even a sampled transition has no reservation before process death (`ModelRouter.kt:288–300`, `EmbeddingClient.kt:175–196`) - Bind/check each transport route and durably reserve before costly transmission.
2. [P1] `services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:451` - LAN comes from the URL string: `.local` and private literals (`core/model/.../net/DataSaver.kt:265–271`). In AUTO, public DNS answers, proxies, or private addresses routed through a VPN retain the Wi-Fi LAN exemption; DNS/route protection applies only outside AUTO (`OfflineOnlyDns.kt:22`, `OfflineOnlyNet.kt:63`) - Establish LAN exemption from the actual direct route and underlying network, including retries/redirects.
3. [P1] `core/model/src/main/kotlin/com/rokid/context/core/net/DataSaver.kt:409` - Reservation holds the ledger monitor throughout synchronous disk commit; Main-thread `AndroidDataSaverControl.kt:25–28` reads and router admission (`DataSaverGraph.kt:85`) block on synchronized `today():399`, so slow storage can still cause ANR despite IO dispatch - Keep Main-thread reads independent of the commit lock or move these reads/admissions onto IO.
4. [P2] `services/ai/src/main/java/com/rokid/context/ai/ModelRouter.kt:387` - Cancellation during IO reservation can discard the committed token before assignment, outside transport cleanup, charging an entirely unsent call until rollover; embeddings repeat this at `EmbeddingClient.kt:175` - Capture ownership inside IO and release unused reservations in an outer NonCancellable cleanup.

| Earlier r3 finding | Status | Trace |
|---|---|---|
| 1 · FREE→costly accounting/transport race | STILL OPEN | Sampling improves accounting, but no route binding or transition reservation; finding 1. |
| 2 · LAN blocked by failed reservation | CLOSED | Router `:277–288`, embeddings `:166–175` skip reservation for `R_LAN`; classification weakness is finding 2. |
| 3 · caller-thread durable commit | CLOSED | Router `:387–392`, embeddings `:175,193` dispatch writes to IO; indirect Main blocking remains finding 3. |
| 4 · low-space refusal orphans phone captures | CLOSED | `RokidFrameInbox.kt:290–307` now proceeds directly to row insertion; refusal removed. |
| 5 · ineffective post-write backpressure | CLOSED | Guard removed and design deferred; storage bounding is no longer implemented. |

Removal check: no remaining `ParkedStorageGuard`, `storagePaused`, `STORAGE_LOW`, or storage-notice references outside graph/review artifacts. Waiting count/bytes remain wired through `AndroidDataSaverControl.kt:48–51` and `DataSaverSettings.kt:196`.

Accounting check: reservation and `sentAt` branches are exclusive; settlement applies a delta (`DataSaver.kt:415–420`). No double-settle path found. Normal reserve→transport→settle ordering is sequential; acquisition cancellation is finding 4.

NonCancellable settlement can delay cancellation on stalled storage; no synchronous shutdown join found in the reviewed paths. Main-thread lock exposure is finding 3.

Default steady Wi-Fi/home behaviour remains unchanged: FREE bypasses caps/unreadable settings (`DataSaver.kt:222`), default home mobile remains allowed without a cap. Failed costly reservations still refuse transmission.