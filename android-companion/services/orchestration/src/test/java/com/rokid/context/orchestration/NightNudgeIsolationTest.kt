package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.orchestration.nudge.NudgeInputs
import com.rokid.context.orchestration.nudge.NudgeTrigger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 9 fix round 2: a night (BACKFILL) describe never starts the nudge backstop, and old-photo memories never reach the NUDGE prompt. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NightNudgeIsolationTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) }
    @After fun tearDown() { graph.db.close(); MemoryGraph.resetForTests(null) }

    private fun nudgeWork() = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(NudgeTrigger.WORK).get().filter { !it.state.isFinished }
    private val saved = listOf(SavedMemory("rokid_kf_x_c0", listOf("f"), null))

    @Test fun nightSaveRunsEmbeddingsButNoNudgeBackstop() = runBlocking {
        val r = ExtractFollowUp.afterSave(ctx, MemoryWipe.generation.get(), saved, trusted = true, confidence = 1.0, personaSnapshot = "", nudge = false)
        assertEquals(ExtractFollowUp.Result.TRUSTED, r)
        assertTrue("no nudge work after a night describe", nudgeWork().isEmpty())
        assertTrue("indexing still runs", WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(EmbeddingWorker.NAME).get().isNotEmpty())
    }

    /** Regression: a live (fresh) extraction triggers the backstop exactly as before. */
    @Test fun liveSaveStillTriggersTheNudgeBackstop() = runBlocking {
        ExtractFollowUp.afterSave(ctx, MemoryWipe.generation.get(), saved, trusted = true, confidence = 1.0, personaSnapshot = "")
        assertEquals(1, nudgeWork().size)
    }

    private suspend fun memory(id: String, frameId: String?, createdAt: Long) =
        graph.db.memoryDao().insert(MemoryEntity(id, frameId, "text $id", "caption", "", MemoryTier.LONG, 1.0, ReviewState.OK, false, createdAt, false, "text $id"))

    @Test fun nudgeInputsSkipMemoriesOfOldPhotos() = runBlocking {
        // fresh: captured 10 min ago, saved now. old: captured a week ago, described tonight. noframe: no photo. late: captured 8 h before its memory.
        for ((id, capturedAt) in listOf("freshF" to now - 10 * 60_000L, "oldF" to now - 7 * 24 * hour, "lateF" to now - 8 * hour))
            graph.db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.9f, null, null, null, "sp", capturedAt))
        memory("fresh", "freshF", now - hour); memory("old", "oldF", now - hour); memory("late", "lateF", now - hour); memory("noframe", null, now - hour)
        val p = NudgeInputs(graph, com.rokid.context.connectors.ContextSourceProvider { _, _ -> emptyList() }).build(now)
        assertEquals(setOf("fresh", "noframe"), p.inputIds)
        assertFalse(p.vars.recentContext.contains("id=old "))
    }
}
