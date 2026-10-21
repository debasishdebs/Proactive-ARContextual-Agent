# Wave 3: Image Recall and the Small-Fixes Bundle — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (or superpowers:executing-plans). This is a lean plan: it gives exact interfaces, values and test names, not full code. **Implementers are Sonnet; reviews are Opus.** Steps use checkboxes.

**Spec:** `docs/superpowers/specs/2026-10-21-wave3-recall-and-fixes-design.md`. Rulings R1–R17 are there. Read the section named in each task first; where this plan and the spec differ, the spec's rulings win.

**Base:** branch from `int/2026-10-21` **after wave 2 has merged**. The recall tasks need:
- wave-2 Task 4: `ImageSpaces` / `ImageSpaceTarget`;
- wave-2 Task 8: photo expiry, which removes the `frame` row and keeps `frame_meta`, `frame_vector` and the memories;
- wave-2 Task 10: Nomic declares `Modality.IMAGE`.

If one of those hasn't merged yet, build against the interface and use fakes in tests. Never re-implement it.

**Goal:**
- **A (Tasks 1–4):** words → photo search (image vectors plus a caption fallback), a photo-grid screen, describe on demand, and Ask answers that cite photos.
- **B (Tasks 5–9):** the small-fixes bundle.
- **Task 10:** the device checklist.

## Global Constraints

- **Modules:**
  - `:services:memory`: DAO queries only;
  - `:services:orchestration`: recall, describe, AnswerService;
  - `:services:voice`: HUD label;
  - `:services:glasses-link`: none;
  - `:phone`: screens, notification, call guard, banner;
  - `:glasses`: WorkRest, start gate, restart diagnostics;
  - `tools/`: shell.
- **Protocol:** `PROTOCOL_VERSION` stays **1**. Any wire change is additive only, and this wave adds none: the HUD photo label is text inside `Overlay.Answer.text` (spec R5).
- **Room:** **no schema change is planned** (spec R7). Every new query reads existing tables. If a task truly needs one, it takes **the next free version (expected v5 after wave 2's v4; check at merge)**, additive only (`ALTER TABLE … ADD COLUMN` / `CREATE INDEX`). It is registered in both `DatabaseFactory.build()` and `buildDeferred()`, the schema is exported, and it gets a `MigrationTestHelper` test. Stop and tell the controller before doing so.
- **Gradle:** run from `android-companion/` as `/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <tasks>`. It wraps `./gradlew --no-daemon` and allows at most 2 runs per machine.
  - **One Gradle run at a time per worktree.** Never `gradlew --stop`.
  - Per-module test commands look like `…/gw.sh :services:orchestration:testDebugUnitTest --tests '<pattern>'`. The same applies to `:services:memory`, `:services:voice`, `:phone` and `:glasses`; all are Android modules, hence `testDebugUnitTest`.
  - The stream gate before hand-back is `tools/gate.sh <log>` from the repo root. It must show BUILD SUCCESSFUL and 0 failures. Known flaky tests (one re-run allowed): PromptEditorTest, DeleteAllFenceTest, PushToTalkTest, AppShellTest.leavingHome…, LocalModelsPageTest.
- **No adb in code tasks.** Only Task 10 uses devices, and only the controller runs it.
- **Secrets and privacy:**
  - Never print secrets, `.env` values, keys or tokens.
  - Logs carry ids, counts and class names only: never query text, captions, answers, recognised text, file paths, package names of other apps, or raw `ApplicationExitInfo` descriptions.
  - Photos are decrypted **in memory only**, through `FrameDecode.opener` (UI) and `FramePhotos.load` (AI). Never write a decrypted or thumbnail file.
- **Memory ids** start with `rokid_` and never with `rokid_review_`. Describe-on-demand ids are `rokid_desc_<8 hex>_<n>` (facts) and `rokid_desc_<8 hex>_c<n>` (captions).
- **Call classes:** recall's describe and the Ask's photos are **USER**. Nothing in this wave takes EXTRACT or NUDGE tokens.
- **Glasses-side tasks (5 does not apply; 6, 7, 8, 9 do):** the battery **wave A3 branch is also editing glasses code**: `WorkRest.kt` (adds `enabled`), `SamplingSession.kt`, `CaptureService.kt`, `Heartbeat.kt`, `GlassesRuntime.kt`, `CaptureSettings` and `HudSettings`.
  - If A3 has merged into your base, build on it.
  - If not, keep your edits minimal and additive (new parameters with defaults, new files) so the merge is mechanical, and say in the report which A3 files you touched.
- **Commits:** local only, never push. Each message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Submodule: `git submodule update --init -- android-companion/services/ai/src/main/cpp/llama.cpp`, never `--reference`.

## Streams and dependencies

| Stream | Tasks | Needs |
|---|---|---|
| R (recall) | 1 → 2 → 3 → 4 | wave 2 merged (see Base). 2 needs only 1's model types; 3 needs 1; 4 needs 1–3. |
| P (phone fixes) | 5 | none; runs in parallel with R and G |
| G (glasses fixes) | 6 → 7 → 8 → 9 | none; 6 and 8 both touch `GlassesRuntime.kt`, so the stream runs in order. 9 also touches tools and the canvas web assets. |
| D | 10 | all merged, gate green |

---

## Part A — Image recall

### Task 1: `PhotoRecall` — image-vector scan plus caption fallback

**Spec:** §3.1, R1, R2, R3, R9.

**Files:**
- modify `services/memory/src/main/java/com/rokid/context/memory/db/FrameDaos.kt`:
  - `FrameVectorDao.pageDesc`;
  - `FrameSourceDao` / `MemoryDao` lookups. Put them where the sibling queries live; if `memory` lookups live in another DAO file, use that one.
- create in `services/orchestration/src/main/java/com/rokid/context/work/frames/recall/`:
  - `PhotoRecall.kt`;
  - `ImageScan.kt` (pure: no Android, no DB).
- tests:
  - `services/orchestration/src/test/java/com/rokid/context/work/frames/recall/ImageScanTest.kt` (pure JVM);
  - `PhotoRecallTest.kt` (Robolectric, in-memory Room, fake `ImageSpaces` and a fake `MemoryRetriever` seam);
  - extend `services/memory/src/test/.../db/FrameVectorDaoTest.kt` (or create it).

**Interfaces (Produces):**
```kotlin
// :services:memory — rowid keyset, newest first; forgotten frames have no frame_meta, so the JOIN drops them.
data class FrameVectorRow(@ColumnInfo(name = "rowid") val rowid: Long, val frameId: String, val blob: ByteArray)
@Query("SELECT v.rowid AS rowid, v.frameId AS frameId, v.blob AS blob FROM frame_vector v JOIN frame_meta m ON m.frameId = v.frameId " +
       "WHERE v.spaceId = :spaceId AND v.rowid < :beforeRowid ORDER BY v.rowid DESC LIMIT :limit")
suspend fun pageDesc(spaceId: String, beforeRowid: Long, limit: Int): List<FrameVectorRow>   // first page: beforeRowid = Long.MAX_VALUE
// Enrichment (chunk IN lists ≤ 500):
suspend fun describedAmong(frameIds: List<String>): List<String>    // a live (not INVALIDATED) memory with frameId IN, or a frame_source row role IN ('FACT','CLUSTER_REP')
suspend fun newestCaptions(frameIds: List<String>): List<FrameCaptionRow>  // per frame: newest live memory type='caption' → (frameId, memoryId, text, createdAt)

// :services:orchestration, package com.rokid.context.work.frames.recall
enum class PhotoSource { IMAGE, CAPTION }
enum class ImageSearchState { RAN, NO_IMAGE_SPACE, FAILED, TIMED_OUT }
data class PhotoHit(
    val frameId: String, val source: PhotoSource, val score: Double,
    val ts: Long?,                 // frame.ts, else frame_meta.createdAt (expired)
    val photoStored: Boolean,      // false = "Photo expired" (frame row gone; wave-2 Ruling 11)
    val described: Boolean, val captionMemoryId: String?, val captionText: String?, val clusterId: String?,
)
data class RecallResult(val image: List<PhotoHit>, val captions: List<PhotoHit>, val imageState: ImageSearchState, val scanned: Int, val truncated: Boolean)

object ImageScan {
    const val PAGE = 500
    const val MAX_SCAN = 50_000
    const val BUDGET_MS = 2_000L
    /** Cosine (vectors are unit length → dot) of [query] against rows from [pages] (called with the last rowid, newest first) into a bounded heap.
     *  Rows whose decoded size != query.size are skipped. Stops at [maxScan] rows or when [nowMs] passes start + [budgetMs]: truncated = true.
     *  Order: score desc, then frameId asc (same as TopHits). */
    suspend fun topK(query: FloatArray, k: Int, pages: suspend (beforeRowid: Long) -> List<Pair<Long, Pair<String, ByteArray>>>,
                     nowMs: () -> Long, maxScan: Int = MAX_SCAN, budgetMs: Long = BUDGET_MS): ScanResult
    data class ScanResult(val hits: List<Pair<String, Double>>, val scanned: Int, val truncated: Boolean)
}

class PhotoRecall(
    private val db: ContextDatabase,
    private val images: ImageSpaces,                                                     // wave-2 Task 4
    private val captions: suspend (query: String, pool: Int, nowMs: Long) -> List<ScoredMemory>,  // MemoryRetriever.retrieve with topK = pool
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** [imageK] = 0 skips the image scan; [captionK] = 0 skips the fallback. Never throws (except cancellation): failures → FAILED / empty. */
    suspend fun search(query: String, imageK: Int = 24, captionK: Int = 24, budgetMs: Long = ImageScan.BUDGET_MS): RecallResult
    companion object { fun forApp(context: Context): PhotoRecall }   // wires MemoryGraph db, SpaceImageSpaces, AiGraph retriever + RetrievalSettingsStore
}
```

**Behaviour:**
- **Image side:**
  - Take the **first** target from `images.targets()` (the ACTIVE one; wave-2 Task 4 puts ACTIVE first; never read BUILDING). If there is none → `NO_IMAGE_SPACE`.
  - Query vector = `target.embedQueryFull(query)` → first `target.imageDims` → L2, the same transform as `WatchVectors.shorten` (reuse it or `NomicText.shorten` / `VectorMath.l2`).
  - Decode with `VectorCodec.decodeInto` into one reused buffer.
  - A query that is unusable or the wrong size → `FAILED`. Budget passed → `TIMED_OUT` (any partial hits are kept). Work runs on `Dispatchers.Default`.
- **Caption side (R3):**
  - `captions(query, 60, now)`, then keep `memory.frameId != null`;
  - keep the best score per frameId, drop frames in the image list, take `captionK`.
- **Enrichment for both lists:**
  - `PipelineRecords.framesByIds` (or `FrameDao`) gives whether the photo is stored and `ts`;
  - `frame_meta` gives `createdAt` and `clusterId`;
  - `describedAmong` and `newestCaptions`.
  - A frame with no `frame_meta` (forgotten mid-search) is dropped.
- **One log line,** `Log.i("PcRecall", "img=… cap=… scanned=… ms=… state=…")`. Never the query.

**Tests:**
- `ImageScanTest`: `ordersByScoreThenId`, `boundedHeapKeepsTopK`, `skipsWrongDimensionRows`, `stopsAtMaxScanAndFlagsTruncated`, `stopsAtBudgetWithFakeClock`, `pagesNewestFirstUntilShortPage`.
- `FrameVectorDaoTest`: `pageDescNewestFirstBySpace`, `pageDescSkipsFramesWithoutMeta`.
- `PhotoRecallTest`:
  - `imageHitsFromActiveSpaceOnly` (BUILDING vectors ignored);
  - `noImageSpaceReturnsCaptionsOnly`;
  - `captionFallbackDedupesByFrameAndSkipsImageFrames`;
  - `expiredPhotoReturnedWithCaptionAndNotStored` (delete the `frame` row and keep meta, vector and caption);
  - `forgottenFrameNeverReturned`;
  - `describedFlagFromMemoryOrFrameSource`;
  - `clusterIdCarried`;
  - `embedFailureIsFailedNotThrown`;
  - `logHasNoQueryText` (ShadowLog: the query string is absent).

- [ ] RED → implement → GREEN:
  - `…/gw.sh :services:memory:testDebugUnitTest --tests '*FrameVectorDao*'`;
  - `…/gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.work.frames.recall.*'`.
- [ ] Commit `feat(recall): PhotoRecall — brute-force image-vector search with a caption fallback`.

---

### Task 2: Describe on demand

**Needs:** Task 1 (`PhotoHit` only, for the UI later). **Spec:** §3.3, R6.

**Files:**
- create `services/orchestration/src/main/java/com/rokid/context/work/frames/recall/DescribeOnDemand.kt`;
- modify `services/orchestration/src/main/java/com/rokid/context/work/frames/KeyframePipeline.kt` (a public `extractNow`);
- modify `KeyframeGraph.kt`:
  - expose `describe: DescribeOnDemand`;
  - factor the existing `extractor` lambda's body into an internal `KeyframeExtract.run(...)` so the no-claim path reuses it: `ExtractSave.extract` → `saveExtraction` → `ExtractFollowUp` (embeddings and critic as today).
- test `services/orchestration/src/test/.../recall/DescribeOnDemandTest.kt` (Robolectric, in-memory Room, fake `RoleRunner` returning a valid VISION_EXTRACT output, wave-1 test kit for frames).

**Interfaces (Produces):**
```kotlin
sealed interface DescribeResult {
    data class Saved(val memories: Int, val frames: Int) : DescribeResult
    data object Expired : DescribeResult        // every chosen frame has no frame row (or no readable photo: FramePhotos.gone)
    data object Busy : DescribeResult           // every chosen frame is EXTRACTING under someone else's claim
    data object Unavailable : DescribeResult    // offline / no provider / AI failure (ExtractOutcome.classify)
    data object Wiped : DescribeResult
}
class DescribeOnDemand(context: Context, pipeline: KeyframePipeline, db: ContextDatabase, clock: () -> Long) {
    /** 1..3 frames (more → first 3). Runs one at a time (a Mutex); class USER. */
    suspend fun describePhotos(frameIds: List<String>): DescribeResult
    /** The cluster's repFrameIds (≤ 3); none → the medoid by frame_meta, then the newest members, up to 3. */
    suspend fun describeScene(clusterId: String): DescribeResult
}
// KeyframePipeline
/** User-started extraction of exactly [frameIds]: claims via claimForAsk (PENDING, IN_CLUSTER of an OPEN/CLOSED cluster) and claimCaptionQueued,
 *  then the ordinary runBatch(user = true) path (CAS, settle, follow-up). Returns the frame ids it claimed and extracted. */
suspend fun extractNow(frameIds: List<String>, callClass: CallClass = CallClass.USER): Set<String>
```

**Behaviour (R6):**
1. Read `frame_state` for the chosen frames. Frames with no `frame` row → expired (left out). All expired → `Expired`.
2. **Claimable** (PENDING; IN_CLUSTER whose cluster is not SUMMARIZING; CAPTION_QUEUED) → `pipeline.extractNow(those)`.
3. **EXTRACTING** → skipped; if every frame was EXTRACTING → `Busy`.
4. **The rest** (EXTRACTED, CLUSTERED, NEAR_DUP, FAILED, FAILED_FINAL, IN_CLUSTER of a SUMMARIZING cluster, and any frame step 2 failed to claim) → `KeyframeExtract.run(frames, claims = null, CallClass.USER, idPrefix = "rokid_desc_${8 hex}_")`. On `Saved`: `FrameStateDao.move(ids, from, "EXTRACTED", "described_on_demand", now)` for each `from` in NEAR_DUP, CLUSTERED, FAILED, FAILED_FINAL (guarded; 0 rows changed is fine).
5. Map results: any memories saved → `Saved`; a Wiped save → `Wiped`; a `ModelCallException` / offline → `Unavailable`.
6. Log `PcDescribe frames=… claimed=… direct=… saved=… result=…`, counts only.

**Tests:**
- `pendingFrameGoesThroughPipelineClaim` (state EXTRACTED via CAS, no `rokid_desc_` ids);
- `extractedFrameDescribedAgainWithDescIds`;
- `nearDupDescribedMovesToExtracted` (and is no longer an "ordinary skipped photo" by the wave-2 Task 8 query, if present);
- `summarizingClusterMemberUsesDirectPath`;
- `extractingFrameIsBusy`;
- `expiredFrameIsExpired`;
- `wipeDuringCallSavesNothing`;
- `usesUserCallClass` (fake runner records `callClass`);
- `atMostThreePhotos`;
- `sceneUsesRepsThenMedoid`;
- `oneAtATime` (second call waits);
- `idsNeverReviewPrefix`.

- [ ] RED → implement → GREEN: `…/gw.sh :services:orchestration:testDebugUnitTest --tests '*DescribeOnDemand*' --tests '*KeyframePipeline*'`.
- [ ] Commit `feat(recall): describe on demand — VISION_EXTRACT on chosen photos or a scene, as USER`.

---

### Task 3: Ask integration — photo hits in ANSWER, photo cites, HUD label, Ask detail

**Needs:** Task 1. **Spec:** §3.4, R4, R5.

**Files:**
- modify `services/orchestration/src/main/java/com/rokid/context/orchestration/AnswerService.kt` and `AskActions.kt` (`AskOutcome.Answered`);
- modify `services/memory/src/main/java/com/rokid/context/memory/forget/ForgetFence.kt`;
- modify `services/voice/src/main/kotlin/com/rokid/context/voice/Answering.kt` and `VoicePipeline.kt`;
- modify `phone/src/main/java/com/rokid/context/phone/voice/AppAnswerer.kt`;
- modify `phone/.../ui/ask/AskDetailModel.kt`, `AskDetailViewModel.kt`, `AskDetailScreen.kt`;
- tests: extend `AnswerServiceTest.kt`, `ForgetFenceTest.kt` (create it if absent), `VoicePipelineTest.kt`, `AppAnswererTest.kt`, `AskDetailModelTest.kt`.

**Interfaces (Changes):**
```kotlin
// AnswerService — new optional ctor params (defaults keep every existing caller and test unchanged):
private val photos: suspend (question: String, nowMs: Long) -> List<PhotoHit> = { _, _ -> emptyList() },
private val photoImages: suspend (frameIds: List<String>) -> Map<String, ModelImageInput> = { emptyMap() },
// forApp: photos = { q, _ -> withTimeoutOrNull(1_500) { PhotoRecall.forApp(ctx).search(q, imageK = 2, captionK = 0).image }.orEmpty() }
//         photoImages = { ids -> FramePhotos.load(ctx, ids).photos.associate { it.frameId to it.image } }
data class Retrieved(val vars: PromptVars, val memories: List<MemoryEntity>, val photos: List<PhotoCite> = emptyList()) {
    val ids: Set<String> get() = memories.map { it.id }.toSet() + photos.map { it.citeId }
}
data class PhotoCite(val citeId: String, val frameId: String, val ts: Long?, val stored: Boolean)  // citeId = captionMemoryId ?: "frame:$frameId"
// AskOutcome
data class Answered(val askId: String, val proposedAction: ProposedAction? = null, val proposalId: String? = null,
                    val photoFrameId: String? = null, val photoAtMs: Long? = null) : AskOutcome
// voice
data class Answered(val askId: String, val text: String, val photoLabel: String? = null) : AnswerResult
data class Answer(override val source: VoiceSource, val askId: String, val question: String, val text: String, val speaking: Boolean, val photoLabel: String? = null) : VoiceState
// ForgetFence.allLive: ids starting "frame:" are live iff frame_meta has that frameId; other ids as today.
const val PHOTO_CITE_PREFIX = "frame:"   // in AskSources (orchestration) and mirrored in ForgetFence (memory) — one const in :services:memory, used by both
```

**Behaviour:**
- **`retrieve`:**
  - Photo hits come from `photos(q, nowMs)`, at most 2, on failure or timeout `emptyList()`.
  - Photo lines are `id=<citeId> · <yyyy-MM-dd HH:mm> · photo · <caption clipped to 300 chars | "no caption yet">`. A photo whose cite id is already a retrieved text memory is still listed once, as the photo line, and the text line is removed.
  - The text lines are bounded first (`AskText.bounded(lines, 5_000 − photoChars)`), then the photo lines are appended.
- **`answerOrThrow`:**
  - `NoMemoriesException` only when `memories` and `photos` are both empty (action commands as today).
  - `RoleCall(…, images = photoImages(stored photo frameIds).values.take(2), inputIds = r.ids)`. Keep `callClass = USER`.
  - The first entry of `out.sources` that is a photo cite id, or a memory whose `frameId` has a stored frame, gives `photoFrameId` / `photoAtMs`.
  - The redaction fence (`ForgetFence.allLive(r.ids + out.sources)`) now covers `frame:` ids. A redacted ask has no photo.
- **`AppAnswerer`:** `photoLabel = photoAtMs?.let { "Photo " + HH:mm in the system zone }`.
- **`VoicePipeline.overlayFor`:** with a label, the HUD text is `VoiceText.clip(s.text, HUD_ANSWER - label.length - 3) + " · " + label`. The spoken text is unchanged, so check the TTS path still uses `s.text`.
- **`AskDetailModel.build(…, photoFrames: Map<String, FrameEntity?>)`:**
  - A `frame:<id>` source becomes a `SourceCard(memoryId = id, text = "Photo", confidence = 1.0, framePath = path or null, expired = path == null)`.
  - A new field `expired: Boolean = false` on `SourceCard`.
  - Such a source is not counted in `missingSources`.
  - The screen shows the "Photo expired" placeholder (reuse the wave-2 Task 8 placeholder composable if present) and a 96 dp thumbnail for photo-backed cards.
  - A "More photos" `TextButton` (tag `more_photos`) navigates to `photos?q=<question>`. If Task 4 hasn't merged, add the route constant and leave the click unwired behind a `null` callback.

**Tests:**
- `AnswerServiceTest`:
  - `twoPhotoLinesAndImagesReachAnswer`;
  - `photoOnlyAskAnswers`;
  - `photoFailureDoesNotFailAsk`;
  - `photoCiteIdsAreCitable` (contract `sources ⊆ inputIds`);
  - `firstCitedPhotoReturned`;
  - `forgottenPhotoDuringAnswerRedacts`;
  - `textBudgetLeavesRoomForPhotos`;
  - `noImagesWhenPhotoExpired` (line kept, no image).
- `ForgetFenceTest`: `frameCiteLiveWhileMetaExists`, `frameCiteDeadAfterForget`.
- `VoicePipelineTest`: `photoLabelAppendedToHudNotSpoken`, `longAnswerClippedToFitLabel`.
- `AppAnswererTest`: `photoLabelFormatsTime`.
- `AskDetailModelTest`: `frameSourceRendersPhotoCard`, `expiredFrameSourceShowsPlaceholderAndIsNotMissing`.

- [ ] RED → implement → GREEN:
  - `…/gw.sh :services:orchestration:testDebugUnitTest --tests '*AnswerService*'`;
  - `:services:memory:testDebugUnitTest --tests '*ForgetFence*'`;
  - `:services:voice:testDebugUnitTest --tests '*VoicePipeline*'`;
  - `:phone:testDebugUnitTest --tests '*AppAnswerer*' --tests '*AskDetail*'`.
- [ ] Commit `feat(ask): photo hits in ANSWER (2 photos), citable photos, HUD photo time, photo cards in Ask detail`.

---

### Task 4: Photo search screen and the entry points

**Needs:** Tasks 1–3. **Spec:** §3.2, R8, R9.

**Files:**
- create in `phone/src/main/java/com/rokid/context/phone/ui/photos/`: `PhotoSearchModel.kt` (pure), `PhotoSearchViewModel.kt`, `PhotoSearchScreen.kt`;
- modify `ui/shell/Routes.kt` (`const val PHOTOS = "photos?q={q}"`, `fun photos(q: String)` URL-encoded);
- modify `AppShell.kt` / the nav graph;
- modify `ui/activity/ActivityScreen.kt` (a search `IconButton`, tag `photo_search`, content description "Find a photo");
- modify `ui/ask/AskDetailScreen.kt` (wire `more_photos`);
- modify `ui/shell/PhoneDeps.kt` (provide `PhotoRecall.forApp` and `KeyframeGraph.describe`);
- tests `phone/src/test/.../ui/photos/PhotoSearchModelTest.kt`, `PhotoSearchScreenTest.kt` (Robolectric Compose), and an `ActivityScreenTest` extension.

**Interfaces (Produces):**
```kotlin
data class PhotoTile(val frameId: String, val path: String?, val timeLabel: String, val score: String, val badge: String /* "Image" | "Caption" */,
                     val described: Boolean, val expired: Boolean, val caption: String?, val clusterId: String?, val date: String /* yyyy-MM-dd for the viewer */)
data class PhotoSearchUiState(val query: String = "", val searching: Boolean = false, val tiles: List<PhotoTile> = emptyList(),
                              val banner: String? = null, val note: String? = null, val describing: Boolean = false, val status: String? = null)
object PhotoSearchModel {
    const val CAPTIONS_ONLY = "Searching captions only. For photo search, turn on Nomic in Settings → AI → On-device models."
    fun tiles(r: RecallResult, paths: Map<String, String>, zone: ZoneId, today: LocalDate): List<PhotoTile>   // image first, then captions; score "%.2f" (Locale.US)
    fun banner(r: RecallResult): String?   // NO_IMAGE_SPACE or FAILED → CAPTIONS_ONLY; TIMED_OUT → null (note instead)
    fun note(r: RecallResult): String?     // truncated → "Searched the newest ${r.scanned} photos"; no tiles → "No photos match"
    fun status(d: DescribeResult): String  // Saved → "Described · N memories saved"; Expired → "Photo expired"; Busy → "Already being described — check back in a minute"; Unavailable → "AI unavailable — try again later"; Wiped → "Memory was cleared"
}
class PhotoSearchViewModel(initialQuery: String?, search: suspend (String) -> RecallResult, describePhotos: suspend (List<String>) -> DescribeResult,
                           describeScene: suspend (String) -> DescribeResult, framePaths: suspend (List<String>) -> Map<String, String>, zone: () -> ZoneId, today: () -> LocalDate) : ViewModel() {
    fun onQueryChange(q: String); fun submit(); fun describe(frameId: String); fun describeScene(clusterId: String)
}
```

**Screen:**
- A search field (tag `photo_query`) that searches on IME Search or the button (tag `photo_submit`). Searching on submit only, never on each keystroke (R8). A non-blank `q` argument submits once on open.
- `LazyVerticalGrid(GridCells.Fixed(3))` of tiles, tag `photo_tile_<frameId>`:
  - `FrameThumb(path, size = 112.dp)`, the time label, the score with its badge, ✓ (tag `described_<frameId>`);
  - for an expired tile: "Photo expired" (tag `expired_<frameId>`) plus 2 lines of caption.
- Tapping a tile opens a `ModalBottomSheet`:
  - "Open" goes to `Routes.frames(date, frameId)` when the photo is stored, else the caption's `memory/{id}`;
  - "Describe this photo" (tag `describe_photo`, disabled when expired or `describing`);
  - "Describe this scene" (tag `describe_scene`, only when `clusterId != null`).
- The banner (tag `photo_banner`), the note and the status line (tag `photo_status`).
- **Never** shows the query or captions in logs; no history is kept.

**Tests:**
- `PhotoSearchModelTest`:
  - `imageTilesBeforeCaptionTiles`;
  - `scoreFormattedTwoDecimals`;
  - `expiredTileHasCaption`;
  - `captionsOnlyBannerWithoutImageSpace`;
  - `truncatedNote`;
  - `describeStatusTexts`.
- `PhotoSearchScreenTest`:
  - `submitRunsOneSearchNotPerKeystroke`;
  - `initialQuerySearchesOnce`;
  - `expiredTileDisablesDescribe`;
  - `describeShowsStatusAndDisablesWhileRunning`;
  - `sceneActionOnlyWithCluster`;
  - `openGoesToFrameViewerOrMemory`.
- `ActivityScreenTest.searchIconOpensPhotos`.
- `AskDetailScreenTest.morePhotosOpensSearchWithQuestion`.

- [ ] RED → implement → GREEN: `…/gw.sh :phone:testDebugUnitTest --tests '*PhotoSearch*' --tests '*ActivityScreen*' --tests '*AskDetail*'`.
- [ ] Stream R gate: `tools/gate.sh <log>`.
- [ ] Commit `feat(phone): Find a photo — grid with scores, described ✓, expired tiles, describe on demand`.

---

## Part B — Small-fixes bundle

### Task 5: Phone fixes — NOT1 notification, PERM call guard, storage quota and banner, backup-rules check

**Spec:** §4.1, §4.4, §4.6, §4.7; R11, R12, R16. Phone and `:services:memory` only, no glasses.

**Files:**
- NOT1:
  - create `phone/src/main/java/com/rokid/context/phone/hub/ClosedOnGlassesNotice.kt` (pure decision plus a thin poster);
  - modify `hub/ContextHubService.kt` (channel, `ACTION_OPEN_GLASSES`, observe the link state it already observes for `notificationText`).
- PERM: modify `hub/GlassesRelauncher.kt` (`AndroidRelaunchGuards` and its pure `callIdle`).
- Quota:
  - modify `services/memory/src/main/java/com/rokid/context/memory/MemorySettingsStore.kt` (`quotaMb = 250` in the data class **and** the prefs read default). If wave-2 Task 8 moved `MemorySettings` to `MemorySettings.kt`, change it there.
  - modify `phone/.../ui/banner/Banners.kt` (the storage text).
- tests: create `ClosedOnGlassesNoticeTest.kt`; extend `ContextHubServiceTest.kt`, `GlassesRelauncherTest.kt`, `MemorySettingsStoreTest.kt`, `BannerResolverTest.kt`, `MemorySettingsTest.kt`; run `BackupRulesTest`.

**Interfaces and values:**
```kotlin
// NOT1 (R11)
class ClosedOnGlassesNotice(private val clock: () -> Long) {
    sealed interface Action { data object None : Action; data object Show : Action; data object Cancel : Action }
    /** Feed every link state change and a tick every 5 s. Show once CLOSED_ON_GLASSES has held >= DELAY_MS continuously; Cancel on any other state;
     *  after Show or onDismissed/onOpened, no second Show until the state has left CLOSED_ON_GLASSES and come back. */
    fun onState(state: LinkState): Action
    fun onTick(): Action
    fun onDismissed(); fun onOpened()
    companion object { const val DELAY_MS = 45_000L }
}
// ContextHubService
const val CLOSED_CHANNEL = "glasses_closed"      // name "Closed on glasses", IMPORTANCE_LOW, setSilent(true)
const val CLOSED_NOTIFICATION_ID = 32
const val ACTION_OPEN_GLASSES = "com.rokid.context.hub.OPEN_GLASSES"   // → the same call as BannerAction.RECONNECT (StatusUi.kt: glasses.requestReconnect()), then notice.onOpened() and cancel
// Notification: title "Closed on glasses · capture stopped", text "Tap Open on glasses to start it again.", action "Open on glasses",
// content intent = launcher intent, deleteIntent → notice.onDismissed(). Requires POST_NOTIFICATIONS granted; otherwise skip silently.

// PERM (R12) — AndroidRelaunchGuards
@Volatile private var lastNonNormalAtMs = Long.MIN_VALUE / 2
// API 31+: audio.addOnModeChangedListener(executor) { mode -> if (mode != MODE_NORMAL) lastNonNormalAtMs = clock() }; every callIdle() poll also stamps it when mode != NORMAL.
companion object {
    const val UNKNOWN_CALL_HOLD_MS = 30_000L          // unchanged
    const val CALL_END_SETTLE_MS = 15_000L            // new
    fun callIdle(audioMode: Int, callState: Int?, sinceSceneMs: Long, sinceNonNormalMs: Long): Boolean = when {
        audioMode != AudioManager.MODE_NORMAL -> false
        sinceNonNormalMs < CALL_END_SETTLE_MS -> false
        callState != null -> callState == TelephonyManager.CALL_STATE_IDLE
        else -> sinceSceneMs >= UNKNOWN_CALL_HOLD_MS
    }
}
// KDoc: "READ_PHONE_STATE is deliberately not requested (2026-10-21 ruling R12); callState is read only if a merged library declared it and it was granted."
```
- **Manifest:** do **not** add READ_PHONE_STATE, and do not add a `tools:node="remove"`.
- **Storage banner text:** `"Memory storage $p% full · raise the limit in Settings → Memory"`. Colours and thresholds are unchanged (CORAL at 90 % or more, AMBER at 70 % or more).
- **Quota:** the Memory settings choices stay `[50, 100, 250, 500, 1000]` plus the current value. A stored `quota_mb` is kept as is. Only an absent key reads 250.
- **Backup rules:** no new phone prefs, and the notice's state is in memory. Run `BackupRulesTest` to confirm it stays green. If anything in Tasks 1–5 added a `getSharedPreferences("…")`, add it to the right set (restorable or excluded) and to both XMLs.

**Tests:**
- `ClosedOnGlassesNoticeTest`:
  - `showsAfterFortyFiveSecondsClosed`;
  - `autoReopenBeforeDelayNeverShows`;
  - `cancelsOnAnyOtherState`;
  - `onceperEpisodeAfterDismiss`;
  - `newEpisodeShowsAgain`.
- `ContextHubServiceTest`:
  - `closedNoticePostedOnClosedChannelSilent` (ShadowNotificationManager);
  - `openActionRequestsReconnectAndCancels`;
  - `noPostWithoutNotificationPermission`.
- `GlassesRelauncherTest`:
  - `notIdleWithinFifteenSecondsOfNonNormalMode`;
  - `idleAfterSettleAndHold`;
  - `ringtoneModeIsBusy`;
  - the existing `callIdle` cases updated to pass `sinceNonNormalMs = Long.MAX_VALUE / 2`.
- `PhoneManifestTest.noReadPhoneStateRequested`: read `src/main/AndroidManifest.xml` and assert `READ_PHONE_STATE` is absent (pins R12).
- `MemorySettingsStoreTest`: `defaultQuotaIs250`, `storedQuotaKept`.
- `BannerResolverTest`: `storageBannerPointsToSettings`.
- `BackupRulesTest`: unchanged, green.

- [ ] RED → implement → GREEN:
  - `…/gw.sh :phone:testDebugUnitTest --tests '*ClosedOnGlasses*' --tests '*ContextHubService*' --tests '*GlassesRelauncher*' --tests '*PhoneManifest*' --tests '*Banner*' --tests '*MemorySettings*' --tests '*BackupRules*'`;
  - `:services:memory:testDebugUnitTest --tests '*MemorySettingsStore*'`.
- [ ] Gate `tools/gate.sh <log>`.
- [ ] Commit `fix(phone): Open-on-glasses notification, call-end settle without READ_PHONE_STATE, 250 MB default quota`.

---

### Task 6: WorkRest state survives process death (glasses)

**Spec:** §4.2, R13. **A3 overlap:** A3 Task 4 adds `enabled: () -> Boolean = { true }` to `WorkRest` and edits `GlassesRuntime.kt`. Add `store` **after** `enabled` (or as the last parameter if A3 hasn't merged), with a default, so both merge orders compile.

**Files:**
- modify `glasses/src/main/java/com/rokid/context/glasses/capture/session/WorkRest.kt`;
- create `capture/session/WorkRestStore.kt`;
- modify `GlassesRuntime.kt` (pass `PrefsWorkRestStore(app.getSharedPreferences("work_rest", MODE_PRIVATE))`);
- tests: extend `WorkRestTest.kt` and `GlassesBackupRulesTest.kt` (only if it enumerates prefs names).

**Interfaces:**
```kotlin
data class WorkRestSnapshot(val workStartMs: Long?, val pausedAtMs: Long?, val restUntilMs: Long?, val savedAtElapsedMs: Long)
interface WorkRestStore {
    fun load(): WorkRestSnapshot?
    fun save(s: WorkRestSnapshot)
    fun clear()
    object None : WorkRestStore { override fun load() = null; override fun save(s: WorkRestSnapshot) {}; override fun clear() {} }
}
class PrefsWorkRestStore(prefs: SharedPreferences) : WorkRestStore   // keys ws, pa, ru, at (Long; absent = null); apply(), never commit()
class WorkRest(clock, workMin, restMin, /* A3: */ enabled: () -> Boolean = { true }, private val store: WorkRestStore = WorkRestStore.None)
```

**Behaviour:**
- **Restore, lazily on the first `onSampling` / `onPaused`:** `load()`; discard it (and `clear()`) when `savedAtElapsedMs > clock()` (a reboot: elapsedRealtime restarted). Otherwise restore the three fields; the existing clamps (a rest longer than R, a backwards clock) then apply as written.
- **Save** whenever one of the three fields changes, and only then: no write per tick.
- **Off:** when `enabled()` is false (A3), `clear()` once and never save.

**Tests:**
- `killDuringRestResumesRest` (two `WorkRest` instances sharing a fake store and clock: the rest end is kept);
- `killDuringWorkKeepsWorkStart`;
- `rebootDiscardsState` (`savedAt` > now);
- `noWritePerTick` (count saves over 600 ticks ≤ 3);
- `disabledClearsStore` (only if A3's `enabled` exists in your base; otherwise note it in the report);
- `prefsStoreRoundTrip` (Robolectric).

- [ ] RED → implement → GREEN: `…/gw.sh :glasses:testDebugUnitTest --tests '*WorkRest*' --tests '*GlassesBackupRules*'`.
- [ ] Commit `fix(glasses): persist WorkRest across process death (reboot-safe)`.

---

### Task 7: Start-up flash (UI1) and launcher-vs-auto-open detection (REF) (glasses)

**Spec:** §4.3, R14. **A3 overlap:** none expected (MainActivity and AutoStartGate). Rebase cleanly if A3 touched `MainActivity`.

**Files:**
- modify `glasses/src/main/java/com/rokid/context/glasses/AutoStartGate.kt` and `MainActivity.kt` (the `Hud()` composable gets a hidden flag);
- tests: extend `AutoStartGateTest.kt` and `MainActivityTest.kt`.

**Interfaces:**
```kotlin
enum class RefClass { LAUNCHER, OTHER, NONE }
object StartSources {
    fun classify(action: String?, categories: Set<String>?, referrerPackage: String?): StartSource   // unchanged
    fun refClass(referrerPackage: String?): RefClass   // null/blank → NONE; contains "launcher" (ignore case) → LAUNCHER; else OTHER
}
data class StartDecision(val minimise: Boolean, val pending: Boolean, val markerRecent: Boolean, val extra: Boolean, val fromMinimised: Boolean, val source: StartSource) {
    /** Count-only diag line; never the referrer package. */
    fun line(ref: RefClass): String =
        "start src=$source ref=$ref pending=${pending.b} marker=${markerRecent.b} fromMin=${fromMinimised.b} extra=${extra.b} minimise=${minimise.b}"   // .b = 1/0
}
class AutoStartGate(...) {
    @Synchronized fun decide(source: StartSource, extraMinimised: Boolean, fromMinimised: Boolean = false): StartDecision   // the body of today's onStart
    @Synchronized fun onStart(source: StartSource, extraMinimised: Boolean, fromMinimised: Boolean = false): Boolean = decide(source, extraMinimised, fromMinimised).minimise
}
```

**MainActivity:**
- `private val hudHidden = mutableStateOf(false)`. `setContent { Hud(hidden = hudHidden.value) }`. When hidden, `Hud` draws only a black full-size `Box` (tag `hud_hidden`), with no HUD children.
- In `onStart`:
  - `val d = runtime.startGate.decide(...)`;
  - `DiagLog.i(CAPTURE_TAG, d.line(StartSources.refClass(referrer?.host)))`;
  - if `d.minimise` → `hudHidden.value = true` **before** `swipeTimer.post { minimise() }`, else `hudHidden.value = false`.
  - The existing Log lines stay.
- Every path that brings the HUD back from icon mode (the next `onStart` without minimise, a Confirm opening the HUD) sets `hudHidden.value = false`.
- The `onAutoOpen` marker path is unchanged (R14).

**Tests:**
- `AutoStartGateTest.decisionTable`, a table over every combination:
  - source ∈ {LAUNCHER, OTHER}, pending, markerRecent, fromMinimised, extra → expected `minimise`. At least these rows:
    - a wearer's launcher open after a kill → HUD;
    - a CXR start (OTHER) after a kill → minimise;
    - a launcher-looking start from icon mode within the marker window → minimise;
    - extra → minimise;
    - a fresh launcher open → HUD.
  - The table also asserts that `onStart` equals `decide(...).minimise`.
- `AutoStartGateTest.refClassNeverLeaksPackage`: `line()` holds none of the referrer's characters beyond the class.
- `MainActivityTest`:
  - `killRestartStartDrawsHiddenHudBeforeMinimise` (Robolectric: build → create → start; before idling the main looper, the `hud_hidden` node exists and the HUD's root tag does not);
  - `launcherOpenShowsHud`: intent MAIN + LAUNCHER with referrer `android-app://com.rokid.os.launcher` (use any launcher-named package in tests; it isn't a secret), pending kill → the HUD is shown and `hud_hidden` is absent;
  - `startLineWrittenToDiagLog` (DiagLog test seam, or capture `DiagLog.i` through its existing test hook).

- [ ] RED → implement → GREEN: `…/gw.sh :glasses:testDebugUnitTest --tests '*AutoStartGate*' --tests '*MainActivity*' --tests '*Minimise*'`.
- [ ] Commit `fix(glasses): no HUD frame before an automatic minimise; start-source decision table and diag line`.

---

### Task 8: Restart-reason polish — count only Rokid force-stops (DIAG) (glasses + phone text)

**Spec:** §4.5, R15. **A3 overlap:** `GlassesRuntime.kt` (the `restarts` construction only).

**Files:**
- modify `glasses/src/main/java/com/rokid/context/glasses/RestartDiagnostics.kt`;
- create `glasses/.../ExitClassifier.kt` (pure);
- modify `GlassesRuntime.kt` (feed `ExitFacts`);
- modify the hello / heartbeat field only if a new count is sent. **Don't add a protocol field:** keep `rokidCloses` and `lastRestartGapSec` as they are; "other" is glasses-local, shown in `summary()` and the diag log.
- tests: extend `RestartDiagnosticsTest.kt`; create `ExitClassifierTest.kt`.

**Interfaces:**
```kotlin
data class ExitFacts(val userRequested: Boolean, val description: String?, val timestampMs: Long)
enum class ExitKind { ROKID, OTHER, UNKNOWN }
object ExitClassifier {
    private val PID = Regex("""from pid (\d+)""")
    /** [callerName] reads /proc/<pid>/cmdline (trimmed at NUL), null when unreadable or the process is gone; [alive] = File("/proc/<pid>").exists(). */
    fun classify(f: ExitFacts, alive: (Int) -> Boolean, callerName: (Int) -> String?): ExitKind = when {
        !f.userRequested -> UNKNOWN /* not a force-stop: not counted at all (caller checks userRequested first) */
        else -> PID.find(f.description.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { pid ->
            val name = callerName(pid)
            when { name?.startsWith("com.rokid.") == true -> ROKID; name != null -> OTHER; !alive(pid) -> OTHER; else -> UNKNOWN }
        } ?: UNKNOWN
    }
}
// RestartDiagnostics: ctor param lastExitWasUserRequested is replaced by
//   lastExit: () -> ExitFacts? = { null }, classify: (ExitFacts) -> ExitKind = { ExitKind.UNKNOWN }
// reason: "forcestop_rokid" (ROKID or UNKNOWN: counted in KEY_CLOSES as today) | "forcestop_other" (KEY_OTHER_CLOSES = "other_closes", not counted as Rokid, gap not stored)
// gap: if lastExit()?.timestampMs in (lastAlive, now] → (now − timestampMs)/1000, else today's (now − lastAlive)/1000.
// killedWhileRunning is unchanged by the kind (any force-stop while running restarts in icon mode).
// summary(): "Rokid closed the app N times (last gap Xs)" + (if other > 0) "; M other force-stops not counted" inside the parentheses.
// Log line: "restart gap=… reason=… ui_mode=…" — the reason token only, never the description.
```
- **GlassesRuntime:** API ≥ R → `getHistoricalProcessExitReasons(pkg, 0, 1).firstOrNull()?.let { ExitFacts(it.reason == REASON_USER_REQUESTED, it.description, it.timestamp) }`. `callerName` reads `/proc/$pid/cmdline` in `runCatching`. Never log either value.

**Tests:**
- `ExitClassifierTest`:
  - `rokidCallerIsRokid`;
  - `shellCallerIsOther`;
  - `deadPidIsOther`;
  - `unreadableAlivePidIsUnknown`;
  - `noPidIsUnknown`;
  - `notUserRequestedIsUnknown`.
- `RestartDiagnosticsTest`:
  - `otherForceStopNotCountedAsRokid`;
  - `unknownStillCounted`;
  - `gapUsesExitTimestamp`;
  - `gapFallsBackToHeartbeat`;
  - `summaryMentionsOtherCount`;
  - `logHasNoDescription`.
  - The existing tests are adapted to the new constructor (`lastExit = { ExitFacts(true, null, 0) }` reproduces today's "forcestop").

- [ ] RED → implement → GREEN: `…/gw.sh :glasses:testDebugUnitTest --tests '*RestartDiagnostics*' --tests '*ExitClassifier*' --tests '*GlassesRuntime*'`.
- [ ] Commit `fix(glasses): count only Rokid force-stops in "Rokid closed N times"; exact restart gap from the exit timestamp`.

---

### Task 9: Deferred one-liners (glasses diag, tools, canvas)

**Spec:** §4.8, R17. Each item is at most about 20 lines plus a test where one is named. **A3 overlap:** `CaptureService.kt` and `Heartbeat.kt` (A19, A20). Keep these edits inside `publishStatus` and the heartbeat builder, and mention them in the report.

| Id | Change | File | Test |
|---|---|---|---|
| A3 | The wearingStatus log prints only a token from an allow-list (`worn`, `not_worn`, `unknown`, `off`, or `other`), never free text | `glasses/.../WearingStatus.kt` (~line 118; find the regex that admits up to 24 chars) | `WearingStatusTest.logTokenAllowListOnly` |
| A19 | The heartbeat prints `sensors=off` when there is no sampling session | `glasses/.../capture/CaptureService.kt` `publishStatus` (~283) | `HeartbeatTest.sensorsOffWithoutSession` (or the CaptureService test seam) |
| A20 | A throw while building the heartbeat must not skip the status publish: wrap the heartbeat block in `runCatching`, log the class name only | same | `CaptureServiceTest.heartbeatThrowStillPublishesStatus` (fake heartbeat that throws) |
| A21 | The awk that reads `batt.log` uses `FILENAME == ARGV[1]`, so an empty first file doesn't misroute | `tools/battery-run.sh` | `tools/test-battery-run.sh`: add the empty-batt.log case |
| A22 | Pulled diag logs (`capture_diag.log`, run-as stderr) go through the same deny filter as logcat (`grep -v -i -E "token\|AuthorizationHelper\|deviceSecret\|deviceKey\|secret\|password\|DevCmdHelper\|PhoneCallManager\|TelephoneManager\|BluetoothPBAPManager\|MobileMsgDialog\|AssistServer"`); `2>/dev/null` on `run-as` | `tools/battery-run.sh` | `tools/test-battery-run.sh`: a fixture line containing `deviceSecret` is absent from the output |
| A23 | Don't cache a failed or empty fps-range read; cache only a successful result | `glasses/.../capture/session/CameraXSamplingCamera.kt` (~88) | `FpsRangePickTest.failedReadNotCached` (or the nearest test of `CameraXBind.fpsRange`) |
| D13 | The canvas critic node returns its pass-through result without a model call when the fact list is empty | `services/orchestration/.../canvas/.../Executors.kt` (~194; find the critic executor) | `ExecutorsTest.criticSkipsCallOnEmptyFacts` |
| D26 | Responses built in the canvas server's catch path carry the same security headers as normal responses | `phone/.../canvas/server/CanvasServer.kt` / `CanvasRouter.kt` (find where headers are added) | `CanvasRouterTest.catchPathHasSecurityHeaders` |
| D30 | `saveDraft` returns the fixed text "Pipeline JSON is not valid." instead of the decoder's message | `phone/.../canvas/api/CanvasApi.kt` | `CanvasApiTest.saveDraftErrorIsFixedText` |
| D39 | `decodeURIComponent` of the `#t=` / `#pair=` fragments sits in try/catch; a malformed fragment shows the pairing screen | `phone/src/main/assets/canvas/app.js` (~196–199) | `CanvasAssetsTest.fragmentDecodeGuarded` (source scan for the try around both calls) |
| D36 | A failed run poll retries with back-off (2 s, 4 s, 8 s, capped at 15 s) instead of stopping | `phone/src/main/assets/canvas/views.js` (~911) | `CanvasAssetsTest.pollRetriesWithBackoff` (source scan for the retry) |

- Line numbers come from the ledgers and may have moved. Find each site by name. If a site no longer exists, or was already fixed, skip it and say so in the report. Do not widen any change.

- [ ] RED → implement → GREEN:
  - `…/gw.sh :glasses:testDebugUnitTest --tests '*WearingStatus*' --tests '*Heartbeat*' --tests '*CaptureService*' --tests '*FpsRange*'`;
  - then `:services:orchestration:testDebugUnitTest --tests '*Executors*'`;
  - then `:phone:testDebugUnitTest --tests '*Canvas*'`;
  - `bash tools/test-battery-run.sh` from the repo root.
- [ ] Stream G gate: `tools/gate.sh <log>`.
- [ ] Commit `chore: deferred one-liners — wear log allow-list, heartbeat guards, battery-run filter, fps cache, canvas hardening`.

---

### Task 10: Device checklist (controller only; not code)

**Needs:** Tasks 1–9 merged with wave 2, gate green, **Nomic bound** (wave-2 Task 12 step 4). Follow handoff §2:
- filtered logcat or our tags only, never print secrets;
- `-s <serial>` always;
- an explicit user OK before anything destructive;
- never toggle Wi-Fi;
- don't touch the devices while the user wears them unless asked.

Devices: glasses `1901092545029120` (USB), phone `adb-RZCY902F76K-nmSM2g._adb-tls-connect._tcp`.

1. **Backup and install:**
   - phone app data tar to `~/Desktop/rokid/backups/` (user OK);
   - `install -r` on both devices; on the glasses re-allow `SYSTEM_ALERT_WINDOW`;
   - check the DB counts only, before and after: `frame_meta`, `frame_vector`, `memory`. They must be unchanged, since this wave has no migration.
2. **Find a photo (user):**
   - Activity → search icon → "keys", "laptop", "door", plus 2 of the user's own.
   - Record: image tiles shown (yes/no), the top-3 judged relevant by the user (count), search time from the `PcRecall ms=` tag (number only), any "Photo expired" tile, and the ✓ marks.
   - With Nomic rolled back (optional, user OK): the captions-only banner shows.
3. **Describe:**
   - one NEAR_DUP or un-described photo → "Describe this photo" → status "Described · N memories saved", ✓ appears;
   - a photo in a scene → "Describe this scene";
   - an expired tile → Describe is disabled.
4. **Voice ask (user, worn):**
   - put the keys somewhere visible, glance for 2 s or more, walk away; after ≥ 2 min ask "where did I leave my keys".
   - Pass: the glasses card ends "· Photo HH:mm", the spoken answer has no "Photo" suffix, and the phone Ask detail shows the photo card and "More photos".
5. **NOT1:**
   - with the app full screen, start a phone call so Rokid closes it, and keep it closed past 45 s (decline any reopen);
   - one silent notification "Closed on glasses · capture stopped"; "Open on glasses" reopens; no second notification in the same episode.
6. **PERM:**
   - `adb -s <phone> shell dumpsys package com.rokid.context | grep -c READ_PHONE_STATE` (a count only: did a merged library declare it?);
   - during a real call no reopen happens until ≥ 15 s after hang-up and ≥ 30 s after the scene event (relauncher log lines from our tag only).
7. **WR:** only if A3 is merged and the user turns Work and rest on with a short test pair (30/5).
   - Force-stop during a rest: `adb -s <g> shell am force-stop com.rokid.context.glasses`, user OK.
   - After the restart, capture stays resting until the original rest end (`capture_diag.log` heartbeat `reason=rest`).
8. **UI1 and REF:**
   - **Kill restart while worn:** the user reports no HUD flash before the icon.
   - **The wearer's own launcher open:** the HUD shows. `capture_diag.log` has `start src=LAUNCHER ref=launcher … minimise=0`.
   - **Automatic reopen:** `src=OTHER … minimise=1`, or a `marker=1` line.
   - Record which `ref=` class the CXR start carries.
9. **DIAG:**
   - `adb force-stop` → Diagnostics "… other force-stops not counted" goes up by 1 and the Rokid count is unchanged;
   - a Rokid call-scene close → the Rokid count goes up by 1;
   - read `dumpsys activity exit-info com.rokid.context.glasses` for **our package only**, and only the reason and whether the description contains `from pid` (yes/no). Never paste the description.
10. **Storage:** Settings → Memory shows a 250 MB limit if the user never set one (the user set 250 manually, so it reads 250 either way); the banner text names Settings → Memory when ≥ 70 % (simulate only if the store is already near the limit; otherwise skip).
11. **Minors:** run `tools/battery-run.sh` once in its dry or read mode; the output has no line matching the deny filter (count = 0).
12. Write the results into the handoff (§6 device facts; §8: NOT1, WR, UI1, PERM, DIAG and REF → fixed or reopened) and the backlog. Suggest a fresh chat.

- [ ] Done when steps 1–12 are recorded. No code commit. Docs only: `docs: wave 3 device check — recall, describe, photo-cited asks, small fixes`.

---

## Rulings (summary; full text in the spec)

R1 brute-force scan with caps (50 000 / 2 s), no ANN · R2 no score merging, 2 photos to ANSWER · R3 caption fallback through MemoryRetriever · R4 photo lines, images and `frame:` cites in ANSWER · R5 HUD "· Photo HH:mm" as text, phone-side only · R6 describe = VISION_EXTRACT under USER, and described skipped photos leave the 1-day rule · R7 no Room or protocol change · R8 search on submit, no history · R9 image hits need an ACTIVE IMAGE space · R10 the Ask half of the debug view stays with the canvas · R11 silent notification after 45 s closed · R12 no READ_PHONE_STATE, plus a 15 s call-end settle · R13 WorkRest persisted, reboot-safe · R14 hidden first frame on an automatic minimise · R15 classify the force-stop caller by pid · R16 250 MB default · R17 the minors list.
