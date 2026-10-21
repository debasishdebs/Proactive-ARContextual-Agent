package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
}
