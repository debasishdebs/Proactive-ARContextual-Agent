package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
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
class ForgetActionsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, frameId: String? = null) =
        graph.db.memoryDao().insert(MemoryEntity(id, frameId, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))

    @Test fun forgetMomentDeletesTheJpegAndRefreshes() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/m.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("f1", 1, jpg.absolutePath, "rokid_glasses"))
        mem("m1", "f1")
        var refreshed = 0
        val removals = ForgetActions.removals.get()
        assertTrue(ForgetActions.forgetMoment(ctx, "f1") { refreshed++ })
        assertFalse(jpg.exists())
        assertEquals(1, refreshed)
        assertTrue("Codex r21: an open canvas page sees the removal", ForgetActions.removals.get() > removals)
    }

    @Test fun invalidatedDescendantsQueueTheReSummarise() = runBlocking {
        mem("A"); mem("B")
        graph.db.forgetDao().insertEdges(listOf(MemorySourceEntity("B", "A")))
        assertTrue(ForgetActions.forgetFact(ctx, "A"))
        assertEquals(1, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ResummarizeWorker.NAME).get().size)
    }

    /** Integration carry 3: Forget cancels the redacted proposal's posted notifications (Add / No thanks, fired reminder). */
    @Test fun forgetCancelsTheRedactedProposalsNotifications() = runBlocking {
        mem("A")
        graph.asks.add(com.rokid.context.memory.db.AskEntity("ask1", 1, "q", "a", "[\"A\"]", null, "phone", false))
        val pid = "prop_ASK_ask1"
        graph.proposals.addIgnore(com.rokid.context.memory.db.ProposalEntity(pid, "ASK", "ask1", "REMINDER", "{}", "PENDING", Long.MAX_VALUE, null, null, 1))
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("t", "t", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        fun n() = android.app.Notification.Builder(ctx, "t").setSmallIcon(android.R.drawable.ic_menu_add).setContentTitle("Buy milk").build()
        nm.notify(pid, com.rokid.context.connectors.actions.ProposalNotifications.PROPOSAL_ID, n())
        nm.notify(pid.hashCode(), n())
        nm.notify("other", com.rokid.context.connectors.actions.ProposalNotifications.PROPOSAL_ID, n())
        assertTrue(ForgetActions.forgetFact(ctx, "A"))
        assertEquals(listOf("other"), org.robolectric.Shadows.shadowOf(nm).activeNotifications.map { it.tag })
    }

    /** Privacy carry: Forget cancels a redacted reminder's armed alarm (its intent still carries the title and note). */
    @Test fun forgetCancelsTheRedactedRemindersAlarm() = runBlocking {
        mem("A")
        graph.asks.add(com.rokid.context.memory.db.AskEntity("ask2", 1, "q", "a", "[\"A\"]", null, "phone", false))
        val pid = "prop_ASK_ask2"
        val due = System.currentTimeMillis() + 3_600_000
        val r = com.rokid.context.connectors.actions.ActionParams.Reminder("Call the clinic", due, "about the results")
        graph.proposals.addIgnore(
            com.rokid.context.memory.db.ProposalEntity(pid, "ASK", "ask2", "REMINDER", com.rokid.context.connectors.actions.ActionParams.encode(r), "EXECUTED", 0, "auto", "alarm:$pid", 1),
        )
        com.rokid.context.connectors.actions.executors.ReminderExecutor(ctx).arm(pid, r)
        val alarms = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertTrue(ForgetActions.forgetFact(ctx, "A"))
        assertEquals(0, alarms.scheduledAlarms.size)
    }

    @Test fun anUnknownIdIsNotAnError() = runBlocking {
        assertTrue(ForgetActions.forgetFact(ctx, "nope"))
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ResummarizeWorker.NAME).get().isEmpty())
    }

    /** Fence: a fact extracted from a frame forgotten while the model was answering is never written. */
    @Test fun aFactForAForgottenFrameIsDropped() = runBlocking {
        graph.db.frameDao().insertIgnore(FrameEntity("f1", 1, "/x.jpg", "rokid_glasses"))
        mem("m1", "f1")
        assertTrue(ForgetActions.forgetMoment(ctx, "f1"))
        assertFalse(com.rokid.context.orchestration.PipelineRecords.appendFact(ctx, "rokid_mem_j_0", "f1", "Late fact", "object", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK))
        assertEquals(0, graph.memories.all().size)
    }

    /** Fix round 1 I4: terminal queue rows holding text are blanked on Forget; live rows are kept. */
    @Test fun terminalTextPayloadsAreBlanked() = runBlocking {
        com.rokid.context.data.runtime.QueueRuntimeStore.clearAll(ctx)
        val q = com.rokid.context.data.runtime.QueueRuntimeStore

        val done = listOf(
            q.enqueue(ctx, com.rokid.context.domain.queue.QueuePriority.EXPLICIT_WRITE, com.rokid.context.data.runtime.QueuePayloads.explicitWrite("remember my PIN 1234", "en", true), "p"),
            q.enqueue(ctx, com.rokid.context.domain.queue.QueuePriority.READ, com.rokid.context.data.runtime.QueuePayloads.readContext("where is my diary", "en", true), "p"),
            q.enqueue(ctx, com.rokid.context.domain.queue.QueuePriority.PRIORITY_REPROCESS, """{"kind":"priority_reprocess","source_context":"Secret diary text"}""", "p"),
        )
        done.forEach { q.markSucceeded(ctx, it.id) }
        val live = q.enqueue(ctx, com.rokid.context.domain.queue.QueuePriority.EXPLICIT_WRITE, com.rokid.context.data.runtime.QueuePayloads.explicitWrite("remember the milk", "en", true), "p")
        mem("A")
        assertTrue(ForgetActions.forgetFact(ctx, "A"))
        val rows = q.recent(ctx, 10)
        assertTrue(rows.none { "1234" in it.payload || "diary" in it.payload || "Secret" in it.payload })
        assertTrue(rows.single { it.id == live.id }.payload.contains("remember the milk"))
        q.clearAll(ctx)
    }

    /** Fix round 1 I3: the sweep deletes JPEGs of tombstoned frames, never of a frame a memory still uses. */
    @Test fun sweepDeletesOnlyOrphanJpegsOfTombstonedFrames() = runBlocking {
        val dir = File(ctx.filesDir, "rokid_archive").apply { mkdirs() }
        val gone = File(dir, "f_gone.jpg").apply { writeBytes(byteArrayOf(1)) }
        val kept = File(dir, "f_kept.jpg").apply { writeBytes(byteArrayOf(2)) }
        val sibling = File(dir, "f_sib.jpg").apply { writeBytes(byteArrayOf(3)) }
        val fdao = graph.db.forgetDao()
        fdao.insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("f_gone", 1))
        graph.db.frameDao().insertIgnore(FrameEntity("f_sib", 1, sibling.absolutePath, "rokid_glasses"))
        mem("m2", "f_sib")
        fdao.insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("f_sib", 1))
        assertEquals(1, FrameSweep.sweep(ctx))
        assertFalse(gone.exists()); assertTrue(kept.exists()); assertTrue(sibling.exists())
    }

    /**
     * Final review I2: the sweep prunes tombstones nothing needs any more (no frame row, no memory, no file left, older
     * than FrameSweep.TOMBSTONE_KEEP_MS); a tombstone that still locks a live frame, a recent one, or one a memory still
     * points at is kept.
     */
    @Test fun sweepPrunesTombstonesNothingNeeds() = runBlocking {
        val fdao = graph.db.forgetDao()
        val now = System.currentTimeMillis()
        val old = now - FrameSweep.TOMBSTONE_KEEP_MS - 1
        val dir = File(ctx.filesDir, "rokid_archive").apply { mkdirs() }
        // 3 000 old rowless tombstones with no file: all go
        (0 until 3_000).forEach { fdao.insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("gone$it", old)) }
        // old rowless with a leftover JPEG: the file is deleted, then the tombstone may go
        val leftover = File(dir, "left.jpg").apply { writeBytes(byteArrayOf(1)) }
        fdao.insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("left", old))
        // kept: a live frame's lock (row kept by Forget fact), a recent rowless one, one a memory still points at
        val live = File(dir, "live.jpg").apply { writeBytes(byteArrayOf(2)) }
        graph.db.frameDao().insertIgnore(FrameEntity("live", 1, live.absolutePath, "rokid_glasses"))
        mem("m_live", "live")
        fdao.insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("live", old))
        fdao.insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("recent", now - 60_000))
        mem("m_ref", "ref")
        fdao.insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("ref", old))
        FrameSweep.sweep(ctx, nowMs = now)
        assertFalse(leftover.exists()); assertTrue(live.exists())
        assertEquals(setOf("live", "recent", "ref"), fdao.allTombstones().toSet())
    }

    /** GAP-1: the cached memory export still holds the forgotten text; every Forget removes the cached memory exports (regenerable). */
    private fun seedExports(): List<File> {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        return listOf("proactive-context-memories-7.json", "proactive-context-memories-8.json.part")
            .map { File(dir, it).apply { writeText("{\"memories\":[{\"text\":\"secret fact\"}]}") } }
    }

    @Test fun forgetFactDeletesTheCachedMemoryExports() = runBlocking {
        mem("A")
        val diag = File(ctx.cacheDir, "exports/proactive-context-diagnostics-1.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        val exports = seedExports()
        assertTrue(ForgetActions.forgetFact(ctx, "A"))
        exports.forEach { assertFalse(it.name, it.exists()) }
        assertTrue("the diagnostics export is not ours", diag.exists())
    }

    @Test fun forgetMomentDeletesTheCachedMemoryExports() = runBlocking {
        graph.db.frameDao().insertIgnore(FrameEntity("f1", 1, File(ctx.filesDir, "x.jpg").absolutePath, "rokid_glasses"))
        mem("m1", "f1")
        val exports = seedExports()
        assertTrue(ForgetActions.forgetMoment(ctx, "f1"))
        exports.forEach { assertFalse(it.name, it.exists()) }
    }
}
