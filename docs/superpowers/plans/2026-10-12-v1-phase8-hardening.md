# Proactive Context v1.0 — Phase 8: Hardening

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (or superpowers:executing-plans). This is a **lean** plan: each task gives files, exact names/signatures, behaviour bullets, named tests with a one-line assertion, and a gate. It does not carry implementation code — write the smallest code that makes the named tests pass, TDD (RED first). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close the edge cases the earlier phases deferred: frame storage at both ends (clock jumps, full disk, crashes, Delete all races), scale (export, lexical, vector, IN-lists), lifecycle leaks (SurfaceTexture, SDK sessions, the force-stop that ends capture), and the last secret/privacy leftovers. Then finish the v1 delivery items: UI smoke completion (R-73), screenshot references and legacy relocation (R-74), and the on-device checklist script (R-75).

**Architecture:** No new modules. No `PROTOCOL_VERSION` bump. No Room schema change: the new `ExportDao` is a DAO only, so the version stays as is. Fixes go into the existing classes. Seams are injected through constructors with strict production defaults. The five streams touch disjoint files, apart from the named one-line shared edits listed under each stream. Each stream runs in its own worktree at the same time as the others and as Phase 7.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, Gradle 8.13, Compose BOM 2024.09.00, Room 2.6.1 + KSP, SQLCipher 4.6.1, WorkManager 2.9.1, kotlinx-serialization 1.7.3, OkHttp 4.12.0, Rokid CXR (client-l 1.1.2, cxr-service-bridge 1.5), Robolectric 4.13 (`@Config(sdk = [34])`), kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-12-v1-redesign-design.md` is the authority (§1.7 lifecycle/buffer, §1.8 transfer, §5.5 72 h cap, §6.1.1 Delete all, §6.3 errors, §6.4 testing, §7 scale). Master plan Phase 8 rows: **R-73** (UI smoke completion), **R-74** (screenshots + legacy relocation), **R-75** (on-device checklist script). Every task also cites the R-IDs its carry belongs to.

## Global Constraints

- **Start point.** Branch every stream from `phase8/hardening` once Phase 6 Tasks 11–16 have merged into it. If you dispatch earlier, the stream must not touch any file Phase 6 Tasks 11–15 modify: `HomeModel/HomeViewModel/HomeScreen`, `Destinations`, `PhoneDeps`, `Routes`, `SettingsDestinations`, `SettingsRoot/Deps/Widgets`, `GlassesSettings`, `EndpointEditor`, `PromptEditor`, `PromptComposer`, `EndpointTester`, `RoleBindingRepository`, `EmbeddingClient`, `ai/schema/*`, `ai/contract/*`, `IndexMaintenance`, `EmbeddingWorker`, `OrchestrationScheduler`, `CodeBox`, `services/memory/…/MemorySettings.kt`. `AppConfigStore.kt` is the exception: Stream D may touch it after Phase 6 Task 15 merges.
- **Phase 7 owns these (never edit them):** `glasses/…/pause/*`, `phone/…/hub/{Hub,HubPauses,HubSources,HubGraph,HubSettingsStore,StateFrameBuilder,ContextHubService}.kt`, `phone/…/ui/onboarding/*`, `phone/…/ui/home/*`, `phone/…/ui/ask/*`, `phone/…/ui/shell/{Routes,SettingsDestinations,Destinations}.kt`, `phone/…/ui/settings/SettingsRoot.kt`, `core/model/…/Protocol.kt`, `core/hud/…/render/*`, `glasses/…/hud/{ConfirmInput,InputRouter}.kt`, `services/glasses-link/…/FrameAdmission.kt`, `services/memory/…/db/{AppDaos,ViewDaos,WipeDao}.kt`, `MemoryEdits`, `services/orchestration/…/{RetryClassifier,AskActions,AnswerService}.kt`, `:services:connectors`.
- **Shared one-line edits:** a stream may make only the edit named in its task to these files. Resolve the merge conflict by keeping both sides: `glasses/…/GlassesRuntime.kt` (A), `glasses/…/MainActivity.kt` (C), `phone/…/ui/banner/Banners.kt` (C), `services/memory/…/db/ContextDatabase.kt` (B), `services/memory/…/MemoryGraph.kt` (D), `services/orchestration/…/MemoryWipe.kt` (A), `phone/build.gradle.kts` (D).
- **TDD.** Write each named test first and record its RED line (a behavioural failure, or a mutant run when the code already exists). Tests that pin behaviour already present are labelled "(pin)" and need a mutant RED.
- **Gate.** Run one Gradle invocation at a time per worktree. Full gate, in the background: `cd android-companion && ./gradlew test :phone:assembleDebug :glasses:assembleDebug > ../.superpowers/sdd/2026-10-12-v1-phase8-hardening/<stream><task>-gate.log 2>&1; echo EXIT=$? >> <same file>`. Set `ANDROID_HOME=/Users/<user>/Desktop/rokid/android-sdk`. Each task's gate below names its focused modules. Run those first, then the full gate before you commit.
- No test-only switches in production code. Inject through constructors with strict defaults. Inject a `work`/`io` dispatcher. No `delay(` in ViewModels.
- No DB, Keystore or file I/O on Main. Rethrow `CancellationException` everywhere, including inside `runCatching`.
- Regex literals must be ICU-safe (no unescaped `}`; `AndroidRegexRulesTest`).
- Logs use `SafeLog.describe` / class name / kind / code only. Never log memory text, transcripts, model text, provider bodies, keys or file paths that contain user data.
- **Text hygiene.** Write non-ASCII characters as `\uXXXX`. Before committing, run `grep -nP '[^\x00-\x7F]' <changed .kt>`.
- Commit after every task. End the message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push. Never print `.env`. Never run adb or touch devices: device steps are `PENDING-DEVICE` for the controller. No subagents.
- Write the full report to `.superpowers/sdd/2026-10-12-v1-phase8-hardening/<stream><task>-report.md`. Return only: status, commit range, gate result, concerns.

## Review Focus

These are conditions the spec implies but does not spell out. Each is pinned by a named test:

1. **The glasses clock jumps** (NTP sync after boot, the user changes the time) while frames sit in the buffer. Expected: the 72 h cap counts uptime within one boot, and capture time is re-based at send time, so the phone's pause filter sees the true instant. Tests: A1 `aClockJumpForwardDoesNotAgeOutFramesFromThisBoot`, `aClockJumpBackDoesNotKeepAFrameBeyond72hOfUptime`, `sendTimeIsRebasedToTheCurrentWallClock`.
2. **Disk full on the glasses while capturing.** Expected: the oldest frame goes and the newest is stored (FIFO, as the count cap). Nothing half-written is left. Test: A1 `storageFullEvictsTheOldestAndStoresTheNewest`.
3. **A frame arrives while Delete all runs, or was captured before it.** Expected: no JPEG or row survives the wipe. Pre-wipe frames still on the glasses are acked and dropped. Tests: A4 `aFrameCapturedBeforeDeleteAllIsAckedAndNotStored`, `aWipeDuringIngestLeavesNoRowAndNoFile`.
4. **Scale: 50 k memories, 12 k asks, 2 000-id IN lists on API 29's 999-variable SQLite.** Expected: the export is complete and streamed, lexical search reads the FTS doclists once, and no DAO call gets more than 500 ids. Tests: B1 `exportsEveryAskBeyondTenThousand`, B2 `theCursorIsOpenedOncePerQuery`, B4 `noDaoCallGetsMoreThanFiveHundredIds`.
5. **SDK objects on failure paths** (bind fails, connect throws, the camera never binds). Expected: every SurfaceTexture, CxrSession and CXRLink created is released exactly once. Tests: C1 `aBindFailureStillReleasesTheTexture`, C4 `aSetupThrowClosesTheCreatedSession`, `aFailedWatcherBindDisconnects`.
6. **A user removes the seeded key, then the app restarts with the legacy file still present.** Expected: the key is never copied back. Test: D1 `aKeyRemovedByTheUserIsNeverCopiedBack`.

---

## Streams at a glance

| Stream | Area | Worktree / branch | Tasks |
|---|---|---|---|
| A | Frame storage edges: glasses buffer, uplink, phone ledger/reassembler/inbox, wipe race | `../pca-p8-a` / `phase8/a-frames` | A1–A4 |
| B | Performance at scale: streaming export, lexical single pass, vector scale, IN chunking | `../pca-p8-b` / `phase8/b-scale` | B1–B4 |
| C | Lifecycle and robustness: capture resources, fallback cap, force-stop, SDK session/watcher/bridge, broadcaster | `../pca-p8-c` / `phase8/c-lifecycle` | C1–C5 |
| D | Security, privacy, data integrity: legacy key, endpoint keys, redaction, SQLCipher, legacy import | `../pca-p8-d` / `phase8/d-privacy` | D1–D5 |
| E | Delivery: smoke completion, gate watchdog, checklist script, screenshots | `../pca-p8-e` / `phase8/e-delivery` | E1–E3 |

`…` below = `src/main/java/com/rokid/context` (phone, glasses, `:services:{ai,memory,orchestration}`) or `src/main/kotlin/com/rokid/context` (`:core:*`, `:services:{glasses-link,voice}`). Paths are under `android-companion/`.

---

## Stream A — Frame storage edges

**Files (A owns):** `glasses/…/glasses/capture/OutboundBuffer.kt`, `glasses/…/glasses/link/FrameUplink.kt`, `core/model/…/core/model/FileDurability.kt` (new), `core/model/…/core/model/FrameCodec.kt` (`FrameAckReason.WIPED` only), `services/glasses-link/…/link/{ReceivedFrameLedger,FrameReassembler}.kt`, `phone/…/phone/hub/FrameIngestor.kt`, `services/orchestration/…/data/frames/RokidFrameInbox.kt`, `services/orchestration/…/orchestration/PipelineRecords.kt` (`isRealPhotoFrame` only), `services/orchestration/…/orchestration/WipeFence.kt` (new). Tests: `glasses/src/test/…/glasses/capture/OutboundBufferTest.kt`, `…/glasses/link/FrameUplinkTest.kt`, `core/model/src/test/…/FileDurabilityTest.kt` (new), `services/glasses-link/src/test/…/link/{ReceivedFrameLedgerTest,FrameReassemblerTest}.kt`, `phone/src/test/…/phone/hub/{FrameIngestorTest,AtomicJpegWriterTest}.kt` (the latter new), `services/orchestration/src/test/…/{data/frames/RokidFrameInboxTest (new),orchestration/PipelineRecordsTest,orchestration/MemoryWipeTest}.kt`.
**Shared one-liners:** `GlassesRuntime.kt` (the `uplink = FrameUplink(…)` line gains `onBufferChanged = { controller?.sendHello() }`) and `MemoryWipe.kt` (`deleteAll` calls `WipeFence.markStarted` and wraps its file-deletion section in `WipeFence.mutex.withLock`).
**Must not touch:** `FrameAdmission.kt`, `Hub.kt`, `HubGraph.kt`, `glasses/…/pause/*`, `GlassesRuntime.togglePause`, `CaptureService.kt` (Stream C), the rest of `MemoryWipe.kt`.

### Task A1: Glasses buffer — clock-proof age, byte cap, full disk, durable dir

**Requirements:** R-07 (offline buffer, 200 frames ≈ 4 MB), R-65 (72 h cap), R-08 · carries: 72 h cap on glasses wall clock; no 4 MB cap; disk-full drops newest; no dir fsync.

**Files:** `OutboundBuffer.kt`, `FileDurability.kt` (new); tests `OutboundBufferTest.kt`, `FileDurabilityTest.kt`.

**Signatures:**
- `object FileDurability { fun fsyncDir(dir: File): Boolean }`. It does `FileChannel.open(dir.toPath(), READ).use { it.force(true) }` and returns false on `IOException`. Pure JVM, so `:core:model` stays Android-free.
- `@Serializable data class BufferedMeta(…, val elapsedAtCaptureMs: Long? = null, val bootId: String? = null)`. The new fields default to null, so meta files from before Phase 8 still load.
- `class OutboundBuffer(dir, capacity = 200, maxBytes: Long = 8L * 1024 * 1024, maxAgeMs = 72 h, clock = System::currentTimeMillis, elapsed: () -> Long = SystemClock::elapsedRealtime, bootId: () -> String = ::currentBootId, writer = ::atomicWrite, fsyncDir: (File) -> Boolean = FileDurability::fsyncDir, log)`
- `val bytes: Long` (synchronized). `fun sendTimeMs(f: BufferedFrame): Long`. `internal fun currentBootId(): String` reads `/proc/sys/kernel/random/boot_id` and returns `""` on failure.
- `add(…)` gains `elapsedAtCaptureMs: Long = elapsed()` as its last parameter, so existing callers compile unchanged.

**Behaviour:**
- Age is `elapsed() - elapsedAtCaptureMs` when the meta's `bootId` equals the current boot and both are known. Otherwise it is `clock() - capturedAtMs` (the earlier behaviour).
- A frame from an earlier boot (or with unknown boot) whose `capturedAtMs` is more than 1 h in the future of `clock()` has no knowable age. It is dropped (fail closed, §5.5).
- `sendTimeMs`: for a same-boot frame it returns `clock() - (elapsed() - elapsedAtCaptureMs)`, so a clock change between capture and send is cancelled out before the phone applies the hello offset. Otherwise it returns `capturedAtMs`.
- The cap is `capacity` frames **and** `maxBytes` of JPEG. Either bound drops the oldest first. Ruling: 8 MiB = 200 × 40 KB worst case, so the spec's 200-frame figure holds for normal frames. Cost if wrong: one constant.
- If a write fails with `IOException` while the buffer is not empty, the oldest frame is evicted and the write retried once. It logs `storage full: dropped <id>`. `false` is returned only if the retry also fails, and nothing is left on disk.
- After both files of an entry are renamed, the directory is fsynced. A false result is logged once and does not fail the add.
- `load()` deletes `*.tmp` (already) and enforces both caps.

**Tests (OutboundBufferTest):**
- `aClockJumpForwardDoesNotAgeOutFramesFromThisBoot`: clock +100 h, elapsed +1 h → `oldest()` still returns the frame.
- `aClockJumpBackDoesNotKeepAFrameBeyond72hOfUptime`: clock −10 h, elapsed +73 h → the frame is dropped.
- `aFrameFromAnEarlierBootUsesWallClockAge`: different bootId, wall age 73 h → dropped; wall age 1 h → kept.
- `aFutureDatedFrameFromAnEarlierBootIsDropped`: different bootId, `capturedAtMs` = now + 2 h → dropped.
- `sendTimeIsRebasedToTheCurrentWallClock`: same boot, clock moved +5 min after capture → `sendTimeMs` = original capture + 5 min.
- `byteCapDropsOldestFirst`: three 4 MiB-ish frames with `maxBytes` = 8 MiB → only the newest two remain.
- `storageFullEvictsTheOldestAndStoresTheNewest`: the writer throws once and then succeeds → `add` returns true, size unchanged, the oldest id is gone.
- `storageFullWithAnEmptyBufferStoresNothing`: the writer always throws → false and the directory is empty.
- `oldMetaWithoutBootFieldsStillLoads`: a pre-Phase-8 JSON meta → loaded, and its age is wall-clock.
- `dirIsFsyncedAfterEachAdd`: the injected `fsyncDir` is called once per successful add.

**Tests (FileDurabilityTest):** `fsyncDirReturnsTrueForARealDirectory`; `fsyncDirReturnsFalseForAMissingDirectory`.

**Gate:** `./gradlew :core:model:test :glasses:testDebugUnitTest`, then the full gate.

### Task A2: Uplink edges — crash accounting, poison frames, clear during wait, lost kick, buffer count

**Requirements:** R-08 · carries: crash in `oldest()` charged to the previous id; transient crashes accumulate; clear during ack wait noticed only after the timeout; poison drop ends the flush; unreadable JPEG deleted (a 4th deletion path); lost kick race; `bufferedFrames` stays one too high (capture races the ack; NACK drops not subtracted); post-schedule hello reports buffered=1.

**Files:** `FrameUplink.kt`; one line in `GlassesRuntime.kt`; test `FrameUplinkTest.kt`.

**Signatures:** `class FrameUplink(scope, buffer, sendChunk, canSend, config, log, onBufferChanged: () -> Unit = {})`. Add `val ackPollMs: Long = 250` to `UplinkConfig`.

**Behaviour:**
- `current` is cleared before every `buffer.oldest()`, so a throw there is charged to no frame.
- Crash counts are consecutive per frame. They reset when that frame leaves the buffer by any path.
- When a frame hits `MAX_CRASHES` and is dropped, the flush **continues** with the next frame. It stops only after `buffer.size` iterations without progress, which bounds the loop.
- The ack wait polls `buffer.contains(frameId)` every `ackPollMs`. A clear (a privacy pause) ends the wait as `CLEARED` within one poll.
- A read `IOException` drops the frame only when `!f.jpeg.exists()`. Otherwise it counts as a crash for that frame, so a transient error never deletes a good frame.
- `kick()` while a flush runs sets `@Volatile pendingKick`. When the flush ends with `pendingKick` set, it runs once more.
- `onBufferChanged()` runs after each `DROPPED` or aged-out removal, and at flush end when `sent + dropped > 0`. The resulting hello corrects the phone's count without a protocol change.
- Each chunk's `capturedAtMs` is `buffer.sendTimeMs(f)` (from A1).

**Tests (FrameUplinkTest):**
- `aCrashInOldestIsNotChargedToThePreviousFrame`: frame X delivered, `oldest()` then throws 3× → X's count is absent and no frame is dropped.
- `crashCountsResetAfterAFrameIsDelivered`: 2 crashes, a delivery, 2 crashes → not dropped.
- `aPoisonFrameIsDroppedAndTheFlushContinues`: frame 1 crashes 3×, frame 2 is fine → frame 2 is delivered in the same kick sequence.
- `aClearDuringTheAckWaitEndsTheWaitWithinOnePoll`: clear 100 ms into the wait → `CLEARED` before 400 ms of virtual time.
- `aTransientReadErrorKeepsTheFrame`: the read throws once and the file exists → the frame is still buffered.
- `aMissingJpegIsDropped`: the file is deleted → removed, and `onBufferChanged` is called.
- `aKickDuringTheFinalFrameRunsAnotherFlush`: kick while the last ack is awaited, a frame added → that frame is sent without another kick.
- `aHelloFollowsAFlushThatDroppedAFrame`: two NACKs → `onBufferChanged` called ≥ 1.
- `aHelloFollowsEveryFlushThatSentFrames`: 2 delivered → called at flush end.
- `chunksCarryTheRebasedCaptureTime`: the sent meta `capturedAtMs` equals `buffer.sendTimeMs`.

**Gate:** `./gradlew :glasses:testDebugUnitTest`, then the full gate.

### Task A3: Phone ledger and reassembler — lazy, crash-proof, bounded

**Requirements:** R-08 (dedupe, reassembly) · carries: reassembler has no cap on concurrent partials or chunk length; ledger init `readLines` can throw; no fsync; leftover `.tmp`; `MainActivity.onCreate` reads the ledger on Main; missing edge tests (duplicate chunk, count/crc restart, corrupt ledger).

**Files:** `ReceivedFrameLedger.kt`, `FrameReassembler.kt`; tests `ReceivedFrameLedgerTest.kt`, `FrameReassemblerTest.kt`.

**Signatures:**
- `class ReceivedFrameLedger(file: File, capacity = 1_000, fsync: (File) -> Unit = { f -> FileOutputStream(f, true).use { it.fd.sync() } }, fsyncDir: (File) -> Boolean = FileDurability::fsyncDir, log: (String) -> Unit = {})`
- `FrameReassembler(staleMs = STALE_MS, maxPartials: Int = MAX_PARTIALS)` with `const val MAX_PARTIALS = 4` and `const val MAX_CHUNK_CHARS = 2 * SAFE_CHUNK_CHARS`.

**Behaviour:**
- The ledger reads nothing in its constructor. The first `contains`/`add` loads it under the lock, so building the hub on Main does no file I/O.
- An unreadable or undecodable ledger file starts empty with one log line and never throws. Invalid lines are skipped (already).
- On load, a leftover `<name>.tmp` is deleted.
- `add` writes the `.tmp`, fsyncs it, renames it and fsyncs the directory. On `IOException` the id is not kept (already).
- A chunk whose Base64 text exceeds `MAX_CHUNK_CHARS` is `Ignored`.
- A new frame that would exceed `maxPartials` evicts the partial with the oldest `lastChunkAtMs`. The glasses send one frame at a time, so 4 is ample.

**Tests:**
- `ReceivedFrameLedgerTest.constructionReadsNoFile`: a ledger built on an unreadable path does not throw until first use.
- `anUnreadableLedgerStartsEmptyAndDoesNotThrow`: the path is a directory → `contains("x")` is false, and `add` throws `IOException` only on write.
- `aLeftoverTmpIsRemoved`: a `.tmp` exists → it is gone after first use.
- `aCorruptLineIsSkipped` (pin): a binary garbage line → the other ids still load.
- `addFsyncsTheFileAndTheDir`: both injected fsyncs are called once.
- `FrameReassemblerTest.aFifthConcurrentFrameEvictsTheStalestPartial`: 5 partial frames → `pendingCount == 4` and the stalest is missing.
- `anOversizeChunkIsIgnored`: a 70 000-char chunk → `Ignored`.
- `aDuplicateChunkIsIdempotent`: chunk 1 twice, then the rest → `Complete` with the right bytes.
- `aChangedCrcOrCountRestartsTheFrame` (pin): a new crc mid-frame → the old parts are discarded.

**Gate:** `./gradlew :services:glasses-link:test`, then the full gate.

### Task A4: Phone inbox — orphan `.part`, dir fsync, failed-ingest retry, the Delete all race, real-photo by name

**Requirements:** R-08, R-72, spec §6.1.1 (Delete all removes every frame) · carries: orphan `.part` never swept; parent directory not fsynced after rename; archived-but-failed ingest never retried; a frame arriving mid-wipe can orphan a JPEG/row; "synthetic"/"screenshot" matched on the full path.

**Files:** `FrameIngestor.kt` (`AtomicJpegWriter`, `FrameIngestor`), `RokidFrameInbox.kt`, `PipelineRecords.kt` (`isRealPhotoFrame`), `WipeFence.kt` (new), `FrameCodec.kt` (`FrameAckReason.WIPED = "wiped_drop"`); a one-call edit in `MemoryWipe.kt`. Tests as listed for the stream.

**Signatures:**
- `object WipeFence { val mutex: Mutex; fun lastStartedAtMs(filesDir: File): Long?; fun markStarted(filesDir: File, nowMs: Long) }`. The value lives in `filesDir/wipe_started_at`, written atomically. `FrameIngestor` passes `inboxDir.parentFile` (`rokid_inbox` sits under `filesDir`).
- `FrameIngestor(…, wipeStartedAtMs: () -> Long? = { WipeFence.lastStartedAtMs(inboxDir.absoluteFile.parentFile) }, wipeLock: Mutex = WipeFence.mutex)`
- `internal fun AtomicJpegWriter.write(target, bytes, lastModifiedMs, beforeRename = {}, fsyncDir: (File) -> Boolean = FileDurability::fsyncDir)`
- `fun RokidFrameInbox.sweepOrphanParts(context: Context, nowMs: Long = System.currentTimeMillis(), minAgeMs: Long = 60_000): Int`
- `internal enum class InboxResult { STORED, DUPLICATE, FAILED }`. `ingestFile` returns it.

**Behaviour:**
- After the rename, `AtomicJpegWriter` fsyncs the directory. A false result is logged and the write still counts.
- `ingestPending` first deletes `*.part` files in the inbox older than `minAgeMs`. A fresh `.part` may be a write in progress.
- An ingest that throws, or that `PipelineRecords.appendFrame` reports as not stored for a reason other than a duplicate, is `FAILED`. The file is renamed back into the inbox under its original name, so the next drain retries it. A duplicate stays archived.
- `ingestPending` reads `MemoryWipe.generation` before each file. If it has moved after `appendFrame`, the new row and its file are deleted (same pattern as `appendFact`).
- `MemoryWipe.deleteAll` calls `WipeFence.markStarted(filesDir, now)` right after bumping the generation, and holds `WipeFence.mutex` while it deletes frame files.
- `FrameIngestor.store` runs its write and ledger-add under `wipeLock`. A complete frame whose `capturedAtPhoneMs < wipeStartedAtMs()` is acked `ok=true, reason=WIPED`, added to the ledger, and never written (§6.1.1: Delete all covers frames still buffered on the glasses).
- `isRealPhotoFrame` applies the "synthetic"/"screenshot" test to `File(path).name` only. The payload test stays.

**Tests:**
- `FrameIngestorTest.aFrameCapturedBeforeDeleteAllIsAckedAndNotStored`: wipe at t=100, frame at t=50 → ack `ok=true, reason=wiped_drop`, the inbox is empty, the ledger has the id.
- `aStoreWaitsForAWipeInProgress`: `wipeLock` held → `onChunk` completes only after release.
- `aFrameCapturedAfterTheWipeIsStored` (pin): t=150 → stored.
- `AtomicJpegWriterTest.dirIsFsyncedAfterRename`: the injected `fsyncDir` is called with the inbox dir.
- `RokidFrameInboxTest.orphanPartsOlderThanAMinuteAreSwept`: a 2-min-old `.part` → deleted, and the result is 1.
- `aFreshPartIsKept`: a 10 s-old `.part` → kept.
- `aFailedIngestReturnsTheFileToTheInbox`: `appendFrame` throws → the file is back in `rokid_inbox` with its original name.
- `aDuplicateStaysArchived`: the second ingest of the same id → the file stays in `rokid_archive`.
- `aWipeDuringIngestLeavesNoRowAndNoFile`: the generation is bumped inside `appendFrame` → no frame row and no file.
- `PipelineRecordsTest.aParentFolderNamedScreenshotsDoesNotHideRealPhotos`: path `/x/Screenshots/rokid_1_a.jpg` → true; name `screenshot_1.jpg` → false.
- `MemoryWipeTest.deleteAllMarksTheWipeStart`: after `deleteAll`, `WipeFence.lastStartedAtMs` is ≥ the call time.

**Gate:** `./gradlew :services:orchestration:testDebugUnitTest :phone:testDebugUnitTest`, then the full gate.

---

## Stream B — Performance at scale

**Files (B owns):** `services/orchestration/…/orchestration/MemoryExport.kt`, `services/memory/…/memory/db/{ExportDao (new),RetrievalDao}.kt`, `services/memory/…/memory/retrieval/{LexicalRetriever,VectorIndex,VectorCodec}.kt`, `services/memory/…/memory/{Repositories,SqlChunks (new)}.kt`. Tests: `services/orchestration/src/test/…/orchestration/MemoryExportTest.kt`, `services/memory/src/test/…/memory/{retrieval/LexicalRetrieverTest,VectorIndexTest,RepositoriesTest,QueryPlanTest (new)}.kt`.
**Shared one-liner:** `ContextDatabase.kt` gains `abstract fun exportDao(): ExportDao`. This is a DAO only: no entity and no version bump.
**Must not touch:** `ViewDaos.kt`, `AppDaos.kt`, `MemoryDaos.kt` (read only), `FeedRepositories.kt`, `MemorySettings` (UI or store), `SettingsDeps.kt` (the export call site keeps its signature).

### Task B1: Streaming export (no 10 k ask cap)

**Requirements:** R-17 (Settings › Memory › Export), R-72 · carry (Phase 8): streaming export — three copies in memory; asks capped at 10 k.

**Files:** `MemoryExport.kt`, `ExportDao.kt` (new), the `ContextDatabase.kt` line; test `MemoryExportTest.kt`.

**Signatures:**
- `@Dao interface ExportDao { @Query("SELECT rowid, id, text, type, tags, tier, confidence, reviewState, createdAt, frameId FROM memory WHERE rowid > :after AND reviewState != 'INVALIDATED' ORDER BY rowid LIMIT :limit") suspend fun memoryPage(after: Long, limit: Int): List<MemoryExportRow>; @Query("SELECT rowid, id, ts, question, answer FROM ask WHERE rowid > :after ORDER BY rowid LIMIT :limit") suspend fun askPage(after: Long, limit: Int): List<AskExportRow> }`. The row classes `MemoryExportRow(rowid: Long, …)` and `AskExportRow(rowid: Long, …)` go in the same file.
- `suspend fun MemoryExport.write(context, graph = MemoryGraph.get(context), nowMs = …, pageSize: Int = 500): File` keeps its signature apart from the new trailing parameter.

**Behaviour:**
- Writes with `android.util.JsonWriter` on `Dispatchers.IO`, page by page. Peak memory is one page, never the whole library.
- Same JSON shape and key names as today (`exportedAt`, `memories[id,text,type,tags,tier,confidence,review,createdAt,frameId]`, `asks[id,at,question,answer]`). The order is now rowid (insertion). Ruling: the order is not part of the contract.
- Every ask is exported: no cap.
- Writes `<name>.json.part`, then renames. Older memory exports are deleted only after the rename succeeds. On any failure or cancellation the `.part` is deleted and the previous export stays.
- INVALIDATED memories are never exported (pin; the query excludes them).

**Tests (MemoryExportTest):**
- `exportsEveryAskBeyondTenThousand`: 12 000 asks → 12 000 entries in `asks`.
- `exportReadsInPagesNotAllAtOnce`: with a spying `ExportDao`, every call has `limit == 500` and `graph.memories.all()` is never called.
- `jsonShapeIsUnchanged`: 2 memories + 1 ask → the decoded keys equal the golden key sets.
- `invalidatedMemoriesAreNeverExported` (pin): an INVALIDATED row → absent.
- `aFailedExportLeavesThePreviousExportAndNoPart`: the writer throws mid-page → the old file is still there and there is no `.part`.

**Gate:** `./gradlew :services:memory:testDebugUnitTest :services:orchestration:testDebugUnitTest`, then the full gate.

### Task B2: Lexical search in one pass

**Requirements:** R-48 (FTS4 + BM25) · carry (P5 T10 → Phase 8 perf): lexical paging re-reads FTS doclists per page (~11× slower at 50 k common-term matches); BM25 stats re-read per page.

**Files:** `RetrievalDao.kt`, `LexicalRetriever.kt`; test `LexicalRetrieverTest.kt`.

**Signatures:**
- `RetrievalDao`: `@Query("SELECT memory_fts.rowid AS rowid, memory.id AS id, matchinfo(memory_fts, 'pcnalx') AS mi FROM memory_fts JOIN memory ON memory.rowid = memory_fts.rowid WHERE memory_fts MATCH :match AND memory.reviewState = 'OK' AND memory.confidence >= :minConfidence") fun lexicalCursor(match: String, minConfidence: Double): android.database.Cursor`. `lexicalPage` is removed once nothing calls it.
- `class LexicalRetriever(dao: RetrievalDao, io: CoroutineDispatcher = Dispatchers.IO)`

**Behaviour:**
- One cursor per query, read on `io`. Each row goes into the same `TopHits(limit)` heap, so peak memory is O(k) plus one CursorWindow.
- Calls `coroutineContext.ensureActive()` every 256 rows. The cursor is closed in `finally`, including on cancellation.
- Ranking is identical to the paged version (same BM25 and the same tie-break).

**Tests (LexicalRetrieverTest):**
- `singlePassMatchesTheRankedResultOfAFullSort`: 2 000 fixture memories → top 20 equals a reference sort of all BM25 scores.
- `theCursorIsOpenedOncePerQuery`: with a spying DAO, `lexicalCursor` is called exactly once for 1 200 matches.
- `cancellationClosesTheCursor`: cancel after the first rows → `cursor.isClosed`.
- `fiftyThousandCommonTermMatchesRankWithinFiveSeconds`: 50 k rows sharing one term → completes in under 5 s on JVM. This is a generous bound; the single pass took ~0.2 s on desktop.
- The existing English/Hindi/mixed pins still pass.

**Gate:** `./gradlew :services:memory:testDebugUnitTest`, then the full gate.

### Task B3: Vector search at the spec's 50 k scale

**Requirements:** R-48 (cosine, `VectorIndex`), spec §7 (brute force fine below ~50 k) · carry: vector paging at scale (user brief). The existing per-page allocations and the unpinned query plan are covered here.

**Files:** `VectorCodec.kt`, `VectorIndex.kt`; tests `VectorIndexTest.kt`, `QueryPlanTest.kt` (new).

**Signatures:** `fun VectorCodec.decodeInto(blob: ByteArray, out: FloatArray): Boolean` returns false when the blob's dimension ≠ `out.size` or the blob is corrupt. `BruteForceVectorIndex(dao, pageSize = 500)` is unchanged.

**Behaviour:**
- One reusable `FloatArray(query.size)` per search. A wrong-size or corrupt blob is skipped without allocating.
- Results equal a full sort by score descending, then id ascending (pin).
- The `livePage` and `lexicalCursor` plans use an index/rowid seek and no `USE TEMP B-TREE`.

**Tests:**
- `VectorIndexTest.decodeIntoMatchesDecode`: random 64-d vectors → identical floats.
- `aWrongDimensionBlobIsSkipped` (pin): a 32-d blob in a 64-d search → absent from the hits.
- `fiftyThousandVectorsSearchMatchesFullSort`: 50 k × 64-d → top 10 equals the reference.
- `QueryPlanTest.livePageSeeksByRowidWithoutASort`: `EXPLAIN QUERY PLAN` of `EmbeddingDao.LIVE_PAGE_SQL` has no `TEMP B-TREE`, and `memory` is searched by its primary-key index.
- `lexicalCursorHasNoTempSort`: same check for the B2 query.

**Gate:** `./gradlew :services:memory:testDebugUnitTest`, then the full gate.

### Task B4: IN-list chunking (API 29 SQLite: 999 variables)

**Requirements:** R-70 · carries: `byIds`/`deleteByIds` unchunked IN (> 999 vars on old SQLite) — Phase 2 T3, T5; `IN(:ids)` unchunked (P2 T3).

**Files:** `SqlChunks.kt` (new), `Repositories.kt`; test `RepositoriesTest.kt`.

**Signatures:** `internal const val MAX_IN = 500`; `internal suspend fun <T> List<String>.inChunks(block: suspend (List<String>) -> List<T>): List<T>`; `internal suspend fun List<String>.forEachChunk(block: suspend (List<String>) -> Unit)`. Both de-duplicate first.

**Behaviour:**
- `FrameRepository.byIds` and `deleteByIds`, `MemoryRepository.deleteByIds` and `TraceRepository.deleteByIds` call the DAO with at most 500 ids per call.
- `FrameRepository.byIds` keeps `ts ASC` order across chunks (it sorts after merging).
- Empty input makes no DAO call (pin).

**Tests (RepositoriesTest):**
- `noDaoCallGetsMoreThanFiveHundredIds`: a spying DAO given 2 000 ids → every call ≤ 500, and the calls together cover all 2 000.
- `byIdsWithTwoThousandIdsReturnsAllInTsOrder`: 2 000 frames → 2 000 rows, sorted by ts.
- `deleteByIdsWithTwoThousandIdsDeletesAll`: memories and traces → count is 0 after.

**Gate:** `./gradlew :services:memory:testDebugUnitTest`, then the full gate.

---

## Stream C — Lifecycle and robustness

**Files (C owns):** `glasses/…/glasses/capture/{CaptureService,ForegroundFallback,CaptureScheduler,CaptureSession (new)}.kt`, `glasses/…/glasses/link/SharedBridge.kt`, `services/glasses-link/…/link/{CxrGlassesLink,GlassAppWatcher,SessionSlot (new)}.kt`, `phone/…/phone/hub/StateBroadcaster.kt`, `core/model/…/core/model/UserFacingError.kt` (the `ClosedOnGlasses` hint only). Tests: `glasses/src/test/…/glasses/capture/{CaptureSessionTest (new),ForegroundFallbackTest,CaptureSchedulerTest}.kt`, `…/glasses/link/SharedBridgeTest.kt`, `…/glasses/MainActivityTest.kt`, `services/glasses-link/src/test/…/link/SessionSlotTest.kt` (new), `phone/src/test/…/phone/hub/StateBroadcasterTest.kt`, `phone/src/test/…/phone/ui/banner/BannerResolverTest.kt`, `core/model/src/test/…/UserFacingErrorTest.kt`.
**Shared one-liners:** `glasses/…/MainActivity.kt` (`ensureCaptureScheduled()` returns a `Rearm`, and a `MISSED` result shows one toast), and `phone/…/ui/banner/Banners.kt` (the `CLOSED_ON_GLASSES` title string).
**Must not touch:** `OutboundBuffer.kt`, `FrameUplink.kt`, `GlassesRuntime.kt` (Stream A), `SessionInbound.kt` semantics, `GlassesSessionPolicy.kt`, `Hub.kt`, `HubGraph.kt`, `ContextHubService.kt`.

### Task C1: CaptureService — every texture released, refused starts, the right startId

**Requirements:** R-30 (single capture path), R-07 · carries: SurfaceTexture not released on bind failure (or on timeout/cancel); `stopSelf` without startId; startForeground-refused path after `startForegroundService`.

**Files:** `CaptureService.kt`, `CaptureSession.kt` (new); test `CaptureSessionTest.kt`.

**Signatures:**
- `internal class CaptureSession(private val unbind: () -> Unit, private val release: () -> Unit) : AutoCloseable`. `close()` is idempotent and runs both lambdas, each in `runCatching`.
- `internal fun startForegroundOrStop(start: () -> Unit, onRefused: () -> Unit): Boolean`

**Behaviour:**
- `captureOnce` creates the `SurfaceTexture` and immediately wraps it in a `CaptureSession` used with `use { }`. Bind failure, window timeout, an exception or cancellation all unbind and release exactly once. The provider's own surface-release callback stays and is harmless.
- `onStartCommand` calls `startForeground` through `startForegroundOrStop`. A throw (e.g. `ForegroundServiceStartNotAllowedException`, `IllegalStateException`) logs the class name, schedules the next capture with the failure cadence, and calls `stopSelf(startId)`.
- The service records `@Volatile lastStartId`. The capture's `finally` calls `stopSelfResult(lastStartId)`, so a start that arrived during the capture is not cut off early.
- A source rule: `SurfaceTexture(` appears in `CaptureService.kt` only inside a `CaptureSession(` construction expression.

**Tests (CaptureSessionTest):**
- `closeReleasesOnceEvenWhenCalledTwice`: counters are 1/1.
- `aBindFailureStillReleasesTheTexture`: the block throws → release is called and the exception propagates.
- `aThrowingUnbindStillReleases`: unbind throws → release is still called.
- `aRefusedStartSchedulesTheNextCaptureAndStops`: `start` throws → returns false and `onRefused` ran once.
- `surfaceTexturesAreOnlyCreatedInsideACaptureSession`: the source scan of `CaptureService.kt` passes.

**Gate:** `./gradlew :glasses:testDebugUnitTest`, then the full gate.

### Task C2: Foreground fallback stops pulling the HUD forward on persistent camera failure

**Requirements:** R-07 (never steal the foreground) · carry: the foreground fallback can keep pulling the HUD forward on persistent camera failure.

**Files:** `ForegroundFallback.kt`; one call in `CaptureService.kt` (`runtime.fallback.onCaptureSucceeded()` when the outcome is `BUFFERED`); test `ForegroundFallbackTest.kt`.

**Signatures:** `class ForegroundFallback(presence, clock, log, maxConsecutive: Int = 3)` with `fun onCaptureSucceeded()`.

**Behaviour:**
- Each launch without a successful capture since the previous one counts. After `maxConsecutive` launches, `maybeLaunch` returns false and logs once: "camera keeps failing in background; not reopening the HUD until a capture works".
- `onCaptureSucceeded()` resets the count. The 20 s cooldown and the presence rules are unchanged.

**Tests (ForegroundFallbackTest):**
- `afterThreeLaunchesWithoutACaptureItStopsLaunching`: the 4th call → false, with one log line.
- `aSuccessfulCaptureReArmsTheFallback`: 3 launches, a success → the next call launches.
- `cooldownStillApplies` (pin): a second call within 20 s → false.

**Gate:** `./gradlew :glasses:testDebugUnitTest`, then the full gate.

### Task C3: Force-stop by Rokid AssistServer — capture resumes on reopen and the user is told

**Requirements:** R-07, R-11 (banners), R-72 · carry (Phase 8): Rokid AssistServer force-stops our glasses app (closing = force-stop, which stops capture until reopened).

A force-stop cancels alarms and blocks every restart until the user opens the app; the platform allows no workaround. So the fix makes the stop visible and guarantees a clean restart. Ruling: capture is not attempted while the app is force-stopped. Cost if wrong: none (the platform forbids it).

**Files:** `CaptureScheduler.kt`, the `MainActivity.kt` one-liner, the `Banners.kt` one-liner, `UserFacingError.kt` (hint); tests `CaptureSchedulerTest.kt`, `MainActivityTest.kt`, `BannerResolverTest.kt`, `UserFacingErrorTest.kt`.

**Signatures:** `enum class Rearm { FRESH, ARMED, MISSED }`; `fun CaptureScheduler.ensureScheduled(): Rearm`.

**Behaviour:**
- `ensureScheduled` returns `MISSED` when the stored next time is more than `STALE_GRACE_MS` in the past (a force-stop or reboot lost the alarm), `FRESH` when nothing was stored, and `ARMED` otherwise. Its arming behaviour is unchanged.
- On `MISSED`, glasses `MainActivity` shows one HUD toast: "Capture resumed — it stops while the app is closed".
- The phone banner for `CLOSED_ON_GLASSES` reads "Closed on glasses · capture stopped", with the action "Open on glasses" unchanged. The `UserFacingErrors.ClosedOnGlasses` hint is "Capture stops until you open Proactive Context on your glasses."

**Tests:**
- `CaptureSchedulerTest.aTimeLostToAForceStopReportsMissedAndReArms`: stored time = now − 10 min → `MISSED`, and an alarm is set about 1 s out.
- `aFutureStoredTimeReportsArmed` (pin): now + 1 min → `ARMED`.
- `MainActivityTest.aMissedCaptureShowsTheResumeToastOnce`: `MISSED` → toast text shown once, and a second resume shows nothing.
- `BannerResolverTest.closedOnGlassesSaysCaptureStopped`: the title equals the new string.
- `UserFacingErrorTest.closedOnGlassesHintSaysCaptureStops`: the hint equals the new string.

**Gate:** `./gradlew :core:model:test :glasses:testDebugUnitTest :phone:testDebugUnitTest`, then the full gate.

### Task C4: SDK sessions, watcher links and the shared bridge never leak or go stale

**Requirements:** R-05, R-07 · carries: connect throwing after `session = s` never calls `s.close()` (Phase 3 T3 → Phase 8); `onConnectResult(false)` drops the session without closing it; `GlassAppWatcher` leaks a `CXRLink` on bind failure; `release()` doesn't replay connection state to a later controller.

**Files:** `SessionSlot.kt` (new), `CxrGlassesLink.kt`, `GlassAppWatcher.kt`, `SharedBridge.kt`; tests `SessionSlotTest.kt`, `SharedBridgeTest.kt`.

**Signatures:**
- `internal class SessionSlot<S : Any>(private val close: (S) -> Unit) { val current: S?; fun replace(new: S?); fun clearIf(s: S): Boolean; fun fail(s: S) }`. Every path that drops a session closes it exactly once, and `close` throws are swallowed and logged.
- `SharedBridge` gains `@Volatile private var lastConnected: Boolean? = null`.

**Behaviour:**
- `CxrGlassesLink` keeps its session in a `SessionSlot` whose close is `inbound.close(s); s.close()`. In `openSession`, the created session is tracked from creation: a throw in `create`, in `inbound.open` setup or in `connect` calls `slot.fail(s)`.
- `onConnectResult(ok=false)` and `onSessionClosed` call `slot.clearIf(s)`. `close()` calls `slot.replace(null)`. The old-session-first ordering is kept.
- `GlassAppWatcher.start`: when `connect` returns false or anything throws after the `CXRLink` is built, it calls `runCatching { l.disconnect() }`. `link` stays null.
- `SharedBridge` records the last inner connected/disconnected callback. `setConnectionListener` replays it to the new listener at once; with no state yet, nothing is replayed. `release()` keeps `lastConnected`.

**Tests:**
- `SessionSlotTest.aSetupThrowClosesTheCreatedSession`: `fail(s)` → close count 1 and `current == null`.
- `aFailedConnectClosesTheSession`: `clearIf(s)` on the current session → closed once.
- `replacingClosesTheOldSession`: `replace(b)` after `a` → `a` closed, and `b` is current.
- `aStaleSessionIsNotClosedTwice`: `clearIf(a)` after `replace(b)` → `a` closes once in total.
- `aThrowingCloseIsSwallowed`: close throws → no exception and the slot is cleared.
- `SharedBridgeTest.aLaterListenerIsToldTheBridgeIsAlreadyConnected`: connected, release, new listener → its `connected` runs once.
- `aLaterListenerIsToldItIsDisconnected`: the same check for disconnected.
- `noReplayBeforeAnyState`: a fresh bridge → neither callback runs.
- `GlassAppWatcher` is SDK-bound: `aFailedWatcherBindDisconnects` is a device check (D-C4) unless the CXR jar loads under Robolectric. If it loads, add it as a unit test (implementer's call; record the ruling).

**Gate:** `./gradlew :services:glasses-link:test :glasses:testDebugUnitTest`, then the full gate.

### Task C5: The state broadcaster survives a throwing error handler

**Requirements:** R-06 (≤ 4 Hz full frames) · carry: the broadcaster's `catch(Exception)` lets a throwing `onError` kill the loop (HUD frozen).

**Files:** `StateBroadcaster.kt`; test `StateBroadcasterTest.kt`.

**Behaviour:**
- `onError(e)` runs inside `runCatching`. Its own failure is logged with the class name only.
- A `send()` exception never ends the loop. `CancellationException` still ends it. `Error`s still crash, by design: the hub FGS restarts.

**Tests:**
- `aThrowingOnErrorDoesNotStopBroadcasting`: `send` throws once, `onError` throws → a later `markDirty` is still sent.
- `cancellationStillStopsTheLoop` (pin).

**Gate:** `./gradlew :phone:testDebugUnitTest`, then the full gate.

---

## Stream D — Security, privacy and data integrity

**Files (D owns):** `services/ai/…/ai/{EndpointSeeder,EndpointRepository,KeyVault,ModelRouter}.kt` (in `ModelRouter.kt`, only `safe` and the failure-path key read), `services/ai/…/model/{ModelProviderResolver,ModelGatewayFactory}.kt` (deleted), `services/orchestration/…/orchestration/{LegacyKeySeed,LegacyImportWorker}.kt`, `services/orchestration/…/data/runtime/AppConfigStore.kt` (`legacySecretHasKey` + KDoc), `phone/…/phone/ui/settings/Logs.kt` (`LogReader.SECRET`), `services/memory/…/memory/DbKeyStore.kt`, `services/memory/…/memory/db/{DatabaseFactory,DeferredOpenHelperFactory,EncryptionMigrator}.kt` (passphrase handling only), `services/memory/…/memory/legacy/LegacyImporter.kt`. Tests: `services/ai/src/test/…/{ai/EndpointSeederTest,ai/EndpointRepositoryTest,ai/ModelRouterTest,model/ModelProviderResolverTest (deleted)}.kt`, `services/orchestration/src/test/…/orchestration/{LegacyKeySeedTest,LegacyImportWorkerTest (new)}.kt`, `phone/src/test/…/phone/ui/settings/LogRedactTest.kt`, `phone/src/test/…/NoSecretsInBuildConfigTest.kt`, `services/memory/src/test/…/memory/{DbKeyStoreTest (new),DatabaseFactoryTest (new),MemoryGraphImportTest (new),legacy/LegacyImporterTest}.kt`.
**Shared one-liners:** `phone/build.gradle.kts` (a `secretValue` guard beside `secretName` in `publicField`) and `MemoryGraph.kt` (`ensureLegacyImported` body only).
**Must not touch:** `RetryClassifier.kt`, `EndpointTester.kt`, `RoleBindingRepository.kt`, `EndpointEditor.kt`, `PipelineRecords.kt` (Stream A), the rest of `MemoryGraph.kt`.

### Task D1: Legacy key retirement and seeding (Phase 5 Task 21 carry)

**Requirements:** R-40 (keys encrypted, `.env` seed), R-72 · carry (Phase 8): dead `ModelProviderResolver`/`ModelGatewayFactory`; a keyset-only legacy secret file on a keyed kind still opens the Keystore each start; the legacy key can be copied back after the user removed it from the seeded endpoint; an `endpoints.save` rejection retries silently each start; stale KDoc naming `get()`; `EndpointSeeder` `runCatching` swallows `CancellationException` (P5 T7, T9).

**Files:** `EndpointSeeder.kt`, `LegacyKeySeed.kt`, `AppConfigStore.kt`; delete `ModelProviderResolver.kt`, `ModelGatewayFactory.kt` and `ModelProviderResolverTest.kt`. Delete `ModelGateway.kt` too only if a grep shows nothing else uses it. Tests `EndpointSeederTest.kt`, `LegacyKeySeedTest.kt`.

**Signatures:**
- `interface SeedState { fun isDone(): Boolean; fun markDone(); fun keyRetired(): Boolean = false; fun markKeyRetired() {} }`. `PrefsSeedState` stores `key_retired`; the in-memory version keeps a field.
- `fun AppConfigStore.legacySecretHasKey(context: Context): Boolean` parses the prefs XML entry names only. It is true when any entry other than the two `__androidx_security_crypto_encrypted_prefs_*_keyset__` names exists, and it never opens the Keystore.

**Behaviour:**
- `LegacyKeySeed.run`: file present and `!legacySecretHasKey` → `clearLegacySecret` without a Keystore open, then the normal no-file path.
- `retireLegacyKey`: when `state.keyRetired()` it returns true without reading or copying. Once it has decided (copied, or found the endpoint with a key, or deleted), it calls `markKeyRetired()` before returning true.
- `seedIfNeeded`: an `IllegalArgumentException` from `EndpointRules`/`UrlNormalizer` (bad legacy config) logs once and marks done, so it is not retried every start. The roles stay unbound and the existing "No model is set" path applies. Other failures still retry next start.
- `seedIfNeeded` and `retireLegacyKey` rethrow `CancellationException`.
- The KDoc that still names `AppConfigStore.get()` is corrected to `getWithoutSecret`/`getWithLegacySecret`.

**Tests:**
- `EndpointSeederTest.aKeyRemovedByTheUserIsNeverCopiedBack`: retire once, the user clears the key, retire again with the legacy key → the vault has no key.
- `aRejectedLegacyConfigIsNotRetriedEachStart`: an `http://remote` endpoint with a key → `isDone()` is true after the first call, and the second call does no repository writes.
- `anIoFailureIsRetriedNextStart`: the vault throws `IllegalStateException` → `isDone()` is false.
- `cancellationPropagatesFromSeed`: the repository throws `CancellationException` → it propagates.
- `LegacyKeySeedTest.aKeysetOnlyFileIsDeletedWithoutOpeningTheKeystore`: a fixture XML with only keyset entries, and a config whose `getWithLegacySecret` throws → the file is gone and no throw.
- `deadGatewayClassesAreGone`: source scan → no `ModelProviderResolver.kt` and no `ModelGatewayFactory.kt` under `services/ai/src/main`.

**Gate:** `./gradlew :services:ai:testDebugUnitTest :services:orchestration:testDebugUnitTest`, then the full gate.

### Task D2: Endpoint key lifecycle — no throwing delete, no orphan keys

**Requirements:** R-40 · carries: `EndpointRepository.delete` can throw from `vault.remove`; delete ordering leaves an orphan key on a crash; save is not atomic (row before key) (P2 T6, P5 carries).

**Files:** `EndpointRepository.kt`, `KeyVault.kt`, `LegacyKeySeed.kt` (one call to the sweep after seeding); test `EndpointRepositoryTest.kt`.

**Signatures:** `interface KeyVault { …; fun refs(): Set<String> = emptySet() }`, overridden by `EncryptedKeyVault` (`prefs.all.keys`) and `InMemoryKeyVault`. `suspend fun EndpointRepository.sweepOrphanKeys(): Int`.

**Behaviour:**
- `save` with a new key: the vault write comes first, then the row. If the row write throws, the new key is removed (best effort), so no key exists without a row. With a null/blank key, the row-then-remove order is unchanged.
- `delete(id)`: remove the key first, catching and logging (`SafeLog`) any vault failure, then delete the row. It never throws for a vault failure.
- `sweepOrphanKeys` removes every `key:<id>` ref with no endpoint row, off Main. `LegacyKeySeed.run` calls it after seeding.

**Tests (EndpointRepositoryTest):**
- `deleteNeverThrowsWhenTheVaultFails`: `remove` throws → the row is gone and no exception.
- `deleteRemovesTheKeyBeforeTheRow`: the order is recorded by fakes as `[vault.remove, dao.delete]`.
- `aFailedRowWriteLeavesNoNewKey`: the DAO throws on upsert → `vault.get` is null.
- `orphanKeysAreSweptAtStartup`: refs `key:a` and `key:b`, row `a` only → `key:b` removed, and the result is 1.
- `sweepKeepsKeysOfLiveEndpoints` (pin): `key:a` stays.

**Gate:** `./gradlew :services:ai:testDebugUnitTest :services:orchestration:testDebugUnitTest`, then the full gate.

### Task D3: Secret redaction leftovers — encoded keys, JNI log lines, `.env` values

**Requirements:** R-40, R-72 · carries: `keyFor()` in the failure path could mask the original error; redaction is exact-match only (URL-encoded or partial keys not masked) (P5 T7); base64 redaction drops JNI descriptor lines (T21); the BuildConfig guard is name-only, so values with embedded credentials (`user:pass@`, `?key=`) are not rejected (P5 T2b).

**Files:** `ModelRouter.kt` (`safe`, failure-path key), `Logs.kt` (`LogReader`), `phone/build.gradle.kts` (guard); tests `ModelRouterTest.kt`, `LogRedactTest.kt`, `NoSecretsInBuildConfigTest.kt`.

**Signatures:** `fun ModelRouter.safe(t: Throwable?, vararg keys: String?): String` keeps its signature. `internal fun LogReader.isLikelyBase64Secret(run: String): Boolean`. In the gradle file: `val secretValue = Regex("(?i)(://[^/@\\s]+@|[?&](key|api[-_]?key|token|sig|code|password)=)")`.

**Behaviour:**
- `safe` masks each key, its `URLEncoder.encode(key, "UTF-8")` form, and its Base64 form (`Base64.getEncoder()`), as `***`.
- The test-call failure path reads the key once, before the call. If `keyFor` itself throws in the failure path, the original error's text is kept: `runCatching { … }.getOrNull()`, rethrowing cancellation.
- `LogReader`: a 40+ char `[A-Za-z0-9+/]` run drops the line only when `isLikelyBase64Secret` holds. That means it has a digit, an upper-case and a lower-case letter, and it is **not** a `/`-separated identifier path (every segment matching `[A-Za-z_$][A-Za-z0-9_$]*`). All other `SECRET` alternatives are unchanged.
- `publicField` also `require`s `!secretValue.containsMatchIn(value)`, with a message that names the variable and never the value.

**Tests:**
- `ModelRouterTest.aUrlEncodedKeyIsMasked`: key `a+b/c=d…` encoded in the message → `***`.
- `aBase64EncodedKeyIsMasked`: Basic-auth style → `***`.
- `aKeystoreFailureInTheFailurePathKeepsTheOriginalError`: the vault throws on the second `keyFor` → the message is from the provider error, not the vault.
- `LogRedactTest.jniDescriptorLinesAreKept`: `Lcom/rokid/cxr/client/extend/listeners/SomeCallbackInterface;` is kept.
- `aRealBase64KeyLineIsDropped` (pin): a random 44-char key → dropped.
- `NoSecretsInBuildConfigTest.theGradleValueGuardRejectsUserinfoAndKeyParams`: reads the `secretValue` literal from `phone/build.gradle.kts` → it matches `https://u:p@h`, `https://h/?key=x`, and does not match `http://10.0.2.2:11434`.
- `buildConfigValuesCarryNoCredentials`: every String `BuildConfig` field fails `secretValue`.

**Gate:** `./gradlew :services:ai:testDebugUnitTest :phone:testDebugUnitTest :phone:assembleDebug`, then the full gate.

### Task D4: SQLCipher passphrase hygiene

**Requirements:** R-70 (SQLCipher when encryption is on) · carries: the SQLCipher passphrase is not zeroed; `loadLibrary` runs on every call; `EncryptedSharedPreferences` is created per call (P2 T1, T3).

**Files:** `DbKeyStore.kt`, `DatabaseFactory.kt`, `DeferredOpenHelperFactory.kt`, `EncryptionMigrator.kt` (passphrase handling only); tests `DbKeyStoreTest.kt`, `DatabaseFactoryTest.kt`.

**Signatures:** `object DbKeyStore { fun passphrase(context: Context): ByteArray }`, unchanged, with an internal `prefsFactory` seam and the prefs cached per process. `internal object SqlCipherLoader { fun ensureLoaded(load: (String) -> Unit = System::loadLibrary) }`.

**Behaviour:**
- `System.loadLibrary("sqlcipher")` runs at most once per process.
- `DbKeyStore` creates its `EncryptedSharedPreferences` once (lazy, synchronized) and returns a fresh copy of the passphrase per call.
- Each copy our code holds (the factory's, the migrator's) is zeroed with `fill(0)` in `finally` once SQLCipher has consumed it. First check whether `SupportOpenHelperFactory` in sqlcipher-android 4.6.1 keeps the array for a later reopen. If it does, hand the factory its own copy, zero ours, and record the ruling. The deferred factory re-reads the passphrase lambda for each open.
- The real SQLCipher path cannot run under Robolectric (native lib). It is covered by device check D-D4.

**Tests:**
- `DbKeyStoreTest.passphraseIsStableAcrossCalls`: two calls → equal contents, and different array instances.
- `prefsAreCreatedOnce`: the factory counter is 1 after 3 calls.
- `DatabaseFactoryTest.libraryIsLoadedOnce`: the loader counter is 1 after 2 encrypted builds (`ensureLoaded` called directly).
- `ourPassphraseCopyIsZeroedAfterUse`: the injected passphrase provider's array is all zeros after `ensureOpenParams` (the extracted helper).

**Gate:** `./gradlew :services:memory:testDebugUnitTest`, then the full gate.

### Task D5: Legacy import — nothing lost, nothing blocks, failures retry

**Requirements:** R-71 (legacy import worker), R-03 · carries: a non-numeric confidence line is skipped (permanently lost) where legacy tolerated 0.0; the worker has no try/catch → `Result.retry()`; per-row inserts and whole-file `readLines()`; "synthetic"/"screenshot" matched on the full path (`LegacyImporter.isRealPhoto`); a failing `importAll` rethrows from every facade call; the eager import holds `importLock`, so a main-thread facade call during startup could block (P2 T4, T5).

**Files:** `LegacyImporter.kt`, `MemoryGraph.kt` (`ensureLegacyImported` body), `LegacyImportWorker.kt`; tests `LegacyImporterTest.kt`, `MemoryGraphImportTest.kt` (new), `LegacyImportWorkerTest.kt` (new).

**Signatures:**
- `fun MemoryGraph.Companion.ensureLegacyImported(context: Context, isMain: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }): ImportReport?`
- `LegacyImportWorker.doWork()` keeps its signature. `const val MAX_RUNS = 3`.

**Behaviour:**
- Files are read with `useLines`, streaming. Rows are inserted in `withTransaction` batches of 500, and the existing resume/dedupe guarantees hold.
- A non-numeric or missing confidence imports as `0.0`, the legacy tolerance, instead of being skipped. Other malformed lines are still skipped and counted.
- `isRealPhoto` applies the word test to `File(path).name` only.
- `ensureLegacyImported` on Main returns `null` without taking `importLock`. The worker imports. A failed import is logged with `SafeLog` and returns `null` to callers, never throwing. `isDone` stays false, so the worker or the next process retries. Facades make at most one attempt per process.
- `LegacyImportWorker`: an exception → `Result.retry()` while `runAttemptCount < MAX_RUNS - 1`, then `Result.failure()`, logging the class only. `CancellationException` is rethrown. The embedding enqueue happens on success only.

**Tests:**
- `LegacyImporterTest.aNonNumericConfidenceImportsWithZero`: `"id|type|abc|…"` → a memory with confidence 0.0, and `skipped` unchanged.
- `aLargeLogImportsInBatches`: 2 600 lines with a transaction spy → 6 transactions and 2 600 rows.
- `aParentFolderNamedSyntheticDoesNotHideRealPhotos`: `/sdcard/synthetic-tests/rokid_1.jpg` (existing file) → imported.
- `MemoryGraphImportTest.ensureLegacyImportedOnMainDoesNotImport`: `isMain = { true }` → null, and the importer was never called.
- `aFailingImportDoesNotThrowToCallers`: the importer throws → null, then a retry on the worker path succeeds.
- `LegacyImportWorkerTest.aFailingImportRetries`: run 0 → `Result.retry()`.
- `afterThreeAttemptsItFails`: run 2 → `Result.failure()`.

**Gate:** `./gradlew :services:memory:testDebugUnitTest :services:orchestration:testDebugUnitTest`, then the full gate.

---

## Stream E — Delivery (R-73, R-74, R-75)

**Files (E owns):** `phone/src/test/java/com/rokid/context/phone/ui/shell/AllRoutesSmokeTest.kt` (new), `core/hud/src/test/kotlin/com/rokid/context/core/hud/render/HudFixturePagesTest.kt` (new), `tools/gate.sh` (new), `tools/device-checklist.sh` (new), `tools/relocate-legacy-screens.sh` (new), `docs/screens/phone/.gitkeep`, `docs/screens/hud/.gitkeep`, `.gitignore` (one line: `/docs/screens/legacy/`).
**Must not touch:** any `src/main` file. If a smoke test finds a real crash, report it and do not fix it in E: the owning stream or Phase 7 fixes it.
**Timing:** E1 can be written now. Merge it **after Phase 7**, so its route enumeration covers Phase 7's screens. E2 and E3 are independent.

### Task E1: UI smoke completion — every route, every HUD page and overlay

**Requirements:** R-73 (§6.4 Compose UI: smoke per screen, HUD pages from fixture StateFrames) · master plan row "8 (UI smoke suite completion)".

**Files:** `AllRoutesSmokeTest.kt`, `HudFixturePagesTest.kt`.

**Behaviour:**
- `AllRoutesSmokeTest` reflects every `const val` String in `Routes`, which picks up Phase 7's routes after merge. Each placeholder is filled from a fixture map: `{id}` → a seeded fixture id, `{date}` → today, `{period}` → `week`, `{start}` → a seeded frame id. A placeholder missing from the map fails the test and names the route.
- Each route is rendered in the real `AppShell` NavHost, under Robolectric with an in-memory DB and the existing fixture `PhoneDeps`. It must compose without throwing, show at least one node, and show no text matching `Exception|java\.|kotlin\.|at com\.rokid` (§6.3).
- `HudFixturePagesTest` renders Glance, Today and Status × Silent/Medium/Max × each `Overlay` subtype (`Listening`, `Answer`, `Nudge`, `Confirm`, `PausedSplash`, `Toast`) from fixture `StateFrame`s through `HudRoot`. It must compose, and no text may match the raw-error pattern.
- Onboarding is covered by the route test once Phase 7 lands. Its flow tests stay Phase 7's (R-19).

**Tests:**
- `everyRouteRendersWithoutCrashOrRawErrorText`: one assertion per route, and the failure message names the route.
- `everyRoutePlaceholderHasAFixture`: there are no unknown `{…}` tokens.
- `everyHudPageAndOverlayRendersFromFixtures`: all 3 × 3 + 6 cases compose.

**Gate:** `./gradlew :phone:testDebugUnitTest :core:hud:testDebugUnitTest`, then the full gate.

### Task E2: Gate watchdog for the intermittent stall

**Requirements:** R-73 (reliable suites) · carry (WATCH): an unexplained 30-min gate stall, seen in Phase 6 Task 11 and Task 17 rounds (a possible intermittent hang). The test tasks already time out at 10 min (`build.gradle.kts`), so the stall is outside test execution: compile, KSP, or the daemon.

**Files:** `tools/gate.sh`.

**Behaviour:**
- `tools/gate.sh <log> [gradle args…]` runs `./gradlew` in `android-companion/` (default args: the full gate) with `ANDROID_HOME` set, writing into `<log>`, and appends `EXIT=<code>`.
- Every `CHECK_SECS` (60) it checks the log size. After `STALL_SECS` (480) without growth, it appends `jps -l` plus a `jstack` of every Gradle daemon and worker JVM to `<log>.stall`, kills the run, and exits with 124.
- `GATE_CMD`, `CHECK_SECS` and `STALL_SECS` can be overridden from the environment, for the self-test only.

**Tests (shell, run by the implementer):**
- `bash -n tools/gate.sh` passes.
- `GATE_CMD="sleep 5" CHECK_SECS=1 STALL_SECS=2 tools/gate.sh <scratch>/g.log` → exit 124, and `<scratch>/g.log.stall` exists.
- `GATE_CMD="true" tools/gate.sh <scratch>/ok.log` → `EXIT=0` in the log, and no `.stall`.

**Gate:** the three shell checks, then the full gate via `tools/gate.sh`.

### Task E3: On-device checklist script, screenshot references and legacy relocation

**Requirements:** R-75 (scripted adb checklist, phone + glasses), R-74 (references in `docs/screens/phone/` and `docs/screens/hud/`; legacy root files → `docs/screens/legacy/`).

**Files:** `tools/device-checklist.sh`, `tools/relocate-legacy-screens.sh`, `docs/screens/{phone,hud}/.gitkeep`, `.gitignore`.

**Behaviour:**
- `device-checklist.sh` takes `PHONE_SERIAL`/`GLASSES_SERIAL` from the environment (`adb devices` must list both) and `--dry-run` (print the steps, no adb). `--only <id>` runs one step.
- Each step prints its instruction, waits for Enter, and checks logcat markers from both devices (e.g. `frame ingested`, `delivered`, `session closed GLASSES_EXIT`). It records PASS/FAIL/SKIP in `docs/screens/checklist-<yyyymmdd>.txt` and takes the named screenshots. Phone: `adb exec-out screencap -p`. Glasses: the same, on the glasses serial.
- **Steps (spec §6.4 + Phase 8):** link; capture→memory; ask→answer→TTS; nudge; action confirm (incl. simultaneous phone + HUD response); pause/resume; offline pause on glasses then reconnect (stays paused); offline buffer flush incl. a mid-frame link drop; Forget fact vs Forget moment; plus D-A1…D-E3 below.
- Screenshot references are captured by the script: Home, Activity, Day, Memory detail, Ask detail, Frames, Settings root, each Settings screen, and Onboarding first step (phone, `docs/screens/phone/<route>.png`); Glance, Today, Status, Listening, Answer, Confirm, Paused splash (HUD, `docs/screens/hud/<page>.png`).
- `relocate-legacy-screens.sh <repo-root>` moves the root `phone-*.png`, `window-*.xml`, `.tmp_glasses_*.png` and `check-*.png` into `docs/screens/legacy/`. It is idempotent and prints the count.
- Ruling: `docs/screens/legacy/` is git-ignored. The files total 15 MB of dev captures with no reference value, and the spec asks only that they be moved. Cost if wrong: one commit to add them.

**Tests (shell):**
- `bash -n` passes on both scripts.
- `tools/device-checklist.sh --dry-run` lists every step id with no adb call.
- `tools/relocate-legacy-screens.sh <scratch-fixture>` on a fixture root with 3 files moves 3; a second run moves 0.

**Gate:** the shell checks, then the full gate. The controller runs the script on devices (PENDING-DEVICE) and commits `docs/screens/{phone,hud}/*.png` and the checklist result.

---

## Carry triage

Every line of the former `.p8-carries-tmp.md` is accounted for: either in a task above, covered elsewhere, or under "Won't fix in v1".

### Included (task)

| Carry | Task |
|---|---|
| 72 h cap on glasses clock; no 4 MB cap; disk-full drops newest; dir fsync (Final; P6 T8) | A1 |
| Orphan `.part`; parent dir not fsynced (Final; P3 T6) | A4 |
| Unreadable JPEG deleted; lost kick race; crash accounting; clear during ack wait; poison drop ends flush (P6 T8 ×2) | A2 |
| `bufferedFrames` one too high; post-schedule hello buffered=1 (P3 T11 ×2) | A2 |
| Reassembler caps; ledger init can throw / no fsync / leftover `.tmp`; edge tests (P3 T4) | A3 |
| `MainActivity.onCreate` reads the ledger on Main (P3 T11) | A3 (+ device check D-A3) |
| Frame mid-wipe orphans JPEG/row (Final) | A4 |
| Archived-but-failed ingest never retried (Final) | A4 |
| "synthetic"/"screenshot" full-path match — `PipelineRecords` (P2 T5) | A4 |
| Streaming export; asks capped 10 k (P5 T20 → P8) | B1 |
| Lexical paging re-reads FTS doclists; BM25 stats per page (P5 T10 → P8 perf) | B2 |
| Vector paging at scale (brief) | B3 |
| `byIds`/`deleteByIds`/`IN(:ids)` unchunked (P2 T3 ×2, T5) | B4 |
| SurfaceTexture release (Final; P3 T11 bind failure) | C1 |
| `stopSelf` without startId; startForeground-refused path (P3 T11; T6) | C1 |
| Foreground fallback keeps pulling the HUD forward (P3 T11) | C2 |
| Rokid AssistServer force-stop stops capture (Final; P3 T11) | C3 |
| Connect throwing never closes the SDK session (P2/P3 T3 → P8) | C4 |
| `GlassAppWatcher` leaks `CXRLink` on bind failure (P3 T3) | C4 |
| `release()` doesn't replay connection state to a later controller (P4 T10) | C4 |
| Broadcaster: a throwing `onError` kills the loop (P2 T5) | C5 |
| Dead resolver/factory; keyset-only file opens the Keystore; key copied back; save rejection retried silently; stale KDoc (P5 T21 → P8) | D1 |
| `EndpointSeeder` `runCatching` swallows `CancellationException` (P5 T7, T9) | D1 |
| `EndpointRepository.delete` throws; orphan key on crash; save not atomic (Final; P5 T6) | D2 |
| Base64 redaction drops JNI descriptor lines (P5 T21) | D3 |
| `keyFor()` in the failure path masks the error; redaction exact-match only (P5 T7) | D3 |
| BuildConfig guard name-only (embedded creds in values) (P5 T2b) | D3 |
| SQLCipher passphrase not zeroed; `loadLibrary` every call; path untested (P2 T1) | D4 (+ D-D4) |
| `EncryptedSharedPreferences` created per call (P2 T3) | D4 |
| Legacy import: non-numeric confidence lost; no retry; per-row inserts / `readLines`; full-path photo match; facade rethrow; main-thread block (P2 T4, T5 ×3) | D5 |
| WATCH: intermittent 30-min gate stall (P6 T17) | E2 |
| Device checks: long-press Hi Rokid with KEYCODE 83; backgrounded-not-finishing keeps the session open; transformable inside `verticalScroll`; `CxrSessionManager.getInstance` before the BT grant (P4 T10, P3 T11, P4 T8) | Device checks D-C3, D-C4, D-E1, D-A3 |

### Covered elsewhere (no Phase 8 work)

| Carry | Where |
|---|---|
| `AppConfigStore.get` on Main decrypts prefs (Final) | Already fixed: `getWithoutSecret` reads plain prefs only |
| `QuietHours` minutes not validated (Final) | Phase 7 (quiet hours, R-65) |
| First-audio-after-idle 1.19 s stall (Final) | Phase 6 Task 8 (tolerated, pinned) |
| Quick pause→resume double tap; `LegacyPipelineControl` caches `enabled`; resync/`setEnabled` races (Final; P4 T10; P3 T4 ×2) | Phase 6 Task 4 (requested state) |
| "offline" while CONNECTING (P4 T10) | Phase 6 Task 2 |
| `togglePause` on Main; phone-merged pause keeps buffered frames; unknown origin → PHONE; QUIET_HOURS ids; interval transactions; `PauseState` placement (P3 T11; P2 T5 ×2; P3 T7) | Phase 7 (pause merge, R-65) |
| µs rebuild race (P5 T11) | Phase 6 Task 14 |
| `AskTextTest` raw literals; sanitizer misses `\r`; Mn look-alikes; raw memory text under RetrievedContext (P5 T12; P2 T3; P5 T6; P2 T5) | Phase 6 Task 13 + Phase 5 data blocks |
| KDoc indent; `CodeBox` stale records; slider/hint; stale Test result; `lastRun`; 5 s bounds; backfill ids; reentrant lock (P5 T13, T15–T20) | Phase 6 Task 15 |
| `humanize` one level; OPENAI_COMPAT / llama.cpp rulings; schema/contract carries; `SAFE_PATH`; empty table coverage; queued native call; untick guard (P5 T1–T9, T17) | Phase 6 Task 14 |
| Old-session late messages re-arm `listenAccepted` (P3 T3) | Phase 6 Task 3 |
| Long-press ENTER emits Tapped (P3 T9) | Phase 6 Task 2 |
| `AskDao.upsert` resets rating (P2 T3) | Already fixed: field-level `AskDao.complete` |
| Cancellations/policy deferrals consume the 8-attempt cap; cancellation increments `attemptCount`; no cap on RETRY (P5 T2; P2 T8) | Already fixed: `QueueRuntimeStore` deferral path + `RetryPolicy.MAX_ATTEMPTS` |
| `PERMANENT_TEXT` matches inside 5xx bodies; `RetryClassifier` duplicates `ProviderErrors` (P2 T8) | Phase 7 (owns `RetryClassifier` wording carry) |
| db-journal / `app_config_secret` backup gap (P5 T7) | Already fixed: commits `03376c5`, `b3d092c` (`BackupRulesTest`) |
| `MemoryDao.recent/all` include INVALIDATED; no FKs/cascades on `memory_source`/`memory_vector`; 👎 critic payload excludes INVALIDATED (P2 T1 ×2; P5 T6) | Phase 7 (Forget, R-77) |
| `LegacyConfigBridge` overlapping syncs / debounce / old key; `LegacyConfigScreen` `onClearApiKey` (P2 T9 ×3) | Obsolete: removed in Phase 5 |

### Won't fix in v1

1. "With flush suspended" case reads "alone" in the spike notes: spike wording only, no behaviour.
2. 2 s retry backoff not in spec §1.8: the behaviour is intended; spec wording only.
3. `:glasses:lintDebug` fails on `PermissionImpliesUnsupportedChromeOsHardware`: lint is not in the gate, and the glasses never run on ChromeOS.
4. `AppConfigDefaultsTest` mutates global `EnvDefaults` without reset: test hygiene, no user effect.
5. `memory/` route always highlights the Activity tab: cosmetic tab highlight.
6. `HomeScreen` Rounded vs Outlined icons: the Outlined rule is for the glasses UI; the phone keeps Material3 Rounded.
7. `FrameViewer` reads the zone once: the label is stale only until the viewer is reopened after a zone change.
8. China Hi Rokid variant not marked "not observed": doc note only.
9. `PromptTemplateRepository` silent asset fallback: the class no longer exists (replaced by `PromptRepository` in Phase 5).
10. `Protocol.kt:6` KDoc on incompatible changes: doc only, and `Protocol.kt` is Phase 7's.
11. `SwipeClassifier.kt:10` cites the wrong Live Fit section: doc only.
12. `Wire` accepts `"v":"1"` as a string: lenient decode of our own payloads, no user effect.
13. Blocking `execute()` ignores cancellation: its results are discarded, so nothing visible happens.
14. `frameIdOf` no longer sanitizes commas: only `ingestSinglePath` (adb/debug) can pass such a path; production names are generated.
15. p95 rise should read +46–110 ms: spike doc number.
16. Post-reconnect throughput halving is a single observation: spike doc caveat.
17. WorkManager-before-`Application.onCreate` `EnvDefaults` race; no manifest-name test: theoretical, since `ContextApp` provides the WorkManager configuration.
18. §3.6 offline line should say "flush waits for session open": spec wording.
19. Cold-start 84 dp fallback ~14 dp short for one frame: one-frame cosmetic.
20. No permission rationale branch ("Allow" always opens settings): the flow works; polish.
21. Hint flashes on first launch: cosmetic.
22. Untested pipeline-gated scheduling / malformed nav arg at shell level / CloseGuard noise in `AppShellTest`: test gaps only (E1 adds route smoke).
23. HUD presenter mutates state during composition: no visible defect; E1's HUD smoke would surface a crash.
24. Filled REC dot: plan-mandated design.
25. Redundant foundation dependency, `enableUnitTest` deprecation warning, interim `nextCaptureAt` polling: build/interim only.
26. Pause stress test non-deterministic: test only.
27. Unknown battery fails open: intended and documented in `CaptureService`.
28. `PendingIntent` deprecation warning in a test: test only.
29. Older broadcast-form legacy alarm (`FIRE_PULSE getBroadcast`) not cancelled: it fires at a missing receiver and is dropped.
30. `RfcommGoneTest` scope, legacy bridge wording, possible unused imports: test/tidy only.
31. `DatabaseFactory.inMemory` with `allowMainThreadQueries` in the main source set: no production caller.
32. Test fakes (`FakeGlassesStatusSource` counter, `Fixtures.jpg()` bytes, close warnings): test only.
33. `FrameCodec.join` doesn't check `chunks.size`; bad-Base64 test cases: the reassembler joins only complete frames; test gaps.
34. Non-discriminating FTS regression test; stale `// REPLACE` comment: test only.
35. 32 dp pause target, bar chart not TalkBack-reachable, label/value mismatch, `pointerInput` restarts, EXIF orientation: accessibility/visual polish for v1.1 (glasses frames carry no EXIF rotation).
36. `ModelErrorMapper` matches phrases anywhere: worst case is a wrong preset text that is still a `UserFacingError`.
37. Policy deferral text not `UserFacingError`-mapped; brittle `"500"` assertion: Diagnostics-only text and a test.
38. Phase 1 `Basics.kt` `AutoMirrored.Rounded` icons: phone module; the Outlined rule is glasses-only.
39. No FK `role_binding.endpointId → endpoint`: the router's deleted-endpoint contract handles it; an FK needs a migration.
40. No RED run, thin `SchemaTest`, DAO upsert/delete untested: process/test gaps.
41. `Pages.kt` status glyph labels OUTDATED as offline: a version mismatch shows its own "Update" screen.
42. No-arg `OpenAiCompatProvider()` has an empty key: no production caller.
43. `AI_ASSIST` pause shown as `PAUSED_BT`: same user meaning (link paused).
44. JSON without `v` → VersionMismatch UX: intended, fail closed.
45. `SharedPreferences` token read on Main in the link: one small prefs read, no ANR observed.
46. No `CxrGlassesLink` tests: SDK-bound; C4 extracts `SessionSlot` and the device checks cover the rest.
47. `setRating` returns Unit; chatty table-level Flow; live-Flow test without timeout; `addIgnore` unasserted: no user effect.
48. `GlassesSessionPolicy` imports an SDK enum: layering nit.
49. `MemoryEdits.memories` param unused; `\s` misses NBSP in edits: tidy; NBSP renders correctly.
50. Thin `RepositoriesTest`; "flag written before build": test gap, and the encryption migrator re-checks the file at open.
51. Loose `skipped>=3`; partial-import fixtures; no trace-skip fixture: test gaps (D5 adds the worker test).
52. Reassembler sweep allocations; whole-file ledger rewrite (≤ 1 000 ids); fragile `setWritable` test: negligible cost / test only.
53. `PAUSED_BT` capture line untested; `StorageUsage` happy-path test only: test gaps.
54. `AiHealthRule` `maxBy` tie nondeterministic: either tied rule shows the same severity.
55. Duplicate `frameId` append skips `incFrameCaptured`; `recent()` order; 2× over-fetch; facade tests: stats/perf negligible.
56. `ensureLegacyImportedRunsImportOnlyOnce` is sequential: test only (the blocking half is D5).
57. Voice guard fires ~9 s on an unpinned 16 KiB pipe; a recognizer closing in < 2 s is flagged: accepted in the Phase 6 rulings.
58. Logs only retryable/status; missing cancel/double-tap/NEEDS_REVIEW tests; asking flag not reset on cancel: cancellation only happens on ViewModel clear, so no stuck UI; test gaps.
59. `take()` can split surrogate pairs: a rare clipped emoji on the HUD.
60. `ConnectException` → "Test failed"; `model`/`apiVersion` unescaped; commas in model names; `https:/host` edges; chat probe not one-token; no `EncryptedKeyVault` test; `test()` swallows cancellation: cosmetic or the user's own input to their own endpoint.
61. Leading-zero octets pass the local check; `OllamaProvider` follows redirects; rows stored before validation: moot, since http with a key is refused for every host, no key is sent to Ollama, and no such rows exist.
62. `onLinkState` unguarded; hello-before-ready discards the fresh offset: self-heals via the hello request.
63. Rating written before an unguarded enqueue; non-atomic double-tap enqueue; source cards don't observe memory changes: minor, and the review is re-tappable.
64. Bridge-thread callbacks unserialised (a benign duplicate hello); OUTDATED/mismatch tests; `cache.write` on the bridge thread: benign/test.
65. An exited `phoneMessage` still updates liveness: cosmetic.
66. Fallback can repeat an (endpoint, model); "Primary provider failed: null"; `set()` allows fallback == primary; vision retry ignores wrapped cancellation; `AiGraph` init order; `resetForTests`; `runBlocking` on the ai-seed thread: no user effect (Diagnostics text at most).
67. Initial empty hint flash; period highlight lag; extreme ISO date in `DayViewModel` (not reachable from the UI); Today/Yesterday stale across midnight; empty tiles clickable: cosmetic.
68. TTS: a synchronous `onInit(false)` from a fake leaks the engine; an old watchdog's global `stop()` can end a newer utterance: needs a lost `onStop`, which is rare.
69. Frame pager resets on title change; autoplay stops at the last frame: cosmetic (transformable scroll is in the device checks).
70. Bridge logs every rejected input: moot after the debounce.
71. Overlay equality by data; overlay timer under a local toast; nav idle ignores overlays: cosmetic.

---

## Merge order

1. **B** first. It adds the `ContextDatabase.exportDao()` line, and everything else rebases trivially.
2. **D**: the `MemoryGraph.ensureLegacyImported` body and the `phone/build.gradle.kts` guard.
3. **A**: the `MemoryWipe` hook and the `GlassesRuntime` uplink line.
4. **C**: the `MainActivity` toast and the `Banners` copy.
5. **Phase 7** merges whenever it is ready. On conflict in the shared one-liners above, keep both sides. Phase 7 rebases over A–D, or A–D rebase over Phase 7, whichever lands second.
6. **E** last, after Phase 7, so `AllRoutesSmokeTest` covers Phase 7's routes. Fix any route fixture the new screens need inside E's test.
7. The controller runs `tools/gate.sh` on the merged tip, then `tools/device-checklist.sh` (R-75), commits the screenshots (R-74), and runs `tools/relocate-legacy-screens.sh` in the main checkout.

Run the full gate after each merge.

## Device checks (controller, PENDING-DEVICE)

Each check is a step id in `tools/device-checklist.sh`.

- **D-A1 Clock jump:** with the glasses offline and frames buffered, move the glasses clock +2 days, then reconnect → the frames flush and are not aged out. Their capture times on the phone match reality, within the offset.
- **D-A2 Full glasses storage:** fill the glasses storage to < 1 MB free, then capture → the newest frame is buffered, the oldest is dropped, and nothing crashes.
- **D-A3 Cold start on Main:** cold-start the phone app with StrictMode logging on → no disk read from `ReceivedFrameLedger` on Main. Also note when `CxrSessionManager.getInstance` runs relative to the BT grant prompt.
- **D-A4 Delete all with frames buffered:** buffer 5 frames offline, run Delete all on the phone, then reconnect → 5 acks `wiped_drop`, and no new frames in Activity.
- **D-A5 Mid-frame drop (spec §6.4):** kill BT during a flush → the frame resumes from chunk 0 with no duplicate, and the Status buffer count is exact after the flush.
- **D-C1 Camera bind failure:** revoke the camera on the glasses mid-session → no SurfaceTexture warning spam, and the service stops cleanly.
- **D-C2 Persistent camera failure in background:** the HUD is brought forward at most 3 times, then stops.
- **D-C3 Force-stop:** close Proactive Context on the glasses the Rokid way → the phone banner says "Closed on glasses · capture stopped". Reopen → the HUD toast "Capture resumed…" appears and capture resumes within one interval. Also: long-press Hi Rokid with KEYCODE 83 consumed, and whether a backgrounded but not finishing activity keeps the session open.
- **D-C4 SDK leaks:** force connect failures (glasses app uninstalled, then reinstalled) 10× → no growth in CXR bindings (`dumpsys activity services`). The watcher reattaches.
- **D-D4 SQLCipher:** turn encryption on, restart, query Activity, turn it off, restart → the data is intact both ways.
- **D-E1 Phone UI:** a transformable frame inside `verticalScroll` (zoom vs scroll). Capture the screenshot references for every route listed in E3.
- **D-E2 Gate stall:** run `tools/gate.sh` 3× on the merged tip. If a `.stall` file appears, attach it to the final review.
- **Spec §6.4 checklist:** link; capture→memory; ask→answer→TTS; nudge; action confirm (incl. simultaneous phone + HUD); pause/resume; offline pause on glasses then reconnect (stays paused); offline buffer flush incl. a mid-frame link drop; Forget fact vs Forget moment.
