package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.data.frames.RokidFrameInbox
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
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
class PipelineRecordsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setUp() = MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    @After fun tearDown() = MemoryGraph.resetForTests(null)

    private fun jpg(name: String) = File(ctx.filesDir, name).apply { writeBytes(byteArrayOf(1)) }.absolutePath
    private fun frame(id: String, ts: Long, path: String, source: String = "rokid_glasses", payload: String = "p") =
        FrameEntity(id = id, ts = ts, path = path, source = source, payload = payload)

    @Test fun framesRoundTripAndRejectNonRokidOrMissingPreview() = runBlocking {
        assertTrue(PipelineRecords.appendFrame(ctx, frame("f1", 10, jpg("a.jpg"))))
        assertFalse(PipelineRecords.appendFrame(ctx, frame("f2", 11, jpg("b.jpg"), source = "phone")))
        assertFalse(PipelineRecords.appendFrame(ctx, frame("f3", 12, "/missing.jpg")))
        assertEquals(listOf("f1"), PipelineRecords.recentFrames(ctx, 10).map { it.id })
        assertEquals(listOf("f1"), PipelineRecords.framesByIds(ctx, listOf("f1", "nope", " ")).map { it.id })
        assertTrue(PipelineRecords.framesByIds(ctx, listOf(" ")).isEmpty())
    }

    @Test fun appendKeepsPayloadOnOneLine() = runBlocking {
        PipelineRecords.appendFrame(ctx, frame("f1", 10, jpg("a.jpg"), payload = "a\tb\nc"))
        assertEquals("a b c", PipelineRecords.allFrames(ctx).single().payload)
    }

    // NB: Robolectric names filesDir after the test method, so the name must not contain "synthetic"/"screenshot".
    @Test fun purgeNonPhotoDeletesFakeFrameAndItsFile() = runBlocking {
        val synth = jpg("synthetic_1.jpg")
        PipelineRecords.appendFrame(ctx, frame("s1", 10, synth, payload = "synthetic"))
        PipelineRecords.appendFrame(ctx, frame("r1", 11, jpg("r.jpg"), payload = "real"))
        val r = PipelineRecords.purgeNonPhotoAndSynthetic(ctx)
        assertEquals(PipelineRecords.PurgeResult(kept = 1, dropped = 1, deletedFiles = 1), r)
        assertFalse(File(synth).exists())
    }

    @Test fun tracesUpsertNewestFirstAndPurgeSynthetic() = runBlocking {
        PipelineRecords.upsertTrace(ctx, "s1", "QUEUED", "a", updatedAt = 1)
        PipelineRecords.upsertTrace(ctx, "s2", "QUEUED", "b\nplaceholder", updatedAt = 2)
        PipelineRecords.upsertTrace(ctx, "s1", "TRUSTED", "c", updatedAt = 3)
        assertEquals(listOf("s1" to "TRUSTED", "s2" to "QUEUED"), PipelineRecords.recentTraces(ctx, 10).map { it.summaryId to it.state })
        assertEquals("b placeholder", PipelineRecords.recentTraces(ctx, 10)[1].note)
        PipelineRecords.purgeSyntheticTraces(ctx)
        assertEquals(listOf("s1"), PipelineRecords.recentTraces(ctx, 10).map { it.summaryId })
    }

    @Test fun firstUseTriggersTheLegacyImport() = runBlocking {
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_9\tshort_term\t0.8\t5\tImported before first use\n")
        assertEquals(listOf("rokid_mem_9"), PipelineRecords.recentMemories(ctx, 10).map { it.id })
    }

    @Test fun ensureLegacyImportedRunsImportOnlyOnce() = runBlocking {
        File(ctx.filesDir, "memory_records.log").writeText("rokid_mem_9\tshort_term\t0.8\t5\tOnce only\n")
        assertEquals(1, MemoryGraph.ensureLegacyImported(ctx, isMain = { false })?.memories) // P8 D5: the test thread is Main
        assertEquals(null, MemoryGraph.ensureLegacyImported(ctx, isMain = { false }))
        assertEquals(1, PipelineRecords.recentMemories(ctx, 10).size)
    }

    @Test fun memoriesAreNewestFirstAndSanitised() = runBlocking {
        PipelineRecords.appendMemory(ctx, "rokid_mem_1", "short_term", "a\tb\nc", 0.9, 1)
        PipelineRecords.appendMemory(ctx, "rokid_mem_2", "short_term", "second", 0.5, 2)
        val r = PipelineRecords.recentMemories(ctx, 10)
        assertEquals(listOf("rokid_mem_2", "rokid_mem_1"), r.map { it.id })
        assertEquals("a b c", r[1].text)
    }

    @Test fun purgeNonRokidKeepsOnlyRokidIds() = runBlocking {
        PipelineRecords.appendMemory(ctx, "other_1", "short_term", "x", 0.1, 1)
        PipelineRecords.appendMemory(ctx, "rokid_mem_3", "short_term", "y", 0.1, 2)
        PipelineRecords.purgeNonRokidMemories(ctx)
        assertEquals(listOf("rokid_mem_3"), PipelineRecords.recentMemories(ctx, 10).map { it.id })
    }

    @Test fun purgeLowSignalDropsReviewsAndMarkers() = runBlocking {
        PipelineRecords.appendMemory(ctx, "rokid_review_1", "review", "needs review", 0.1, 1)
        PipelineRecords.appendMemory(ctx, "rokid_mem_4", "short_term", "Metadata only frame", 0.1, 2)
        PipelineRecords.appendMemory(ctx, "rokid_mem_5", "short_term", "Milk carton nearly empty", 0.8, 3)
        PipelineRecords.purgeLowSignalMemories(ctx)
        assertEquals(listOf("rokid_mem_5"), PipelineRecords.recentMemories(ctx, 10).map { it.id })
    }

    @Test fun oneLineCollapsesEveryKindOfLineBreak() = runBlocking {
        PipelineRecords.upsertTrace(ctx, "s1", "QUEUED", "a\r\nb\u2028c\u0085d\t e", updatedAt = 1)
        assertEquals("a b c d e", PipelineRecords.recentTraces(ctx, 1).single().note)
    }

    /** Carry C2: the Phase 5 ids (rokid_mem_<job>_<i> facts, rokid_mem_<job> summaries) survive every legacy purge. */
    @Test fun phase5MemoryIdsSurviveTheLegacyPurges() = runBlocking {
        PipelineRecords.appendFact(ctx, "rokid_mem_abc_0", "f1", "Milk carton on the counter", "object", listOf("kitchen"), 0.9, MemoryTier.SHORT, ReviewState.OK, createdAt = 1)
        PipelineRecords.appendFact(ctx, "rokid_mem_abc_1", "f1", "Maybe a cat", "object", emptyList(), 0.3, MemoryTier.SHORT, ReviewState.NEEDS_REVIEW, createdAt = 2)
        PipelineRecords.appendFact(ctx, "rokid_mem_def", null, "A quiet morning in the kitchen", "summary", emptyList(), 0.8, MemoryTier.MEDIUM, ReviewState.OK, createdAt = 3)
        PipelineRecords.purgeNonRokidMemories(ctx)
        PipelineRecords.purgeLowSignalMemories(ctx)
        assertEquals(setOf("rokid_mem_abc_0", "rokid_mem_abc_1", "rokid_mem_def"), PipelineRecords.recentMemories(ctx, 10).map { it.id }.toSet())
    }

    /** Fix round 2: drops only this job's surplus fact rows; a sibling id differing at an underscore position survives. */
    @Test fun dropFactsFromRemovesOnlyThatJobsSurplusRows() = runBlocking {
        listOf("rokid_mem_ab_c_0", "rokid_mem_ab_c_1", "rokid_mem_ab_c_2", "rokid_mem_abxc_1", "rokid_mem_ab_cx2", "rokid_mem_ab_c").forEachIndexed { i, id ->
            PipelineRecords.appendFact(ctx, id, null, "fact $i", "note", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK, createdAt = i.toLong())
        }
        PipelineRecords.dropFactsFrom(ctx, "rokid_mem_ab_c_", keep = 1)
        assertEquals(setOf("rokid_mem_ab_c_0", "rokid_mem_abxc_1", "rokid_mem_ab_cx2", "rokid_mem_ab_c"), PipelineRecords.recentMemories(ctx, 10).map { it.id }.toSet())
    }

    /** A re-run with fewer photos drops the stale caption memories (and their frame_source rows) too. */
    @Test fun dropFactsFromAlsoDropsCaptionIds() = runBlocking {
        listOf("rokid_x_0", "rokid_x_1", "rokid_x_c0", "rokid_x_c1", "rokid_xc0", "rokid_x_cache").forEachIndexed { i, id ->
            PipelineRecords.appendFact(ctx, id, null, "fact $i", "note", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK, createdAt = i.toLong())
        }
        val dao = MemoryGraph.get(ctx).db.frameSourceDao()
        dao.insertAll(listOf(com.rokid.context.memory.db.FrameSourceEntity("rokid_x_c1", "f1", null, "FACT")))
        PipelineRecords.dropFactsFrom(ctx, "rokid_x_", keep = 1, keepCaptions = 1)
        assertEquals(setOf("rokid_x_0", "rokid_x_c0", "rokid_xc0", "rokid_x_cache"), PipelineRecords.recentMemories(ctx, 10).map { it.id }.toSet())
        assertTrue(dao.forMemory("rokid_x_c1").isEmpty())
    }

    @Test fun inboxFramesArchivedInTheSameMillisecondBothStoreWithDistinctFullIds() = runBlocking {
        val a = jpg("1760000000000_rokid_1111_a.jpg")
        val b = jpg("1760000000000_rokid_1111_b.jpg")
        assertTrue(RokidFrameInbox.ingestSinglePath(ctx, a) != null)
        assertTrue(RokidFrameInbox.ingestSinglePath(ctx, b) != null)
        assertEquals(setOf("1760000000000_rokid_1111_a", "1760000000000_rokid_1111_b"), PipelineRecords.allFrames(ctx).map { it.id }.toSet())
    }

    @Test fun aParentFolderNamedScreenshotsDoesNotHideRealPhotos() {
        assertTrue(PipelineRecords.isRealPhotoFrame(frame("f1", 1, "/x/Screenshots/rokid_1_a.jpg")))
        assertTrue(PipelineRecords.isRealPhotoFrame(frame("f2", 1, "/x/synthetic-data/rokid_1_b.jpg")))
        assertFalse(PipelineRecords.isRealPhotoFrame(frame("f3", 1, "/x/screenshot_1.jpg")))
        assertFalse(PipelineRecords.isRealPhotoFrame(frame("f4", 1, "/x/rokid_1_c.jpg", payload = "synthetic test")))
    }

    private suspend fun seedPipelineFrame(id: String, state: String = "EXTRACTED") {
        val db = MemoryGraph.get(ctx).db
        db.frameDao().insertIgnore(frame(id, 10, jpg("$id.jpg")))
        db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity(id, id, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1))
        db.frameStateDao().insertIgnore(listOf(com.rokid.context.memory.db.FrameStateEntity(id, state, 0, null, null, null, 1)))
    }

    /** Final review C1: exactly what MainActivity runs at start-up, over memories the keyframe pipeline saved. */
    @Test fun pipelineSavedFactsAndCaptionsSurviveTheStartupPurges() = runBlocking {
        seedPipelineFrame("f1")
        val prefix = com.rokid.context.work.frames.KeyframeHandle.factPrefix("0123456789ab-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val saved = ExtractSave.saveExtraction(
            ctx, prefix,
            com.rokid.context.ai.contract.ExtractOutput(
                listOf(com.rokid.context.ai.contract.Fact("A mug on the desk", "object", 0.9, emptyList(), listOf("f1"))),
                captions = listOf(com.rokid.context.ai.contract.FrameCaption("f1", "A desk with a mug")),
            ),
            setOf("f1"), claims = null, tier = MemoryTier.SHORT, reviewState = ReviewState.OK,
        )
        assertEquals(2, (saved as ExtractSaveResult.Saved).memories.size)
        // an earlier build's ids (kf_<claimId>_n) are kept too
        PipelineRecords.appendFact(ctx, "kf_old_0", "f1", "Keys by the door", "object", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK, createdAt = 5)

        com.rokid.context.data.runtime.QueueRuntimeStore.purgeSynthetic(ctx)
        PipelineRecords.purgeNonRokidFrames(ctx)
        PipelineRecords.purgeSyntheticTraces(ctx)
        PipelineRecords.purgeNonRokidMemories(ctx)
        com.rokid.context.orchestration.forget.FrameSweep.sweep(ctx)

        assertEquals(setOf(prefix + "0", prefix + "c0", "kf_old_0"), PipelineRecords.recentMemories(ctx, 10).map { it.id }.toSet())
        val g = MemoryGraph.get(ctx).db
        assertEquals(listOf("f1"), g.frameSourceDao().forMemory(prefix + "0").map { it.frameId })
        assertEquals("EXTRACTED", g.frameStateDao().get("f1")!!.state)
        assertEquals(listOf("f1"), PipelineRecords.allFrames(ctx).map { it.id })
        // and the low-signal purge (used by Reprocess) keeps them too
        PipelineRecords.purgeLowSignalMemories(ctx)
        assertEquals(3, PipelineRecords.recentMemories(ctx, 10).size)
        assertTrue(PipelineRecords.isPipelineMemoryId(prefix + "c0") && !prefix.startsWith("rokid_review_"))
        // dropFactsFrom still sees `<prefix><n>` and `<prefix>c<n>`
        PipelineRecords.dropFactsFrom(ctx, prefix, keep = 0, keepCaptions = 0)
        assertEquals(listOf("kf_old_0"), PipelineRecords.recentMemories(ctx, 10).map { it.id })
    }

    /** Final review I3: the legacy purges take the frame_state / frame_meta / frame_source rows with them (no orphan ends FAILED_FINAL). */
    @Test fun legacyPurgesLeaveNoFrameOrphans() = runBlocking {
        val db = MemoryGraph.get(ctx).db
        db.frameDao().insertIgnore(frame("s1", 10, jpg("synthetic_s1.jpg"), source = "other_source"))
        db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity("s1", "s1", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1))
        db.frameStateDao().insertIgnore(listOf(com.rokid.context.memory.db.FrameStateEntity("s1", "PENDING", 0, null, null, null, 1)))
        db.frameSourceDao().insertAll(listOf(com.rokid.context.memory.db.FrameSourceEntity("m1", "s1", null, "FACT")))
        // Task 9: the manual photo purge leaves keyframes (frame_meta) alone, so the orphan cleanup is shown with the non-Rokid purge.
        PipelineRecords.purgeNonRokidFrames(ctx)
        assertEquals(null, db.frameStateDao().get("s1")); assertEquals(null, db.frameMetaDao().get("s1"))
        assertTrue(db.frameSourceDao().forMemory("m1").isEmpty())

        PipelineRecords.appendFact(ctx, "rokid_review_1", "f9", "needs review", "note", emptyList(), 0.1, MemoryTier.SHORT, ReviewState.NEEDS_REVIEW, createdAt = 1)
        db.frameSourceDao().insertAll(listOf(com.rokid.context.memory.db.FrameSourceEntity("rokid_review_1", "f9", null, "FACT")))
        PipelineRecords.purgeLowSignalMemories(ctx)
        assertTrue("a purged memory no longer pins a photo", db.frameSourceDao().forMemory("rokid_review_1").isEmpty())
    }

    /** Final review I3: Reprocess puts a frame back to PENDING only when it has no memory left and was not forgotten. */
    @Test fun requeueMovesOnlyEmptyUnforgottenFramesToPending() = runBlocking {
        val db = MemoryGraph.get(ctx).db
        for (id in listOf("e1", "e2", "e3", "e4")) seedPipelineFrame(id)
        PipelineRecords.appendFact(ctx, "rokid_kf_c_0", "e2", "Keeps", "note", emptyList(), 0.9, MemoryTier.SHORT, ReviewState.OK, createdAt = 1)
        db.openHelper.writableDatabase.execSQL("INSERT INTO forget_tombstone(frameId, createdAt) VALUES ('e3', 1)")
        db.openHelper.writableDatabase.execSQL("UPDATE frame_state SET attempts = 3, state = 'FAILED_FINAL' WHERE frameId = 'e4'")
        assertEquals(2, PipelineRecords.requeueFramesWithoutMemories(ctx, listOf("e1", "e2", "e3", "e4"), nowMs = 99))
        assertEquals("PENDING", db.frameStateDao().get("e1")!!.state)
        assertEquals("EXTRACTED", db.frameStateDao().get("e2")!!.state)
        assertEquals("EXTRACTED", db.frameStateDao().get("e3")!!.state)
        assertEquals(0, db.frameStateDao().get("e4")!!.attempts)
        assertEquals("PENDING", db.frameStateDao().get("e4")!!.state)
    }
}
