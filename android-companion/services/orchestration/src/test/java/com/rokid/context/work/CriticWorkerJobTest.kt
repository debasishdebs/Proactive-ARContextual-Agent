package com.rokid.context.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.PipelineRecords
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Preflight F10: the periodic scan finds facts by reviewState, not by a "review" type. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CriticWorkerJobTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        // The scan also starts a drain (enqueueQueueDrainNow), which runs on its own thread and can claim and re-write a
        // reprocess row while the test marks it done. These tests pin the scan's queueing only, so the drain stays paused.
        com.rokid.context.data.runtime.AppConfigStore.setPipelineEnabled(ctx, false)
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun fact(id: String, text: String, state: ReviewState) = runBlocking {
        PipelineRecords.appendFact(ctx, id, "f1", text, "object", emptyList(), 0.3, MemoryTier.SHORT, state)
    }

    private fun scan() = runBlocking { TestListenableWorkerBuilder<CriticWorkerJob>(ctx).build().doWork() }
    private fun reprocessJobs() = QueueRuntimeStore.recent(ctx, 10).filter { it.queue == QueuePriority.PRIORITY_REPROCESS }

    @Test fun periodicScanQueuesAReprocessForFactsAwaitingReview() {
        fact("rokid_mem_a_0", "Maybe a cat", ReviewState.NEEDS_REVIEW)
        fact("rokid_mem_b_0", "A red mug", ReviewState.OK)
        scan()
        val job = reprocessJobs().single()
        assertEquals(listOf("rokid_mem_a_0"), QueuePayloads.ids(QueuePayloads.parse(job.payload), "source_ids_csv"))
        // Phase 7: ids only, never memory text.
        assertTrue(!job.payload.contains("Maybe a cat") && !job.payload.contains("A red mug"))
    }

    /** Fix M4: no second reprocess while one for the same facts is still pending (the scan runs every 3 h). */
    @Test fun periodicScanDoesNotQueueASecondReprocessWhileOneIsPending() {
        fact("rokid_mem_a_0", "Maybe a cat", ReviewState.NEEDS_REVIEW)
        scan()
        scan()
        assertEquals(1, reprocessJobs().size)
    }

    @Test fun periodicScanSkipsFactsTheDrainAlreadyQueuedForReprocess() {
        fact("rokid_mem_a_0", "Maybe a cat", ReviewState.NEEDS_REVIEW)
        QueueRuntimeStore.enqueue(ctx, QueuePriority.PRIORITY_REPROCESS, QueuePayloads.reprocess("Critic low confidence=0.30", listOf("rokid_mem_a_0"), listOf("f1")), personaSnapshot = "p")
        scan()
        assertEquals(1, reprocessJobs().size)
    }

    @Test fun periodicScanQueuesAgainOnceThePendingReprocessFinished() {
        fact("rokid_mem_a_0", "Maybe a cat", ReviewState.NEEDS_REVIEW)
        scan()
        QueueRuntimeStore.markSucceeded(ctx, reprocessJobs().single().id)
        scan()
        assertEquals(2, reprocessJobs().size)
    }

    @Test fun periodicScanQueuesNothingWhenNoFactAwaitsReview() {
        fact("rokid_mem_b_0", "A red mug", ReviewState.OK)
        scan()
        assertTrue(reprocessJobs().isEmpty())
    }
}
