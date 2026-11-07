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
        "forget_tombstone", "cleanup_outbox", "canvas_helper_cleanup", "moment_tombstone",
    )

    /**
     * `canvas_meta` is NOT exempt any more (v0.9.7 review fix R18: a blanket exemption hid `mark_claim:*` and `canvas_save:*`). It is scanned like every table except the ONE documented control key family that
     * must outlive a mark: `mark_undone:<labelId>` (an id, no content; it refuses a late photo of an undone mark; Delete all clears it, [deleteAllLeavesNoMarker...] pins that).
     */
    private val tableFilter = mapOf("canvas_meta" to " WHERE `key` NOT LIKE 'mark\\_undone:%' ESCAPE '\\'")

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(app); h = PyFnRunHarness(app); com.rokid.context.memory.forget.ForgetScopeSetting.experimentalAllowed = { true } } // Developer mode on for the Moment/Scene scopes
    @After fun tearDown() { com.rokid.context.memory.forget.ForgetScopeSetting.experimentalAllowed = { false }; h.close() }

    // ---- the scan ----

    private fun residue(needles: Collection<String>, allowTables: Set<String> = control): List<String> {
        val out = ArrayList<String>()
        val sdb = h.db.openHelper.readableDatabase
        val tables = ArrayList<String>()
        sdb.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'").use { c -> while (c.moveToNext()) tables += c.getString(0) }
        for (table in tables.filter { it !in allowTables }) {
            sdb.query("SELECT * FROM `$table`" + (tableFilter[table] ?: "")).use { c ->
                while (c.moveToNext()) for (i in 0 until c.columnCount) {
                    if (c.getType(i) != android.database.Cursor.FIELD_TYPE_STRING && c.getType(i) != android.database.Cursor.FIELD_TYPE_INTEGER) continue
                    val v = c.getString(i)
                    needles.firstOrNull { v.contains(it) }?.let { out += "$table.${c.getColumnName(i)} names $it" }
                }
            }
        }
        for (root in listOf(app.filesDir, app.cacheDir)) root.walkTopDown().filter { it.isFile }.forEach { f ->
            needles.firstOrNull { f.name.contains(it) || fileContains(f, it) }?.let { out += "file ${f.name} names $it" }
        }
        return out.distinct()
    }
    /** Streams the file in 64 KB chunks with an overlap of the needle length, so a file of any size is searched. */
    private fun fileContains(f: File, needle: String): Boolean {
        val keep = needle.length - 1
        var carry = ""
        f.reader(Charsets.ISO_8859_1).use { r ->
            val buf = CharArray(64 * 1024)
            while (true) {
                val n = r.read(buf); if (n < 0) return false
                val text = carry + String(buf, 0, n)
                if (text.contains(needle)) return true
                carry = text.takeLast(keep)
            }
        }
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
        { id -> val ai = com.rokid.context.orchestration.TestAi.graph(app) { "" }; RunContext(id, ai.runner, ai.router, ai.prompts, ai.persona, { role -> ai.bindings.get(role) }, liveness = com.rokid.context.orchestration.AllLive) },
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
        // over 1 MB on purpose: a scan that skips big files, or a deletion that does, must be caught (the forgotten text is at the END)
        File(app.cacheDir, "exports/proactive-context-memories-1.json").apply { parentFile!!.mkdirs(); writeText("{\"pad\":\"" + "x".repeat(1_200_000) + "\",\"memories\":[{\"id\":\"$mem\",\"text\":\"$memText\"}]}") }
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

    /** v14: a Moment carries TEXT (timestamp + visible memory text). The oracle sees it in `moment.text`; Forget drops the holding Moments, and the rebuild never writes a forgotten or stale-derived text back. */
    @Test fun momentTextOfAForgottenFactAndOfItsDerivedSummaryIsGoneAndStaysGoneAfterTheRebuild() = runBlocking {
        world()
        h.db.frameSourceDao().insertAll(listOf(com.rokid.context.memory.db.FrameSourceEntity(derivedMem, keepFrame, null, "FACT"))) // the derived summary is also cited on the KEPT photo
        val builder = MomentBuilder(h.db, enabled = { true }, clock = { 6 })
        builder.build(listOf(mid(10), mid(900)))
        val texts = { runBlocking { h.db.momentDao().allMoments().mapNotNull { it.text } } }
        assertTrue("the world's Moments carry the texts: ${texts()}", texts().any { memText in it } && texts().any { "text of the kept fact" in it && "text of the derived fact" in it })
        assertTrue("the oracle scans moment.text", residue(listOf(memText)).any { it.startsWith("moment.text") })
        assertTrue(ForgetActions.forgetFact(app, mem))
        assertTrue("no Moment text names the forgotten fact right after Forget: ${texts()}", texts().none { memText in it || "text of the derived fact" in it })
        builder.build(listOf(mid(10), mid(900)))
        assertTrue("nor after the rebuild: ${texts()}", texts().none { memText in it || "text of the derived fact" in it })
        assertEquals("the kept photo's Moment keeps only the visible fact", listOf("text of the kept fact"), texts().filter { "kept" in it }.map { it.lines().drop(1).joinToString("|") })
        assertClean("Forget fact (Moment text)", listOf(mem, memText))
    }

    // ---- v0.9.6: memories saved from a canvas run (Run & Save) are memories like any other ----

    private val savedText = "CANVAS-SAVED-FORGOT-TEXT"
    private val savedId = "rokid_cv_runcv_0"

    /** The world plus a canvas run over the forgotten photo whose extraction was SAVED by the real save path (`rokid_cv_runcv_0`, `rokid_cv_runcv_c0`). */
    private suspend fun savedFromCanvas() {
        world()
        val p = Pipeline(2, "p-save", "P", listOf(PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())), PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", JsonObject(emptyMap())),
            PipelineNode("save", NodeTypes.MEMORY_WRITE, "save", JsonObject(emptyMap()))), listOf(PipelineEdge("photos", "ext"), PipelineEdge("ext", "save")))
        h.dao.insertRun(CanvasRunEntity("runcv", "p-save", null, "h", com.rokid.context.ai.canvas.PipelineJson.encode(p), "DONE", 1, 1, 0, 0, 1, null, "[]"))
        val ex = CanvasValue.Extraction(listOf(com.rokid.context.ai.contract.Fact(savedText, "object", 0.9, emptyList(), listOf(frame))), listOf(com.rokid.context.ai.contract.FrameCaption(frame, "CANVAS-CAPTION-TEXT")), trusted = true)
        h.dao.upsertItem(CanvasRunItemEntity("runcv", frame, "ext", "OK", null, com.rokid.context.canvas.CanvasValueJson.encode(ex), null, null, null, null, null, null, "runcv:ext:$frame"))
        h.dao.upsertItem(CanvasRunItemEntity("runcv", frame, "save", "OK", null, com.rokid.context.canvas.CanvasValueJson.encode(CanvasValue.Preview(listOf(com.rokid.context.canvas.PreviewRow("fact", savedText, "object", "SHORT", "OK")))), null, null, null, null, null, null, "runcv:ext:$frame"))
        val r = com.rokid.context.canvas.CanvasRunSave(app, h.db, afterSave = { _, _ -> }).save("runcv")
        assertEquals("codex r10: the caption is not saved from a stored run", com.rokid.context.canvas.CanvasRunSave.Result.Saved(1, 0), r)
        assertTrue("the oracle sees the saved memory", residue(listOf(savedId, savedText)).size >= 2)
    }

    @Test fun forgetFactOfACanvasSavedMemoryLeavesNothingThatNamesIt() = runBlocking {
        savedFromCanvas()
        assertTrue(ForgetActions.forgetFact(app, savedId))
        assertClean("Forget fact (canvas saved)", listOf(savedId, savedText))
    }

    @Test fun forgetPhotoOfACanvasSavedMemoryLeavesNothingThatNamesIt() = runBlocking {
        savedFromCanvas()
        assertTrue(ForgetActions.forgetMoment(app, frame))
        assertClean("Forget photo (canvas saved)", listOf(savedId, "rokid_cv_runcv_c0", savedText, "CANVAS-CAPTION-TEXT"))
    }

    @Test fun deleteAllLeavesNothingOfACanvasSavedMemory() = runBlocking {
        savedFromCanvas()
        MemoryWipe.deleteAll(app)
        assertClean("Delete all (canvas saved)", listOf(savedId, savedText))
        assertEquals(0, h.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM canvas_meta WHERE key LIKE 'canvas%save:%'").use { c -> c.moveToFirst(); c.getInt(0) })
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
        File(app.cacheDir, "exports/proactive-context-memories-2.json").writeText("{\"memories\":[{\"id\":\"$oldMem\",\"text\":\"$oldText\"}]}") // an export made before the expiry
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

    // ---- v0.9.7 review fix R18: the markers in canvas_meta leave with their owners ----

    private fun keys(like: String): List<String> = h.db.openHelper.readableDatabase.query("SELECT `key` FROM canvas_meta WHERE `key` LIKE '$like' ESCAPE '\\'").use { c -> val out = ArrayList<String>(); while (c.moveToNext()) out += c.getString(0); out }
    /** A claim whose mark is gone, or a save marker whose run is gone: ids only, but residue that no owner will ever clean. */
    private fun orphanMarkers(): List<String> = h.db.openHelper.readableDatabase.query(
        "SELECT `key` FROM canvas_meta WHERE (`key` LIKE 'mark\\_claim:%' ESCAPE '\\' AND substr(`key`, 12) NOT IN (SELECT labelId FROM canvas_label)) " +
            "OR (`key` LIKE 'canvas\\_save:%' ESCAPE '\\' AND substr(`key`, 13) NOT IN (SELECT runId FROM canvas_run))",
    ).use { c -> val out = ArrayList<String>(); while (c.moveToNext()) out += c.getString(0); out }
    /** A mark that is still waiting for its photo and has already claimed one (the durable pause allowance). */
    private suspend fun claimedMark(id: String, atMs: Long) {
        fx.mark(id, atMs, photo = "pending")
        assertTrue(h.dao3.claimMarkPhoto(id, "claimed-photo")); assertEquals(listOf("mark_claim:$id"), keys("mark\\_claim:$id"))
    }

    @Test fun aMarkClaimLeavesWithAnUndoneMark() = runBlocking {
        world(); claimedMark("mk:c1", t(20))
        assertTrue(h.dao3.deleteLabel("mk:c1"))
        assertEquals(emptyList<String>(), orphanMarkers()); assertClean("mark undo (claim)", listOf("mk:c1:"))
    }

    @Test fun aMarkClaimLeavesWithAForgottenPhotoNearIt() = runBlocking {
        world(); claimedMark("mk:c2", t(14)) // inside the photo link radius of the forgotten photo
        assertTrue(ForgetActions.forgetMoment(app, frame))
        assertEquals(emptyList<String>(), orphanMarkers()); assertEquals(emptyList<String>(), keys("mark\\_claim:mk:c2"))
    }

    @Test fun aMarkClaimLeavesWithARangeForget() = runBlocking {
        world(); claimedMark("mk:c3", t(20) + 100)
        com.rokid.context.memory.forget.CanvasSlice3Forget(h.db).forgetRange(t(20), t(21))
        assertEquals(emptyList<String>(), orphanMarkers()); assertEquals(emptyList<String>(), keys("mark\\_claim:mk:c3"))
    }

    @Test fun aMarkClaimLeavesWhenAMomentForgetDeletesAStrayMarkOfAProjection() = runBlocking {
        world(); claimedMark("mk:c4", t(700)) // far from the forgotten second: only the projection names it
        h.db.momentDao().replace(com.rokid.context.memory.db.MomentEntity(mid(10), 1, 1, "h", 1, com.rokid.context.memory.moment.MomentType.FRAME), listOf(com.rokid.context.memory.db.MomentPartEntity(mid(10), com.rokid.context.memory.db.MomentPartKind.MARK, "mk:c4", "r", 1)))
        val service = com.rokid.context.memory.forget.ForgetService(h.db, MemoryGraph.get(app).proposals)
        com.rokid.context.memory.forget.MomentForget(h.db, service).forgetMoments(listOf(mid(10)))
        assertEquals("the stray mark itself is gone", null, h.dao3.label("mk:c4"))
        assertEquals(emptyList<String>(), orphanMarkers()); assertEquals(emptyList<String>(), keys("mark\\_claim:mk:c4"))
    }

    @Test fun aSaveMarkerLeavesWithItsRunAndNeverOutlivesIt() = runBlocking {
        savedFromCanvas()
        assertEquals(listOf("canvas_save:runcv"), keys("canvas\\_save:%"))
        assertTrue(ForgetActions.forgetMoment(app, frame))
        assertEquals("the run row stays after a photo Forget, so its marker may (a retry must not save twice)", emptyList<String>(), orphanMarkers())
        h.dao.deleteRun("runcv")
        assertEquals(emptyList<String>(), keys("canvas\\_save:%"))
    }

    @Test fun deleteAllLeavesNoMarkerOfAnyKind() = runBlocking {
        savedFromCanvas(); claimedMark("mk:c5", t(20)); h.dao3.putMeta("mark_undone:mk:gone", "1")
        MemoryWipe.deleteAll(app)
        assertEquals(emptyList<String>(), keys("canvas\\_save:%") + keys("mark\\_claim:%") + keys("mark\\_undone:%"))
    }

    /** `signal_config` holds the user's own expressions (user config): Delete all wipes it, no Forget touches it. Written the way the phone store writes it. */
    private fun signalPrefs() = app.getSharedPreferences("signal_config", Context.MODE_PRIVATE)
    @Test fun theSignalConfigurationIsUserConfigWipedByDeleteAllAndNotByAForget() = runBlocking {
        world()
        signalPrefs().edit().putString("derived", "{\"d_mine\":{\"USER-EXPRESSION\":1}}").putString("history", "[{\"derived\":{\"d_old\":{}}}]").commit()
        assertTrue(ForgetActions.forgetFact(app, mem)); assertTrue(ForgetActions.forgetMoment(app, frame))
        com.rokid.context.memory.forget.CanvasSlice3Forget(h.db).forgetRange(t(0), t(3599))
        assertTrue("no Forget touches the user's configuration", signalPrefs().getString("derived", null)!!.contains("USER-EXPRESSION") && signalPrefs().contains("history"))
        MemoryWipe.deleteAll(app)
        assertEquals("Delete all removes it", emptyMap<String, Any?>(), signalPrefs().all)
    }

    // ---- v0.9.7 slice C: the Forget scope. After each scope nothing names the removed set; neighbours survive at PHOTO ----

    private val nearFrame = "frame-NEAR"; private val nearMem = "mem-NEAR"; private val nearText = "NEAR-NEIGHBOUR-TEXT"
    /** The world plus a neighbouring photo 4 s after the forgotten one (inside the 10 s radius, in its own Moment) with a fact. */
    private suspend fun scopedWorld() {
        world()
        fx.frame(nearFrame, t(14) + 5); h.db.memoryDao().insert(MemoryEntity(nearMem, nearFrame, nearText, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, t(14) + 5, false, nearText))
        MomentBuilder(h.db, enabled = { true }, clock = { 6 }).build(listOf(mid(14)))
        assertTrue("the neighbour has its own Moment before", h.db.momentDao().moment(mid(14)) != null)
    }
    private fun frameRows(id: String) = h.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM frame WHERE id = '$id'").use { c -> c.moveToFirst(); c.getInt(0) }

    @Test fun photoScopeLeavesNothingOfThePhotoAndTheNeighbourSurvivesAndFormsItsOwnMoment() = runBlocking {
        scopedWorld()
        fx.frame("frame-SAMESEC", t(10) + 700) // another photo in the SAME second: it survives and may form a Moment (no fence at PHOTO scope)
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(app, com.rokid.context.memory.forget.ForgetTarget.Photo(frame), com.rokid.context.memory.forget.ForgetScope.PHOTO))
        assertClean("Forget photo (scope Photo)", listOf(frame, mem, memText, mark, derivedText, trace, momentOfForgotten()))
        assertEquals("the neighbouring photo survives", 1, frameRows(nearFrame)); assertTrue(h.db.memoryViewDao().byId(nearMem) != null); assertEquals(1, frameRows(keepFrame))
        MomentBuilder(h.db, enabled = { true }, clock = { 7 }).build(listOf(mid(10), mid(14)))
        assertTrue("the neighbour forms its OWN Moment", h.db.momentDao().moment(mid(14)) != null)
        assertEquals(1, frameRows("frame-SAMESEC")); assertTrue("the sibling of the same second rebuilds that Moment (today's behaviour)", h.db.momentDao().moment(mid(10)) != null)
        assertClean("Forget photo (scope Photo, after the rebuild)", listOf(frame, mem, memText, mark, derivedText, trace))
    }

    @Test fun momentScopeLeavesNothingOfTheMomentWindowAndNeighboursOutsideItSurvive() = runBlocking {
        scopedWorld()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(app, com.rokid.context.memory.forget.ForgetTarget.Photo(frame), com.rokid.context.memory.forget.ForgetScope.MOMENT))
        assertClean("Forget (scope Moment)", listOf(frame, mem, memText, mark, derivedText, trace, momentOfForgotten(), nearFrame, nearMem, nearText, mid(14)))
        assertEquals(1, frameRows(keepFrame)); assertTrue("a Moment far away survives", h.db.momentDao().moment(mid(900)) != null); assertTrue(h.dao3.label(undoMark) != null)
        MomentBuilder(h.db, enabled = { true }, clock = { 7 }).build(listOf(mid(10), mid(14), mid(12)))
        assertClean("Forget (scope Moment, after the rebuild)", listOf(frame, mem, memText, mark, derivedText, trace, momentOfForgotten(), nearFrame, nearMem, nearText, mid(14)))
        assertTrue("its Moment run rows and its export are gone too", residue(listOf(mem, memText, nearText, "proactive-context-memories")).isEmpty())
    }

    @Test fun sceneScopeLeavesNothingOfTheSceneAndAnotherSceneSurvives() = runBlocking {
        scopedWorld()
        val all = h.db.sceneDao().allScenes(); val sceneOf = h.db.sceneDao().sceneIdsHoldingMoments(listOf(mid(10))).single()
        val inScene = h.db.sceneDao().parts(sceneOf).map { it.refId }
        val otherScenes = all.map { it.sceneId } - sceneOf
        assertTrue("the oracle needs a second Scene to keep (the Moment 900 s later): $all", otherScenes.isNotEmpty())
        val gone = h.db.momentDao().partsOf(inScene).filter { it.kind == com.rokid.context.memory.db.MomentPartKind.FRAME }.map { it.refId } + inScene + sceneOf
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(app, com.rokid.context.memory.forget.ForgetTarget.Photo(frame), com.rokid.context.memory.forget.ForgetScope.SCENE))
        assertClean("Forget (scope Scene)", gone + listOf(mem, memText, mark, derivedText, trace))
        assertEquals(null, h.db.sceneDao().scene(sceneOf)); assertEquals("another Scene survives", otherScenes.toSet(), h.db.sceneDao().allScenes().map { it.sceneId }.toSet())
        assertEquals(1, frameRows(keepFrame)); assertTrue(h.db.memoryViewDao().byId(keepMem) != null)
        SceneBuilder(h.db, enabled = { true }, clock = { 8 }).rebuildWindow(t(0), t(3599))
        MomentBuilder(h.db, enabled = { true }, clock = { 8 }).build(inScene)
        assertClean("Forget (scope Scene, after the rebuilds)", gone + listOf(mem, memText, mark, derivedText, trace))
    }

    @Test fun aSceneEntryLeavesNothingOfItsSceneEvenWithTheSettingOnPhoto() = runBlocking {
        scopedWorld()
        val sceneOf = h.db.sceneDao().sceneIdsHoldingMoments(listOf(mid(10))).single()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(app, com.rokid.context.memory.forget.ForgetTarget.Scene(sceneOf), com.rokid.context.memory.forget.ForgetScope.PHOTO))
        assertClean("Forget Scene (setting Photo)", listOf(frame, mem, memText, mark, derivedText, trace, sceneOf))
    }

    /** Round 4 (2): a fact of the span that cites a photo OUTSIDE it: the photo is kept, but the fact's text must be gone from stored run outputs, DONE helper results, Moment.text and the cached export. */
    @Test fun momentScopeLeavesNoTextOfAFactCitingAnOutsidePhotoAnywhere() = runBlocking {
        scopedWorld()
        val citeText = "CITING-FACT-TEXT-ZQ"
        h.db.memoryDao().insert(MemoryEntity("mem-CITE", keepFrame, citeText, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, t(11), false, citeText))
        MomentBuilder(h.db, enabled = { true }, clock = { 6 }).build(listOf(mid(900)))
        h.dao.insertRun(CanvasRunEntity("run-cite", "p", null, "h", "{}", "DONE", 1, 1, 0, 0, 1, null, "[]"))
        h.dao.upsertItem(CanvasRunItemEntity("run-cite", keepFrame, "n", "OK", null, "{\"fact\":\"$citeText\"}", null, null, null, null, null, null, null))
        h.dao3.insertHelperJob(com.rokid.context.memory.db.CanvasHelperJobEntity("hj-cite", "py_exec", "DONE", frameIdsJson = "[\"$keepFrame\"]", createdAt = 1, updatedAt = 1, resultJson = "{\"r\":\"$citeText\"}"))
        File(app.cacheDir, "exports/proactive-context-memories-9.json").writeText("{\"memories\":[{\"text\":\"$citeText\"}]}")
        val before = residue(listOf(citeText)).map { it.substringBefore('.') }.toSet()
        assertTrue("the oracle sees it in run items, helper jobs, moments, memory and the export: $before", before.containsAll(listOf("canvas_run_item", "canvas_helper_job", "memory")))
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(app, com.rokid.context.memory.forget.ForgetTarget.Photo(frame), com.rokid.context.memory.forget.ForgetScope.MOMENT))
        assertClean("Forget (scope Moment), fact citing an outside photo", listOf(citeText, "mem-CITE"))
        assertEquals("the outside photo is kept", 1, frameRows(keepFrame))
    }

    @Test fun aFactAtMomentScopeLeavesNothingOfItsMoment() = runBlocking {
        scopedWorld()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(app, com.rokid.context.memory.forget.ForgetTarget.Fact(mem), com.rokid.context.memory.forget.ForgetScope.MOMENT))
        assertClean("Forget fact (scope Moment)", listOf(frame, mem, memText, mark, derivedText, trace, nearFrame, nearText))
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
