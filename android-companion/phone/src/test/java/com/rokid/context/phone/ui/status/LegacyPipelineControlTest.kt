package com.rokid.context.phone.ui.status

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
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
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.work.OrchestrationScheduler
import kotlinx.coroutines.flow.first
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

    /** Records every SharedPreferences file opened through it; the legacy secrets store is "app_config_secret". */
    private class PrefsSpyContext(base: Context) : ContextWrapper(base) {
        val opened = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences { opened += name.orEmpty(); return super.getSharedPreferences(name, mode) }
    }

    /** Fix round 1 (review I1, Threads): the ⏸ control runs on Main, so it reads only the plain flag, never the Keystore-backed secrets. */
    @Test fun constructingAndTogglingNeverOpensTheSecretsStore() {
        val spy = PrefsSpyContext(ctx)
        val control = LegacyPipelineControl(spy, OrchestrationScheduler(ctx), io = kotlinx.coroutines.Dispatchers.Unconfined)
        control.setEnabled(false); control.setEnabled(true)
        assertTrue(control.enabled.value)
        assertTrue(spy.opened.isNotEmpty())
        assertEquals(listOf<String>(), spy.opened.filter { it == "app_config_secret" })
    }

    /** Records the thread of every prefs open and queue-file lookup made through it. */
    private class ThreadSpyContext(base: Context) : ContextWrapper(base) {
        val threads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences { threads += Thread.currentThread(); return super.getSharedPreferences(name, mode) }
        override fun getFilesDir(): java.io.File { threads += Thread.currentThread(); return super.getFilesDir() }
    }

    /** Final-review I3: the tap returns at once; the TTL freeze/extend (queue file) and the flag commits run off Main. */
    @Test fun aTapDoesNoPrefsOrQueueFileWorkOnMain() = kotlinx.coroutines.runBlocking {
        AppConfigStore.setPipelineEnabled(ctx, true)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, com.rokid.context.data.runtime.QueuePayloads.explicitWrite("x", "en", manual = true), personaSnapshot = "p")
        val spy = ThreadSpyContext(ctx)
        val control = LegacyPipelineControl(spy, OrchestrationScheduler(ctx))
        val main = android.os.Looper.getMainLooper().thread
        assertEquals(main, Thread.currentThread())
        spy.threads.clear() // the constructor's one plain-flag read is allowed (the initial state)
        control.setEnabled(false)
        kotlinx.coroutines.withTimeout(5_000) { control.enabled.first { !it } }
        control.setEnabled(true)
        kotlinx.coroutines.withTimeout(5_000) { control.enabled.first { it } }
        assertTrue(spy.threads.isNotEmpty())
        assertEquals(listOf<Thread>(), spy.threads.filter { it == main })
        assertTrue(AppConfigStore.isPipelineEnabled(ctx))
    }

    @Test fun pauseStopsWorkersAndPersistsResumeReschedules() {
        val scheduler = OrchestrationScheduler(ctx).apply { scheduleQueueDrain() }
        val control = LegacyPipelineControl(ctx, scheduler, io = kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue(control.enabled.value)

        control.setEnabled(false)
        assertFalse(control.enabled.value)
        assertFalse(AppConfigStore.isPipelineEnabled(ctx))
        assertTrue(drainStates().all { it == WorkInfo.State.CANCELLED })

        control.setEnabled(true)
        assertTrue(AppConfigStore.isPipelineEnabled(ctx))
        assertEquals(true, drainStates().any { it == WorkInfo.State.ENQUEUED })
    }

    /** Final review I2: frames that waited in the inbox while paused are drained on resume (a FRAME_INGEST job + drain now). */
    @Test fun resumeQueuesTheInboxBacklogAndADrain() {
        val control = LegacyPipelineControl(ctx, OrchestrationScheduler(ctx), io = kotlinx.coroutines.Dispatchers.Unconfined)
        control.setEnabled(false)
        assertEquals(0, QueueRuntimeStore.snapshot(ctx).pendingByQueue[QueuePriority.FRAME_INGEST])
        control.setEnabled(true)
        assertEquals(1, QueueRuntimeStore.snapshot(ctx).pendingByQueue[QueuePriority.FRAME_INGEST])
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("queue-drain-now").get().isNotEmpty())
    }

    @Test fun staleCacheDoesNotSkipResume() {
        val scheduler = OrchestrationScheduler(ctx)
        val control = LegacyPipelineControl(ctx, scheduler, io = kotlinx.coroutines.Dispatchers.Unconfined)
        assertTrue(control.enabled.value)
        // legacy MainActivity path: flips the flag and stops workers behind the control's back
        AppConfigStore.setPipelineEnabled(ctx, false)
        scheduler.pausePipeline()
        control.setEnabled(true)
        assertTrue(AppConfigStore.isPipelineEnabled(ctx))
        assertTrue(control.enabled.value)
        assertEquals(true, drainStates().any { it == WorkInfo.State.ENQUEUED })
    }

    /** Phase 4 carry: the second tap toggles the first tap's request, not the not-yet-published state. */
    @Test fun aQuickPauseThenResumeTogglesFromTheRequestedState() = kotlinx.coroutines.test.runTest {
        AppConfigStore.setPipelineEnabled(ctx, true)
        val io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        // The TestScope itself, not backgroundScope: advanceUntilIdle never runs background-only work.
        val control = LegacyPipelineControl(ctx, OrchestrationScheduler(ctx), io = io, scope = this)
        control.toggle() // pause
        control.toggle() // resume, before the pause was persisted
        assertTrue("nothing published yet", control.enabled.value)
        testScheduler.advanceUntilIdle()
        assertTrue(control.enabled.value)
        assertTrue(AppConfigStore.isPipelineEnabled(ctx))
        control.toggle()
        testScheduler.advanceUntilIdle()
        assertFalse("the requested state resynced after both applied", control.enabled.value)
    }
}
