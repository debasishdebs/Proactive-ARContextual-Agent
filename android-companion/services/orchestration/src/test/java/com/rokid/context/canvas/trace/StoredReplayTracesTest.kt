package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StoredReplayTracesTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val dao = db.canvasSlice3Dao()
    private val store = DeletionEpochStore.of(ctx)
    private val pulled = File(ctx.cacheDir, "pulled_str")
    private val root = File(ctx.filesDir, FencedFiles.TRACES_DIR)
    private fun fence() = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)

    @Before fun clean() { wipe() }
    @After fun close() { db.close(); wipe() }
    private fun wipe() {
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; pulled.deleteRecursively()
    }

    @Test fun aTraceOfAnOlderEpochIsRefusedEvenWithValidRowAndFiles() = runBlocking {
        val dir = PulledTraces.write(pulled, "trace-aaaa-0001", store.read().installId, store.read().counter)
        TraceImport(dao, FencedFiles(db, ctx.filesDir), ::fence) { 5_000L }.import(dir)
        assertNotNull(StoredReplayTraces(dao, root, store::read).load("trace-aaaa-0001"))
        store.commitWipe(9_000L) // a Delete all whose cleanup never ran: row and files are still there
        assertNull(StoredReplayTraces(dao, root, store::read).load("trace-aaaa-0001"))
        assertNotNull("without the epoch check it would load: the guard is what refuses it", StoredReplayTraces(dao, root).load("trace-aaaa-0001"))
    }

    @Test fun anImportedTraceLoadsAndReplaysAndAnUnknownOrTombstonedOneDoesNot() = runBlocking {
        val dir = PulledTraces.write(pulled, "trace-aaaa-0001", store.read().installId, store.read().counter)
        TraceImport(dao, FencedFiles(db, ctx.filesDir), ::fence) { 5_000L }.import(dir)
        val loader = StoredReplayTraces(dao, root)
        val t = loader.load("trace-aaaa-0001")
        assertNotNull(t)
        assertEquals("abc123def456", t!!.planHash); assertEquals(1, t.revision)
        val report = t.replay(null, null) { }
        assertNotNull(report)
        assertEquals("a second pass works (events are re-read)", report.toString(), t.replay(null, null) { }.toString())
        assertNull(loader.load("trace-none-0002")); assertNull(loader.load("../x"))
        dao.tombstoneTrace("trace-aaaa-0001", 9_000L)
        assertNull(loader.load("trace-aaaa-0001"))
    }
}
