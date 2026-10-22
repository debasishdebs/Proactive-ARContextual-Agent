package com.rokid.context.canvas.plan

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.trace.CollectSession
import com.rokid.context.canvas.trace.FencedFiles
import com.rokid.context.core.model.TraceControl
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.memory.forget.CanvasSlice3Forget
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The files and glasses half of the deletion graph (S58, P7) and Delete all's reach into every slice 3a artefact class. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CanvasForgetCoordinatorTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val sent = ArrayList<TraceControl>()
    private lateinit var coordinator: CanvasForgetCoordinator
    private lateinit var collect: CollectSession

    @Before fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx); com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
        (MemoryWipe.WIPED_PREFS + DeletionEpochStore.PREFS).forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }
        ForgetListeners.clearForTests()
        collect = CollectSession.of(ctx)
        coordinator = CanvasForgetCoordinator(CanvasSlice3Forget(MemoryGraph.get(ctx).db), ctx.filesDir, collect, send = { sent += it })
    }
    @After fun tearDown() {
        ForgetListeners.clearForTests()
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, true); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }
    }

    private val dao get() = MemoryGraph.get(ctx).db.canvasSlice3Dao()
    private fun fence() = DeletionEpochStore.of(ctx).read().let { e -> DaoFence(0, e, { 0 }, { e }) }
    private suspend fun trace(id: String, from: Long, to: Long): File {
        val e = DeletionEpochStore.of(ctx).read()
        dao.acceptTraceFenced(CanvasTraceEntity(id, "IMU", "COMPLETE", "{}", 0, installId = e.installId, deletionCounter = e.counter, startedAtMs = from, endedAtMs = to, createdAt = 1), fence())
        return File(File(ctx.filesDir, FencedFiles.TRACES_DIR), id).apply { mkdirs(); File(this, "chunk-0000.json").writeText("x") }
    }

    @Test fun rangeForgetRemovesTheSealedFolderAndTellsTheGlassesTheTombstonesAndRange() = runBlocking {
        val inside = trace("trace-in-1", 1_000, 2_000); val outside = trace("trace-out-1", 9_000, 9_500)
        val r = coordinator.forgetRange(1_500, 1_800)
        assertEquals(listOf("trace-in-1"), r.tombstonedTraceIds)
        assertFalse(inside.exists()); assertTrue(outside.exists())
        val c = sent.single()
        assertEquals(listOf("trace-in-1"), c.tombstones); assertEquals(1_500L, c.forgetFromMs); assertEquals(1_800L, c.forgetToMs)
        assertTrue(dao.trace("trace-in-1")!!.tombstoned)
    }

    @Test fun lateChunkForAForgottenTraceIsRejectedAndLeavesNoFile() = runBlocking {
        trace("trace-001", 1_000, 2_000)
        coordinator.forgetRange(0, 10_000)
        val e = DeletionEpochStore.of(ctx).read()
        assertFalse(dao.acceptTraceFenced(CanvasTraceEntity("trace-001", "IMU", "COMPLETE", "{\"x\":1}", 0, installId = e.installId, deletionCounter = e.counter, startedAtMs = 1_000, createdAt = 2), fence()))
        assertFalse(File(File(ctx.filesDir, FencedFiles.TRACES_DIR), "trace-001").exists())
    }

    @Test fun aFailingSendNeverBlocksTheDeletion() = runBlocking {
        val failing = CanvasForgetCoordinator(CanvasSlice3Forget(MemoryGraph.get(ctx).db), ctx.filesDir, collect, send = { error("link down") })
        val f = trace("trace-001", 1, 2)
        failing.forgetRange(0, 10)
        assertFalse(f.exists()); assertTrue(dao.trace("trace-001")!!.tombstoned)
    }

    @Test fun aHostileTraceIdCannotEscapeTheTracesFolder() {
        val victim = File(ctx.filesDir, "keep.txt").apply { writeText("x") }
        assertEquals(0, coordinator.deleteTraceFiles(listOf("../keep.txt", "..", "")))
        assertTrue(victim.exists())
    }

    @Test fun deleteAllReachesEveryArtefactClassAndTheGlassesGetTheNewEpochWithCollectOff() = runBlocking {
        ForgetListeners.register(coordinator)
        val before = DeletionEpochStore.of(ctx).read()
        val traceDir = trace("trace-001", 1, 2)
        val imp = File(ctx.filesDir, FencedFiles.IMPORT_DIR + "/a.bin").apply { parentFile!!.mkdirs(); writeText("x") }
        val tmp = File(ctx.filesDir, FencedFiles.TMP_DIR + "/b.tmp").apply { parentFile!!.mkdirs(); writeText("x") }
        dao.insertLabelFenced(CanvasLabelEntity("l", "MOMENT", "tap", "MOMENT", before.wipedAtMs + 5, createdAt = 1), fence())
        MemoryWipe.PLAN_STATE_PREFS.let { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().putString("state", "plan").commit() }
        collect.start()
        sent.clear()
        MemoryWipe.deleteAll(ctx)
        listOf(traceDir, imp, tmp).forEach { assertFalse(it.path, it.exists()) }
        assertTrue(dao.labels().isEmpty()); assertNull(dao.trace("trace-001"))
        MemoryWipe.WIPED_PREFS.forEach { assertTrue("$it cleared", ctx.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty()) }
        val c = sent.last()
        assertEquals(before.counter + 1, c.deletionCounter); assertEquals(before.installId, c.installId); assertFalse("collect is off", c.collect)
    }

    @Test fun labelsWrittenBeforeTheWipeCannotBeResurrectedByALateWriter() = runBlocking {
        val before = DeletionEpochStore.of(ctx).read()
        val stale = DaoFence(0, before, { 0 }, { DeletionEpochStore.of(ctx).read() })
        MemoryWipe.deleteAll(ctx)
        assertFalse("a writer that began before the wipe is refused (counter moved)", dao.insertLabelFenced(CanvasLabelEntity("late", "MOMENT", "tap", "MOMENT", before.wipedAtMs + 9_000, createdAt = 1), stale))
        val now = DeletionEpochStore.of(ctx).read()
        val fresh = DaoFence(0, now, { 0 }, { now })
        assertFalse("an occurrence before the wipe is refused even by a fresh writer", dao.insertLabelFenced(CanvasLabelEntity("old", "MOMENT", "tap", "MOMENT", now.wipedAtMs - 1, createdAt = 1), fresh))
        assertTrue(dao.labels().isEmpty())
    }

    // --- fix round 1 ---

    @Test fun aForgottenPhotoRemovesTheTraceFolderAroundItThroughTheListenerPath() = runBlocking {
        ForgetListeners.register(coordinator)
        val g = MemoryGraph.get(ctx)
        val photoAt = 800_000L
        g.db.frameDao().insertIgnore(com.rokid.context.memory.db.FrameEntity("ph1", photoAt, "/ph1.jpg", "rokid_glasses"))
        val dir = trace("trace-near-01", photoAt - 2_000, photoAt + 2_000)
        val far = trace("trace-far-001", 9_000_000, 9_001_000)
        val service = com.rokid.context.memory.forget.ForgetService(g.db, com.rokid.context.memory.ProposalRepository(g.db.proposalDao(), g.db.actionDao(), g.db))
        service.forgetMoment("ph1")
        assertFalse("the photo's moment: the sealed trace folder is gone", dir.exists()); assertTrue(far.exists())
        assertTrue("and the glasses were told the tombstone and range", sent.any { "trace-near-01" in it.tombstones })
    }

    @Test fun startupRemovesTraceFoldersWhoseTombstoneIsDurableButWhoseFilesSurvivedACrash() = runBlocking {
        val dir = trace("trace-crash-01", 1_000, 2_000)
        dao.tombstoneTrace("trace-crash-01", 5) // the DB committed, the process died before the file delete
        assertTrue(dir.exists())
        FencedFiles.let { com.rokid.context.canvas.trace.CanvasStartup.run(ctx, MemoryGraph.get(ctx).db) }
        assertFalse(dir.exists())
    }

    @Test fun aMarkRedeliveredAfterARangeForgetIsAckedButNotStored() = runBlocking {
        val rec = com.rokid.context.canvas.marks.MarkRecorder(dao, { fence() }, { 1L })
        val m = com.rokid.context.core.model.MarkMessage(epoch = "e", seq = 1, atMs = 5, atElapsedMs = 5, listen = true)
        val at = com.rokid.context.canvas.marks.MarkArrival(1_500, true, 1_500)
        assertTrue(rec.onMark(m, at)); assertEquals(1, dao.labels().size)
        coordinator.forgetRange(1_000, 2_000)
        assertTrue("acked, so the glasses stop re-sending it", rec.onMark(m, at))
        assertTrue(dao.labels().isEmpty())
        rec.onListenStart(1_800)
        rec.applyVoice(listOf(com.rokid.context.canvas.marks.VoiceMark.Mark("driving")), true, 1_900)
        assertTrue("a delayed voice command for the forgotten moment stores nothing", dao.labels().isEmpty())
    }

    @Test fun anUnimportedTraceOverlappingAForgottenRangeCannotImportLater() = runBlocking {
        val e = DeletionEpochStore.of(ctx).read()
        val pulled = File(ctx.cacheDir, "pulled_forget").apply { deleteRecursively() }
        val d = com.rokid.context.canvas.trace.PulledTraces.write(pulled, "trace-late-001", e.installId, e.counter) // recorded at wall 1_700_000_001_000..012_010
        coordinator.forgetRange(1_700_000_000_000, 1_700_000_005_000)
        val r = com.rokid.context.canvas.trace.TraceImport(dao, FencedFiles(MemoryGraph.get(ctx).db, ctx.filesDir), { DaoFence(MemoryWipe.generation.get(), DeletionEpochStore.of(ctx).read(), MemoryWipe.generation::get, DeletionEpochStore.of(ctx)::read) }) { 5_000L }.import(d)
        assertTrue(r.toString(), r is com.rokid.context.canvas.trace.TraceImportResult.Rejected)
        assertFalse(File(File(ctx.filesDir, FencedFiles.TRACES_DIR), "trace-late-001").exists())
        pulled.deleteRecursively(); Unit
    }
}
