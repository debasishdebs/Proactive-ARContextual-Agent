package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.flow.first
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
class EmbeddingWorkerTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        AiGraph.resetForTests(AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState()))
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun queued() = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(EmbeddingWorker.NAME).get()

    @Test fun withoutAnEmbedBindingTheWorkerSucceedsQuietly() = runBlocking {
        val r = TestListenableWorkerBuilder<EmbeddingWorker>(ctx).build().doWork()
        assertTrue(r is ListenableWorker.Result.Success)
    }

    @Test fun enqueueIsUniqueAndBatteryAware() {
        EmbeddingWorker.enqueue(ctx); EmbeddingWorker.enqueue(ctx)
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(EmbeddingWorker.NAME).get()
        assertEquals(1, infos.size)
        assertTrue(infos.single().state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.SUCCEEDED))
        assertTrue(infos.single().constraints.requiresBatteryNotLow()) // F5
    }

    @Test fun aTextBacklogContinuesAtOnceWithoutTouchingTheBackoff() = runBlocking {
        EmbeddingWorker.execute(ctx, attempt = 0) { IndexMaintenance.Outcome.MORE }
        assertEquals(1, queued().size); assertEquals(0, PrefsIndexClock(ctx).moreStreak())
    }

    /** Review C2: image backfill with work left is continued after 30 s, doubling per consecutive MORE up to 15 min, never at once. */
    @Test fun moreContinuesWithAnExponentialBackoff() = runBlocking {
        assertEquals(30_000L, EmbeddingWorker.moreDelayMs(0))
        assertEquals(60_000L, EmbeddingWorker.moreDelayMs(1))
        assertEquals(15 * 60_000L, EmbeddingWorker.moreDelayMs(9))
        assertEquals(15 * 60_000L, EmbeddingWorker.moreDelayMs(50))
        EmbeddingWorker.execute(ctx, attempt = 0) { IndexMaintenance.Outcome.BACKFILL }
        val first = queued().single()
        assertTrue("delayed, not immediate", first.state == WorkInfo.State.ENQUEUED)
        assertEquals(1, PrefsIndexClock(ctx).moreStreak())
        EmbeddingWorker.execute(ctx, attempt = 0) { IndexMaintenance.Outcome.DONE }
        assertEquals(0, PrefsIndexClock(ctx).moreStreak())
    }

    /** Carried from Task 8: a dimension mismatch is a config failure. It is recorded and the work stops: no retry, no continuation. */
    @Test fun aConfigFailureStopsWithoutRetryOrContinuation() = runBlocking {
        var runs = 0
        val r = EmbeddingWorker.execute(ctx, attempt = 0) { runs++; throw IndexConfigException("vector has 3 dimensions, space expects 2") }
        assertTrue(r is ListenableWorker.Result.Failure)
        assertEquals(1, runs)
        assertTrue("nothing re-queued", queued().isEmpty())
    }

    /** 5 runs in total: attempts 0..3 retry, attempt 4 (the fifth run) fails. */
    @Test fun anOtherFailureRetriesABoundedNumberOfTimes() = runBlocking {
        (0..3).forEach { a -> assertTrue("attempt $a", EmbeddingWorker.execute(ctx, attempt = a) { throw java.io.IOException("offline") } is ListenableWorker.Result.Retry) }
        assertTrue(EmbeddingWorker.execute(ctx, attempt = 4) { throw java.io.IOException("offline") } is ListenableWorker.Result.Failure)
    }

    @Test fun anUncheckedCanaryRetriesWithTheSameCap() = runBlocking {
        assertTrue(EmbeddingWorker.execute(ctx, attempt = 0) { IndexMaintenance.Outcome.RETRY } is ListenableWorker.Result.Retry)
        assertTrue(EmbeddingWorker.execute(ctx, attempt = 4) { IndexMaintenance.Outcome.RETRY } is ListenableWorker.Result.Failure)
        assertTrue("no continuation", queued().isEmpty())
    }

    private fun bindOllama(server: okhttp3.mockwebserver.MockWebServer) = runBlocking {
        MemoryGraph.get(ctx).db.endpointDao().upsert(com.rokid.context.memory.db.EndpointEntity("ol", "Ollama", "OLLAMA",
            server.url("/").toString().trimEnd('/'), null, "nomic-embed-text", com.rokid.context.ai.EndpointRepository.keyRef("ol"), "EMBEDDINGS", 1))
        AiGraph.get(ctx).bindings.set(com.rokid.context.ai.RoleBinding(com.rokid.context.ai.Role.EMBED, "ol", "nomic-embed-text")).getOrThrow()
    }

    /** Phase 4 rule: paused (phone ⏸), nothing goes to the AI provider — not even the binding probe. */
    @Test fun pausedSendsNothingToTheEmbeddingEndpoint() = runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            bindOllama(server)
            com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
            assertTrue(TestListenableWorkerBuilder<EmbeddingWorker>(ctx).build().doWork() is ListenableWorker.Result.Success)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun aRebuildRequestedWhilePausedIsKeptPending() = runBlocking {
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
        val worker = TestListenableWorkerBuilder<EmbeddingWorker>(ctx).setInputData(androidx.work.workDataOf(EmbeddingWorker.KEY_REBUILD to true)).build()
        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertTrue(PrefsIndexClock(ctx).rebuildPending())
    }

    @Test fun resumingThePipelineQueuesTheIndexWork() {
        com.rokid.context.work.OrchestrationScheduler(ctx).resumePipeline()
        assertEquals(1, queued().size)
    }

    @Test fun prefsClockKeepsItsStateAndExposesUnstable() = runBlocking {
        val clock = PrefsIndexClock(ctx)
        assertTrue(!clock.unstable()); assertTrue(!PrefsIndexClock(ctx).observeUnstable().first())
        clock.markCanary(42); clock.setRebuildPending(true); clock.consumeRebuild("w1")
        clock.recordDiscard(DiscardRecord("A", 7, IndexMaintenance.MAX_DISCARD_STREAK))
        val again = PrefsIndexClock(ctx)
        assertEquals(42L, again.lastCanaryAt()); assertTrue(again.rebuildPending()); assertEquals("w1", again.consumedRebuild())
        assertEquals(DiscardRecord("A", 7, 3), again.lastDiscard())
        assertTrue(again.unstable()); assertTrue(again.observeUnstable().first())
        again.recordDiscard(null)
        assertTrue(!PrefsIndexClock(ctx).unstable())
    }

    @Test fun moreWorkContinuesInANewRun() = runBlocking {
        assertTrue(EmbeddingWorker.execute(ctx, attempt = 0) { IndexMaintenance.Outcome.MORE } is ListenableWorker.Result.Success)
        assertEquals(1, queued().size)
        assertTrue(EmbeddingWorker.execute(ctx, attempt = 0) { IndexMaintenance.Outcome.DONE } is ListenableWorker.Result.Success)
    }

    /** Phase 2 Task 4 carry: the legacy import is followed by embedding in the background. */
    @Test fun legacyImportQueuesTheEmbeddingWork() = runBlocking {
        assertTrue(TestListenableWorkerBuilder<LegacyImportWorker>(ctx).build().doWork() is ListenableWorker.Result.Success)
        assertEquals(1, queued().size)
        assertTrue(queued().single().constraints.requiresBatteryNotLow())
    }

    @Test fun pausingCancelsTheIndexWorkAndKeepsARequestedRebuild() {
        EmbeddingWorker.enqueue(ctx, rebuild = true)
        com.rokid.context.work.OrchestrationScheduler(ctx).pausePipeline()
        assertTrue(queued().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue("the Rebuild is not lost", PrefsIndexClock(ctx).rebuildPending())
    }

    @Test fun pausingCancelsANormalIndexRun() {
        EmbeddingWorker.enqueue(ctx)
        com.rokid.context.work.OrchestrationScheduler(ctx).pausePipeline()
        assertTrue(queued().all { it.state == WorkInfo.State.CANCELLED })
        assertTrue(!PrefsIndexClock(ctx).rebuildPending())
    }

    /** T19: the user's Rebuild resets the drift streak when it is asked for, so "index unstable" clears even while paused. */
    @Test fun aRebuildRequestClearsTheUnstableStateAndStaysQueuedWhilePaused() = runBlocking {
        com.rokid.context.work.OrchestrationScheduler(ctx).pausePipeline()
        PrefsIndexClock(ctx).recordDiscard(DiscardRecord("A", 7, IndexMaintenance.MAX_DISCARD_STREAK))
        assertTrue(PrefsIndexClock(ctx).observeUnstable().first())
        EmbeddingWorker.enqueue(ctx, rebuild = true)
        assertTrue("streak reset", !PrefsIndexClock(ctx).unstable())
        assertTrue(!PrefsIndexClock(ctx).observeUnstable().first())
        assertTrue("queued, not lost", PrefsIndexClock(ctx).rebuildPending())
    }

    @Test fun aRebuildRequestRecordsItsOwnIdAsThePendingToken() {
        EmbeddingWorker.enqueue(ctx, rebuild = true)
        assertEquals(queued().single().id.toString(), PrefsIndexClock(ctx).pendingRebuildToken())
    }
}
