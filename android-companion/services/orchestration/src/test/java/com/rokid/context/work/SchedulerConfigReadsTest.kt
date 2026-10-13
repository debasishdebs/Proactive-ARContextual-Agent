package com.rokid.context.work

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.AppConfig
import com.rokid.context.data.runtime.AppConfigReader
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/** Records every config read and whether it ran on Main; [get] is the one that opens the Keystore-backed secrets. */
class SpyConfigReader : AppConfigReader {
    val secretReadsOnMain = mutableListOf<String>()
    var plainReads = 0
    private fun onMain() = Looper.myLooper() == Looper.getMainLooper()
    override fun getWithLegacySecret(context: Context): AppConfig {
        if (onMain()) secretReadsOnMain += "getWithLegacySecret"
        return AppConfigStore.getWithoutSecret(context)
    }
    override fun getWithoutSecret(context: Context): AppConfig { plainReads++; return AppConfigStore.getWithoutSecret(context) }
    override fun isPipelineEnabled(context: Context): Boolean { plainReads++; return AppConfigStore.isPipelineEnabled(context) }
}

/** Task 16 carry: every enqueue path may run on Main, so none of them may open the secrets store. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SchedulerConfigReadsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val spy = SpyConfigReader()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    }
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    @Test fun enqueuesOnMainNeverReadTheSecret() = runBlocking {
        assertTrue("runs on Main", Looper.getMainLooper().isCurrentThread)
        val s = OrchestrationScheduler(ctx, spy)
        s.enqueueExplicitWrite("buy milk")
        s.enqueueReadContext("where are my keys")
        s.enqueueFrameIngestQueue()
        s.enqueueSummarizer(UUID.randomUUID().toString(), 0.8, System.currentTimeMillis())
        s.enqueueHistoricalPhotoBackfill()
        assertEquals(emptyList<String>(), spy.secretReadsOnMain)
        assertTrue("the spy is the store the scheduler reads (${spy.plainReads})", spy.plainReads >= 5)
    }
}
