package com.rokid.context.phone.ui.status

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
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

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(ctx)

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
