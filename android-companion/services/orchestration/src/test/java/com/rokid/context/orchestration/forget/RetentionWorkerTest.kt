package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.work.OrchestrationScheduler
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetentionWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val graph get() = MemoryGraph.get(ctx)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    }

    @After fun tearDown() { AppConfigStore.setPipelineEnabled(ctx, true); graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, tier: MemoryTier, ageMs: Long, frameId: String? = null, review: ReviewState = ReviewState.OK) =
        graph.db.memoryDao().insert(MemoryEntity(id, frameId, "text $id", "note", "", tier, 0.8, review, false, now - ageMs, false, "text $id"))
    private fun work() = runBlocking { TestListenableWorkerBuilder<RetentionWorker>(ctx).build().doWork() }
    private suspend fun exists(id: String) = graph.db.memoryViewDao().byId(id) != null

    /** Defaults: SHORT 24 h, MEDIUM 7 d, LONG 30 d (MemorySettings). */
    @Test fun tiersUseTheirWindows() = runBlocking {
        mem("s_old", MemoryTier.SHORT, 25 * hour); mem("s_new", MemoryTier.SHORT, 23 * hour)
        mem("m_kept", MemoryTier.MEDIUM, 6 * 24 * hour); mem("m_old", MemoryTier.MEDIUM, 8 * 24 * hour, review = ReviewState.NEEDS_REVIEW)
        mem("l_old", MemoryTier.LONG, 31 * 24 * hour); mem("l_new", MemoryTier.LONG, 29 * 24 * hour)
        assertTrue(work() is ListenableWorker.Result.Success)
        assertFalse(exists("s_old")); assertTrue(exists("s_new"))
        assertTrue(exists("m_kept")); assertFalse(exists("m_old"))
        assertFalse(exists("l_old")); assertTrue(exists("l_new"))
    }

    @Test fun runsWhileCaptureIsPaused() = runBlocking {
        AppConfigStore.setPipelineEnabled(ctx, false)
        mem("s_old", MemoryTier.SHORT, 25 * hour)
        work()
        assertFalse(exists("s_old"))
    }

    @Test fun oldFrameWithoutMemoriesDeleted() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/old.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        val kept = File(ctx.filesDir, "rokid_archive/new.jpg").apply { writeBytes(byteArrayOf(2)) }
        graph.db.frameDao().insertIgnore(FrameEntity("f_old", now - 31 * 24 * hour, jpg.absolutePath, "rokid_glasses"))
        graph.db.frameDao().insertIgnore(FrameEntity("f_new", now - hour, kept.absolutePath, "rokid_glasses"))
        work()
        assertNull(graph.db.forgetDao().frame("f_old"))
        assertFalse(jpg.exists())
        assertTrue(kept.exists() && graph.db.forgetDao().frame("f_new") != null)
    }

    @Test fun lastExpiredMemoryOfAFrameDeletesItsJpegAndLocksIt() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/f1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("f1", now - 2 * hour, jpg.absolutePath, "rokid_glasses"))
        mem("s_old", MemoryTier.SHORT, 25 * hour, frameId = "f1")
        work()
        assertFalse(jpg.exists())
        assertEquals(listOf("f1"), graph.db.forgetDao().tombstoned(listOf("f1")))
    }

    @Test fun redactsAsksCitingExpiredMemory() = runBlocking {
        mem("s_old", MemoryTier.SHORT, 25 * hour)
        graph.db.askDao().upsert(AskEntity("q1", 1, "q", "answer", """["s_old"]""", null, "phone_typed", false))
        work()
        val ask = graph.db.askDao().byId("q1")!!
        assertTrue(ask.redacted); assertEquals(ForgetService.REDACTED, ask.answer)
    }

    @Test fun ensureRetentionSchedulesOneDailyJob() {
        OrchestrationScheduler(ctx).scheduleCritic()
        OrchestrationScheduler(ctx).scheduleCritic()
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(RetentionWorker.NAME).get()
        assertEquals(1, infos.size)
        // A pause cancels the pipeline's periodic work, never retention (it only removes data).
        OrchestrationScheduler(ctx).pausePipeline()
        assertFalse(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(RetentionWorker.NAME).get().single().state.isFinished)
    }

    /** Fix round 1 C1: expiry never invalidates descendants; a MEDIUM summary outlives its SHORT sources. */
    @Test fun summaryOutlivesItsShortSources() = runBlocking {
        mem("src", MemoryTier.SHORT, 25 * hour)
        mem("sum_young", MemoryTier.MEDIUM, 25 * hour)
        mem("sum_old", MemoryTier.MEDIUM, 8 * 24 * hour)
        graph.db.forgetDao().insertEdges(listOf(com.rokid.context.memory.db.MemorySourceEntity("sum_young", "src"), com.rokid.context.memory.db.MemorySourceEntity("sum_old", "src")))
        work()
        assertFalse(exists("src"))
        assertEquals(ReviewState.OK, graph.db.memoryViewDao().byId("sum_young")!!.reviewState)
        assertFalse(exists("sum_old"))
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ResummarizeWorker.NAME).get().isEmpty())
    }

    /** Fix round 1 I3: retention also sweeps JPEGs of tombstoned frames left behind by an earlier failed delete. */
    @Test fun retentionSweepsOrphanJpegsOfTombstonedFrames() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/f_gone.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("f_gone", 1))
        work()
        assertFalse(jpg.exists())
    }

    /** Final review C1: an accepted reminder whose cited memory expires keeps its params and its armed alarm. */
    private suspend fun acceptedReminder(askId: String, source: String): Pair<String, org.robolectric.shadows.ShadowAlarmManager> {
        graph.db.askDao().upsert(AskEntity(askId, 1, "q", "answer", "[\"$source\"]", null, "phone_typed", false))
        val pid = "prop_ASK_$askId"
        val r = com.rokid.context.connectors.actions.ActionParams.Reminder("Call the clinic", now + 72 * hour, "about the results")
        graph.proposals.addIgnore(
            com.rokid.context.memory.db.ProposalEntity(pid, "ASK", askId, "REMINDER", com.rokid.context.connectors.actions.ActionParams.encode(r), "EXECUTED", 0, "hud", "alarm:$pid", 1),
        )
        graph.proposals.addIgnore(com.rokid.context.memory.db.ProposalEntity("pend_$askId", "ASK", askId, "SHOPPING", "{\"items\":[\"milk\"]}", "PENDING", Long.MAX_VALUE, null, null, 1))
        com.rokid.context.connectors.actions.executors.ReminderExecutor(ctx).arm(pid, r)
        val alarms = org.robolectric.Shadows.shadowOf(ctx.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        return pid to alarms
    }

    @Test fun retentionKeepsAnAcceptedRemindersAlarmAndParams() = runBlocking {
        mem("s_old", MemoryTier.SHORT, 25 * hour)
        val (pid, alarms) = acceptedReminder("qa", "s_old")
        val params = graph.proposals.byId(pid)!!.paramsJson
        work()
        assertFalse(exists("s_old"))
        assertTrue(graph.db.askDao().byId("qa")!!.redacted)
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals("EXECUTED", graph.proposals.byId(pid)!!.state)
        assertEquals(params, graph.proposals.byId(pid)!!.paramsJson)
        // a PENDING proposal of the same ask still expires and loses its text (spec 6.1.1: PENDING -> EXPIRED)
        assertEquals("EXPIRED", graph.proposals.byId("pend_qa")!!.state)
        assertEquals(ForgetService.REDACTED_PARAMS, graph.proposals.byId("pend_qa")!!.paramsJson)
    }

    @Test fun aUserForgetOfTheSameSourceStillCancelsIt() = runBlocking {
        mem("s_young", MemoryTier.SHORT, 1 * hour)
        val (pid, alarms) = acceptedReminder("qb", "s_young")
        assertTrue(ForgetActions.forgetFact(ctx, "s_young"))
        assertEquals(0, alarms.scheduledAlarms.size)
        assertEquals(ForgetService.REDACTED_PARAMS, graph.proposals.byId(pid)!!.paramsJson)
    }
}
