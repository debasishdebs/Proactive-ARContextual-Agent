package com.rokid.context.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Phase 5 T20 carry: frames chosen before a Delete all are never queued after it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HistoricalBackfillTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        QueueRuntimeStore.clearAll(ctx)
    }

    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun aWipeAfterTheFramesWereChosenQueuesNothing() = runBlocking {
        val jpeg = File(ctx.filesDir, "f1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        MemoryGraph.get(ctx).frames.add(FrameEntity("f1", 1, jpeg.path, "rokid_glasses"))
        MemoryGraph.get(ctx).frames.add(FrameEntity("s1", 2, "", "phone_screenshot")) // what a backfill's purge drops
        val stale = MemoryWipe.generation.get() - 1
        assertEquals(0, OrchestrationScheduler(ctx).enqueueHistoricalPhotoBackfill(generation = stale).queuedJobs)
        // Fix round 1: a stale run touches nothing, not even the purges.
        assertEquals(setOf("f1", "s1"), MemoryGraph.get(ctx).frames.all().map { it.id }.toSet())
        assertEquals(0, QueueRuntimeStore.snapshot(ctx).pendingByQueue[QueuePriority.SUMMARIZER] ?: 0)
        assertEquals(1, OrchestrationScheduler(ctx).enqueueHistoricalPhotoBackfill().queuedJobs)
    }
}
