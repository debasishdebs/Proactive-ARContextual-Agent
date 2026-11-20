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
import kotlinx.coroutines.Dispatchers
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

    /** Final review I3: the photo test's labels (the user's own free text about their photos) and results go with Delete all. */
    /** Fix round 2 (m4): Delete all withdraws the consent to send photos with questions, like image intent. */
    /** M-follow-up: a shadow that read the old generation can store after the first clear, so Delete all clears again once the generation moved (behaviour, not a source scan). */
    @Test fun deleteAllClearsTheShadowCachesBeforeAndAfterTheGenerationBump() = runBlocking {
        val seen = mutableListOf<Long>()
        com.rokid.context.work.frames.place.ShadowCaches.resetForTests()
        com.rokid.context.work.frames.place.ShadowCaches.register("probe") { seen += MemoryWipe.generation.get() }
        val g0 = MemoryWipe.generation.get()
        try { MemoryWipe.deleteAll(ctx) } finally { com.rokid.context.work.frames.place.ShadowCaches.resetForTests() }
        assertTrue("cleared before the bump: $seen", seen.any { it == g0 })
        assertTrue("cleared again after the bump: $seen", seen.any { it > g0 })
    }

    @Test fun deleteAllTurnsTheAskPhotosSwitchOff() = runBlocking {
        AskPhotoPolicy.set(ctx, true)
        MemoryWipe.deleteAll(ctx)
        assertFalse(AskPhotoPolicy.enabled(ctx))
    }

    @Test fun deleteAllRemovesThePhotoEvalDirectory() = runBlocking {
        val dir = File(ctx.filesDir, "photo-eval").apply { mkdirs() }
        File(dir, "queries.json").writeText("[{\"frameId\":\"f1\",\"query\":\"private words\"}]")
        File(dir, "result.json").writeText("{}")
        File(ctx.filesDir, "keep_me.txt").writeText("x")
        MemoryWipe.deleteAll(ctx)
        assertFalse(File(dir, "queries.json").exists()); assertFalse(File(dir, "result.json").exists()); assertFalse(dir.exists())
        assertTrue("other files are untouched", File(ctx.filesDir, "keep_me.txt").exists())
    }

    /** P7xP8 I1: Delete all redacts every surviving proposal (EXECUTED included), scrubs its log and disarms its reminder alarm. */
    @Test fun deleteAllRedactsExecutedProposalsAndCancelsTheirReminderAlarms() = runBlocking<Unit> {
        val g = MemoryGraph.get(ctx)
        g.asks.add(AskEntity("ask9", 1, "q", "a", "[]", null, "phone", false))
        val pid = "prop_ASK_ask9"
        val r = com.rokid.context.connectors.actions.ActionParams.Reminder("Call the clinic", System.currentTimeMillis() + 3_600_000, "about the results")
        g.proposals.addIgnore(com.rokid.context.memory.db.ProposalEntity(pid, "ASK", "ask9", "REMINDER", com.rokid.context.connectors.actions.ActionParams.encode(r), "EXECUTED", 0, "auto", "alarm:$pid", 1))
        g.db.actionDao().log(com.rokid.context.memory.db.ActionLogEntity(ts = 1, proposalId = pid, transition = "EXECUTED", detail = "Call the clinic"))
        com.rokid.context.connectors.actions.executors.ReminderExecutor(ctx).arm(pid, r)
        val alarms = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)

        MemoryWipe.deleteAll(ctx)

        assertEquals(0, alarms.scheduledAlarms.size)
        val p = g.db.actionDao().proposal(pid)
        assertTrue(p == null || p.paramsJson == com.rokid.context.memory.forget.ForgetService.REDACTED_PARAMS)
        assertTrue(g.db.actionDao().logFor(pid).all { it.detail.isEmpty() })
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
        // Stamped before the wipe (inbox files carry their capture time; one at or after the fence survives, fix round 1 I2).
        val waiting = File(ctx.filesDir, "rokid_inbox/w.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(9)); setLastModified(System.currentTimeMillis() - 5_000) }
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
        assertEquals(null, MemoryGraph.ensureLegacyImported(ctx, isMain = { false })) // nothing left to import later
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
        assertEquals(null, MemoryGraph.ensureLegacyImported(ctx, isMain = { false }))
        assertEquals(0, g().db.memoryDao().count())
    }

    /** Review Focus 3: the start time lets the phone drop frames captured before the wipe that are still on the glasses. */
    @Test fun deleteAllMarksTheWipeStart() = runBlocking<Unit> {
        val before = System.currentTimeMillis()
        MemoryWipe.deleteAll(ctx)
        assertTrue((WipeFence.lastStartedAtMs(ctx.filesDir) ?: 0L) >= before)
        assertFalse(WipeFence.mutex.isLocked)
    }

    /** Fix round 2 I2: a file already in the inbox when the fence is marked goes, whatever its stamp says. */
    @Test fun aPreWipeInboxFileWithAFutureStampIsDeleted() = runBlocking<Unit> {
        val inbox = File(ctx.filesDir, "rokid_inbox").apply { mkdirs() }
        val future = File(inbox, "rokid_2_b.jpg").apply { writeBytes(byteArrayOf(2)); setLastModified(System.currentTimeMillis() + 60_000) }
        MemoryWipe.deleteAll(ctx)
        assertFalse(future.exists())
    }

    /** Fix round 1/2 I2: a frame stored after the fence mark (outside the snapshot, stamped after it) survives the wipe. */
    @Test fun aFrameStoredAfterTheFenceMarkSurvives() = runBlocking<Unit> {
        val inbox = File(ctx.filesDir, "rokid_inbox").apply { mkdirs() }
        File(ctx.filesDir, WipeFence.FILE).delete()
        WipeFence.mutex.lock() // the wipe marks its fence, then waits here before deleting files
        val wipe = launch(Dispatchers.IO) { MemoryWipe.deleteAll(ctx) }
        while (WipeFence.lastStartedAtMs(ctx.filesDir) == null) Thread.sleep(5)
        val after = File(inbox, "rokid_3_c.jpg").apply { writeBytes(byteArrayOf(3)); setLastModified(System.currentTimeMillis() + 1_000) }
        WipeFence.mutex.unlock()
        wipe.join()
        assertTrue(after.exists())
        after.delete()
    }

    /** Fix round 1 I3: a fence that can't be recorded (even after the frames are gone) fails closed. */
    @Test fun aFenceThatCannotBeRecordedFailsClosed() = runBlocking<Unit> {
        val blocker = File(ctx.filesDir, WipeFence.FILE).apply { mkdirs(); File(this, "x").writeText("x") }
        try {
            MemoryWipe.deleteAll(ctx)
            assertTrue(WipeFence.fence(ctx.filesDir) is WipeFence.Fence.Unknown)
        } finally {
            blocker.deleteRecursively()
        }
        assertTrue("written once possible", WipeFence.fence(ctx.filesDir) is WipeFence.Fence.At)
    }

    private fun g() = MemoryGraph.get(ctx)
}
