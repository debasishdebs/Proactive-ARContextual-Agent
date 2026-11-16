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
        com.rokid.context.ai.embed.LocalModelPower.minBatteryPct = { 70 } // these cases were written for the 70 % level
    }
    @After fun tearDown() {
        clock.setBatteryPaused(false); clock.setRebuildPending(false); clock.setPendingRebuildToken(null)
        com.rokid.context.ai.embed.LocalModelPower.minBatteryPct = { com.rokid.context.ai.embed.LocalModelPower.DEFAULT_MIN_BATTERY_PCT }
    }

    private fun queued(): List<WorkInfo> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(EmbeddingWorker.NAME).get()
    private fun waits(): List<WorkInfo> = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(EmbeddingWorker.NAME_WAIT).get().filter { !it.state.isFinished }

    private suspend fun gate(power: PowerState, local: Boolean = true, rebuild: Boolean = false) =
        EmbeddingWorker.gated(ctx, rebuild, token = "req-1", usesLocal = { local }, power = { power }) { ran++; ListenableWorker.Result.success() }

    @Test fun reindexDeferredBelow70NotCharging() = runBlocking {
        for (pct in listOf(70, 30, -1)) {
            val r = gate(PowerState(pct, charging = false), rebuild = true)
            assertTrue(r is ListenableWorker.Result.Success)
        }
        assertEquals("never embedded", 0, ran)
        assertTrue("re-checks later, not only on the system charging signal", waits().any { !it.constraints.requiresCharging() && it.initialDelayMillis == EmbeddingWorker.BATTERY_RECHECK_MS })
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

    @Test fun savesDuringAPauseKeepOneWaitAndLeaveTheIndexQueueAlone() = runBlocking {
        gate(PowerState(30, charging = false))
        val wait = waits().single().id
        val before = queued().map { it.id }.toSet()
        repeat(5) { EmbeddingWorker.enqueue(ctx) } // five saves while paused
        assertEquals("still the one wait", listOf(wait), waits().map { it.id })
        assertEquals("no extra index run was queued or replaced", before, queued().map { it.id }.toSet())
    }

    @Test fun aRebuildStillRunsNowWhilePaused() = runBlocking {
        gate(PowerState(30, charging = false))
        EmbeddingWorker.enqueue(ctx, rebuild = true)
        assertTrue(queued().any { !it.state.isFinished && it.initialDelayMillis == 0L })
        assertTrue("the wait is untouched", waits().isNotEmpty())
    }

    @Test fun changingTheBatteryLevelRechecksNow() = runBlocking {
        gate(PowerState(30, charging = false))
        val wait = waits().single().id
        EmbeddingWorker.recheckBatteryNow(ctx)
        val now = waits()
        assertTrue("the wait was replaced by an immediate run", now.none { it.id == wait } && now.any { it.initialDelayMillis == 0L })
        clock.setBatteryPaused(false)
        val ids = waits().map { it.id }
        EmbeddingWorker.recheckBatteryNow(ctx)
        assertEquals("not paused: nothing is enqueued", ids, waits().map { it.id })
    }

    @Test fun batteryDroppingMidRunDefersInsteadOfFailing() = runBlocking {
        val r = EmbeddingWorker.execute(ctx, attempt = 4) { throw LocalModelPausedException() }
        assertTrue(r is ListenableWorker.Result.Success)
        assertTrue(clock.batteryPaused())
        assertTrue(waits().any { it.initialDelayMillis == EmbeddingWorker.BATTERY_RECHECK_MS })
    }
}
