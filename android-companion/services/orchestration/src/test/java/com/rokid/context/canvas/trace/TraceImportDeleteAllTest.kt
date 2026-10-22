package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Delete all removes an imported trace (its row and its folder), and a pulled copy recorded before it is then refused. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TraceImportDeleteAllTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val pulled = File(ctx.cacheDir, "pulled-da")
    @Before fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx); com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; pulled.deleteRecursively()
    }
    @After fun tearDown() {
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, true); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; pulled.deleteRecursively()
    }

    @Test fun deleteAllRemovesAnImportedTraceAndRefusesTheStaleCopy() = runBlocking {
        val g = MemoryGraph.get(ctx); val store = DeletionEpochStore.of(ctx)
        val fence = { DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read) }
        val imp = TraceImport(g.db.canvasSlice3Dao(), FencedFiles(g.db, ctx.filesDir), fence)
        val e = store.read()
        val dir = PulledTraces.write(pulled, "trace-aaaa-0001", e.installId, e.counter)
        assertTrue(imp.import(dir) is TraceImportResult.Imported)
        val folder = File(File(ctx.filesDir, FencedFiles.TRACES_DIR), "trace-aaaa-0001")
        assertTrue(folder.exists()); assertNotNull(g.db.canvasSlice3Dao().trace("trace-aaaa-0001"))

        MemoryWipe.deleteAll(ctx)
        assertFalse("the imported folder is gone", folder.exists()); assertNull(g.db.canvasSlice3Dao().trace("trace-aaaa-0001"))

        val again = imp.import(dir) // the same pulled copy: its counter is now older than the phone's
        assertEquals("old_epoch", (again as TraceImportResult.Rejected).code)
        assertFalse(folder.exists())
    }

    @Test fun deleteAllClearsPlanStateAndCollectPrefs() = runBlocking {
        ctx.getSharedPreferences("canvas_plan_state", Context.MODE_PRIVATE).edit().putLong("gen", 7).putString("msg", "x").commit()
        ctx.getSharedPreferences(CollectSession.PREFS, Context.MODE_PRIVATE).edit().putLong("until", 9).commit()
        MemoryWipe.deleteAll(ctx)
        // the plan state loses everything but the generation counter (the glasses keep theirs, so the phone must keep counting)
        assertEquals(mapOf<String, Any?>("gen" to 7L), ctx.getSharedPreferences("canvas_plan_state", Context.MODE_PRIVATE).all)
        assertTrue(ctx.getSharedPreferences(CollectSession.PREFS, Context.MODE_PRIVATE).all.isEmpty())
    }
}
