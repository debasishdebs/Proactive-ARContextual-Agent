# Experiment canvas, slice 2: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. This is a **lean plan**: exact interfaces, values, behaviour bullets and named tests, not full code. Every code task is TDD: write the named tests RED, implement, then make them GREEN. Opus writes specs and reviews; **Sonnet implements**.

**Goal:** a drag-and-drop DAG editor whose JavaScript is tested headlessly. The router → intent → memory pipeline goes live through an interpreter behind the `KeyframePipeline` extractor seam, in Shadow mode and then Live mode, with fallback and auto-rollback. A/B runs, a versioned diff view, the Ask debug view with replay, compare-and-set prompt saves, HTTPS on LAN, token usage and estimates. The scene nodes are gated on wave 2.

**Spec:** `docs/superpowers/specs/2026-10-21-canvas-slice2-design.md`. § numbers below point into it. The MVP spec and plan (`2026-10-21-experiment-canvas-design.md`, `2026-10-21-experiment-canvas-mvp.md`) describe the code you are extending.

**Worktree:** start from `canvas/integration` (`/Users/debasishkanhar/Desktop/rokid/pca-canvas/int`, head `9ae7d04` or later). Paths below are relative to `android-companion/`.

**Waves** (at most 2 Gradle runs machine-wide; one per worktree):

| wave | tasks | modules |
|---|---|---|
| W1 | 1, 2, 3, 4 | 1: phone tests · 2: memory + ai + orchestration (prompt CAS) · 3: ai (`ai.canvas`) · 4: memory (Room) |
| W2 | 5 (needs 3, 4), 7 (needs 4), 8 (none), 10 (none) | orchestration · orchestration · orchestration + phone · phone server |
| W3 | 6 (needs 2, 4, 5) | orchestration (live seam) + phone (Diagnostics line) |
| W4 | 9 (needs 3, 6, 7, 8, 10) | phone api |
| W5 | 11 (needs 1, 9), then 12 (needs 11) | phone assets |
| W6 | 13 (gated: needs 9 and wave 2 merged) | ai + orchestration + assets |
| — | 14 (device and browser checklist, for the user) | none |

## Global Constraints

- **Modules:** only `:services:ai`, `:services:orchestration`, `:services:memory` and `:phone`, including `phone/src/main/assets/canvas/`. **Do not touch** `:glasses`, `:core:hud`, `:services:glasses-link`, `core/model` protocol types, or any glasses code. If a task seems to need one of them, stop and report.
- **PROTOCOL_VERSION is unchanged.** There is no wire change of any kind in this plan.
- **Room is additive only.** Task 4 adds one migration, numbered with the **next free version, check at merge**. Canvas MVP is v3, wave 2 takes v4 and wave B takes v5, so on the merged line this one is expected to be **v6**. On a branch where v4 or v5 doesn't exist yet, use that branch's next number. Then, at merge time, renumber to come after wave 2 (v4) and wave B (v5): rename the `Migration` and its test, regenerate the schema JSON, and **never edit an existing migration**. Every migration gets a migration test (`MigrationNToMTest`) proving that the old rows survive and that no old column changed, plus `runMigrationsAndValidate`. Do not use `fallbackToDestructiveMigration*` anywhere.
- **Live path:** with no interpreter publish active, live behaviour is byte-identical to today. Every new parameter defaults to today's behaviour. The existing tests of `RoleRunner`, `ExtractSave`, `ExtractFollowUp`, `KeyframePipeline`, `PromptRepository` and `CanvasPublisher` must pass unchanged. Experiment runs never write `memory`, `frame_state`, `frame_source`, `memory_vector`, nudges or jobs.
- **Ownership:** wave B owns the `frame_meta` intent column and its writer. The canvas writes the label only to `canvas_live_trace` and to the `FrameIntentSink` interface (default no-op). It never adds or writes a `frame_meta` column.
- **Gradle:** from `android-companion/`, always through the throttle, with the absolute path (worktrees don't have the git-ignored `.superpowers/`):
  `/Users/debasishkanhar/Desktop/rokid/proactive-context-agent/.superpowers/sdd/gw.sh <args>`. It runs `./gradlew --no-daemon …`. **One Gradle run at a time** per worktree; never `gradlew --stop`.
- **No adb** in any code task. Device steps belong to Task 14 only.
- **Test commands** (Robolectric `@Config(sdk = [34])` where Android is needed; JUnit4 `org.junit.Assert`):
  - ai: `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'`
  - memory: `gw.sh :services:memory:testDebugUnitTest --tests '<class>'`
  - orchestration: `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'` (plus the named classes)
  - phone: `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.*'`
  - full gate: `tools/gate.sh <log>` from the repo root (Tasks 12 and 13 only).
- **Secrets and privacy:**
  - Never print `.env` values, keys, tokens, pairing codes, certificate private material or device secrets.
  - Canvas and server logs are count-only: method, route template, status and duration. No ids in paths, no prompt text, no model output.
  - Stored error strings are fixed short texts, never provider text.
  - Do not read or print `glasses`/`AssistServer` logcat (there is no adb anyway).
  - The test-only TLS fixture (Task 10) is a throwaway self-signed certificate. Never reuse it, and never ship it in `src/main`.
- **Web UI:** plain ES2020 modules, no build toolchain, no vendored libraries, no external URLs. No `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval` or `new Function` (the existing `CanvasAssetsTest` enforces this). No inline scripts, styles or `on*=` attributes (CSP). New files must match the static path rule `^/[a-z0-9_-]+\.(html|js|css|svg)$` (flat in `assets/canvas/`). Logic goes in pure modules (no `document`/`window`), and each pure module gets a JS test (Task 1 harness).
- **Android ICU regex:** no unescaped `}` in a `Regex` (`AndroidRegexRulesTest`). Keep `\uXXXX` escapes as escapes, and grep each diff for accidental non-ASCII in `.kt` files.
- **JSON:** `kotlinx.serialization.json` elements by hand. No serialization Gradle plugin.
- **Dependencies:** the only new ones are test-only, in `:phone`: GraalJS (Task 1). TLS uses the platform (AndroidKeyStore and JSSE). Nothing else.
- **Commits:** local only, never push. One commit per task with the given message, ending with the trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **FAST MODE:** inside a task, fix only Critical and Important review findings. Minors go to the ledger.
- **Fakes:** reuse the MVP test fakes (`FakeLanguageModel`, the `FakeCanvasHost`, fake backends, `DatabaseFactory.inMemory(context)`).

## Review focus
1. **The live seam** (Task 6): with no interpreter publish, `LiveExtract.batch` makes exactly the old `ExtractSave.extract(ai.runner, photos, callClass)` call. Diff `KeyframeHandle.extract` line by line. The fallback never drops a batch.
2. **CAS** (Task 2): a mismatch on any key writes nothing.
3. **TLS** (Task 10): LAN never serves plain HTTP. The WebView proceeds only on a matching fingerprint.
4. **The JS really executes** (Tasks 1, 11, 12): each pure module has tests run by GraalJS, and every asset parses.
5. **Forget and Delete all** reach `canvas_live_trace` (Task 4).

---

### Task 1: Headless JS test harness (GraalJS) and the fake DOM

**Files:**
- Modify: `phone/build.gradle.kts`: `testImplementation("org.graalvm.polyglot:polyglot:<v>")` and `testImplementation("org.graalvm.polyglot:js-community:<v>")`. Use the newest **23.1.x** that resolves from mavenCentral (23.1 is the last line that supports JDK 17). `js-community` is a POM-type artifact; if Gradle needs it, declare it with `{ artifact { type = "pom" } }` or via `platform(...)`. Record the version in the commit body. If no 23.1.x artifact resolves, **stop and report**: no Rhino fallback, because the bundle uses ES modules and classes.
- Create: `phone/src/test/java/com/rokid/context/phone/canvas/js/JsHarness.kt`, `JsModulesParseTest.kt`, `JsDomTest.kt`
- Create: `phone/src/test/js/fake-dom.js`, `phone/src/test/js/dom.test.js`, `phone/src/test/js/dag.test.js`, `phone/src/test/js/app-boot.test.js`
- Modify: `phone/src/main/assets/canvas/app.js`: move the token/hash parsing into a new pure module `phone/src/main/assets/canvas/boot.js` (`parseBootHash(hash) → {token?, pair?, route}`). It never throws, which fixes MVP Task 10 minor 8: a malformed `#t=` falls back to pairing.

**Interfaces (Produces):**
```kotlin
package com.rokid.context.phone.canvas.js
/** Runs ES-module JS tests on GraalJS. Assets root = phone/src/main/assets/canvas, tests root = phone/src/test/js (resolved from the Gradle test working dir = the module dir). */
object JsHarness {
    data class Outcome(val name: String, val ok: Boolean, val message: String?)
    /** Imports [testFile] (relative to the tests root) as a module; calls every function in its exported `tests` object (async ones are awaited); returns one Outcome each. */
    fun run(testFile: String): List<Outcome>
    /** Parses (does not run) [assetFile] as an ES module; returns the syntax error message or null. */
    fun parseError(assetFile: String): String?
    fun assertAllPass(testFile: String)   // fails with the list of failing test names + messages
}
```
- The context is built with `allowIO(true)` restricted to the two roots (a custom `FileSystem`, or `IOAccess` with a read-only filesystem that rejects paths outside them), `js.esm-eval-returns-exports=true`, `engine.WarnInterpreterOnly=false`, no host access and no threads.
- The JS tests import their modules by relative path (`../../main/assets/canvas/graph.js`) and the fake DOM by `./fake-dom.js`.
- `fake-dom.js` exports `installFakeDom()`, which sets `globalThis.document` with `createElement`, `createElementNS`, `createTextNode` and `getElementById`. Elements support `setAttribute`, `getAttribute`, `appendChild`, `removeChild`, `children`, `textContent`, `classList`, `addEventListener` and `dispatch(type, init)`. It also exports `uninstall()`, and `find(el, pred)` / `findAll`.

**Tests:**
- `JsModulesParseTest.everyAssetModuleParses`: lists all `*.js` files in the assets root (at least `app.js`, `dom.js`, `dag.js`, `views.js`, `boot.js`) and asserts `parseError == null` for each.
- `JsDomTest`: `domTests` runs `dom.test.js`; `dagTests` runs `dag.test.js`; `bootTests` runs `app-boot.test.js`.
- `dom.test.js`: `hSetsTextNotHtml` (a child string `<b>x</b>` becomes a text node with that literal text); `attrsViaSetAttribute`; `svgNamespace`.
- `dag.test.js`:
  - `oneGroupPerLaidOutNode`;
  - `edgePathPerEdge`;
  - `whenLabelDrawn`;
  - `selectedClass`;
  - `enterSelects` (dispatch keydown Enter → `onSelect(id)`);
  - `viewBoxFitsLayout`.
- `app-boot.test.js`: `tokenFragment`, `pairFragment`, `malformedFragmentIsPairing`, `routeParsed`.

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.js.*' --tests 'com.rokid.context.phone.canvas.CanvasAssetsTest'`

**Commit:** `test(phone): headless GraalJS harness for the canvas JS, fake DOM, parse check of every module`

---

### Task 2: Compare-and-set prompt save; atomic multi-key publish and rollback

**Files:**
- Modify: `services/memory/src/main/java/com/rokid/context/memory/db/PromptDao.kt`; `services/ai/src/main/java/com/rokid/context/prompt/PromptRepository.kt`; `services/orchestration/src/main/java/com/rokid/context/canvas/CanvasPublisher.kt`
- Test: `services/memory/src/test/java/com/rokid/context/memory/db/PromptDaoCasTest.kt`, `services/ai/src/test/java/com/rokid/context/prompt/PromptRepositoryCasTest.kt`; extend `services/orchestration/src/test/java/com/rokid/context/canvas/CanvasPublisherTest.kt`

**Interfaces (Produces):**
```kotlin
// PromptDao (all inside one Room transaction per call)
data class CasWrite(val id: String, val expectTemplate: String, val expectSchema: String?, val template: String, val schema: String?, val note: String, val testedOk: Boolean)
/** Compares each key's ACTIVE text exactly (template and schema, null == null); on any mismatch writes nothing and returns the mismatching ids. Otherwise appends (keepVersions) or overwrites as appendAndActivate / overwriteActive do, and returns the new versions. */
@Transaction open suspend fun casSaveAll(writes: List<CasWrite>, keepVersions: Boolean, now: Long): CasOutcome
sealed interface CasOutcome { data class Saved(val versions: Map<String, Int>) : CasOutcome; data class Mismatch(val ids: List<String>) : CasOutcome }

// PromptRepository
data class CasEdit(val key: PromptKey, val expected: PromptDraft, val draft: PromptDraft, val note: String, val testedOk: Boolean)
suspend fun saveAllIfUnchanged(edits: List<CasEdit>): CasOutcome   // same normalisation as save(): trim, SYSTEM schema null, note collapse/take(80); seeds first
```
**Behaviour:**
- `save` and `restore` are unchanged.
- Expected texts are compared after the same normalisation `save` applies to stored text (the stored text is already trimmed).
- `CanvasPublisher.publish` (prompts kind) builds its edits with `expected` = the snapshot it took, so a prompt edited between snapshot and save blocks the publish with `"<label> was changed during publish. Try again."`. `rollback` builds every key's edit with `expected` = the published text, and a `Mismatch` gives the existing "was changed after the publish…" reason with **nothing** written. This removes the per-key re-check loop and closes ledger Task 7 (b) and the half-done multi-key minor.

**Tests:**
- `PromptDaoCasTest`: `matchWritesAppend`, `matchWritesOverwriteWhenVersioningOff`, `mismatchWritesNothing`, `secondKeyMismatchRollsBackFirst`, `nullSchemaEqualsNull`.
- `PromptRepositoryCasTest`: `normalisesLikeSave`, `seedsBeforeCompare`, `systemSchemaIgnored`.
- `CanvasPublisherTest` (new): `rollbackMultiKeyAllOrNothing` (two keys, the second edited after the publish → both unchanged), `publishBlockedWhenEditedDuringPublish` (the fake compatibility lambda edits the prompt), and the existing publisher tests unchanged and green.

**Test command:** `gw.sh :services:memory:testDebugUnitTest --tests '*PromptDaoCas*'`, then `gw.sh :services:ai:testDebugUnitTest --tests '*PromptRepositoryCas*' --tests 'com.rokid.context.prompt.*'`, then `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'`

**Commit:** `fix(prompts): compare-and-set multi-key prompt save; canvas publish and rollback all-or-nothing`

---

### Task 3: Pipeline model additions: structured problems, interpretable shape, pipeline diff

**Files (`services/ai/src/main/java/com/rokid/context/ai/canvas/`):** modify `PipelineValidator.kt`; create `InterpretableShape.kt`, `PipelineDiff.kt`
**Test:** extend `PipelineValidatorTest.kt`; create `InterpretableShapeTest.kt`, `PipelineDiffTest.kt`

**Interfaces (Produces):**
```kotlin
data class Problem(val text: String, val nodeId: String? = null, val edge: PipelineEdge? = null)
object PipelineValidator {
    fun problems(p: Pipeline): List<Problem>          // NEW: the same rules and texts as today, with the node/edge each one is about
    fun validate(p: Pipeline): List<String> = problems(p).map { it.text }   // unchanged result
}
object InterpretableShape {
    const val MAX_PROMPT_NODES = 3
    fun problems(p: Pipeline): List<String>          // fixed texts, see below; empty = can be published as an interpreter
    fun warnings(p: Pipeline): List<String>          // e.g. "Live always runs the confidence check and the critic after extraction"
    /** Calls per photo relative to Live extraction (1 call per batch of 3): (promptNodes * 3 + extractNodesOnAPath) / 1, rounded to 1 decimal, for the publish dialog. */
    fun callFactor(p: Pipeline): Double
    fun preSaveNodes(p: Pipeline): List<PipelineNode> // topological order: input, ai.prompt, route.switch, ai.vision_extract (what the interpreter runs)
}
object PipelineDiff {
    data class NodeChange(val nodeId: String, val kind: String /* added|removed|changed */, val params: List<String> /* changed param names, sorted */)
    data class PromptDiff(val nodeId: String, val part: String /* template|schema */, val lines: List<DiffLine>)   // DiffLine from LineDiff
    data class Result(val nodes: List<NodeChange>, val edgesAdded: List<PipelineEdge>, val edgesRemoved: List<PipelineEdge>, val prompts: List<PromptDiff>, val renamed: Boolean)
    fun of(a: Pipeline, b: Pipeline): Result
}
```
**InterpretableShape problem texts (exact):**
- `"Fix the pipeline's problems first"`;
- `"node <id>: <type> can't run before extraction"`;
- `"photo path through <id> reaches no extraction"`;
- `"photo path through <id> reaches more than one extraction"`;
- `"node <id>: only checks, critic and Save can follow extraction"`;
- `"node <id>: the critic must use the Live prompt"`;
- `"at most 3 prompt nodes"`.

Paths are enumerated over the DAG, edges with `when` included. The `other` branch counts as a path when the switch has no `other` edge: that's fine (no item travels it), so that path is ignored.
- `PipelineDiff` matches nodes by id. Params compare as canonical JSON per key. A prompt diff appears for every node whose `prompt` param (or `ai.prompt`'s `template`/`schema`) differs. The `LineDiff` lives in `com.rokid.context.ai.prompt`; reuse it.

**Tests:**
- `PipelineValidatorTest`: `problemsCarryNodeIds` (unknown edge end → `edge` set; batch range → `nodeId` set), `validateTextsUnchanged` (for every existing invalid fixture, `validate` gives the same list as before).
- `InterpretableShapeTest`: `intentDraftIsInterpretable`, `liveExtractIsInterpretable`, `criticDraftBlocked`, `promptAfterExtractBlocked`, `pathWithoutExtractBlocked`, `twoExtractsOnOnePathBlocked`, `tooManyPromptNodes`, `invalidPipelineBlocked`, `callFactorIntentDraft`, `preSaveNodesOrder`, `warningsWhenNoTrustNode`.
- `PipelineDiffTest`: `identicalIsEmpty`, `addedRemovedChangedNodes`, `edgesAddedRemoved`, `promptTemplateLineDiff`, `schemaDiff`, `renameDetected`.

**Test command:** `gw.sh :services:ai:testDebugUnitTest --tests 'com.rokid.context.ai.canvas.*'`

**Commit:** `feat(ai): canvas structured problems, interpretable shape check, pipeline diff`

---

### Task 4: Room (next free version): publish kinds and modes, A/B group, live trace table, cascades

**Files:**
- Modify: `services/memory/src/main/java/com/rokid/context/memory/db/CanvasEntities.kt` (`CanvasPublishEntity` + `kind: String = "prompts"` `@ColumnInfo(defaultValue = "prompts")`, `mode: String? = null`, `rollbackReason: String? = null`; `CanvasRunEntity` + `abGroup: String? = null`; new `CanvasLiveTraceEntity`); `CanvasDao.kt`; `ContextDatabase.kt` (version N, the entity, `MIGRATION_<N-1>_<N>`); `DatabaseFactory.kt` (add the migration at both builder sites); `WipeDao.kt`; `ForgetService.kt` / `ForgetDao.kt`
- Generated: `services/memory/schemas/com.rokid.context.memory.db.ContextDatabase/<N>.json` (commit it)
- Test: `services/memory/src/test/java/com/rokid/context/memory/db/Migration<N-1>To<N>Test.kt`; extend `CanvasDaoTest.kt`, `WipeDaoTest.kt`, `ForgetCanvasTest.kt`

**Entity:**
```kotlin
@Entity(tableName = "canvas_live_trace", primaryKeys = ["publishId", "frameId", "nodeId"], indices = [Index("frameId"), Index("at")])
data class CanvasLiveTraceEntity(val publishId: Long, val frameId: String, val nodeId: String, val mode: String /* SHADOW|LIVE */,
    val status: String /* OK|ERROR */, val branch: String?, val outputJson: String?, val error: String?, val at: Long)
```
`nodeId = "_batch"` is the per-photo outcome row: OK when the interpreter's extraction was used, ERROR plus a fixed error when the batch fell back.

**Migration SQL:** use Room's generated statements from `<N>.json`. They are: three `ALTER TABLE canvas_publish ADD COLUMN …` (with `kind` `TEXT NOT NULL DEFAULT 'prompts'`), one `ALTER TABLE canvas_run ADD COLUMN abGroup TEXT`, plus `CREATE TABLE IF NOT EXISTS canvas_live_trace …` and its two indexes.

**`CanvasDao` additions (Produces):**
```kotlin
@Query("SELECT * FROM canvas_publish WHERE rolledBackAt IS NULL AND kind = 'interpreter' ORDER BY id DESC LIMIT 1") abstract suspend fun activeInterpreter(): CanvasPublishEntity?
@Query("UPDATE canvas_publish SET rolledBackAt = :at, rollbackReason = :reason WHERE id = :id AND rolledBackAt IS NULL") abstract suspend fun markRolledBackWithReason(id: Long, at: Long, reason: String?): Int
@Upsert abstract suspend fun upsertTrace(rows: List<CanvasLiveTraceEntity>)
@Query("SELECT * FROM canvas_live_trace WHERE publishId = :publishId ORDER BY at DESC LIMIT :limit") abstract suspend fun traces(publishId: Long, limit: Int): List<CanvasLiveTraceEntity>
@Query("SELECT * FROM canvas_live_trace WHERE frameId = :frameId ORDER BY at DESC") abstract suspend fun tracesForFrame(frameId: String): List<CanvasLiveTraceEntity>
/** The last [window] per-photo outcomes of [publishId]: the count of ERROR among them. */
@Query("SELECT COUNT(*) FROM (SELECT status FROM canvas_live_trace WHERE publishId = :publishId AND nodeId = '_batch' ORDER BY at DESC LIMIT :window) WHERE status = 'ERROR'")
abstract suspend fun recentFallbacks(publishId: Long, window: Int): Int
@Query("SELECT COUNT(*) FROM canvas_live_trace WHERE publishId = :publishId AND nodeId = '_batch'") abstract suspend fun interpretedCount(publishId: Long): Int
/** Retention: rows older than [olderThan], then all but the newest [keep]. */
@Transaction open suspend fun pruneTraces(olderThan: Long, keep: Int) { /* two deletes */ }
@Query("SELECT * FROM canvas_run WHERE abGroup = :group ORDER BY startedAt") abstract suspend fun runsInGroup(group: String): List<CanvasRunEntity>
@Query("SELECT * FROM ask ORDER BY ts DESC LIMIT :limit") abstract suspend fun recentAsks(limit: Int): List<AskEntity>   // for the Ask debug list
/** Mean tokens over the last [limit] OK items whose nodeId is in [nodeIds] (the caller passes the ids of same-type nodes). */
@Query("SELECT AVG(promptTokens) AS p, AVG(completionTokens) AS c, COUNT(*) AS n FROM (SELECT promptTokens, completionTokens FROM canvas_run_item WHERE nodeId IN (:nodeIds) AND status = 'OK' AND promptTokens IS NOT NULL ORDER BY rowid DESC LIMIT :limit)")
abstract suspend fun tokenMeans(nodeIds: List<String>, limit: Int): TokenMeans
data class TokenMeans(val p: Double?, val c: Double?, val n: Int)
```
- Also add `recentFrames` with a time window: `recentFramesBetween(afterMs, beforeMs, dwellOnly, limit)`, the same query plus `f.ts >= :afterMs`, for the day filter.
- `pruneRuns` tiebreak (MVP Task 4 minor): order by `startedAt DESC, runId DESC`.

**Cascade:** `WipeDao.wipeMemory` also deletes all `canvas_live_trace` rows. `ForgetService`, at every site that already deletes canvas items and scores for tombstoned frames, also deletes `canvas_live_trace` rows for those frames, in the same transaction.

**Tests:**
- `Migration<N-1>To<N>Test`: `oldRowsSurviveWithDefaults` (an existing `canvas_publish` row reads `kind = prompts`, mode null; a `canvas_run` has abGroup null), `traceTableExistsEmpty`, `noOtherTableChanged` (column sets equal between `<N-1>.json` and `<N>.json` except the 4 added columns), `migratedMatchesExportedSchema`, `chainFromV1`.
- `CanvasDaoTest`:
  - `activeInterpreterNewestNotRolledBack`, `rollbackReasonStored`;
  - `recentFallbacksWindow` (35 `_batch` rows: 5 oldest ERROR, then 30 newer of which 12 are ERROR → 12);
  - `pruneTracesByAgeAndCount`, `runsInGroupOrdered`, `tokenMeansOnlyOk`, `recentFramesBetweenBounds`, `pruneRunsTiebreak`.
- `WipeDaoTest.wipeClearsLiveTrace`; `ForgetCanvasTest.forgetDeletesLiveTraceForFrame`.
- Plus a device test: extend `SqlCipherMigrationDeviceTest` (androidTest, compile-verified only; it runs on the phone in Task 14).

**Test command:** `gw.sh :services:memory:testDebugUnitTest --tests 'com.rokid.context.memory.db.*' --tests 'com.rokid.context.memory.forget.*'`

**Commit:** `feat(memory): Room v<N> canvas publish kinds/modes, A/B group, live trace table (additive) with cascades`

---

### Task 5: Pipeline interpreter (executors at a chosen call class), intent sink

**Files (`services/orchestration/src/main/java/com/rokid/context/canvas/`):** modify `NodeExecutor.kt` (`RunContext` gains `val callClass: CallClass = CallClass.EXPERIMENT`), `Executors.kt` (use `ctx.callClass` at the three call sites; the vision extractor records tokens and model, see Task 7 for the `ExtractedBatch` fields; **if Task 7 isn't merged yet, leave tokens null here**); create `PipelineInterpreter.kt`, `FrameIntentSink.kt`
**Test:** `services/orchestration/src/test/java/com/rokid/context/canvas/PipelineInterpreterTest.kt`; extend `ExecutorsTest.kt`

**Interfaces (Produces):**
```kotlin
fun interface FrameIntentSink { suspend fun record(frameId: String, label: String, confidence: Float?, atMs: Long)
    companion object { val None = FrameIntentSink { _, _, _, _ -> } } }   // wave B binds the frame_meta writer
class PipelineInterpreter(private val executors: Map<String, NodeExecutor> = Executors.all(), private val clock: () -> Long = System::currentTimeMillis) {
    sealed interface Outcome {
        /** [batch]: merged facts (distinct by text+type+sourceFrameIds set), captions (one per frame, first wins), provenance = all extract chunks had it; promptVersion 0. */
        data class Extracted(val batch: ExtractedBatch, val traces: List<CanvasLiveTraceEntity>, val labels: Map<String, Pair<String, Float?>>) : Outcome
        /** The batch can't be used (an item reached no extraction, an executor error, INVALID_OUTPUT, NOT_SET, merge conflict). [reason] is a fixed text. */
        data class Failed(val reason: String, val traces: List<CanvasLiveTraceEntity>) : Outcome
    }
    /** Runs [InterpretableShape.preSaveNodes] of [p] over [photos] with [ctx] (ctx.callClass = the live call class, or EXPERIMENT for shadow).
     *  A retryable or budget ModelCallException from an executor is RETHROWN (live classification applies); CancellationException is rethrown. */
    suspend fun run(p: Pipeline, publishId: Long, mode: String, photos: List<ExtractPhoto>, ctx: RunContext): Outcome
}
```
**Behaviour:**
- It uses the same item-flow rules as `CanvasRunner` (the edge `when` rule and the merge conflict). Factor the shared routing helper out of `CanvasRunner` into an internal `ItemFlow` object, and keep `CanvasRunnerTest` green.
- **Executor errors:** executors catch exceptions and return item errors. For the live path, the interpreter must know when a failure is "retryable network or budget". Add an optional `cause: Throwable?` to `NodeResult` (defaulting to null; it is not stored). Executors set it when they catch a `ModelCallException`. The interpreter rethrows the first cause whose `retryable == true` or whose kind is in `{NETWORK, TIMEOUT, RATE_LIMIT, BUDGET}` (use the existing `FailureKind` names; check `ExtractOutcome.classify` for the set live treats as retryable and match it).
- **Labels:** for each item that passed a `route.switch`, the label is the switch `field` value of the item's JSON, and the confidence is its numeric `confidence` field when present.
- **Traces:** one row per (item, executed node), with `outputJson` capped at 8,000 characters (truncated with a fixed marker), plus one `_batch` row per photo (OK, or ERROR with the reason).
- It writes nothing itself: the caller persists the traces and calls the sink.

**Tests (`PipelineInterpreterTest`, fake LanguageModel + in-memory DB for the prompts):**
- `intentRoutesToPerLabelPrompt` (2 photos, the fake intent replies `driving` / `computer_work` → each extract call carries its own draft template and the upstream JSON);
- `mergedBatchHasFactsAndCaptionsPerFrame`, `duplicateFactAcrossItemsMergedOnce`;
- `invalidIntentOutputFails`, `unboundRoleFails`;
- `retryableModelErrorRethrown`, `cancellationRethrown`;
- `liveExtractShapeEqualsDirectExtract` (live-extract pipeline over 3 photos gives the same facts as `ExtractSave.extract` with the same fake replies);
- `labelsCollected`, `tracesPerNodeAndBatchRow`, `callClassFromContext` (the fake model sees EXTRACT when ctx.callClass = EXTRACT);
- `executorsWriteNothing` stays green.

**Test command:** `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*'`

**Commit:** `feat(orchestration): canvas pipeline interpreter over the same executors, intent sink`

---

### Task 6: Interpreter publish (Shadow / Live), unified rollback, auto-rollback, the live seam

**Files:**
- Modify: `services/orchestration/src/main/java/com/rokid/context/canvas/CanvasPublisher.kt`; `services/orchestration/src/main/java/com/rokid/context/work/frames/KeyframeGraph.kt` (only the one line in `KeyframeHandle.extract`, plus shadow scheduling after a successful live save)
- Create: `services/orchestration/src/main/java/com/rokid/context/canvas/LiveExtract.kt`
- Modify: `phone/src/main/java/com/rokid/context/phone/ui/settings/Diagnostics.kt` (the "Live pipeline" line)
- Test: `services/orchestration/src/test/java/com/rokid/context/canvas/LiveExtractTest.kt`, `InterpreterPublishTest.kt`; extend `phone/src/test/java/com/rokid/context/phone/ui/settings/DiagnosticsTest.kt` (or the existing Diagnostics test class; find it)

**Interfaces (Produces):**
```kotlin
// CanvasPublisher additions
enum class LiveMode { SHADOW, LIVE }
suspend fun publishInterpreter(pipelineId: String, version: Int, mode: LiveMode): Outcome
// gates (exact reasons): InterpretableShape.problems texts; "<label>: <compat problem>" for draft extract prompts (TestBed compatibility, VISION_EXTRACT);
// "node <id>: schema is not a JSON object" for ai.prompt; "Run this version first: <node ids> have no successful run" (AI nodes = ai.prompt + ai.vision_extract on the pre-save list).
// Inserts CanvasPublishEntity(kind = "interpreter", mode = mode.name, snapshotJson = {"pipelineId","version","hash"}); writes no prompts.
suspend fun rollback(): Outcome          // CHANGED: undoes the newest active row of EITHER kind; an interpreter row is just marked rolled back
suspend fun autoRollback(publishId: Long, reason: String): Boolean   // marks with reason; false when already rolled back

object LiveExtract {
    const val FALLBACK_WINDOW = 30; const val FALLBACK_LIMIT = 10; const val SHADOW_EVERY = 4
    const val TRACE_KEEP = 5_000; const val TRACE_DAYS = 7L
    /** The extraction step of KeyframeHandle.extract. With no active interpreter, or an active SHADOW one: exactly ExtractSave.extract(runner, photos, callClass). */
    suspend fun batch(deps: LiveDeps, photos: List<ExtractPhoto>, callClass: CallClass): ExtractedBatch
    /** After a successful live save: in SHADOW mode, every SHADOW_EVERY-th call runs the interpreter as EXPERIMENT (when deps.shadowAllowed()), persisting traces only. Never throws (logs the class name). */
    suspend fun shadow(deps: LiveDeps, photos: List<ExtractPhoto>)
}
class LiveDeps(val dao: CanvasDao, val runner: RoleRunner, val context: (runId: String, callClass: CallClass) -> RunContext,
               val interpreter: PipelineInterpreter = PipelineInterpreter(), val sink: FrameIntentSink = FrameIntentSink.None,
               val publisher: suspend (Long, String) -> Boolean /* autoRollback */, val shadowAllowed: () -> Boolean /* Developer mode on */,
               val clock: () -> Long = System::currentTimeMillis)
```
**Behaviour:**
- **`batch`:**
  1. `dao.activeInterpreter()`. When it is null or SHADOW, return `ExtractSave.extract(runner, photos, callClass)`.
  2. When it is LIVE, decode the version's JSON (cache it by publishId in a `@Volatile` field).
  3. Call `interpreter.run(p, id, "LIVE", photos, context("live:<publishId>", callClass))`.
  4. **On `Extracted`:** persist the traces, call `sink.record` for each label (catching and logging errors), and return its batch.
  5. **On `Failed`:** persist the traces, then return `ExtractSave.extract(runner, photos, callClass)` (the fallback). Then, if `interpretedCount ≥ FALLBACK_WINDOW` and `recentFallbacks(id, 30) ≥ FALLBACK_LIMIT`, call `publisher(id, "auto: 10 of 30 fell back")`.
  6. A rethrown retryable exception propagates (no fallback, and no `_batch` row).
  7. Any other unexpected throwable from the interpreter path is treated as `Failed("internal")`.
  8. Pruning: at most once per hour (an in-memory timestamp), `dao.pruneTraces(now - 7 d, 5_000)`.
- **Wiring in `KeyframeHandle.extract`:** replace `ExtractSave.extract(ai.runner, photos, callClass)` with `LiveExtract.batch(liveDeps, photos, callClass)`. `liveDeps` is a `lazy` built from `MemoryGraph.get(app).db.canvasDao()`, `ai.runner`, and a context factory mirroring `CanvasBackendImpl`'s. The publisher is a lambda into a `CanvasPublisher` built over the same DAO. `shadowAllowed` reads `DeveloperModeStore`'s prefs (`pc_dev` / `developer_mode`). The phone module owns that store, so pass a `() -> Boolean` reading the SharedPreferences directly: there is no `:phone` import in orchestration.
- After `ExtractSaveResult.Saved`, launch `LiveExtract.shadow(liveDeps, photos)` in `scope`, **without awaiting**, and only when the active interpreter is SHADOW. At most one shadow job at a time: an `AtomicBoolean`, and skip when busy.
- Nothing else in `extract` changes.
- **Diagnostics:** when `activeInterpreter()` is not null, the line reads `Live pipeline: <pipeline name> v<N> · <Shadow|Live> · <fallbacks>/<interpreted> fell back`. The calls line also names `EXPERIMENT` (spec §7): change `named` to include `CallClass.EXPERIMENT`, which removes it from OTHER.

**Tests:**
- `LiveExtractTest` (in-memory DB, fake model):
  - `noPublishIsExactlyExtractSave` (the same requests as a direct `ExtractSave.extract`, and no trace rows);
  - `shadowModeBatchIsLive`;
  - `liveModeUsesInterpreter`;
  - `failedFallsBackToLive` (the batch is returned; ERROR `_batch` rows);
  - `retryableErrorPropagatesNoFallback`;
  - `autoRollbackAfterTenOfThirty`;
  - `sinkCalledWithLabels`, `sinkErrorIgnored`;
  - `shadowEveryFourthAsExperiment`, `shadowSkippedWhenDevModeOff`, `shadowNeverThrows`;
  - `pruneAtMostHourly`.
- `InterpreterPublishTest`: `blockedNotInterpretable`, `blockedUntested`, `blockedCompat`, `publishShadowThenLive`, `rollbackUndoesNewestEitherKind`, `promptPublishStillWorks`, `autoRollbackSetsReason`.
- Diagnostics: `livePipelineLineShown`, `experimentNamedInCallsLine`.
- The existing `KeyframePipeline*` and `KeyframeHandle`-related tests stay green, unchanged.

**Test command:** `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*' --tests 'com.rokid.context.work.frames.*'`, then `gw.sh :phone:testDebugUnitTest --tests '*Diagnostics*'`

**Commit:** `feat(canvas): interpreter publish in shadow or live mode behind the extractor seam, fallback and auto-rollback`

---

### Task 7: A/B runs, token usage, token estimate, runner minors

**Files:**
- Modify: `services/orchestration/src/main/java/com/rokid/context/orchestration/ExtractSave.kt` (`ExtractedBatch` gains `promptTokens: Int? = null, completionTokens: Int? = null, model: String? = null`, filled from the extract call's `RoleResult.reply`; the single-photo fallback path sums them); `services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt` (record them in `CallMeta`); `CanvasRunner.kt`
- Test: extend `ExtractSaveOverrideTest.kt` (or create `ExtractSaveUsageTest.kt`), `CanvasRunnerTest.kt`, `ExecutorsTest.kt`

**Interfaces (Produces):**
```kotlin
// CanvasRunner
data class AbSpec(val a: Pair<Pipeline, Int?>, val aId: String, val b: Pair<Pipeline, Int?>, val bId: String)
suspend fun estimateAb(a: Pipeline, b: Pipeline, frameIds: List<String>, labels: suspend (PipelineNode) -> ModelLabel?): Estimate   // sums nominal/max; one token bound to (hashA + "|" + hashB, frames); same limits (50 photos, 200 nominal in total)
/** Starts A now; when A ends DONE, starts B in the same scope; both rows carry abGroup. Cancel of either cancels the group (B never starts). Returns the group id. */
suspend fun startAb(spec: AbSpec, frameIds: List<String>, confirmToken: String, scope: CoroutineScope): String
// Estimate.Ok gains: val tokens: TokenEstimate? = null
data class TokenEstimate(val promptTokens: Int, val completionTokens: Int, val basedOn: Int /* items */)
```
**Behaviour:**
- **Token estimate:** for each AI node, `dao.tokenMeans(<node ids of the same type in past runs: pass the current node's id plus any id of the same type found in the 20 kept runs' pipelineJson>, 200)` × its nominal calls. It is null when `n == 0` for every node. Keep it simple: the `labels` lambda's caller (the backend) can pass the node-id lists. Document the choice in KDoc.
- `pruneRuns` must not delete the A run of an active group: prune before starting A, once for the group (`KEEP_RUNS - 2`).
- **Runner minors:**
  - the CANCELLED-branch `updateRun` gets the same one-retry-as-FAILED treatment as the final update (final re-review minor);
  - `frameIds` over 50 is refused by the runner with `"more than 50 photos"`, and is no longer silently truncated (the API change is in Task 9).

**Tests:**
- `ExtractSaveUsageTest`: `tokensFromReply`, `singlePhotoFallbackSumsTokens`, `defaultsNullWhenUnknown`.
- `ExecutorsTest.extractRecordsTokensAndModel`.
- `CanvasRunnerTest`: `abRunsSequentiallyWithGroup`, `abBNotStartedWhenACancelled`, `abEstimateSumsAndRefusesOver200`, `abTokenBoundToBothHashes`, `pruneKeepsActiveGroup`, `tokenEstimateFromHistory`, `tokenEstimateNullWithoutHistory`, `cancelledUpdateRetried`.

**Test command:** `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*' --tests 'com.rokid.context.orchestration.*'`

**Commit:** `feat(canvas): A/B runs, token usage on extract calls, token estimate, runner fixes`

---

### Task 8: Ask debug backend: scored re-retrieval, composed prompt, replay

**Files:**
- Modify: `services/orchestration/src/main/java/com/rokid/context/orchestration/AnswerService.kt`: add `retrieveScored`, additive; `retrieve` and `answer*` are unchanged.
- Create: `services/orchestration/src/main/java/com/rokid/context/canvas/AskDebug.kt`
- Test: `services/orchestration/src/test/java/com/rokid/context/canvas/AskDebugTest.kt`; `services/orchestration/src/test/java/com/rokid/context/orchestration/AnswerServiceScoredTest.kt`

**Interfaces (Produces):**
```kotlin
// AnswerService (additive)
data class ScoredRetrieved(val retrieved: Retrieved, val hits: List<ScoredMemory>, val mode: RetrievalMode, val semanticUnavailable: Boolean, val fallback: Boolean)
/** As retrieve(), but keeps the scores; [notAfterMs] drops memories with createdAt > it from the hits AND the fallback pool before bounding. */
suspend fun retrieveScored(question: String, nowMs: Long, notAfterMs: Long? = null): ScoredRetrieved

class AskDebug(private val dao: CanvasDao, private val answers: AnswerService, private val runner: RoleRunner, private val prompts: PromptRepository,
               private val persona: suspend () -> PersonaSettings, private val zone: () -> ZoneId = ZoneId::systemDefault) {
    data class Hit(val memoryId: String, val text: String, val frameId: String?, val lexical: Double?, val semantic: Double?, val fused: Double, val score: Double, val cited: Boolean, val inPrompt: Boolean)
    data class View(val askId: String, val ts: Long, val question: String, val answer: String, val cited: List<String>, val rating: Int?, val hits: List<Hit>,
                    val mode: String, val fallback: Boolean, val composedPrompt: String, val activeTemplate: String, val activeVersion: Int)
    sealed interface Replay { data class Ok(val answer: String, val sources: List<String>, val latencyMs: Long, val promptTokens: Int?, val completionTokens: Int?, val model: String) : Replay
                              data class Failed(val error: String /* fixed: kind name or simpleName */) : Replay }
    suspend fun list(limit: Int = 50): List<AskEntity>
    suspend fun view(askId: String): View?          // null: unknown; throws IllegalStateException("redacted") for redacted asks
    suspend fun replay(askId: String, template: String): Replay   // RoleRunner ANSWER with override = PromptDraft(template, active schema), CallClass.EXPERIMENT; saves nothing
}
```
**Behaviour:**
- `view` re-retrieves with `nowMs = ask.ts` and `notAfterMs = ask.ts`. `cited` = the ask's `AskSources.parse(sourcesJson)`. `inPrompt` = the hit was among the kept (bounded) memories.
- `composedPrompt` = `PromptComposer.compose(active SYSTEM, active ANSWER template, vars.toMap(persona, NowText.of(ask time)))`. That is the same composition `RoleRunner` uses. If it isn't public, add an internal helper next to RoleRunner rather than copying the logic.
- `replay` validates the template the way `TestBed.compatibility(ANSWER, draft)` does. Problems → `Failed("incompatible")`. It never calls `memory.asks.add` or the proposal intake.
- It holds no extra state, and logs the class name only.

**Tests:**
- `AnswerServiceScoredTest`: `sameIdsAsRetrieve`, `notAfterDropsNewerMemories`, `fallbackFlagWhenNoHits`.
- `AskDebugTest`:
  - `viewShowsScoresCitedAndInPrompt`;
  - `redactedRefused`, `unknownIsNull`;
  - `composedPromptHasQuestionAndMemories`;
  - `replayUsesOverrideAndExperimentClass`;
  - `replaySavesNothing` (the ask row count and the proposal count are unchanged);
  - `replayIncompatibleFails`.

**Test command:** `gw.sh :services:orchestration:testDebugUnitTest --tests 'com.rokid.context.canvas.*' --tests '*AnswerServiceScored*'`

**Commit:** `feat(canvas): ask debug backend (scored re-retrieval as of the ask, composed prompt, replay)`

---

### Task 9: API routes and backend for slice 2

**Files:** modify `phone/src/main/java/com/rokid/context/phone/canvas/api/CanvasBackend.kt`, `CanvasApi.kt`, `CanvasBackendImpl.kt`; fix the stale KDoc at `CanvasApi.kt:28` and `CanvasServer.kt:36` (MVP Task 9 minor)
**Test:** extend `CanvasApiTest.kt`, `CanvasBackendImplTest.kt`; create `phone/src/test/java/com/rokid/context/phone/canvas/CanvasSlice2EndToEndTest.kt`

**New and changed routes (JSON; ids validated as today):**

| route | does |
|---|---|
| `POST /api/validate {"json"}` | `{problems:[{text,nodeId?,edge?:{from,to,when?}}], layout, liveShape, interpretable:{problems,warnings,callFactor}}`. An undecodable body gives `problems` = [its fixed text] and `layout` = null. It saves nothing and needs no `draftRev`. |
| `GET /api/pipelines/{id}` | as today, plus `problemsDetailed` and `interpretable` |
| `GET /api/pipelines/{id}/diff?a=<v>&b=<v\|draft>` | the `PipelineDiff.Result` as JSON |
| `POST /api/pipelines/{id}/versions/{n}/publish {"mode"?}` | mode absent or `"prompts"` → the MVP prompt publish; `"shadow"` / `"live"` → `publishInterpreter`. Blocked → 409 `publish_blocked` |
| `GET /api/publish` | the active stack: `[{id,kind,mode,pipelineId,version,publishedAt}]` plus `interpreter:{…, interpreted, fallbacks}` or null |
| `POST /api/publish/rollback` | unified (Task 6) |
| `GET /api/live?limit=<≤ 100>` | the active interpreter's recent per-frame traces: `[{frameId, at, label, batch:"OK"\|"ERROR", nodes:[{nodeId,status,branch,output,error}]}]`, the label counts, and `fallbacks/interpreted` |
| `POST /api/runs/estimate-ab {"a":{pipelineId,version?},"b":{…},"frameIds"}` | `Estimate` for both, with `tokens` |
| `POST /api/runs/ab {"a","b","frameIds","confirmToken"}` | `{group, runA}` |
| `GET /api/runs/{id}` | as today, plus `abGroup`, `usage:{calls, est, promptTokens, completionTokens, medianLatencyMs}`, and `branches:{"<switchNodeId>":{"<label>":count}}` |
| `GET /api/compare?a=&b=` | as today, plus `branches` for each side, `agreement:{"<switchNodeId>":0..1}` and `scoreSums:{"<nodeId>":{a,b}}` |
| `GET /api/frames?…&day=YYYY-MM-DD` | the day filter in the phone's zone (`after` = local midnight, `before` = the next midnight, or `before` when it is earlier) |
| `GET /api/asks`, `GET /api/asks/{id}`, `POST /api/asks/{id}/replay {"template"}` | Task 8. A redacted ask → 409 `refused` "This ask was redacted". Replay is a mutation: NonCancellable, with the 60 s mutation deadline |
| `POST /api/runs/estimate` | as today, plus `tokens` |

- `frameIds` over 50 → 400 `bad_request` "At most 50 photos" (it used to be silently truncated: final-review M5).
- `CanvasBackendImpl` builds `AskDebug` and `LiveDeps`-compatible readers over the same DAO and `AiGraph`. The publisher instance stays single (as the final review required).
- Node types: `GET /api/node-types` adds `"palette": true|false` (false for `input.photos`, which is exactly 1 per pipeline) and `"short"` (the id prefix: `photos`, `extract`, `trust`, `critic`, `prompt`, `route`, `write`).

**Tests:**
- `CanvasApiTest`: one happy-path test and one error-mapping test per new or changed route, plus `frameIdsOver50Is400`, `publishModeAbsentIsPrompts`, `validateUndecodable`, `replayRedactedIs409`, `dayFilterParsed`, `dayFilterBadIs400`.
- `CanvasBackendImplTest`: `validateReturnsLayoutAndProblems`, `diffDraftVsVersion`, `liveViewListsTraces`, `compareBranchesAndAgreement`, `runUsageLine`, `asksListedNewestFirst`.
- `CanvasSlice2EndToEndTest` (real server on loopback, OkHttp, fake model, fake photos):
  1. pair;
  2. duplicate `intent-draft` and set two per-intent draft prompts through PUT;
  3. validate → no problems, interpretable;
  4. estimate-ab against live-extract, then start AB, then poll until both are DONE; compare shows `agreement` and `branches`;
  5. freeze → v1 → publish `shadow` → `GET /api/publish` shows it;
  6. publish `live` → drive `LiveExtract.batch` directly with the fake photos → `GET /api/live` lists traces with labels;
  7. rollback → the active interpreter is gone; rollback again → the shadow row is gone;
  8. the ask flow: seed an ask and memories → view has scores → replay → the ask count is unchanged.

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.*'`

**Commit:** `feat(phone): canvas API for validate, diff, interpreter publish, live traces, A/B, asks, day filter`

---

### Task 10: HTTPS on LAN with a keystore certificate, WebView pinning, pairing hardening, server minors

**Files:**
- Create: `phone/src/main/java/com/rokid/context/phone/canvas/server/TlsIdentity.kt` (interface + `KeystoreTlsIdentity`)
- Modify: `CanvasServer.kt`, `HostPolicy.kt`, `CanvasAuth.kt`, `phone/src/main/java/com/rokid/context/phone/canvas/CanvasGraph.kt`, `ui/CanvasHome.kt`, `ui/CanvasWeb.kt` (`CanvasWebPolicy` + `onReceivedSslError`)
- Test resource: `phone/src/test/resources/canvas-tls-test.p12` (a throwaway self-signed EC certificate made with `keytool -genkeypair -keyalg EC -groupname secp256r1 -storetype PKCS12 -storepass test-only -dname CN=test …`; storepass `test-only` hard-coded in the test only)
- Test: `TlsIdentityTest.kt` (fixture-based `PkcsTlsIdentity` test helper), extend `CanvasServerTest.kt`, `HostPolicyTest.kt`, `CanvasAuthTest.kt`, `CanvasWebPolicyTest.kt`, `CanvasHomeTest.kt`

**Interfaces (Produces):**
```kotlin
interface TlsIdentity {
    /** SHA-256 of the DER certificate, uppercase hex, grouped "ABCD EF01 …" (16 groups); show the first 8 groups large in the app. */
    val fingerprint: String
    fun serverSocketFactory(): javax.net.ssl.SSLServerSocketFactory
    fun matches(cert: java.security.cert.X509Certificate): Boolean   // SHA-256(DER) equals ours, constant-time
    fun rotate()
}
class KeystoreTlsIdentity(context: Context) : TlsIdentity   // AndroidKeyStore alias "pc_canvas_tls", EC P-256, PURPOSE_SIGN, SHA-256, subject CN=Proactive Context canvas, valid now-1d..+825d; SSLContext("TLS") + KeyManagerFactory over KeyStore("AndroidKeyStore")
class CanvasServer(router: CanvasRouter, bindLan: Boolean, tls: TlsIdentity? /* required when bindLan */) // bindLan && tls == null → IllegalArgumentException
class HostPolicy(port: () -> Int, lanIp: () -> String?, secure: () -> Boolean)   // originAllowed: origin == "${if (secure()) "https" else "http"}://$host"
```
**Behaviour:**
- **LAN on:** `makeSecure(tls.serverSocketFactory(), arrayOf("TLSv1.3", "TLSv1.2"))` on `0.0.0.0`, so loopback is HTTPS too while LAN is on. LAN off: plain HTTP on `127.0.0.1`, as today.
- When `KeystoreTlsIdentity` can't be created, `CanvasGraph` exposes `lanAvailable = false`, and the home screen disables the LAN switch with "Secure LAN isn't available on this phone". There is **no** HTTP LAN path anymore.
- **Home screen:**
  - the LAN card shows `https://<ip>:<port>`, "Certificate fingerprint" with the first 8 groups (and a "Show all" toggle), the new text "Your browser will warn once. Check that the fingerprint in its certificate details matches.", and a "New certificate" button (`rotate()` → LAN restart; it revokes all sessions);
  - the LAN warning text "travel unencrypted" is removed.
- **WebView:**
  - the URL scheme follows LAN (`https://127.0.0.1:<port>/#t=…` while LAN is on);
  - `onReceivedSslError(view, handler, error)`: `if (tls.matches(error.certificate.x509Certificate)) handler.proceed() else handler.cancel()`, with no other path to `proceed()`;
  - `CanvasWebPolicy.allow(url, port, secure)` accepts only the scheme matching `secure`.
- **Pairing (`CanvasAuth`):**
  - after a lockout, `currentCode()` returns null until `newCode()` is called explicitly;
  - the home screen's `tick()` must no longer mint after a lockout: it shows "Locked — tap New code" and a "New code" button;
  - lockout durations escalate per process: 30 s, 120 s, 600 s, then 600 s each time; a successful pair resets the escalation.
- **Server minors** (MVP Task 8):
  - on a failed bind attempt, the watchdog thread of that attempt is stopped (no leak);
  - a throwing `log` can't stop the watchdog (`runCatching` around log calls);
  - the watchdog/disarm race: read the deadline once into a local before comparing;
  - `stop()` sets a `stopping` flag that `exec` checks, so a racing accept closes immediately.

**Tests:**
- `TlsIdentityTest`: `fingerprintFormat`, `matchesOwnCertOnly`, `constantTimeCompareUsed` (a structural check: uses `MessageDigest.isEqual`).
- `CanvasServerTest`:
  - `lanServesHttpsOnly` (an OkHttp client trusting only the fixture cert gets 200 over https; a plain-http request to the same port fails);
  - `lanWithoutTlsRejected`;
  - `loopbackOffStaysHttp`;
  - `failedBindLeaksNoWatchdog` (thread count back to baseline after binding over busy ports);
  - `throwingLogKeepsWatchdog`.
- `HostPolicyTest`: `httpsOriginWhenSecure`, `httpOriginRejectedWhenSecure`.
- `CanvasAuthTest`: `noAutoCodeAfterLockout`, `lockoutEscalates`, `successResetsEscalation`.
- `CanvasWebPolicyTest`: `schemeMustMatchSecure`.
- `CanvasHomeTest`: `fingerprintShownWhenLanOn`, `lanDisabledWhenTlsUnavailable`, `lockedShowsNewCodeButton`, `noUnencryptedWarning`.
- Also write a pure, unit-tested `SslDecision.decide(matches: Boolean): Boolean` that the WebView client delegates to, plus a test, `sslErrorProceedsOnlyOnMatch`.

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.*'`

**Commit:** `feat(phone): HTTPS-only LAN with a keystore certificate, pinned WebView, escalating pairing lockout, server thread fixes`

---

### Task 11: Drag-and-drop DAG editor (plain JS, SVG, keyboard), with JS tests

**Files (`phone/src/main/assets/canvas/`):**
- create the pure modules `graph.js`, `keys.js`, `validate.js`, and the DOM module `editor.js`;
- modify `dag.js` (ports, problem marks, focusable edges), `views.js` (the pipeline view uses the editor; one draft model), `style.css`.
- Tests: `phone/src/test/js/graph.test.js`, `keys.test.js`, `validate.test.js`, `editor.test.js` (fake DOM); `phone/src/test/java/com/rokid/context/phone/canvas/js/JsEditorTest.kt` (runs the four); keep `CanvasAssetsTest` green (raise `jsUnderSizeBudget` to 3,500 lines in total, and say so in the commit).

**Pure module contracts:**
```js
// graph.js — never mutates its input; every op returns { pipe, error? } (error is a short fixed text)
export function nextId(pipe, short)                       // "<short>_<n>", smallest free n ≥ 1, matches ^[a-z][a-z0-9_]{0,31}$
export function addNode(pipe, nodeType, after /* nodeId|null */)   // default params from nodeType.params; edge after→new when after's output is accepted (branch: first unused)
export function removeNode(pipe, nodeId)                  // and its edges; "input.photos can't be removed"
export function connect(pipe, from, to, when, nodeTypes)  // uses validate.portCheck; refuses duplicates, self-loops, cycles ("That would make a loop")
export function disconnect(pipe, edge)
export function setParam(pipe, nodeId, key, value); export function setTitle(pipe, nodeId, title)
export function branchesOf(node, nodeTypes)               // switch: cases + "other"; else nodeType.branches
// validate.js
export function portCheck(pipe, from, to, nodeTypes)      // null | "<to title> accepts <A or B>, not <X>" | "<to title> already receives <Y>" (mixed incoming)
export function problemsByNode(problems)                  // {nodeId: [text]}, plus {edgeKey: [text]} with edgeKey "from>to>when"
// keys.js — focus navigation over layout cells
export function move(cells, currentId, key /* ArrowUp|Down|Left|Right */)   // nearest node in that direction by layer/column; stays when none
export function reduce(state, key, ctx)                   // connect-mode state machine: idle → (c) connecting{from} → arrows pick target → Enter → {action:"connect"} | Esc → idle
```
**Editor behaviour (§4):**
- **Palette:** buttons from `/api/node-types` where `palette` is true. Drag (pointer events with `setPointerCapture`) onto the SVG, or Enter, calls `addNode`.
- **Connecting:** each node has an out-port circle (`role="button"`, `aria-label="Connect from <title>"`). Dragging it shows a ghost line. On hover, a target gets class `ok` or `refused` from `portCheck`. Dropping on a refused target shows the reason in an inline `role="status"` line. Dropping from a branching source opens a small menu of free branches.
- **Edges:** focusable `<path tabindex=0>` with `aria-label="<from> to <to> [when]"`. Delete removes the focused node or edge.
- **Validation:** after every change, a 250 ms debounce sends `POST /api/validate`; the response's layout re-renders. While it is pending, the previous layout stays, and a new node is appended at the end of its layer. Problem marks:
  - a node gets class `has-problem`, a badge, and `aria-describedby` pointing at a hidden list item;
  - an edge gets class `has-problem`;
  - the list under the canvas is buttons that focus their node.
- **One draft:** the editor holds the draft. The inspector edits through `setParam`, the JSON panel shows `JSON.stringify(draft, null, 2)`, and "Apply JSON" replaces the draft after a `JSON.parse`. Save sends PUT with `draftRev`; Freeze saves first when dirty. Leaving with unsaved changes asks for confirmation (MVP Task 10 minors 1–2).
- Built-in pipelines are read-only: the palette is hidden, and the note "Duplicate to edit" shows.
- **Look:** the 44 px minimum touch size; the focus ring always visible; works at 360 px (the canvas container scrolls).

**Tests:**
- `graph.test.js`:
  - `nextIdSkipsTaken`, `addNodeDefaultsAndAutoEdge`, `addAfterBranchingUsesFirstFreeBranch`;
  - `removeNodeDropsEdges`, `cannotRemoveInput`;
  - `connectRefusesCycle`, `connectRefusesDuplicate`, `connectRefusesSelf`, `connectRefusesPortMismatch`;
  - `inputNeverMutated`, `idsMatchRule`.
- `validate.test.js`: `portCheckAcceptsListedInputs`, `portCheckMixedIncoming`, `problemsByNodeAndEdge`.
- `keys.test.js`: `arrowMovesByLayerAndColumn`, `staysAtEdge`, `connectModeFlow`, `escCancels`.
- `editor.test.js` (fake DOM, fake `api`):
  - `paletteEnterAddsNode`, `dropOnRefusedShowsReason`;
  - `deleteKeyRemovesFocusedEdge`;
  - `problemMarksApplied`, `debouncedValidateOncePerBurst` (fake timers: inject `setTimeout`);
  - `applyJsonReplacesDraft`, `freezeSavesFirstWhenDirty`;
  - `builtInReadOnly`.

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.js.*' --tests 'com.rokid.context.phone.canvas.CanvasAssetsTest'`

**Commit:** `feat(phone): canvas drag-and-drop DAG editor with keyboard access and inline validation, JS-tested`

---

### Task 12: Web views: versions diff, publish modes, Live view, A/B compare, Ask debug, day filter, usage; full gate

**Files (`phone/src/main/assets/canvas/`):**
- create the pure modules `format.js` (usage line, token estimate text, agreement percent, fingerprint-free) and `diffview.js` (turns `PipelineDiff` JSON into a row model), and the DOM module `asks.js` (the ask list and the ask view);
- modify `views.js`, `app.js` (routes), `style.css`.
- Tests: `phone/src/test/js/format.test.js`, `diffview.test.js`, `views-smoke.test.js` (fake DOM + fake `api`: every view renders from canned JSON without throwing); `phone/src/test/java/com/rokid/context/phone/canvas/js/JsViewsTest.kt`

**Views:**
- `#/p/<id>`: the versions list gains "Diff with previous" and "Diff with draft" → `#/p/<id>/diff?a=&b=`. The publish dialog:
  - offers "Prompts only" (live-shaped), "Shadow" and "Live" (interpretable), each with its blocking reasons;
  - for an interpreter it shows `callFactor` ("about 2.7× the model calls of Live extraction"), the warnings, and "Live always runs the confidence check, the critic and the save rules";
  - "Live" asks for a second confirm.
- `#/p/<id>/diff`: node changes (added, removed, changed with param names), edges, and prompt line diffs (`add`/`del`/`same` classes).
- `#/live`: the active interpreter (name, version, mode, fallbacks/interpreted), the label counts, and the recent frames (thumbnail, label, `_batch` status, node outputs, live memories via `GET /api/frames/{id}`). The nav shows "Live" only when an interpreter is active.
- `#/run/new`:
  - a day picker (`<input type="date">`), sent as `day`;
  - an "A/B against…" selector (pipeline and version) → `estimate-ab` → the confirm dialog shows both pipelines, calls and tokens ("about 12k tokens in, 2k out, from 40 earlier items") → `POST /api/runs/ab`.
- `#/run/<id>`:
  - the usage line ("calls 14 (estimated 15) · tokens 12.1k in / 2.3k out · median 2.4 s");
  - branch counts per switch;
  - when in an A/B group, a link to the partner and an auto-open of compare when both are DONE;
  - the run poll retries a failed GET 3 times with backoff (1, 2, 4 s) before stopping (MVP Task 10 minor 4).
- `#/compare`: per-switch branch counts for A and B, agreement %, and score sums per node.
- `#/asks`, `#/ask/<id>` (§8):
  - the hits table (scores to 3 decimals, cited and in-prompt badges, thumbnails through `loadImage`);
  - the composed prompt in a read-only `<textarea>`;
  - the template editor with Check (`/api/check-prompt` with nodeType `ask.answer`: add that case in Task 9's backend if it's missing, mapping to `TestBed.compatibility(ANSWER, …)`) and Replay (a confirm dialog "1 model call"), showing the result card;
  - the note "Re-retrieved as of the ask's time; may differ if settings or embeddings changed";
  - a redacted ask shows the 409 message.
- Every model or ask text goes through `textContent` (`h()` only).

**Tests:**
- `format.test.js`: `usageLine`, `tokensCompact` (`12100 → "12.1k"`), `agreementPercent`, `nullTokensOmitted`.
- `diffview.test.js`: `rowsForAddedRemovedChanged`, `promptLinesClassed`.
- `views-smoke.test.js`: `pipelineViewRenders`, `diffViewRenders`, `liveViewRenders`, `runViewShowsUsageAndBranches`, `compareShowsAgreement`, `askViewRendersHitsAndPrompt`, `askRedactedShowsMessage`, `runPollRetriesThenStops`.
- `CanvasAssetsTest` stays green.

**Gate:** after the tests pass, run `tools/gate.sh /tmp/pca-canvas-s2-gate.log` from the repo root: BUILD SUCCESSFUL with 0 failures. Note any flaky re-run in the commit body.

**Test command:** `gw.sh :phone:testDebugUnitTest --tests 'com.rokid.context.phone.canvas.*'`, then the gate.

**Commit:** `feat(phone): canvas views for version diff, publish modes, live traces, A/B compare, ask debug and replay`

---

### Task 13: Scene-grouping nodes (GATED: dispatch only when wave 2 is merged into the canvas line)

**Precondition:** `services/orchestration/src/main/java/com/rokid/context/work/frames/scene/SceneRules.kt`, `SceneTuning.kt` and `services/ai/src/main/java/com/rokid/context/ai/contract/ClusterContract.kt` exist, and `PromptKey.CLUSTER_SUMMARY` exists. If any is missing, **do not implement**: report "skipped: wave 2 not merged", and the controller records it in the ledger.

**Files:**
- Modify: `services/ai/src/main/java/com/rokid/context/ai/canvas/NodeTypes.kt` (types `scene.group`: inputs {PHOTOS} → JSON, no branches; `ai.cluster_summary`: inputs {JSON} → EXTRACTION, promptKey CLUSTER_SUMMARY), `PipelineValidator.kt` (param ranges), `InterpretableShape.kt` (`"node <id>: scene nodes are preview-only"`)
- Modify: `services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt` (`SceneGroupExecutor`, `ClusterSummaryExecutor`), `CanvasRunner.kt` (whole-node chunking for `scene.group`: `chunkSize = Int.MAX_VALUE`)
- Modify: the backend's `paramsOf` for the inspector; `views.js` renders the `scene.group` JSON (route, cluster, salience terms)
- Test: `services/ai/src/test/java/com/rokid/context/ai/canvas/SceneNodeTypesTest.kt`, `services/orchestration/src/test/java/com/rokid/context/canvas/SceneExecutorsTest.kt`, extend `views-smoke.test.js`

**Behaviour:**
- **`scene.group` params:** any subset of `SceneTuning` fields (`dedupeCos` 0.5–1, `dedupeWindowMs` 0–3,600,000, `salientAt` 0–1, `driftCos` 0–1, `driftRun` 1–5, `maxMembers` 2–200, `maxAgeMs` 0–3,600,000, `gapMs` 0–3,600,000, `maxReps` 1–3). Unknown keys → a validator problem.
- **The executor:**
  - builds `SceneTuning().copy(...)`;
  - reads each photo's vector from `frame_vector` (read-only DAO query; missing → null, so `SceneRules.route` gives FastPath);
  - reads dwell, motion and blur from `frame_meta`;
  - walks the run's photos in `ts` order through `SceneRules.salience`, `nearestDup`, `route`, `shouldClose` and `closeByTime`, keeping an in-memory open-cluster view, and assigns cluster ids `c1…`;
  - outputs per photo `{"route":"fast_path|near_dup|cluster","cluster":"c1"|null,"salience":{dwell,text,novelty,watch,total},"nearDupOf":id|null}`;
  - makes 0 calls and writes nothing. `textWords` is null when there's no OCR record.
- **`ai.cluster_summary`:**
  - groups items by `cluster` (items without one get item error `"not_clustered"`) and picks reps with `SceneRules.pickReps`;
  - packs ≤ 3 clusters / ≤ 6 photos per call per `photosPerCluster`;
  - calls `RoleRunner` CLUSTER_SUMMARY at `ctx.callClass` with the override for a Draft prompt;
  - gives each member item an Extraction of its cluster's caption and facts.
  - nominal = ceil(clusters / 3), with the clusters estimated as ceil(n / 5) for the estimate (document it); max = nominal × 2.

**Tests:**
- `SceneNodeTypesTest`: `portsAndPromptKey`, `tuningParamRanges`, `unknownTuningKeyRejected`, `scenePipelineNotInterpretable`.
- `SceneExecutorsTest`: `overridesReachRules` (a dedupe threshold of 0.99 vs 0.80 changes near_dup), `noVectorIsFastPath`, `clusterIdsInTimeOrder`, `summaryPacksThreeClustersSixPhotos`, `summaryDraftOverrideUsed`, `writesNothing`.

**Gate:** `tools/gate.sh /tmp/pca-canvas-s2-scene-gate.log`.

**Commit:** `feat(canvas): scene grouping and cluster summary nodes over wave 2 SceneRules (preview only)`

---

### Task 14: Device and browser checklist (for the user; not code; agents never use adb)

Write it as `docs/superpowers/specs/2026-10-21-canvas-slice2-device-checklist.md` (the controller writes the doc; the steps are run by the user with the phone and glasses in hand).

0. **Install safety:**
   - export memories first (Settings → Memory → Export);
   - install only a build whose Room version is ≥ every build already on the phone (no downgrade path);
   - run `SqlCipherMigrationDeviceTest` (extended in Task 4) before the app build;
   - if wave B or wave 2 merged in between, confirm the slice 2 migration number came after theirs.
1. **JS in a real browser:**
   - open the editor in the WebView, in phone Chrome and on a laptop: the pipelines list, the DAG and the inspector render;
   - no console errors (laptop DevTools);
   - check `blob:` thumbnails in the WebView (open MVP item).
2. **Editor:**
   - duplicate `intent-draft`; drag a Prompt node from the palette; connect it; try a refused connection and read the inline reason;
   - delete an edge with the keyboard only (Tab, arrows, Delete); connect with `c` → arrows → Enter;
   - check the 360 px phone width.
3. **A/B:** pick 10 photos of mixed activities (desk, walking, driving as passenger, workout), A/B intent-draft vs live-extract; read the branch counts, agreement and token line; score some cards.
4. **Shadow:**
   - freeze → publish Shadow; wear the glasses for 15 min;
   - `#/live` shows labels on about 1 in 4 batches; Diagnostics shows "Live pipeline … Shadow" and `EXPERIMENT` calls;
   - live memories are unchanged in kind.
5. **Live:**
   - publish Live; 15 min worn; memories still appear; the `#/live` fallbacks count is low;
   - check that an incompatible draft schema is **blocked** at publish; then force live failures by unbinding the role the intent node uses for a few minutes: batches fall back, and after 10 of 30 the publish auto-rolls back (Diagnostics line gone, and the reason shows in `GET /api/publish` history);
   - note the extra call volume against the battery and budget notes.
6. **Rollback:** Roll back from the app's Canvas home with the browser closed; a prompt publish + an interpreter publish unwind newest first.
7. **Ask debug:** open a recent ask; the scores and cited badges look plausible; replay with an edited ANSWER template; the ask history doesn't grow.
8. **TLS:**
   - LAN on; the laptop opens `https://<ip>:8765`, the browser warns, and the fingerprint matches the app; pair;
   - plain `http://<ip>:8765` doesn't load;
   - the WebView works without a warning while LAN is on;
   - "New certificate" → the laptop warns again and the old session is signed out;
   - 5 wrong codes → locked, with no new code until tapped; the next lock is longer;
   - if the LAN switch says "Secure LAN isn't available on this phone", report it (S16 fallback decision).
9. **Wave B hook:** when wave B is merged, swap `FrameIntentReader` to read `frame_meta`'s intent column and bind `FrameIntentSink` to wave B's writer (a small follow-up task); confirm a live label lands in both places.
10. **Scene nodes** (only if Task 13 ran): a scene.group run over a 30-photo desk session; change `dedupeCos` and see the near-dup count change.

**Commit:** none (doc only; the controller commits it with the ledger).
