package com.rokid.context.work

import android.content.Context
import android.os.PowerManager
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
import com.rokid.context.ai.ProviderHttpException
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoleBinding
import com.rokid.context.core.model.UserFacingErrors
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.ModelProvider
import com.rokid.context.orchestration.PipelineRecords
import com.rokid.context.orchestration.RetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueueDrainWorkerRetryTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var behaviour: () -> String = { "ok" }
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String) = behaviour() }
        })
        AiGraph.resetForTests(ai)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, QueuePayloads.explicitWrite("remember milk", "en", manual = true), personaSnapshot = "default")
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun bind(key: String = "k") = runBlocking {
        ai.endpoints.save(Endpoint("az", "Azure", EndpointKind.AZURE_OPENAI, "https://az.example", "2024-10-21", listOf("gpt"), setOf(Capability.CHAT), 1), apiKey = key).getOrThrow()
        ai.bindings.set(RoleBinding(Role.EXPLICIT_REMEMBER, "az", "gpt")).getOrThrow()
    }

    private fun runWorker(): Throwable? = runCatching { runBlocking { TestListenableWorkerBuilder<QueueDrainWorker>(ctx).build().doWork() } }.exceptionOrNull()
    private fun state() = QueueRuntimeStore.recent(ctx, 10).single().state

    /** Final review I2: a paused pipeline sends nothing to the AI provider and leaves the queue intact; resuming drains it. */
    @Test fun pausedPipelineCallsNoProviderAndTheResumedDrainProcessesTheBacklog() {
        var calls = 0
        bind(); behaviour = { calls++; """{"facts":[{"text":"milk","type":"note","confidence":0.9,"tags":[]}]}""" }
        AppConfigStore.setPipelineEnabled(ctx, false)
        assertEquals(null, runWorker())
        assertEquals(0, calls)
        assertEquals(QueueRuntimeStore.STATE_ENQUEUED, state())
        AppConfigStore.setPipelineEnabled(ctx, true)
        assertEquals(null, runWorker())
        assertEquals(1, calls)
        assertEquals(QueueRuntimeStore.STATE_SUCCEEDED, state())
    }

    @Test fun startupBeforeSeedingDefersTheJob() {
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
    }

    @Test fun transientServerErrorDefers() {
        bind(); behaviour = { throw ProviderHttpException(500, "Azure OpenAI", "upstream") }
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
    }

    @Test fun serverErrorWhoseBodyMentions404Defers() {
        bind(); behaviour = { throw ProviderHttpException(500, "Azure OpenAI", "{\"detail\":\"upstream route 404\"}") }
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
    }

    @Test fun authFailureFails() {
        bind(); behaviour = { throw ProviderHttpException(401, "Azure OpenAI", "invalid key") }
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_FAILED, state())
    }

    @Test fun cancellationKeepsTheJobAndPropagates() {
        bind(); behaviour = { throw CancellationException("work stopped") }
        assertTrue(runWorker() is CancellationException)
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
    }

    @Test fun successStoresOneMemoryPerFact() {
        bind(); behaviour = { """{"facts":[{"text":"Buy milk","type":"task","confidence":0.9,"tags":["shopping"]},{"text":"Oat milk","type":"note","confidence":0.7,"tags":[]}]}""" }
        assertEquals(null, runWorker())
        assertEquals(QueueRuntimeStore.STATE_SUCCEEDED, state())
        val saved = runBlocking { MemoryGraph.get(ctx).memories.recent(10) }.sortedBy { it.id }
        assertEquals(listOf("Buy milk", "Oat milk"), saved.map { it.text })
        assertEquals(listOf(MemoryTier.LONG, MemoryTier.MEDIUM), saved.map { it.tier })
        assertTrue(saved.all { it.reviewState == ReviewState.OK && it.frameId == null })
        assertEquals("shopping", saved[0].tags)
    }

    @Test fun anInvalidReplyIsRetriedOnceThenTheJobDefers() {
        bind(); var calls = 0; behaviour = { calls++; "Sure! I'll remember that." }
        runWorker()
        assertEquals(2, calls)
        assertEquals(QueueRuntimeStore.STATE_DEFERRED, state())
        assertEquals(1, QueueRuntimeStore.recent(ctx, 10).single().attemptCount)
    }

    @Test fun cancellationDoesNotUseAnAttempt() {
        bind(); behaviour = { throw CancellationException("work stopped") }
        repeat(3) { runWorker(); dueNow() }
        assertEquals(0, QueueRuntimeStore.recent(ctx, 10).single().attemptCount)
    }

    @Test fun batterySaverDeferralsDoNotUseAttemptsAndTheCapStillApplies() {
        bind(); behaviour = { throw ProviderHttpException(500, "Azure OpenAI", "upstream") }
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(true)
        repeat(20) { runWorker(); dueNow() }
        assertEquals(0, QueueRuntimeStore.recent(ctx, 10).single().attemptCount)
        shadowOf(ctx.getSystemService(PowerManager::class.java)).setIsPowerSaveMode(false)
        repeat(RetryPolicy.MAX_ATTEMPTS) { runWorker(); dueNow() }
        assertEquals(QueueRuntimeStore.STATE_FAILED, state())
    }

    /** Carry C1: a pause freezes the 24 h TTL, so a job queued 25 h before resume is processed, not EXPIRED. */
    @Test fun aPauseLongerThanTheTtlDoesNotExpireAQueuedJob() {
        var calls = 0
        bind(); behaviour = { calls++; """{"facts":[{"text":"milk","type":"note","confidence":0.9,"tags":[]}]}""" }
        QueueRuntimeStore.markSucceeded(ctx, QueueRuntimeStore.recent(ctx, 10).single().id) // set aside the setUp job
        val hour = 60L * 60L * 1000L
        val now = System.currentTimeMillis()
        val old = QueueRuntimeStore.enqueue(ctx, QueuePriority.EXPLICIT_WRITE, QueuePayloads.explicitWrite("remember milk", "en", manual = true),
            personaSnapshot = "default", ttlHours = 24, enqueueAtEpochMs = now - 25 * hour)
        AppConfigStore.setPipelineEnabled(ctx, false, nowEpochMs = now - 24 * hour - 30 * 60_000L) // 30 min of TTL left at pause
        QueueRuntimeStore.snapshot(ctx) // Diagnostics reads the queue while paused
        assertEquals(null, runWorker())
        assertEquals(QueueRuntimeStore.STATE_ENQUEUED, QueueRuntimeStore.recent(ctx, 10).single { it.id == old.id }.state)
        AppConfigStore.setPipelineEnabled(ctx, true)
        assertEquals(null, runWorker())
        assertEquals(1, calls)
        assertEquals(QueueRuntimeStore.STATE_SUCCEEDED, QueueRuntimeStore.recent(ctx, 10).single { it.id == old.id }.state)
    }

    /** Makes a deferred job due again (tests do not wait out the backoff). */
    private fun dueNow() {
        val r = QueueRuntimeStore.recent(ctx, 10).single()
        if (r.state == QueueRuntimeStore.STATE_DEFERRED) QueueRuntimeStore.markPostponed(ctx, r.id, r.lastError, delayMs = 0, nowEpochMs = System.currentTimeMillis() - 60_000)
    }

    @Test fun traceNoteAndQueueRowAreUserFacing() {
        bind(); behaviour = { throw ProviderHttpException(500, "Azure OpenAI", "upstream body") }
        runWorker()
        assertEquals(UserFacingErrors.AiUnavailable.line, runBlocking { PipelineRecords.recentTraces(ctx, 5) }.first { it.state == "RETRY" }.note)
        assertEquals(UserFacingErrors.AiUnavailable.line, QueueRuntimeStore.recent(ctx, 10).single().lastError)
    }

    @Test fun retryStopsAtTheCap() {
        bind(); behaviour = { throw ProviderHttpException(500, "Azure OpenAI", "upstream") }
        val id = QueueRuntimeStore.recent(ctx, 10).single().id
        // Earlier attempts, already due again (notBefore = now - 60 s + 15 s minimum backoff).
        repeat(RetryPolicy.MAX_ATTEMPTS - 1) {
            QueueRuntimeStore.markDeferred(ctx, id, "earlier", backoffMs = 0, nowEpochMs = System.currentTimeMillis() - 60_000)
        }
        runWorker()
        assertEquals(QueueRuntimeStore.STATE_FAILED, state())
        assertEquals(UserFacingErrors.AiGaveUp.line, runBlocking { PipelineRecords.recentTraces(ctx, 5) }.first { it.state == "FAILED" }.note)
    }

    @Test fun failuresAreLoggedWithoutThrowablesOrKeys() {
        ShadowLog.reset()
        bind(key = "sk-live-123"); behaviour = { throw ProviderHttpException(500, "Azure OpenAI", "echoed sk-live-123") }
        runWorker()
        val ours = listOf("QueueDrainWorker", "ModelRouter").flatMap { ShadowLog.getLogsForTag(it) }
        assertTrue("the failure is logged", ours.any { "RETRY" in it.msg })
        assertTrue("no throwable is logged", ours.none { it.throwable != null })
        assertTrue("the key is never logged", ours.none { "sk-live-123" in it.msg })
    }
}
