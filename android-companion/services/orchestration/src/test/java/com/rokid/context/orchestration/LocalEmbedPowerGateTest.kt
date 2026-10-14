package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.embed.LocalModelPausedException
import com.rokid.context.ai.embed.PowerState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Gaps G6: bulk on-device (LLAMACPP_LOCAL) embedding runs only when charging or battery > 70 %; otherwise it waits for a charger. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalEmbedPowerGateTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val clock by lazy { PrefsIndexClock(ctx) }
    private var ran = 0

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        clock.setBatteryPaused(false); clock.setRebuildPending(false); clock.setPendingRebuildToken(null)
    }
    @After fun tearDown() { clock.setBatteryPaused(false); clock.setRebuildPending(false); clock.setPendingRebuildToken(null) }

    private fun queued(): List<WorkInfo> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(EmbeddingWorker.NAME).get()

    private suspend fun gate(power: PowerState, local: Boolean = true, rebuild: Boolean = false) =
        EmbeddingWorker.gated(ctx, rebuild, token = "req-1", usesLocal = { local }, power = { power }) { ran++; ListenableWorker.Result.success() }

    @Test fun reindexDeferredBelow70NotCharging() = runBlocking {
        for (pct in listOf(70, 30, -1)) {
            val r = gate(PowerState(pct, charging = false), rebuild = true)
            assertTrue(r is ListenableWorker.Result.Success)
        }
        assertEquals("never embedded", 0, ran)
        assertTrue("waits for a charger", queued().any { !it.state.isFinished && it.constraints.requiresCharging() })
        assertTrue("the banner reason is set", clock.batteryPaused())
        assertTrue("a Rebuild stays pending", clock.rebuildPending())
        assertEquals("req-1", clock.pendingRebuildToken())
    }

    @Test fun reindexRunsWhenCharging() = runBlocking {
        clock.setBatteryPaused(true)
        gate(PowerState(15, charging = true))
        gate(PowerState(71, charging = false))
        gate(PowerState(5, charging = false), local = false) // a remote/Ollama binding is not the on-device model
        assertEquals(3, ran)
        assertFalse("the reason clears once a run is allowed", clock.batteryPaused())
        assertTrue(queued().none { it.constraints.requiresCharging() })
    }

    @Test fun batteryDroppingMidRunDefersInsteadOfFailing() = runBlocking {
        val r = EmbeddingWorker.execute(ctx, attempt = 4) { throw LocalModelPausedException() }
        assertTrue(r is ListenableWorker.Result.Success)
        assertTrue(clock.batteryPaused())
        assertTrue(queued().any { !it.state.isFinished && it.constraints.requiresCharging() })
    }
}
