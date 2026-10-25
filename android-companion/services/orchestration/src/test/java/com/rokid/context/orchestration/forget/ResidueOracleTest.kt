package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.canvas.CanvasItem
import com.rokid.context.canvas.CanvasRunner
import com.rokid.context.canvas.CanvasValue
import com.rokid.context.canvas.Estimate
import com.rokid.context.canvas.ModelLabel
import com.rokid.context.canvas.NodeExecutor
import com.rokid.context.canvas.NodeResult
import com.rokid.context.canvas.PhotoLoad
import com.rokid.context.canvas.PyFnRunHarness
import com.rokid.context.canvas.RunContext
import com.rokid.context.canvas.moment.DaoMomentSource
import com.rokid.context.canvas.moment.DaoSceneRunSource
import com.rokid.context.canvas.moment.MomentBuilder
import com.rokid.context.canvas.moment.MomentFixture
import com.rokid.context.canvas.moment.MomentId
import com.rokid.context.canvas.moment.MomentResolver
import com.rokid.context.canvas.moment.SceneBuilder
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.memory.moment.ObjectKind
import com.rokid.context.memory.moment.ObjectRef
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * RESIDUE ORACLE (design doc 2026-10-25-itemref-forget-design.md, section 3.7, minimal Phase A). It does not know HOW deletion works. It seeds one world (a photo with a memory, a
 * derived memory, an ask, a mark, a Moment, a Scene, derived summaries, sensor rows, a trace, a cached export, Moment and Scene run rows, watch run rows, a JPEG), runs one
 * app-reachable trigger and then scans EVERY table (all columns, as text), every lineage token and every file under files/ and cache/ for the names (ids and texts) of the forgotten
 * item. What may remain is the explicit [CONTROL] allow-list below (control data a deletion creates on purpose); anything else fails the test.
 *
 * Known-open gaps are `@Ignore("GAP-n ...")` tests that FAIL when un-ignored: remove the ignore with the fix. Gaps the user ruled ACCEPTED are `@AcceptedResidue` tests that PIN the
 * residue as it is (they pass; if the behaviour ever changes they fail, so the ruling is revisited rather than forgotten).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ResidueOracleTest {
    /** Residue the user ruled acceptable (2026-10-25). Distinct from `@Ignore`: this is not an open bug. */
    @kotlin.annotation.Retention(AnnotationRetention.RUNTIME) @kotlin.annotation.Target(AnnotationTarget.FUNCTION)
    annotation class AcceptedResidue(val gap: String, val reason: String)

    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val fx get() = MomentFixture(h.db)
    private val audit get() = ObjectAudit(h.db.objectDao()) { 7 }
    private val hour = Math.floorDiv(MomentFixture.MS, 3_600_000L) * 3_600_000L
    private fun t(sec: Long) = hour + sec * 1000
    private fun mid(sec: Long) = MomentId.ofMs(t(sec))

    // The names of the world. "FORGOT" things are forgotten by a trigger, "KEEP" things must survive it.
    private val frame = "frame-FORGOT"; private val mem = "mem-FORGOT"; private val memText = "SECRET-FORGOT-TEXT"
    private val derivedMem = "mem-DERIVED"; private val mark = "mark-FORGOT"; private val undoMark = "mark-UNDO"; private val oldMem = "mem-OLD"; private val oldText = "OLD-EXPIRED-TEXT"
    private val keepFrame = "frame-KEEP"; private val keepMem = "mem-KEEP"
    private val derivedText = "DERIVED-SUMMARY-FORGOT"; private val trace = "trace-FORGOT-0001"

    /** Tables that legitimately keep ids of a forgotten item: control data CREATED by a deletion (fences, tombstones, the outbox), never user content. */
    private val control = setOf(
        "forget_tombstone", "cleanup_outbox", "canvas_helper_cleanup", "moment_tombstone", "canvas_meta",
    )

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(app); h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    // ---- the scan ----

    private fun residue(needles: Collection<String>, allowTables: Set<String> = control): List<String> {
        val out = ArrayList<String>()
        val sdb = h.db.openHelper.readableDatabase
        val tables = ArrayList<String>()
        sdb.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
        for (table in tables.filter { it !in allowTables }) {
            sdb.query("SELECT * FROM `$table`").use { c ->
                while (c.moveToNext()) for (i in 0 until c.columnCount) {
                    if (c.getType(i) != android.database.Cursor.FIELD_TYPE_STRING && c.getType(i) != android.database.Cursor.FIELD_TYPE_INTEGER) continue
                    val v = c.getString(i)
                    needles.firstOrNull { v.contains(it) }?.let { out += "$table.${c.getColumnName(i)} names $it" }
                }
            }
        }
        for (root in listOf(app.filesDir, app.cacheDir)) root.walkTopDown().filter { it.isFile }.forEach { f ->
            val body = if (f.length() < 1_000_000) runCatching { f.readText() }.getOrDefault("") else ""
            needles.firstOrNull { f.name.contains(it) || body.contains(it) }?.let { out += "file ${f.name} names $it" }
        }
        return out.distinct()
    }
    private fun assertClean(trigger: String, needles: Collection<String>) {
        val r = residue(needles)
        assertTrue("$trigger leaves residue naming the forgotten item: $r", r.isEmpty())
    }

    // ---- the world ----

    private val label: suspend (PipelineNode) -> ModelLabel? = { n -> ModelLabel(n.id, "az", "gpt") }
    private val recorder: NodeExecutor = object : NodeExecutor {
        override val type = NodeTypes.PROMPT
        override fun nominalCalls(node: PipelineNode, items: Int) = 0
        override fun maxCalls(node: PipelineNode, items: Int) = 0
        override suspend fun run(node: PipelineNode, items: List<CanvasItem>, ctx: RunContext) =
            items.map { NodeResult(it.frameId, CanvasValue.Json(JsonObject(mapOf("ok" to JsonPrimitive(true))))) }
    }
    private fun resolver() = MomentResolver(DaoMomentSource(h.dao, h.dao3))
    private fun runner() = CanvasRunner(
        h.dao, { f -> PhotoLoad(f.map(h::photo), emptySet()) }, mapOf(NodeTypes.PROMPT to recorder),
        { id -> val ai = com.rokid.context.orchestration.TestAi.graph(app) { "" }; RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }) },
        h.tokens, { false }, { h.now }, { "run${++h.ids}" }, { h.gen }, { _, _, m -> h.log += m }, moments = resolver(), scenes = DaoSceneRunSource(h.db, resolver()),
    )
    private fun pipeline(input: String) = Pipeline(2, "p-$input", "P", listOf(
        PipelineNode("in", input, "in", JsonObject(emptyMap())),
        PipelineNode("ask", NodeTypes.PROMPT, "ask", buildJsonObject { put("schema", "{\"type\":\"object\"}") }),
    ), listOf(PipelineEdge("in", "ask")))

    /** One world; every test starts from exactly this. */
    private suspend fun world() {
        val jpg = File(app.filesDir, "rokid_archive/$frame.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        h.db.frameDao().insertIgnore(FrameEntity(frame, t(10) + 5, jpg.absolutePath, "rokid_glasses"))
        h.db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity(frame, null, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = t(10) + 5))
        fx.frame(keepFrame, t(900) + 5)
        suspend fun m(id: String, f: String?, text: String, derived: Boolean = false, createdAt: Long = 1) =
            h.db.memoryDao().insert(MemoryEntity(id, f, text, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, createdAt, derived, text))
        m(mem, frame, memText); m(derivedMem, null, "text of the derived fact", derived = true); m(keepMem, keepFrame, "text of the kept fact")
        h.db.forgetDao().insertEdges(listOf(MemorySourceEntity(derivedMem, mem)))
        fx.ask("ask-FORGOT", frame)
        fx.mark(mark, t(12)); fx.mark(undoMark, t(1500), name = "UNDO")
        MomentBuilder(h.db, enabled = { true }, clock = { 5 }).build(listOf(mid(10), mid(900), mid(1500)))
        SceneBuilder(h.db, enabled = { true }, clock = { 5 }).rebuildWindow(t(0), t(3599))
        audit.writeDerived(ObjectRef(ObjectKind.MOMENT, mid(10), 1), DerivedField.SUMMARY, derivedText, null, 1)
        h.db.momentDao().replaceSensor(mid(10), listOf(MomentSensorEntity(mid(10), t(10) / 1000, trace, 1, "OFFSET_ESTIMATED", 40, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, "WALKING", null, 5, 5)), 1, "build")
        File(app.cacheDir, "exports/proactive-context-memories-1.json").apply { parentFile!!.mkdirs(); writeText("{\"memories\":[{\"id\":\"$mem\",\"text\":\"$memText\"}]}") }
        val r = runner()
        val moments = h.db.momentDao().allMoments().map { it.momentId }
        val scenes = h.db.sceneDao().scenesBetween(t(0), t(3599), 50).map { it.sceneId }
        assertTrue("the world has Moments and Scenes", moments.size >= 3 && scenes.isNotEmpty())
        val mp = pipeline(NodeTypes.INPUT_MOMENTS); val sp = pipeline(NodeTypes.INPUT_SCENES)
        r.startMoments(mp, mp.id, null, moments, (r.estimateMoments(mp, moments, label) as Estimate.Ok).confirmToken, h.scope); h.finish(r)
        r.startScenes(sp, sp.id, null, scenes, (r.estimateScenes(sp, scenes, label) as Estimate.Ok).confirmToken, h.scope); h.finish(r)
        h.dao.insertRun(CanvasRunEntity("run-watch", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, 1, null, "[]"))
        h.dao.upsertItem(CanvasRunItemEntity("run-watch", "watch:1000", "n", "OK", null, "{}", null, null, null, null, null, null, null))
    }

    private fun momentOfForgotten() = mid(10)

    // ---- the triggers the app can reach ----

    @Test fun forgetFactLeavesNothingThatNamesIt() = runBlocking {
        world()
        val before = residue(listOf(mem, memText))
        assertTrue("the oracle must see the world before the trigger (found ${before.size})", before.size >= 3)
        assertTrue(ForgetActions.forgetFact(app, mem))
        assertClean("Forget fact", listOf(mem, memText))
        assertTrue("the kept fact survives", h.db.memoryViewDao().byId(keepMem) != null)
    }

    @Test fun forgetPhotoLeavesNothingThatNamesIt() = runBlocking {
        world()
        assertTrue(ForgetActions.forgetMoment(app, frame))
        assertClean("Forget photo", listOf(frame, mem, memText, mark, derivedText, trace, momentOfForgotten()))
        assertTrue("the kept photo survives", h.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM frame WHERE id = '$keepFrame'").use { c -> c.moveToFirst(); c.getInt(0) } == 1)
    }

    /** After the next Moment build (the 15 minute cadence or a build trigger) nothing names an undone mark; see GAP-13 below for the window before it. */
    @Test fun markUndoLeavesNothingThatNamesTheMarkOnceTheMomentsAreRebuilt() = runBlocking {
        world()
        assertTrue(h.dao3.deleteLabel(undoMark)) // what MarkRecorder does for the tap and the voice undo
        MomentBuilder(h.db, enabled = { true }, clock = { 6 }).build(listOf(mid(1500)))
        assertClean("mark undo", listOf(undoMark))
        assertTrue("another mark survives", h.dao3.label(mark) != null)
    }

    @Test fun retentionExpiryLeavesNothingThatNamesTheExpiredFact() = runBlocking {
        world()
        h.db.memoryDao().insert(MemoryEntity(oldMem, null, oldText, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, System.currentTimeMillis() - 3 * 24 * 3_600_000L, false, oldText))
        assertTrue(residue(listOf(oldMem, oldText)).isNotEmpty())
        runBlocking { TestListenableWorkerBuilder<RetentionWorker>(app).build().doWork() }
        assertClean("retention expiry", listOf(oldMem, oldText))
    }

    @Test fun deleteAllLeavesNothingThatNamesAnything() = runBlocking {
        world()
        MemoryWipe.deleteAll(app)
        assertClean("Delete all", listOf(frame, mem, memText, derivedMem, keepFrame, keepMem, mark, undoMark, derivedText, trace, "watch:", "ask-FORGOT", "proactive-context-memories"))
    }

    @Test fun watchOffLeavesNoWatchRunRow() = runBlocking {
        world()
        assertTrue(residue(listOf("watch:")).isNotEmpty())
        h.dao.deleteWatchRuns() // what the hub does when the watch source is switched off
        assertClean("watch off", listOf("watch:"))
        assertTrue("a Moment run stays", h.dao.runs(10).isNotEmpty())
    }

    // ---- accepted residue (user rulings 2026-10-25): pinned, not bugs ----

    @AcceptedResidue("GAP-2", "the watch has no app of ours, its sensor data belongs to the watch vendor's services and the in-memory ring is ephemeral: watch run rows and the ring are only removed by watch-off, Delete all and run pruning")
    @Test fun acceptedGap2WatchRunRowsSurviveAPhotoForget() = runBlocking {
        world()
        assertTrue(ForgetActions.forgetMoment(app, frame))
        assertTrue("accepted: a photo Forget does not touch watch run rows", h.dao.items("run-watch").isNotEmpty())
    }

    @AcceptedResidue("GAP-3", "the aggregated sensor summary in a Scene run row is too coarse to be personal data: it keeps no lineage token of the trace and survives the trace's Forget")
    @Test fun acceptedGap3SceneRunRowsKeepTheirSensorSummaryAfterTheTraceIsForgotten() = runBlocking {
        world()
        val sceneRows = { runBlocking { h.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM canvas_run_item WHERE frameId LIKE 'scene:%'").use { c -> c.moveToFirst(); c.getInt(0) } } }
        val before = sceneRows()
        assertTrue(before > 0)
        h.dao3.tombstoneTrace(trace, 9)
        assertEquals("accepted: the Scene run rows stay", before, sceneRows())
    }

    // ---- open gaps (design doc section 5): each FAILS today; remove the @Ignore with its fix ----

    @Ignore("GAP-4: Moment-run batch mates survive a photo Forget (rows of OTHER Moments that shared the forgotten photo's model call keep its lineage); see the design doc section 5")
    @Test fun gap4BatchMatesOfAMomentRunAreDeletedWithThePhoto() = runBlocking {
        world()
        // two Moments shared one model call: the forgotten photo's row carries mf:<frame>|call, the other Moment's row only the call token
        val call = "run9:ask:moment:x"
        h.dao.insertRun(CanvasRunEntity("run9", "p", null, "h", "{}", "DONE", 2, 1, 0, 0, 1, null, "[]"))
        h.dao.upsertItem(CanvasRunItemEntity("run9", "moment:a", "ask", "OK", null, "{}", null, null, null, null, null, null, "mf:$frame|$call", null))
        h.dao.upsertItem(CanvasRunItemEntity("run9", "moment:b", "ask", "OK", null, "{}", null, null, null, null, null, null, call, null))
        assertTrue(ForgetActions.forgetMoment(app, frame))
        assertEquals(emptyList<CanvasRunItemEntity>(), h.dao.items("run9"))
    }

    @Ignore("GAP-13 (new, low, found by this oracle): right after a mark undo, moment_part and object_audit still hold the undone mark's id (ids only, no text) until the next Moment build; see the handoff")
    @Test fun gap13MarkUndoLeavesNoIdBeforeTheNextMomentBuild() = runBlocking {
        world()
        assertTrue(h.dao3.deleteLabel(undoMark))
        assertClean("mark undo (before the rebuild)", listOf(undoMark))
    }

    @Ignore("GAP-9: frame_cluster.repFrameIds keeps the id of a forgotten photo (ids only); see the design doc section 5")
    @Test fun gap9ClusterRowsDoNotKeepAForgottenFrameId() = runBlocking {
        world()
        h.db.openHelper.writableDatabase.execSQL("INSERT INTO frame_cluster (clusterId, openedAt, repFrameIds, state, attempts) VALUES ('c1', 1, '$frame', 'OPEN', 0)")
        assertTrue(ForgetActions.forgetMoment(app, frame))
        assertClean("Forget photo (clusters)", listOf(frame))
    }
}
