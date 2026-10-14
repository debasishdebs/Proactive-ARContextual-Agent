# Keyframe Capture, Wave 1 (F + C + P1 + P2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. This is a **lean plan**, per the user's standing preference: exact interfaces, behaviour bullets and named tests, not full code. **Implementers are Sonnet; reviews are Opus.** Streams F, C and G run in parallel worktrees. Stream P starts once F1 and C1 are merged.

**Goal:**
- motion-aware keyframe sampling on the glasses (640 px, with heat and work/rest guards);
- keyframes processed on arrival on the phone, under a call budget, with per-photo provenance, durable per-frame state and fast live nudges;
- Nomic Embed v1.5 (text + vision) behind one `EmbeddingService`;
- Room v2 (additive tables).

**Architecture:**
- `T0` lands the shared additive protocol fields and version numbers first.
- **F** (`:services:ai`) adds the `LanguageModel` seam over `ModelRouter`, `EmbeddingService`, the Nomic text transform and Nomic vision on ONNX Runtime.
- **C** (`:services:memory`) adds Migration(1,2): 5 tables plus the rollback grace on space swaps.
- **G** (`:glasses`) replaces the per-capture CameraX bind with a bound sampling session driven by `MotionClassifier`, `KeyframeGate`, `HeatGuard` and `WorkRest`.
- **P** (`:phone`, `:services:orchestration`, `:services:ai` contracts) adds `KeyframePipeline`, `CallBudget`, the provenance contract, claim/CAS recovery, `NudgeEvaluator` and the settings UI.

**Tech Stack:**
- Kotlin, Room + SQLCipher, WorkManager, Compose;
- CameraX (`ResolutionSelector`) and SensorManager on Android 12 glasses;
- llama.cpp JNI (existing), ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android`, MIT);
- Robolectric `@Config(sdk=[34])`.

**Spec:** `docs/superpowers/specs/2026-10-15-keyframe-capture-image-memory-design.md` (revision 6). Read the spec section named in each task before starting it. The spec wins on any conflict.

## Global Constraints

- **Room:** DB version 1 → **2**, through `Migration(1, 2)` that **only adds** `frame_meta`, `frame_state`, `frame_source`, `frame_cluster`, `frame_vector`. No existing table or column changes. `memory_source` (memory → memory) is untouched.
- **PROTOCOL_VERSION stays 1.** Only new optional fields with defaults, and new message types. Old glasses ↔ new phone, and the reverse, must both still parse.
- **App version:** `versionName "1.0.0"`, `versionCode 2` on `:phone` and `:glasses`. Tag `v1-beta` already exists at `b2fc43a`; don't move it.
- **Nomic transforms:**
  - **text:** mean-pool (768) → LayerNorm over all 768 (no affine, eps 1e-5) → first 256 → L2;
  - **vision:** CLS (768) → L2 → first D → L2;
  - D is 256 or 768, decided by the photo acceptance test (spec §F.5);
  - **prefixes:** `search_document: ` / `search_query: `.
- **Query embedding:** once at 768, then shortened for each index. Text and image scores are never compared.
- **Keyframe:** a 640 px wide JPEG at q75 (requested via `ResolutionSelector`, 4:3, landscape after rotation). The 160 px grey thumbnail is internal to the glasses: never stored, never sent.
- **Motion-driven camera (spec §P1.1, revision 5):** the IMU runs always. The camera is open while moving and for the hold time H after motion, opening early on deceleration (gyro below 60 % of the peak); it's closed when settled, with a brief open → first usable frame → close check every 5 s (15 s after 10 quiet minutes). 3 Hz analysis while open. A dwell (≥ 1.0 s still) gives an immediate keyframe.
- **Quick look** (`HudSettings.quickLookMs` ∈ {1000, 1500, 2000, 3000}, **default 1000** (probe-confirmed)): H = 4 s / 2 s / 1 s / 0 (bursts every 2 s while moving). Battery Saver or battery ≤ 20 %: checks every 10 s, H halved. The values are confirmed by the Quick-look probe (spec Probe results 2).
- **Camera LED:** never disabled, suppressed or worked around; it's reduced only by opening the camera less.
- **Keyframe test:** the smaller Hamming distance of dHash and pHash must be > 10 against each of the last 5 keyframes; ≥ 1.2 s between keyframes; the blur floor uses Laplacian variance; the sharpest of each ≤ 1.5 s window while moving.
- **Heat:**
  - MODERATE or battery ≥ 40 °C → rates halved;
  - SEVERE+ or battery ≥ 44 °C → stop, with the "Cooling" HUD icon;
  - resume below MODERATE and battery ≤ 38 °C for 2 min;
  - `getThermalHeadroom` is NaN on these glasses: record it, never depend on it.
- **Work/rest:** W ∈ {30, 40, 45, 50, 60, 75, 90} min (default 45); R ∈ {5, 10, 15, 20} min (default 15); **if W > 45 then R ≥ 15** (enforced in the phone UI, and clamped again on the glasses for safety). During rest, a Fixed-mode capture every 60 s.
- **Camera LED rule test:** no torch, flash or LED control anywhere (`enableTorch`, `FLASH_MODE`, `/sys/class/leds`, vendor LED APIs).
- **Phone power (spec §P2.5):** no work/rest on the phone. In the background, image vectors are batched every 60 s; at thermal ≥ MODERATE, background work pauses (non-user extraction ≤ 1 per 30 s); at ≥ SEVERE, all background processing pauses until below MODERATE for 2 min.
- **Not worn:** proximity "far" for > 5 s.
- **Camera LED:** never disabled or worked around.
- **Budget:**
  - a token bucket of 1 per 12 s (setting 10 / 12 / 15 s), burst 2;
  - 1 in 5 tokens reserved for nudges (released after 60 s unused);
  - a due nudge borrows the next extraction token;
  - caption-queue and backfill calls never take nudge tokens;
  - user-started calls bypass the bucket but count towards it.
- **Batching:** ≤ 3 photos per VISION_EXTRACT; dwell first, then newest. A backlog > 30 goes to cluster mode (P4, wave 2); until then the excess waits.
- **Nudge evaluation:**
  - eligible: memories from salient or dwell keyframes, or a new place;
  - settle window 5 s (setting 3 / 5 / 10 / 15), gap between checks ≥ 30 s (setting 30 / 60 / 120);
  - notify throttle: the shown-nudge gap (today's 30 min; setting 10 / 30 / 60 min) plus the daily cap;
  - target p50 ≤ 15 s and p95 ≤ 45 s, when the provider's p95 ≤ 8 s and the keyframe is at the phone within 3 s.
- **Claims:** `EXTRACTING` sets `claimed_at` and a random `claim_id`; a claim older than 5 min is abandoned. The commit is one transaction, starting with the CAS `UPDATE frame_state … WHERE frame_id=? AND state='EXTRACTING' AND claim_id=?`; zero rows → roll back and drop the output. 5 attempts → `FAILED_FINAL`.
- **Repo rules:**
  - Gradle: `./gradlew --no-daemon`; never `gradlew --stop`; one Gradle run per worktree.
  - Never print secrets or read `.env` (copy it without printing). Never push.
  - Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
  - Logs carry ids and counts only, never user text, captions or paths.
  - Every logcat read is filtered with `grep -v -i -E "token|AuthorizationHelper"`.
  - Submodule: plain `git submodule update --init -- android-companion/services/ai/src/main/cpp/llama.cpp`, **never `--reference`**.
  - Known flaky tests (one re-run allowed): PromptEditorTest, DeleteAllFenceTest, PushToTalkTest, AppShellTest.leavingHome…, LocalModelsPageTest.
- **Full gate for each stream:** `tools/gate.sh <log>` (`test :phone:assembleDebug :glasses:assembleDebug`). It must be BUILD SUCCESSFUL with 0 failures before handing back.

## Review Focus

1. **Version skew:** new phone + old glasses, or old phone + new glasses. A `pc_frame` without the new fields is ingested as `motion=UNKNOWN`, not dwell; old `HudSettings` without `captureMode` mean Fixed on new glasses. Owned by T0: `oldFrameMetaParsesWithDefaults`, `oldHudSettingsMeanFixedMode`.
2. **Upgrading an install with v1 data:** inbox frames and queued 24 h jobs that exist before v2. They are backfilled into `frame_state` as PENDING on first start after the migration, without duplicating already-extracted frames. Owned by P1: `preV2InboxFramesAreAdoptedOnce`.
3. **Switching Smart → Fixed (or entering rest) while a session holds the camera:** there's exactly one camera owner at any instant, and no "camera in use" error. Owned by G3: `modeSwitchReleasesSessionBeforeFixedCapture`.
4. **Forgetting one memory from a 3-photo batch whose frames are shared with another memory:** that memory's `frame_source` rows go and its frames are tombstoned at once (no re-extraction); the photo stays while another memory cites it. Owned by P3: `forgetSharedFrameKeepsOtherMemory`.
5. **Clock skew between the glasses and the phone** (seconds to minutes): latency stats and caption aging use phone-receive time plus the existing clock offset, never raw glasses time. Owned by P5: `latencyUsesOffsetCorrectedCaptureTime`.

---

## T0 (controller, before the streams): shared protocol + versions

**Files:** `core/model/src/main/kotlin/com/rokid/context/core/model/Protocol.kt`; `phone/build.gradle.kts`, `glasses/build.gradle.kts` (versionName/versionCode); tests in `core/model/src/test/...`.

**Interfaces (Produces):**
```kotlin
enum class CaptureMode { SMART, FIXED }
enum class MotionState { STILL, HEAD_MOVING, WALKING, VEHICLE, UNKNOWN }
// HudSettings, new fields with defaults (add after existing fields):
val captureMode: CaptureMode = CaptureMode.FIXED      // the phone sends SMART for new installs and the migration default
val pauseWhenNotWorn: Boolean = true
val workMinutes: Int = 45                              // 30 | 40 | 45 | 50 | 60 | 75 | 90
val quickLookMs: Int = 1000                            // 1000 | 1500 | 2000 | 3000
val restMinutes: Int = 15                              // 5 | 10 | 15 | 20; ≥ 15 when workMinutes > 45
// pc_frame metadata (the existing frame meta class), new optional fields:
val motion: MotionState = MotionState.UNKNOWN
val dwell: Boolean = false
val blur: Float? = null
val dhash: String? = null; val phash: String? = null   // 16 hex chars
val width: Int? = null; val sampleSeq: Long? = null; val sessionId: String? = null
// new message (glasses → phone, P3 hook, defined now and unused in wave 1):
@Serializable data class MotionHint(val state: MotionState, val atMs: Long)  // channel "pc_motion_hint"
```
- `burstOnChange` stays in `HudSettings`, documented as ignored.

**Tests:** `oldFrameMetaParsesWithDefaults`, `oldHudSettingsMeanFixedMode`, `newFieldsRoundTrip`, `unknownEnumValueFallsBackToUnknown` (an unknown `motion` string → UNKNOWN, using the existing lenient Json).

- [ ] Write the tests → RED → add the fields and versions → GREEN with `./gradlew --no-daemon :core:model:test`. Commit `feat(protocol): additive capture-mode, work/rest and keyframe metadata fields; app 1.0.0`.

---

## Stream F: foundation (`:services:ai`, `:services:memory` interface only)

### Task F1: `LanguageModel` seam
**Files:** create `services/ai/src/main/java/com/rokid/context/model/LanguageModel.kt`; modify `services/ai/.../ai/ModelRouter.kt`, `services/ai/.../ai/run/RoleRunner.kt` (route through the seam).

**Interfaces (Produces):**
```kotlin
enum class CallClass { USER, EXTRACT, NUDGE, CAPTION_QUEUE, BACKFILL, OTHER }
interface LanguageModel { suspend fun complete(request: ModelRequest, callClass: CallClass = CallClass.OTHER): ModelReply }
interface CallGate { suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T }   // a regular interface: Kotlin forbids type parameters on a fun interface's method
object PassThroughGate : CallGate { override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T) = block() }
```
- `ModelRouter` implements `LanguageModel`. Every provider `complete` call inside it is wrapped in `gate.withPermit(callClass)`, and `gate` is injected (default `PassThroughGate`).
- `RoleRunner` passes a `CallClass`: VISION_EXTRACT → EXTRACT, NUDGE → NUDGE, ANSWER → USER, others → OTHER.

**Tests:** `ModelRouterGateTest`: `everyProviderCallPassesThroughTheGate`, `fallbackRetryWithoutImagesIsAlsoGated` (`ModelRouter.kt:127-132` retries), `defaultGateChangesNothing`. All existing role tests pass unchanged.

- [ ] RED → implement → GREEN (`:services:ai:test`). Commit `feat(ai): LanguageModel seam with an injectable CallGate over ModelRouter`.

### Task F2: `EmbeddingService` + Nomic text transform
**Files:**
- modify `services/memory/.../memory/Embedder.kt`: add `EmbeddingService`, and keep `Embedder` as `typealias Embedder = EmbeddingService` only if every call site compiles; otherwise add a one-line adapter;
- modify `services/ai/.../model/provider/LlamaCppNativeProvider.kt` and `services/ai/src/main/cpp/llama_jni.cpp` (a `normalize` flag);
- create `services/ai/.../ai/local/NomicText.kt`.

**Interfaces (Produces):**
```kotlin
enum class Modality { TEXT, IMAGE }
interface EmbeddingService {
    val fingerprint: String
    val modalities: Set<Modality>
    suspend fun embedDocuments(texts: List<String>): List<FloatArray>
    suspend fun embedQuery(text: String): FloatArray
    suspend fun embedQueryFull(text: String): FloatArray = embedQuery(text)  // 768 for Nomic; for P5 shortening per index
    suspend fun embedImages(jpegs: List<ByteArray>): List<FloatArray> = throw UnsupportedOperationException("text-only space")
}
object NomicText {
    const val DOC = "search_document: "; const val QUERY = "search_query: "
    fun layerNorm(v: FloatArray, eps: Float = 1e-5f): FloatArray
    fun shorten(v: FloatArray, dims: Int): FloatArray   // take the first dims → L2
    fun fingerprint(dims: Int): String                   // "nomic-embed-text-v1.5|mean|ln|d=$dims|l2"
}
```
- JNI: change `nativeEmbed(handle, text)` to `nativeEmbed(handle, text, normalize: Boolean)`. bge keeps `true`; Nomic passes `false`, then `layerNorm` → `shorten(256)`.
- The fingerprint includes family, dimension and normalisation, so bge and Nomic spaces never mix.

**Tests:**
- `NomicTextTest`: `layerNormMatchesReference` (a fixed 8-dim vector, expected values computed by hand in the test with the formula), `shortenRenormalises`, `prefixesApplied`, `fingerprintDiffersByDims`.
- `EmbeddingServiceTest`: `textOnlyServiceRejectsImages`.
- `LlamaCppNativeProviderTest`: `normalizeFlagPassedThrough` (injectable natives seam).
- Native build: `./gradlew --no-daemon :services:ai:assembleDebug` still produces `libllama_jni.so`.

- [ ] RED → implement → GREEN. Commit `feat(embed): EmbeddingService and the Nomic text transform (LayerNorm → 256 → L2)`.

### Task F3: Nomic vision on ONNX Runtime
**Files:**
- create `services/ai/.../ai/local/NomicVision.kt` and `services/ai/.../ai/local/OnnxImageEmbedder.kt`;
- modify `services/ai/build.gradle.kts` (dependency `com.microsoft.onnxruntime:onnxruntime-android:<latest stable>`, arm64 only through the existing `abiFilters`).

**Interfaces (Produces):**
```kotlin
object NomicVision {
    const val INPUT = 224
    fun preprocess(jpeg: ByteArray, cfg: PreprocessConfig): FloatArray   // EXACTLY the pinned processor config's order (resize → crop as the config says), resample mode, rescale, mean/std, RGB, NCHW
    data class PreprocessConfig(/* copied verbatim from preprocessor_config.json at the pinned commit */ val resize: Int, val crop: Int, val resample: String, val mean: FloatArray, val std: FloatArray, val rescale: Float)
    fun fingerprint(dims: Int): String                 // "nomic-embed-vision-v1.5|cls|l2|d=$dims|l2"
}
class OnnxImageEmbedder(modelPath: String, private val dims: Int) : AutoCloseable {
    suspend fun embed(jpegs: List<ByteArray>): List<FloatArray>   // CLS → L2 → shorten(dims) → L2; batches of 8; Dispatchers.Default
}
```
- **Preprocessing parity (review fix 6):** download `preprocessor_config.json` at the same pinned commit as the ONNX file, and commit it as a test resource. `PreprocessConfig` is built from it, and the reference script uses the same processor class at the same commit. The current official config resizes to 224×224 before cropping, so don't assume crop-then-resize. Reference images are **rectangular** (640×480 and 480×640) so that aspect handling is exercised.
- **16 KB alignment:** check with `zipalign -c -P 16 -v 4` on the built phone APK, and with `llvm-readelf -l` on the extracted `libonnxruntime.so`: the LOAD segment's `p_align` must be ≥ 0x4000. If the latest stable ORT AAR fails, pick the newest version that passes and record it in the report. If none passes, stop and report: don't ship a misaligned `.so`.
- One retry on session creation failure. Then mark it unavailable for the process (a `ProviderConfigException` with a safe message), with a count-only log.

**Tests:**
- `NomicVisionTest`: `preprocessShapeAndRange` (a fixed 640×480 test JPEG gives 3×224×224 in the expected range), `followsPinnedConfigOrder`, `matchesPythonPixelsForRectangularImage` (pixel tensor within 1e-3 of the script's dumped tensor, both orientations).
- `OnnxImageEmbedderTest` (Robolectric, fake session seam): `batchesOfEight`, `failureAfterRetryMarksUnavailable`.
- Device: `androidTest` `NomicVisionDeviceTest.throughputAtLeast20PerSecond`. It's run by the controller on the phone, and skipped when the model isn't downloaded.

- [ ] RED → implement → GREEN, plus the alignment check output in the report. Commit `feat(embed): Nomic vision v1.5 on ONNX Runtime (arm64, 16 KB aligned)`.

### Task F4: catalog, download and reference vectors
**Files:** `services/ai/.../ai/local/LocalModels.kt` (catalog); test resources `services/ai/src/test/resources/nomic/reference.json`; `tools/nomic_reference.py` (new, run by the implementer once).

**Behaviour:**
- **Catalog:** add `nomic-embed-v1.5`, a two-file bundle. Text: `nomic-ai/nomic-embed-text-v1.5-GGUF`, file `nomic-embed-text-v1.5.Q8_0.gguf`. Vision: `nomic-ai/nomic-embed-vision-v1.5`, file `onnx/model_quantized.onnx` if it exists at the pinned commit, else `onnx/model.onnx`.
  - Pin each URL to a **commit SHA** (`https://huggingface.co/<repo>/resolve/<commit>/<file>`). Take the commit from `https://huggingface.co/api/models/<repo>` (`sha`). Size and SHA-256 come from downloading once into the scratchpad.
  - Licence "Apache-2.0" for both; record the source links in the report.
  - bge-small stays listed. Nomic becomes "Recommended".
- **Download:** the existing `LocalModelDownloadWorker` handles each file (https-only redirects, Range resume, SHA verify). If the `LocalModel` shape can't express two files, add `files: List<ModelFile>` with a default single-file list built from the existing fields, so existing callers are unchanged.
- **Reference vectors:**
  - `tools/nomic_reference.py` uses `sentence-transformers` / `transformers` at the same commits. It writes 10 texts (document and query prefixed) and 5 test images (committed under `services/ai/src/test/resources/nomic/img*.jpg`; use synthetic or public-domain images, never user photos), with their 768-dim outputs before shortening.
  - Run it in a venv in the scratchpad. Commit only the JSON and images.

**Tests:**
- `LocalModelsTest`: `nomicPinnedToCommitAndSha`, `bundleHasTextAndVisionFiles`.
- `NomicReferenceDeviceTest` (`androidTest`, controller-run): cosine ≥ 0.99 against `reference.json` for every text and image, after applying the same transforms.

- [ ] Implement → GREEN (JVM). The device test runs in the device session. Commit `feat(models): Nomic Embed v1.5 bundle pinned by commit, with reference vectors`.

---

## Stream C: database v2 (`:services:memory`)

### Task C1: Migration(1, 2) and the 5 tables
**Files:**
- create `services/memory/.../memory/db/FrameEntities.kt` and `services/memory/.../memory/db/FrameDaos.kt`;
- modify `services/memory/.../memory/db/ContextDatabase.kt` (`version = 2`, entities, DAOs, `MIGRATION_1_2`), and add the exported schema `services/memory/schemas/.../2.json`;
- modify `services/memory/.../memory/db/DatabaseFactory.kt`: **both `build()` and `buildDeferred()` call `.addMigrations(MIGRATION_1_2)`** (review fix 1); no destructive fallback;
- modify `services/memory/.../memory/db/WipeDao.kt` (Delete all clears the frame tables; notes and shopping are kept);
- modify `services/memory/.../memory/forget/ForgetService.kt` (the `frame_source` walk).

**Interfaces (Produces):**
```kotlin
@Entity(tableName = "frame_meta", indices = [Index(value = ["wireFrameId"], unique = true)]) data class FrameMetaEntity(@PrimaryKey val frameId: String, val wireFrameId: String?, val sessionId: String?, val motion: String, val dwell: Boolean,
    val blur: Float?, val dhash: String?, val phash: String?, val width: Int?, val salience: Float?, val salienceTerms: String?,
    val clusterId: String?, val nearDupOf: String?, val imageVectorSpace: String?, val createdAt: Long)
enum class FrameState { PENDING, EXTRACTING, EXTRACTED, CLUSTERED, NEAR_DUP, FAILED, FAILED_FINAL }
@Entity(tableName = "frame_state", indices = [Index("state")]) data class FrameStateEntity(@PrimaryKey val frameId: String, val state: String,
    val attempts: Int, val lastError: String?, val claimedAt: Long?, val claimId: String?, val updatedAt: Long)
@Entity(tableName = "frame_source", primaryKeys = ["memoryId", "frameId"], indices = [Index("frameId")])
data class FrameSourceEntity(val memoryId: String, val frameId: String, val clusterId: String?, val role: String) // FACT | CLUSTER_REP | CLUSTER_MEMBER
@Entity(tableName = "frame_cluster") data class FrameClusterEntity(@PrimaryKey val clusterId: String, val openedAt: Long, val closedAt: Long?,
    val closeReason: String?, val repFrameIds: String, val memoryId: String?)
@Entity(tableName = "frame_vector", primaryKeys = ["frameId", "spaceId"], indices = [Index("spaceId")])
class FrameVectorEntity(val frameId: String, val spaceId: String, val blob: ByteArray) // equals/hashCode as MemoryVectorEntity

@Dao abstract class FrameStateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insertIgnore(rows: List<FrameStateEntity>)
    /** PENDING/FAILED(attempts<5)/abandoned EXTRACTING → EXTRACTING; returns the rows it claimed. */
    @Transaction open suspend fun claim(frameIds: List<String>, claimId: String, nowMs: Long, abandonBeforeMs: Long): List<FrameStateEntity>
    /** The CAS; returns true when exactly the claimed rows moved to [to]. Used inside the caller's transaction. */
    abstract suspend fun completeIfClaimed(frameId: String, claimId: String, to: String, nowMs: Long): Int
    abstract suspend fun fail(frameId: String, claimId: String, error: String, nowMs: Long, maxAttempts: Int = 5): Int  // → FAILED or FAILED_FINAL
    abstract suspend fun recoverable(nowMs: Long, abandonBeforeMs: Long, limit: Int): List<FrameStateEntity>
    abstract fun counts(): Flow<Map<String, Int>>   // per state, for Diagnostics
    abstract suspend fun retryAllFinal(nowMs: Long): Int
}
@Dao interface FrameMetaDao { upsert(row); get(frameId); } 
@Dao interface FrameSourceDao { insertAll(rows); forMemory(memoryId); memoriesForFrame(frameId); deleteForMemory(memoryId) }
@Dao interface FrameVectorDao { upsert(rows); forSpace(spaceId, limit, offset); deleteSpace(spaceId); countForSpace(spaceId) }
@Dao interface FrameClusterDao { upsert(row); open(): FrameClusterEntity? }   // used in wave 2
```
- **The migration** creates the 5 tables with SQL identical to Room's generated schema (copy the statements from the exported `2.json`). Nothing else.
- **Forget (review fix 3):** forgetting a memory **immediately tombstones every frame it cites** (today's extraction lock, unchanged), so no forgotten fact can be re-extracted, even if other memories still cite the frame. Then delete its `frame_source` rows. The **photo file** is deleted only when no surviving memory cites the frame; until then it's kept for those memories. The existing `memory_source` walk is unchanged.

**Tests:**
- `Migration1To2Test` (`MigrationTestHelper`, Robolectric): `v1RowsSurvive` (seed every v1 table with one row), `fiveTablesExistAfterMigration`, `noV1TableChanged` (schema diff of v1 tables = none).
- `DatabaseFactoryUpgradeTest`: `buildUpgradesV1File` and `buildDeferredUpgradesV1File`. Create a v1 database file, then open it through the **production** builders (plain and SQLCipher keyed) and assert the v1 rows plus the 5 new tables.
- `SqlCipherMigrationDeviceTest` (extend the existing test): `encryptedDbMigratesTo2`.
- `FrameStateDaoTest`: `claimTakesPendingAndAbandoned`, `casRejectsStaleClaim` (two overlapping claims: the old claim's `completeIfClaimed` returns 0), `fifthFailureIsFinal`, `retryAllFinalResets`.
- `ForgetFrameSourceTest`: `forgetSharedFrameKeepsOtherMemory` (Review Focus 4: the other memory and the photo stay, **the frame is tombstoned at once**), `forgottenFactNotReextracted`, `forgetLastCiterDeletesPhoto`.
- `WipeDaoTest`: `deleteAllClearsFrameTablesKeepsNotes`.

- [ ] RED → implement → GREEN (`:services:memory:test`). Commit `feat(db): Room v2, additive frame tables (meta, state, source, cluster, vector)`.

### Task C2: space swap with a 7-day rollback and recall-loss counts
**Files:** `services/memory/.../memory/db/EmbeddingDao.kt` (`swap`, a new `rollback`, `purgeRetired`); the retirement purge worker in `services/orchestration/.../IndexMaintenance.kt`; the phone On-device models page (Roll back button plus the confirmation dialog); `MemoryEntities.kt` only if `EmbeddingSpaceEntity` lacks a `retiredAt` → **don't add a column**: store `retiredAt` in a `space_retired_at` SharedPreferences map keyed by space id.

**Behaviour:**
- `swap`: the old space → RETIRED, **keeping** its `memory_vector` and `frame_vector` rows; `retiredAt = now`.
- **Coverage:** 100 % of live memories. **In wave 1, image coverage isn't required**, because `frame_vector` backfill ships in wave 2 (review fix 2). The `frame_vector` coverage requirement (100 % of frames with a stored photo) is behind a flag `requireImageCoverage` (false in wave 1) and is switched on in wave 2 with its backfill.
- `rollback(retiredId)`: allowed within 7 days. The retired space → ACTIVE, and the current one → RETIRED (kept).
- `purgeRetired(now)`: deletes the vectors of spaces retired more than 7 days ago. It's a daily job.
- **Dialog before the swap:**
  - `N` = deleted-photo frames with a caption or a surviving `frame_source`/cluster memory;
  - `M` = deleted-photo frames with neither;
  - copy, verbatim from the spec: "N photos will be searchable by text only", "M photos will no longer be findable at all", "Both can be recovered with Roll back for 7 days."

**Tests:**
- `EmbeddingSwapTest`: update the existing swap tests, plus `swapKeepsRetiredVectors`, `rollbackWithin7Days`, `rollbackRefusedAfter7Days`, `purgeAfter7Days`, `coverageIgnoresDeletedPhotos`, `wave1SwapNeedsTextCoverageOnly`.
- `SwapDialogCountsTest`: `countsReducedVsLost`.
- Robolectric `LocalModelsPageTest`: `rollBackShownOnlyInGrace`.

- [ ] RED → implement → GREEN. Commit `feat(embed): space swap keeps retired vectors 7 days with Roll back; recall-loss counts`.

---

## Stream G: glasses capture (`:glasses`)

### Task G1: `MotionClassifier` + wear check
**Files:** create `glasses/.../capture/motion/MotionClassifier.kt`, `glasses/.../capture/motion/WearDetector.kt`, `glasses/.../capture/motion/SensorFeed.kt` (registration; fakeable).

**Interfaces (Produces):**
```kotlin
class MotionClassifier(private val clock: () -> Long) {
    fun onGyro(x: Float, y: Float, z: Float, tMs: Long)
    fun onLinearAccel(x: Float, y: Float, z: Float, tMs: Long)
    val state: StateFlow<MotionState>                 // 1 s sliding window; HEAD_MOVING if |gyro| > 0.6 rad/s; WALKING if var(linacc) > 0.5 and a 1.5–2.5 Hz periodicity; else STILL
    val dwell: SharedFlow<Long>                       // emits tMs when moving → STILL held ≥ 1000 ms
    val onset: SharedFlow<Long>                       // emits on STILL → moving
}
class WearDetector(clock: () -> Long) { fun onProximity(near: Boolean, tMs: Long); val worn: StateFlow<Boolean> }  // far > 5 s → false; near → true at once
interface SensorFeed { fun start(c: MotionClassifier, w: WearDetector); fun stop() }  // SENSOR_DELAY_GAME, maxReportLatency 1 s, non-wakeup sensors
```

**Tests:** `MotionClassifierTest`: `stillTrace`, `headTurnTrace`, `walkingTraceAt2Hz`, `dwellAfterOneSecondStill`, `noDwellWithoutPriorMotion`, `onsetEmitsOnce`. `WearDetectorTest`: `farFor5sMeansNotWorn`, `briefFarIgnored`, `nearRestoresAtOnce`. Use synthetic traces generated in the test.

- [ ] RED → implement → GREEN (`:glasses:testDebugUnitTest`). Commit `feat(glasses): motion classifier (gyro + linear accel) and proximity wear check`.

### Task G2: `KeyframeGate` (replaces FrameGuard's hash step)
**Files:** create `glasses/.../capture/keyframe/KeyframeGate.kt`, `glasses/.../capture/keyframe/ImageHashes.kt`; modify `glasses/.../capture/FrameGuard.kt` (keep the dark and battery skips; the hash/throttle step moves to the gate).

**Interfaces (Produces):**
```kotlin
class Thumb(val w: Int, val h: Int, val luma: ByteArray)          // 160 px wide grey, built from the Y plane
object ImageHashes { fun dHash(t: Thumb): Long; fun pHash(t: Thumb): Long; fun blur(t: Thumb): Float /* Laplacian variance */ }
class Candidate(val seq: Long, val thumb: Thumb, val pixels: Nv21Frame)   // pixels = a copy of the camera frame (640×480 NV21, ≈ 460 KB), retained while it's a window candidate
class Nv21Frame(val w: Int, val h: Int, val rotation: Int, val data: ByteArray)
sealed interface GateResult { data class Keep(val candidate: Candidate, val dhash: Long, val phash: Long, val blur: Float, val dwell: Boolean) : GateResult
    data class Skip(val reason: String /* BLUR | DUPLICATE | THROTTLED | WINDOW */) : GateResult }
class KeyframeGate(private val store: GateStore, private val clock: () -> Long) {
    fun offer(c: Candidate, motion: MotionState, dwellArmed: Boolean): GateResult   // handles the sharpest-of-window logic internally while moving; keeps ≤ 3 candidates, releasing losers at once
    fun flushWindow(): GateResult?                                            // at window end (≤ 1.5 s): best candidate or null
}
interface GateStore { fun lastKeyframes(): List<Pair<Long, Long>>; fun push(d: Long, p: Long); fun lastKeptAt(): Long; fun setLastKeptAt(t: Long) } // SharedPreferences "capture_guard", ring of 5
```
- Rules from the Global Constraints. **Dwell exception:** compare against the last keyframe only, but still require blur ≥ floor. The blur floor is a constant (`BLUR_FLOOR = 60f`), tuned in the device run.

**Tests:**
- `ImageHashesTest`: `identicalThumbsDistance0`, `shiftedThumbSmallDistance`, `differentSceneLargeDistance`, `blurLowerForBlurred`.
- `KeyframeGateTest`: `abaPingPongSuppressed` (A, B, A → A kept, B kept, second A skipped), `throttle1200ms`, `sharpestOfWindowWins`, `dwellBypassesLastFive`, `blurryDwellStillSkipped`, `keepCarriesWinningCandidatePixels` (the winner's seq and pixels are those of the sharpest thumb), `losersReleased`.
- **Glimpse-offset test:** `GlimpseOffsetTest`. Synthetic scene timelines at 3 Hz moving / 1 Hz still analysis; looks of 1.0 / 1.5 / 2.0 s at 10 phase offsets × each motion state. Assert 100 % capture at ≥ 1.5 s, and that a change in front of a still head is caught within ≤ 5 s at the slowest still rate. The 1.0 s rate is printed only.

- [ ] RED → implement → GREEN. Commit `feat(glasses): keyframe gate (dHash + pHash vs last 5, blur, window, dwell)`.

### Task G3: bound sampling session
**Files:** create `glasses/.../capture/session/SamplingSession.kt`, `glasses/.../capture/session/AnalysisRate.kt`, `glasses/.../capture/session/CameraOwner.kt`; modify `glasses/.../capture/CaptureService.kt` (mode switch), `CaptureScheduler.kt` / `CaptureAlarmReceiver.kt` (Fixed mode + watchdog only), `CaptureCadence.kt` (Fixed only), and `GlassesRuntime.kt` (buffer keyframes with metadata).

**Interfaces (Produces):**
```kotlin
object CameraPolicy {   // pure function of motion, time and settings → what the camera should do now
    data class Decision(val open: Boolean, val analysisIntervalMs: Long, val nextBriefCheckAtMs: Long?)
    fun decide(state: MotionState, decelerating: Boolean, msSinceMotionEnd: Long?, msSinceKeyframe: Long, quickLookMs: Int,
               lowPower: Boolean, heatSlow: Boolean, nowMs: Long, lastBriefCheckAtMs: Long): Decision
}
class CameraOwner { suspend fun <T> acquire(owner: String, block: suspend () -> T): T; val current: String? }  // a Mutex: exactly one owner ("session" | "fixed")
class SamplingSession(/* camera provider, CameraOwner, MotionClassifier, WearDetector, KeyframeGate, HeatGuard, WorkRest, sink: (KeyframeOut) -> Unit, clock */) {
    fun start(); fun stop(reason: String); val status: StateFlow<SessionStatus>
}
data class KeyframeOut(val jpeg: ByteArray, val width: Int, val motion: MotionState, val dwell: Boolean, val blur: Float,
    val dhash: String, val phash: String, val sampleSeq: Long, val sessionId: String)
data class SessionStatus(val running: Boolean, val motion: MotionState, val keyframesLast10Min: Int, val restingUntilMs: Long?, val reason: String?)
```
- **Camera open/close** follows `CameraPolicy.decide`, evaluated on every motion change and every 250 ms. Brief checks open → take the first frame above the blur floor (or give up after 1.5 s) → close.
- **Camera API:** CameraX (probe: about 300 ms cold p50), kept behind `CameraOwner` so it can be swapped. Tune H and the deceleration start so `GlimpseOffsetTest` reaches ≥ 95 % for 1 s looks at the 1 s setting (the probe got 80 %: misses came from the 5 s settled check).
- **Winning frame (review fix 5):** each analysed frame is copied into a `Candidate` (thumb plus NV21 pixels) before the `ImageProxy` is closed. `Keep` returns the winning `Candidate`, and G3 encodes **that candidate's pixels**, never the current frame.
- **CameraX (or the Camera2 equivalent):**
  - `ImageAnalysis` with `ResolutionSelector(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY, ResolutionStrategy(Size(640, 480), FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))` and `STRATEGY_KEEP_ONLY_LATEST`;
  - rotation applied before encoding, so the **output width is 640** (assert it: if it isn't 640 after rotation, scale to 640 wide and log the count);
  - the thumbnail comes from **Y-plane subsampling** (no JPEG, target ≤ 30 ms per sample);
  - JPEG q75 only on `Keep`.
- **Start conditions:** HUD visible (the Android 12 rule), worn, not paused, `captureMode == SMART`, heat and work/rest allow it.
- **Immediate checks:** `onset` triggers one; `dwell` triggers a forced `offer(dwellArmed = true)`.
- **Mode switch or rest:** `stop()` releases the `CameraOwner` before any Fixed capture runs.
- **Outbound buffer full:** drop the oldest non-dwell keyframe first (modify `OutboundBuffer.kt`).
- **Camera bind fails 3×:** fall back to Fixed at 30 s for the session, with a "capture degraded" HUD mark.
- **"Capture now"** from the phone forces one keyframe, bypassing the change test (blur floor still applies; debug `skipDedupe` stays debug-only).
- **`pauseWhenNotWorn = false`:** the wear check is ignored.
- **Rest periods** (from G4): `stop("rest")`, then one Fixed capture every 60 s through `CameraOwner` until rest ends.

**Tests:**
- `CameraPolicyTest`: `openWhileMoving`, `holdPerQuickLook` (4/2/1/0 s), `opensEarlyOnDeceleration`, `closedWhenSettled`, `briefCheckEvery5sThen15s`, `burstModeAt3s`, `lowPowerHalvesHold`.
- `SamplingSessionTest` (fakes for camera, sensors, gate, clock): `startsOnlyWhenAllConditionsHold`, `onsetTriggersImmediateCheck`, `dwellForcesKeyframe`, `notWornStopsCamera`, `modeSwitchReleasesSessionBeforeFixedCapture` (Review Focus 3), `bindFailsThriceFallsBackToFixed`, `keyframeWidthIs640`, `captureNowForcesKeyframe`, `emittedJpegMatchesWinningThumb` (decode the emitted JPEG, rebuild its 160 px thumb, and assert dHash distance ≤ 2 to the winning candidate's thumb, never a later frame's), `wearCheckOffKeepsSampling`, `restUsesFixedEvery60s`.
- `OutboundBufferTest`: `dropsOldestNonDwellFirst`. `LedSourceRulesTest` (a source scan like `SettingsSourceRulesTest`): `noTorchFlashOrLedControlInGlassesSources`.

- [ ] RED → implement → GREEN. Commit `feat(glasses): bound sampling session (640 px keyframes, Y-plane thumbnails, single camera owner)`.

### Task G4: `HeatGuard`, `WorkRest` and HUD status
**Files:** create `glasses/.../capture/session/HeatGuard.kt` and `glasses/.../capture/session/WorkRest.kt`; modify the HUD (a "Cooling" icon, "capture degraded" mark) and the status uplink (`SessionStatus` in the existing glasses status message as additive fields, or a debug-only log if no status channel fits; check `GlassesStatus.kt`).

**Interfaces (Produces):**
```kotlin
class HeatGuard(private val read: () -> HeatReading, private val clock: () -> Long) {   // polled every 30 s
    val level: StateFlow<HeatLevel>   // OK | SLOW | STOP ; thresholds from Global Constraints; STOP → OK needs < MODERATE and ≤ 38 °C for 2 min
}
data class HeatReading(val thermalStatus: Int, val headroom: Float /* NaN on this HAL */, val batteryTempC: Float)
enum class HeatLevel { OK, SLOW, STOP }
class WorkRest(private val clock: () -> Long, workMin: () -> Int, restMin: () -> Int) {
    fun onSampling(); fun onPaused(); val resting: StateFlow<Long?>   // restingUntilMs; any pause ≥ R restarts the cycle
}
```

**Tests:** `HeatGuardTest`: `moderateSlows`, `battery40Slows`, `severeStops`, `battery44Stops`, `resumeNeedsTwoMinutesCool`, `nanHeadroomIgnored`. `WorkRestTest`: `restsAfter45`, `honours60and20`, `pauseLongerThanRestResetsCycle`, `clampsRestTo15WhenWorkOver45`, `acceptsAllWorkAndRestOptions`.

- [ ] RED → implement → GREEN; then the stream gate. Commit `feat(glasses): heat guard (thermal status + battery temp) and work/rest cycle`.

---

## Stream P: phone intake (starts after F1 + C1 are merged into the integration branch)

### Task P1: ingest writes `frame_meta` + `frame_state`; adopt pre-v2 frames
**Files:** `phone/.../hub/FrameIngestor.kt`; `services/orchestration/.../data/frames/RokidFrameInbox.kt` (the ingest transaction); create `services/orchestration/.../work/frames/FrameAdoption.kt`.

**Behaviour (review fix 4):**
- **Identity:**
  - `frame_meta.frameId` and `frame_state.frameId` use the **database frame id**: the one derived from the archived file name at ingest, as today, and the one memories already reference.
  - The glasses' `pc_frame` id is stored in a new `frame_meta.wireFrameId` column (unique index; part of the C1 table, so C1 must add it).
  - Dedupe of resends uses `wireFrameId`.
- **Receipt durability, in order:**
  1. the JPEG and a **sidecar** `<name>.meta.json` (the T0 metadata) are written to temp files, fsynced, and renamed into the inbox. The sidecar is renamed first, the JPEG last, so a JPEG never appears without its sidecar.
  2. **Ack the glasses only after step 1.** This matches today's "ack after the durable inbox write".
  3. The existing drain moves the file to the archive and runs **one Room transaction**: the frame row, `frame_meta` from the sidecar (old glasses with no sidecar → UNKNOWN/false), and `frame_state` PENDING. The sidecar is deleted after commit.
- **Crash cases:**
  - after step 1, before the ack: the glasses resend; the phone finds `wireFrameId` already in the inbox or DB and acks without duplicating;
  - after the ack, before step 3: the inbox drain picks the file up on the next run;
  - during step 3: the transaction rolls back and the file goes back to the inbox (existing FAILED path).
- **Adoption:** a one-shot `FrameAdoption.run()` after the migration (a prefs flag `frames_adopted_v2`) inserts PENDING `frame_state` rows (IGNORE) for **(a) inbox files and (b) archived frames already in the DB that are still awaiting extraction** (no extracted memory, no tombstone, including those whose 24 h job expired). Frames already extracted get `EXTRACTED`; tombstoned frames get none.

**Tests:** `FrameIngestorMetaTest`: `sidecarBeforeJpegThenAck`, `ackOnlyAfterDurableWrite`, `metaAndStateInOneTransaction`, `resendAfterCrashDedupesByWireId`, `oldGlassesFrameGetsDefaults`, `dbIdMapsToWireId`. `FrameAdoptionTest`: `preV2InboxFramesAreAdoptedOnce` (Review Focus 2), `archivedAwaitingFramesAdopted`, `extractedFramesNotRequeued`, `tombstonedFramesNotAdopted`.

- [ ] RED → implement → GREEN. Commit `feat(intake): per-frame meta and state at ingest; adopt pre-v2 frames`.

### Task P2: `CallBudget`
**Files:** create `services/orchestration/.../work/budget/CallBudget.kt`; wire it as the `CallGate` for `ModelRouter` in the phone graph (`phone/.../AppGraph` or wherever `ModelRouter` is built: grep `ModelRouter(`).

**Interfaces (Produces):**
```kotlin
class CallBudget(private val clock: () -> Long, private val periodMs: () -> Long /* 10_000 | 12_000 | 15_000 */, private val burst: Int = 2) : CallGate {
    override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T
    // USER: bypasses, counted; NUDGE: a reserved token (1 in 5) or borrows the next EXTRACT token; EXTRACT: an unreserved token;
    // CAPTION_QUEUE, BACKFILL: unreserved only, after EXTRACT waiters; OTHER: treated like EXTRACT.
    // Transport errors (IOException, timeouts) refund the token; model/validation errors don't.
    val stats: StateFlow<BudgetStats>   // calls/hour by class, waiters
}
```

**Tests:** `CallBudgetTest` (virtual time): `ratePerPeriod`, `burstOfTwo`, `userBypassCounts`, `oneInFiveReservedForNudge`, `reservationReleasedAfter60s`, `nudgeBorrowsNextExtractToken`, `captionQueueNeverTakesNudgeToken`, `transportErrorRefunds`, `periodSettingApplies`.

- [ ] RED → implement → GREEN. Commit `feat(budget): token-bucket call budget with nudge reservation and borrowing`.

### Task P3: provenance contract for multi-photo extraction
**Files:** `services/ai/.../ai/contract/DefaultSchemas.kt` (VISION_EXTRACT schema), `services/ai/.../ai/contract/RoleContracts.kt` (parse and validate), `services/ai/.../ai/contract/ContractOutputs.kt`, the VISION_EXTRACT seed prompt (`services/ai/src/main/assets/prompts/seeds/…`), `services/ai/.../prompt/PromptDefaults.kt` (add the old VISION_EXTRACT default hash to `PREVIOUS`, so unedited installs upgrade, as notes did), and the save path that writes memories (grep the VISION_EXTRACT result handling in `QueueDrainWorker.kt`).

**Behaviour:**
- The prompt labels each image `frame_id: <id>`.
- The schema requires `facts[].source_frame_ids` (non-empty array of strings) and `captions[]` with `{frame_id, caption}`.
- **Validator:** any id not in the sent set, or a sent id missing from `captions`, gives `INVALID_OUTPUT`. Batches of more than one photo are then retried one photo per call.
- **Save:** memories plus `frame_source` rows (role FACT; captions become memories as today, with `frame_source` FACT for their frame) plus the `frame_state` CAS, all in **one transaction** (the CAS from C1). CAS 0 rows → roll back and drop.
- **Upgrade:** `SeedAssetsTest` must pin the new hash, and add the old one to `PREVIOUS`.

**Tests:**
- `VisionContractTest`: `requiresSourceIdsAndCaptions`, `unknownIdRejected`, `missingCaptionRejected`, `singlePhotoUsesSameSchema`.
- `ExtractSaveTest`: `batchFallsBackToSingles`, `frameSourceWritten`, `staleClaimCommitDropped` (two attempts; the old one finishes last and its output is dropped).
- `SeedAssetsTest` update.

- [ ] RED → implement → GREEN. Commit `feat(extract): provenance contract (source_frame_ids + per-photo captions) with an atomic claim-checked save`.

### Task P4: `KeyframePipeline` + recovery
**Files:**
- create `services/orchestration/.../work/frames/KeyframePipeline.kt` and `services/orchestration/.../work/frames/FrameRecovery.kt`;
- modify `phone/.../hub/ContextHubService.kt` (own the pipeline), `FrameIngestor.kt` (signal after commit) and `OrchestrationScheduler.kt` (the periodic workers stay as the backstop, calling `FrameRecovery.run`).

**Interfaces (Produces):**
```kotlin
class KeyframePipeline(/* FrameStateDao, FrameMetaDao, extractor: suspend (List<String>, claimId) -> ExtractOutcome, CallBudget via LanguageModel, clock */) {
    fun signal()                      // a new frame committed
    suspend fun drainOnce(): Int      // claims ≤ 3 (dwell first, then newest), extracts, returns frames done
}
object FrameRecovery { suspend fun run(dao: FrameStateDao, nowMs: Long): Int }  // abandoned claims (> 5 min) → PENDING (attempts+1); then signal
```
- **Ask with a backlog:** the newest ≤ 3 frames from the last 60 s are extracted first as `CallClass.USER` (spec §P2.3). Hook into the Ask path (grep `AnswerService`).
- **Diagnostics:** `frame_state` counts, plus "Retry all" for `FAILED_FINAL` (Settings → Diagnostics).

**Tests:** `KeyframePipelineTest`: `dwellFirstThenNewest`, `maxThreePerCall`, `backlogDrainsInOrderUnderBudget`, `killMidBacklogRecoversAll` (simulate a crash between claim and commit), `offline25hNoLoss` (jobs expire, `frame_state` rebuilds them), `askExtractsNewestFirst`. `FrameRecoveryTest`: `abandonedClaimsRequeued`.

- [ ] RED → implement → GREEN. Commit `feat(intake): KeyframePipeline processes on arrival, durable recovery from frame_state`.

### Task P5: `NudgeEvaluator` (evaluate vs notify) + nudge settings
**Files:**
- create `services/orchestration/.../orchestration/nudge/NudgeEvaluator.kt`;
- modify `NudgeWorker.kt` (`NudgeRunGate` keeps the 30-min gap for the periodic backstop only) and the notification path (apply the shown-nudge gap and daily cap there);
- add the phone settings rows in `phone/.../ui/settings/` (the existing Nudges section; grep "Nudge").

**Interfaces (Produces):**
```kotlin
class NudgeEvaluator(private val clock: () -> Long, settleMs: () -> Long, gapMs: () -> Long, private val evaluate: suspend (List<String>) -> NudgeDecision) {
    fun offer(memoryId: String, eligible: Boolean)    // eligible = salient or dwell keyframe, or a new place
    val decisions: SharedFlow<Pair<NudgeDecision, Long>> // with latency
}
```
- **Settings:** settle 3 / 5 / 10 / 15 s (default 5), gap 30 / 60 / 120 s (default 30), shown-nudge gap 10 / 30 / 60 min (default 30), daily cap (expose the existing value).
- A "nudge" decision while throttled is recorded as dropped, with a reason in Diagnostics.
- **Latency:** measured from capture time corrected by the existing clock offset.

**Tests:**
- `NudgeEvaluatorTest`: `settleWindowCoalesces`, `gapEnforced`, `arrivalInGapStartsNextWindowAtGapEnd`, `throttledDecisionRecordedDropped`, `latencyUsesOffsetCorrectedCaptureTime` (Review Focus 5).
- Settings Robolectric: `nudgeTimingRowsPersist`.

- [ ] RED → implement → GREEN. Commit `feat(nudge): immediate evaluation with settle window and gap; notify throttle separate; settings`.

### Task P6: capture settings UI, Diagnostics and the stream gate
**Files:** `phone/.../ui/settings/capture/CaptureSettings.kt`; `phone/.../ui/settings/Diagnostics.kt`; settings inventory doc `docs/superpowers/specs/2026-10-14-settings-inventory.md` (additions).

**Behaviour:**
- **Quick look** 1 / 1.5 / 2 / 3 s (default from the probe), with a one-line explanation: "Shorter catches quicker glances; the camera light is on more."
- **Phone power:** create `services/orchestration/.../work/budget/PhonePowerPolicy.kt` (foreground/background + thermal status → pause flags read by `KeyframePipeline` and the image-vector batching), with `PhonePowerPolicyTest`: `backgroundBatchesVectors60s`, `moderatePausesBackground`, `severePausesAll`, `userAskAlwaysAllowed`.
- **Capture mode** Smart/Fixed (default Smart on this upgrade). **Interval** shown only in Fixed. **Pause when not worn**. **Work** 30/40/45/50/60/75/90 min, **Rest** 5/10/15/20 min; when Work > 45, Rest 5 and 10 are disabled and an existing lower Rest is raised to 15 with a short note. **AI call pace** 10/12/15 s.
- Remove the "Take an extra photo when the scene changes" switch.
- The status line uses `SessionStatus` when available.
- **Diagnostics:** keyframes/h, calls/h by class, backlog by state, p50/p95 latency, dropped-nudge reasons.

**Tests:** `CaptureSettingsTest`: `smartHidesInterval`, `burstSwitchGone`, `workRestOptions`, `restBelow15DisabledWhenWorkOver45`, `pacePersistsToBudget`. `DiagnosticsTest`: `showsBacklogAndLatency`.

- [ ] RED → implement → GREEN → **full gate**. Commit `feat(settings): capture mode, work/rest, call pace, keyframe diagnostics`.

---

## Integration and device session (controller)

1. **Merge order** into `feat/proactive-mvp-implementation`: T0 → F → C → G → P. Run the full gate after each merge; it must be green.
2. **Opus whole-branch review** of the combined diff, against the spec and this plan's Review Focus. Fix Critical/Important findings; record minors in the ledger.
3. **Device session (glasses + phone; the user present but not wearing during installs):**
   - install both builds (`install -r`, keeping data; re-grant overlay appops);
   - check the Room migration on the phone's encrypted DB;
   - download Nomic → `NomicReferenceDeviceTest`, `NomicVisionDeviceTest` → re-index swap dialog → Roll back check;
   - **P1 run:** 10 min desk-still, 10 min walking, glancing at 5 placed objects, then minimised. Record keyframes per minute, battery temperature, and **battery drain unplugged for 30 min**. Tune `BLUR_FLOOR` and the thresholds from the logs.
   - **P2 checks:** latency p50/p95 against the target, the **Doze check** (`dumpsys deviceidle force-idle`, 20 min), and `killMidBacklogRecoversAll` on device (force-stop mid-backlog);
   - **Photo acceptance test (spec §F.5):** ≥ 60 of the user's keyframes, ≥ 40 queries (≥ 15 small/edge objects), recall@3 at 768 vs 256 → decide the image dimension and record it in the decision log.
4. Update the backlog §11, the decision log and the settings inventory, then commit.

## Later waves (separate plans, after this wave's device session)
- **Wave 2:** P4 image index + P6 storage lifecycle (spec §P4, §P6).
- **Wave 3:** P5 image recall + the debug view (spec §P5).
- **Wave 4:** P3 motion hub (spec §P3; copy the LiveFit structure at `../live-fitness-tracker`).
