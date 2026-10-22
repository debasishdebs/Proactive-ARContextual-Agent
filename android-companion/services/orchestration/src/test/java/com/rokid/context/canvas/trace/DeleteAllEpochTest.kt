package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Delete all and the persisted deletion epoch (S57, S58): the epoch is committed first, and every slice 3a table and folder goes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DeleteAllEpochTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx); com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }
    }
    @After fun tearDown() {
        MemoryWipe.afterEpochCommit = {}
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, true); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }
    }

    private fun put(dir: String, name: String) = File(File(ctx.filesDir, dir), name).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test fun epochCommittedBeforeDelete() = runBlocking {
        val g = MemoryGraph.get(ctx)
        val before = DeletionEpochStore.of(ctx).read()
        val epoch = before.copy(counter = before.counter)
        val fence = DaoFence(0, epoch, { 0 }, { epoch })
        g.db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity("l1", "MOMENT", "tap", "X", before.wipedAtMs + 5, createdAt = 1), fence)
        val kept = put(FencedFiles.TRACES_DIR, "t.bin")
        MemoryWipe.afterEpochCommit = { throw IllegalStateException("crash after the epoch commit") }
        assertThrows(IllegalStateException::class.java) { runBlocking { MemoryWipe.deleteAll(ctx) } }
        // The next start, through a fresh store, sees the new counter and wipe time...
        val after = DeletionEpochStore.of(ctx).read()
        assertEquals(before.installId, after.installId); assertEquals(before.counter + 1, after.counter); assertTrue(after.wipedAtMs > 0)
        // ...and nothing had been deleted yet: the record came first, not last.
        assertNotNull(g.db.canvasSlice3Dao().label("l1")); assertTrue(kept.exists())
    }

    @Test fun deleteAllRemovesFolders() = runBlocking {
        val a = put(FencedFiles.TRACES_DIR, "a.bin"); val b = put(FencedFiles.IMPORT_DIR, "sub/b.bin"); val c = put(FencedFiles.TMP_DIR, "c.tmp")
        val keep = File(ctx.filesDir, "keep_me.txt").apply { writeText("x") }
        MemoryWipe.deleteAll(ctx)
        listOf(a, b, c).forEach { assertFalse(it.path, it.exists()) }
        FencedFiles.DELETE_ALL_DIRS.forEach { assertFalse("$it is gone", File(ctx.filesDir, it).exists()) }
        assertTrue("other files are untouched", keep.exists())
    }

    @Test fun deleteAllRemovesSlice3Rows() = runBlocking {
        val g = MemoryGraph.get(ctx)
        val e = DeletionEpochStore.of(ctx).read()
        val fence = DaoFence(MemoryWipe.generation.get(), e, MemoryWipe.generation::get, { DeletionEpochStore.of(ctx).read() })
        g.db.canvasSlice3Dao().acceptTraceFenced(CanvasTraceEntity("t1", "IMU", "COMPLETE", "{}", 0, installId = e.installId, deletionCounter = e.counter, startedAtMs = 1, createdAt = 1), fence)
        g.db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity("l1", "MOMENT", "tap", "X", 5, createdAt = 1), fence)
        MemoryWipe.deleteAll(ctx)
        assertNull(g.db.canvasSlice3Dao().trace("t1")); assertNull(g.db.canvasSlice3Dao().label("l1"))
    }

    /** After Delete all, a derived commit that captured the OLD epoch (even in a process that never saw the wipe) is refused by the persisted counter. */
    @Test fun aCommitCapturedBeforeDeleteAllIsRefusedAfterIt() = runBlocking {
        val g = MemoryGraph.get(ctx)
        val old = DeletionEpochStore.of(ctx).read()
        val fence = DaoFence(0, old, { 0 }, { DeletionEpochStore.of(ctx).read() }) // the in-process generation never moved: only the persisted epoch can refuse
        MemoryWipe.deleteAll(ctx)
        assertFalse(g.db.canvasSlice3Dao().acceptTraceFenced(CanvasTraceEntity("t2", "IMU", "COMPLETE", "{}", 0, installId = old.installId, deletionCounter = old.counter, startedAtMs = 1, createdAt = 1), fence))
        assertNull(g.db.canvasSlice3Dao().trace("t2"))
    }

    @Test fun theEpochCounterGrowsWithEveryDeleteAll() = runBlocking {
        val start = DeletionEpochStore.of(ctx).read().counter
        MemoryWipe.deleteAll(ctx); MemoryWipe.deleteAll(ctx)
        assertEquals(start + 2, DeletionEpochStore.of(ctx).read().counter)
    }

    /**
     * Delete all racing in-flight fenced commits: commits keep capturing the epoch and committing while a wipe runs on another thread. Whatever the interleaving,
     * no row recorded under a pre-wipe counter survives the wipe, and no commit that captured the old epoch lands after it.
     */
    @Test fun concurrentDeleteAllAgainstInFlightCommits() = runBlocking {
        val g = MemoryGraph.get(ctx)
        val store = DeletionEpochStore.of(ctx)
        val dao = g.db.canvasSlice3Dao()
        val wipe = kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) { MemoryWipe.deleteAll(ctx) }
        val committers = (0 until 4).map { w ->
            kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) {
                repeat(60) { i ->
                    val e = store.read() // captured before the write, as a recorder does
                    val fence = DaoFence(MemoryWipe.generation.get(), e, MemoryWipe.generation::get, store::read)
                    dao.acceptTraceFenced(CanvasTraceEntity("t-$w-$i", "IMU", "COMPLETE", "{}", 0, installId = e.installId, deletionCounter = e.counter, startedAtMs = 1, createdAt = 1), fence)
                }
            }
        }
        wipe.await(); committers.forEach { it.await() }
        val now = store.read()
        assertEquals(1L, now.counter)
        val stale = g.db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM canvas_trace WHERE deletionCounter < ${now.counter}").use { it.moveToFirst(); it.getInt(0) }
        assertEquals("no pre-wipe row survives the wipe", 0, stale)
    }
}
