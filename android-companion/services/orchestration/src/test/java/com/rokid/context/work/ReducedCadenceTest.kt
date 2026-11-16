package com.rokid.context.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.canvas.moment.MomentCadenceWorker
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

/** v1.1 T7: an expected stop with nothing waiting moves the periodic jobs to 60 minutes (battery guarded), never cancels them; start or a stored frame puts 15 back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReducedCadenceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val wm get() = WorkManager.getInstance(ctx)
    private val sched get() = OrchestrationScheduler(ctx)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ctx.getSharedPreferences(OrchestrationScheduler.CADENCE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        RokidFrameInbox.inboxDir(ctx).deleteRecursively()
        sched.apply { scheduleFrameIngest(); scheduleQueueDrain(); ensureMomentCadence() }
    }
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    private fun intervalMs(name: String): Long {
        val id = wm.getWorkInfosForUniqueWork(name).get().single { !it.state.isFinished }.id
        val db = androidx.work.impl.WorkManagerImpl.getInstance(ctx).workDatabase
        return db.workSpecDao().getWorkSpec(id.toString())!!.intervalDuration
    }

    private fun batteryGuard(name: String): Boolean {
        val id = wm.getWorkInfosForUniqueWork(name).get().single { !it.state.isFinished }.id
        return androidx.work.impl.WorkManagerImpl.getInstance(ctx).workDatabase.workSpecDao().getWorkSpec(id.toString())!!.constraints.requiresBatteryNotLow()
    }

    private val all = listOf(OrchestrationScheduler.FRAME_INGEST_WORK, OrchestrationScheduler.QUEUE_DRAIN_WORK, MomentCadenceWorker.NAME)

    @Test fun anIdleExpectedStopFallsBackToSixtyMinutesBatteryGuardedAndCancelsNothing() {
        all.forEach { assertEquals(TimeUnit.MINUTES.toMillis(15), intervalMs(it)) }
        assertTrue(sched.reducePeriodicJobsIfIdle())
        all.forEach { assertEquals("$it stays scheduled at 60 min", TimeUnit.MINUTES.toMillis(60), intervalMs(it)); assertTrue("$it is battery guarded", batteryGuard(it)) }
        assertTrue(OrchestrationScheduler.cadenceReduced(ctx))
    }

    @Test fun aFileWaitingInTheInboxKeepsTheNormalCadence() {
        File(RokidFrameInbox.inboxDir(ctx).apply { mkdirs() }, "f.jpg").writeBytes(byteArrayOf(1))
        assertFalse(sched.reducePeriodicJobsIfIdle())
        all.forEach { assertEquals(TimeUnit.MINUTES.toMillis(15), intervalMs(it)) }
        assertFalse(OrchestrationScheduler.cadenceReduced(ctx))
    }

    @Test fun aProcessStartKeepsTheReducedPeriodAndRestoreBringsTheNormalOneBack() {
        sched.reducePeriodicJobsIfIdle()
        sched.ensureBackgroundWork() // every process start: KEEP, never the 15 minute spec again
        all.forEach { assertEquals(TimeUnit.MINUTES.toMillis(60), intervalMs(it)) }
        sched.restorePeriodicJobs() // hub start / a frame stored
        all.forEach { assertEquals(TimeUnit.MINUTES.toMillis(15), intervalMs(it)); assertFalse(batteryGuard(it)) }
        assertFalse(OrchestrationScheduler.cadenceReduced(ctx))
    }

    @Test fun pausedProcessingStaysCancelledAfterAReduce() {
        AppConfigStore.setPipelineEnabled(ctx, false)
        sched.pausePipeline()
        sched.reducePeriodicJobsIfIdle()
        assertTrue(wm.getWorkInfosForUniqueWork(OrchestrationScheduler.FRAME_INGEST_WORK).get().all { it.state.isFinished })
        assertEquals(TimeUnit.MINUTES.toMillis(60), intervalMs(MomentCadenceWorker.NAME))
    }

    // ---- review hub-wp3 r1 ----

    @Test fun aReduceDecidedBeforeARestorationCannotUndoIt() {
        val epoch = OrchestrationScheduler.cadenceEpoch() // the stop
        OrchestrationScheduler.noteCadenceRestore() // a hub start or a frame arrives (not reduced yet: it has nothing to restore) ...
        sched.restorePeriodicJobs()
        assertFalse("... and the late reduce is void", sched.reducePeriodicJobsIfIdle(epoch) { true })
        assertFalse(OrchestrationScheduler.cadenceReduced(ctx)); all.forEach { assertEquals(TimeUnit.MINUTES.toMillis(15), intervalMs(it)) }
        assertTrue("a reduce from the latest epoch still works", sched.reducePeriodicJobsIfIdle(OrchestrationScheduler.cadenceEpoch()) { true })
    }

    @Test fun anEmptyQueueIsNotEnoughWhileMomentsAreNotCaughtUp() {
        assertFalse(sched.reducePeriodicJobsIfIdle(OrchestrationScheduler.cadenceEpoch()) { false })
        assertFalse(OrchestrationScheduler.cadenceReduced(ctx)); all.forEach { assertEquals(TimeUnit.MINUTES.toMillis(15), intervalMs(it)) }
    }

    @Test fun theDefaultCatchUpRebuildsAndAllowsTheReduceWhenNothingFails() {
        assertTrue(sched.reducePeriodicJobsIfIdle())
    }
}
