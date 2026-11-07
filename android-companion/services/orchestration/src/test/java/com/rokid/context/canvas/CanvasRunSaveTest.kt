package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.canvas.NodeTypes
import com.rokid.context.ai.canvas.Pipeline
import com.rokid.context.ai.canvas.PipelineEdge
import com.rokid.context.ai.canvas.PipelineJson
import com.rokid.context.ai.canvas.PipelineNode
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.db.CanvasRunEntity
import com.rokid.context.memory.db.CanvasRunItemEntity
import com.rokid.context.memory.db.CanvasSaveMarker
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.SavedMemory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Playground vs Run-and-Save: [CanvasRunSave] over stored run rows. The memories it writes are read back from the real tables.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasRunSaveTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var h: PyFnRunHarness
    private val followUps = ArrayList<List<SavedMemory>>()

    @Before fun setUp() { androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app); h = PyFnRunHarness(app) }
    @After fun tearDown() { h.close() }

    private fun saver(gate: DataOpGate = DataOpGate(), generation: () -> Long = MemoryWipe.generation::get, clock: () -> Long = { 5_000L }) =
        CanvasRunSave(app, h.db, { gate }, clock, generation) { _, saved -> followUps += saved }

    private suspend fun frame(id: String, live: Boolean = true) {
        val jpg = File(app.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); if (live) writeBytes(byteArrayOf(1)) else delete() }
        h.db.frameDao().insertIgnore(FrameEntity(id, 1_000, jpg.absolutePath, "rokid_glasses"))
    }

    private fun pipeline(withMemory: Boolean = true, extra: List<PipelineNode> = emptyList()): Pipeline {
        val nodes = listOf(PipelineNode("photos", NodeTypes.INPUT_PHOTOS, "photos", JsonObject(emptyMap())), PipelineNode("ext", NodeTypes.VISION_EXTRACT, "ext", JsonObject(emptyMap()))) +
            (if (withMemory) listOf(PipelineNode("save", NodeTypes.MEMORY_WRITE, "save", JsonObject(emptyMap()))) else emptyList()) + extra
        val edges = listOf(PipelineEdge("photos", "ext")) + (if (withMemory) listOf(PipelineEdge("ext", "save")) else emptyList())
        return Pipeline(2, "p", "P", nodes, edges)
    }

    /** A DONE run whose extract node produced [byFrame] (what the run rows hold). */
    private suspend fun run(id: String, p: Pipeline, byFrame: Map<String, CanvasValue.Extraction>, status: String = "DONE") {
        h.dao.insertRun(CanvasRunEntity(id, "p", null, "h", PipelineJson.encode(p), status, byFrame.size, 1, 0, 0, 1, null, "[]"))
        byFrame.forEach { (f, v) -> h.dao.upsertItem(CanvasRunItemEntity(id, f, "ext", "OK", null, CanvasValueJson.encode(v), null, null, null, null, null, null, "$id:ext:$f")) }
    }

    private fun fact(text: String, vararg src: String, type: String = "object") = Fact(text, type, 0.9, listOf("t"), src.toList())
    private fun ext(trusted: Boolean?, facts: List<Fact>, captions: List<FrameCaption> = emptyList()) = CanvasValue.Extraction(facts, captions, trusted)

    private fun rows(sql: String): List<List<String?>> = h.db.openHelper.readableDatabase.query(sql).use { c ->
        val out = ArrayList<List<String?>>(); while (c.moveToNext()) out += (0 until c.columnCount).map { c.getString(it) }; out
    }
    private fun memories() = rows("SELECT id, text, reviewState, tier, tags, derived FROM memory ORDER BY id")

    // ---- playground: nothing is stored ----

    @Test fun aPipelineWithoutAMemoryNodeIsAPlaygroundAndSavingItWritesNothing() = runBlocking {
        frame("f1")
        run("r1", pipeline(withMemory = false), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val s = saver()
        assertEquals(CanvasRunSave.Reason.NO_MEMORY_NODE, s.proposal("r1")!!.reason)
        assertEquals(CanvasRunSave.Result.NotSaveable(CanvasRunSave.Reason.NO_MEMORY_NODE), s.save("r1"))
        assertEquals(emptyList<Any>(), memories())
        assertNull(h.dao3.meta(CanvasSaveMarker.key("r1")))
    }

    @Test fun onlyAFinishedPhotoRunCanBeSaved() = runBlocking {
        frame("f1")
        run("running", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))), status = "RUNNING")
        assertEquals(CanvasRunSave.Result.NotSaveable(CanvasRunSave.Reason.NOT_DONE), saver().save("running"))
        val moments = pipeline(extra = listOf(PipelineNode("m", NodeTypes.INPUT_MOMENTS, "m", JsonObject(emptyMap()))))
        run("moments", moments, mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        assertEquals(CanvasRunSave.Result.NotSaveable(CanvasRunSave.Reason.NOT_PHOTO_RUN), saver().save("moments"))
        assertEquals(emptyList<Any>(), memories())
    }

    @Test fun aProposalIsReadOnlyAndListsWhatASaveWouldWrite() = runBlocking {
        frame("f1"); frame("f2")
        run("r1", pipeline(), mapOf(
            "f1" to ext(true, listOf(fact("a mug", "f1")), listOf(FrameCaption("f1", "a desk"))),
            "f2" to ext(null, listOf(fact("a cup", "f2"))),
        ))
        val p = saver().proposal("r1")!!
        assertTrue(p.saveable); assertNull(p.saved)
        assertEquals(
            // codex r10: a stored run keeps no delivery proof, so its caption is neither proposed nor saved
            listOf(CanvasRunSave.Proposed("f1", "fact", "a mug", "object", "OK"), CanvasRunSave.Proposed("f2", "fact", "a cup", "object", "NEEDS_REVIEW")),
            p.memories,
        )
        assertEquals("a proposal writes nothing", emptyList<Any>(), memories())
        assertNull(saver().proposal("missing"))
    }

    // ---- Run & Save: the real write path ----

    @Test fun saveWritesTheMemoriesThroughTheRealPathWithProvenanceAndTheReviewState() = runBlocking {
        frame("f1"); frame("f2")
        run("r1", pipeline(), mapOf(
            "f1" to ext(true, listOf(fact("a mug", "f1")), listOf(FrameCaption("f1", "a desk"))),
            "f2" to ext(null, listOf(fact("a cup", "f2"))),
        ))
        val r = saver().save("r1")
        assertEquals(CanvasRunSave.Result.Saved(2, 1), r)
        val m = memories().associateBy { it[0] }
        assertEquals("codex r10: no caption without delivery proof", setOf("rokid_cv_r1_0", "rokid_cv_r1_1"), m.keys)
        assertEquals("a mug", m["rokid_cv_r1_0"]!![1]); assertEquals("OK", m["rokid_cv_r1_0"]!![2]); assertEquals("SHORT", m["rokid_cv_r1_0"]!![3])
        assertEquals("a cup", m["rokid_cv_r1_1"]!![1]); assertEquals("NEEDS_REVIEW", m["rokid_cv_r1_1"]!![2])
        assertTrue("tagged as a canvas run", m["rokid_cv_r1_0"]!![4]!!.split(" ").contains("canvas"))
        // frame_source provenance (what makes the memory forgettable with its photo)
        assertEquals(listOf(listOf("rokid_cv_r1_0", "f1"), listOf("rokid_cv_r1_1", "f2")), rows("SELECT memoryId, frameId FROM frame_source ORDER BY memoryId"))
        assertEquals("2", h.dao3.meta(CanvasSaveMarker.key("r1")))
        assertEquals("the follow-up (embeddings) got the saved memories", listOf(2), followUps.map { it.size })
        assertEquals(CanvasRunSave.Result.Saved(3, 1).message, "Saved 3 memories (1 need review in Memory).")
    }

    @Test fun theMemoryNodeNeverWritesDuringTheRun() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val ai = com.rokid.context.orchestration.TestAi.graph(app) { "" }
        val ex = MemoryWritePreviewExecutor.run(pipeline().nodes[2], listOf(CanvasItem("f1", h.photo("f1"), ext(true, listOf(fact("a mug", "f1"))))), RunContext("r1", ai.runner, ai.router, ai.prompts, ai.persona, { null }, liveness = com.rokid.context.orchestration.AllLive))
        assertTrue(ex.single().value is CanvasValue.Preview)
        assertEquals("the preview wrote nothing", emptyList<Any>(), memories())
    }

    @Test fun aSecondSaveOrADoubleClickNeverDuplicatesAndNeverBringsBackAForgottenMemory() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1"), fact("a lamp", "f1")))))
        assertEquals(CanvasRunSave.Result.Saved(2, 0), saver().save("r1"))
        h.db.openHelper.writableDatabase.execSQL("DELETE FROM memory WHERE id = 'rokid_cv_r1_1'") // the user forgot one of them
        assertEquals(CanvasRunSave.Result.AlreadySaved(2), saver().save("r1"))
        assertEquals(listOf("rokid_cv_r1_0"), memories().map { it[0] })
        assertEquals("the follow-up ran once", 1, followUps.size)
    }

    @Test fun twoSavesRacingExactlyOneWins() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        // both passed the cheap pre-check: the marker appears between it and the transaction (what a concurrent save does)
        val racing = saver(generation = { h.dao3.let { d -> runBlocking { d.putMeta(CanvasSaveMarker.key("r1"), "1") } }; MemoryWipe.generation.get() })
        assertEquals(CanvasRunSave.Result.AlreadySaved(1), racing.save("r1"))
        assertEquals("the loser rolled back whole", emptyList<Any>(), memories())
    }

    @Test fun twoConcurrentSavesOfAFreshRunSaveOnce() = runBlocking {
        frame("f1")
        run("r9", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val results = listOf(async { saver().save("r9") }, async { saver().save("r9") }).awaitAll()
        assertEquals(1, results.count { it is CanvasRunSave.Result.Saved })
        assertEquals(1, results.count { it is CanvasRunSave.Result.AlreadySaved })
        assertEquals(1, memories().size)
    }

    @Test fun validMemoriesOnlyAnExpiredOrMissingPhotoIsLeftOut() = runBlocking {
        frame("f1"); frame("gone", live = false)
        run("r1", pipeline(), mapOf(
            "f1" to ext(true, listOf(fact("a mug", "f1")), listOf(FrameCaption("f1", "a desk"))),
            "gone" to ext(true, listOf(fact("a ghost", "gone")), listOf(FrameCaption("gone", "a void"))),
        ))
        assertEquals(CanvasRunSave.Result.Saved(1, 0), saver().save("r1"))
        assertEquals("codex r10: no caption from a stored run", listOf("a mug"), memories().sortedBy { it[0] }.map { it[1] })
        run("r2", pipeline(), mapOf("gone" to ext(true, listOf(fact("a ghost", "gone")))))
        assertEquals(CanvasRunSave.Result.NothingToSave, saver().save("r2"))
        assertNull("nothing saved, so no marker: a later retry is possible", h.dao3.meta(CanvasSaveMarker.key("r2")))
    }

    @Test fun aForgottenPhotoIsNotSavedEvenWhenItsRowsStillExist() = runBlocking {
        frame("f1"); frame("f2")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1"))), "f2" to ext(true, listOf(fact("a cup", "f2")))))
        h.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("f2", 1))
        assertEquals(CanvasRunSave.Result.Saved(1, 0), saver().save("r1"))
        assertEquals(listOf("a mug"), memories().map { it[1] })
    }

    @Test fun aSummaryIsSavedAsADerivedMemoryCitingTheOtherMemoriesOfItsPhoto() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1"), fact("it is a day", "f1", type = "summary")))))
        assertEquals(CanvasRunSave.Result.Saved(2, 0), saver().save("r1"))
        assertEquals(listOf("rokid_cv_r1_1" to "1"), memories().filter { it[5] == "1" }.map { it[0]!! to it[5]!! })
        assertEquals(listOf(listOf("rokid_cv_r1_1", "rokid_cv_r1_0")), rows("SELECT memoryId, sourceMemoryId FROM memory_source"))
    }

    // ---- DataOpGate: bounded wait, typed result naming the reason ----

    @Test fun aRunningExportOrDeleteGivesATypedBusyResultAndNothingIsWritten() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val gate = DataOpGate(waitMs = 100)
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { gate.export { inside.complete(Unit); release.await() } }
        inside.await()
        val byExport = saver(gate).save("r1")
        assertEquals(CanvasRunSave.Result.Busy(byExport = true), byExport)
        assertTrue(byExport.message, byExport.message.contains("export is running") && byExport.message.contains("Try again"))
        release.complete(Unit); exp.await()
        val inside2 = CompletableDeferred<Unit>(); val release2 = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside2.complete(Unit); release2.await() } }
        inside2.await()
        val byDelete = saver(gate).save("r1")
        assertEquals(CanvasRunSave.Result.Busy(byExport = false), byDelete)
        assertTrue(byDelete.message, byDelete.message.contains("delete") && byDelete.message.contains("Try again"))
        assertEquals(emptyList<Any>(), memories()); assertNull(h.dao3.meta(CanvasSaveMarker.key("r1")))
        release2.complete(Unit); del.await()
        assertEquals("after the delete the retry saves", CanvasRunSave.Result.Saved(1, 0), saver(gate).save("r1"))
    }

    @Test fun anAlreadySavedRunAnswersEvenWhileAnExportRuns() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val gate = DataOpGate(waitMs = 100)
        assertEquals(CanvasRunSave.Result.Saved(1, 0), saver(gate).save("r1"))
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = async { gate.export { inside.complete(Unit); release.await() } }
        inside.await()
        assertEquals(CanvasRunSave.Result.AlreadySaved(1), saver(gate).save("r1"))
        release.complete(Unit); exp.await()
    }

    @Test fun deleteAllDuringTheSaveWritesNothingAndSaysSo() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val r = saver(generation = { MemoryWipe.generation.get() - 1 }).save("r1")
        assertEquals(CanvasRunSave.Result.Wiped, r)
        assertTrue(r.message.contains("Delete all"))
        assertEquals(emptyList<Any>(), memories()); assertNull(h.dao3.meta(CanvasSaveMarker.key("r1")))
    }

    /** Codex review 2026-10-26: the wipe generation is read BEFORE anything is loaded, so a Delete all between the load and the commit can never be mistaken for the current state. */
    @Test fun aDeleteAllBetweenTheLoadAndTheSaveWritesNothing() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val s = saver()
        s.testAfterLoad = { MemoryWipe.generation.incrementAndGet() }
        assertEquals(CanvasRunSave.Result.Wiped, s.save("r1"))
        assertEquals(emptyList<Any>(), memories()); assertNull(h.dao3.meta(CanvasSaveMarker.key("r1")))
    }

    @Test fun aRunDeletedAfterTheLoadIsRecheckedInsideTheSaveAndNothingIsWritten() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val s = saver()
        s.testAfterLoad = { h.dao.deleteRun("r1") }
        val r = s.save("r1")
        assertTrue("$r", r is CanvasRunSave.Result.NotSaveable)
        assertEquals(emptyList<Any>(), memories()); assertNull(h.dao3.meta(CanvasSaveMarker.key("r1")))
    }

    @Test fun aSourcePhotoGoneAfterTheLoadIsRecheckedInsideTheSaveAndNothingIsWritten() = runBlocking {
        frame("f1"); frame("f2")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1"))), "f2" to ext(true, listOf(fact("a cup", "f2")))))
        val s = saver()
        s.testAfterLoad = { h.db.frameDao().deleteByIds(listOf("f2")) }
        assertEquals(CanvasRunSave.Result.NothingToSave, s.save("r1"))
        assertEquals("all or nothing: the rolled-back save leaves no memory of f1 either", emptyList<Any>(), memories()); assertNull(h.dao3.meta(CanvasSaveMarker.key("r1")))
        assertEquals("a retry now saves what is valid", CanvasRunSave.Result.Saved(1, 0), saver().save("r1"))
    }

    @Test fun aFailedSaveIsNotSwallowedAndLeavesNothingBehind() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        val err = runCatching { saver(clock = { throw IllegalStateException("boom") }).save("r1") }.exceptionOrNull()
        assertTrue("the failure reaches the caller: $err", err is IllegalStateException)
        assertEquals(emptyList<Any>(), memories()); assertNull(h.dao3.meta(CanvasSaveMarker.key("r1")))
        assertEquals("a retry works", CanvasRunSave.Result.Saved(1, 0), saver().save("r1"))
    }

    @Test fun anUnknownRunIsNotFound() = runBlocking {
        val err = runCatching { saver().save("nope") }.exceptionOrNull()
        assertTrue(err is NoSuchElementException)
    }

    // ---- the marker goes with its run and with Delete all ----

    @Test fun theMarkerIsDeletedWithItsRunAndByDeleteAll() = runBlocking {
        frame("f1")
        run("r1", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        run("r2", pipeline(), mapOf("f1" to ext(true, listOf(fact("a mug", "f1")))))
        saver().save("r1"); saver().save("r2")
        assertNotNull(h.dao3.meta(CanvasSaveMarker.key("r1"))); assertNotNull(h.dao3.meta(CanvasSaveMarker.key("r2")))
        h.dao.deleteRun("r1")
        assertNull(h.dao3.meta(CanvasSaveMarker.key("r1"))); assertNotNull("another run's marker stays", h.dao3.meta(CanvasSaveMarker.key("r2")))
        MemoryWipe.deleteAll(app)
        assertNull(h.dao3.meta(CanvasSaveMarker.key("r2")))
        assertFalse(rows("SELECT key FROM canvas_meta").any { (it[0] ?: "").startsWith(CanvasSaveMarker.PREFIX) })
    }
}
