# ItemRef and unified Forget: inventory, design, cost and recommendation

Status: ANALYSIS AND DESIGN ONLY. No production or test code was changed and Gradle was not run. Everything below comes from reading the v0.9.5 tip (97a70810) with grep and the code graph; line numbers are for that commit. Where a claim could not be proven by reading alone it is marked **verify** and the first step of the plan is a RED test that proves or refutes it.

Scope note on names. The Moment-model spec (2026-10-23, phases 2 and 3) bundles two different things under "ItemRef/CanvasItem": (a) a refactor of the canvas RUNNER's item type (`CanvasItem(frameId, photo, value)` becomes `CanvasItem(itemId, value, itemType)`, 17-28 h in the spec), and (b) unified Forget entry points with root-set inheritance (9-15 h in the spec). This document is about (b) only: a typed way to NAME an item and DELETE it. (a) is not needed for it and is left out (section 3.9).

## 0. Findings that change the picture

1. **Only five user-reachable deletion triggers exist in production today**: Memory detail "Forget this fact" and "Forget this moment (photo)" (`MemoryDetailRoute.kt:26-27`), Delete all (`SettingsDeps.kt:140`), retention (`RetentionWorker.kt`), mark undo by tap or voice (`MarkRecorder.kt:75,112`), and watch-source switch-off (`ContextHub.kt:230`). `MomentForget` (the Moment Forget by id or range) and `CanvasForgetCoordinator.forgetRange` / `CanvasSlice3Forget.forgetRange` (the canvas range Forget) have **no production caller**: `grep` finds them only in tests and in `HubGraph.kt:201-207`, which only registers the coordinator as a listener. There is no screen and no canvas route that triggers either. The two range entry points also have different meaning: the canvas range Forget deletes traces and marks and keeps photos and memories; `MomentForget` deletes everything in the time range. Both are "ready but unreachable".
2. **Deletion is ONE well-tested subsystem already, with many entry flags.** `ForgetService` carries seven behaviour flags (`redactCites`, `phoneFilesOnly`, `lockFrames`, `queueOutbox`, `keepKeyframes`, `scrubAccepted`, `invalidateDescendants`) that together encode the "cause" (user Forget, retention, routine expiry). That implicit cause policy is the main thing a unified design must make explicit.
3. **About 480 tests already pin the area** (counted in section 4.3). A "characterisation test per entry point" mostly exists; what does not exist is a test that asks the question the Codex reviews keep asking: after entry point X, is there ANY row, file or token anywhere that still names the forgotten thing? That is the missing oracle (section 3.7), and it is cheaper than the refactor.
4. The outbox is already unified (`CleanupOutbox.queue`, guarded by `DeletionOutboxInventoryTest`) and Delete all is already one transaction (`WipeDao.wipeMemory`). What is NOT unified is discovery (which rows belong to the thing), fences, lineage tokens, derived-field hiding, and post-commit file work. Those are exactly where the recent bugs were.

## 1. INVENTORY of every path that deletes or hides user data

Legend. Columns: **Out** = writes `cleanup_outbox` rows (and `canvas_helper_cleanup` where relevant); **Fence** = durable fence or tombstone written; **Hide** = hides derived fields (`object_derived`) of surviving Moments or Scenes; **Lineage** = deletes run rows (`canvas_run_item`, `canvas_score`, `canvas_live_trace`) by frame id, by batch token `mf:<frameId>`, or by `mf:<labelId>`; **Audit** = writes `object_audit`. "n.a." = not applicable to that entry. "GAP-n" refers to the ranked list in section 5. Where a Moment holding a deleted source is DROPPED (`MomentDao.dropHolding`), its audit and derived rows go with it (`MomentDao.remove`, :55), and every Scene holding it shrinks, hides its derived fields and gets FORGOTTEN audit rows (`DerivedStaleDao.shrinkScenesHolding`, :96).

### 1.1 User-facing and background entry points

| # | Entry (file:line) | Trigger and production caller | What it deletes or hides | Out | Fence | Hide | Lineage | Audit | Gaps |
|---|---|---|---|---|---|---|---|---|---|
| E1 | `ForgetService.forgetFacts` / `forgetFactsTx` :82/:84 -> `cascade` :138 (via `ForgetActions.forgetFact` :29) | Memory detail "Forget this fact" (`MemoryDetailRoute.kt:26`) | memories, their vectors in all spaces, `memory_source` edges, `frame_source`; descendants D set INVALIDATED (hidden, vectors dropped, re-summarise queued); cited frames tombstoned; JPEG, `frame` row and image vector only if no surviving memory cites the photo; asks and nudges citing F or D redacted; their proposals expired and scrubbed, alarms and notifications cancelled; Moments holding F, D or the frame DROPPED (Scenes shrink); +-10 s link: marks, traces, sensor rows, results, helper jobs (see E2) | yes (files, wire ids, traces, range) | `forget_tombstone`, `forgetRanges` (+-10 s), `frame_forget_rev`, `labelRev` | Scenes only (Moments are dropped) | frame ids, batch tokens, `mf:<frameId>`, marks of the +-10 s span | Scene FORGOTTEN | GAP-1 export file, GAP-9, GAP-12 |
| E2 | `ForgetService.forgetMoment` / `forgetMomentTx` :54/:56 (via `ForgetActions.forgetMoment` :32) | Memory detail "Forget this moment" (`MemoryDetailRoute.kt:27`) | all memories of the frame (E1 cascade), `frame` row + JPEG + sidecar, `frame_meta`, `frame_state`, `frame_vector`, `frame_source`, tombstone, `frame:<id>` cites redacted; `dropCanvasOutputs` :211: forgetRangeTx over capture time +-10 s (traces tombstoned, sensor rows purged, marks deleted, replays and sweeps invalidated, `frame_forget_rev` bumped, every open helper job voided, DONE jobs carrying the frame scrubbed), run rows by lineage | yes | as E1 | as E1 | as E1 | as E1 | GAP-1, GAP-7, GAP-9, GAP-12 |
| E3 | `ForgetService.expire` / `expireTx` :94/:96 (flags: keepKeyframes, phoneFilesOnly, no accepted-proposal scrub, no descendants) | `RetentionWorker.kt:94,103` | expired memories and their vectors and edges; legacy (non-keyframe) frames tombstoned and deleted; asks and nudges redacted; PENDING proposals expire | phone files only | tombstone of legacy frames | Moments holding the memory dropped | only for legacy frames | Scene shrink | GAP-8 |
| E4 | `ForgetService.expirePhotos` / `expirePhotosTx` :111/:113 | `RetentionWorker.kt:114,127` | `frame` row and JPEG + sidecar (memories, `frame_meta`, vectors stay); tombstone; `dropCanvasOutputs` (same +-10 s cascade as E2: marks, traces, sensor rows, results, ALL open helper jobs) | phone files only (but see GAP-8: the listener still sends the glasses a forget range) | tombstone, `forgetRanges`, `frame_forget_rev`, `labelRev` | Moments dropped (no live photo) | yes | Scene shrink | GAP-8 |
| E5 | `ForgetService.forgetMoment(phoneFilesOnly=true, redactCites=false)` | `RetentionWorker.kt:142` (legacy orphan frames) | as E2 minus glasses/helper outbox rows | phone files only | as E2 | as E2 | as E2 | as E2 | GAP-8 |
| E6 | `RetentionWorker.kt:135` image vectors | retention step 6 | `frame_vector` rows; marker `imageVectorSpace='expired'` | no (n.a.) | marker only | n.a. | n.a. | no | none |
| E7 | `CanvasMaintenance.pruneTraces` :20 -> `CanvasDao.pruneTraces` :106; `CanvasDao.pruneRuns` :48 (called from `CanvasRunner.kt:330,401,448,496,538`) | on canvas reads, runner start | `canvas_live_trace` rows older than 7 days or beyond 5000; runs beyond KEEP_RUNS with items and scores | n.a. | no | n.a. | n.a. | no | none (history only) |
| E8 | `MomentForget.forgetMoments` / `forgetRange` :35/:42 -> `run` :52 | **no production caller** (tests, `MomentForgetEntryPointsTest`) | fences first (`addMomentForgetRange`, `moment_tombstone` per second), then independent discovery of frames (`frame.ts` and `frame_meta.capturedAtMs`), memories, marks, unioned with `moment_part`; calls E2 and E1 transactions per source (`queueOutbox=false`), `forgetRangeTx` per span, strays of marks, `deleteRunRowsOfLabels`, projection drop, ONE outbox write | yes (single write, :100) | `momentForgetRanges`, `moment_tombstone` (one per second, plus span start), `forgetRanges`, tombstones, `frame_forget_rev` | via drops and shrink | frames, batch, `mf:` frames, `mf:` labels | Scene FORGOTTEN | GAP-10 |
| E9 | `CanvasForgetCoordinator.forgetRange` :28 -> `CanvasSlice3Forget.forgetRange` :17 -> `CanvasSlice3Dao.forgetRangeTx` :75 | **no production caller** | traces tombstoned (events deleted, sensor rows purged), marks and intent labels deleted, results invalidated, helper jobs voided, `dropBetween` drops Moments of the range. **Photos and memories are kept.** Files follow in the listener | traces + glasses range (:20) | `forgetRanges`, `frame_forget_rev`, `labelRev`, trace tombstones | sensor-derived hide (via `purgeSensorBetween`), Scene shrink | marks yes (`deleteLabelsBetween` :178); frames no | Scene shrink | GAP-10 |
| E10 | `MemoryWipe.deleteAll` :84 + `WipeDao.wipeMemory` :78 (+ `resumeIfPending` :65) | Settings > Memory > Delete all (`SettingsDeps.kt:140`) | every memory, frame, vector, ask, nudge, open proposal (rest scrubbed), canvas run tables, all `canvas_*` slice-3 tables, `moment*`, `scene*`, `moment_sensor`, `object_audit`, `object_derived`, acked outbox rows, tombstones, fence ranges; files (frames, legacy, exports, photo-eval, traces, staging); prefs (plan state, collect, helper grants); queue; VACUUM and WAL checkpoint; log wipe via listener | yes: one `ALL` row + helper wildcard row, unacked rows survive | deletion epoch committed BEFORE anything is deleted, wipe generation, `frame_forget_rev`, `labelRev`, `scene_seq_floor` kept | all rows gone | all | audit deleted | GAP-2 (watch ring survives) |
| E11 | `MarkRecorder.onMark(undo)` :75, `applyVoice(Undo)` :112 -> `CanvasSlice3Dao.deleteLabel` :147 | glasses undo tap, voice "undo" | mark and its intent label (`<id>:i`); run rows by `mf:<labelId>`; `labelRev`; replays/sweeps invalidated; derived fields of Moments and Scenes holding the mark hidden | none needed (phone-only data) | `labelRev` | yes (`staleMomentsHolding`) | yes `mf:<labelId>` | INVALIDATED + DERIVED_* rows | none |
| E12 | `retagLabel` :154, `correctLabel` :162 (also `MarkRecorder.kt:126,132`) | ask-tap flag, voice retag, correction | label changed, not deleted; derived fields hidden; `labelRev` bumped | n.a. | `labelRev` | yes | n.a. | yes | GAP-6 (retag does not invalidate replays and sweeps) |
| E13 | `ContextHub.stopSource` :230 -> `watchRing.clear()` + `HubGraph.kt:136` -> `CanvasDao.deleteWatchRuns` :45 | watch source switched off | in-memory watch ring; runs holding `watch:<atMs>` items (items, scores, run row) | n.a. | **none** | n.a. | run-level only | no | GAP-2 |
| E14 | `HelperDeletion.grantOff` / `disconnectAll` / `endSession` / `restart` (`HelperDeletion.kt:48`; wired `HelperBackend.kt:121`) | grant off, disconnect, developer mode off, session end, process start | every open helper job voided, payload and ids dropped | `canvas_helper_cleanup` row per job | `canvas_helper_cleanup` | n.a. | n.a. | no | none |
| E15 | `MomentBuilder.kt:110` (`dao.remove(..., BUILD)`), `SceneBuilder.kt:91` | 15-min cadence worker, build trigger | Moments that no longer qualify, Scenes rebuilt: rows, parts, sensor rows, audit, derived (Scene seq floor kept) | n.a. | tombstones and range fences are READ here | by removal | no (rebuildable projection) | removed with object | none |
| E16 | `CanvasSlice3Dao.tombstoneTrace` :240 (inside E1, E2, E4, E8, E9), `DerivedStaleDao.purgeSensorOfTrace` :61, `purgeSensorBetween` :70 | any Forget that overlaps a trace | trace events deleted, row keeps identity; sensor rows of that trace in ALL Moments deleted; derived fields hidden | glasses trace row (phone files only on retention) | trace tombstone, `forgetRanges` | yes | **no** | INVALIDATED | GAP-3 |
| E17 | `FrameIntake.commit` :24 | a frame arrives inside a Moment Forget fence | refuses the frame; queues its JPEG and sidecar for deletion | yes (own insert, allowed by `DeletionOutboxInventoryTest`) | reads `momentForgetRanges`, `moment_tombstone` | n.a. | n.a. | no | GAP-11 |
| E18 | `RokidFrameInbox.dropRows` :181 and the cancelled-ingest paths :146-157 | Delete all or cancel during ingest | frame, meta, state rows and inbox/archive files of an ingest that lost a race | no (files deleted inline, under `WipeFence`) | `WipeFence`, generation | n.a. | n.a. | no | none |
| E19 | `ForgetActions.apply` :42, `scrubQueue` :68, `FrameSweep.delete` :29 / `sweep` :50 | after every committed E1/E2/E3; start-up | alarms and notifications of redacted proposals, JPEGs (under `WipeFence`), queue log text, re-summarise queued, WAL checkpoint; sweep of orphan JPEGs and tombstone pruning after 8 days | n.a. (this is a second, parallel file path next to the outbox) | `WipeFence` | n.a. | n.a. | no | GAP-1 |
| E20 | `OutboxDrainer.drain` :46 (`PhoneFileCleanup`, `NotReachableGlasses`, `NotReachableHelper`; `OutboxTriggers.kt:19`) | `MomentCadenceWorker` every 15 min | turns rows into deletions; removes a row only after the port confirms | n.a. | n.a. | n.a. | n.a. | no | GAP-5 (glasses and helper ports are not built; rows are trimmed past 2000) |
| E21 | `LogWipeListener` (`PhoneLogging.kt:139`) | Delete all only | log files (content-free by the log ratchet) | n.a. | n.a. | n.a. | n.a. | no | none |
| E22 | Glasses: `TraceStorage.wipe` :358, `runForget` :426, `MarkOutbox.onEpoch` :71, `OutboundBuffer.remove` :107 / `clear` :111, `TraceRecorder` forget/wipe tasks | `pc_trace_ctl` (epoch, tombstones, forget range), startup replay of `trace_cleanup_pending` | glasses trace files, unsent marks (only on epoch change), buffered frames (only by ack or clear) | n.a. (receiver side) | `trace_epoch`, `trace_forget`, `trace_cleanup_pending` | n.a. | n.a. | no | GAP-5, GAP-12 |

### 1.2 Artefacts and who removes them (the "where could it be left behind" view)

| Artefact | Removed by | Not removed by |
|---|---|---|
| `frame`, `frame_meta`, `frame_state`, `frame_vector`, `frame_source` | E2, E10 (E4 removes only `frame` and the JPEG) | E1 for a frame some memory still cites (by design) |
| `frame_cluster` (ids in `repFrameIds`, times) | E10 only | E1, E2, E4 leave the cluster row with the forgotten frame id (GAP-9, ids only) |
| `memory`, vectors, `memory_source` | E1, E2, E3, E10 | E4 (memories stay on photo expiry by design) |
| `ask`, `nudge`, `proposal` | redacted by E1-E3; E10 deletes | export copies of them (GAP-1) |
| `summary_trace` | E10 | E1-E3 (ids and counts only, documented) |
| `canvas_label` marks | E2/E4 (+-10 s), E8, E9, E10, E11 | none |
| `moment`, `moment_part`, `moment_sensor`, audit, derived | `MomentDao.remove`, E10 | n.a. |
| `scene`, `scene_part`, audit, derived | shrink on Moment removal, E10 | n.a. |
| `canvas_trace`, events, trace folders | E2/E4 span, E8, E9, E10, `sweepTombstoned` | glasses copy until the transport exists (GAP-5) |
| `canvas_run_item`, `canvas_score`, `canvas_live_trace` | E1, E2, E4, E8, E9, E10, E11, E13 (run level), pruning (E7) | rows holding sensor values of a forgotten trace (GAP-3); `watch:` rows on any Forget (GAP-2); batch mates across Moments (GAP-4) |
| `canvas_replay`, `canvas_sweep` | invalidated (results nulled) by E2, E4, E8, E9, E11; deleted by E10 | not invalidated by retag (GAP-6) |
| `canvas_helper_job` | voided by E2, E4, E9, E14; DONE jobs scrubbed by E2/E4 | DONE jobs are found by frame id only, so range Forget (E9) never scrubs them |
| `cleanup_outbox`, `canvas_helper_cleanup` | acked rows by E10; drained by E20 | kept on purpose |
| Export JSON in `cacheDir/exports` | E10 only (`MemoryWipe.kt:137`) | **E1-E4 never touch it (GAP-1)** |
| Watch ring (memory) and `watch:` run rows | E13, E10 (rows) | **ring survives E10 (GAP-2)** |
| Prefs: plan state, collect, helper grants | E10 | n.a. |
| Fence data: tombstones, `forgetRanges`, `momentForgetRanges`, `frame_forget_rev`, `labelRev`, deletion epoch, `scene_seq_floor` | cleared or moved by E10 as designed | n.a. |

### 1.3 Which writer checks which fence (the fence matrix)

| Writer | Wipe generation | Persisted epoch (`DaoFence`) | Frame tombstone | `forgetRanges` | `momentForgetRanges` / `moment_tombstone` | `labelRev` | `frame_forget_rev` |
|---|---|---|---|---|---|---|---|
| `FrameIntake.commit` | no | no | no (checked upstream in `RokidFrameInbox.kt:404,430`, outside the transaction, GAP-11) | no (by design: burst neighbours stay) | yes | no | no |
| `insertLabelFenced` (marks) | yes | yes + `wipedAtMs` | no | yes | covered because Moment Forget also writes `forgetRanges` | bumps | no |
| `acceptTraceFenced` | yes | yes | trace tombstone | yes | via `forgetRanges` | no | no |
| `insertReplayFenced` / `insertSweepFenced` | yes | yes | trace sources | via sources | no | yes | no |
| `CanvasDao.insertRunItemsFenced` :119 | in-process generation only | **no** | yes (members) | no | no | no | no (**GAP-2**: no watch fence) |
| helper job rows (`rowFenceHolds` :398) | yes | yes | n.a. | n.a. | n.a. | n.a. | yes |
| derived writes (`writeDerivedGuardedTx`) | yes | yes | n.a. | n.a. | n.a. | n.a. | parent exists + input seq |
| `MomentBuilder` | n.a. | n.a. | yes | n.a. | yes | n.a. | n.a. |
| `replaceSensor` (sensor rows) | no | no | n.a. | n.a. | n.a. | n.a. | Moment must exist |
| answers / facts (`ForgetFence.allLive`, `PipelineRecords`) | yes | no | yes | no | no | no | no |

The matrix is itself the finding: there are eight fence mechanisms and each writer remembers a different subset. A plan type that names the fences a deletion MOVES and a writer type that names the fences it MUST CHECK is the natural way to make "a fence that did not cover marks" a compile-time question (section 3.6).

## 2. ITEM KINDS and their relations

```
                      derives-from (memory_source)
                   +----------------------------+
                   v                            |
 FILE(phone JPEG) <-- FRAME ------------- MEMORY (fact, caption, summary)
 FILE(glasses wire id)   |  1..n  frame_source / memory.frameId    |
 FILE(helper copy)       |                                         +--> ASK / NUDGE (redact), PROPOSAL (expire, scrub)
                         |  time link +-10 s (no id)               +--> VECTOR (all spaces)
                         v
                       MARK (canvas_label, intent label "<id>:i")
                         |  time = second
                         v
  FRAME, MEMORY, SUMMARY, CITE, MARK parts (moment_part, refs only)
                         v
                      MOMENT (id = phone-clock second) ---- sensor rows (moment_sensor) <---- TRACE (canvas_trace, files, glasses copy)
                         |  parts                              ^   computed from, deleted with the TRACE, in ALL Moments
                         v
                       SCENE (scene_part -> MOMENT refs)

 DERIVED FIELD (SUMMARY, CAPTION, EMBEDDING) belongs to a MOMENT or a SCENE: hidden (staleSince), never "forgotten" on its own
 AUDIT (object_audit) belongs to a MOMENT or a SCENE: deleted with it, appended on hide/shrink
 RUN ITEM (canvas_run_item, canvas_score, canvas_live_trace): keyed by frameId OR momentKey OR sceneKey OR "watch:<atMs>";
     lineage tokens in batchId (pipe-separated): "<frameId>", "mf:<frameId>", "mf:<labelId>", "<runId>:<nodeId>:<firstKey>" (a model call)
 RESULT (canvas_replay, canvas_sweep): INVALIDATE, read TRACES and MARKS (via labelRev)
 HELPER JOB: carries frame ids; laptop copy removed through canvas_helper_cleanup
 OUTBOX ROW / TOMBSTONE / FENCE: control data, no user content; created BY a deletion, never deleted by one (except acked rows)
```

Relations that matter for deletion:

- **Strong (id) links**: frame to memory (`memory.frameId`, `frame_source`), memory to memory (`memory_source`), Moment to its parts (`moment_part`), Scene to Moments (`scene_part`), run row to its frame or Moment key, helper job to its frame ids, derived field and audit to their owner.
- **Time links (no id)**: mark to photo (+-10 s, `PHOTO_LINK_MS`), trace to everything it overlaps, sensor row to its second, Moment Forget range to everything inside it. Every recent defect lives on a time link or a lineage token, never on an id link.
- **Lineage tokens** are the only record of "this row was computed from that thing". They are stored as text in `batchId` and assembled in two places that must agree: `CanvasRunner.kt:613-626` (writer) and `CanvasDao.deleteCanvasOutputsForFrames` :218 plus `CanvasSlice3Dao.deleteRunRowsOfLabels` :142 (readers). A token kind written by the runner and not read by a deleter (sensor, watch value, memory) is silently undeletable.

## 3. PROPOSED DESIGN

### 3.1 Principles

1. Name once: every deletable thing has exactly one `ItemRef` value; no entry point takes a bare `String` id.
2. Decide once: a pure `ForgetPlanner` turns "what the user asked" into a complete typed `ForgetPlan`. All "which other rows belong to this" knowledge lives there.
3. Apply once: one executor applies a plan in ONE Room transaction through the existing fenced DAOs, in a fixed phase order (fences first). It contains no business rules.
4. Totality by the compiler: adding an `ItemKind`, a lineage token kind, an outbox target or a fence kind must not compile until the planner states what deletion means for it.
5. Old entry points stay as thin callers (their signatures and their tests do not change); the planner and executor are introduced behind them one entry at a time, each move guarded by its characterisation test and a shadow comparison (section 3.8).

### 3.2 Types (sketch, names final enough to review)

```kotlin
// memory module, package forget.itemref
sealed interface ItemRef {
    data class Frame(val id: String) : ItemRef                 // photo: frame row, meta, state, vector, sources, JPEG, wire id
    data class Memory(val id: String) : ItemRef
    data class Mark(val labelId: String) : ItemRef             // canvas_label of kind MOMENT; the intent label "<id>:i" is derived, not named
    data class Moment(val second: Long) : ItemRef              // MomentId
    data class Scene(val id: String) : ItemRef
    data class Trace(val traceId: String) : ItemRef
    data class Run(val runId: String) : ItemRef
    data class RunItem(val runId: String, val key: String, val nodeId: String) : ItemRef
    data class HelperJob(val jobId: String) : ItemRef
    data class Field(val owner: ObjectRef, val field: DerivedFieldName) : ItemRef   // hide-only: HIDE is its only legal verb
    data class File(val where: FileHome, val path: String) : ItemRef                // PHONE | GLASSES_FRAME | GLASSES_TRACE | HELPER | EXPORT
    data class WatchSample(val atMs: Long) : ItemRef
}
sealed interface ObjectRef { data class OfMoment(val second: Long) : ObjectRef; data class OfScene(val id: String) : ObjectRef }

sealed interface ForgetScope {
    data class Items(val refs: List<ItemRef>) : ForgetScope
    data class Range(val fromMs: Long, val toMs: Long, val depth: RangeDepth) : ForgetScope   // depth: EVERYTHING (Moment Forget) | MARKS_AND_TRACES (canvas range)
    data object All : ForgetScope
}

enum class ForgetCause { USER, RETENTION_MEMORY, RETENTION_PHOTO, RETENTION_LEGACY_FRAME, UNDO, SOURCE_OFF, WIPE }
// the seven flags of ForgetService become one table keyed by cause (ForgetPolicy), reviewed in one place
data class ForgetRequest(val scope: ForgetScope, val cause: ForgetCause)
```

`ItemKind` is the sealed hierarchy's discriminator (`ItemRef::class`); the planner has one `when` over `ItemRef` with no `else`.

```kotlin
// pure; no I/O, no clock, no DAO. Input is a Closure: what discovery read inside the transaction.
class ForgetPlanner(private val policy: (ForgetCause) -> ForgetPolicy) {
    fun plan(req: ForgetRequest, world: Closure): ForgetPlan
}

data class ForgetPlan(
    val fences: List<FenceMove>,            // sealed: AddRange(kind, from, to), TombstoneFrame, TombstoneSecond, TombstoneTrace, BumpLabelRev, BumpFrameForgetRev, WipeEpoch
    val deletes: List<RowDelete>,           // sealed, one subtype per table family (Frames, Memories, Labels, Moments, Scenes, TraceEvents, ...)
    val invalidations: List<Invalidate>,    // memories -> INVALIDATED, replays/sweeps, helper jobs -> VOID/SCRUB
    val hides: List<HideDerived>,           // (ObjectRef, step, cause ref): derived fields hidden + INVALIDATED audit
    val lineage: Set<LineageToken>,         // sealed: FrameMember, MarkMember, SensorOfTrace(traceId), WatchSample(atMs), BatchCall(id)
    val outbox: List<OutboxRow>,            // sealed target: PhoneFile, GlassesFrame, GlassesTrace, GlassesRange, HelperCleanup, ExportFile, All
    val audit: List<AuditOp>,               // FORGOTTEN, PART_REMOVED, INVALIDATED ... (content-free)
    val postCommit: List<PostCommit>,       // sealed: DeletePhoneFiles, NotifyGlasses, CancelAlarms, CancelNotifications, ScrubQueue, ResummarizeInvalidated, Checkpoint
)
```

`Closure` is the one place that reads: it is produced by `ForgetDiscovery` (suspend, inside the transaction, the union of the projection and the authoritative stores, exactly the logic that `MomentForget.run` steps 1-2 and `ForgetService.cascade` steps 1-3 already implement, extracted rather than rewritten). The planner then needs no database, so its tests are pure JVM table tests that run in milliseconds.

### 3.3 One executor

```kotlin
class ForgetExecutor(private val db: ContextDatabase, private val fence: DaoFence?) {
    suspend fun apply(plan: ForgetPlan): Committed   // Committed(postCommit) after commit; nothing else escapes the transaction
}
```

Fixed phase order inside one `db.withTransaction`: (1) fences (`addMomentForgetRange`, tombstones, `frame_forget_rev`, epoch for `All`), (2) invalidations and helper-job voids, (3) row deletes in dependency order (edges before rows), (4) lineage-token deletes (one helper for every token kind, replacing `deleteCanvasOutputsForFrames` and `deleteRunRowsOfLabels`), (5) projection drops and Scene shrinks, (6) derived-field hides, (7) audit, (8) outbox rows (the existing `CleanupOutbox.queue`, unique key keeps repeats a no-op), (9) test fault seam (the existing `testFaultBeforeCommit`). It calls only existing DAO methods, so the DAO fences that exist today keep working. After commit it returns the `postCommit` list; the orchestration layer runs it (this replaces the instance-shared `pendingFrames` queue in `ForgetService.notifying` :192-202, which is a latent cross-call race, GAP-7).

Delete all stays the one special executor strategy: `ForgetScope.All` is planned (so its outbox, fences and file list come from the same types) but applied by `WipeDao.wipeMemory` inside the existing epoch-first protocol (`MemoryWipe.deleteAll`), because "epoch committed before anything is deleted" and `resumeIfPending` are crash-safety properties that must not move into a generic path.

### 3.4 Exhaustiveness (how a new kind fails to compile)

- `ItemRef` is sealed; `ForgetPlanner.plan` and `ForgetDiscovery.discover` are `when (ref)` expressions used as values (no `else`), so a new subtype breaks compilation of both until it is given discovery and plan rules.
- `LineageToken` is sealed and has two functions: `encode(): String` (used by `CanvasRunner` when it writes `batchId`) and `deleteRows(dao)` (used by the executor). A runner that wants a new token kind must create the subtype, which forces the delete side. This closes the class "the runner records a token no deleter reads" (GAP-3, GAP-4, and the earlier `mf:<labelId>` bug).
- `OutboxRow.target` is sealed and `OutboxDrainer.plan(row)` is a `when` over it (today a string `when` with `else -> null`, `OutboxDrainer.kt:~120`), so a new target must state its port.
- `FenceMove` is sealed; each writer declares the fences it must check as `val checks: Set<FenceKind>` (data, not behaviour) and a JVM test asserts, for every writer, `checks` is a superset of the fences every plan can move for the kinds that writer's rows belong to (the fence matrix of 1.3 becomes a test instead of a table in a document).
- Table coverage: `DeletionGraph` becomes `kind -> tables owned`. The inventory test (now limited to `canvas_`, `moment`, `object_`, `scene`, `cleanup_outbox`, see `DeletionGraphInventoryTest.kt:~13`) is widened to EVERY Room table, so `frame_cluster`, `summary_trace` and `ask` must declare their `ItemKind` owner or a KEPT reason (closes GAP-9's class).
- DAO surface: delete and purge primitives become `internal` to the `forget` package (or a source-scan test lists the only allowed callers, as `DeletionOutboxInventoryTest` already does for the cascades), so a new deleter outside the executor fails a test.

### 3.5 What a plan makes explicit that the code does implicitly today

| Today (implicit) | In the plan (explicit, typed) |
|---|---|
| `cascade(..., keepKeyframes = true, phoneFilesOnly = true, scrubAccepted = false, invalidateDescendants = false)` | `ForgetPolicy(RETENTION_MEMORY)` |
| "hide derived fields" scattered over `MomentDao.remove`, `markStaleCore`, `purgeSensor*`, `staleMomentsHolding` | `plan.hides` computed once from the set of changed sources |
| three helpers delete run rows by token (`deleteCanvasOutputsForFrames`, `deleteRunRowsOfLabels`, `deleteWatchRuns`) | `plan.lineage: Set<LineageToken>`, one deleter |
| `queueCleanup(files, wire, spans, phoneFilesOnly)` called from four places | `plan.outbox`, written once |
| `pendingFrames` queue + `ForgetListeners` after commit | `plan.postCommit`, returned by `apply` |
| "+-10 s around a photo, widened for privacy" inside `dropCanvasOutputs` | a `RangeDepth` and a named `LinkRule(PHOTO_LINK_MS)` in `ForgetPolicy`, visible and testable |

### 3.6 Closure rules the planner owns (the part reviewers keep finding)

1. Frame: all memories of the frame, the cites, the Moments holding it, the +-10 s span (marks, traces, sensor rows, results, open helper jobs), run rows by `FrameMember`, by the batch tokens of every row found, AND by the `BatchCall` token of any row found through a Moment key (closes GAP-4).
2. Memory: descendants INVALIDATED (hide + vectors), cited frames locked, run rows by the frames, and rows whose lineage lists a descendant's frames (GAP-12, **verify**).
3. Mark: its intent label, rows by `MarkMember`, derived fields of Moments and Scenes holding it, replays and sweeps (also on retag, GAP-6).
4. Trace: events, files, glasses copy (outbox), sensor rows in ALL Moments, derived fields of those Moments and their Scenes, AND run rows whose lineage has `SensorOfTrace(traceId)` (closes GAP-3; requires the runner to write that token for any run whose input carried a sensor summary).
5. Moment: parts, sensor rows, audit, derived; Scenes shrink; DONE helper jobs keyed by its frames.
6. Scene: parts, audit, derived, `scene_seq_floor`.
7. Watch sample: ring and `watch:` rows (closes GAP-2), fenced by a `watchEpoch` counter that `insertRunItemsFenced` checks.
8. Export file: `File(EXPORT)` target for every USER-cause plan that deletes memories or asks (closes GAP-1).

### 3.7 The residue oracle (independent of the refactor, first deliverable)

A test helper `ResidueScan.after(world, entry)`: seed one "full world" (a frame with memory, descendant, ask, nudge, proposal, mark, Moment with all part kinds, Scene, derived fields, sensor rows, trace with files, helper job, run rows carrying every token kind, outbox rows, export file, watch sample); run an entry point; then scan EVERY table's text columns and every app file for the forgotten ids, label ids, second ids, wire ids and trace ids, plus every `batchId` for tokens naming them, and assert the residue equals a small checked-in allow-list (ids-only rows such as `frame_cluster.repFrameIds`, control data such as tombstones). The scan does not know how deletion works, so it finds a bug class instead of a bug. It is the generic test behind each Codex find, and it is what lets the later refactor be proven equivalent.

### 3.8 Migration in small safe steps

Every step: the app keeps working, no schema change (Room stays v12; nothing here needs a migration), PROTOCOL_VERSION unchanged, old entry points keep their signatures. "RED" means the test is written first and fails for the right reason; "mutation" means one named mutation per behaviour, as in the project rules. Sonnet implementer, one Gradle at a time, Codex review once per phase.

**Phase A: oracle and gap fixes (valuable on their own; section 4 recommends stopping here for v1)**

- A1. `ResidueScan` helper + `FullWorldFixture` (memory module, JVM Robolectric like the existing forget tests). No production change. Tests: one per entry E1, E2, E3, E4, E5, E8, E9, E10, E11, E13 that records today's residue as the allow-list; each GAP is a RED expectation marked `@Ignore("GAP-n")` until its fix step. Mutation: delete one lineage call from `dropCanvasOutputs` and see the scan fail.
- A2. Entry matrix test `DeletionMatrixTest`: a declarative table (entry x artefact kind -> expected effect: deleted, hidden, invalidated, outbox target, fence moved), asserted from the same fixture. Documents section 1 in executable form.
- A3..A10. One fix per GAP (section 5), each its own small commit with RED first, ordered by rank.

**Phase B: planner and executor (post-v1, or pulled in only if the user wants the structural guarantee before v1)**

- B1. `ItemRef`, `ForgetScope`, `ForgetCause`, `ForgetPolicy` (data only) and the widened inventory test over ALL tables with an `ItemKind` owner per table. No behaviour change. Tests: `ItemRefTest` (round trip, no bare ids), widened `DeletionGraphInventoryTest`.
- B2. Extract `ForgetDiscovery` from `MomentForget.run` steps 1-2 and `ForgetService.cascade` steps 1-3 into a class both call. Both keep calling their own DAO code afterwards. Characterisation: all existing `MomentForget*` and `ForgetService*` tests unchanged and green; plus the residue scan unchanged.
- B3. `ForgetPlan` types, `ForgetPlanner` for `Frame` and `Memory` refs, and a **shadow test**: for each fixture, `plan(...)` is computed and compared with what the old path did (rows deleted, outbox rows, fences moved, hides, tokens) on a cloned database; any difference fails. Old path still does the work.
- B4. `ForgetExecutor`; switch E1, E2 (`forgetFacts`, `forgetMoment`) to `plan + apply` behind thin callers; E3, E4, E5 next (policy rows for the retention causes). Old private cascade code is deleted only when its shadow test has been green for a full phase. Tests: existing `ForgetServiceTest` (27), `ForgetOutboxPathsTest`, `PhotoExpiryTest`, `RetentionWorkerTest` (36) unchanged.
- B5. Marks and labels: `deleteLabel`, `deleteLabelsBetween`, `deleteLabelsForFrames` and `MomentForget` stray labels go through `Mark` plans; retag and correct become "change" plans (hide only). `LineageToken` becomes the sealed type and `CanvasRunner` encodes through it (compile-driven, touches `CanvasRunner.kt:613-626` and `MomentResolver.markLineage`).
- B6. Ranges: `MomentForget` and `CanvasSlice3Forget` become `Range` plans with `RangeDepth`. Still no UI caller; the shadow tests compare against the old code.
- B7. Outbox: sealed target; `OutboxDrainer.plan` exhaustive; the file post-commit action replaces `FrameSweep.delete` inside `ForgetActions.apply` (the sweep stays as the retry net).
- B8. Delete all: `All` planned and applied by `WipeDao` strategy; `DeletionGraph` derived from `ItemKind`; the DAO delete primitives made `internal`; ratchet tests (no deleter outside `forget`).

### 3.9 Out of scope

- The `CanvasItem(itemId, value, itemType)` runner refactor, resolver registry, encoded keys `"<type>:<id>"`, `input.moments` executor/cost contract and `itemType` migration (Moment-spec phase 2 proper, 17-28 h): it solves "canvas nodes accept more than photos", not "deletion is complete".
- The generic `derived_from(derivedType, derivedId, rootKey)` edge table of Moment-spec 8.3 (every derived row carries its roots): a schema change and a new mandatory writer API. This design reaches the same closure with lineage tokens that already exist plus two new token kinds; revisit only if token matching proves too weak.
- The glasses and laptop transports for the outbox (ports are `NotReachable*` by design in v0.9.5), trained-artifact cascades (no trained artefacts exist), Audio parts, Scenes beyond the existing shrink rule, exports handed out to other apps (cannot be recalled), user-authored config (pipelines, prompts, versions: KEPT by design).
- Hostile-code hardening (threat model A, slice 3d).

## 4. RISK and COST

### 4.1 Estimates (agent-hours of a Sonnet implementer, including RED tests and the one named mutation per behaviour; excluding Codex review turn-around and the user's device checks)

| Step | Content | Hours (low-high) | Confidence |
|---|---|---|---|
| A1 | residue helper + full-world fixture + per-entry baselines | 8-12 | medium (the fixture touches ~30 tables) |
| A2 | declarative matrix test | 3-5 | high |
| A3 | GAP-1 export file removed on Forget (post-commit file step + test) | 1.5-2.5 | high |
| A4 | GAP-2 watch: ring cleared on Delete all, `watch:` rows by range/Forget, `watchEpoch` fence for an in-flight run | 3-5 | medium |
| A5 | GAP-3 sensor token for Scene runs + deleter | 3-5 | medium (touches `CanvasRunner` lineage assembly) |
| A6 | GAP-4 batch-mate closure for Moment runs (latent; RED test first proves reachability) | 2-4 | medium |
| A7 | GAP-5 outbox trim to coalescing instead of dropping | 2-3 | high |
| A8 | GAP-6 retag invalidates replays and sweeps; GAP-7 per-call post-commit queue | 2-4 | high |
| A9 | GAP-9 `frame_cluster` id residue + widen the inventory to all tables | 2-3 | high |
| A10 | GAP-8, GAP-10 need a USER decision first (section 5); implementation after | 2-4 | n.a. |
| **Phase A total** | | **28-47** | |
| B1 | types + inventory widening | 3-4 | high |
| B2 | extract discovery | 6-9 | medium |
| B3 | planner for Frame and Memory + shadow tests | 10-15 | medium-low (the retention policies are the subtle part) |
| B4 | executor + move E1-E5 | 7-10 | medium |
| B5 | marks, labels, sealed lineage tokens through the runner | 7-10 | medium-low |
| B6 | range plans (E8, E9) | 5-8 | medium |
| B7 | outbox exhaustiveness and post-commit file step | 4-6 | high |
| B8 | Delete all strategy, derived registry, DAO visibility ratchets | 5-8 | medium |
| **Phase B total** | | **47-70** | about 50 percent within the range |

Compare the Moment spec: phases 2+3 together 26-43 h, of which roughly 17-28 h is the runner refactor this design drops. The B-total above is larger than the spec's phase 3 (9-15 h) because the spec assumed root-set inheritance (a schema and writer change) and did not count the shadow tests that make the move safe.

### 4.2 What could regress

- **Fence ordering.** `MomentForget` writes fences first, `forgetRangeTx` writes the range fence first, `WipeDao` is epoch-first across processes. A generic executor that reorders phases re-opens the "run resurrected deleted rows" class. Mitigation: fixed phase order, `MomentForgetRacingBuildTest` and `ForgetFenceTest` stay unchanged, and fence-first is asserted on the plan.
- **Cause policy drift.** Retention differs from a user Forget in five ways (phone files only, no accepted-proposal scrub, keyframes kept, no descendant invalidation, no redaction of cites). Collapsing them into one table is the riskiest edit; the shadow test per cause is the guard.
- **Transaction size and chunking.** Everything is chunked at 500 for SQLite's variable limit; a plan that carries all ids at once must keep chunking in the executor. A Moment Forget of a long range is one large transaction today; the executor keeps that, it does not make it larger.
- **Listener timing.** Replacing `pendingFrames` and `ForgetListeners` changes when files and the glasses are told. `ForgetListenerTest` (6) and `CanvasForgetCoordinatorTest` (10) pin it; the new post-commit list must be delivered exactly once and never on rollback.
- **Outbox uniqueness.** Idempotence relies on the unique (target, payload) key and on `INSERT IGNORE`; counts reported in `MomentForget.Result.outboxRows` must not change (`ForgetOutboxPathsTest` pins "a repeated Forget queues nothing new").
- **Lineage encoding.** `batchId` is a text column matched with `instr('|' || batchId || '|', '|' || token || '|')`. A sealed `LineageToken` must encode to exactly the strings stored today or old rows become undeletable. Pinned by the Moment-run and mark-run tests; add an explicit "legacy string decodes" test.
- **Delete all.** Crash recovery (`resumeIfPending`, `afterEpochCommit`) must stay byte-for-byte; B8 deliberately leaves the protocol alone.
- **Schema.** None is needed (token kinds and the export target are data). If B5 chose to add a lineage table instead of tokens, that would be an additive Room migration (v13); not recommended.

### 4.3 Existing tests that pin behaviour (about 480)

Memory module: `ForgetServiceTest` 27, `MomentForgetTest` 19, `MomentForgetEntryPointsTest` 20, `CanvasSlice3ForgetTest` 16, `CanvasSlice3DaoTest` 31, `HelperDeletionTest` 15, `HelperDeletionHooksTest` 12, `ForgetCanvasTest` 11, `ForgetOutboxPathsTest` 10, `ForgetFrameSourceTest` 7, `ObjectAuditTest` 7, `ForgetListenerTest` 6, `DeletionEpochStoreTest` 6, `DeletionGraphInventoryTest` 5, `WipeDaoTest` 5, `IntervalOverlapSelectionTest` 4, `DeletionOutboxInventoryTest` 3, `DeletionFencePinsTest` 2, `ForgetFenceTest` 2, `DeletionGraphWipeTest` 1. Orchestration: `RetentionWorkerTest` 36, `NightIndexWorkerTest` 37 (describe-before-delete), `MarkRecorderTest` 23, `OutboxDrainerTest` 20, `MemoryWipeTest` 14, `CanvasForgetCoordinatorTest` 10, `PhotoExpiryTest` 10, `ForgetActionsTest` 9, `FencedFilesTest` 9, `MomentAuditTest` 9, `ResummarizeWorkerTest` 8, `DeleteAllFenceTest` 6, `WipeFenceTest` 6, `PhoneFileCleanupTest` 6, `OutboxRoomTest` 6, `MomentForgetRacingBuildTest` 5, `OriginFencedIntakeTest` 4, `MemoryRetentionTest` 4, `CanvasRunBatchForgetTest` 1 (photo runs only: no Moment-run batch test). Glasses: `TraceCleanupFenceTest` 23, `MarkOutboxTest` 13. Phone: `CanvasSlice3aRoutesTest` 6. Source-scan ratchets: `DeletionOutboxInventoryTest` (files that call the cascades must queue), `DeletionFencePinsTest`, `DeletionInventoryFilesTest`, `MemoryHasNoOrchestrationDependencyTest`.

What they do NOT pin: any cross-table residue (section 3.7), the time-link semantics of the two range entries versus each other, retention versus marks (the pinned test `retentionPhotoExpiryReachesTheSameTables` makes the +-10 s mark deletion a deliberate rule), exports, watch values, sensor tokens, and Moment-run batch mates.

### 4.4 Recommendation

**Do Phase A now (oracle, matrix and the inventory-driven gap fixes), defer Phase B to after v1, and never do the runner `CanvasItem` refactor for this purpose.**

Reasons:

1. **The risk to the user is in the gaps, not in the number of entry points.** Five gaps are real or probable today (section 5), the top three are cheap (about 2, 4 and 4 hours) and none needs a design change. Fixing them is a smaller and faster privacy gain than any refactor.
2. **The refactor moves privacy-critical, race-sensitive code that is currently green.** About 480 tests pin it and it passed three Codex review rounds; B3-B6 touch fence order, cause policy, lineage encoding and listener timing, and a regression there is silent (a row left behind), not a crash. The shadow tests make it safe but cost most of the hours, and the user's budget is 86 percent spent with the v1 freeze checklist (worn run, M1, WP13 live steps, release build) still open.
3. **Only five triggers are reachable from the UI**, and the two range entries that motivate "one Forget" have no caller. The unification pays off when the range Forget gets a screen; deciding what the range Forget MEANS (section 5, GAP-10) is a product question that should come first, and it can wait for v1.1 without risk because nothing can trigger it yet.
4. **The oracle gives most of the structural guarantee for a fraction of the cost.** It catches the class "something still names the forgotten thing" for every present and future entry, writer and token kind, and it is what any later planner must satisfy anyway (it is the equivalence proof for Phase B).
5. **Go-criteria for Phase B after v1**: (a) a range Forget screen or route is scheduled, (b) a third Codex round still finds a "deleter forgot X" defect after Phase A, or (c) Audio parts or a new derived artefact kind is about to be added (a new `ItemKind` is the moment the exhaustive types pay for themselves). If none holds, keep Phase A's matrix and ratchets as the permanent guard.

If the user prefers the structural guarantee before v1 regardless, the cheapest slice that delivers it is A1-A2 + B1 + B2 (about 20-30 h): the oracle, the widened all-tables inventory, the typed `ItemRef` and a shared discovery, with no behaviour moved.

## 5. REAL GAPS today, ranked

Ranking = reachable in production today x privacy weight x confidence. File:line is the place a fix lands. All are from code reading; each starts with a RED test (A1) that proves it. Gaps 8 and 10 are not bugs but rulings the user must make.

**GAP-1 (high). A Forget never deletes the memory export.** `MemoryExport.write` leaves `cacheDir/exports/proactive-context-memories-<ts>.json` (all memory texts and asks, `MemoryExport.kt:20,76`); only Delete all removes it (`MemoryWipe.kt:137`). `ForgetActions.apply` (:42-65) and `ForgetService` never touch it. Scenario: the user exports, then uses "Forget this fact" for a sensitive memory. The database row is gone, the outbox is clean, asks are redacted, but the old JSON still contains the text until the next export or Delete all. The Moment spec lists EXPORT in the per-type policy table (delete the app-owned copy) and no code does it. Fix: a post-commit step in `apply` that deletes (or marks for regeneration) the export directory for USER-cause deletions of memories or asks; later a `File(EXPORT)` plan entry. ~2 h.

**GAP-2 (high-medium). Watch values are the one user-data store with no unified deletion.** (a) `ContextHub.watchRing` (in memory) survives Delete all: `HubGraph.kt:207` passes `onWiped = sender::resetAfterWipe`, which resets plan state and not the ring (`ContextHub.kt:79,230`). (b) Run items keyed `watch:<atMs>` are deleted only by watch-off (`CanvasDao.kt:45`), run pruning and Delete all; a photo or range Forget never removes them. (c) Watch-off does not fence an in-flight run: `deleteWatchRuns` deletes the run row of a run that already holds items, but the runner's later `flush()` goes through `insertRunItemsFenced` (`CanvasDao.kt:119`), which checks only the in-process wipe generation, frame tombstones and mark ids, so the remaining nodes' rows (with watch-derived values) are inserted again with no parent run row. This is the "run resurrected deleted rows" class on a store that has no fence. Scenario: start a watch run, switch the watch source off while it runs, then Delete all: the ring still serves the old samples to the next watch run, and orphan items hold values. Fix: clear the ring in `onWiped`, add a `watchEpoch` bumped by watch-off and checked by `insertRunItemsFenced` for `watch:` keys, delete `watch:` rows in range Forgets. ~4 h.

**GAP-3 (medium). Scene run rows keep sensor numbers after the trace is forgotten.** `SceneInputExecutor.kt:80` and `SceneRunSource.kt:32` write the Scene's `sensor` summary into the item value stored in `canvas_run_item`. Lineage tokens written by `CanvasRunner.kt:613-626` are frames and marks only. When a Forget tombstones a whole trace (`CanvasSlice3Dao.tombstoneTrace` :240 deletes whole overlapping traces), `purgeSensorOfTrace` (`DerivedStaleDao.kt:61`) deletes the sensor rows of ALL Moments from that trace and hides their derived fields, but no run row is touched. Scenario: a 2-hour glasses trace overlaps the +-10 s span of a forgotten photo; yesterday's Scene run over the neighbouring hour still holds that hour's activity and sensor summary. (Moment runs are not affected today: `momentItem` writes `"sensor" to JsonNull`, `CanvasRunner.kt:770`.) Fix: a `SensorOfTrace(traceId)` token written when a Scene item carries a sensor summary and deleted by the purge. ~4 h.

**GAP-4 (medium, latent). Moment-run batch mates survive a photo Forget.** `deleteCanvasOutputsForFrames` (`CanvasDao.kt:218-227`) finds the batch tokens of a forgotten frame from rows keyed by that FRAME id (`itemBatchesOf`), then deletes all rows sharing a batch token. Moment runs key rows by the Moment key, so no row has the frame id; only the `mf:<frameId>` token is deleted, which removes the forgotten photo's own Moment rows, not the other Moments that shared its model call (`Executors.kt:83`, `batchId = runId:nodeId:firstKey`). It matters when two or more Moments share one vision call (`batch` > 1; default is 1 and a Moment vision node is not yet shipped, **verify** reachability). Fix: close the token set over the rows found by `mf:` tokens before deleting. ~3 h.

**GAP-5 (low-medium, known). Privacy rows for the glasses are dropped silently, and nothing drains them.** `OutboxTriggers.kt:19` binds `NotReachableGlasses` and `NotReachableHelper`, so every GLASSES_FRAME, GLASSES_TRACE and GLASSES_RANGE row stays pending; `OutboxDrainer` then TRIMS each glasses target to its newest 2000 rows (`OutboxDrainer.kt` `GLASSES_CAP_PER_TARGET`, `store.trim`), contradicting its own rule "a privacy deletion does not expire". Unacked glasses marks are also not purged by a range Forget (`MarkOutbox.kt:71` clears only on an epoch change); the phone refuses them on arrival via `forgetRanges`, so only the glasses copy lingers. Fix: coalesce adjacent range rows instead of dropping (frames need the transport). ~3 h; the transport is separate work.

**GAP-6 (low). Retag does not invalidate stored replays and sweeps.** `retagLabel` (`CanvasSlice3Dao.kt:154-160`) bumps `labelRev` and hides derived fields but, unlike `deleteLabel` (:147) and `correctLabel` (:162), does not call `invalidateBySources(..., labelRevChanged = true)`; replays and sweeps computed under the old tag stay `invalidated = 0` and are still returned by `validReplays` as plan evidence. Fix: one line plus a test. ~1 h.

**GAP-7 (low, latent). `ForgetService.pendingFrames` is shared per instance** (`ForgetService.kt:192-202`): a failing transaction does `pendingFrames.clear()`, discarding the notifications of a concurrent successful one, and a success can drain another call's entries before that call commits. Not reachable while each UI Forget builds its own `ForgetService` (`ForgetActions.kt:80`), reachable as soon as `MomentForget` and retention share one. Fix: return the post-commit list from the transaction. ~1.5 h (free in Phase B).

**GAP-8 (ruling needed, behaviour). Retention deletes marks and tells the glasses.** `expirePhotos` runs `dropCanvasOutputs`, which for each expired photo deletes every mark and tombstones every trace within +-10 s, voids ALL open helper jobs and adds a durable forget range (`ForgetService.kt:211-224`); `retentionPhotoExpiryReachesTheSameTables` pins this as intended (S25). With marks now first-class Moments (mark-moments handoff) a 30-day photo expiry silently deletes the user's own marks next to it, the Moment spec 8.7 says the Moment merely "keeps the FRAME ref flagged expired", and the outbox says "phone files only" while the listener still sends the glasses a forget range for the same expiry (`CanvasForgetCoordinator.afterRange`). Ruling: should routine retention delete marks and send glasses ranges? Until decided the three statements contradict each other.

**GAP-9 (low). Id residue in core tables.** `frame_cluster.repFrameIds` keeps the forgotten frame id after E1, E2, E4 (Delete all clears it); `summary_trace` is documented as ids and counts. The inventory test covers only `canvas_`, `moment`, `object_`, `scene`, `cleanup_outbox` tables (`DeletionGraphInventoryTest.kt`), so core tables have no deletion decision on record. Fix: widen the inventory to all tables (Phase A, cheap) and either prune cluster ids or record the reason.

**GAP-10 (ruling needed, product). Two range Forgets with different meaning and no caller.** `MomentForget.forgetRange` deletes frames, memories, marks, traces and Moments of the range; `CanvasSlice3Forget.forgetRange` deletes marks and traces and keeps photos and memories. Neither is reachable from the app (section 0). Before any screen is built the user must say which one "Forget this time range" is, or whether it has two depths (`RangeDepth` in 3.2).

**GAP-11 (low, race). Frame admission re-checks the Moment fence in the transaction but not the frame tombstone.** `FrameIntake.commit` (:24) checks `momentForgetRanges` and `moment_tombstone` inside the transaction; the `forget_tombstone` check is upstream in `RokidFrameInbox.kt:404,430`, outside it. A re-delivered, already forgotten frame id between the check and the commit can come back as a new row (the glasses resend window is 7 days). Fix: add `forgetDao().tombstoned` to the in-transaction check. ~1 h.

**GAP-12 (unverified). Run rows computed from a descendant memory that Forget only hides.** `cascade` marks derived memories D INVALIDATED (`ForgetService.kt:148-154`) and drops Moments holding them, but run rows whose lineage contains only the frames of D (not of the forgotten source F) are not deleted, so a Moment run that read D's text before the Forget keeps it. Whether D's text reaches run rows depends on how `liveMemories` (`CanvasDao.kt:192`) filters INVALIDATED; **verify** with a RED test in A1 before spending any time.

### Top three in one line each

1. GAP-1: forgetting a fact leaves its text in the cached memory export JSON.
2. GAP-2: watch values survive Delete all in memory, are not removed by any Forget, and an in-flight run re-inserts them after watch-off.
3. GAP-3: Scene run rows keep sensor summaries after the trace they came from is forgotten.
