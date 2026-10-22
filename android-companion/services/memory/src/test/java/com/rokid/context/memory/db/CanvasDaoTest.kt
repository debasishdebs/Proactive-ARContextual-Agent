package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.canvasDao()
    @After fun close() = db.close()

    private fun pipe(id: String, builtIn: Boolean = false, rev: Int = 0, at: Long = 1) = CanvasPipelineEntity(id, id, "{}", rev, builtIn, at, at)
    private fun run(id: String, status: String = "DONE", at: Long = 1, hash: String = "h") =
        CanvasRunEntity(id, "p", null, hash, "{}", status, 1, 1, 0, 0, at, null, "[]")
    private fun item(run: String, frame: String, node: String = "n", status: String = "OK") =
        CanvasRunItemEntity(run, frame, node, status, null, "{}", null, null, null, null, null, null, null)
    private fun score(run: String, frame: String, node: String = "n") = CanvasScoreEntity(run, frame, node, 1, "", 1)

    @Test fun saveDraftCasOnRev() = runBlocking {
        dao.upsertPipeline(pipe("p", rev = 2))
        assertEquals(0, dao.saveDraft("p", "{\"a\":1}", 1, 5))
        assertEquals(1, dao.saveDraft("p", "{\"a\":1}", 2, 5))
        val p = dao.pipeline("p")!!
        assertEquals(3, p.draftRev); assertEquals("{\"a\":1}", p.draftJson); assertEquals(5L, p.updatedAt)
        assertEquals(0, dao.saveDraft("p", "{}", 2, 6))
    }

    @Test fun builtInIsReadOnly() = runBlocking {
        dao.upsertPipeline(pipe("b", builtIn = true))
        assertEquals(0, dao.saveDraft("b", "{}", 0, 5))
        assertEquals(0, dao.deletePipeline("b"))
        assertNotNull(dao.pipeline("b"))
        dao.upsertPipeline(pipe("u"))
        assertEquals(1, dao.deletePipeline("u"))
        assertNull(dao.pipeline("u"))
        // built-ins list first
        dao.upsertPipeline(pipe("u2", at = 99))
        assertEquals(listOf("b", "u2"), dao.pipelines().map { it.id })
    }

    @Test fun versionsNewestFirst() = runBlocking {
        listOf(1, 3, 2).forEach { dao.insertVersion(CanvasPipelineVersionEntity("p", it, "{}", "h$it", "", 1)) }
        assertEquals(listOf(3, 2, 1), dao.versions("p").map { it.version })
        assertEquals("h2", dao.version("p", 2)!!.hash)
        assertNull(dao.version("p", 9))
    }

    @Test fun interruptRunningMarksOnlyRunning() = runBlocking {
        dao.insertRun(run("a", "RUNNING")); dao.insertRun(run("b", "DONE")); dao.insertRun(run("c", "RUNNING"))
        assertEquals(2, dao.interruptRunning(77))
        assertEquals("INTERRUPTED", dao.run("a")!!.status); assertEquals(77L, dao.run("a")!!.finishedAt)
        assertEquals("DONE", dao.run("b")!!.status); assertNull(dao.run("b")!!.finishedAt)
        dao.updateRun("b", "FAILED", 4, 2, 9)
        assertEquals(4, dao.run("b")!!.calls)
    }

    @Test fun pruneKeepsNewest20() = runBlocking {
        (1..23).forEach { i -> dao.insertRun(run("r$i", at = i.toLong())); dao.upsertItem(item("r$i", "f")); dao.upsertScore(score("r$i", "f")) }
        dao.pruneRuns(20)
        val kept = dao.runs(100).map { it.runId }
        assertEquals(20, kept.size)
        assertEquals("r23", kept.first()); assertEquals("r4", kept.last())
        listOf("r1", "r2", "r3").forEach { assertNull(it, dao.run(it)); assertEquals(0, dao.items(it).size); assertEquals(0, dao.scores(it).size) }
        assertEquals(1, dao.items("r4").size); assertEquals(1, dao.scores("r23").size)
    }

    @Test fun testedNodeCountOnlyDoneOk() = runBlocking {
        dao.insertRun(run("done", "DONE", hash = "H")); dao.insertRun(run("run", "RUNNING", hash = "H")); dao.insertRun(run("other", "DONE", hash = "X"))
        dao.upsertItem(item("done", "f1", "a", "OK")); dao.upsertItem(item("done", "f2", "a", "OK"))
        dao.upsertItem(item("done", "f1", "b", "ERROR"))
        dao.upsertItem(item("run", "f1", "c", "OK"))
        dao.upsertItem(item("other", "f1", "d", "OK"))
        assertEquals(1, dao.testedNodeCount("H", listOf("a", "b", "c", "d")))
        dao.upsertItem(item("done", "f2", "b", "OK"))
        assertEquals(2, dao.testedNodeCount("H", listOf("a", "b", "c", "d")))
        assertEquals(1, dao.testedNodeCount("H", listOf("a")))
    }

    @Test fun activePublishesNewestFirstAndRollback() = runBlocking {
        val id1 = dao.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 1, hash = "h", publishedAt = 1, rolledBackAt = null, snapshotJson = "{}"))
        val id2 = dao.insertPublish(CanvasPublishEntity(pipelineId = "p", version = 2, hash = "h", publishedAt = 2, rolledBackAt = null, snapshotJson = "{}"))
        assertEquals(listOf(id2, id1), dao.activePublishes().map { it.id })
        assertEquals(1, dao.markRolledBack(id2, 9))
        assertEquals(0, dao.markRolledBack(id2, 10))
        assertEquals(listOf(id1), dao.activePublishes().map { it.id })
    }

    @Test fun recentFramesSkipsTombstonedAndFiltersDwell() = runBlocking {
        listOf("f1" to 10L, "f2" to 20L, "f3" to 30L, "f4" to 40L).forEach { (id, ts) -> db.frameDao().insertIgnore(FrameEntity(id, ts, "/$id.jpg", "rokid_glasses")) }
        fun meta(id: String, dwell: Boolean) = FrameMetaEntity(id, "w$id", "s", "STILL", dwell, 0.5f, null, null, 640, null, null, null, null, null, 1)
        db.frameMetaDao().upsert(meta("f1", true)); db.frameMetaDao().upsert(meta("f2", false)); db.frameMetaDao().upsert(meta("f3", true))
        db.frameStateDao().insertIgnore(listOf(FrameStateEntity("f1", "EXTRACTED", 0, null, null, null, 1)))
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f3", 1))
        val all = dao.recentFrames(100, false, 10)
        assertEquals(listOf("f4", "f2", "f1"), all.map { it.id })
        assertEquals("EXTRACTED", all.last().state); assertEquals(true, all.last().dwell); assertNull(all.first().motion)
        assertEquals(listOf("f1"), dao.recentFrames(100, true, 10).map { it.id })
        assertEquals(listOf("f2", "f1"), dao.recentFrames(30, false, 10).map { it.id })
        assertEquals(1, dao.recentFrames(100, false, 1).size)
    }

    @Test fun liveMemoriesViaFrameIdAndFrameSource() = runBlocking {
        fun mem(id: String, frame: String?, at: Long) = MemoryEntity(id, frame, "t$id", "FACT", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, at, false, "t$id")
        db.memoryDao().insertIgnore(mem("m1", "f1", 2)); db.memoryDao().insertIgnore(mem("m2", null, 1)); db.memoryDao().insertIgnore(mem("m3", "f9", 3))
        db.frameSourceDao().insertAll(listOf(FrameSourceEntity("m2", "f1", null, "FACT")))
        assertEquals(listOf("m2", "m1"), dao.liveMemories("f1").map { it.id })
        assertEquals("tm2", dao.liveMemories("f1").first().text)
    }

    // ---- Room v6 ----
    private fun trace(publish: Long, frame: String, node: String = "_batch", status: String = "OK", at: Long = 1, batch: String? = null, mode: String = "LIVE") =
        CanvasLiveTraceEntity(publish, frame, node, mode, status, null, "{}", null, batch, at)
    private fun pub(kind: String = "interpreter", mode: String? = "LIVE", v: Int = 1) =
        CanvasPublishEntity(pipelineId = "p", version = v, hash = "h", publishedAt = v.toLong(), rolledBackAt = null, snapshotJson = "{}", kind = kind, mode = mode)

    @Test fun activeInterpreterNewestNotRolledBack() = runBlocking {
        val prompts = dao.insertPublish(pub("prompts", null, 1))
        val i1 = dao.insertPublish(pub(v = 2)); val i2 = dao.insertPublish(pub(v = 3))
        assertEquals(i2, dao.activeInterpreter()!!.id)
        dao.markRolledBack(i2, 5)
        assertEquals(i1, dao.activeInterpreter()!!.id)
        dao.markRolledBack(i1, 6)
        assertNull(dao.activeInterpreter())
        assertEquals(prompts, dao.activePublishes().single().id)
    }

    @Test fun rollbackReasonStored() = runBlocking {
        val id = dao.insertPublish(pub())
        assertEquals(1, dao.markRolledBackWithReason(id, 9, "fallbacks"))
        assertEquals(0, dao.markRolledBackWithReason(id, 10, "other"))
        val row = dao.publishHistory(10).single()
        assertEquals("fallbacks", row.rollbackReason); assertEquals(9L, row.rolledBackAt); assertEquals("interpreter", row.kind); assertEquals("LIVE", row.mode)
    }

    @Test fun recentFallbacksWindow() = runBlocking {
        val rows = (1..35).map { i -> trace(1, "f$i", at = i.toLong(), status = if (i <= 5 || i % 5 == 0 || i % 5 == 1) "ERROR" else "OK") }
        // the 30 newest (6..35) hold exactly 12 errors; the 5 oldest are errors too but fall outside the window
        val newest = rows.filter { it.at > 5 }
        assertEquals(12, newest.count { it.status == "ERROR" })
        dao.upsertTrace(rows + trace(1, "f1", node = "n1", status = "ERROR", at = 99) + trace(2, "x", status = "ERROR", at = 100))
        assertEquals(12, dao.recentFallbacks(1, 30))
        assertEquals(17, dao.recentFallbacks(1, 35))
        assertEquals(35, dao.interpretedCount(1))
    }

    @Test fun traceFramesNewestFirstDistinctPerPublish() = runBlocking {
        dao.upsertTrace(listOf(
            trace(1, "a", at = 10), trace(1, "a", node = "n1", at = 11), trace(1, "b", at = 20), trace(1, "c", at = 5), trace(2, "z", at = 99),
        ))
        assertEquals(listOf("b", "a", "c"), dao.traceFrames(1, 10))
        assertEquals(listOf("b", "a"), dao.traceFrames(1, 2))
        assertEquals(listOf("z"), dao.traceFrames(2, 10))
        val rows = dao.tracesOfFrames(1, listOf("a", "b"))
        assertEquals(listOf("a", "a", "b"), rows.map { it.frameId })
        assertTrue(dao.tracesOfFrames(1, listOf("z")).isEmpty()) // another publish's frame
    }

    @Test fun pruneTracesByAgeAndCount() = runBlocking {
        dao.upsertTrace((1..10).map { trace(1, "f$it", at = it.toLong()) })
        assertEquals(3, dao.pruneTraces(4, 100))
        assertEquals(7, dao.traces(1, 100).size)
        assertEquals(4, dao.pruneTraces(0, 3))
        assertEquals(listOf(10L, 9L, 8L), dao.traces(1, 100).map { it.at })
    }

    @Test fun maintenancePrunesAtMostHourly() = runBlocking {
        var now = 10L * 24 * 3600 * 1000
        val m = CanvasMaintenance(dao) { now }
        dao.upsertTrace(listOf(trace(1, "old", at = 1), trace(1, "new", at = now)))
        assertEquals(1, m.pruneTraces())
        dao.upsertTrace(listOf(trace(1, "old2", at = 2)))
        now += 59 * 60 * 1000; assertEquals(-1, m.pruneTraces()); assertEquals(2, dao.traces(1, 10).size)
        now += 2 * 60 * 1000; assertEquals(1, m.pruneTraces()); assertEquals(1, dao.traces(1, 10).size)
    }

    @Test fun runsInGroupOrdered() = runBlocking {
        dao.insertRun(run("b", at = 20).copy(abGroup = "g")); dao.insertRun(run("a", at = 10).copy(abGroup = "g")); dao.insertRun(run("c", at = 5).copy(abGroup = "other")); dao.insertRun(run("d", at = 1))
        assertEquals(listOf("a", "b"), dao.runsInGroup("g").map { it.runId })
    }

    @Test fun tokenMeansOnlyOk() = runBlocking {
        dao.upsertItem(item("r", "f1", "n1").copy(promptTokens = 100, completionTokens = 10, calls = 1))
        dao.upsertItem(item("r", "f2", "n1").copy(promptTokens = 200, completionTokens = 30, calls = 1))
        dao.upsertItem(item("r", "f3", "n1", status = "ERROR").copy(promptTokens = 9999, completionTokens = 9999, calls = 1))
        dao.upsertItem(item("r", "f4", "n1")) // no usage recorded
        dao.upsertItem(item("r", "f5", "other").copy(promptTokens = 5000, completionTokens = 5000, calls = 1))
        val m = dao.tokenMeans(listOf("n1"), 10)
        assertEquals(2, m.n); assertEquals(150.0, m.p!!, 0.0); assertEquals(20.0, m.c!!, 0.0)
        assertEquals(1, dao.tokenMeans(listOf("n1"), 1).n)
        assertEquals(0, dao.tokenMeans(listOf("none"), 10).n); assertNull(dao.tokenMeans(listOf("none"), 10).p)
    }

    /** S32: a mean per provider CALL (sum of tokens / sum of calls over the owner rows), not per item. */
    @Test fun tokenMeansPerCallNotPerItem() = runBlocking {
        dao.upsertItem(item("r", "f1", "n1").copy(promptTokens = 600, completionTokens = 30, calls = 3)) // owner of a 3-call item
        dao.upsertItem(item("r", "f2", "n1").copy(promptTokens = 100, completionTokens = 0, calls = 1))
        dao.upsertItem(item("r", "f3", "n1")) // batch-mate: not an owner, no tokens, no calls
        val m = dao.tokenMeans(listOf("n1"), 10)
        assertEquals(2, m.n)
        assertEquals(700.0 / 4, m.p!!, 1e-9)
        assertEquals(30.0 / 4, m.c!!, 1e-9)
    }

    @Test fun tokenMeansIgnoresRowsWithoutCalls() = runBlocking {
        dao.upsertItem(item("r", "f1", "n1").copy(promptTokens = 100, completionTokens = 10)) // legacy row: tokens but calls unknown
        assertEquals(0, dao.tokenMeans(listOf("n1"), 10).n)
    }

    /** S31 recovery: the QUEUED and RUNNING rows of a group are CANCELLED; plain runs are not touched here. */
    @Test fun cancelOpenGroupRuns() = runBlocking {
        dao.insertRun(run("a", "RUNNING", at = 1).copy(abGroup = "g")); dao.insertRun(run("b", "QUEUED", at = 2).copy(abGroup = "g"))
        dao.insertRun(run("c", "DONE", at = 3).copy(abGroup = "g")); dao.insertRun(run("d", "RUNNING", at = 4))
        assertEquals(2, dao.cancelOpenGroupRuns(99))
        assertEquals(listOf("CANCELLED", "CANCELLED", "DONE", "RUNNING"), listOf("a", "b", "c", "d").map { dao.run(it)!!.status })
        assertEquals(99L, dao.run("b")!!.finishedAt)
    }

    @Test fun askByIdAndLiveMemoryCount() = runBlocking {
        db.askDao().upsert(AskEntity("a1", 5, "q", "a", "[]", null, "phone_typed", false))
        assertEquals("q", dao.ask("a1")!!.question); assertNull(dao.ask("nope"))
        db.memoryDao().insert(MemoryEntity("m1", null, "x", "note", "", MemoryTier.SHORT, 0.9, ReviewState.OK, false, 1, false, ""))
        db.memoryDao().insert(MemoryEntity("m2", null, "y", "note", "", MemoryTier.SHORT, 0.9, ReviewState.INVALIDATED, false, 1, false, ""))
        assertEquals(1, dao.liveMemoryCount(listOf("m1", "m2", "gone"))); assertEquals(0, dao.liveMemoryCount(emptyList()))
    }

    @Test fun recentFramesBetweenBounds() = runBlocking {
        listOf("f1" to 10L, "f2" to 20L, "f3" to 30L, "f4" to 40L).forEach { (id, ts) -> db.frameDao().insertIgnore(FrameEntity(id, ts, "/$id.jpg", "rokid_glasses")) }
        assertEquals(listOf("f3", "f2"), dao.recentFramesBetween(20, 40, false, 10).map { it.id })
        assertEquals(listOf("f2"), dao.recentFramesBetween(20, 30, false, 10).map { it.id })
        assertEquals(listOf("f4"), dao.recentFramesBetween(0, 100, false, 1).map { it.id })
    }

    @Test fun pruneRunsTiebreak() = runBlocking {
        listOf("a", "b", "c").forEach { dao.insertRun(run(it, at = 5)) }
        dao.pruneRuns(2)
        assertEquals(listOf("c", "b"), dao.runs(10).map { it.runId }.sortedDescending())
        assertNull(dao.run("a"))
    }

    @Test fun recentAsksNewestFirst() = runBlocking {
        listOf(1L, 3L, 2L).forEach { db.askDao().upsert(AskEntity("a$it", it, "q", "a", "[]", null, "app", false)) }
        assertEquals(listOf("a3", "a2"), dao.recentAsks(2).map { it.id })
    }

    // ---- S24 fences ----
    @Test fun fencedTraceCommitsWhenFenceHolds() = runBlocking {
        assertTrue(dao.commitTracesFenced(listOf(trace(1, "f1")), 7, setOf("f1")) { 7 })
        assertEquals(1, dao.traces(1, 10).size)
    }

    @Test fun fencedTraceDroppedAfterWipe() = runBlocking {
        assertFalse(dao.commitTracesFenced(listOf(trace(1, "f1")), 7, setOf("f1")) { 8 })
        assertEquals(0, dao.traces(1, 10).size)
    }

    @Test fun fencedTraceDroppedForTombstone() = runBlocking {
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f2", 1))
        assertFalse(dao.commitTracesFenced(listOf(trace(1, "f1"), trace(1, "f2")), 7, setOf("f1", "f2")) { 7 })
        assertEquals("nothing written, f1 included", 0, dao.traces(1, 10).size)
    }

    @Test fun fencedItemsDroppedAfterWipe() = runBlocking {
        assertFalse(dao.insertRunItemsFenced(listOf(item("r", "f1")), listOf(score("r", "f1")), 7, setOf("f1")) { 9 })
        assertEquals(0, dao.items("r").size); assertEquals(0, dao.scores("r").size)
        assertTrue(dao.insertRunItemsFenced(listOf(item("r", "f1")), listOf(score("r", "f1")), 7, setOf("f1")) { 7 })
        assertEquals(1, dao.items("r").size); assertEquals(1, dao.scores("r").size)
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f3", 1))
        assertFalse(dao.insertRunItemsFenced(listOf(item("r", "f3")), emptyList(), 7, setOf("f3")) { 7 })
        assertEquals(1, dao.items("r").size)
    }

    @Test fun fenceAlsoChecksRowFrameIds() = runBlocking {
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f2", 1))
        assertFalse(dao.commitTracesFenced(listOf(trace(1, "f2")), 7, emptySet()) { 7 })
        assertFalse(dao.insertRunItemsFenced(listOf(item("r", "f2")), emptyList(), 7, emptySet()) { 7 })
        assertEquals(0, dao.traces(1, 10).size); assertEquals(0, dao.items("r").size)
    }

    @Test fun scoreIsWrittenOnlyForALiveItemInsideTheSameTransaction() = runBlocking {
        dao.insertRun(run("r")); dao.upsertItem(item("r", "f1"))
        assertTrue(dao.upsertScoreFenced(score("r", "f1"), 7) { 7 })
        assertEquals(1, dao.scores("r").size)
        assertFalse("wipe moved", dao.upsertScoreFenced(score("r", "f1").copy(score = -1, note = "secret"), 7) { 8 })
        assertEquals(1, dao.scores("r")[0].score)
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1))
        assertFalse("tombstoned", dao.upsertScoreFenced(score("r", "f1"), 7) { 7 })
        assertFalse("the item is gone (Forget/Delete all deleted it): the note must not recreate an orphan", dao.upsertScoreFenced(score("r", "gone"), 7) { 7 })
        assertEquals(0, dao.scores("r").count { it.frameId == "gone" })
    }
}
