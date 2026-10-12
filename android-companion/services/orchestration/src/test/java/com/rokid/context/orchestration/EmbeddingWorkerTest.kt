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

    /** Carried from Task 8: a dimension mismatch is a config failure. It is recorded and the work stops: no retry, no continuation. */
    @Test fun aConfigFailureStopsWithoutRetryOrContinuation() = runBlocking {
        var runs = 0
        val r = EmbeddingWorker.execute(ctx, attempt = 0) { runs++; throw IndexConfigException("vector has 3 dimensions, space expects 2") }
        assertTrue(r is ListenableWorker.Result.Failure)
        assertEquals(1, runs)
        assertTrue("nothing re-queued", queued().isEmpty())
    }

    @Test fun anOtherFailureRetriesABoundedNumberOfTimes() = runBlocking {
        assertTrue(EmbeddingWorker.execute(ctx, attempt = 0) { throw java.io.IOException("offline") } is ListenableWorker.Result.Retry)
        assertTrue(EmbeddingWorker.execute(ctx, attempt = 5) { throw java.io.IOException("offline") } is ListenableWorker.Result.Failure)
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
}
