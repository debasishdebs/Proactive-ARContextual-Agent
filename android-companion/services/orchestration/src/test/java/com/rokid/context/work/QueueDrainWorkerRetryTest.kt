package com.rokid.context.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Capability
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.EndpointKind
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueDrainWorkerRetryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var behaviour: () -> String = { "ok" }
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String) = behaviour() }
        })
        AiGraph.resetForTests(ai)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, QueuePayloads.explicitWrite("remember milk", "en", manual = true), personaSnapshot = "default")
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun bind() = runBlocking {
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT), 1), apiKey = "k").getOrThrow()
        ai.bindings.set(RoleBinding(Role.EXPLICIT_REMEMBER, "az", "gpt")).getOrThrow()
    }

    private fun runWorker(): Throwable? = runCatching { runBlocking { TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork() } }.exceptionOrNull()
    private fun state() = QueueRuntimeStore.recent(ctx, 10).single().state

    @Test fun startupBeforeSeedingDefersTheJob() {
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
    }

    @Test fun transientServerErrorDefers() {
        bind(); behaviour = { error("Azure OpenAI request failed: 500 upstream") }
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
    }

    @Test fun serverErrorWhoseBodyMentions404Defers() {
        bind(); behaviour = { error("Azure OpenAI request failed: 500 {\"detail\":\"upstream route 404\"}") }
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
    }

    @Test fun authFailureFails() {
        bind(); behaviour = { error("Azure OpenAI request failed: 401 invalid key") }
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_FAILED, state())
    }

    @Test fun cancellationKeepsTheJobAndPropagates() {
        bind(); behaviour = { throw CancellationException("work stopped") }
        assertTrue(runWorker() is CancellationException)
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
    }

    @Test fun successStillSucceeds() {
        bind(); behaviour = { """{"memory":"milk","confidence":0.9}""" }
        assertEquals(null, runWorker())
        assertEquals(QueueRuntimeStore.STATE_SUCCEEDED, state())
    }
}
