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
        assertEquals(1, MemoryGraph.ensureLegacyImported(ctx)?.memories)
        assertEquals(null, MemoryGraph.ensureLegacyImported(ctx))
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

    @Test fun inboxFramesArchivedInTheSameMillisecondBothStoreWithDistinctFullIds() = runBlocking {
        val a = jpg("1760000000000_rokid_1111_a.jpg")
        val b = jpg("1760000000000_rokid_1111_b.jpg")
        assertTrue(RokidFrameInbox.ingestSinglePath(ctx, a))
        assertTrue(RokidFrameInbox.ingestSinglePath(ctx, b))
        assertEquals(setOf("1760000000000_rokid_1111_a", "1760000000000_rokid_1111_b"), PipelineRecords.allFrames(ctx).map { it.id }.toSet())
    }
}
