package com.rokid.context.phone.ui.status

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.Worker
import androidx.work.testing.SynchronousExecutor
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.work.OrchestrationScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class LegacyPipelineControlTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Without this the scheduled workers (QueueDrainWorker etc.) really execute on background dispatchers the moment they are
     * enqueued, so a WorkInfo can be RUNNING when asserted. No-op workers + synchronous executors make the states deterministic.
     */
    private class NoopWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result = Result.success()
    }

    @Before fun setUp() {
        val config = Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    NoopWorker(appContext, workerParameters)
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, config)
    }

    private fun drainStates() = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(OrchestrationScheduler.QUEUE_DRAIN_WORK).get().map { it.state }

    @Test fun pauseStopsWorkersAndPersistsResumeReschedules() {
        val scheduler = OrchestrationScheduler(ctx).apply { scheduleQueueDrain() }
        val control = LegacyPipelineControl(ctx, scheduler)
        assertTrue(control.enabled.value)

        control.setEnabled(false)
        assertFalse(control.enabled.value)
        assertFalse(AppConfigStore.get(ctx).pipelineEnabled)
        assertTrue(drainStates().all { it == WorkInfo.State.CANCELLED })

        control.setEnabled(true)
        assertTrue(AppConfigStore.get(ctx).pipelineEnabled)
        assertEquals(true, drainStates().any { it == WorkInfo.State.ENQUEUED })
    }

    @Test fun staleCacheDoesNotSkipResume() {
        val scheduler = OrchestrationScheduler(ctx)
        val control = LegacyPipelineControl(ctx, scheduler)
        assertTrue(control.enabled.value)
        // legacy MainActivity path: flips the flag and stops workers behind the control's back
        AppConfigStore.setPipelineEnabled(ctx, false)
        scheduler.pausePipeline()
        control.setEnabled(true)
        assertTrue(AppConfigStore.get(ctx).pipelineEnabled)
        assertTrue(control.enabled.value)
        assertEquals(true, drainStates().any { it == WorkInfo.State.ENQUEUED })
    }
}
