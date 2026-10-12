package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueueRuntimeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class InboxIngestGateTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(ctx)

    /** Final review I2: a stored frame (acked, kept in rokid_inbox) queues no ingest and starts no drain while paused. */
    @Test fun pausedPipelineQueuesNothingForAStoredFrame() {
        AppConfigStore.setPipelineEnabled(ctx, false)
        enqueueInboxIngest(ctx)
        assertEquals(0, QueueRuntimeStore.snapshot(ctx).totalPending)
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("queue-drain-now").get().isEmpty())
    }
}
