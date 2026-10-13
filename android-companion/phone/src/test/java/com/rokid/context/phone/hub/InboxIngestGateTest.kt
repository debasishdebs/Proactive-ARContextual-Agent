package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import android.content.Context
import android.os.Looper
import com.rokid.context.data.runtime.AppConfig
import com.rokid.context.data.runtime.AppConfigReader
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

    /** Task 16 carry: the hub's ingest callback runs on Main.immediate, so it must never open the secrets store. */
    @Test fun theIngestCallbackNeverReadsTheSecretOnMain() {
        val secretOnMain = mutableListOf<String>(); var plain = 0
        val spy = object : AppConfigReader {
            override fun get(context: Context): AppConfig {
                if (Looper.myLooper() == Looper.getMainLooper()) secretOnMain += "get"
                return AppConfigStore.getWithoutSecret(context)
            }
            override fun getWithoutSecret(context: Context): AppConfig { plain++; return AppConfigStore.getWithoutSecret(context) }
            override fun isPipelineEnabled(context: Context): Boolean { plain++; return AppConfigStore.isPipelineEnabled(context) }
        }
        assertTrue(Looper.getMainLooper().isCurrentThread)
        AppConfigStore.setPipelineEnabled(ctx, true)
        enqueueInboxIngest(ctx, spy)
        AppConfigStore.setPipelineEnabled(ctx, false)
        enqueueInboxIngest(ctx, spy)
        assertEquals(emptyList<String>(), secretOnMain)
        assertTrue("the spy is the store the callback reads ($plain)", plain >= 3)
        assertEquals(1, QueueRuntimeStore.snapshot(ctx).totalPending)
    }
}
