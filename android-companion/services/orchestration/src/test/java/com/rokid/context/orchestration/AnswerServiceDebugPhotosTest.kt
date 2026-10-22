package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.PauseIntervalEntity
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.core.model.Verbosity
import com.rokid.context.orchestration.nudge.NudgeEnvironment
import com.rokid.context.orchestration.nudge.NudgeHud
import com.rokid.context.model.ModelImageInput
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Task 14 seam (a): the Ask debug photo binding is the live Ask's gate, search, floor, lines and image loader. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnswerServiceDebugPhotosTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var allow = true
    private var searches = 0
    private var imageLoads = 0
    private var endpoint: String? = "az"

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); TestAi.bindAll(TestAi.graph(ctx) { "{}" }) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun hit(id: String, score: Double, stored: Boolean = true) = PhotoHit(id, PhotoSource.IMAGE, score, 1_000L, 1_000L, stored, false, null, null, null)

    private fun service(hits: List<PhotoHit>) = AnswerService(
        MemoryGraph.get(ctx), AiGraph.get(ctx).runner, AiGraph.get(ctx).retriever, { RetrievalSettings() },
        photosAllowed = { allow }, imageEndpointId = { endpoint },
        photos = { _, _ -> searches++; hits },
        photoImages = { ids -> imageLoads++; ids.associateWith { ModelImageInput("image/jpeg", "AAAA") } },
    )

    @Test fun switchOffNeverSearchesOrLoads() = runBlocking<Unit> {
        allow = false
        val p = service(listOf(hit("f1", 0.9))).askDebugPhotos()
        assertFalse(p.allowed())
        val r = p.recall("mug", 1) // even a direct recall re-checks the gate
        assertEquals(0, searches); assertEquals(0, imageLoads)
        assertTrue(r.lines.isEmpty()); assertNull(r.imageEndpointId); assertTrue(r.images().isEmpty()); assertEquals(0, imageLoads)
    }

    @Test fun allowedGivesLiveLinesAndImagesOnlyOnTheCheckedEndpoint() = runBlocking<Unit> {
        val p = service(listOf(hit("f1", 0.9), hit("f2", 0.1))).askDebugPhotos() // f2 is below the floor
        assertTrue(p.allowed())
        val r = p.recall("mug", 1)
        assertEquals(1, searches); assertEquals(0, imageLoads) // the images are lazy
        assertEquals(setOf("f1"), r.frameIds); assertEquals(setOf("frame:f1"), r.citeIds)
        assertTrue(r.lines.any { it.startsWith("id=frame:f1 ·") && it.contains("image=1") })
        assertEquals("az", r.imageEndpointId)
        assertEquals(1, r.images().size); assertEquals(1, imageLoads)
    }

    @Test fun noVisionEndpointMeansLinesWithoutImages() = runBlocking<Unit> {
        endpoint = null
        val r = service(listOf(hit("f1", 0.9))).askDebugPhotos().recall("mug", 1)
        assertEquals(setOf("f1"), r.frameIds); assertFalse(r.lines.any { it.contains("image=") })
        assertNull(r.imageEndpointId); assertTrue(r.images().isEmpty()); assertEquals(0, imageLoads)
    }

    @Test fun anExpiredPhotoGetsALineButNoImage() = runBlocking<Unit> {
        val r = service(listOf(hit("f1", 0.9, stored = false))).askDebugPhotos().recall("mug", 1)
        assertEquals(1, r.frameIds.size); assertNull(r.imageEndpointId); assertTrue(r.images().isEmpty())
    }

    // ---- the production binding (forApp): the REAL gate with its real sources ----

    @Test fun theProductionGateIsOffByDefaultOnWithTheSwitchAndPausedByAPause() = runBlocking<Unit> {
        NudgeEnvironment.hud = { NudgeHud(null, Verbosity.MEDIUM, java.time.ZoneOffset.UTC) }
        AppConfigStore.setPipelineEnabled(ctx, true)
        try {
            val gate = AnswerService.forApp(ctx, extractFirst = false).askDebugPhotos()
            assertFalse("the switch defaults to OFF", gate.allowed())
            AskPhotoPolicy.set(ctx, true)
            assertTrue(gate.allowed())
            val now = System.currentTimeMillis()
            MemoryGraph.get(ctx).db.pauseDao().insertInterval(PauseIntervalEntity("p1", "PHONE", now - 60_000, null))
            assertFalse("a pause blocks", gate.allowed())
            MemoryGraph.get(ctx).db.pauseDao().closeInterval("p1", now - 1)
            assertTrue(gate.allowed())
            AskPhotoPolicy.set(ctx, false)
            assertFalse(gate.allowed())
        } finally { NudgeEnvironment.hud = null; AskPhotoPolicy.set(ctx, false) }
    }
}
