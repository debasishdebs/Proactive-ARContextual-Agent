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
import com.rokid.context.work.budget.PhoneThermal
import com.rokid.context.work.budget.ThermalStatusProvider
import com.rokid.context.work.frames.KeyframeGraph
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
        // The backfill signals the real keyframe pipeline (Dispatchers.Default), which would legitimately claim a requeued PENDING frame
        // (PENDING -> EXTRACTING) before the assertions read it. Latch SEVERE so background extraction stays off: the test then sees PENDING.
        KeyframeGraph.resetForTests()
        KeyframeGraph.thermal = ThermalStatusProvider { PhoneThermal.SEVERE }
    }

    @After fun tearDown() {
        KeyframeGraph.resetForTests(); KeyframeGraph.thermal = ThermalStatusProvider { PhoneThermal.NONE }
        MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

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

    /** Final review I3: "Reprocess old photos" rebuilds what its purge emptied: such frames go back to PENDING, good ones stay as they are. */
    @Test fun reprocessRequeuesFramesWhoseLowSignalMemoriesWerePurged() = runBlocking {
        val g = MemoryGraph.get(ctx); val db = g.db
        for (id in listOf("bad", "good")) {
            val jpeg = File(ctx.filesDir, "$id.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            g.frames.add(FrameEntity(id, id.length.toLong(), jpeg.path, "rokid_glasses"))
            db.frameStateDao().insertIgnore(listOf(com.rokid.context.memory.db.FrameStateEntity(id, "EXTRACTED", 2, "x", null, null, 1)))
        }
        com.rokid.context.orchestration.PipelineRecords.appendFact(ctx, "rokid_kf_a_0", "bad", "Metadata only frame", "note", emptyList(), 0.5,
            com.rokid.context.memory.db.MemoryTier.SHORT, com.rokid.context.memory.db.ReviewState.OK)
        com.rokid.context.orchestration.PipelineRecords.appendFact(ctx, "rokid_kf_b_0", "good", "A red mug on the desk", "object", emptyList(), 0.9,
            com.rokid.context.memory.db.MemoryTier.SHORT, com.rokid.context.memory.db.ReviewState.OK)
        db.frameSourceDao().insertAll(listOf(com.rokid.context.memory.db.FrameSourceEntity("rokid_kf_a_0", "bad", null, "FACT"), com.rokid.context.memory.db.FrameSourceEntity("rokid_kf_b_0", "good", null, "FACT")))
        OrchestrationScheduler(ctx).enqueueHistoricalPhotoBackfill()
        assertEquals("PENDING", db.frameStateDao().get("bad")!!.state); assertEquals(0, db.frameStateDao().get("bad")!!.attempts)
        assertEquals("EXTRACTED", db.frameStateDao().get("good")!!.state)
        assertEquals(setOf("rokid_kf_b_0"), g.memories.all().map { it.id }.toSet())
    }
}
