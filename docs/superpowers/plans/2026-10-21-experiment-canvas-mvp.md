# Experiment canvas: MVP implementation plan (slice 1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. This is a **lean plan**, per the user's standing preference: exact interfaces, values, behaviour bullets and named tests, not full code. Every task is TDD: write the named tests RED, implement, then GREEN. Opus specs and reviews; **Sonnet implements**.

**Goal:** a developer-only experiment canvas in the phone app. An embedded local web server serves a plain-JS page that shows pipelines as DAGs, edits prompt nodes and pipeline JSON, bulk-runs N chosen photos through a pipeline, compares and scores the outputs, and freezes, publishes (live-shaped pipelines only: prompt changes) and rolls back. The live extraction pipeline is the built-in default pipeline, and an "Intent → extract" draft ships beside it.

**Spec:** `docs/superpowers/specs/2026-10-21-experiment-canvas-design.md`. Section numbers (§) below refer to it. Read §3 (rulings), §4 (model), §5 (runner), §6 (publish), §7 (tables) and §8 (server and API) before your task.

**Streams and waves** (parallel worktrees; at most 2 Gradle runs machine-wide):

| wave | tasks | modules |
|---|---|---|
| W1 | 1, 2, 4, 8, 10 | 1: ai + orchestration · 2: ai (`ai.canvas`) · 4: memory · 8: phone (`canvas.server`) · 10: phone assets |
| W2 | 3 (needs 2), 5 (needs 1, 2) | ai · orchestration |
| W3 | 6 (needs 4, 5), 7 (needs 3, 4) | orchestration |
| W4 | 9 (needs 6, 7, 8) | phone |
| W5 | 11 (needs 8, 9) | phone |
| W6 | 12 (needs everything) | all four |

## Global Constraints

- **Modules:** only `:services:ai`, `:services:orchestration`, `:services:memory` and `:phone` (plus `phone/src/main/assets/canvas/`). **Do not touch `:glasses`, `:core:hud`, `:services:glasses-link` or any glasses code.** A separate battery plan is being implemented on the glasses module at the same time. If a task seems to need a glasses change, stop and report.
- **PROTOCOL_VERSION stays 1, additive only.** This plan makes no wire change at all. Don't touch `core/model` protocol types.
- **Room: v2 → v3, additive only** (Task 4): six new `canvas_*` tables, `MIGRATION_2_3` with Room's generated SQL from `services/memory/schemas/…/3.json` (commit the exported JSON), and a `Migration2To3Test` that proves the v2 rows survive and no v2 table changed. If another branch has taken v3 by merge time, renumber to the next version and regenerate the schema; never edit an existing migration.
- **No live behaviour change.** Default values of every new parameter keep today's behaviour. The existing tests of `RoleRunner`, `ExtractSave`, `KeyframePipeline` and `ExtractFollowUp` must pass unchanged. Experiment runs never write memories, `frame_state`, embeddings, nudges or jobs (§3 R10).
- **Privacy and secrets:** never print `.env` values, keys or tokens. Server and canvas logs are count-only (method, route template, status, duration): no ids in paths, no prompt text, no model output, no tokens or pairing codes. Error strings stored in canvas tables are fixed short texts, never provider text. Web UI rendering never uses `innerHTML`, `outerHTML`, `insertAdjacentHTML` or `document.write`: model output is untrusted.
- **Android ICU regex:** no unescaped `}` in any `Regex` (`AndroidRegexRulesTest`). Keep `\uXXXX` escapes as escapes; grep each diff for accidental non-ASCII in `.kt` files.
- **JSON:** use `kotlinx.serialization.json` elements by hand (`buildJsonObject`, `jsonObject`, `Json.parseToJsonElement`). Don't add the serialization Gradle plugin to any module.
- **Dependencies:** the only new one is `implementation("org.nanohttpd:nanohttpd:2.3.1")` in `phone/build.gradle.kts` (Task 8). Nothing else.
- **Gradle:** from `android-companion/`, always through the throttle, with an absolute path (worktrees don't have the git-ignored `.superpowers/`):
  `/Users/<user>/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <args>`. It runs `./gradlew --no-daemon …`. One Gradle run per worktree at a time; never `gradlew --stop`; never `adb`.
- **Test commands** (Robolectric `@Config(sdk = [34])`, JUnit4 `org.junit.Assert`):
  - ai: `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'` (plus the named classes of Task 1)
  - memory: `gw.sh :services:memory:testDebugUnitTest --tests '<class>'`
  - orchestration: `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'`
  - phone: `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.*'`
  - full gate (Task 12 only): `tools/gate.sh <log>` from the repo root.
- **Commits:** commit locally only, never push. One commit per task, with the message given in the task, ending with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **FAST MODE:** fix only Critical and Important review findings inside a task. Minors go to the ledger.
- **Test fakes:** if no `LanguageModel` fake exists in the module's test set, write `FakeLanguageModel : LanguageModel` there: queued `RoutedReply`s, and a record of the `ModelRequest`s it received (build the `Endpoint` the way existing tests in `services/ai/src/test` do). Tests that need a database use `DatabaseFactory.inMemory(context)`.

## Review focus
1. **Live path untouched** (Task 1): every new parameter defaults to today's behaviour; diff `RoleRunner`, `ExtractSave` and `ExtractFollowUp` line by line.
2. **Nothing live written by a run** (Tasks 5–6): `CanvasRunnerTest.runWritesNoLiveRows` counts the `memory`, `frame_state`, `frame_source` and `memory_vector` rows before and after.
3. **Auth on loopback** (Task 8): every `/api/*` route except `/api/pair` returns 401 without a token; the Host and Origin checks; pairing lockout.
4. **Rollback can't clobber a later edit** (Task 7): `rollbackRefusedWhenPromptChangedSincePublish`.
5. **Forget and Delete all reach the canvas tables** (Task 4).
6. **XSS** (Task 10): the source scan bans HTML sinks; model text is set through `textContent` only.

---

### Task 1: Prompt override seam in RoleRunner, ExtractSave and ExtractFollowUp

**Files:**
- Modify: `services/ai/src/main/java/com/rokid/context/ai/run/RoleRunner.kt`
- Modify: `services/orchestration/src/main/java/com/rokid/context/orchestration/ExtractSave.kt`
- Modify: `services/orchestration/src/main/java/com/rokid/context/orchestration/ExtractFollowUp.kt`
- Test: `services/ai/src/test/java/com/rokid/context/ai/run/RoleRunnerOverrideTest.kt`, `services/orchestration/src/test/java/com/rokid/context/orchestration/ExtractSaveOverrideTest.kt`

**Interfaces (Produces):**
```kotlin
// RoleRunner.kt: add as the LAST field of RoleCall, defaulting to null
data class RoleCall(/* existing fields unchanged */, val override: PromptDraft? = null)
// RoleRunner.run: when call.override != null, use override.template and override.outputSchema in place of
// prompts.active(call.key) (SYSTEM is still the active SYSTEM); RoleResult.promptVersion = 0 (meaning "draft").
// Everything else (OutputCheck.schemaFor, StructuredOutput, corrective retry, cap doubling, fromFrames) is unchanged.

// ExtractSave.kt
suspend fun extract(runner: RoleRunner, photos: List<ExtractPhoto>, callClass: CallClass,
                    override: PromptDraft? = null, contextSources: String = ""): ExtractedBatch
// current = promptIsCurrent(if (override != null) (override.outputSchema ?: DefaultSchemas.text(PromptKey.VISION_EXTRACT)) else runner.activeSchema(VISION_EXTRACT))
// call(): PromptVars(recentContext = <frames block as today>, contextSources = contextSources); RoleCall(..., override = override)

// ExtractFollowUp.kt
fun criticVars(facts: List<Fact>, frameLines: List<String>): PromptVars   // extracted from modelCriticPasses, the same text
suspend fun modelCriticPasses(ai: AiGraph, facts: List<Fact>, frameLines: List<String>, callClass: CallClass = CallClass.EXTRACT,
                              override: PromptDraft? = null): Boolean
```

**Tests:**
- `RoleRunnerOverrideTest`:
  - `overrideTemplateIsSentInsteadOfActive` (the fake model's received prompt contains the override text and not the active text);
  - `overrideSchemaValidatesReply`;
  - `nullOverrideUsesActivePromptAndVersion`;
  - `overrideReportsVersionZero`;
  - `systemPreambleStillActive`.
- `ExtractSaveOverrideTest`:
  - `overrideWithProvenanceSchemaBatchesThree`;
  - `overrideWithLegacySchemaGoesSinglePhoto`;
  - `contextSourcesReachPrompt`;
  - `defaultsMatchTodayBehaviour` (same calls and prompts as without the new args).
- Existing `RoleRunner*`, `ExtractSave*`, `ExtractFollowUp*` and `KeyframePipeline*` tests stay green.

**Test command:** `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.run.*'` then `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.orchestration.*'`

**Commit:** `feat(ai): in-memory prompt override for RoleRunner, ExtractSave and the critic (canvas seam)`

---

### Task 2: Pipeline model, JSON codec, node-type registry, validator, hash

**Files (all new, `services/ai/src/main/java/com/rokid/context/ai/canvas/`):** `Pipeline.kt`, `NodeTypes.kt`, `PipelineJson.kt`, `PipelineValidator.kt`, `PipelineHash.kt`
**Test:** `services/ai/src/test/java/com/rokid/context/ai/canvas/PipelineJsonTest.kt`, `PipelineValidatorTest.kt`, `PipelineHashTest.kt`

**Interfaces (Produces):**
```kotlin
package com.rokid.context.ai.canvas
data class Pipeline(val schema: Int, val id: String, val name: String, val nodes: List<PipelineNode>, val edges: List<PipelineEdge>)
data class PipelineNode(val id: String, val type: String, val title: String, val params: JsonObject)
data class PipelineEdge(val from: String, val to: String, val `when`: String? = null)
enum class PortType { PHOTOS, EXTRACTION, JSON, PREVIEW }
data class NodeType(val type: String, val label: String, val inputs: Set<PortType>, val output: PortType,
                    val promptKey: PromptKey? = null,                       // role-backed nodes
                    val branches: (JsonObject) -> List<String> = { emptyList() })
object NodeTypes {
    const val INPUT_PHOTOS = "input.photos"; const val VISION_EXTRACT = "ai.vision_extract"; const val LOCAL_CRITIC = "gate.local_critic"
    const val CRITIC = "ai.critic"; const val PROMPT = "ai.prompt"; const val SWITCH = "route.switch"; const val MEMORY_WRITE = "memory.write"
    val all: List<NodeType>; fun byType(t: String): NodeType?
}
sealed interface PromptSource { data object Live : PromptSource; data class Draft(val template: String, val schema: String?) : PromptSource }
fun PipelineNode.promptSource(): PromptSource?          // null when the node has no "prompt" param
object PipelineJson {
    const val SCHEMA = 1; const val MAX_BYTES = 64 * 1024
    fun decode(text: String): Result<Pipeline>          // failure message is fixed text, e.g. "not JSON", "missing nodes"
    fun encode(p: Pipeline, pretty: Boolean = false): String
    fun canonical(p: Pipeline): String                  // keys sorted at every level, no whitespace
}
object PipelineValidator { fun validate(p: Pipeline): List<String> }   // empty = valid
object PipelineHash { fun of(p: Pipeline): String /* sha256(canonical) hex */; fun short(h: String) = h.take(12) }
```

**Exact values (§4):**
- id regex `^[a-z][a-z0-9_]{0,31}$`; at most 40 nodes and 80 edges;
- template and schema at most 16,000 characters;
- `batch` 1..3, default 3;
- `ai.prompt` `role` is any `Role` except EMBED, default `VISION_EXTRACT`; `schema` required; `images` default true;
- `route.switch` `cases` 1..12 labels matching the id regex; its branches are `cases + "other"`;
- `gate.local_critic` branches are `trusted`, `unsure`; `ai.critic` branches are `pass`, `fail`.
- The ports are those in the §4 table.

**Validator rules:** all of §4 "Validation". The problem texts are fixed, with ids only, for example `edge photos->x: unknown node x`, `node extract: batch must be 1..3`, `cycle through extract`.

**Tests:**
- `PipelineJsonTest`: `roundTripKeepsEverything`, `decodeRejectsOversize`, `decodeRejectsWrongSchemaVersion`, `canonicalIsKeyOrderIndependent`, `promptSourceLiveAndDraft`.
- `PipelineValidatorTest`, one test per rule: `duplicateIds`, `unknownEdgeEnd`, `exactlyOneInput`, `inputHasNoIncoming`, `cycleRejected`, `portTypeMismatch`, `mixedIncomingTypes`, `branchEdgeNeedsKnownWhen`, `plainEdgeMustNotHaveWhen`, `unreachableNode`, `batchRange`, `draftSchemaMustBeObject`, `promptNodeRequiresSchema`, `switchCasesLimits`, `embedRoleRejected`, `validMinimalPipeline`.
- `PipelineHashTest`: `sameContentSameHash`, `promptEditChangesHash`.

**Test command:** `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'`

**Commit:** `feat(ai): canvas pipeline model, JSON codec, node types and validator`

---

### Task 3: Default pipelines, LiveShape, PipelineFlows (pipeline → AgentFlow + layout)

**Files:** create `services/ai/src/main/java/com/rokid/context/ai/canvas/DefaultPipelines.kt`, `LiveShape.kt`, `PipelineFlows.kt`
**Test:** `services/ai/src/test/java/com/rokid/context/ai/canvas/DefaultPipelinesTest.kt`, `LiveShapeTest.kt`, `PipelineFlowsTest.kt`

**Interfaces (Produces):**
```kotlin
object DefaultPipelines {
    const val LIVE_EXTRACT = "live-extract"; const val INTENT_DRAFT = "intent-draft"
    val INTENT_LABELS = listOf("sitting_idle", "workout", "computer_work", "driving", "walking", "other")
    fun liveExtract(): Pipeline
    fun intentDraft(seedExtractTemplate: String): Pipeline   // seed = the live VISION_EXTRACT seed text (the caller reads it via PromptSeeds)
    val all: List<String>                                     // ids of the built-in pipelines
}
object LiveShape { fun matches(p: Pipeline): Boolean; fun draftPromptNodes(p: Pipeline): List<PipelineNode> }
object PipelineFlows {
    fun toAgentFlow(p: Pipeline): AgentFlow
    fun layout(p: Pipeline): FlowLayout = FlowLayouts.layout(toAgentFlow(p))
    fun layoutJson(p: Pipeline): JsonObject   // {"layers":L,"columns":C,"cells":{"<nodeId>":{"layer":l,"column":c}}}
}
```

**Values:**
- `liveExtract()`: nodes `photos` (input.photos, "Photo"), `extract` (ai.vision_extract, "Facts from frames", batch 3, prompt live), `trust` (gate.local_critic, "Confidence check"), `critic` (ai.critic, "Critic", prompt live), `write` (memory.write, "Save memory"). Edges: `photos→extract`, `extract→trust`, `trust→write [trusted]`, `trust→critic [unsure]`, `critic→write [pass]`, `critic→write [fail]`.
- `intentDraft(seed)`:
  - `photos → intent`. `intent` is an `ai.prompt` node titled "Intent", with role VISION_EXTRACT, name `intent`, and images true. Its template asks for exactly one label from `INTENT_LABELS`, plus `confidence` 0..1 and `reason` (≤ 120 characters), and ends with `{{recent_context}}`. Its schema is `{"type":"object","properties":{"activity":{"enum":[…labels]},"confidence":{"type":"number","minimum":0,"maximum":1},"reason":{"type":"string","maxLength":120}},"required":["activity","confidence"],"additionalProperties":false}`.
  - `intent → route`. `route` is a `route.switch` with field `activity` and cases equal to the first 5 labels.
  - For each of the 5 cases, `route → extract_<label> [<label>]`. Each `extract_<label>` is an `ai.vision_extract` with batch 3 and a draft prompt: the template is the `seed` text, and the schema is null (the default contract). Each extract node then goes to `write`, a single `memory.write`.
  - `route → extract_other [other]`, where `extract_other` uses the live prompt, then → `write`.
- `toAgentFlow`: maps node kinds per §4. Role-backed nodes get `Facet.Prompt(key)` and `Facet.Model(role)`; `ai.prompt` gets `Facet.Custom("canvas.prompt")`; edge labels come from `when`. The flow's `id` = pipeline id, `title` = name, `purpose` = `"Experiment pipeline"`.

**Tests:**
- `DefaultPipelinesTest`: `bothDefaultsValidate`, `liveExtractMirrorsCaptureFlowPhotoPath` (CaptureFlow `photo→extract→confidence→critic→save` and its edge labels map 1:1 to the live-extract edges, via the id mapping photo→photos, confidence→trust, save→write), `intentRoutesEveryLabel`, `intentSchemaParses`.
- `LiveShapeTest`: `liveExtractMatches`, `promptEditStillMatches`, `batchChangeBreaksShape`, `extraNodeBreaksShape`, `intentDraftDoesNotMatch`, `draftPromptNodesListsOnlyDrafts`.
- `PipelineFlowsTest`: `kindsMapped`, `edgeLabelsFromWhen`, `layoutCoversEveryNode`, `layoutJsonShape`.

**Test command:** `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'`

**Commit:** `feat(ai): canvas default pipelines (live extraction, intent draft), live-shape check, DAG mapping`

---

### Task 4: Room v3: canvas tables, CanvasDao, migration, Delete all and Forget cascade

**Files:**
- Create: `services/memory/src/main/java/com/rokid/context/memory/db/CanvasEntities.kt`, `CanvasDao.kt`
- Modify: `ContextDatabase.kt` (add the 6 entities, `version = 3`, `abstract fun canvasDao(): CanvasDao`, `val MIGRATION_2_3`); `DatabaseFactory.kt` (`.addMigrations(MIGRATION_1_2, MIGRATION_2_3)` at both builder sites); `WipeDao.kt`; `ForgetDao.kt`; `memory/forget/ForgetService.kt`
- Generated: `services/memory/schemas/com.rokid.context.memory.db.ContextDatabase/3.json` (commit it)
- Test: `services/memory/src/test/java/com/rokid/context/memory/db/Migration2To3Test.kt`, `CanvasDaoTest.kt`; extend `WipeDaoTest.kt`; add `services/memory/src/test/java/com/rokid/context/memory/forget/ForgetCanvasTest.kt`

**Entities:** exactly the §7 table, with the column names and nullability given there.
- `canvas_run` has indexes on `pipelineId` and `startedAt`; `canvas_run_item` and `canvas_score` have an index on `frameId`.
- `canvas_publish.id` is `@PrimaryKey(autoGenerate = true) val id: Long = 0`.
- Entity names: `CanvasPipelineEntity`, `CanvasPipelineVersionEntity`, `CanvasRunEntity`, `CanvasRunItemEntity`, `CanvasScoreEntity`, `CanvasPublishEntity`.

**`CanvasDao` (Produces):**
```kotlin
@Dao abstract class CanvasDao {
    // pipelines and versions
    @Upsert abstract suspend fun upsertPipeline(p: CanvasPipelineEntity)
    @Query("SELECT * FROM canvas_pipeline WHERE id = :id") abstract suspend fun pipeline(id: String): CanvasPipelineEntity?
    @Query("SELECT * FROM canvas_pipeline ORDER BY builtIn DESC, updatedAt DESC") abstract suspend fun pipelines(): List<CanvasPipelineEntity>
    @Query("SELECT * FROM canvas_pipeline ORDER BY builtIn DESC, updatedAt DESC") abstract fun observePipelines(): Flow<List<CanvasPipelineEntity>>
    @Query("DELETE FROM canvas_pipeline WHERE id = :id AND builtIn = 0") abstract suspend fun deletePipeline(id: String): Int
    /** Compare-and-set on draftRev: returns rows updated (0 = stale or built-in). */
    @Query("UPDATE canvas_pipeline SET draftJson = :json, draftRev = draftRev + 1, updatedAt = :now WHERE id = :id AND draftRev = :rev AND builtIn = 0")
    abstract suspend fun saveDraft(id: String, json: String, rev: Int, now: Long): Int
    @Insert abstract suspend fun insertVersion(v: CanvasPipelineVersionEntity)
    @Query("SELECT * FROM canvas_pipeline_version WHERE pipelineId = :id ORDER BY version DESC") abstract suspend fun versions(id: String): List<CanvasPipelineVersionEntity>
    @Query("SELECT * FROM canvas_pipeline_version WHERE pipelineId = :id AND version = :v") abstract suspend fun version(id: String, v: Int): CanvasPipelineVersionEntity?
    // runs
    @Insert abstract suspend fun insertRun(r: CanvasRunEntity)
    @Query("UPDATE canvas_run SET status = :status, calls = :calls, failures = :failures, finishedAt = :finishedAt WHERE runId = :id")
    abstract suspend fun updateRun(id: String, status: String, calls: Int, failures: Int, finishedAt: Long?)
    @Query("SELECT * FROM canvas_run WHERE runId = :id") abstract suspend fun run(id: String): CanvasRunEntity?
    @Query("SELECT * FROM canvas_run ORDER BY startedAt DESC LIMIT :limit") abstract suspend fun runs(limit: Int): List<CanvasRunEntity>
    @Query("SELECT * FROM canvas_run ORDER BY startedAt DESC LIMIT :limit") abstract fun observeRuns(limit: Int): Flow<List<CanvasRunEntity>>
    @Query("UPDATE canvas_run SET status = 'INTERRUPTED', finishedAt = :now WHERE status = 'RUNNING'") abstract suspend fun interruptRunning(now: Long): Int
    @Transaction open suspend fun deleteRun(id: String) { deleteItems(id); deleteScores(id); deleteRunRow(id) }
    /** Keeps the newest [keep] runs (by startedAt) and deletes the rest with their items and scores. */
    @Transaction open suspend fun pruneRuns(keep: Int) { /* select ids beyond keep, deleteRun each */ }
    @Upsert abstract suspend fun upsertItem(i: CanvasRunItemEntity)
    @Query("SELECT * FROM canvas_run_item WHERE runId = :id") abstract suspend fun items(id: String): List<CanvasRunItemEntity>
    @Upsert abstract suspend fun upsertScore(s: CanvasScoreEntity)
    @Query("SELECT * FROM canvas_score WHERE runId = :id") abstract suspend fun scores(id: String): List<CanvasScoreEntity>
    /** How many of [nodeIds] have at least one OK item in a DONE run of [hash] (publish's "tested" gate). */
    @Query("SELECT COUNT(DISTINCT i.nodeId) FROM canvas_run_item i JOIN canvas_run r ON r.runId = i.runId WHERE r.pipelineHash = :hash AND r.status = 'DONE' AND i.status = 'OK' AND i.nodeId IN (:nodeIds)")
    abstract suspend fun testedNodeCount(hash: String, nodeIds: List<String>): Int
    // publish
    @Insert abstract suspend fun insertPublish(p: CanvasPublishEntity): Long
    @Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL ORDER BY id DESC") abstract suspend fun activePublishes(): List<CanvasPublishEntity>
    @Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL ORDER BY id DESC") abstract fun observeActivePublishes(): Flow<List<CanvasPublishEntity>>
    @Query("UPDATE canvas_publish SET rolledBackAt = :at WHERE id = :id AND rolledBackAt IS NULL") abstract suspend fun markRolledBack(id: Long, at: Long): Int
    // frames for the picker (read-only joins over existing tables)
    @Query("SELECT f.id AS id, f.ts AS ts, m.motion AS motion, m.dwell AS dwell, m.blur AS blur, s.state AS state, " +
        "(SELECT COUNT(*) FROM frame_source fs WHERE fs.frameId = f.id) + (SELECT COUNT(*) FROM memory mm WHERE mm.frameId = f.id) AS liveCount " +
        "FROM frame f LEFT JOIN frame_meta m ON m.frameId = f.id LEFT JOIN frame_state s ON s.frameId = f.id " +
        "WHERE f.ts < :beforeMs AND (:dwellOnly = 0 OR m.dwell = 1) AND f.id NOT IN (SELECT frameId FROM forget_tombstone) ORDER BY f.ts DESC LIMIT :limit")
    abstract suspend fun recentFrames(beforeMs: Long, dwellOnly: Boolean, limit: Int): List<CanvasFrameRow>
    @Query("SELECT id, text, type FROM memory WHERE frameId = :frameId OR id IN (SELECT memoryId FROM frame_source WHERE frameId = :frameId) ORDER BY createdAt")
    abstract suspend fun liveMemories(frameId: String): List<LiveMemoryRow>
    // private helpers deleteItems / deleteScores / deleteRunRow, and the forget helpers below
    @Query("DELETE FROM canvas_run_item WHERE frameId IN (:frameIds)") abstract suspend fun deleteItemsForFrames(frameIds: List<String>): Int
    @Query("DELETE FROM canvas_score WHERE frameId IN (:frameIds)") abstract suspend fun deleteScoresForFrames(frameIds: List<String>): Int
}
data class CanvasFrameRow(val id: String, val ts: Long, val motion: String?, val dwell: Boolean?, val blur: Float?, val state: String?, val liveCount: Int)
data class LiveMemoryRow(val id: String, val text: String, val type: String)
```

**Cascade:**
- `WipeDao.wipeMemory` also deletes all rows of `canvas_run_item`, `canvas_score` and `canvas_run`, inside its existing transaction. Pipelines, versions and publishes stay.
- `ForgetService`: right after **each** `dao.insertTombstone(...)` site (`forgetMoment`; `cascade` when `lockFrames`), delete the canvas items and scores for those frame ids. Either add the two `@Query` deletes to `ForgetDao`, or call `db.canvasDao()`; both are in the same transaction.
- Check that `ExportDao`, `MemoryExport` and `ConfigExport` select named tables only. If any enumerates every table, exclude `canvas_*` and add a test.

**Tests:**
- `Migration2To3Test`: `v2RowsSurvive` (seed v2 via `helper.createDatabase(name, 2)` with one row per v2 table, extending `V1Fixture` with a v2 seed), `sixCanvasTablesExistEmpty`, `noV2TableChanged` (columns of every v2 table equal in 2.json and 3.json), `migratedMatchesExportedSchema` (`runMigrationsAndValidate(name, 3, true, MIGRATION_2_3)`), `v1ToV3Chain`.
- `CanvasDaoTest`: `saveDraftCasOnRev`, `builtInIsReadOnly`, `versionsNewestFirst`, `interruptRunningMarksOnlyRunning`, `pruneKeepsNewest20` (insert 23, keep 20, items and scores of the pruned runs gone), `testedNodeCountOnlyDoneOk`, `activePublishesNewestFirstAndRollback`, `recentFramesSkipsTombstonedAndFiltersDwell`, `liveMemoriesViaFrameIdAndFrameSource`.
- `WipeDaoTest.wipeClearsCanvasRunsKeepsPipelines`.
- `ForgetCanvasTest`: `forgetMomentDeletesCanvasItemsAndScores`, `forgetFactsWithLockDeletesCanvasForLockedFrames`, `otherFramesUntouched`.

**Test command:** `gw.sh :services:memory:testDebugUnitTest --tests 'com.rokid.context.memory.db.*' --tests 'com.rokid.context.memory.forget.*'`

**Commit:** `feat(memory): Room v3 canvas tables (additive) with migration, Delete all and Forget cascade`

---

### Task 5: Node executors (dry run)

**Files (new, `services/orchestration/src/main/java/com/rokid/context/canvas/`):** `CanvasValues.kt`, `NodeExecutor.kt`, `Executors.kt` (one class per type), `CanvasValueJson.kt`
**Test:** `services/orchestration/src/test/java/com/rokid/context/canvas/ExecutorsTest.kt`, `CanvasValueJsonTest.kt`

**Interfaces (Produces):**
```kotlin
package com.rokid.context.canvas
sealed interface CanvasValue {
    data object Photo : CanvasValue
    data class Extraction(val facts: List<Fact>, val captions: List<FrameCaption>, val trusted: Boolean? = null, val note: String? = null) : CanvasValue
    data class Json(val value: JsonElement) : CanvasValue
    data class Preview(val rows: List<PreviewRow>) : CanvasValue
}
data class PreviewRow(val kind: String /* "fact" | "caption" */, val text: String, val type: String, val tier: String, val review: String)
data class CanvasItem(val frameId: String, val photo: ExtractPhoto, val value: CanvasValue)
data class CallMeta(val latencyMs: Long, val promptTokens: Int?, val completionTokens: Int?, val model: String?, val promptVersion: Int?, val batchId: String?)
data class NodeResult(val frameId: String, val value: CanvasValue?, val branch: String? = null, val error: String? = null, val meta: CallMeta? = null, val calls: Int = 0)
class RunContext(val runId: String, val runner: RoleRunner, val model: LanguageModel, val prompts: PromptRepository,
                 val persona: PersonaSettings, val bindings: suspend (Role) -> RoleBinding?, val now: () -> LocalDateTime = LocalDateTime::now)
interface NodeExecutor {
    val type: String
    fun chunkSize(node: PipelineNode): Int = 1
    fun nominalCalls(node: PipelineNode, items: Int): Int
    fun maxCalls(node: PipelineNode, items: Int): Int
    suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext): List<NodeResult>   // one result per input item, same order
}
object Executors { fun all(): Map<String, NodeExecutor> }   // every type except input.photos (the runner owns photo loading)
object CanvasValueJson { fun encode(v: CanvasValue): String; fun branchOf(r: NodeResult): String? = r.branch }
```

**Behaviour:**
- **`VisionExtractExecutor`:**
  - chunk = the `batch` param. It calls `ExtractSave.extract(ctx.runner, photos, CallClass.USER, override, contextSources)`.
  - `override` = `PromptDraft(template, schema)` for a Draft prompt, null for Live. `contextSources` = `PromptComposer.dataBlock("upstream", [compact JSON of the item's Json value])` when the item carries Json, else "".
  - Each item gets the facts whose `sourceFrameIds` contain its frameId, plus its own caption.
  - Meta: latency and tokens are not exposed by `ExtractedBatch`. Record `promptVersion` and `batchId` = `"<runId>:<nodeId>:<chunkIndex>"`; latency is the measured wall time of the call.
  - nominal = ceil(n / batch); max = nominal × 2 + (n if batch > 1 else 0).
  - A thrown `ModelCallException` gives every item of the chunk `error = kind.name`. Any other exception gives `error = simpleName`. Cancellation is rethrown.
- **`LocalCriticExecutor`:** branch `trusted` when `ExtractFollowUp.locallyTrusted(CriticWorker(), "${ctx.runId}:${frameId}", facts)` is true, else `unsure`. The value is copied with `trusted` set. 0 calls.
- **`CriticExecutor`:**
  - With the CRITIC binding null: branch `fail`, `note = "unbound"`, 0 calls.
  - Otherwise: `ctx.runner.run(RoleCall(CRITIC, ExtractFollowUp.criticVars(facts, [photo.description]), callClass = USER, override = …))`. Branch `pass` or `fail` from `CriticOutput.pass`, and `trusted` = pass.
  - A failure gives branch `fail` with `error = kind`, as live keeps the local verdict.
  - nominal = n; max = 2n.
- **`PromptExecutor` (`ai.prompt`):**
  - Builds the prompt with `PromptComposer.compose(prompts.active(SYSTEM).template, template, PromptVars(recentContext = dataBlock("frames", ["frame_id: <id> - <description>"]), contextSources = upstream block).toMap(persona, NowText.of(now)))`.
  - Calls `ctx.model.complete(role, CallClass.USER) { e -> plan = StructuredOutput.plan(e, schemaObj, name); ModelRequest(prompt + plan.promptSuffix, if (images) [photo.image] else [], format = plan.format) }`.
  - Parses with `ModelJson.parse` (null gives `error = "NOT_JSON"`) and validates with `JsonSchemaValidator.validate(schemaObj, json)`; any problem gives `error = "INVALID_OUTPUT"` with the value still stored, for debugging.
  - An unbound role gives `error = "NOT_SET"`. nominal = n; max = n.
- **`SwitchExecutor`:** the branch is the string value of `field` when it's in `cases`, else `other`. The value is passed through. 0 calls.
- **`MemoryWritePreviewExecutor`:** facts become rows `kind=fact`, `tier=SHORT`, `review = if (trusted == true) "OK" else "NEEDS_REVIEW"`; captions become `kind=caption`, `tier=LONG`, `review=OK`, as `ExtractSave.saveExtraction` and `KeyframeHandle.extract` would do. It **writes nothing**.
- **`CanvasValueJson.encode`** gives a stable JSON: `{"kind":"extraction","facts":[{"text","type","confidence","source_frame_ids"}],"captions":[…],"trusted":…,"note":…}`, `{"kind":"json","value":…}`, `{"kind":"preview","rows":[…]}`, `{"kind":"photo"}`.

**Tests (`ExecutorsTest`, Robolectric with an in-memory DB for the PromptRepository, fake LanguageModel):**
- `extractBatchesByParamAndAttributesPerPhoto`;
- `extractDraftOverrideReachesModel`;
- `extractUpstreamJsonInContextSources`;
- `extractErrorMarksWholeChunk`;
- `localCriticBranches`;
- `criticUnboundFailsWithNote`;
- `criticOverrideUsed`;
- `promptValidJson`, `promptInvalidOutputKeepsValue`, `promptNotJson`, `promptImagesFlag`;
- `switchKnownAndOther`;
- `previewTiersAndReview`;
- `executorsWriteNothing` (row counts of `memory`, `frame_state`, `frame_source` and `memory_vector` unchanged);
- `callEstimates` (nominal and max per type).
- `CanvasValueJsonTest`: `stableEncoding`.

**Test command:** `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'`

**Commit:** `feat(orchestration): canvas node executors (dry run over the live AI path)`

---

### Task 6: CanvasRunner, estimate, confirm tokens

**Files (new, `services/orchestration/src/main/java/com/rokid/context/canvas/`):** `CanvasRunner.kt`, `ConfirmTokens.kt`, `PhotoSource.kt`
**Test:** `services/orchestration/src/test/java/com/rokid/context/canvas/CanvasRunnerTest.kt`, `ConfirmTokensTest.kt`

**Interfaces (Produces):**
```kotlin
fun interface PhotoSource { suspend fun load(frameIds: List<String>): PhotoLoad }
data class PhotoLoad(val photos: List<ExtractPhoto>, val missing: Set<String>)   // the prod adapter wraps FramePhotos.load (gone + unseen = missing)
data class ModelLabel(val nodeId: String, val endpoint: String, val model: String)
sealed interface Estimate {
    data class Ok(val photos: Int, val nominal: Int, val max: Int, val models: List<ModelLabel>, val confirmToken: String) : Estimate
    data class Refused(val reasons: List<String>) : Estimate   // "more than 50 photos", "more than 200 model calls", "pipeline has problems", "no photos"
}
class ConfirmTokens(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) {
    fun issue(hash: String, frameIds: List<String>): String           // 16 random bytes hex; TTL 5 min
    fun consume(token: String, hash: String, frameIds: List<String>): Boolean   // single use; the frame-id set must match
}
class CanvasRunner(
    private val dao: CanvasDao, private val photos: PhotoSource, private val executors: Map<String, NodeExecutor>,
    private val context: (runId: String) -> RunContext, private val tokens: ConfirmTokens,
    private val hot: () -> Boolean, private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    companion object { const val MAX_PHOTOS = 50; const val MAX_NOMINAL_CALLS = 200; const val KEEP_RUNS = 20 }
    suspend fun estimate(p: Pipeline, frameIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate
    /** Throws [Busy] when a run is active, [IllegalArgumentException] for a bad or expired token. Returns the runId; the run continues in [scope]. */
    suspend fun start(p: Pipeline, pipelineId: String, version: Int?, frameIds: List<String>, confirmToken: String, scope: CoroutineScope): String
    fun cancel(runId: String): Boolean
    val active: StateFlow<String?>        // the runId in progress, or null
    suspend fun recoverAtStart(): Int = dao.interruptRunning(clock())
    class Busy : IllegalStateException("busy")
}
```

**Behaviour (§5):**
- `start`:
  - validates the pipeline (problems → `IllegalArgumentException("invalid_pipeline")`), consumes the token, and calls `dao.pruneRuns(KEEP_RUNS - 1)`;
  - inserts `CanvasRunEntity(status = RUNNING, pipelineJson = canonical, pipelineHash, frameCount, estCalls = nominal, modelsJson)`;
  - launches the execution.
- **Execution:**
  - It loads the photos; each missing one gets an item `ERROR "unreadable"` at the `input.photos` node, and its own `input.photos` item is `OK` with kind photo.
  - It walks the nodes in topological order (ties broken by node order in the JSON). For each node it collects the items arriving on its incoming edges, per the edge rule in §5; an item arriving twice gets `ERROR "merge_conflict"`.
  - Items are chunked by `chunkSize` and run through the executor. Each `NodeResult` is upserted as soon as its chunk ends, as `CanvasRunItemEntity(status OK/ERROR, branch, outputJson, error, meta…)`. Items that never reach a node get no row.
  - After each chunk it calls `updateRun` with the running calls and failures, and it checks cancellation and `hot()`.
- **End:** `DONE`, `CANCELLED` (cancel called), `STOPPED_HOT` (`hot()` true before a chunk) or `FAILED` (an unexpected exception: fixed text in the log, simple class name only), with `finishedAt` set.
- **Single-flight:** guarded by a `Mutex` plus `active`.

**Tests (`CanvasRunnerTest`, fake executors plus a real in-memory DB):**
- `estimateCountsUpperBound`, `estimateRefusesOver50Photos`, `estimateRefusesOver200Calls`;
- `startNeedsValidToken`, `tokenSingleUseAndFrameBound`, `secondRunIsBusy`;
- `topoOrderAndBranchRouting` (live-extract with a fake local critic that splits the items 2/1: critic sees 1 item, write sees 3);
- `switchRoutesPerItem` (intent-draft with a fake switch: each extract_<label> gets only its items);
- `mergeConflictIsItemError`, `missingPhotoIsItemError`, `itemsPersistedPerChunk`;
- `cancelStopsBeforeNextChunk`, `hotStopsAsStoppedHot`, `recoverMarksInterrupted`, `pruneKeepsTwentyIncludingNew`;
- `snapshotStoresCanonicalJsonAndHash`;
- `runWritesNoLiveRows` (with the real executors and a fake model).
- `ConfirmTokensTest`: `expiresAfterFiveMinutes`, `rejectsDifferentFrames`.

**Test command:** `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'`

**Commit:** `feat(orchestration): canvas runner over N photos with estimate, confirm, cancel and recovery`

---

### Task 7: CanvasRepository (pipelines, versions) and CanvasPublisher (freeze, publish, rollback)

**Files (new, `services/orchestration/src/main/java/com/rokid/context/canvas/`):** `CanvasRepository.kt`, `CanvasPublisher.kt`
**Test:** `services/orchestration/src/test/java/com/rokid/context/canvas/CanvasRepositoryTest.kt`, `CanvasPublisherTest.kt`

**Interfaces (Produces):**
```kotlin
class CanvasRepository(private val dao: CanvasDao, private val seedTemplate: suspend (PromptKey) -> String,
                       private val clock: () -> Long = System::currentTimeMillis, private val newId: () -> String = { "p-" + randomHex(8) }) {
    suspend fun ensureBuiltIns()   // upserts live-extract and intent-draft as builtIn = 1 with the current code's JSON (overwritten each call; they're read-only)
    suspend fun list(): List<CanvasPipelineEntity>
    suspend fun get(id: String): CanvasPipelineEntity?
    suspend fun duplicate(from: String, version: Int?, name: String): String   // a new editable draft (builtIn = 0, draftRev = 0); its JSON "id" is set to the new id
    sealed interface SaveResult { data class Saved(val rev: Int, val problems: List<String>) : SaveResult; data object Conflict : SaveResult; data object ReadOnly : SaveResult; data class Unreadable(val reason: String) : SaveResult }
    suspend fun saveDraft(id: String, json: String, draftRev: Int): SaveResult   // undecodable JSON → Unreadable (not saved); decodable but invalid → saved with problems
    suspend fun freeze(id: String, note: String): CanvasPipelineVersionEntity     // invalid → IllegalArgumentException("invalid_pipeline"); same hash as the last version → returns it
    suspend fun delete(id: String): Boolean                                       // false for built-in, or when an active publish names it
}
class CanvasPublisher(private val dao: CanvasDao, private val prompts: PromptRepository,
                      private val compatibility: suspend (PromptKey, PromptDraft) -> List<String>, private val clock: () -> Long = System::currentTimeMillis) {
    sealed interface Outcome { data class Published(val publishId: Long, val keys: List<PromptKey>) : Outcome; data class Blocked(val reasons: List<String>) : Outcome }
    suspend fun publish(pipelineId: String, version: Int): Outcome
    suspend fun rollback(): Outcome            // Published(id, keys) = what was undone; Blocked(reasons)
}
```

**Publish rules (§6), exact reason texts:**
- `"Only pipelines shaped like Live extraction can go live in this version"`
- `"Nothing to publish: every prompt is Live"`
- `"<label>: <compat problem>"` (from `compatibility`)
- `"Run this version first: <node ids> have no successful run"`

**Publish steps:**
1. Find the draft nodes with `LiveShape.draftPromptNodes`; their key comes from the node type's `promptKey`.
2. Snapshot each key: `{"key","version","template","schema"}` from `prompts.active(key)`.
3. `prompts.save(key, PromptDraft(t, s), note = "Canvas: ${name.take(50)} v$version", testedOk = true)`.
4. Record `snapshotJson = {"prev":[…],"published":[{"key","fp":PromptDefaults.fingerprint(t, s)}]}` with `insertPublish`.

**Rollback:** takes `activePublishes().firstOrNull()`; none → `Blocked(["Nothing to roll back"])`. For each published key, the current `PromptDefaults.fingerprint(active.template, active.outputSchema)` must equal `fp`; otherwise → `Blocked(["<label> was changed after the publish. Use Settings → Prompts → History instead."])`. Then `prompts.save(key, prev, note = "Canvas rollback", testedOk = true)` for each key, and `markRolledBack`.

**Tests:**
- `CanvasRepositoryTest`: `builtInsSeededAndRefreshed`, `duplicateFromVersionAndDraft`, `saveDraftConflictOnStaleRev`, `builtInReadOnly`, `unreadableNotSaved`, `invalidSavedWithProblems`, `freezeIncrementsAndDedupesSameHash`, `freezeRejectsInvalid`, `deleteRefusedWhenPublished`.
- `CanvasPublisherTest` (real PromptRepository on an in-memory DB, with keepVersions both on and off): `publishBlockedNotLiveShaped`, `publishBlockedNothingDraft`, `publishBlockedUntested`, `publishBlockedCompat`, `publishSavesDraftPromptAndSnapshot`, `liveRunnerSeesPublishedPrompt` (RoleRunner afterwards sends the published text), `rollbackRestoresPrevious`, `rollbackRefusedWhenPromptChangedSincePublish`, `stackedPublishesRollBackNewestFirst`, `versioningOffRollbackStillRestoresText`.

**Test command:** `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'`

**Commit:** `feat(orchestration): canvas pipelines repository, freeze, publish to live prompts, guarded rollback`

---

### Task 8: Canvas server core: NanoHTTPD adapter, auth, host policy, headers, static assets

**Files:**
- Modify: `phone/build.gradle.kts` (add `implementation("org.nanohttpd:nanohttpd:2.3.1")`)
- Create (`phone/src/main/java/com/rokid/context/phone/canvas/server/`): `Http.kt`, `HostPolicy.kt`, `CanvasAuth.kt`, `CanvasRouter.kt`, `CanvasServer.kt`
- Test: `phone/src/test/java/com/rokid/context/phone/canvas/server/CanvasAuthTest.kt`, `HostPolicyTest.kt`, `CanvasRouterTest.kt`, `CanvasServerTest.kt`

**Interfaces (Produces):**
```kotlin
data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String> /* lower-case keys */, val body: ByteArray)
data class HttpResponse(val status: Int, val contentType: String, val body: ByteArray, val headers: Map<String, String> = emptyMap()) {
    companion object { fun json(status: Int, body: JsonElement): HttpResponse; fun error(status: Int, code: String, message: String, problems: List<String> = emptyList()): HttpResponse }
}
class HostPolicy(private val port: () -> Int, private val lanIp: () -> String? /* null = LAN off */) {
    fun hostAllowed(host: String?): Boolean
    fun originAllowed(method: String, origin: String?, host: String?): Boolean   // GET/HEAD always true; else origin == null || origin == "http://$host"
}
class CanvasAuth(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) {
    companion object { const val CODE_TTL_MS = 5 * 60_000L; const val MAX_TRIES = 5; const val LOCK_MS = 30_000L
                       const val IDLE_MS = 30 * 60_000L; const val MAX_AGE_MS = 12 * 3_600_000L; const val MAX_SESSIONS = 3 }
    data class Code(val code: String, val expiresAt: Long)
    sealed interface Pair { data class Ok(val token: String) : Pair; data class Wrong(val triesLeft: Int) : Pair; data class Locked(val untilMs: Long) : Pair; data object NoCode : Pair }
    fun newCode(): Code                    // 6 digits, zero-padded; replaces any previous code; resets the tries
    fun currentCode(): Code?               // null when expired or used
    fun pair(code: String): Pair           // single use; constant-time compare; MAX_TRIES wrong in total → code voided + lock LOCK_MS
    fun newWebViewToken(): String          // a session without pairing (the app's own WebView)
    fun check(token: String?): Boolean     // valid → touch idle; expired → removed
    fun logout(token: String); fun revokeAll(); fun sessionCount(): Int
    val lastActivityMs: Long               // the newest successful check (for the service's idle rule)
}
fun interface AssetSource { fun open(path: String): ByteArray? }   // prod: context.assets.open("canvas/$path")
fun interface ApiHandler { fun handle(req: HttpRequest): HttpResponse }   // Task 9 provides the real one
class CanvasRouter(private val auth: CanvasAuth, private val hosts: HostPolicy, private val api: ApiHandler,
                   private val assets: AssetSource, private val log: (String) -> Unit = {}) { fun handle(req: HttpRequest): HttpResponse }
class CanvasServer(private val router: CanvasRouter, private val bindLan: Boolean) {
    companion object { const val PORT = 8765; val FALLBACK = 8766..8770; const val MAX_BODY = 256 * 1024 }
    fun start(): Int      // binds 127.0.0.1 (or 0.0.0.0 when bindLan) on PORT, then FALLBACK; returns the port; throws IOException when all are busy
    fun stop(); val port: Int?
}
```

**Router order:**
1. Host check → 421 `forbidden_host`.
2. Body over `MAX_BODY` → 413 `too_large`.
3. Origin check for non-GET → 403 `forbidden`.
4. `/api/pair` (POST, no token; JSON `{"code"}`): `Ok` → 200 `{"token"}`; `Wrong` → 401 with `triesLeft`; `Locked` → 429; `NoCode` → 401.
5. Other `/api/*` without a valid `Authorization: Bearer` → 401 `unauthorized`.
6. Valid → `api.handle`.
7. Otherwise a static asset: `/` → `index.html`; only paths matching `^/[a-z0-9_-]+\.(html|js|css|svg)$`; content types by extension; missing → 404.

**Headers:** every response carries the §8 headers (CSP, nosniff, no-referrer, no-store). The log line is `"$method ${template(path)} $status ${ms}ms"`, where `template` replaces every path segment after `/api/<name>/` with `:id`.

**`CanvasServer`** converts `NanoHTTPD.IHTTPSession` to `HttpRequest`: it reads the body by `content-length` up to `MAX_BODY`, and a larger body gives 413 without reading it all. It lower-cases the header keys. It runs `router.handle` on NanoHTTPD's thread; the API's suspend calls use `runBlocking(Dispatchers.IO)` with `withTimeout(30_000)` inside Task 9's handler.

**Tests:**
- `CanvasAuthTest`: `codeIsSixDigits`, `codeSingleUse`, `codeExpires`, `fiveWrongTriesVoidAndLock`, `lockLifts`, `tokenIdleExpiry`, `tokenMaxAge`, `fourthSessionEvictsOldest`, `revokeAll`, `webViewTokenValid`.
- `HostPolicyTest`: `loopbackHostsAllowed`, `lanHostOnlyWhenOn`, `rebindHostRejected`, `originMustMatchHostForPost`, `missingOriginAllowed`.
- `CanvasRouterTest` (fake ApiHandler and AssetSource): `apiWithoutTokenIs401` (every method), `pairFlow`, `pairLockedIs429`, `staticNeedsNoToken`, `pathTraversalRejected` (`/../x`, `/%2e%2e/x`, `/a/b.js`), `securityHeadersOnEveryResponse`, `oversizeBodyIs413`, `logLineHasNoIdsOrQuery`.
- `CanvasServerTest` (a real server on loopback, OkHttp client): `servesIndexAndApi`, `fallsBackWhenPortBusy`, `lanOffBindsLoopbackOnly` (the bound address is 127.0.0.1), `stopFreesPort`.

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.server.*'`

**Commit:** `feat(phone): canvas local server core (NanoHTTPD) with pairing, tokens, host/origin checks`

---

### Task 9: Canvas API routes and backend

**Files (new, `phone/src/main/java/com/rokid/context/phone/canvas/api/`):** `CanvasBackend.kt` (interface), `CanvasApi.kt` (`ApiHandler` implementation: routing, JSON in and out), `CanvasBackendImpl.kt` (wiring to repo, runner, publisher, DAO, frames, AiGraph), `FrameImagesHttp.kt` (thumb and image bytes)
**Test:** `phone/src/test/java/com/rokid/context/phone/canvas/api/CanvasApiTest.kt` (fake backend), `CanvasBackendImplTest.kt` (Robolectric, in-memory DB, fake model)

**Interfaces (Produces):**
```kotlin
interface CanvasBackend {
    suspend fun session(token: String): JsonObject
    fun nodeTypes(): JsonArray
    suspend fun pipelines(): JsonArray
    suspend fun pipeline(id: String): JsonObject?           // {json, draftRev, builtIn, problems, layout, versions:[{version,hash,note,frozenAt}], liveShape, live:{version}|null,
                                                             //  activePrompts:{"<nodeId>":{template,schema,version}} for every role-backed node}
    suspend fun duplicate(from: String, name: String): String
    suspend fun saveDraft(id: String, json: String, rev: Int): CanvasRepository.SaveResult
    suspend fun deletePipeline(id: String): Boolean
    suspend fun freeze(id: String, note: String): JsonObject
    suspend fun publish(id: String, version: Int): CanvasPublisher.Outcome
    suspend fun activePublish(): JsonObject
    suspend fun rollback(): CanvasPublisher.Outcome
    suspend fun checkPrompt(nodeType: String, template: String, schema: String?): List<String>
    suspend fun frames(beforeMs: Long, limit: Int, dwellOnly: Boolean): JsonArray
    suspend fun frame(id: String): JsonObject?               // meta + live:[{id,text,type}]
    suspend fun frameImage(id: String, thumb: Boolean): ByteArray?
    suspend fun estimate(pipelineId: String, version: Int?, frameIds: List<String>): Estimate
    suspend fun startRun(pipelineId: String, version: Int?, frameIds: List<String>, token: String): String
    suspend fun runs(): JsonArray
    suspend fun run(id: String): JsonObject?                 // run row + items + scores + progress {done, total}
    suspend fun cancelRun(id: String): Boolean
    suspend fun deleteRun(id: String): Boolean
    suspend fun score(runId: String, frameId: String, nodeId: String, score: Int, note: String)
    suspend fun compare(a: String, b: String): JsonObject?   // {frames:[{frameId, nodes:[{nodeId, a, b, diff:[{op,text}]}]}]}
}
class CanvasApi(private val backend: CanvasBackend, private val callTimeoutMs: Long = 30_000) : ApiHandler
```

**Routes:** exactly the §8 API table.
- Status mapping:
  - `SaveResult.Conflict` → 409 `conflict`; `ReadOnly` → 403 `forbidden`; `Unreadable` → 400 `bad_request`;
  - `Blocked` → 409 `publish_blocked` or `rollback_blocked` with the reasons in `problems`;
  - `Estimate.Refused` → 422 `refused`; `CanvasRunner.Busy` → 409 `busy`;
  - a bad token → 400 `bad_request`; an unknown id → 404 `not_found`; bad JSON or params → 400.
- Limits: `limit` is clamped to 1..120, and `frameIds` to 50 entries of at most 64 characters each.
- Ids in paths must match `^[A-Za-z0-9:_-]{1,64}$`, else 400.
- Score must be −1, 0 or 1; note at most 280 characters.

**`CanvasBackendImpl`:**
- **Runner `context` factory:** builds `RunContext(runId, ai.runner, AiGraph's LanguageModel, ai.prompts, settings.persona, ai.bindings::get)`.
- **`labels`:** for each AI node, the role binding's endpoint name and model. For a node whose role binding is null, the label is endpoint `"not set"`.
- **`PhotoSource`:** wraps `FramePhotos.load(app, ids)`.
- **`hot`:** `KeyframeGraph.get(app).power.severe()`.
- **`checkPrompt`:** role-backed nodes call `ai.testBed.compatibility(key, PromptDraft(template, schema))`; `ai.prompt` checks that the schema parses as a JSON object and runs `SchemaCompatibilityChecker.check(<nearest key>, …)` only if it applies. Otherwise it returns the parse problem.
- **`frameImage`:** finds the frame via `PipelineRecords.framesByIds(app, listOf(id))` and decodes `FrameInputs.fromFrames(listOf(f), 1)` base64 to bytes. For a thumb, it decodes with `BitmapFactory` at `inSampleSize` = the largest power of 2 keeping the long side ≥ 160, scales to a 160 px long side, and writes JPEG quality 80.
- **`compare`:** for frames present in both runs and node ids present in both, it pretty-prints each `outputJson` (or error) and runs `LineDiff.diff(aText, bText)`.

**Tests:**
- `CanvasApiTest`: one test per route for its happy path and its error mapping; `idValidation`, `limitClamped`, `scoreRange`, `timeoutIs503` (backend suspends longer than the timeout → 503 `busy`).
- `CanvasBackendImplTest`: `pipelinesIncludeBuiltIns`, `pipelineHasLayoutAndProblems`, `estimateListsModels`, `endToEndRunOnFakeFrames` (3 seeded frames with sealed test images through live-extract with the fake model → DONE, items per node), `frameLiveMemoriesListed`, `thumbIsSmallJpeg`, `compareDiffs`.

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.api.*'`

**Commit:** `feat(phone): canvas JSON API over pipelines, frames, runs, scores, compare, publish`

---

### Task 10: Web UI static bundle (plain JS, no build)

**Files (new, `phone/src/main/assets/canvas/`):** `index.html`, `app.js` (bootstrap, token, hash router, `api()` client), `dom.js` (`h(tag, attrs, ...children)` using `textContent` and `setAttribute` only), `dag.js` (SVG from layout cells), `views.js` (pipelines, pipeline, run-new, run, compare), `style.css`
**Test:** `phone/src/test/java/com/rokid/context/phone/canvas/CanvasAssetsTest.kt` (a JVM source scan of the asset folder)

**Behaviour:**
- **Boot:**
  - If `location.hash` starts with `#t=`, store the token in `sessionStorage['pcToken']` and call `history.replaceState(null, '', '/#/')`.
  - If the hash is `#pair=<code>`, prefill the pairing form.
  - With no token, show the pairing form → `POST /api/pair` → store the token.
- **API client:** `api(method, path, body)` adds `Authorization: Bearer`. A 401 clears the token and shows pairing. Errors show `message` plus the `problems` list.
- **Images:** loaded with `fetch` + Bearer → `URL.createObjectURL(blob)`, and revoked when the view changes.
- **DAG (`dag.js`):**
  - column x = 40 + column × 220; layer y = 40 + layer × 110; node 180 × 64 rounded rectangles;
  - the kind is shown by a CSS class: input, step, ai, output;
  - edges are cubic paths, with the `when` label at the midpoint;
  - clicking a node selects it; the SVG viewBox fits the layout and scrolls horizontally on phones.
- **Views:** per spec §9.
  - **Pipeline view:**
    - inspector with params rendered from `/api/node-types`;
    - for prompt nodes, a Live/Draft toggle; switching to Draft prefills the template and schema from the pipeline response's `activePrompts[nodeId]` (Task 9);
    - "Check" calls `/api/check-prompt`;
    - "Save draft" sends PUT with `draftRev` and shows a 409 as "Changed elsewhere, reload";
    - "Edit JSON" is a textarea with pretty JSON;
    - "Freeze" asks for a note; "Publish" shows the blocking reasons.
  - **Run-new view:**
    - a grid of thumbnails (lazy, 60 per page, "Load older" uses `before`) with filters (dwell only, motion);
    - "Select newest N" takes an input from 1 to 50;
    - Estimate → a modal: "N photos · about X model calls (at most Y) · <node>: <endpoint> / <model> …" → Start.
  - **Run view:** polls `GET /api/runs/{id}` every 2 s while RUNNING. Per photo it shows the metadata, the live result list, and the node cards in topological order, with 👍/👎 buttons and a note field (PUT on blur).
  - **Compare view:** two columns, with diff lines classed add/del/same.
- **Look:** system font; CSS variables for light and dark under `prefers-color-scheme`; works at 360 px width with no horizontal page scroll, except inside the DAG container; `button` minimum 44 px tall.
- **Size:** at most 2,000 lines of JS in total.

**Tests (`CanvasAssetsTest`):**
- `allFilesPresent`;
- `noExternalUrls` (no `http://` or `https://` in any asset);
- `noHtmlSinks` (no `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval(` or `new Function`);
- `noInlineScriptOrHandlers` (index.html has no `<script>` without `src`, no ` on[a-z]+=` attributes, no `style=`);
- `scriptsAreModules` (`type="module"`);
- `tokenNeverInQueryString` (no `?token` / `&token`);
- `jsUnderSizeBudget`.

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.CanvasAssetsTest'`

**Commit:** `feat(phone): canvas web UI (DAG view, prompt and JSON editing, photo picker, runs, compare, scores)`

---

### Task 11: App side: Developer mode, CanvasService, Canvas home, WebView, read-only DAG

**Files:**
- Create (`phone/src/main/java/com/rokid/context/phone/canvas/`): `DeveloperModeStore.kt`, `CanvasGraph.kt` (process singleton: auth, router, server, repo, runner, publisher, LAN state), `CanvasService.kt`, `IdlePolicy.kt`, `LanAddress.kt`; `ui/CanvasHome.kt` (screen + ViewModel), `ui/CanvasWeb.kt` (WebView screen + `CanvasWebPolicy`), `ui/CanvasPipelineView.kt`
- Modify: `phone/src/main/AndroidManifest.xml` (add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` and `<service android:name=".phone.canvas.CanvasService" android:exported="false" android:foregroundServiceType="dataSync" />`); `ui/settings/Diagnostics.kt` (Developer mode switch at the bottom); `ui/settings/SettingsRoot.kt` (the "Experiment canvas" row under AI, only when Developer mode is on); `ui/shell/Routes.kt` + `SettingsDestinations.kt` (routes `settings/canvas`, `settings/canvas/web`, `settings/canvas/pipeline?id={id}`)
- Test: `phone/src/test/java/com/rokid/context/phone/canvas/DeveloperModeStoreTest.kt`, `IdlePolicyTest.kt`, `CanvasWebPolicyTest.kt`, `CanvasHomeTest.kt` (Compose), `CanvasSettingsEntryTest.kt`

**Interfaces and values:**
```kotlin
class DeveloperModeStore(context: Context) { val enabled: StateFlow<Boolean>; fun set(on: Boolean) }   // prefs "pc_dev", key "developer_mode", default false
object IdlePolicy { const val IDLE_MS = 30 * 60_000L
    fun shouldStop(lastActivityMs: Long, nowMs: Long, runActive: Boolean, screenOpen: Boolean): Boolean = !runActive && !screenOpen && nowMs - lastActivityMs >= IDLE_MS }
object CanvasWebPolicy { fun allow(url: String, port: Int): Boolean }   // only http://127.0.0.1:<port>/...; anything else → open externally
object LanAddress { fun wifiIpv4(context: Context): String? }           // ConnectivityManager LinkProperties of the Wi-Fi network; null otherwise
```
- **`CanvasGraph.get(app)`:**
  - lazily builds the pieces from Tasks 7–9, calls `runner.recoverAtStart()` and `repo.ensureBuiltIns()` once, and exposes `status: StateFlow<CanvasStatus(on, port, lanIp, code)>`;
  - `start(lan: Boolean)` (re)binds the server (LAN toggled = restart with `bindLan`), `stop()`, `setLan(on)` (on: `auth.newCode()`; off: rebind loopback);
  - `revokeAll()`; and when Developer mode turns off: `stop()` + `revokeAll()`.
- **`CanvasService`:**
  - `startForeground` with a low-importance channel `canvas`; notification text "Experiment canvas is on · <url>" with a Stop action;
  - checks `IdlePolicy` every 60 s; stops itself and the server when the policy says so.
  - It's started by the home screen's `onStart`, by LAN on and by a run start.
- **Canvas home** (Compose; §10 contents):
  - The pairing code is shown only while LAN is on or after "Open in phone browser". It is refreshed when expired, with a countdown.
  - The LAN switch shows the warning text "Photos and outputs travel unencrypted on this Wi-Fi. Turns off after 30 min idle." before it turns on, and is disabled when there's no Wi-Fi.
  - "Open in phone browser" sends an `ACTION_VIEW` of `http://127.0.0.1:<port>/#pair=<code>`.
  - "Roll back live" calls `publisher.rollback()` and shows the outcome (the reasons when blocked) in a snackbar.
- **WebView screen:**
  - loads `http://127.0.0.1:<port>/#t=<auth.newWebViewToken()>`;
  - settings: `javaScriptEnabled = true`, `domStorageEnabled = true`, `allowFileAccess = false`, `allowContentAccess = false`; no JS interface;
  - `shouldOverrideUrlLoading` uses `CanvasWebPolicy`; Back steps through WebView history, then leaves.
- **Read-only DAG:** `FlowViewModel(ids = listOf(id), ai, retrieval, registry = { pid -> pipelineAsFlow(pid) })` with `onNode = {}`. The pipeline is read from the repository and mapped by `PipelineFlows.toAgentFlow`.

**Tests:**
- `DeveloperModeStoreTest`: `defaultOff`, `persists`.
- `IdlePolicyTest`: `stopsAfter30MinIdle`, `neverDuringRun`, `neverWhileScreenOpen`.
- `CanvasWebPolicyTest`: `allowsOwnOrigin`, `rejectsOtherPortHostOrScheme`.
- `CanvasSettingsEntryTest`: `rowHiddenWhenDevModeOff`, `rowShownWhenOn`.
- `CanvasHomeTest`: `statusLineShowsUrl`, `lanSwitchShowsWarningAndCode`, `lanDisabledWithoutWifi`, `rollbackBlockedShowsReason`, `devModeOffStopsServer` (CanvasGraph fake).

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.*'`

**Commit:** `feat(phone): developer mode, canvas service, home screen, WebView editor and read-only DAG`

---

### Task 12: End-to-end test, full gate, device checklist

**Files:**
- Test: `phone/src/test/java/com/rokid/context/phone/canvas/CanvasEndToEndTest.kt` (Robolectric; real in-memory DB, real repo, runner, publisher, API and router, a real server on loopback, OkHttp client, fake LanguageModel, fake PhotoSource)

**The test (`fullFlow`):**
1. Pair with the current code.
2. `GET /api/pipelines` lists both built-ins.
3. Duplicate `live-extract`.
4. PUT the draft with the extract prompt as Draft (new template).
5. Estimate 3 frames, then start with the token, then poll until DONE.
6. Assert that the items exist for every node, that the fake model received the draft text, and that the live tables are unchanged.
7. Score one card.
8. Freeze → v1. Publish v1 → `PromptRepository.active(VISION_EXTRACT).template` equals the draft.
9. Roll back → the previous text is active again.
10. A second rollback → 409 `rollback_blocked`.

**More tests:** `forgetCascadesIntoCanvas` (run, forget one frame, `GET /api/runs/{id}` has no items for it), and `noTokenNoData` (every route 401).

**Gate:** `tools/gate.sh /tmp/pca-canvas-gate.log` from the repo root. BUILD SUCCESSFUL with 0 failures is required. Note any flaky re-run in the commit body.

**Device checklist** (for the user; agents never use adb):
1. Developer mode on, then the canvas row appears.
2. Open editor, then the pipelines list shows.
3. Pick 5 recent photos, estimate, run, then outputs and live results appear side by side.
4. Draft an extract prompt edit, run, compare with the first run.
5. Freeze and publish, then the next live keyframe uses the new prompt (Prompts history shows "Canvas: …").
6. Roll back.
7. LAN on, then the laptop opens the URL, pairs with the code, and the 6th wrong code locks.
8. Idle 30 min, then the server and the notification stop.
9. Developer mode off, then the server stops.

**Commit:** `test(phone): canvas end-to-end flow (pair, run, freeze, publish, rollback) and gate`
