package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetActionsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { graph.db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, frameId: String? = null) =
        graph.db.memoryDao().insert(MemoryEntity(id, frameId, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))

    @Test fun forgetMomentDeletesTheJpegAndRefreshes() = runBlocking {
        val jpg = File(ctx.filesDir, "rokid_archive/m.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("f1", 1, jpg.absolutePath, "rokid_glasses"))
        mem("m1", "f1")
        var refreshed = 0
        assertTrue(ForgetActions.forgetMoment(ctx, "f1") { refreshed++ })
        assertFalse(jpg.exists())
        assertEquals(1, refreshed)
    }

    @Test fun invalidatedDescendantsQueueTheReSummarise() = runBlocking {
        mem("A"); mem("B")
        graph.db.forgetDao().insertEdges(listOf(MemorySourceEntity("B", "A")))
        assertTrue(ForgetActions.forgetFact(ctx, "A"))
        assertEquals(1, WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ResummarizeWorker.NAME).get().size)
    }

    @Test fun anUnknownIdIsNotAnError() = runBlocking {
        assertTrue(ForgetActions.forgetFact(ctx, "nope"))
        assertTrue(WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(ResummarizeWorker.NAME).get().isEmpty())
    }

    /** Fence: a fact extracted from a frame forgotten while the model was answering is never written. */
    @Test fun aFactForAForgottenFrameIsDropped() = runBlocking {
        graph.db.frameDao().insertIgnore(FrameEntity("f1", 1, "/x.jpg", "rokid_glasses"))
        mem("m1", "f1")
        assertTrue(ForgetActions.forgetMoment(ctx, "f1"))
        assertFalse(com.rokid.context.orchestration.PipelineRecords.appendFact(ctx, "rokid_mem_j_0", "f1", "Late fact", "object", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK))
        assertEquals(0, graph.memories.all().size)
    }
}
