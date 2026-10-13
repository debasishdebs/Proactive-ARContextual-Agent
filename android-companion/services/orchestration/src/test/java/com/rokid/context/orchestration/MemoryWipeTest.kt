package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.contract.PromptKey
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.SummaryTraceEntity
import com.rokid.context.memory.legacy.LegacyImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MemoryWipeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    /** R20F1-1 isolation: an empty queue and a paused pipeline, so no resumed worker writes during the test. */
    @Before fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx); com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
    }
    @After fun tearDown() { com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, true); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun deleteAllRemovesMemoryButKeepsSettingsAndSearchStillWorks() = runBlocking {
        val g = MemoryGraph.get(ctx)
        val ai = TestAi.graph(ctx) { "{}" }; TestAi.bindAll(ai); ai.prompts.ensureSeeded()
        val jpg = File(ctx.filesDir, "rokid_archive/f1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        g.frames.add(FrameEntity("f1", 1, jpg.absolutePath, "rokid_glasses"))
        g.memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 1, "Milk carton"))
        g.asks.add(AskEntity("a1", 1, "q", "a", "[\"m1\"]", 1, "phone_typed", false))
        g.traces.upsert(SummaryTraceEntity("s1", "TRUSTED", 1, "ok"))
        val s = g.spaces.startBuilding("fp", "{}", 2, "[]", 1); g.spaces.put("m1", s.id, floatArrayOf(1f, 0f))

        assertEquals(1, MemoryWipe.deleteAll(ctx))
        assertFalse(jpg.exists())
        assertEquals(0, g.db.memoryDao().count()); assertEquals(0, g.db.frameDao().count())
        assertTrue(g.asks.recent(10).first().isEmpty()); assertTrue(g.traces.recent(10).isEmpty())
        assertTrue(g.db.embeddingDao().vectors(s.id).isEmpty())
        assertEquals("Azure", ai.endpoints.byId("az")!!.name) // settings survive
        assertEquals(1, ai.prompts.active(PromptKey.ANSWER).version)
        g.memories.add(LegacyImporter.toMemory("m2", "short_term", 0.9, 2, "Keys on the shelf"))
        assertEquals(listOf("m2"), g.memories.search("keys", 5).map { it.id }) // FTS rebuilt after the vacuum
    }

    /** Final-review I2: cancelling the caller (the Memory screen's ViewModel) right after Delete all starts never stops it part-way. */
    @Test fun cancellingTheCallerRightAfterTheStartStillFinishesTheWipe() = runBlocking<Unit> {
        ctx.deleteDatabase(com.rokid.context.memory.db.DatabaseFactory.NAME)
        val fileDb = com.rokid.context.memory.db.DatabaseFactory.buildDeferred(ctx, { false }) { ByteArray(0) }
        try {
            val repo = com.rokid.context.memory.MemoryRepository(fileDb.memoryDao())
            repeat(20) { repo.add(LegacyImporter.toMemory("m$it", "short_term", 0.9, 1L + it, "Milk carton $it")) }
            val wal = File(ctx.getDatabasePath(com.rokid.context.memory.db.DatabaseFactory.NAME).path + "-wal")
            assertTrue("WAL in use", wal.length() > 0)
            val legacy = File(ctx.filesDir, "memory_records.log").apply { writeText("old text") }
            val vmScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
            val job = vmScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { MemoryWipe.deleteAll(ctx, fileDb) }
            vmScope.cancel() // the user pressed Back: the ViewModel is cleared
            job.join() // returns once deleteAll has returned or thrown
            assertEquals(0, fileDb.memoryDao().count())
            assertFalse(legacy.exists())
            assertEquals(0L, wal.length()) // the checkpoint ran
        } finally { fileDb.close(); ctx.deleteDatabase(com.rokid.context.memory.db.DatabaseFactory.NAME) }
    }

    /** Task 8 carry + F21: no vector or space survives, and a JPEG still waiting in a frame folder (no row yet) goes too. */
    @Test fun deleteAllLeavesNoVectorsSpacesOrUnlistedFrameFiles() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx)
        g.memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 1, "Milk carton"))
        val active = g.spaces.startBuilding("fp1", "{}", 2, "[]", 1); g.spaces.put("m1", active.id, floatArrayOf(1f, 0f)); assertTrue(g.spaces.swapIfComplete(active.id))
        val other = g.spaces.startBuilding("fp2", "{}", 2, "[]", 2); g.spaces.put("m1", other.id, floatArrayOf(0f, 1f))
        val waiting = File(ctx.filesDir, "rokid_inbox/w.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)) }
        val unrelated = File(ctx.filesDir, "keep.bin").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(1, MemoryWipe.deleteAll(ctx))
        assertFalse(waiting.exists()); assertTrue(unrelated.exists())
        assertTrue(g.db.embeddingDao().observeSpaces().first().isEmpty())
        assertTrue(g.db.embeddingDao().vectors(active.id).isEmpty() && g.db.embeddingDao().vectors(other.id).isEmpty())
        assertEquals(null, g.db.embeddingDao().current())
        unrelated.delete()
    }

    /** Review I2: copies of memory text outside the database go too; the received-frames ledger stays (no re-send). */
    @Test fun deleteAllRemovesTheQueueLegacyLogsAndExportsButKeepsTheFrameLedger() = runBlocking<Unit> {
        com.rokid.context.data.runtime.QueueRuntimeStore.enqueue(ctx, com.rokid.context.domain.queue.QueuePriority.EXPLICIT_WRITE,
            com.rokid.context.data.runtime.QueuePayloads.explicitWrite("remember the milk", "en", manual = true), personaSnapshot = "p")
        val queue = File(ctx.filesDir, "queue_runtime.log")
        assertTrue(queue.exists())
        val legacy = listOf("memory_records.log", "captured_frames.log", "summary_trace.log").map { File(ctx.filesDir, it).apply { writeText("old text") } }
        val exports = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val exported = listOf("proactive-context-memories-1.json", "proactive-context-diagnostics-1.txt").map { File(exports, it).apply { writeText("x") } }
        val ledger = File(ctx.filesDir, "cxr_received_frames.txt").apply { writeText("f1\n") }

        MemoryWipe.deleteAll(ctx)
        // The resumed pipeline may queue a fresh frame-ingest job; nothing queued before the wipe survives.
        assertTrue(com.rokid.context.data.runtime.QueueRuntimeStore.recent(ctx, 50).none { it.queue == com.rokid.context.domain.queue.QueuePriority.EXPLICIT_WRITE })
        legacy.forEach { assertFalse(it.name, it.exists()) }
        exported.forEach { assertFalse(it.name, it.exists()) }
        assertTrue(ledger.exists())
        ledger.delete()
    }

    /** Fix round 2 N3: a first-run legacy import finishes before the wipe, so it can't bring legacy memories back after it. */
    @Test fun theLegacyImportIsDoneBeforeTheWipe() = runBlocking<Unit> {
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_1\tshort_term\t0.9\t100\tMilk carton nearly empty\n")
        val importer = LegacyImporter(ctx, MemoryGraph.get(ctx))
        assertFalse(importer.isDone())
        MemoryWipe.deleteAll(ctx)
        assertTrue(importer.isDone())
        assertEquals(null, MemoryGraph.ensureLegacyImported(ctx)) // nothing left to import later
        assertEquals(0, MemoryGraph.get(ctx).db.memoryDao().count())
    }

    /** Fix round 3: an importer that would throw (unreadable legacy log) never blocks Delete all; it doesn't run at all. */
    @Test fun aBrokenLegacyImportDoesNotStopDeleteAll() = runBlocking<Unit> {
        val broken = File(ctx.filesDir, "memory_records.log").apply { mkdirs() } // readLines() on a directory throws
        g().memories.add(LegacyImporter.toMemory("m1", "short_term", 0.9, 1, "Milk carton"))
        MemoryWipe.deleteAll(ctx)
        assertEquals(0, g().db.memoryDao().count())
        assertFalse(broken.exists())
    }

    /** Fix round 3: after Delete all the import is marked done, so legacy logs that appear later are never imported. */
    @Test fun afterDeleteAllTheLegacyImportNeverRuns() = runBlocking<Unit> {
        MemoryWipe.deleteAll(ctx)
        assertTrue(LegacyImporter(ctx, g()).isDone())
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_1\tshort_term\t0.9\t100\tMilk carton nearly empty\n")
        assertEquals(null, MemoryGraph.ensureLegacyImported(ctx))
        assertEquals(0, g().db.memoryDao().count())
    }

    private fun g() = MemoryGraph.get(ctx)
}
