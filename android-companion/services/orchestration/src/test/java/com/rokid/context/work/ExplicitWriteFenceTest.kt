package com.rokid.context.work

import android.content.Context
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.CallClass
import com.rokid.context.model.CallGate
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Codex r15 P1-2: a queued explicit write's persisted text is stored content: a Delete all completed at router admission drops the call unsent. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExplicitWriteFenceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val sent = CopyOnWriteArrayList<String>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        var wiped = false
        // Delete all runs to completion while the call waits at admission (inside the permit, before the upload slot)
        val gate = object : CallGate {
            override suspend fun <T> withPermit(callClass: CallClass, block: suspend () -> T): T {
                if (!wiped) { wiped = true; MemoryWipe.deleteAll(ctx) }
                return block()
            }
        }
        TestAi.bindAll(AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { sent += prompt; return """{"facts":[{"text":"x","type":"note","confidence":0.9,"tags":[]}]}""" } }
        }, gate = gate).also { AiGraph.resetForTests(it) })
        AppConfigStore.setPipelineEnabled(ctx, true)
        QueueRuntimeStore.clearAll(ctx)
        shadowOf(ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 80)
    }

    @After fun tearDown() {
        QueueRuntimeStore.clearAll(ctx); AppConfigStore.setPipelineEnabled(ctx, true)
        AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    @Test fun aDeleteAllAtAdmissionDropsTheQueuedExplicitWriteUnsent() {
        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, QueuePayloads.explicitWrite("PRIVATE-NOTE my pin is 4321", "en", manual = true), personaSnapshot = "p")
        runBlocking { TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork() }
        assertTrue("the deleted payload never reached the provider: $sent", sent.none { "PRIVATE-NOTE" in it })
    }
}
