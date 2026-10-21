# Wave 2: Scene Grouping (P4 + P6) and the Nomic Switch — Implementation Plan

> **Controller ruling (2026-10-21): Room version renumber.** The experiment canvas plan (`2026-10-21-experiment-canvas-mvp.md`) ships first and takes **Room v3** (its `canvas_*` tables). Everywhere below, read this plan's "v3" as **v4** and "v2 → v3" as **v3 → v4**: `Migration(3, 4)`, `Migration3To4Test`, and the device test v1 → v4. Why: the user's order puts the canvas before wave 2. Cost if wrong: renaming one migration.

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (or superpowers:executing-plans). Lean plan, per the user's standing preference: exact interfaces, values, named tests, not full code. **Implementers are Sonnet; reviews are Opus.** Steps use checkboxes.

**Goal:**
- **Part A (wave 2):** an image vector for every keyframe, semantic dedupe, salience, scene clusters with one AI call per cluster batch instead of one per photo, retention for each kind of data, describe-before-delete, and the skipped-photo 1-day rule (bug RET).
- **Part B (last):** the Nomic switch. `NomicDeviceRuntime.provider` gets wired, Nomic is bound as an image-capable embedder behind the existing "Use for Embeddings" / re-index / 7-day Roll back flow, and a device check runs the reference test and the photo 256-vs-768 test.

**Spec:** `docs/superpowers/specs/2026-10-15-keyframe-capture-image-memory-design.md`: §P4 (image index), §P6 (storage lifecycle), §F.2/§F.4/§F.5. Read the section named in each task first. **Where this plan's Rulings differ from the spec, the Rulings win** (they record changes made since wave 1). On anything else, the spec wins.

**Order of work (handoff §9):** the battery plan (glasses) and the experiment-canvas plan (phone) run first; this wave runs after them. Part A and Part B touch different modules, so Part B can run in parallel with Part A. It is listed last because its device check closes the wave.

## Rulings

Format: `Ruling: <what> — <why> — <cost if wrong>`.

1. Ruling: every non-salient, non-duplicate keyframe goes to a scene cluster, not only the backlog beyond 30 (spec §P2.1 "cluster mode for the excess" is superseded) — the user's goal is one AI call per scene, and wave 1's rate cap and shedding already show the per-photo path can't keep up (12–15 keyframes a minute against about 5 tokens a minute) — non-salient scenes get a coarser summary than a per-photo extraction; if recall suffers, tune `SceneTuning` (salience cut-off, drift) instead of reverting.
2. Ruling: with no image vectors (Nomic not bound, ORT unavailable, or phone thermal ≥ MODERATE) there is **no clustering**: frames route as in wave 1 (PENDING, per-photo calls, shedding). Spec §F.4 said "clusters close on motion and time only" — without vectors, a 5-minute time window would merge different scenes into one summary — no call savings until Nomic is bound (Part B).
3. Ruling: routing (vector → salience → dedupe → cluster) runs **before** any claim. The background claim queries skip frames that have a `frame_meta` row but are not yet routed (`routedAt IS NULL`); after 10 min an unrouted frame becomes claimable as in wave 1 (a safety valve). An Ask claims unrouted and `IN_CLUSTER` frames of the last 60 s — this keeps routing from racing the legacy backstop workers, and "what am I looking at" still works — if routing is broken, frames wait up to 10 min before the wave-1 path picks them up.
4. Ruling: in the background, routing batches until 8 frames are waiting or the oldest has waited 15 s; dwell frames route at once (spec §P2.5 said image vectors every 60 s in the background) — routing now decides the fast path, so a 60 s wait would delay nudges by 60 s with the phone screen off, the common case — slightly more CPU wake-ups; checked in the phone-heat device check.
5. Ruling: one CLUSTER_SUMMARY call carries ≤ 3 closed clusters and ≤ 6 photos: 3 representatives each for 1–2 clusters; for 3 clusters, 2 each (the medoid and the most different). A closed cluster waits ≤ 60 s for companions. CallClass EXTRACT — 9 photos per call (spec: 3 clusters × 3) means slow, large provider calls, and wave 1 found the schema caps at 3 captions per call; 6 balances latency against savings — less detail per summary for 3-cluster calls.
6. Ruling: the cluster's claim lives on `frame_cluster`. Room **v3** (additive `ALTER TABLE ADD COLUMN`s on `frame_cluster` and `frame_meta`) adds state, attempts and the claim columns. Member frames get a new `frame_state` value, `IN_CLUSTER` (a string; no schema change), and the commit's CAS runs on the cluster row (spec §C: "any further schema change needs its own recorded decision"; this is that decision) — per-frame claims on 100 members would make the CAS and recovery much more complex — a v3 migration ships, tested as v1→v2 was.
7. Ruling: two extra close rules are added to spec §P4.1: **gap** (> 2 min between consecutive keyframes) and **out of order** (a keyframe captured > 60 s before the cluster's last one, e.g. a buffered flight burst) — without them, a pause or a delayed burst joins unrelated scenes — some very short clusters.
8. Ruling: wave 1's backlog shedding stays (above 40 PENDING, keep the newest 20, dwell exempt) as a safety valve for the per-photo path. A shed frame with salience ≥ 0.5 goes to `CAPTION_QUEUED` instead of `NEAR_DUP`. Shed, semantic near-dup and non-representative cluster frames are "skipped photos" under the 1-day rule (fixes RET) — clustering removes most of the PENDING load, so shedding should rarely fire — none expected.
9. Ruling: caption-queue aging (spec §Captions) is done in the pipeline's pick order, not in CallBudget. After 10 min of waiting, a queued frame is picked ahead of non-dwell PENDING frames; after 30 min its call class becomes EXTRACT (ordinary tokens; EXTRACT never takes reserved nudge tokens) — CallBudget already ranks CAPTION_QUEUE below EXTRACT, so aging needs no budget change — none.
10. Ruling: a cluster that reaches FAILED_FINAL (5 attempts): its representatives → `FAILED_FINAL` (eligible for describe-before-delete), its other members → `NEAR_DUP` with lastError `cluster_failed` (skipped) — re-extracting 100 members one photo at a time would undo the savings — those members are never described.
11. Ruling: **photo expiry = tombstone + delete the `frame` row + delete the JPEG and sidecar** (through `FrameSweep.delete`, under WipeFence). `frame_meta`, `frame_vector`, `frame_state`, `frame_source` and memories are kept — wave 1's swap counts already define "photo deleted" as a `frame_vector` row with no `frame` row; the tombstone blocks re-extraction and lets the sweep retry a failed delete — UI that assumes `memory.frameId` has a row must show "Photo expired" (Task 8 checks this).
12. Ruling: caption memories (`type = "caption"`) and cluster summaries (`type = "cluster_summary"`) leave the SHORT/MEDIUM/LONG tier windows and get their own retention kinds (default forever). The spec's separate "text vector" kind is **not offered**: a text vector lives and dies with its memory — today captions are LONG (30 days), against the user's "captions saved long-term" — one knob fewer than the spec.
13. Ruling: the new photo deadlines count from `max(capture time, perKindSince)`, where `perKindSince` is stamped on the first retention run of this build — with a 7-day default, the first run would otherwise delete about 800 trip photos at once, and the user debugs captions against them — old photos live up to 7 more days after the upgrade.
14. Ruling: the existing "orphan frames older than `longDays`" retention pass skips frames that have a `frame_meta` row (wave-1+ keyframes follow the per-kind rules) — that pass calls `forgetMoment`, which deletes the vectors and meta that P6 keeps — none.
15. Ruling: image vectors are stored under the **embedding_space id** (one shared text + image space, as wave-1's swap and purge code expects), only for spaces whose embedder declares `Modality.IMAGE`. Image coverage for a swap is required **per space**, iff the building embedder declares IMAGE (replacing the global `requireImageCoverage` flag). A photo that fails to decode twice is marked `imageVectorSpace = "unembeddable"` and doesn't block a swap — no image vectors exist before Nomic is bound (see 2) — those photos are findable by text only.
16. Ruling: cluster-summary memories are never offered to the fast-path NudgeEvaluator (only salient/dwell frames are); the periodic backstop still sees them — non-salient by definition — a nudge about a non-salient scene comes ≤ 30 min late.
17. Ruling: the photo 256-vs-768 test runs **before** binding Nomic (handoff §10.5). The image dimension is a code constant, `NomicVision.STORED_DIMS = 768`, and is part of the space fingerprint — binding at 768 and then moving to 256 would force a second full re-index — the binding waits for one device session.
18. Ruling: live per-frame image embedding is **not** under the G6 bulk gate (charging or > 70 %); only backfill is. Live embedding obeys PhonePowerPolicy `IMAGE_VECTORS` — about 50 ms per keyframe is live work like extraction, and the G6 gate would turn routing off at 69 % battery — some phone battery; checked in the device check.

## Global Constraints

- **Modules:** `:services:memory` (Room, DAOs, retention settings), `:services:orchestration` (scene indexer, cluster calls, pipeline, retention and night workers), `:services:ai` (Nomic embedder, prompt key and contract), `:phone` (Settings, Diagnostics). **No glasses change; no protocol change**: PROTOCOL_VERSION stays 1 (additive only, and this wave adds nothing).
- **Room:** version 2 → **3** through `MIGRATION_2_3`, **additive only** (`ALTER TABLE … ADD COLUMN` plus `CREATE INDEX`). It is registered in **both** `DatabaseFactory.build()` and `buildDeferred()`, with no destructive fallback, and the schema is exported to `services/memory/schemas/…/3.json`. It is tested with `MigrationTestHelper` from v2 with real rows, through the production builders (plain and SQLCipher), and in `SqlCipherMigrationDeviceTest` (device, controller-run).
- **Frame states (strings in `frame_state.state`):** the existing PENDING, EXTRACTING, EXTRACTED, CLUSTERED, NEAR_DUP, FAILED, FAILED_FINAL, plus new **`IN_CLUSTER`** (member of an open or closed cluster, awaiting its summary) and **`CAPTION_QUEUED`** (a key-moment skipped photo, lowest priority). Add both to `enum class FrameState`.
- **Cluster states (`frame_cluster.state`):** OPEN, CLOSED, SUMMARIZING, SUMMARIZED, FAILED, FAILED_FINAL.
- **SceneTuning defaults** (one `data class`, injectable, so the canvas can override it): dedupe cosine ≥ **0.93** against the last **10 min**; novelty window **30 min**; watch-list cosine ≥ **0.25**; salience weights dwell **0.4**, text **0.3** (ML Kit Latin, ≥ **4** words), novelty **0.3 × (1 − maxCos)**, watch **0.3**, total capped at 1.0; salient ≥ **0.5**; cluster drift **> 0.25** from the centroid for **2** consecutive frames; close also on motion-class change, **100** members, **5 min** since open, gap **> 2 min**, out of order **> 60 s**; ≤ **3** representatives; one call carries ≤ **3** clusters and ≤ **6** photos; a closed cluster waits ≤ **60 s** for companions.
- **Claims (wave-1 rules, unchanged):** claim timeout 15 min; claims are released at process start; 5 attempts → FAILED_FINAL; each commit is one transaction that starts with the CAS; a CAS that matches 0 rows rolls back and drops the output.
- **Budget (unchanged):** CallBudget at 10/12/15 s, burst 2, 1 in 5 tokens reserved for nudges. Cluster calls are EXTRACT. The caption queue is CAPTION_QUEUE (EXTRACT after 30 min). The night describe calls are BACKFILL. Asks are USER.
- **Retention defaults (spec §P6.1):** photo 7 days {1, 3, 7, 30, 90, forever}; skipped photo 1 day {0, 1, 7}; caption forever {30, 90, 365, forever}; cluster summary forever (same choices); image vector forever (same choices). `FOREVER = -1`. Describe-before-delete grace is **3 days**.
- **Memory ids** start with `rokid_` and never with `rokid_review_` (purge rules in `KeyframeGraph`). Cluster memories use `rokid_cl_<claimId>_<n>`.
- **Gradle:** from `android-companion/`, run `../.superpowers/sdd/gw.sh <tasks>` (it wraps `./gradlew --no-daemon` and allows at most 2 runs per machine). One Gradle run per worktree; never `gradlew --stop`. Test commands:
  - `../.superpowers/sdd/gw.sh :services:memory:testDebugUnitTest --tests '<Class>'`
  - `../.superpowers/sdd/gw.sh :services:orchestration:testDebugUnitTest --tests '<Class>'`
  - `../.superpowers/sdd/gw.sh :services:ai:testDebugUnitTest --tests '<Class>'`
  - `../.superpowers/sdd/gw.sh :phone:testDebugUnitTest --tests '<Class>'`
  - Full gate before handing back a stream: `tools/gate.sh <log>` from the repo root. It must end BUILD SUCCESSFUL with 0 failures. Known flaky tests (one re-run allowed): PromptEditorTest, DeleteAllFenceTest, PushToTalkTest, AppShellTest.leavingHome…, LocalModelsPageTest.
- **Repo rules:** never print secrets or `.env` values; never push. Logs carry ids, counts and class names only, never captions, user text or paths. Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Submodule: `git submodule update --init -- android-companion/services/ai/src/main/cpp/llama.cpp`, never `--reference`. No adb in code tasks (Task 12 is controller-only).

## Streams and dependencies

| Stream | Tasks | Needs |
|---|---|---|
| A-db | 1 | — |
| A-rules | 2, 3 | — (parallel with 1) |
| A-index | 4, 5 → 6 | 1, 2, 3 (5 needs only 1) |
| A-life | 7, 8 → 9 | 1 (9 also needs 6) |
| B | 10 → 11 → 12 | 10 is independent of Part A; 12 runs after the Part A merge |

## Canvas touch points (keep them in mind; don't build for the canvas)

- `SceneTuning` is a plain injectable `data class`; a canvas "scene" node can run `SceneRules` with other values on chosen photos.
- `SceneRules` and `ClusterContract` are pure (no DB, no Android), so a canvas runner can call them on N photos without writing rows.
- `PromptKey.CLUSTER_SUMMARY` is editable in the existing prompt editor; the canvas prompt node reads the same key. An Agent-flows step for it is **not** in this wave.
- The canvas's planned first stage (image intent: sitting, driving, …) may later feed `SceneRules.route` as an extra input. Leave `RouteInput` open to new optional fields; don't add any now.
- Canvas bulk runs use CallClass USER through `LanguageModel`; nothing here changes that.

---

## Part A — Wave 2: scene grouping and storage lifecycle

### Task 1: Room v3 — cluster claim columns, routing marker, DAOs

**Files:**
- modify `services/memory/src/main/java/com/rokid/context/memory/db/FrameEntities.kt`, `FrameDaos.kt`, `ContextDatabase.kt` (`version = 3`, `MIGRATION_2_3`), `DatabaseFactory.kt` (both builders);
- add `services/memory/schemas/com.rokid.context.memory.db.ContextDatabase/3.json` (exported);
- tests: create `services/memory/src/test/java/com/rokid/context/memory/db/Migration2To3Test.kt`, `FrameClusterDaoTest.kt`; extend `DatabaseFactoryUpgradeTest.kt`, `FrameStateDaoTest.kt`; extend `services/memory/src/androidTest/.../SqlCipherMigrationDeviceTest.kt` (v1 → v3).

**Migration (exact SQL):**
```sql
ALTER TABLE frame_cluster ADD COLUMN state TEXT NOT NULL DEFAULT 'OPEN';
ALTER TABLE frame_cluster ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE frame_cluster ADD COLUMN claimId TEXT;
ALTER TABLE frame_cluster ADD COLUMN claimedAt INTEGER;
ALTER TABLE frame_cluster ADD COLUMN lastError TEXT;
ALTER TABLE frame_cluster ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE frame_cluster ADD COLUMN motionClass TEXT;      -- STILL | MOVING
ALTER TABLE frame_cluster ADD COLUMN memberCount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE frame_cluster ADD COLUMN lastTs INTEGER;
UPDATE frame_cluster SET state = CASE WHEN closedAt IS NULL THEN 'OPEN' ELSE 'CLOSED' END;
CREATE INDEX IF NOT EXISTS index_frame_cluster_state ON frame_cluster(state);
ALTER TABLE frame_meta ADD COLUMN routedAt INTEGER;          -- null = not routed yet (Ruling 3)
CREATE INDEX IF NOT EXISTS index_frame_meta_clusterId ON frame_meta(clusterId);
```
The entities get matching fields with `@ColumnInfo(defaultValue = …)` so that Room's schema validation matches the migrated table.

**Interfaces (Produces):**
```kotlin
enum class FrameState { PENDING, EXTRACTING, EXTRACTED, CLUSTERED, NEAR_DUP, FAILED, FAILED_FINAL, IN_CLUSTER, CAPTION_QUEUED }
enum class ClusterState { OPEN, CLOSED, SUMMARIZING, SUMMARIZED, FAILED, FAILED_FINAL }

@Dao abstract class FrameClusterDao {
    @Insert(onConflict = REPLACE) abstract suspend fun upsert(row: FrameClusterEntity)
    @Query("SELECT * FROM frame_cluster WHERE state = 'OPEN' ORDER BY openedAt DESC LIMIT 1") abstract suspend fun open(): FrameClusterEntity?
    abstract suspend fun get(clusterId: String): FrameClusterEntity?
    /** CLOSED, FAILED past its backoff (same 30 s-doubling SQL as frame_state, capped at 30 min), or SUMMARIZING abandoned before [abandonBeforeMs]; oldest closedAt first. */
    abstract suspend fun claimable(nowMs: Long, abandonBeforeMs: Long, limit: Int): List<FrameClusterEntity>
    /** → SUMMARIZING with claimId/claimedAt; returns the rows it claimed (abandoned ones count an attempt first, as FrameStateDao.claim does). */
    @Transaction open suspend fun claim(ids: List<String>, claimId: String, nowMs: Long, abandonBeforeMs: Long): List<FrameClusterEntity>
    /** The CAS: SUMMARIZING + claimId → SUMMARIZED with memoryId; 1 or 0. */
    abstract suspend fun completeIfClaimed(clusterId: String, claimId: String, memoryId: String, nowMs: Long): Int
    abstract suspend fun fail(clusterId: String, claimId: String, error: String, nowMs: Long, maxAttempts: Int = 5): Int   // FAILED or FAILED_FINAL
    abstract suspend fun release(clusterId: String, claimId: String, nowMs: Long): Int                                       // → CLOSED, no attempt
    abstract suspend fun releaseAllSummarizing(nowMs: Long, claimedBeforeMs: Long): Int                                       // process start
    abstract suspend fun requeueAbandoned(nowMs: Long, abandonBeforeMs: Long, maxAttempts: Int = 5): Int
    abstract fun counts(): Flow<Map<String, Int>>                                                                              // by state, for Diagnostics
}
// FrameMetaDao additions
suspend fun unroutedPending(limit: Int): List<FrameMetaEntity>   // frame_state PENDING, frame_meta.routedAt IS NULL, no tombstone; ORDER BY COALESCE(frame.ts, meta.createdAt) ASC
suspend fun members(clusterId: String): List<FrameMetaEntity>
suspend fun markRouted(frameId: String, salience: Float?, salienceTerms: String?, nearDupOf: String?, clusterId: String?, imageVectorSpace: String?, routedAt: Long)
// FrameVectorDao additions
suspend fun get(frameId: String, spaceId: String): FrameVectorEntity?
/** Vectors of [spaceId] for frames captured in [fromTs, toTs] (JOIN frame ON f.id = v.frameId, by f.ts). */
suspend fun window(spaceId: String, fromTs: Long, toTs: Long): List<FrameVectorEntity>
suspend fun forFrames(spaceId: String, frameIds: List<String>): List<FrameVectorEntity>
// FrameStateDao additions
/** Guarded move from [from] to [to] (no claim involved): routing and cluster settlement. Returns rows changed. */
abstract suspend fun move(frameIds: List<String>, from: String, to: String, lastError: String?, nowMs: Long): Int
/** Members of [clusterId] still IN_CLUSTER → [to] (CLUSTERED, NEAR_DUP or FAILED_FINAL). */
abstract suspend fun settleCluster(clusterId: String, to: String, lastError: String?, nowMs: Long): Int
/** Ask path: frames captured >= sinceMs in PENDING (routed or not) or IN_CLUSTER of an OPEN/CLOSED cluster → EXTRACTING. */
@Transaction open suspend fun claimForAsk(frameIds: List<String>, claimId: String, nowMs: Long): List<FrameStateEntity>
abstract suspend fun captionQueued(limit: Int): List<FrameStateEntity>   // oldest updatedAt first, no tombstone
abstract suspend fun claimCaptionQueued(frameIds: List<String>, claimId: String, nowMs: Long): List<FrameStateEntity>
```
- **Ruling 3 in SQL:** `newestClaimable`, `newestDwellClaimable` and `recoverableRows` gain `AND (NOT EXISTS (SELECT 1 FROM frame_meta m2 WHERE m2.frameId = s.frameId AND m2.routedAt IS NULL) OR s.updatedAt <= :nowMs - 600000)`. The legacy claim path is unchanged otherwise.
- `WipeDao` (Delete all) already clears `frame_cluster`; check that it still does with the new columns. `ForgetService.forgetMoment` is unchanged.

**Tests:**
- `Migration2To3Test`: `v2RowsSurvive` (rows in all 5 frame tables plus memory), `closedClustersBecomeClosedState`, `newColumnsHaveDefaults`, `noOtherTableChanged`.
- `DatabaseFactoryUpgradeTest.v1FileUpgradesToV3ThroughBothBuilders` (plain and SQLCipher).
- `FrameClusterDaoTest`: `claimCasAndStaleClaim`, `abandonedClaimCountsAttempt`, `fifthFailureIsFinal`, `releaseAtStartOnlyOldClaims`, `failedBackoffRespected`.
- `FrameStateDaoTest`: `unroutedFrameNotClaimableForTenMinutes`, `legacyFrameWithoutMetaStillClaimable`, `settleClusterOnlyMovesInCluster`, `askClaimsInClusterAndUnrouted`, `askSkipsSummarizingClusterMembers`, `captionQueuedOldestFirst`.

- [ ] RED → implement → GREEN: `../.superpowers/sdd/gw.sh :services:memory:testDebugUnitTest`. Commit `feat(db): Room v3 — additive cluster claim/state columns and frame_meta.routedAt`.

---

### Task 2: `SceneRules` — salience, dedupe, cluster closing, representatives (pure Kotlin)

**Files:** create `services/orchestration/src/main/java/com/rokid/context/work/frames/scene/SceneRules.kt` and `SceneTuning.kt`; test `services/orchestration/src/test/java/com/rokid/context/work/frames/scene/SceneRulesTest.kt`. No Android and no DB imports (a canvas runner reuses this).

**Interfaces (Produces):**
```kotlin
data class SceneTuning(
    val dedupeCos: Float = 0.93f, val dedupeWindowMs: Long = 10 * 60_000L, val noveltyWindowMs: Long = 30 * 60_000L,
    val watchCos: Float = 0.25f, val wDwell: Float = 0.4f, val wText: Float = 0.3f, val wNovelty: Float = 0.3f, val wWatch: Float = 0.3f,
    val textMinWords: Int = 4, val salientAt: Float = 0.5f,
    val driftCos: Float = 0.25f, val driftRun: Int = 2, val maxMembers: Int = 100, val maxAgeMs: Long = 5 * 60_000L,
    val gapMs: Long = 2 * 60_000L, val outOfOrderMs: Long = 60_000L, val maxReps: Int = 3,
    val callMaxClusters: Int = 3, val callMaxPhotos: Int = 6, val companionWaitMs: Long = 60_000L,
)
enum class MotionClass { STILL, MOVING, UNKNOWN }   // HEAD_MOVING/WALKING/VEHICLE → MOVING; UNKNOWN never closes a cluster
data class SalienceTerms(val dwell: Float, val text: Float, val novelty: Float, val watch: Float) { val total: Float /* capped 1.0 */; fun json(): String }
data class RouteInput(val frameId: String, val ts: Long, val dwell: Boolean, val motion: MotionClass, val blur: Float?, val vector: FloatArray?, val textWords: Int?)
sealed interface Route {
    data object FastPath : Route                                         // PENDING
    data class NearDup(val of: String, val captionQueue: Boolean) : Route // NEAR_DUP, or CAPTION_QUEUED when salient
    data object Cluster : Route                                          // join or open a cluster
}
object SceneRules {
    fun salience(dwell: Boolean, textWords: Int?, maxCosNovelty: Float?, maxCosWatch: Float?, t: SceneTuning): SalienceTerms
    /** Highest-cosine neighbour within the dedupe window (vectors are unit length, so cosine = dot). */
    fun nearestDup(v: FloatArray, recent: List<Pair<String, FloatArray>>, t: SceneTuning): Pair<String, Float>?
    /** dwell → FastPath; non-dwell dup → NearDup (captionQueue = salient); salient → FastPath; vector == null → FastPath (Ruling 2); else Cluster. */
    fun route(input: RouteInput, salience: SalienceTerms, dup: Pair<String, Float>?, t: SceneTuning): Route
    sealed interface Close { data object No : Close; data class Yes(val reason: String, val carry: List<String>) : Close }  // reasons: drift|motion|size|age|gap|out_of_order
    /** Before adding [next] to the open cluster. [carry] = frames moved to the new cluster (the earlier drifting frame on a drift close). */
    fun shouldClose(state: OpenClusterView, next: RouteInput, t: SceneTuning): Close
    fun closeByTime(state: OpenClusterView, nowMs: Long, t: SceneTuning): String?   // "age" | "gap" | null
    /** Up to maxReps distinct ids in this order: medoid (max mean cosine), most different (min cosine to the medoid), sharpest (max blur). */
    fun pickReps(members: List<RepCandidate>, t: SceneTuning): List<String>
    /** Photos per cluster for one call: 1–2 clusters → 3 each; 3 clusters → 2 each (medoid, most different); total ≤ callMaxPhotos. */
    fun photosPerCluster(clusters: Int, t: SceneTuning): Int
    fun centroid(vs: List<FloatArray>): FloatArray   // mean, then L2
}
data class OpenClusterView(val openedAt: Long, val lastTs: Long, val memberCount: Int, val motion: MotionClass, val centroid: FloatArray?, val driftStreak: List<String>)
data class RepCandidate(val frameId: String, val vector: FloatArray, val blur: Float?)
```

**Tests (fake unit vectors):** `salienceTermsAndCap`, `dwellAlwaysFastPath`, `dupAboveThresholdIsNearDup`, `salientDupGoesToCaptionQueue`, `noVectorMeansFastPath`, `nonSalientClusters`, `driftNeedsTwoConsecutive`, `driftCloseCarriesFirstDriftingFrame`, `motionChangeCloses`, `unknownMotionNeverCloses`, `sizeAgeGapOutOfOrderClose`, `repsMedoidMostDifferentSharpest`, `repsDistinctWhenFewMembers`, `photosPerClusterTable`, `replayDayGivesExpectedClusters` (a fixture of about 200 synthetic frames, 6 scenes, at fixed timestamps → the expected cluster boundaries and salient set; spec §P4.6 replay).

- [ ] RED → implement → GREEN: `../.superpowers/sdd/gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.work.frames.scene.*'`. Commit `feat(scene): SceneRules — salience, semantic dedupe, cluster closing and representatives`.

---

### Task 3: text-in-photo detector and the watch list

**Files:**
- create `services/orchestration/src/main/java/com/rokid/context/work/frames/scene/TextPresence.kt` and `WatchList.kt`;
- modify `services/orchestration/build.gradle.kts` (`implementation("com.google.mlkit:text-recognition:16.0.1")`, the bundled Latin model; record the APK size delta in the report);
- modify `phone/src/main/java/com/rokid/context/phone/ui/settings/memory/MemorySettings.kt` (a "Watch list" row: one text field, comma-separated, with Reset to defaults);
- tests `services/orchestration/src/test/.../scene/WatchListTest.kt`, `TextPresenceTest.kt`; `phone/src/test/.../settings/memory/WatchListRowTest.kt`.

**Interfaces (Produces):**
```kotlin
fun interface TextPresence { suspend fun words(jpeg: ByteArray): Int? }      // null = couldn't run (no model, timeout)
class MlKitTextPresence(private val timeoutMs: Long = 2_000L) : TextPresence   // counts tokens with ≥ 2 letters; never logs text
class WatchListStore(prefs: SharedPreferences) {
    val items: StateFlow<List<String>>   // trimmed, distinct, 1..20 items, each ≤ 40 chars
    fun set(items: List<String>); fun reset()
    companion object { val DEFAULTS = listOf("keys", "car", "parking sign", "receipt", "whiteboard", "document", "a person's face") }
}
/** Query vectors for the list, cached per (space fingerprint, items, dims); rebuilt when either changes. */
class WatchVectors(private val store: WatchListStore) {
    suspend fun vectors(fingerprint: String, dims: Int, embedQueryFull: suspend (String) -> FloatArray): List<FloatArray>   // full 768 → first dims → L2
    fun maxCos(v: FloatArray, list: List<FloatArray>): Float?
}
```
- Recognised text is **never** stored or logged; only the word count reaches `salienceTerms`.

**Tests:** `defaultsAndReset`, `trimsDedupesCaps`, `vectorsCachedUntilListOrSpaceChanges`, `shortenRenormalises`; `TextPresenceTest.timeoutReturnsNull` (fake recognizer seam), `countsOnlyWordTokens`; `WatchListRowTest.editSavesAndResetRestores`.

- [ ] RED → implement → GREEN (`:services:orchestration:testDebugUnitTest`, `:phone:testDebugUnitTest --tests '*WatchList*'`). Commit `feat(scene): ML Kit text presence and an editable watch list`.

---

### Task 4: `SceneIndexer` — route each keyframe (vector → salience → dedupe → cluster)

**Needs:** Tasks 1–3. **Spec:** §P4.1, §P2.5 (power), Rulings 2, 3, 4, 7, 15, 18.

**Files:** create `services/orchestration/src/main/java/com/rokid/context/work/frames/scene/SceneIndexer.kt` and `ImageSpaces.kt`; modify `work/budget/PhonePowerPolicy.kt` (`vectorBatchIntervalMs` → 15 000 in the background, per Ruling 4); tests `SceneIndexerTest.kt` (Robolectric, in-memory Room, fake embedder).

**Interfaces (Produces):**
```kotlin
/** A space that can hold image vectors: the ACTIVE one, plus the BUILDING one during a re-index (both are written; only ACTIVE is read). */
class ImageSpaceTarget(val spaceId: String, val fingerprint: String, val imageDims: Int,
    val embedImages: suspend (List<ByteArray>) -> List<FloatArray>, val embedQueryFull: suspend (String) -> FloatArray)
fun interface ImageSpaces { suspend fun targets(): List<ImageSpaceTarget> }   // [active?, building?]; only spaces whose embedder has Modality.IMAGE
/** Real one: EmbeddingSpaces.queryable()/building() + SpaceEmbedderProvider.forSpace(); imageDims = the first vector's size, cached per space. */
class SpaceImageSpaces(spaces: EmbeddingSpaces, provider: SpaceEmbedderProvider) : ImageSpaces

class SceneIndexer(
    db: ContextDatabase, images: ImageSpaces, text: TextPresence, watch: WatchVectors, power: PhonePowerPolicy?,
    photos: suspend (List<String>) -> Map<String, ByteArray>,   // FramePhotos.load; missing = unreadable
    clock: () -> Long, tuning: () -> SceneTuning = { SceneTuning() }, foreground: () -> Boolean = { true },
) {
    /** Routes up to 8 unrouted PENDING frames, oldest capture first. Returns frames routed and, if it held back, when to come back. */
    suspend fun routeBatch(): RouteResult
    /** Closes the open cluster on age (5 min) or gap (2 min); called on every pipeline pass and by the backstop. */
    suspend fun closeAged(): Boolean
}
data class RouteResult(val routed: Int, val retryInMs: Long?)
```

**Behaviour:**
- **Gates:**
  - SEVERE: nothing is routed.
  - MODERATE (`mayRunBackground(IMAGE_VECTORS) == false`): dwell frames are routed without a vector (FastPath); the others wait.
  - In the background with no dwell frame waiting: wait until 8 are waiting or the oldest has waited 15 s (`retryInMs`).
- **Per frame:**
  - load the JPEG; a missing photo → FastPath (the pipeline fails it as unreadable);
  - `embedImages` in one batch per target; `frame_vector` upsert for every target;
  - two decode/embed failures for the same frame → `imageVectorSpace = "unembeddable"`;
  - text words; novelty = max cosine against `window(active, ts − 30 min, ts)`; dedupe against the last 10 min; watch = `maxCos`;
  - `SceneRules.route`.
- **Write per frame, in one transaction:**
  - `markRouted(... routedAt = now)`;
  - the state move from PENDING is guarded (`move`): FastPath stays PENDING; NearDup → NEAR_DUP with lastError `semantic_dup` (or CAPTION_QUEUED); Cluster → IN_CLUSTER, with `clusterId` set;
  - a guarded move that changed 0 rows (an Ask claimed the frame meanwhile) keeps the meta and leaves the state alone.
- **Clusters:**
  - one OPEN cluster at a time;
  - before adding, `shouldClose` runs; on close: `pickReps` over the members' active-space vectors and `blur` (from frame_meta), then `repFrameIds` JSON, `closedAt`, `closeReason`, state CLOSED, and the carried frames' `clusterId` moves to the new cluster;
  - the centroid and the drift streak are kept in memory and rebuilt from `members()` after a restart (the cost is at most one extra frame).
- **Logging:** only `PcScene` with `routed=… fast=… dup=… clustered=… closed=<reason>`.

**Tests:** `dwellRoutesFastEvenWithoutVectors`, `noImageSpaceKeepsWaveOneBehaviour` (Ruling 2), `vectorsWrittenToActiveAndBuilding`, `dupMarkedWithLink`, `salientDupCaptionQueued`, `clusterOpenedJoinedAndClosedOnDrift`, `closeAgedOnFiveMinutesAndGap`, `outOfOrderBurstStartsNewCluster`, `moderateRoutesOnlyDwell`, `severeRoutesNothing`, `backgroundBatchesEightOrFifteenSeconds`, `askClaimedFrameKeepsItsClaim`, `unembeddableAfterTwoFailures`, `restartRebuildsCentroid`, `logsHaveNoText` (log capture: no caption or recognised text).

- [ ] RED → implement → GREEN: `../.superpowers/sdd/gw.sh :services:orchestration:testDebugUnitTest --tests '*SceneIndexer*'`. Commit `feat(scene): SceneIndexer routes keyframes into fast path, near-dups and scene clusters`.

---

### Task 5: CLUSTER_SUMMARY prompt, contract and atomic cluster save

**Needs:** Task 1. **Spec:** §P4.1 cluster-batch call, §P2.1 provenance; Rulings 5, 6.

**Files:**
- modify `services/ai/src/main/java/com/rokid/context/ai/contract/PromptKey.kt` (append `CLUSTER_SUMMARY(Role.VISION_EXTRACT, "Scene summaries")` **after** the existing entries, so that `of(role)` still returns VISION_EXTRACT);
- the default prompt and schema next to the VISION_EXTRACT defaults (find them via `PromptKey.VISION_EXTRACT` usages);
- create `services/ai/src/main/java/com/rokid/context/ai/contract/ClusterContract.kt`;
- create `services/orchestration/src/main/java/com/rokid/context/work/frames/scene/ClusterSave.kt`;
- tests `ClusterContractTest.kt` (`:services:ai`), `ClusterSaveTest.kt` (`:services:orchestration`, Robolectric).

**Interfaces (Produces):**
```kotlin
// Output schema (JSON schema, strict): {"clusters":[{"cluster_id":str,"summary":str,"facts":[{"text","type","tags","confidence","source_frame_ids":[str]}]}],
//                                       "captions":[{"frame_id":str,"caption":str}]}   captions maxItems 6, clusters maxItems 3
data class ClusterPhoto(val clusterId: String, val frameId: String, val jpeg: ByteArray, val line: String)   // line = "cluster_id: … frame_id: … time: … motion: …"
data class ClusterOutput(val clusters: List<ClusterResult>, val captions: List<FrameCaption>)
data class ClusterResult(val clusterId: String, val summary: String, val facts: List<ExtractedFact>)
object ClusterContract {
    fun promptIsCurrent(schema: String?): Boolean            // has clusters[].cluster_id and per-fact source_frame_ids
    /** INVALID when: an unknown cluster_id, a sent cluster missing or blank, a source_frame_id not sent for THAT cluster, or a sent photo without a caption. */
    fun validate(out: ClusterOutput, sent: Map<String, List<String>>): Boolean
    suspend fun call(runner: RoleRunner, photos: List<ClusterPhoto>, callClass: CallClass): ClusterOutput
}
sealed interface ClusterSaveResult { data class Saved(val memories: List<SavedMemory>) : ClusterSaveResult; data object Stale : ClusterSaveResult; data object Wiped : ClusterSaveResult }
object ClusterSave {
    suspend fun save(context: Context, claimId: String, out: ClusterOutput, sent: Map<String, List<String>>, generation: Long, nowMs: Long, factReview: ReviewState): ClusterSaveResult
}
```

**Save (one transaction; spec §P2.5 rules):**
1. `MemoryWipe.generation` is checked; then `FrameClusterDao.completeIfClaimed` for **every** cluster in the call. Any 0 → roll back everything (Stale).
2. Per cluster:
   - the summary memory `rokid_cl_<claimId>_<i>`: type `cluster_summary`, tier LONG, ReviewState OK, `frameId` = the medoid;
   - its facts `rokid_cl_<claimId>_<i>f<n>`: type and tags from the output, tier SHORT, ReviewState `factReview`;
   - captions `rokid_cl_<claimId>_c<n>`: type `caption`, tier LONG, OK;
   - all go through `PipelineRecords.appendFact` with `fenceFrameIds` = the cluster's sent representatives.
3. `frame_cluster.memoryId` = the summary id.
4. `frame_source` rows:
   - summary → every member: role CLUSTER_REP for representatives, CLUSTER_MEMBER for the rest; `clusterId` set; members tombstoned meanwhile are skipped;
   - each fact → its `source_frame_ids` (role FACT, `clusterId` set);
   - each caption → its frame (role FACT).
5. `FrameStateDao.settleCluster(clusterId, "CLUSTERED", null, now)`.
- **An out-of-date or edited prompt** (`promptIsCurrent == false`): one cluster per call; the facts are attributed to that cluster's representatives; captions are optional. Diagnostics shows "Scene summary prompt is out of date — reset to default" (mirror wave 1's VISION_EXTRACT warning).
- **INVALID output:** retry each cluster alone once; still invalid → `fail` for that cluster (counts an attempt).

**Tests:**
- `ClusterContractTest`: `validRoundTrip`, `rejectsUnknownCluster`, `rejectsCrossClusterSourceId`, `rejectsMissingCaption`, `editedPromptIsOneClusterPerCall`.
- `ClusterSaveTest`: `saveWritesSummaryFactsCaptionsAndProvenance`, `casFailureWritesNothing`, `wipeDuringCallWritesNothing`, `forgottenRepDropsSave`, `forgottenMemberSkippedInProvenance`, `membersBecomeClustered`, `askClaimedMemberNotOverwritten` (the member is EXTRACTING → stays), `idsStartWithRokidPrefix`.

- [ ] RED → implement → GREEN (`:services:ai:testDebugUnitTest --tests '*ClusterContract*'`, `:services:orchestration:testDebugUnitTest --tests '*ClusterSave*'`). Commit `feat(scene): CLUSTER_SUMMARY prompt, provenance contract and atomic cluster save`.

---

### Task 6: pipeline integration — routing, cluster calls, caption queue, asks, recovery, Diagnostics

**Needs:** Tasks 4, 5. **Spec:** §P2.1, §P2.3, §P2.5, §Captions (aging); Rulings 3, 5, 8, 9, 10, 16.

**Files:**
- modify `services/orchestration/src/main/java/com/rokid/context/work/frames/KeyframePipeline.kt`, `KeyframeGraph.kt`, `FrameRecovery.kt`, `KeyframeStats.kt`;
- modify `phone/src/main/java/com/rokid/context/phone/ui/settings/Diagnostics.kt`;
- tests: extend `KeyframePipelineTest.kt`; new `ClusterCallTest.kt`, `CaptionQueueTest.kt`; extend `DiagnosticsTest.kt`.

**Behaviour (`drain`, background classes):**
1. Power and battery gates as today; then `indexer.closeAged()` and `indexer.routeBatch()`. A `retryInMs` from routing becomes the Idle wait when nothing else is claimable.
2. `shedBacklog` as today, except that shed frames with `frame_meta.salience >= 0.5` move to CAPTION_QUEUED (lastError `shed_backlog`) instead of NEAR_DUP.
3. **Pick, in order:**
   1. dwell PENDING;
   2. caption-queued frames that have waited ≥ 10 min (`updatedAt`);
   3. newest PENDING;
   4. **cluster call:** when ≥ 3 clusters are claimable, or the oldest CLOSED one has waited ≥ 60 s: claim ≤ 3 clusters (`FrameClusterDao.claim`), take `photosPerCluster` representatives each from `repFrameIds` (still IN_CLUSTER, readable), call `ClusterContract.call` under EXTRACT, then `ClusterSave.save`;
   5. caption-queued frames (class CAPTION_QUEUE, or EXTRACT after 30 min), ≤ 3 per VISION_EXTRACT through the existing `ExtractSave` path (`claimCaptionQueued`, then the CAS → EXTRACTED).
   Steps 1–3 are wave 1's per-photo path, unchanged.
4. **Cluster settle:**
   - Saved → `onExtracted` is **not** called (Ruling 16), but `ExtractFollowUp.afterSave` (embeddings) is;
   - Stale → release every claimed cluster (no attempt);
   - Failed → `FrameClusterDao.fail`; offline / no provider → `release` and the existing offline pause;
   - on FAILED_FINAL → the representatives' frame_state IN_CLUSTER → FAILED_FINAL (`move`), the others → `settleCluster(…, "NEAR_DUP", "cluster_failed")`.
5. **Ask (`extractForAsk`):** routes the frames of the last 60 s first, ignoring the background batching; then `claimForAsk` (PENDING routed or not, IN_CLUSTER not in a SUMMARIZING cluster) under USER, through the existing ExtractSave path.
6. **Recovery (`FrameRecovery`):** at process start `releaseAllSummarizing(processStart)`; the backstop runs `requeueAbandoned` for clusters (15 min); `drainUntilIdle` also routes and runs cluster calls.
7. **Diagnostics (`KeyframeStats` + Diagnostics page):**
   - clusters by state;
   - "Photos covered by scene summaries (last 24 h)" and "Scene calls (last 24 h)";
   - caption queue size;
   - unrouted count;
   - the out-of-date CLUSTER_SUMMARY prompt warning.
   Counts only.

**Tests:**
- `KeyframePipelineTest`: `routesBeforeClaim`, `dwellStillFirst`, `waveOneBehaviourWithoutImageSpace`, `shedSalientGoesToCaptionQueue`.
- `ClusterCallTest`: `threeClustersOneCall`, `singleClusterWaitsSixtySeconds`, `sixPhotoCap`, `staleReleasesAll`, `finalFailureSplitsRepsAndMembers`, `offlineReleasesWithoutAttempt`, `noNudgeOfferForClusters`, `killMidCallThenRestartSummarisesOnce` (no duplicate memories).
- `CaptionQueueTest`: `lowestPriorityByDefault`, `agesAheadOfNonDwellAfterTenMinutes`, `becomesExtractAfterThirtyMinutes`, `neverTakesNudgeToken` (fake CallBudget).
- Ask: `askExtractsInClusterFrameOfLastMinute`, `askSkipsSummarizingCluster`.
- `DiagnosticsTest.showsClusterAndQueueCounts`.

- [ ] RED → implement → GREEN (`:services:orchestration:testDebugUnitTest`, `:phone:testDebugUnitTest --tests '*Diagnostics*'`). Then the stream gate `tools/gate.sh <log>`. Commit `feat(pipeline): route keyframes, one AI call per scene-cluster batch, caption queue with aging`.

---

### Task 7: image backfill and the per-space image-coverage swap gate

**Needs:** Task 1 (and `ImageSpaces` from Task 4; build against the interface if Task 4 isn't merged yet). **Spec:** §P4.2, §F.2; Rulings 15, 18.

**Files:**
- modify `services/orchestration/src/main/java/com/rokid/context/orchestration/IndexMaintenance.kt`;
- modify `services/memory/src/main/java/com/rokid/context/memory/EmbeddingSpaces.kt` and `db/EmbeddingDao.kt`;
- tests: extend `IndexMaintenanceTest.kt`, `EmbeddingSwapTest.kt`, `SwapDialogCountsTest.kt`.

**Interfaces (Changes):**
```kotlin
// EmbeddingSpaces: the constructor flag requireImageCoverage is removed; the caller decides per space.
suspend fun swapIfComplete(buildingId: String, requireImages: Boolean): Boolean
suspend fun missingImages(spaceId: String, limit: Int): List<String>   // frame rows with no frame_vector in spaceId and imageVectorSpace != 'unembeddable'
// EmbeddingDao.missingImageCount gains: AND NOT EXISTS (SELECT 1 FROM frame_meta m WHERE m.frameId = f.id AND m.imageVectorSpace = 'unembeddable')
```
- **IndexMaintenance.fill:** after the text pass, if the building space's embedder declares `Modality.IMAGE`, fill images in batches of 8. `LocalModelPower.bulkAllowed` is checked before each batch (Ruling 18: backfill is bulk). Photos come through `FramePhotos.load`; unreadable twice → `unembeddable`. The same applies to the ACTIVE space's missing images (catch-up after downtime).
- `swapIfComplete(building.id, requireImages = embedder.modalities.contains(IMAGE))`.
- **Backfill writes vectors only**: no salience, no clusters, no AI calls for already-extracted frames (Ruling 1 applies to new frames only). Frames that are still unrouted are left to the indexer.

**Tests:**
- `IndexMaintenanceTest`: `imageBackfillFillsStoredPhotosOnly`, `swapWaitsForImageCoverageWhenImageCapable`, `textOnlySpaceSwapsWithoutImages`, `unembeddableDoesNotBlockSwap`, `backfillPausedByBulkGate`.
- `EmbeddingSwapTest`: `rollbackKeepsRetiredFrameVectorsSevenDays` (extend), `purgeAfterGraceDropsFrameVectors`.
- `SwapDialogCountsTest`: `expiredPhotoCountsUseFrameRowAbsence` (with Task 8's expiry semantics: meta and vector kept, frame row gone).

- [ ] RED → implement → GREEN (`:services:memory:testDebugUnitTest`, `:services:orchestration:testDebugUnitTest --tests '*IndexMaintenance*'`). Commit `feat(index): image-vector backfill and a per-space image-coverage swap gate`.

---

### Task 8: retention for each kind of data and the skipped-photo 1-day rule (bug RET)

**Needs:** Task 1. **Spec:** §P6.1, §P6.2, §P6.3; Rulings 8, 11, 12, 13, 14.

**Files:**
- modify `services/memory/src/main/java/com/rokid/context/memory/MemorySettings.kt` (fields, choices, prefs keys, `clean`);
- modify `db/ForgetDao.kt` (queries) and `forget/ForgetService.kt` (`expirePhotos`);
- modify `services/orchestration/src/main/java/com/rokid/context/orchestration/forget/RetentionWorker.kt` (`Retention.run`);
- modify `phone/src/main/java/com/rokid/context/phone/ui/settings/memory/MemorySettings.kt` (the Retention section);
- modify the frame viewer and memory detail (`phone/.../ui/frames/FrameViewerViewModel.kt`, `ui/memory/MemoryDetailModel.kt`) for a "Photo expired" placeholder when `memory.frameId` has no frame row;
- tests: extend `RetentionWorkerTest.kt` and `MemoryRetentionTest.kt`; new `PhotoExpiryTest.kt`; `phone/src/test/.../RetentionSettingsTest.kt`.

**Interfaces (Produces):**
```kotlin
data class MemorySettings(val shortHours: Int = 24, val mediumDays: Int = 7, val longDays: Int = 30, val quotaMb: Int = 100,
    val photoDays: Int = 7, val skippedPhotoDays: Int = 1, val captionDays: Int = FOREVER, val clusterSummaryDays: Int = FOREVER,
    val imageVectorDays: Int = FOREVER, val perKindSince: Long = 0L) {
    companion object { const val FOREVER = -1 }
}
// choices: PHOTO_DAYS = [1,3,7,30,90,FOREVER]; SKIPPED_DAYS = [0,1,7]; LONG_KIND_DAYS = [30,90,365,FOREVER]
suspend fun ForgetService.expirePhotos(frameIds: List<String>): Result   // one transaction: tombstone + delete frame row; returns the JPEG and sidecar paths; keeps meta/vector/state/source/memories
```

**Retention pass, in order (daily, no charging constraint):**
1. Stamp `perKindSince = now` if it is 0 (Ruling 13).
2. Tier windows as today, but `expiredIds` excludes `type IN ('caption','cluster_summary')`.
3. Captions older than `captionDays`, and cluster summaries older than `clusterSummaryDays` (unless FOREVER) → `service.expire`.
4. **Ordinary skipped photos:**
   - who: a frame row with frame_meta, not tombstoned, `frame_state` NEAR_DUP, or CLUSTERED and **not** a representative (no `frame_source` row with role CLUSTER_REP for it), and salience < 0.5 or null;
   - deadline: `max(frame_state.updatedAt, perKindSince) + skippedPhotoDays` (0 = this run);
   - action: `expirePhotos`, with no describe-before-delete and no extension.
5. **Other wave-1+ photos:**
   - who: frame_meta present, state not in (PENDING, EXTRACTING, FAILED, IN_CLUSTER, CAPTION_QUEUED);
   - deadline: `max(frame.ts, perKindSince) + photoDays` (FOREVER = never);
   - past the deadline and linked to a memory (`memory.frameId` or `frame_source`) → `expirePhotos`;
   - past the deadline and not linked → kept until deadline + **3 days**, then `expirePhotos` anyway, counting `deletedUndescribed` in Diagnostics.
6. **Image vectors** older than `imageVectorDays` (by `frame_meta.createdAt`) → delete `frame_vector` rows (all spaces).
7. The legacy orphan pass (`orphanFramesBefore`) gains `AND NOT EXISTS (SELECT 1 FROM frame_meta m WHERE m.frameId = frame.id)` (Ruling 14).
8. Every JPEG goes through `ForgetActions.apply` → `FrameSweep.delete`, then `FrameSweep.sweep` as today (the tombstone makes a failed delete retry).
- **Settings → Memory → Retention:** five new rows (Photos, Skipped photos, Captions, Scene summaries, Image vectors), plus a warning when image vectors are shorter than captions, plus the note "Photos you already have are kept at least <photoDays> days from this update." `retentionNote` is updated.
- **Diagnostics counters** (prefs, count-only): `skippedPhotosDeleted`, `deletedUndescribed`.

**Tests:**
- `PhotoExpiryTest`: `expiryKeepsMetaVectorSourceAndMemories`, `tombstoneBlocksReextract`, `failedFileDeleteRetriedBySweep`, `fenceWithConcurrentForget`.
- `RetentionWorkerTest`:
  - `skippedPhotoDeletedAfterOneDay` (shed and semantic dup and cluster member);
  - `clusterRepNotTreatedAsSkipped`;
  - `promotedKeyMomentFollowsPhotoRetention`;
  - `zeroDaySkippedDeletesThisRun`;
  - `linkedPhotoDeletedAtSevenDays`;
  - `unlinkedPhotoGetsThreeDayGraceWithChargerNeverConnected` (spec §P6.4: still deleted on day 3);
  - `captionsAndSummariesOutliveTierWindows`;
  - `captionRetentionHonoured`;
  - `imageVectorRetention`;
  - `upgradeGraceProtectsOldPhotos`;
  - `legacyOrphanPassSkipsKeyframes`.
- `RetentionSettingsTest`: `rowsSaveChoices`, `warnsWhenVectorsShorterThanCaptions`.
- `MemoryDetailModelTest.expiredPhotoShowsPlaceholder`.

- [ ] RED → implement → GREEN (`:services:memory:testDebugUnitTest`, `:services:orchestration:testDebugUnitTest --tests '*Retention*' --tests '*PhotoExpiry*'`, `:phone:testDebugUnitTest --tests '*Retention*' --tests '*MemoryDetail*'`). Commit `feat(retention): per-kind retention, photo expiry that keeps vectors and captions, 1-day skipped photos (fixes RET)`.

---

### Task 9: night job — describe-before-delete and the image backfill

**Needs:** Tasks 6 and 8 (7 for the backfill call). **Spec:** §P6.1 "Two jobs"; Ruling 9.

**Files:**
- create `services/orchestration/src/main/java/com/rokid/context/orchestration/forget/NightIndexWorker.kt`;
- modify `work/OrchestrationScheduler.kt` (`ensureNightIndex()`: a unique periodic 24 h job, KEEP, constraints `setRequiresCharging(true)` and `setRequiresDeviceIdle(true)`);
- modify `KeyframePipeline.kt` (`drainCaptionQueue(maxMs, callClass = BACKFILL)` from the caller's coroutine, the same claim/CAS path as Task 6 step 5);
- tests `NightIndexWorkerTest.kt` (Robolectric + WorkManager test helpers).

**Behaviour:**
1. **Select** photos that are due within the next 24 h (Task 8's "other" deadline, ≤ now + 24 h), have no linked memory and are **not** ordinary skipped photos, in state FAILED_FINAL, EXTRACTED or NEAR_DUP (salience ≥ 0.5). Move them to `CAPTION_QUEUED` with attempts 0 and lastError `describe_before_delete` (a guarded `move`, chunked by 500), ordered by deadline.
2. `pipeline.drainCaptionQueue(maxMs = 9 * 60_000L, callClass = BACKFILL)`: BACKFILL never takes nudge tokens; the photo caps are as in wave 1.
3. Run `IndexMaintenance` once (the text and image backfill, under the bulk gate).
4. Count-only log `PcNight described=… left=… backfilled=…`.
- **No charger, no network, no budget:** nothing is described, and the retention job's 3-day grace (Task 8) deletes anyway. This job never deletes anything.

**Tests:** `describesOnlyUnlinkedNonSkippedDuePhotos`, `budgetRefusalLeavesForRetentionGrace`, `usesBackfillClass`, `constraintsChargingAndIdle`, `ordinarySkippedNeverDescribed`, `runsBackfillAfterDescribe`.

- [ ] RED → implement → GREEN (`:services:orchestration:testDebugUnitTest --tests '*NightIndex*'`). Stream gate `tools/gate.sh <log>`. Commit `feat(retention): night job — describe before delete, then the image backfill`.

---

## Part B — The Nomic switch (last)

### Task 10: Nomic as an image-capable embedder behind "Use for Embeddings"

**Needs:** nothing from Part A (it builds on wave-1 `:services:ai`). **Spec:** §F.1, §F.2, §F.4; Rulings 15, 17, 18.

**Files:**
- modify `services/ai/src/main/java/com/rokid/context/ai/local/NomicVision.kt` (`const val STORED_DIMS = 768`, with a KDoc: "256 only after the §F.5 photo test passes; changing it is a new space and a re-index");
- create `services/ai/src/main/java/com/rokid/context/ai/local/NomicBundle.kt`;
- modify `ai/embed/EndpointEmbedder.kt`, `ai/embed/EmbeddingSpec.kt` (an optional `imageDim: Int? = null` in the config JSON; `fromConfig` without it → null), `ai/embed/Embedders.kt`, `ai/embed/LocalModelPower.kt` (`PowerGatedEmbedder`);
- modify `phone/src/main/java/com/rokid/context/phone/ui/settings/ai/LocalModelsSource.kt` (only if "Use for Embeddings" for the bundle doesn't already require both files Ready);
- tests: extend `EndpointEmbedderTest`, `EmbeddersTest`, `LocalModelPowerTest`, `LocalModelsPageTest`; create `NomicBundleTest.kt`.

**Interfaces (Produces):**
```kotlin
object NomicBundle {
    const val VISION_FILE = "nomic-embed-vision-v1.5.model_quantized.onnx"
    /** One space for text (256) and images (imageDims): "nomic-embed-text-v1.5|mean|ln|d=256|l2+nomic-embed-vision-v1.5|cls|l2|d=<imageDims>|l2". */
    fun fingerprint(imageDims: Int = NomicVision.STORED_DIMS): String
    fun visionPath(textModelPath: String): File = File(File(textModelPath).parentFile, VISION_FILE)
}
```
- **EndpointEmbedder (Nomic + LLAMACPP_LOCAL):**
  - `fingerprint = NomicBundle.fingerprint(spec.imageDim ?: STORED_DIMS)`. It is **always** combined, whether or not the vision file is present, so the fingerprint never flips at runtime;
  - `modalities = {TEXT, IMAGE}` when `visionPath(...)` is a file and `!OnnxImageEmbedder.isUnavailable`, else `{TEXT}`;
  - `embedImages` goes through one process-wide `OnnxImageEmbedder(visionPath, imageDim)` (lazy, keyed by path and dims). The GGUF path is the endpoint's model path; find where `LlamaCppNativeProvider.embed` gets it in `EmbeddingClient`.
- **`Embedders.forBinding`** writes `imageDim = STORED_DIMS` into the spec for Nomic. A wave-1 Nomic text-only space (if the user ever bound it) has a different fingerprint, so the existing re-index runs. That's intended.
- **`PowerGatedEmbedder`** forwards `modalities`, `embedQueryFull` (the query gate) and `embedImages` (**no** G6 check, Ruling 18).
- **Roll back** (existing `SpacesSwapRollback` + `AiEmbedRebinder`): after Roll back, the bge space is ACTIVE with no IMAGE, so the indexer takes the Ruling 2 fallback. The Nomic space is RETIRED with its `frame_vector` rows kept 7 days. There is no code change unless a test shows otherwise.

**Tests:**
- `NomicBundleTest`: `fingerprintCombinesTextAndImageDims`, `differentImageDimsDifferentSpace`, `visionPathIsSibling`.
- `EndpointEmbedderTest`: `nomicDeclaresImageWhenVisionFilePresent`, `noVisionFileTextOnlySameFingerprint`, `embedImagesUsesStoredDims` (fake session seam), `bgeUnchanged`.
- `EmbeddersTest`: `bindingWritesImageDim`, `oldConfigWithoutImageDimParses`.
- `LocalModelPowerTest`: `gatedForwardsImagesWithoutBulkGate`, `gatedForwardsModalities`.
- `LocalModelsPageTest`: `useForEmbeddingsNeedsBothBundleFiles`, `rollbackAfterNomicSwapRebindsBge`.

- [ ] RED → implement → GREEN (`:services:ai:testDebugUnitTest`, `:phone:testDebugUnitTest --tests '*LocalModels*'`). Commit `feat(embed): Nomic v1.5 as one text+image space behind Use for Embeddings`.

---

### Task 11: wire `NomicDeviceRuntime.provider`, and the photo 256-vs-768 harness

**Needs:** Task 10 (Part A not needed). **Spec:** §F.1 reference vectors, §F.5 photo acceptance test.

**Files:**
- create `services/ai/src/androidTest/java/com/rokid/context/ai/embed/RealNomicDeviceRuntime.kt`;
- modify `NomicReferenceDeviceTest.kt` (an `@Before` installs the real runtime when `provider == null`; the models dir comes from the instrumentation argument `modelsDir`, default `filesDir/models`);
- create `services/ai/src/main/java/com/rokid/context/ai/local/RecallAtK.kt` (pure);
- create `phone/src/main/java/com/rokid/context/phone/ui/settings/diagnostics/PhotoDimEval.kt` (debug builds only, behind `BuildConfig.DEBUG`), with a Diagnostics row "Photo search dimension test (debug)";
- tests `RecallAtKTest.kt` (`:services:ai`), `PhotoDimEvalTest.kt` (`:phone`, Robolectric with fake vectors).

**Interfaces (Produces):**
```kotlin
class RealNomicDeviceRuntime(models: File) : NomicReferenceDeviceTest.NomicDeviceRuntime {
    // rawText = LlamaCppNativeProvider.embed(File(models, "nomic-embed-text-v1.5.Q8_0.gguf").path, listOf(prefixed), normalize = false).single()
    // rawImage = runBlocking { OnnxImageEmbedder(File(models, NomicBundle.VISION_FILE).path, 768).rawCls(listOf(jpeg)).single() }
}
object RecallAtK {
    data class Query(val text: String, val relevant: Set<String>, val small: Boolean)
    data class Result(val dims: Int, val overall: Double, val small: Double, val queries: Int, val smallQueries: Int, val photos: Int)
    /** recall@k = share of queries with ≥ 1 relevant photo in the top k by cosine. Images: CLS → L2 → first d → L2; queries: raw → LayerNorm → first d → L2. */
    fun evaluate(images: Map<String, FloatArray>, queries: List<Pair<Query, FloatArray>>, dims: Int, k: Int = 3): Result
    /** The §F.5 rule: 256 only if overall drops ≤ 3 points AND small drops ≤ 5 points versus 768. */
    fun choose256(r768: Result, r256: Result): Boolean
}
```
- **PhotoDimEval (debug only):**
  - reads `files/photo-eval/queries.json`, pushed by the controller with `run-as`: `[{"query": "...", "frameIds": ["..."], "small": true}]`;
  - loads each frame's photo through `FramePhotos.load` (decrypting in-app; photos never leave the phone), embeds the images and `search_query: `-prefixed queries with the downloaded bundle (not the bound space);
  - runs `evaluate` at 768 and 256 and writes `files/photo-eval/result.json` with **numbers only** (no query text, no ids);
  - shows the two results and `choose256` on screen;
  - refuses to run with < 60 photos, < 40 queries or < 15 small queries (message with the counts).
- **Frame ids for labelling:** in debug builds the frame viewer shows a "Copy frame id" action if it doesn't already (check `FrameViewerViewModel`).

**Tests:**
- `RecallAtKTest`: `perfectAndZeroRecall`, `smallSubsetSeparate`, `shortenThenRenormalise`, `ruleBoundaries` (exactly 3.0 / 5.0 → 256 allowed; 3.1 → not).
- `PhotoDimEvalTest`: `refusesUndersizedFixture`, `resultHasNoText`, `releaseBuildHidesRow`.
- Native and androidTest compile: `../.superpowers/sdd/gw.sh :services:ai:assembleDebugAndroidTest`.

- [ ] RED → implement → GREEN (`:services:ai:testDebugUnitTest --tests '*RecallAtK*'`, `:phone:testDebugUnitTest --tests '*PhotoDimEval*'`, plus the androidTest assemble). Gate `tools/gate.sh <log>`. Commit `test(nomic): wire the device runtime for the reference test; debug photo 256-vs-768 harness`.

---

### Task 12: device check (controller only; no code) — reference test, photo test, bind, re-index, Roll back

**Needs:** Tasks 1–11 merged and the gate green. Follow handoff §2 (filtered logcat or our tags only, never print secrets, `-s <serial>` always, an explicit user OK before anything destructive, never toggle Wi-Fi). Phone: `adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp`.

1. **Backup first:** the phone app data tar (as `~/Desktop/rokid/backups/…`) before installing the wave-2 build (it runs the v2 → v3 migration and the new retention). Install with `install -r`. Check that the migration kept every row: DB counts only (`frame_meta`, `frame_state`, `frame_cluster`, `memory`).
2. **Reference test:**
   - Fetch the two pinned bundle files on the Mac from the catalog URLs into the scratchpad, and check SHA-256 against `LocalModels.kt` (print match yes/no only).
   - `adb push` them to `/data/local/tmp/pca-nomic/`, install the `:services:ai` androidTest APK, then `adb shell run-as com.rokid.context.services.ai.test sh -c 'mkdir -p files/models && cp /data/local/tmp/pca-nomic/* files/models/'` (if `run-as` can't read there, pass `-e modelsDir /data/local/tmp/pca-nomic`).
   - Run `NomicReferenceDeviceTest` and `NomicVisionDeviceTest`.
   - **Pass:** every cosine ≥ 0.99 and ≥ 20 images/s. **On a fail, stop:** don't bind Nomic, and record the result.
3. **Photo test (user + controller):**
   - The user labels in the app: debug frame viewer -> "Add to photo test" (query text + small/edge toggle), ≥ 60 photos, ≥ 40 queries, ≥ 15 small. The app adds up to 1,500 other photos as distractors (refuses below max(300, 5 × labelled)); verdict PASS_256 / KEEP_768 / INCONCLUSIVE (768 ≥ 0.95 overall and small → keep 768). Vision truncation to 256 is not a documented Nomic property: say "evidence from your own photos only" in the decision log.
   - Run Diagnostics → "Photo search dimension test". Read `result.json` (numbers only).
   - **Rule:** store 256 only if recall@3 drops ≤ 3 points overall **and** ≤ 5 points on the small/edge subset; **otherwise keep 768 (the default).**
   - If 256 wins: a one-line commit `NomicVision.STORED_DIMS = 256` and a rebuild **before** step 4.
   - Record the result in `docs/superpowers/specs/2026-10-14-decision-log.md`.
4. **Bind:**
   - Settings → AI → On-device models → Nomic → "Use for Embeddings" (needs the user's OK).
   - The re-index runs under the battery gate (keep the phone charging). Watch progress, and check that the swap waits for 100 % text and stored-photo image coverage.
   - After the swap: Diagnostics shows clusters forming while the glasses capture (a 10-min worn session). Record keyframes per minute against AI calls per minute; the expected cut is ≥ 3× fewer calls than wave 1 at the same keyframe rate.
5. **Roll back:**
   - Roll back (OK first). bge is active again, search works, the indexer is in the fallback (no clusters for new frames), and the Nomic vectors are still counted.
   - Then re-bind Nomic (or leave it rolled back, as the user prefers).
6. **Retention sanity** (no waiting for days): Diagnostics shows the `perKindSince` note, and skipped-photo counts grow after a capture session. Nothing older than the upgrade is deleted on the first run (check the frame row count before and after the next RetentionWorker run).
7. Write the results into the handoff (§6 device facts, §8 bug RET → fixed) and the backlog. Then suggest a fresh chat.

- [ ] Done when steps 1–7 are recorded. No commit except the possible `STORED_DIMS` line and the docs (`docs: wave 2 device check — Nomic reference, photo dims, bind and roll back`).
