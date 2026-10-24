# Moment model design: a first-class Moment, Scenes, typed canvas items, simple cascade deletion

Status: DESIGN ONLY (phase 0). Reviewed through m2; re-review before phase 1 is scheduled. Changelog: m1 (17 findings applied); m2 (late-admission fences and receipt discovery, outbox survives Delete all, unknown-time handling; phase 1 +4-6 h, phase 2 +1-2 h). No code, no migration, no device access. Date 2026-10-23. Paths are relative to `android-companion/` unless they start with `docs/`.
Line numbers are from the tree read on 2026-10-23 and drift; the symbol names are the contract.

## 1. Goals and non-goals

User intent (binding):
1. A **Moment** is keyed by a unique hex id derived from the wall-clock timestamp at one-second resolution. It holds references to the photos/frames, sensor sub-parts (per-second feature vectors), citations, memory entries, marks and other objects of that second.
2. A **Scene** is a collection of Moments. Scene identification over photos comes later.
3. Canvas items become `CanvasItem(itemId, value, itemType)`. `itemType` selects an object resolver (`momentObj`, `sceneObj`, later `promptObj`, `classifierObj`, `intentObj`, `embeddingObj`), so callers stay unchanged and the base logic gains only per-type flows.
4. Deletion by id or by timestamp range, cascading simply: deleting a photo deletes its Moment and everything derived (summary etc.).

Adjustments adopted in this design (each checked against the repo, section 2):
- Lookup speed is not the problem (all keys are indexed Room keys). **Cascade completeness is.** Hence a dependency DAG with an indexed edge table `derived_from(derivedType, derivedId, rootKey)` carrying the full inherited root set (8.3). A linked list cannot express one photo living in one Moment, several Scenes and several derived outputs.
- A Scene that loses a member Moment is **not deleted**; its derived artifacts are invalidated.
- Prompts, classifiers and embeddings are config or derived artifacts, not world data. They share the ItemRef/resolver mechanism but only `MOMENT` ships first, with an open registry.
- A Moment is a **derived read model** (rebuildable) over the source-of-truth stores (frames, memory, traces, marks). It does not replace them.
- One-second buckets lose the 20 ms precision replay needs. **Replay keeps using raw traces.**
- Only **non-empty** Moments are stored (sparse).
- A second may hold a **list** of frames.
- Sensor-only seconds are ephemeral resolved objects, never stored (3.4).

Non-goals: scene identification algorithm; prompt/classifier/intent/embedding resolvers (registry slots only); changing the trace format (frozen schema 1); changing capture, gate or replay behaviour; a watch implementation; moving any existing table's source of truth; touching the v1 release blockers.

## 2. Current state (with citations)

**Photo-shaped storage.**
- `FrameEntity(id, ts, path, source, payload, luma, phash, placeLabel)`, table `frame`, index on `ts`: `services/memory/.../db/MemoryEntities.kt:13-23`. `MemoryEntity(id, frameId?, text, type, tags, tier, confidence, reviewState, trusted, createdAt, derived, searchText)`, indices `frameId`, `createdAt`: `MemoryEntities.kt:26-40`. `MemorySourceEntity(memoryId, sourceMemoryId)` is already a memory-to-memory derivation DAG (`MemoryEntities.kt:~47`). `ForgetTombstoneEntity(frameId, createdAt)` is the frame-keyed extraction lock (`MemoryEntities.kt:~51`).
- `frame_meta` (`FrameEntities.kt:9-34`) holds `wireFrameId`, `motion`, `dwell`, `clusterId`, `createdAt` (receive time) and `capturedAtMs` (capture time on the phone clock, Room v5; null on pre-v5 rows). `frame_source(memoryId, frameId, clusterId, role)` links a memory to each photo it cites (`FrameEntities.kt:53`); `frame_cluster` is the summary-input grouping (`FrameEntities.kt:57`).
- Frame id: glasses `CaptureService.newFrameId(nowMs)` = `"g${nowMs}_${6 hex random}"` (`glasses/.../capture/CaptureService.kt:322`, used at :314 and :530). So the id already carries glasses wall epoch milliseconds, which is why a second bucket can be derived from an id.
- Phone ingest: `RokidFrameInbox.ingestPending` writes `FrameEntity.ts = file.lastModified() ?: now` (`services/orchestration/.../data/frames/RokidFrameInbox.kt:~305`). That is a file time, not a trusted capture time; `frame_meta.capturedAtMs` (set by `FrameDaos.setCapturedAt`, `FrameDaos.kt:382`) is the better source. `PhotoRecall` already prefers `frame.ts`, then `capturedAtMs` (`PhotoRecall.kt:28`).

**Canvas is photo-shaped.**
- `data class CanvasItem(val frameId: String, val photo: ExtractPhoto, val value: CanvasValue)` at `services/orchestration/.../canvas/NodeExecutor.kt:15`; `NodeResult(frameId, value, ...)` at :31. `CanvasRunner` loads `photos.load(ids)`, maps `associateBy { it.frameId }`, and writes per `(runId, frameId, nodeId)` (`CanvasRunner.kt:301-316`, :359-362). Limits `MAX_PHOTOS = 50` (:157, :211).
- `PipelineValidator` requires exactly one `input.photos` for any graph with an ASYNC half (`services/ai/.../canvas/PipelineValidator.kt:70-74`; node constant `NodeTypes.kt:51`).
- Tables: `canvas_run_item` PK `(runId, frameId, nodeId)`, index `frameId`; `canvas_score` same PK (`services/memory/.../db/CanvasEntities.kt:50,69`). DAO: `CanvasDao.deleteRun` (:39), `pruneRuns` (:42), `items` (:45), `upsertScoreFenced` (:52, checks fence plus parent item exists), `insertRunItemsFenced` (:113), `fenceHolds` (:120: generation equal AND no tombstoned frame id; `tombstonedAmong` :125).
- Room is `version = 7` (`ContextDatabase.kt:20`). 3b core plans v8 and states "3c takes v9" (`docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md:19`).

**Deletion today.**
- `ForgetService.forgetMoment(frameId)` (`services/memory/.../forget/ForgetService.kt:54-72`): one transaction; cascade of memories over `memory_source` descendants (:131-147), tombstone (`insertTombstone`, :67), `dropCanvasOutputs` (:62,68), frame row and frame_source/vector/meta/state deletes (:69-70). Fact Forget invalidates descendants (hidden, vectors dropped), the re-summarise worker rebuilds them (class doc :14-22). `notifying` fires `ForgetListeners.framesForgotten` / `rangeForgotten` only after commit (:~170).
- `dropCanvasOutputs` (`ForgetService.kt:~177-190`) already treats a photo as a moment in time: it widens each forgotten photo by `PHOTO_LINK_MS = 10_000` (:~205) and calls `canvasSlice3Dao().forgetRangeTx(from, to, now)`, then `deleteCanvasOutputsForFrames` and `deleteSliceOutputsForFrames`. The "link between photo, marks and traces is TIME" rule exists today as an ad-hoc 10 s window. The Moment makes it explicit and per-second.
- Range forget: `CanvasSlice3Forget.forgetRange` (`.../forget/CanvasSlice3Forget.kt:17`) calls `CanvasSlice3Dao.forgetRangeTx` (`CanvasSlice3Dao.kt:47-55`): durable range fence first (`addForgetRange`, canvas_meta key `forgetRanges`, :26-30, :299), tombstones overlapping traces (`tombstoneTrace`, :167: events deleted, stub kept so a late chunk is refused), deletes marks (`canvas_label`) in range, bumps `labelRev`, invalidates replays and sweeps reading them. Idempotent.
- `DeletionGraph` (`.../forget/DeletionGraph.kt:8`) is the inventory: every `canvas_*` table, prefs, dirs and GLASSES files is WIPED or KEPT with a reason; `DeletionGraphInventoryTest` currently scans only `canvas_*` tables (`DeletionGraphInventoryTest.kt:14,38`) so it must be extended for new tables, and `WipeDao` has explicit DELETE statements that need matching additions (section 8.7).

**Traces and clocks.**
- Trace on disk: `manifest.json` + `chunk-0000.json...` (`core/capture/.../trace/TraceFiles.kt:49-65`); each chunk is a `TraceChunk` with header `["PCT1", traceId, seq, count]`, payload gzip+base64 of JSON-array event lines `["<tag>", tMs, fields...]` (`TraceRecords.kt:66`). Tags (frozen, 16): `g, a, cx, rs, pf, gp, pl, ep, sc, gt, fl, bo, bl, dk, pr, cl` (`TraceRecords.kt:331`); `g`/`a` = `[tag, tMs, sMs, x, y, z]` (:335, :365-366); `ep` episode open/close with cause and outcome (:338); `gt` gate outcome with `keptAtMs` (:340). Every `tMs` is glasses `elapsedRealtime`, non-decreasing; `sMs` is the sensor's own time and is NOT ordered (`TraceRecords.kt:~62-85`).
- `Manifest` (`Manifest.kt:~50-80`): `startElapsedMs/endElapsedMs`, `startWallMs/endWallMs` (glasses wall), `startPhoneWallMs/endPhoneWallMs`, `startPhoneAgeMs/endPhoneAgeMs`, `phoneOffsetMs` (phone minus glasses wall, filled at intake) and `offsetUncertaintyMs`, `bootId`, `installId`, `deletionCounter`, `revision`. `ClockMap` (`ClockMap.kt`) maps elapsed to glasses wall and phone wall, answers null on a wall jump above `MAX_WALL_JUMP_MS = 2000`, on stale phone samples (`MAX_PHONE_AGE_MS = 120_000`), on a phone jump (`MAX_PHONE_JUMP_MS = 2000`), outside `SLACK_MS = 60_000` of the trace, or for another `bootId`; `uncertaintyMs` is never null.
- **Known defect:** `TraceImport` stores `startedAtMs = manifest.startWallMs, endedAtMs = manifest.endWallMs` (`services/orchestration/.../canvas/trace/TraceImport.kt:87`), which are GLASSES wall times. `forgetRangeTx` compares them with the PHONE-clock range the user typed (`CanvasSlice3Dao.kt:49`). With an offset of seconds (a ~107-144 ms offset was measured at phase 0 of the link work, `docs/superpowers/plans/2026-10-12-v1-phase3-cxr-link.md:1948`; larger after a clock correction) a range forget can miss a trace edge. Section 7 fixes this for Moments and recommends the same fix for `canvas_trace`.

**Sensor features and marks.**
- `MotionClassifier.onGyro(x,y,z,tMs)` (`core/capture/.../glasses/capture/motion/MotionClassifier.kt:108`), `onLinearAccel` (:137), `features()` (:260) returning `Features(gyroMax, accVariance, stepHz, state, gyroMean, gyroRms, aboveOnFrac, rotRad, glances, micro)` (:254). `SensorFeed` turns that into `SecondFeatures(gyroMax, gyroMean, gyroRms, aboveOnFrac, accVar, stepHz, rotRad)` once per second (`glasses/.../motion/SensorFeed.kt:136,153`; `model/MotionModel.kt:7-12`, `toArray()` :11, order is the trained tree's index order). `FeatureLogger` logs `gyroMax/accVar/stepHz/state` (`FeatureLogger.kt:~33`).
- Marks: glasses `MarkOutbox.record(atMs, atElapsedMs, bootCount, listen, undo)` queues `MarkMessage(epoch, seq, atMs, atElapsedMs, bootCount, ...)` (`glasses/.../mark/MarkOutbox.kt:~56`). Phone stores them as `CanvasLabelEntity(labelId, kind MOMENT|INTENT, source, name, atMs, frameId?, traceId?, ...)`, indices `atMs`, `frameId` (`CanvasSlice3Entities.kt:100-114`). A mark carries no frame id (the tap does not know it); marks and photos meet only by time.
- Summaries: derived `MemoryEntity` rows (`derived = true`) produced by the summariser from a `frame_cluster`; edges in `memory_source` and `frame_source`.

**3b core plan today** (`docs/superpowers/plans/2026-10-23-canvas-slice3b-core/00-core-plan.md`): WP3 adds Room v8 incl. a trace-derived **result store** and `canvas_meta frame_forget_rev` (line 43); WP4 adds helper deletion fences; WP8a defines trace items identified by `(traceId, revision, chunk seq, ordinal)` with provenance fields and estimate tokens binding `(traceId, revision, itemId)` tuples (line 48, 70); addendum A1 forbids scores and notes on trace runs (line 93); WP8b `PythonExecutor` over photos and trace items.

## 3. The Moment record

### 3.1 Id specification
- `momentId = lowercase hex of floor(canonicalWallMs / 1000)`, zero-padded to **8 digits** (`String.format("%08x", sec)`). 8 hex digits cover epoch seconds up to 2106-02-07. Builder and parser assert `sec in 0..0xFFFFFFFF`; a test pins the failure past that bound. (Q2: 10 digits to be future-proof; default 8.)
- Ordering: fixed width makes lexicographic order equal chronological order, so `ORDER BY momentId` and `momentId BETWEEN :a AND :b` are correct and use the primary-key index. `MomentId.parse` rejects anything not `[0-9a-f]{8}`.
- Time of a Moment: `startMs = sec * 1000`, `endMs = startMs + 999`. A range `[fromMs, toMs]` maps outward to `[floor(fromMs/1000), floor(toMs/1000)]` inclusive on both ends. The **outward-rounded full-second fence** `[floor(fromMs/1000)*1000, floor(toMs/1000)*1000 + 999]` is what the cascade persists as the range fence (8.2), not the raw typed range.
- Collisions: none by construction (pure function of the second). Two photos in one second are two entries of `frames[]` in one Moment. The builder is idempotent: upsert merges parts, never creates a second row.
- Canonical second: section 7.1. A frame with no usable capture evidence gets no Moment and stays photo-only (one policy, used everywhere in this spec: **unknown capture time = no Moment**; receipt time is used only when the frame is flagged `RECEIVE_TIME` and still recorded with its true uncertainty, see 7.1).

### 3.2 Fields (references, not copies)
Table `moment`: `momentId TEXT PK`, `buildRev INT` (builder code version), `contentRev INT` (monotonic, bumped by the builder whenever the set of parts or any part's content fingerprint changes), `contentHash TEXT` (SHA-256 over the canonical, sorted part list including each part's own revision), `builtAt INT`, `clockQuality TEXT` (EXACT | OFFSET_ESTIMATED | RECEIVE_TIME | SUSPECT), `uncertaintyMs INT`, `deletionCounter INT`.
Deterministic aggregation: Moment `uncertaintyMs` = max over parts; `clockQuality` = the worst part in the order EXACT < OFFSET_ESTIMATED < RECEIVE_TIME < SUSPECT. `SUSPECT` is set by a detected phone/wall clock jump (7.4) and is a declared state of the enum.
Table `moment_part` (PK `(momentId, kind, refId)`, index `(kind, refId)`, index `momentId`), plus per-part `partTimeMs`, `uncertaintyMs`, `partRev` columns:

| kind | refId | points at (source of truth) |
|---|---|---|
| FRAME | `frame.id` | `FrameEntity` (JPEG at `path`); several per second |
| MEMORY | `memory.id` | `MemoryEntity` whose `frameId` is a frame of this Moment, or whose `createdAt` second matches for non-photo memories |
| CITE | `frame:<frameId>` | saved ask/nudge citing a frame of this Moment (`PHOTO_CITE_PREFIX`, `ForgetService.kt:12`) |
| MARK | `canvas_label.labelId` | marks/intents with canonical `atMs` in this second |
| SENSOR | `<traceId>@<revision>` | per-second feature vector in `moment_sensor` |
| SUMMARY | `memory.id` (derived) | derived summary memory sourced from a MEMORY/FRAME of this Moment |
| OTHER | namespaced id | registry slot (ask, proposal) |

Table `moment_sensor(momentId, traceId, revision, f0..f6 REAL, state TEXT, nGyro INT, nAcc INT, quality TEXT, warmup INT, partial INT, PRIMARY KEY (momentId, traceId, revision))`: derived, rebuildable, feature numbers and counts only, no raw samples. Arbitration when several traces cover one second: all rows are kept (PK includes traceId and revision); the **resolved** read picks deterministically by (higher `nGyro+nAcc`, then not `partial`, then higher `revision`, then lexicographically smaller `traceId`), and the losers stay visible in `quality`. Rows of superseded revisions of the same trace are replaced in one transaction.

### 3.3 Stored vs derived
- Source of truth stays in `frame`, `frame_meta`, `memory`, `memory_source`, `canvas_trace` + trace files, `canvas_label`. They keep their own Forget logic.
- `moment`, `moment_part` are a **projection**: `MomentBuilder` can drop and rebuild them (test: build, wipe, rebuild, equal). A corrupted table is a rebuild, never a data loss, never backed up.
- **Deletion never trusts the projection** (8.1). Because the projection is asynchronous and can be incomplete, Forget discovers sources from the authoritative stores independently and unions them with projection references.
- `moment_sensor` is the only derived payload, derivable from sealed trace files; if the trace is tombstoned the sensor rows are deleted and cannot be rebuilt (correct).

### 3.4 Sparse rule and sensor-only seconds (Q4 decided)
A stored Moment row exists only if it has at least one non-SENSOR part (FRAME, MEMORY, CITE, MARK, SUMMARY, OTHER). Decision: **sensor-only seconds are ephemeral resolved objects, never persisted.** A time-window experiment resolves a second by (a) the stored row if it exists, else (b) a transient `MomentObj` assembled from `moment_sensor` rows computed on demand from the sealed trace (or from stored `moment_sensor` rows). Selection is defined independently of stored sparse rows: a window `[from,to]` expands to its seconds, each second resolved as above. No persistent sensor-only exception exists, so the builder never removes rows for that reason. Attachment: when a frame/mark later creates the Moment, the builder (triggered by the frame/mark insert, and on every trace import or revision) attaches the existing or recomputed SENSOR part, so import-before-frame and frame-before-import converge. Forget fences cover seconds that never had a row (8.2). Tests: `ImportBeforeFrameAttachesSensorTest`, `SensorOnlyWindowEphemeralTest`, `SensorOnlySecondForgetFencedTest`.

## 4. Sensor sub-part: per-second feature vector

**Same code as capture, same time axis as replay.** `TraceSecondFeatures` (new, `core/capture`, plain Kotlin) feeds a fresh `MotionClassifier` exactly as `SensorFeed` does. Events are traversed in **append order**, and the classifier receives the event's **sensor time `sMs`** (`TraceRecords.kt:73-77`: replay feeds `sMs` to the classifier and uses `tMs` only for scheduling; live `SensorFeed.kt:111-115` passes sensor time). Delivery-time batching must not collapse sample spacing, so `tMs` is never passed to `onGyro`/`onLinearAccel`. Recorded configuration (sample rate, thresholds) is restored from the manifest/`cx` events before the first sample; the first window after a (re)configuration is flagged `warmup`, a downsampled or truncated window `partial`, both persisted in `moment_sensor`.

**Parity claim, stated precisely.** Live ticks are `postDelayed(1000)` and drift. The Moment features are **canonical-window features** (windows aligned to phone seconds), not byte-exact replicas of live ticks; no test asserts equality with live per-tick output. Golden tests use fixed, independently derived expectations (hand-computed or produced once by a reviewed reference and checked in), never two runs of the same possibly mutated classifier.

**Canonical phone-time windows.** Feature reset/emission boundaries are defined in phone time: for each canonical second `S`, the builder uses the trace `ClockMap` to compute the elapsed instants `e0 = elapsed(S*1000)` and `e1 = elapsed((S+1)*1000)` and resets/emits the classifier at those elapsed boundaries (not at elapsed multiples of 1000). With a +500 ms offset, elapsed `[1000,2000)` is therefore split across two phone seconds correctly. Rules:
- Exact-boundary ordering: a sample at `e1` belongs to the next window (half-open `[e0, e1)`).
- A trace's first and last windows are `partial` unless aligned; a gap longer than 1 sample interval inside a window lowers `nGyro/nAcc` and is flagged; windows with no samples produce no row (absence = unknown, not still).
- Overlapping traces (two traces covering one second) each produce rows; arbitration is the 3.2 rule.
- `ClockMap` returning null for the window start or end, or uncertainty beyond limits, yields no row (never a guess).

**How a trace becomes SENSOR parts** (per trace import or revision change, idempotent on `(traceId, revision)`):
1. Read the verified trace (`StoredReplayTraces`) and its `ClockMap`.
2. Walk events in append order feeding `sMs`; split windows per above; emit `(momentSecond, SecondFeatures, state, nGyro, nAcc, warmup, partial)`.
3. Upsert `moment_sensor` and the SENSOR part only for seconds that have a stored Moment; others are produced on demand (3.4). Replace the trace's earlier-revision rows in one transaction (`DELETE ... WHERE traceId=? AND revision<?`).
4. Non-sensor events (`ep`, `gt`, `cx`) are not copied; the Moment may reference the trace so a consumer opens the raw trace.
5. **Each SENSOR part is a trace-source dependency (section 8.3b)**: the builder writes `moment_trace_dep(momentId, traceId, revision)` in the same transaction.

**Not replay.** One-second features cannot reproduce 20 ms timing; `ReplayRule` and replay keep reading raw traces.

## 5. Scene

- `scene(sceneId TEXT PK, kind, createdAt, buildRev, membershipRev INT, membershipDigest TEXT, labelsJson)` and `scene_member(sceneId, momentId, ord)` PK `(sceneId, momentId)`, index `momentId`. A Scene is an ordered set of Moment ids; it has no photos of its own.
- **Identity (Q3 decided): stable id plus membership revision.** `sceneId` = `s` + hex of the first member's `momentId` at creation + `-` + 8 hex random (collision-checked on insert, retried). The id never changes when membership changes. `membershipRev` increments on every membership change; `membershipDigest` is a full SHA-256 over the ordered member ids (never truncated) and is stored for equality checks and rebuild verification. A rebuild that reproduces the same membership keeps the id (matched by digest); different membership gets a new revision of the same id when it is an evolution of an existing Scene (overlap rule in `SceneBuilder`), else a new id.
- Creation (phase 4): `SceneBuilder` over existing Moments; first version a time-gap grouper. Derived read model, rebuildable.
- Scene artifacts carry the full root set (8.3a) and bind `(sceneId, membershipRev)`.
- **Deletion effect:** deleting a Moment removes its `scene_member` rows, bumps `membershipRev` and `buildRev`, recomputes `membershipDigest`, and **invalidates** every Scene artifact bound to an earlier `membershipRev` (hidden, vectors dropped, scheduled for rebuild from surviving members). The Scene survives while it has a member; with zero members it and its artifacts are deleted.

## 6. ItemRef, ItemType and the resolver registry

### 6.1 Shape
```
open registry:  ItemType(id: String)   // "frame" (legacy), "moment" (first); later "scene","prompt","classifier","intent","embedding"
data class ItemRef(val itemId: String, val itemType: String)
data class CanvasItem(val itemId: String, val value: CanvasValue, val itemType: String)   // replaces (frameId, photo, value)
interface ItemResolver<T> { val type: String; suspend fun resolve(ids: List<String>): List<Resolved<T>>; suspend fun missing(ids): Set<String> }
object ItemResolvers { fun register(r: ItemResolver<*>); fun of(type: String): ItemResolver<*>? }   // unknown type = typed failure
```
- `momentObj` resolves a `momentId` to `MomentObj { id, startMs, frames: List<ExtractPhoto>, sensor: SecondFeatures?, memories, marks, cites, contentRev, contentHash }`. `frame` resolves via today's `PhotoSource.load`.
- `NodeExecutor.run(node, items, ctx)` keeps its signature; executors read `item.itemType` and ask the registry. `NodeResult.frameId` becomes `itemId` in phase 2.
- **Multi-frame executor and cost contract (phase 2).**
  - Frame order: ascending `partTimeMs`, ties by frame id. Per-Moment cap `MAX_FRAMES_PER_MOMENT` (default 4; extra frames are listed in lineage but not sent, flagged `truncated`).
  - Vision node processes a Moment as **one call with an ordered image batch** limited by `MAX_BATCH_BYTES` and the helper payload limit; if over the limit, it sends the first frames that fit and records the rest as `skipped`. Exactly **one `NodeResult` per Moment**, with `extracted` aggregated over the frames, `lineage = [frameIds used]`, and call-owner/token accounting charged once to the run with the per-frame image count recorded.
  - Partial failure: a failed frame call fails the Moment's result (typed `PARTIAL_FAILURE` with the surviving frames' results attached) rather than silently dropping frames.
  - **Estimate** (`nominalCalls`/`maxCalls`) is computed from the **resolved frame count**, not the item count; the run is refused at estimate time when resolved frames exceed `MAX_PHOTOS`-equivalent call/payload budgets. 50 Moments may exceed the 50-photo budget; the estimate token shows that.
  - **Photo-free Moments:** vision nodes return a typed refusal `NO_PHOTO` for the Moment (counted, not a crash); non-vision nodes (e.g. Python over sensor/text) accept it. The validator refuses a graph whose only consumer is a vision node over a selection with zero photos.
- Graph input: `input.photos` stays valid (type `frame`); `input.moments` yields `moment`. `PipelineValidator` rule becomes "exactly one input root of the ASYNC half (`input.photos`, `input.moments`, or the 3b `input.traceItems`)", mixed types refused. Existing saved pipelines validate unchanged.

### 6.2 Compatibility and key encoding
Post-3b starting state (the recommended path) already contains `input.traceItems` graphs, trace result-store reads (two tables), legacy `canvas_run_item` rows that include `ti<N>` status rows, and A1 behaviour (no scores/notes on trace runs). All of these keep working through an explicit compatibility path:
- Legacy row classification uses the **authoritative run kind** (`canvas_run`), not a blanket default: photo runs -> `frame`; trace-item runs -> `traceItem` (a registered legacy type whose resolver is the 3b `StoredTraceItemSource`); only rows of runs of kind photo get `itemType='frame'`. Trace result reads, status rows, A1 refusals and the 3b result store are untouched until phase 3 backfill, and `input.traceItems` stays a valid root.
- Frame ids: `frameIdOf` uses stored filenames and `LegacyImporter` (`services/.../LegacyImporter.kt:47-53`) accepts imported ids without a format restriction, so **no format-disjointness is assumed**. Existing keys stay intact (`frameId` column holds legacy ids verbatim). **Every new type uses an unambiguous encoded key** `"<type>:<id>"` (e.g. `moment:5f3a1b2c`) in the `frameId` column and in `derived_from`; there is no conditional "namespace only on collision" rule. Legacy `frame` ids are never prefixed and never parsed as typed keys (a legacy id containing `:` is still read through the legacy path because the column `itemType` decides, not the string).
- Additive migration: `itemType TEXT NOT NULL DEFAULT 'frame'` on `canvas_run_item`, `canvas_score`; the migration also sets `traceItem` for rows whose run is a trace-item run (a one-statement UPDATE joined to `canvas_run`). The column `frameId` keeps its name (renaming a PK column is a table rebuild); Kotlin exposes `itemId` via `@ColumnInfo(name = "frameId")`. PK stays `(runId, frameId, nodeId)`.
- Old rows remain viewable; where a legacy frame has a Moment, edges are backfilled in phase 3 (additional migration work, not subtracted work).

### 6.3 Migration numbers
Numbers are **reserved at merge time**, not now: whichever slice merges takes the next free version. At time of writing 3b WP3 plans v8 and 3c is written as v9, but the Moment phases do not claim v9/v10; each phase's migration is numbered by the merger, ships its `N.json`, a `MigrationNtoM` test and both factory lists (as WP3 does with `MIGRATION_7_8`). The 3b plan text "3c takes v9" is edited when the first Moment phase is scheduled. Frames with unknown capture time and traces without a clock map keep working with no Moment.

## 7. Cross-device clocks

7.1 **Canonical second = phone wall clock** (user types ranges, Forget runs, `canvas_label.atMs` live there). Accurate per-path evidence:
- **Frame, receipt-backed:** `FrameIngestor.kt:155` passes `Admission.Ingest.capturedAtPhoneMs` (already offset-corrected); `ReceiptStore` stamps the file mtime from it; `RokidFrameInbox.kt:294-305` writes that mtime into BOTH `FrameEntity.ts` and `frame_meta.capturedAtMs`. So for receipt frames `capturedAtMs` is an offset-adjusted phone-clock capture time and is **not** an independent source; quality `OFFSET_ESTIMATED` with the intake offset uncertainty.
- **Frame, no receipt:** `capturedAtMs` is null; `FrameEntity.ts` is an arbitrary file mtime (`RokidFrameInbox.kt:305`) and `frame_meta.createdAt` records intake time only. No per-frame intake `phoneOffsetMs` is stored in `FrameMetaEntity`. **Unknown capture evidence creates no Moment.** Receipt-time placement requires a verified receipt instant and a demonstrated delay bound; otherwise uncertainty is unbounded and the frame is treated as unbounded in Forget (7.3), never given a default bound. The wire-id wall time (`g<ms>_`) is used only with persisted mapping evidence, because `OutboundBuffer.sendTimeMs` (`OutboundBuffer.kt:120-122`) can differ from the original wall time after a clock correction. Phase 1 persists, **in the source stores independently of the projection** (columns on `frame_meta`, `canvas_label`, `canvas_trace`), each source's `clockQuality`, `uncertaintyMs` and evidence kind; legacy rows lacking evidence are backfilled as `UNKNOWN` (unbounded), i.e. conservative.
- **Q5 resolved:** `frame.ts = file.lastModified()` is a capture time on the phone clock for receipt-backed frames (mtime stamped by `ReceiptStore`) and a receive time otherwise; `capturedAtMs` is null for non-receipt frames and equals the same mtime for receipt frames. Builder trusts `capturedAtMs` when non-null and treats `ts` as `RECEIVE_TIME` otherwise.
- **Sensor:** `ClockMap.phoneWall(elapsed, bootId)`; null means no row. Each trace carries `offsetUncertaintyMs` and phone-sample age; the uncertainty never falls below `ClockMap.uncertaintyMs`.
- **Mark:** marks can use the receipt time (`Hub.kt:464`, `MarkRecorder.kt:76`) rather than a mapped capture time. Preserve **mapped-versus-receipt quality** per mark, persisted on `canvas_label` (not only in `moment_part`): `EXACT`/`OFFSET_ESTIMATED` when mapped via `atElapsedMs + bootCount` against a trace `ClockMap`; `RECEIVE_TIME` only with a verified receipt instant and demonstrated delay bound, else `UNKNOWN` (unbounded). `atElapsedMs + bootCount` is the join key to a trace (`MarkMessage`, `MarkOutbox`).
- **Watch / LiveFit:** the repo has a LiveFit integration, `WatchSource` and `WatchMotionIntake` (`phone/src/main/.../context/`, tests `WatchSourceTest`, `WatchMotionIntakeTest`; settings in `ContextSourcesSettings.kt`). It is not a watch module in the old sense, but it **is** an existing data path. Contract (8.6): nothing from the watch clock is keyed into Moments in MOMENT-first; any watch-derived data that later becomes a part must use phone-receipt time with receive-delay uncertainty; the cleanup contract in 8.6 lists what must be deletable. Whether LiveFit itself supports deleting remotely retained data is **unverified** and is a phase 1 task 1 research item.

7.2 **Known defect and its fix (required, phase 1, separate commit).** `TraceImport.kt:87` writes glasses wall into `canvas_trace.startedAtMs/endedAtMs`, compared by `forgetRangeTx` (`CanvasSlice3Dao.kt:49`) with a phone-clock range. Fix: add columns `phoneStartMs`, `phoneEndMs`, `clockQuality`, `uncertaintyMs` to `canvas_trace` (migration), populate at intake via `ClockMap` (fallback: glasses wall plus `offsetUncertaintyMs`, quality `UNMAPPED`), **backfill existing rows** from their stored manifests (rows with no usable clock get `UNMAPPED`, treated as unbounded in overlap), and update both import admission (refuse by the corrected interval) and the Forget overlap check to interval overlap (7.3).

7.3 **Uncertainty handling by interval overlap.** A part is selected by a range `[from, to]` when `partTime - uncertainty <= to && partTime + uncertainty >= from`, evaluated over **all** parts/traces (not only already-selected candidates), so an adjacent part whose interval overlaps is included. The uncertainty is **not capped** (the old 5 s cap under-deletes: `ClockMap` incorporates sample ages up to 120 s). **Unknown or unbounded mapping** (`UNMAPPED`, null map, no clock-quality) is treated conservatively: the part is selected whenever its coarse bounds (receive time, trace file times, bootId window) could overlap, and glasses wall times are never compared to phone ranges directly. If coarse bounds are unknown, the source is **conservatively included** in every range Forget (and in any Forget whose time scope is not provably disjoint). There is no confirmation fallback; if inclusion is judged too destructive for a product reason, the alternative is that Forget is reported **incomplete** and payload release for dependent runs is blocked until resolved. Default: conservative inclusion. Rounding is outward.

7.4 **Second boundaries.**
- A frame at 12:00:00.999 and one at 12:00:01.000 are two Moments. A mark belongs to its own second; the "10 s before" window semantic stays in the analysis layer.
- A trace spanning a boundary contributes one SENSOR part per second (4, canonical windows).
- Uncertainty larger than 500 ms may put a part one second off; handled by delete widening, not by multi-second membership.
- Clock jump (`ClockMap.phoneJump`/`wallJump`): Moments keep ids; affected parts get `SUSPECT` (aggregated by 3.2); rebuild uses the corrected map and may move SENSOR parts; frames keep their stored capture time.

## 8. Deletion and provenance

### 8.1 Entry points and source discovery
`MomentForget.forgetMoments(ids)` and `forgetRange(fromMs, toMs)`. **Discovery is independent of the projection.** Inside the transaction the set of affected sources is the union of:
(a) projection references (`moment_part` of the Moments in range);
(b) authoritative-store scans by canonical occurrence time and relationships: `frame` rows by `capturedAtMs`/`ts`/`frame_meta` times with the 7.3 overlap rule; `memory` by `createdAt` and by `frameId` in the frame set plus memory descendants; `canvas_label` by canonical `atMs`; `canvas_trace` by interval overlap (7.2/7.3); asks/nudges citing frames in the set;
(c) `moment_trace_dep`, `derived_from` closures (8.3).
(d) **durable inbox/archive receipts discovered independently of frame rows** (receipts can exist before their frame row commits; scan receipt stores by their stamped time and wire id, and the pending JPEG/sidecar files).
Requested seconds are **tombstoned even when no Moment exists** (8.2). A source that commits before its asynchronous projection, or is missed by a defective builder, is still found by (b). Existing `forgetRangeTx` only handles traces, labels and their dependants (`CanvasSlice3Dao.kt:47-55`); (b) adds frames/memories discovery to it. Tests (phase 1, since this is the first writer): `ForgetWithEmptyProjectionTest`, `ForgetWithStaleProjectionTest`, `ForgetSourceArrivingConcurrentlyTest`.
Existing entry points (`ForgetService.forgetMoment(frameId)`, `ForgetActions`, `CanvasForgetCoordinator.forgetRange`) stay and delegate in phase 3.

### 8.2 Tombstones and fences
- **Late source admission is fenced.** Today `FrameIntake.commit` (`FrameIntake.kt:19-29`) inserts with no range check, and a durable receipt can precede its frame row. In phase 1: frame admission, receipt publication and frame commit each reject sources overlapping a persisted Forget fence (using 7.3 overlap, unknown evidence treated as overlapping a fence from the same session), with the check **re-done inside the commit transaction** (`withTransaction` reading the fence). A rejected source's JPEG and sidecar are removed through a durable cleanup record (`cleanup_outbox` target `PHONE_FILE`), not best-effort. Tests: `ForgetBetweenReceiptAndFrameCommitTest` (including process restart between the two), `FrameCommitRejectedByFenceTest`, `RejectedJpegCleanupSurvivesCrashTest`.
- `moment_tombstone(momentId PK, createdAt)` for every requested second (also when no Moment row exists), parallel to `forget_tombstone(frameId)`. Range deletes persist the **outward-rounded full-second fence** via `addForgetRange` (`canvas_meta forgetRanges`) FIRST, so a mark, trace import, late chunk or builder run is refused whatever the arrival order. The builder checks both before creating or extending a Moment.
- Frame tombstone and `moment_tombstone` are inserted in one transaction.

### 8.3 Dependency closure
**(a) Closure rule: transactional inheritance of the full root set.** Every derived row carries edges `derived_from(derivedType, derivedId, rootKey)` where `rootKey` is a Moment id **or** a legacy frame/trace key. A derivation of a derived thing **must insert the union of all roots of all its inputs** in the same transaction as the row (including shared model-call batches: a batch output inherits the roots of every item in the batch, and per-item outputs inherit their own item's roots plus the batch-level shared roots). A writer that cannot enumerate its roots must not write (`DerivedWriter` is the only insert API; the scan test fails any direct insert). This gives a flat closure with one indexed lookup `WHERE rootKey IN M`; memory-to-memory derivation additionally follows `memory_source` as today.
Table `derived_from(derivedType TEXT, derivedId TEXT, rootKey TEXT)`, PK all three, index `rootKey`. Types: `MEMORY`, `RUN_ITEM` (`runId|encodedKey|nodeId`), `SCORE`, `LIVE_TRACE`, `REPLAY`, `SWEEP`, `HELPER_JOB`, `HELPER_RESULT`, `SCENE_ARTIFACT`, `EXPORT`, `ASK`, `NUDGE`, `CLASSIFIER_TRAINED`, `EMBEDDING_LEARNED`, `CALIBRATION`.

**(b) Trace-source dependencies kept beside Moment roots.** `moment_trace_dep(momentId, traceId, revision)` plus `derived_from` rows keyed `trace:<traceId>@<revision>` for every output computed from trace data. Whole-trace deletion is real (`CanvasSlice3Dao.forgetRangeTx:49-50` tombstones an entire overlapping trace), so forgetting Moment A also removes the trace supplying Moment B. **Every trace tombstone, reclaim, retention expiry, direct deletion or revision change** (not only Moment Forget) must, in one transaction: delete the trace's SENSOR parts/`moment_sensor` rows for all Moments (B included), invalidate/delete dependent outputs via `rootKey='trace:...'`, void helper jobs, and enqueue cleanup (8.6). Captured trace revision, tombstone state and persisted epoch are checked at payload release and at result commit. **3b's existing source checks (trace revision/tombstone/chunk-sha at estimate/start/commit) are preserved**, not replaced by Moment tombstones.

**(c) Per-type policy (exhaustive).**
| type | on source deleted/changed |
|---|---|
| RUN_ITEM, SCORE, LIVE_TRACE, REPLAY, SWEEP, HELPER_RESULT | DELETE (a result read private data) |
| HELPER_JOB | void + cancel; enqueue laptop cleanup |
| MEMORY (derived), SCENE_ARTIFACT, ASK, NUDGE | INVALIDATE (hide, drop vectors, redact), rebuild from surviving inputs |
| EXPORT | delete app-owned copy; copies already handed out cannot be recalled (UI states it) |
| CLASSIFIER_TRAINED | disable immediately, delete the model, **rebuild without forgotten inputs** or leave disabled; dependents (scores, runs, deployed copies on glasses) follow via the same rootKeys |
| EMBEDDING_LEARNED, CALIBRATION | delete and rebuild without forgotten inputs; stale vectors dropped; dependents follow |
| Prompt, intent (user-authored config) | KEPT; contain no world data; a prompt that embedded world data is not allowed (writer refuses) |
**Immutable pretrained/config artifacts** (shipped models, thresholds, fixed prompts) contain no user data, are identified by `artifactKind='PRETRAINED'` and a content hash, and are never targets of the cascade. A trained or fine-tuned artifact is always `CLASSIFIER_TRAINED`/`EMBEDDING_LEARNED`; "config" is not an exemption.

### 8.4 Cascade (one transaction, idempotent)
```
M := requested seconds (by id or outward-rounded range)
BEGIN IMMEDIATE
 1 addForgetRange(fence)  [range]; INSERT OR IGNORE moment_tombstone for every second in M (no Moment needed)
 2 sources S := union (8.1 a,b,c): projection parts, authoritative-store scans, deps
 3 traceSet := traces overlapping M (7.3) -> 8.3b for each (removes other Moments' sensor parts, outputs, jobs)
 4 D := derived_from WHERE rootKey IN (M, S, trace:*)   + memory descendants via memory_source
 5 apply per-type policy (8.3c): delete / invalidate / disable-and-rebuild
 6 source rows: tombstone + delete frames, memories, labels, trace tombstone (existing code paths called, not copied)
 7 scene_member removal -> membershipRev++ and artifact invalidation
 8 delete moment_part/moment_sensor/moment_trace_dep/moment rows; delete derived_from rows whose derived thing was deleted
 9 INSERT cleanup_outbox rows (8.6) for every file, wire frame id, trace id, mapped range, queued mark, helper stage and export copy
COMMIT
```
All `IN (...)` chunked at `CHUNK`. Idempotent (DELETE/UPDATE by key, INSERT OR IGNORE). After commit the outbox drainer does the I/O (8.6).

### 8.5 Races
Same pattern as `fenceHolds` (`CanvasDao.kt:120`): every derived write is `@Transaction`, takes `(generation, rootSet, capturedRevisions)`, aborts when the wipe generation moved, or a `moment_tombstone`/forget range covers any root, or a captured trace revision/tombstone no longer holds; then inserts the row **and** its full root set. Long-running producers hold no transaction and re-check at commit and at payload release. The builder uses the fence so a rebuild cannot resurrect a deleted Moment.

### 8.6 Other devices: durable outbox
After-commit `ForgetListeners` are in-memory and swallow failures, `FrameSweep` only retries phone JPEG removal, and `CanvasForgetCoordinator.trySend` (`:68-69`) does not persist an unsent range. So cleanup is **durable**: the deletion transaction inserts `cleanup_outbox(id, target, payloadJson, createdAt, ackedAt)` rows (paths, wire frame ids, trace ids, mapped glasses ranges retained until acknowledged), recovered on app start and on link reconnect, idempotent on the receiver, deleted on ack. Targets and contract:
- **Glasses** `OutboundBuffer` (frames by wire id; verify delete-by-id for un-acked frames, phase 1 task), trace files under `imu/traces` and `trace_forget`, **queued marks** (`MarkOutbox.onEpoch` clears marks only on epoch change, not on range Forget; a range-Forget message that purges queued marks in range is added), `onFramesForgotten` currently only sweeps phone trace folders (extended).
- **Laptop helper**: 3b's cleanup journal/ack lifecycle (`canvas_helper_cleanup`, 7-day retention, staged inputs under `$HOME/.pca-helper/stage/<job>/`) is retained and fed by the outbox; Moment-keyed jobs carry `momentIds` and trace deps.
- **LiveFit / watch**: inventory `WatchSource`, `WatchMotionIntake` (phone side). Contract: list every field they persist (raw motion, derived windows, caches); phone-side copies are deleted by the cascade; any remotely retained data must have an explicit delete request in the outbox, or be documented as not retained. Current LiveFit deletion capability: **unverified**.
- **Exports**: `export_manifest` lists Moment ids; app-owned copies deleted; handed-out copies cannot be recalled (UI line).
- **Backups**: backup exclusion operates on the whole database and files, not individual tables. The claim "moment tables are excluded" is replaced by: the Room database and trace/frame/export directories are already excluded as whole units (backup rules); a test asserts the new files/databases are not added to backup includes.

### 8.7 Inventory, WipeDao and retention
- New tables `moment`, `moment_part`, `moment_sensor`, `moment_trace_dep`, `derived_from`, `moment_tombstone`, `cleanup_outbox`, `export_manifest`, `scene`, `scene_member`. `DeletionGraphInventoryTest` currently scans only `canvas_*` (`:14,38`), so registering them fails its stale-entry/equality checks. Phase 1 **extends the inventory discovery regexes to every new table name** and the `WipeDao` assertion; `WipeDao` has explicit `DELETE FROM` statements and the registry does not execute them, so each new table gets an actual `WipeDao` DELETE, and a test asserts each wiped table is emptied by Delete all. Auxiliary stores (export manifests, outbox files) are covered by the same test.
- `moment_tombstone` is WIPED, the epoch/generation fence doing that job (as `fenceHolds`). **Unacknowledged cleanup survives Delete all until acknowledged.** The wipe atomically preserves or transfers outstanding cleanup (`cleanup_outbox` and the retained 3b WP4 `canvas_helper_cleanup`, which that plan already keeps through Delete all) and adds its own commands before deleting source references; `cleanup_outbox` is therefore KEPT-with-reason (payload reduced to ids/paths/ranges, no user content) and only acked rows are removed. `DeletionGraphInventoryTest` and the `WipeDao` assertions list it as KEPT and assert it is not deleted while unacked rows exist. Tests: `WipePreservesUnackedCleanupTest`, `CrashBeforeDispatchRecoveryTest`, `DisconnectedDeviceRecoveryTest`.
- Retention: photo expiry (`expirePhotos`, `ForgetService.kt:107`) removes the `frame` row but keeps memories; the Moment keeps the FRAME ref flagged `expired`. Memory expiry removes its MEMORY part. A Moment with no non-sensor part is deleted by the builder. Sensor parts and `moment_trace_dep` expire with the trace (8.3b).

## 9. How Python experiments (3b) consume Moments

**Current 3b direction (changed 2026-10-23, Option 1).** In-app Python runs over **photos/frames only**; sensors are handled by a **laptop-side `tools/trace-lab`**. The Moment slice therefore **later adds in-app sensor inputs** (the `sensor` field below) on top of 3b; it does not replace an in-app trace-item path that no longer exists in that plan. Where the 3b plan still carries trace-item items/result-store text, read it as superseded by Option 1 and reconcile when the Moment slice is scheduled.

**Input.** A run selects Moments (`momentIds`, bounded by the 6.1 resolved-frame budget, or a time window expanded to resolved Moments including ephemeral sensor-only ones). The `py_exec` payload per item: `{ itemType:"moment", momentId, contentRev, contentHash, startMs, photos:[{frameId, jpegRef}], sensor:{gyroMax,gyroMean,gyroRms,aboveOnFrac,accVar,stepHz,rotRad,state,warmup,partial}|null }`. Helper contract unchanged except for the item shape and a schema-lite sample for `sensor` in the WP1 fixtures.

**Freshness binding (identity is not freshness).** `buildRev` is the builder code version and says nothing about input freshness; a late frame, corrected mark or changed membership changes the payload without changing selected ids. The estimate/start token binds `(input kind, graph hash, sorted selected momentIds, each Moment's contentRev + contentHash, captured trace revisions)`; start **re-resolves** and refuses if any value moved; the captured provenance is carried through confirmation, payload release and result commit. `derived_from` is deletion lineage, not a substitute for verified source identity and freshness.

**Recommendation: finish 3b first.** The earlier idea of folding the Moment work into 3b to save hours is **marked unavailable until prerequisites exist**: ItemRef storage arrives in Moment phase 2, deletion edges in phase 1/2/3 with their own protection, the legacy/photo-only protection (`frame_forget_rev`) must stay, the trace result store is two tables (not one), and removing raw trace-item input changes the data Python sees, not only an adapter. Under Option 1 most of those 3b trace pieces are already out of the 3b scope. After 3b ships, Moment consolidation and edge backfill are **additional migration work**, not subtracted work. If the early-integration alternative is ever revisited, it requires an explicit enumeration across WP1/WP3/WP4/WP8a/WP8b/WP9, protocol fixtures, confirmation, route/read/delete/prune paths and B2 acceptance, while preserving real-chunk verification and legacy trace protections.

**New work that appears:** MomentStore and builder (phase 1), `MomentResolver`, `input.moments`, executor contract (phase 2), moment-keyed estimate token, the `canvas_trace` clock fix and backfill (7.2), in-app sensor input after Option 1.

## 10. Phased rollout (agent-hours: Sonnet implementers; independent review by Codex; product decisions by the user)

**Rule: no phase materialises derived data without its own deletion protection.** Each phase that introduces a writer ships, in the same phase: transactional fences, source-to-projection cleanup, Delete-all integration and required provenance/root sets, and Forget/racing-write tests. Phase 3 only unifies entry points and backfills legacy rows. If a phase cannot ship its protection, **activation (feature flag on) is blocked** until it exists.

| Phase | Content | Est. agent-h (provisional) | Depends on |
|---|---|---|---|
| 0 | This spec; Codex review rounds; user answers open questions; edit 3b plan line "3c takes v9" | 1-2 | none |
| 1 | `MomentId`; Moment/part/sensor/`moment_trace_dep`/tombstone/outbox tables (version reserved at merge); builder; `TraceSecondFeatures` canonical windows; clock-quality rules; `canvas_trace` clock columns + backfill + overlap fix; independent source discovery; fences; trace-dependency removal; durable `cleanup_outbox` and glasses/trace/marks/laptop/LiveFit contract; inventory + `WipeDao`; Forget/racing/empty-projection tests | 22-32 | 0 |
| 2 | `ItemRef`/`CanvasItem` refactor, registry, compatibility path for trace graphs/legacy rows, encoded keys, `input.moments`, executor/cost contract, photo-free behaviour, content revision binding, `DerivedWriter` + full root inheritance for run items/scores, per-type policy incl. trained artifacts, `itemType` migration, protection and race tests for outputs | 17-28 | 1 |
| 3 | Unify entry points (`ForgetService` delegation), edge backfill for legacy rows, remaining derived writers (summaries, replay/sweep, exports), outbox consolidation | 9-15 | 1, 2 |
| 4 | Scenes: tables, `SceneBuilder`, identity + membershipRev, artifact edges, `sceneObj`, delete effect, protection tests | 7-12 | 1, 3 |

Corrected total: **55-87 agent-h excluding phase 0, 56-89 including it** (22+17+9+7=55, 32+28+15+12=87). These ranges are **provisional pending a task breakdown** covering compatibility, clock backfill, durable device cleanup and every derived writer; the writer/device inventory is not yet complete (first task of phases 1 and 2: grep call sites of `insertRunItemsFenced`, `upsertScoreFenced`, summariser/replay/sweep inserts, helper results, and list every device store). Confidence is low-to-medium (about 50 % within the range).

**Release ordering.** None of these phases is a v1 release blocker. The v1 blockers (always-on logging, ANR recovery fix, replay fix, M1) land first; phase 1 adds code in new files and reads `MotionClassifier` without editing it. Recommendation: phase 0 now; finish 3b core as planned; run phases 1-4 afterwards.

## 11. Risks and open questions (for the USER, with defaults)

Risks:
1. **Cascade drift:** a future derived writer skips its root set. Mitigation: `DerivedWriter` is the only insert API plus a DAO-scan test and the inventory pattern.
2. **Dual truth:** the projection diverges from sources. Mitigation: rebuildable; Forget discovers from authoritative stores (8.1).
3. **Clock wrongness** misplaces a part: store quality/uncertainty, widen by interval overlap, fix `TraceImport.kt:87`.
4. Refactor breadth in `CanvasItem` (phase 2); `frame`/`traceItem` adapters and compiler-guided rename.
5. Schedule: 55-87 h is more than the earlier estimate; unscheduled until after 3b.
6. Device cleanup reliability depends on the glasses/LiveFit capabilities that are unverified.

Open questions:
- Q1: Insert phase 1 before 3b freezes v8? Default **no**; savings from early integration are unavailable until prerequisites (9).
- Q2: 8 or 10 hex digits? Default 8.
- Q3: **Decided** in 5: stable id plus membership revision and full digest.
- Q4: **Decided** in 3.4: sensor-only seconds are ephemeral.
- Q5: **Resolved** in 7.1.
- Q6: widening cap: **removed**; interval overlap with no cap (7.3). Keep the 10 s `PHOTO_LINK_MS` for marks/traces around a photo forget (default).
- Q7: Lost Scene member invalidates artifacts (default).
- Q8: User-visible warning for handed-out exports (default yes).

## 12. Test strategy (RED tests first, mutation check per phase)

Every phase that materialises derived data includes its Forget/racing-write and inventory tests (section 10 rule).

Phase 1
- RED: `MomentIdTest`; `MomentBuilderTest` (two frames/one second, 1 s apart gives two, marks/memories attach); `MomentRebuildEqualityTest`; `TraceSecondFeaturesGoldenTest` with a real trimmed chunk and **fixed independent expected values**, feeding `sMs` in append order (test with reordered `sMs` and batched delivery); `CanonicalWindowTest` (+500 ms offset splits an elapsed bucket across two phone seconds; exact-boundary ordering; partial windows; gaps; overlapping traces and arbitration); `MomentClockTest` (null map, wall jump, 00.999 vs 01.000, SUSPECT aggregation); `ClockPathTest` (receipt frame, non-receipt frame, mark mapped vs receipt); `TraceImportPhoneClockTest` + backfill test (3 s offset, long sample age up to 120 s, UNMAPPED treated conservatively); `IntervalOverlapSelectionTest` (adjacent part overlaps); `ForgetWithEmptyProjectionTest`, `ForgetWithStaleProjectionTest`, `ForgetSourceArrivingConcurrentlyTest`; `TombstoneWithoutMomentTest`; `TraceTombstoneRemovesOtherMomentsSensorTest`; `CleanupOutboxRecoveryTest` (restart/reconnect before ack); `Migration*Test`; `DeletionGraphInventoryTest` extended; `WipeDaoNewTablesTest` (outbox excepted, as above); `ImportBeforeFrameAttachesSensorTest`; `SensorOnlyWindowEphemeralTest`.
- Mutation: `/ 1000` to `/ 1001`; pass `tMs` instead of `sMs`: golden fails; drop interval-overlap uncertainty: overlap test fails; remove projection union in discovery: empty-projection test fails; drop outbox insert: recovery test fails.

Phase 2
- RED: `CanvasItemRefTest` (existing runner tests unchanged); `ResolverRegistryTest`; `PipelineValidatorInputRootTest` (photos/moments/traceItems roots; mixed refused); `MigrationLegacyKindTest` (photo runs `frame`, trace runs `traceItem`, status rows and A1 intact); `EncodedKeyTest` (legacy ids containing `:` stay legacy); `MultiFrameExecutorTest` (ordering, byte limit, partial failure, one result, lineage, estimate from resolved frames); `PhotoFreeMomentTest` (typed refusal); `ContentRevisionBindingTest` (late frame/mark/membership change refuses at start, carried to commit); `RootInheritanceTest` (derived-of-derived and batch outputs carry the full root set); `TrainedArtifactCascadeTest`; `ForgetRacingDerivedWriteTest` (both orders, real `withTransaction`); `ImmutablePretrainedUntouchedTest`.
- Mutation: omit one root in inheritance; default `itemType` to `moment`; accept two roots; drop adapter; ignore `contentHash` at start.

Phase 3
- RED: `MomentCascadeTest` (all types), `MomentRangeForgetTest`, `CascadeIdempotentTest`, `EdgeBackfillTest`, `NoDerivedWithoutEdgeTest`, delegation tests for `ForgetService`/`CanvasForgetCoordinator`, glasses/laptop/LiveFit outbox contract tests.
- Mutation: omit an edge insert; remove tombstone check; widen by 0.

Phase 4
- RED: `SceneMembershipTest`, `SceneLosesMemberTest` (survives, `membershipRev` moves, artifact invalidated), `SceneEmptyDeletedTest`, `SceneIdStableAcrossMembershipChangeTest`, `SceneDigestCollisionTest`, `SceneRebuildEqualityTest`.
- Mutation: delete the Scene on first member loss.

Always: existing `ForgetServiceTest`, `CanvasSlice3`/`DeletionGraph*` suites and 3b WP4 helper suites stay green at every phase; each phase adds its RED group first and ends with a named mutation check (3b plan section 4.1 convention). Reviews are done by Codex; the user decides product questions.
