# Handoff: v0.9.5 hardening A (branch feat/harden-a, from v0.9.5 base = v0.9.4 tip 5563c209), 2026-10-25

Three correctness items, no user input needed. No PROTOCOL_VERSION or TRACE_SCHEMA change, **no Room schema change** (still v13, no migration), no glasses behaviour change beyond item 1, Default capture plan hash f5782e0d8610 and the pinned plan bytes untouched.

## Commits
- `88169c8b` item 1, glasses recorder thread
- `40096dbb` item 2, outbox rows for every deletion path
- `151d25bf` item 3, mark lineage of every Moment
- this handoff (final run: :glasses, :services:memory, :services:orchestration, :phone all green; the only failure, LocalModelsPageTest (known flaky), passed alone)

## 1. GlassesRuntime: the trace recorder is never built on Main
- Before: `traceRecorder` was a `lazy`, so the first access from Main (CaptureService.startSmart, a control callback) built it there; construction queues `TraceStorage.recover()` (the 2026-10-23 ANR class). Today the constructor reads no file, but nothing stopped a regression.
- Now: the recorder is built ONLY by a task queued on the writer thread (`imu-trace`) at process start (`init`), published through a `CompletableFuture`. `traceRecorder` (getter) returns it, waits for the writer on a non-main thread, and THROWS on the main looper when it is not built yet (never builds, never blocks Main). `traceRecorderOrNull` / `traceRecorderReady` are the non-blocking reads.
- Callers changed: `CaptureService.reconcile` starts the Smart session only when `traceRecorderReady` (at the first supervisor beat after the build; it is built within moments of process start); `stopSmart` uses `traceRecorderOrNull`; `onTraceControl` hands `traceRecorder.onControl(m)`, `diagRecord.retryPersist()` and the hello to ONE writer task (same order as before: the hello is queued after the control's own work); `diagRecord`'s `epochSaved` / `onChanged` lambdas use the non-blocking read (false / no-op before the build).
- Test seams: `GlassesRuntime.recorderBuildHook` (companion, runs on the writer before the build) and `recorderBuiltOn`.
- Tests: `GlassesRuntimeRecorderThreadTest` (writer held back: not ready, `traceRecorderOrNull` null, the getter on the main looper throws, `pollTraceState` no-op, nothing built on Main; after release it was built on `imu-trace`, not the main thread); `CaptureServiceModeTest.smartSessionWaitsForTheRecorderTheWriterBuildsAndNeverBuildsItOnMain` (no session for 300 rounds while the recorder is held back, then a session).
- Mutations (red, restored): getter builds lazily on the caller (`GlassesRuntimeRecorderThreadTest` fails); that plus the CaptureService gate removed (`CaptureServiceModeTest` new test fails; with ONLY the lazy mutation it passes, because the gate is what that test pins).
- First version of the CaptureService test idled only 3 s and the gate mutation survived: the session needs more main-looper time than that; the test now runs as long as `waitUntil` does.

## 2. Outbox rows for every deletion path
- ONE helper `memory/forget/CleanupOutbox.queue(dao, now, phoneFiles, wireFrameIds, traceIds, ranges)` (same row order and payloads as before; unique (target, payload) makes a repeat a no-op). Called INSIDE the deleting transaction from:
  - `MomentForget` (refactored to the helper; its nested `forgetMomentTx/forgetFactsTx` calls pass `queueOutbox = false` so its rows stay exactly its own),
  - `ForgetService.forgetMomentTx` (JPEG + sidecar, glasses frame by wire id, the +-10 s photo-link range and the traces it tombstones), `cascade` (so `forgetFact(s)` and retention `expire`: the JPEG and glasses frame of a photo no memory cites any more, plus the link ranges and traces of the frames it locks), `expirePhotosTx` (retention photo expiry: JPEG, sidecar, wire id, link range, traces),
  - `CanvasSlice3Forget.forgetRange` (the canvas range Forget: tombstoned traces + the range).
  Retention step 7 (legacy frames) goes through `forgetMoment`, so it is covered. Delete all is unchanged (the `ALL` row stays; the outbox is kept).
- Helper (laptop) rows: already written in the same transactions by `CanvasSlice3Dao.forgetRangeTx` / `deleteSliceOutputsForFrames` into `canvas_helper_cleanup` (these paths all call them); no new row kind, so no schema change.
- `dropCanvasOutputs` now returns the spans it fenced so the callers can queue them.
- Tests: `ForgetOutboxPathsTest` (one per path: forgetMoment, forgetFact, retention `expire`, `expirePhotos`, an expiry that skips everything queues nothing, canvas range forget, atomicity: the rows roll back with the delete (outer transaction that throws), repeat is idempotent, MomentForget keeps exactly its own range row); `DeletionOutboxInventoryTest` (every production file outside `db/` that calls the deleting cascades must be in a fixed list AND call the helper; the only writers of outbox rows outside `db/` are the helper and `FrameIntake`).
- Mutations (red, restored): forgetMoment skips the write; cascade skips (forgetFact, expire); expirePhotos skips; canvas range forget skips; helper drops ranges; helper drops wire ids; nested MomentForget cascade also queues (this one SURVIVED until `momentForgetKeepsItsOwnRows...` was added).

## 3. A mark inside a photo Moment is in the run lineage
- `markLineage(m)` is now `m.marks.map { it.id }` for EVERY Moment type (was MARK only). So every Moment run (`input.moments`, FRAME and MARK) and every Scene run (`SceneRunItem.markIds` is built from `markLineage` of all its Moments) carries the `mf:<labelId>` tokens of all marks; the commit fence (`insertRunItemsFenced(.., labelIds)`) and the pre-call fence already used the same set. Every deleter of a label already calls `deleteRunRowsOfLabels` (`deleteLabel` = mark undo and voice undo, `deleteLabelsBetween` = range Forget, `deleteLabelsForFrames` = photo-link Forget, `MomentForget`), so nothing changed there: the FRAME rows simply have the tokens now. Delete all wipes the tables.
- Tests (`MarkMomentRunTest`, photo-Moment variants): the rows carry the token; mark undo (tap) and voice undo delete the Moment's rows and keep the photo; range Forget of only the mark's time; photo-link Forget of a mark linked to another photo; a mark forgotten while the model call waits writes nothing and the run is CANCELLED; a Scene run over a photo Moment loses its rows when the mark is undone.
- Mutations (red, restored): FRAME lists no marks (7 tests); Scene markIds MARK-only; commit fence ignores marks; undo / range Forget / photo-link Forget each without the shared helper (the photo-Moment tests fail alongside the MARK ones).
- Not mutated: the PRE-call fence check alone (the commit fence catches the same case in the test, which forgets inside the call; a Forget between resolve and the call would need a seam).

## Dependents found (graph first, then the compiler)
`graphify affected GlassesRuntime --depth 2` named CaptureService, MainActivity, SamplingSession, MinimisedIcon, PopupOverlay, CaptureAlarmReceiver, GlassesLogging and the listed tests; the only users of `traceRecorder` were CaptureService (3 sites) and GlassesRuntime itself (grep). For items 2-3: ForgetService, MomentForget, CanvasSlice3Forget, ForgetActions/RetentionWorker (callers, unchanged), markLineage users CanvasRunner and DaoSceneRunSource; compiler found no other site.

## Decisions I made (please review)
1. SUPERSEDED by Codex fix 2 below: retention expiry now writes PHONE_FILE rows only.
2. `forgetFact` queues only for photos no memory cites any more (the same photos whose JPEG is listed), not for every locked frame; the link range and traces of locked frames are queued.
3. Only the cadence worker drains (up to 15 min after a Forget); the existing listeners still delete files at once. No "drain after Forget" kick added (not asked).
4. Main-thread access to `traceRecorder` before it is built THROWS instead of waiting (all production callers are guarded); an exception while building completes the future exceptionally and later reads throw.
5. A FRAME Moment's run rows now go when ANY mark inside it is undone or Forgotten (the photo and its Moment stay): intended by the brief; the next run recreates them.

## NOT done
- Glasses and helper transports stay stubs (not asked).
- No device or emulator run, no install; CaptureService with the real recorder was exercised only in Robolectric.
- The `forgetFact` path does not list the `.meta.json` sidecar of an uncited photo (pre-existing; `FrameSweep` catches it at start-up).

## Merge steps
1. In the integration worktree: `git merge --no-ff feat/harden-a` (touches `GlassesRuntime.kt`, `CaptureService.kt`, `ForgetService.kt`, `MomentForget.kt`, `CanvasSlice3Forget.kt`, new `CleanupOutbox.kt`, `CanvasRunner.kt` (comment only), `MomentResolver.kt`, `SceneRunSource.kt`, tests).
2. From android-companion, one Gradle: `./gradlew --no-daemon -Dorg.gradle.workers.max=2 --offline :glasses:cleanTestDebugUnitTest :glasses:testDebugUnitTest :services:memory:cleanTestDebugUnitTest :services:memory:testDebugUnitTest :services:orchestration:cleanTestDebugUnitTest :services:orchestration:testDebugUnitTest :phone:cleanTestDebugUnitTest :phone:testDebugUnitTest`; rerun flaky tests alone.
3. No schema, protocol, manifest or plan change; before any install the usual phone backup.

## Codex fixes (after the merge of v0.9.5, tip 71757886)
### Fix 1: a queued trace control keeps its ARRIVAL clocks
- `GlassesRuntime.onTraceControl` samples `SystemClock.elapsedRealtime()` and `System.currentTimeMillis()` BEFORE queueing the control on the writer, and passes them to `TraceRecorder.onControl(c, arrivedElapsed, arrivedWall)` (new defaulted parameters; the `early` list and `applyControl` already used arrival clocks). The boot id is not part of a control's application (it is read when a trace starts), so nothing else needed sampling.
- Tests: `TraceCleanupFenceTest.ALateAppliedControlUsesTheArrivalClocksPassedByTheCaller` (20 s between arrival and execution: the Forget range still covers the trace and the 10 s collect window is over); `GlassesRuntimeRecorderThreadTest.aControlQueuedBehindTheWriterIsAppliedWithItsArrivalClocks` (through the real runtime path: the writer is held back, Robolectric's elapsed clock advanced 1.5 s, the 300 ms window is closed when applied).
- Mutations (red, restored): recorder samples at execution; runtime calls `onControl(m)` without the arrival clocks.
### Fix 2: retention writes phone files only; the drain is bounded
- `ForgetService.expirePhotos`, memory `expire` and retention's legacy-frame `forgetMoment(.., phoneFilesOnly = true)` (RetentionWorker) queue ONLY `PHONE_FILE` rows (no glasses frame/range/trace rows; the helper has no drainable row today). User Forgets (`forgetMoment`, `forgetFact(s)`, MomentForget, canvas range Forget) still write all rows.
- Drainer: `OutboxStore` now has `due(limit, excludeIds)` (SQL `LIMIT`, phone files first, then oldest, skipping rows in their backoff, at most 500 ids), `count()`, `existing(ids)`, `trim(target, keep)`; no whole-table load. `OutboxDrainer.WINDOW` = 200 rows read per run (budget 50 attempts per run unchanged). Hard cap `GLASSES_CAP_PER_TARGET` = 2000 pending rows for each of GLASSES_FRAME / GLASSES_TRACE / GLASSES_RANGE, oldest dropped after each run and counted (`dropped=N` in the counts-only log and `Report.dropped`). PHONE_FILE and ALL rows are never capped. `waiting` is now the number of rows in backoff (they are no longer read).
- Tests: `ForgetOutboxPathsTest` (photo expiry phone-only, memory expiry + retention step 7 phone-only), `OutboxDrainerTest` (window limit, backoff rows not read, cap drops the oldest and counts, phone rows never capped), `OutboxRoomTest` (SQL bound / phone-first / exclude, trim keeps newest of one target).
- Mutations (red, restored): expirePhotos and memory expiry write glasses rows again; drain with no bound; cap removed; trim deletes the newest; backoff rows not excluded; phone rows capped. Equivalent (survived, redundant coverage): making only `forgetMomentTx`'s own call phone-only (the cascade inside it writes the same rows).
- Decision for you: the cap number (2000 per glasses target) and the dropped-row policy (a dropped glasses row means that device copy is never told to delete; it only happens when the glasses transport stays unreachable for thousands of Forgets).
