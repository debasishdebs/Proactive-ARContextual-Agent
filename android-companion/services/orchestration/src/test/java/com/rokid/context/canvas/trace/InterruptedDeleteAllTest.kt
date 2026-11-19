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

/** S57: a Delete all that committed its epoch and then died is finished at the next start, and nothing recorded before it can be read in the meantime. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InterruptedDeleteAllTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx); com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        MemoryWipe.WIPED_PREFS.forEach { ctx.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }
    }
    @After fun tearDown() {
        MemoryWipe.afterEpochCommit = {}
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, true); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }
    }
    private val dao get() = MemoryGraph.get(ctx).db.canvasSlice3Dao()
    private fun epochStore() = DeletionEpochStore.of(ctx)

    private suspend fun seed(): File {
        val e = epochStore().read()
        val f = DaoFence(0, e, { 0 }, { e })
        dao.acceptTraceFenced(CanvasTraceEntity("trace-0001", "IMU", "COMPLETE", "{}", 0, installId = e.installId, deletionCounter = e.counter, startedAtMs = 1, createdAt = 1), f)
        dao.insertLabelFenced(CanvasLabelEntity("l", "MOMENT", "tap", "MOMENT", e.wipedAtMs + 5, createdAt = 1), f)
        return File(File(ctx.filesDir, FencedFiles.TRACES_DIR), "trace-0001").apply { mkdirs(); File(this, "chunk-0000.json").writeText("x") }
    }

    @Test fun theMarkerIsWrittenWithTheEpochAndSurvivesACrashBeforeCleanup() = runBlocking {
        val dir = seed()
        MemoryWipe.afterEpochCommit = { throw IllegalStateException("crash") }
        assertThrows(IllegalStateException::class.java) { runBlocking { MemoryWipe.deleteAll(ctx) } }
        assertTrue("pending after the crash", epochStore().wipePending())
        assertTrue(dir.exists()); assertNotNull(dao.trace("trace-0001"))
    }

    @Test fun startupFinishesTheInterruptedWipeAndClearsTheMarker() = runBlocking {
        val dir = seed()
        MemoryWipe.afterEpochCommit = { throw IllegalStateException("crash") }
        assertThrows(IllegalStateException::class.java) { runBlocking { MemoryWipe.deleteAll(ctx) } }
        MemoryWipe.afterEpochCommit = {}
        CanvasStartup.run(ctx, MemoryGraph.get(ctx).db)
        assertFalse(dir.exists()); assertNull(dao.trace("trace-0001")); assertTrue(dao.labels().isEmpty())
        assertFalse("marker cleared after a complete cleanup", epochStore().wipePending())
        assertFalse("nothing pending: a second start does nothing", MemoryWipe.resumeIfPending(ctx))
    }

    @Test fun aResumedWipeClearsTheStreetMapSwitchConsentAndTileCache() = runBlocking {
        val prefs = ctx.getSharedPreferences(MemoryWipe.ONLINE_MAP_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", true).putBoolean("consented", true).commit()
        val tiles = File(File(ctx.cacheDir, MemoryWipe.ONLINE_TILES_DIR), "maptiler").apply { mkdirs(); File(this, "15_1_2.tile").writeText("x") }
        MemoryWipe.afterEpochCommit = { throw IllegalStateException("crash") }
        assertThrows(IllegalStateException::class.java) { runBlocking { MemoryWipe.deleteAll(ctx) } }
        assertTrue("nothing deleted yet", tiles.exists() && prefs.getBoolean("enabled", false))
        MemoryWipe.afterEpochCommit = {}
        assertTrue(MemoryWipe.resumeIfPending(ctx))
        assertFalse(prefs.getBoolean("enabled", false)); assertFalse(prefs.getBoolean("consented", false))
        assertFalse("the tile cache is gone", File(ctx.cacheDir, MemoryWipe.ONLINE_TILES_DIR).exists())
        assertFalse(epochStore().wipePending())
    }

    @Test fun aNormalDeleteAllClearsTheStreetMapToo() = runBlocking {
        ctx.getSharedPreferences(MemoryWipe.ONLINE_MAP_PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        File(File(ctx.cacheDir, MemoryWipe.ONLINE_TILES_DIR), "osm").apply { mkdirs(); File(this, "1_0_0.tile").writeText("x") }
        MemoryWipe.deleteAll(ctx)
        assertFalse(ctx.getSharedPreferences(MemoryWipe.ONLINE_MAP_PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false))
        assertFalse(File(ctx.cacheDir, MemoryWipe.ONLINE_TILES_DIR).exists())
    }

    private fun staged(): File = File(File(ctx.getExternalFilesDir(null)!!, FencedFiles.STAGING_DIR), "trace-aaaa-0001").apply { mkdirs(); File(this, "chunk-0000.json").writeText("raw sensor data") }

    @Test fun deleteAllClearsTheExternalTraceStagingFolder() = runBlocking {
        val dir = staged()
        MemoryWipe.deleteAll(ctx)
        assertFalse("the staged raw trace is gone", dir.exists()); assertFalse(epochStore().wipePending())
    }

    @Test fun theInterruptedWipeResumeClearsTheExternalTraceStagingFolderToo() = runBlocking {
        val dir = staged()
        MemoryWipe.afterEpochCommit = { throw IllegalStateException("crash") }
        assertThrows(IllegalStateException::class.java) { runBlocking { MemoryWipe.deleteAll(ctx) } }
        assertTrue(dir.exists())
        MemoryWipe.afterEpochCommit = {}
        MemoryWipe.resumeIfPending(ctx)
        assertFalse(dir.exists()); assertFalse(epochStore().wipePending())
    }

    @Test fun aCompleteDeleteAllLeavesNoMarker() = runBlocking {
        seed()
        MemoryWipe.deleteAll(ctx)
        assertFalse(epochStore().wipePending())
    }

    @Test fun aDeletionThatFailsLeavesTheMarkerSoTheNextStartRetries() = runBlocking {
        val dir = seed()
        // A plain FILE where the traces folder should be a directory tree is deletable; model a failure with an undeletable parent: read-only dir
        dir.setWritable(false); File(ctx.filesDir, FencedFiles.TRACES_DIR).setWritable(false)
        try {
            MemoryWipe.deleteAll(ctx)
            if (dir.exists()) assertTrue("a leftover keeps the marker", epochStore().wipePending())
        } finally { File(ctx.filesDir, FencedFiles.TRACES_DIR).setWritable(true); dir.setWritable(true) }
        MemoryWipe.resumeIfPending(ctx)
        assertFalse(dir.exists()); assertFalse(epochStore().wipePending())
    }

    @Test fun anOldEpochTraceIsNotLoadableForReplayEvenIfItsRowIsStillThere() = runBlocking {
        val before = epochStore().read()
        seed()
        MemoryWipe.afterEpochCommit = { throw IllegalStateException("crash") }
        assertThrows(IllegalStateException::class.java) { runBlocking { MemoryWipe.deleteAll(ctx) } }
        val now = epochStore().read()
        assertEquals(before.counter + 1, now.counter)
        val traces = StoredReplayTraces(dao, File(ctx.filesDir, FencedFiles.TRACES_DIR), epochStore()::read)
        assertNull("row and files are still there but belong to the old epoch", traces.load("trace-0001"))
    }
}
