package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.ExtractOutput
import com.rokid.context.ai.contract.Fact
import com.rokid.context.ai.contract.FrameCaption
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameStateEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.model.CallClass
import com.rokid.context.model.ModelImageInput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spec P2.1: the multi-photo call falls back to singles; the save writes memories + frame_source + the claim CAS atomically. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExtractSaveTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val prompts = mutableListOf<String>()
    private val db get() = MemoryGraph.get(ctx).db

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
    }

    @After fun tearDown() {
        AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null)
    }

    private fun sentIds(p: String) = Regex("frame_id: (ex\\d)").findAll(p).map { it.groupValues[1] }.toList()
    private fun photo(id: String) = ExtractPhoto(id, "ts=1", ModelImageInput("image/jpeg", "AAAA"))
    private fun reply(id: String) =
        """{"facts":[{"text":"Mug in $id","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$id"]}],"captions":[{"frame_id":"$id","caption":"Counter $id"}]}"""

    @Test fun batchFallsBackToSingles() = runBlocking<Unit> {
        // The batch of 3 comes back without captions (INVALID_OUTPUT, also after the runner's own retry); each single is fine.
        val ai = TestAi.graph(ctx) { p ->
            prompts += p
            val ids = sentIds(p)
            if (ids.size > 1) """{"facts":[],"captions":[]}""" else reply(ids.single())
        }
        TestAi.bindAll(ai)
        val batch = ExtractSave.extract(ai.runner, listOf(photo("ex1"), photo("ex2"), photo("ex3")), CallClass.EXTRACT)
        assertEquals(listOf(listOf("ex1", "ex2", "ex3"), listOf("ex1", "ex2", "ex3"), listOf("ex1"), listOf("ex2"), listOf("ex3")), prompts.map(::sentIds).map { it.distinct() })
        assertEquals(listOf("Mug in ex1", "Mug in ex2", "Mug in ex3"), batch.output.facts.map { it.text })
        assertEquals(listOf("ex1", "ex2", "ex3"), batch.output.captions.map { it.frameId })
        assertTrue(batch.output.provenance)
    }

    private fun claim(frameIds: List<String>, claimId: String, abandonBeforeMs: Long) = runBlocking {
        db.frameStateDao().insertIgnore(frameIds.map { FrameStateEntity(it, "PENDING", 0, null, null, null, 0) })
        db.frameStateDao().claim(frameIds, claimId, 1_000, abandonBeforeMs)
    }

    private fun output(vararg ids: String) = ExtractOutput(
        ids.map { Fact("Mug in $it", "object", 0.9, emptyList(), listOf(it)) }, captions = ids.map { FrameCaption(it, "Counter $it") }, provenance = true,
    )

    @Test fun frameSourceWritten() = runBlocking<Unit> {
        claim(listOf("ex1", "ex2"), "c1", abandonBeforeMs = 0)
        val out = ExtractOutput(
            listOf(Fact("Mug on the counter", "object", 0.9, emptyList(), listOf("ex1", "ex2"))),
            captions = listOf(FrameCaption("ex1", "Counter one"), FrameCaption("ex2", "Counter two")), provenance = true,
        )
        val r = ExtractSave.saveExtraction(ctx, "p_", out, setOf("ex1", "ex2"), mapOf("ex1" to "c1", "ex2" to "c1"), MemoryTier.SHORT, ReviewState.OK)
        assertEquals(3, (r as ExtractSaveResult.Saved).memories.size)
        val src = db.frameSourceDao()
        assertEquals(setOf("ex1", "ex2"), src.forMemory("p_0").map { it.frameId }.toSet())
        assertEquals(listOf("ex1"), src.forMemory("p_c0").map { it.frameId })
        assertEquals(listOf("ex2"), src.forMemory("p_c1").map { it.frameId })
        assertTrue((src.forMemory("p_0") + src.forMemory("p_c0")).all { it.role == "FACT" })
        assertEquals(listOf("EXTRACTED", "EXTRACTED"), db.frameStateDao().byIds(listOf("ex1", "ex2")).map { it.state })
        assertEquals(setOf("p_0", "p_c0", "p_c1"), MemoryGraph.get(ctx).memories.all().map { it.id }.toSet())
    }

    @Test fun staleClaimCommitDropped() = runBlocking<Unit> {
        claim(listOf("ex1"), "old", abandonBeforeMs = 0)
        // The 5-minute abandon rule: the second attempt retakes the frame while the first is still in flight.
        assertEquals(1, db.frameStateDao().claim(listOf("ex1"), "new", 2_000, abandonBeforeMs = 1_500).size)
        val newer = ExtractSave.saveExtraction(ctx, "new_", output("ex1"), setOf("ex1"), mapOf("ex1" to "new"), MemoryTier.SHORT, ReviewState.OK)
        assertTrue(newer is ExtractSaveResult.Saved)
        // The old attempt finishes last: its CAS updates 0 rows, so nothing of it lands.
        val older = ExtractSave.saveExtraction(ctx, "old_", output("ex1"), setOf("ex1"), mapOf("ex1" to "old"), MemoryTier.SHORT, ReviewState.OK)
        assertEquals(ExtractSaveResult.StaleClaim, older)
        assertEquals(setOf("new_0", "new_c0"), MemoryGraph.get(ctx).memories.all().map { it.id }.toSet())
        assertTrue(db.frameSourceDao().forMemory("old_0").isEmpty() && db.frameSourceDao().forMemory("old_c0").isEmpty())
        assertEquals("EXTRACTED", db.frameStateDao().get("ex1")!!.state)

        // And the other way round: a two-frame batch whose second claim is stale rolls back the first frame's CAS too.
        claim(listOf("ex2", "ex3"), "a", abandonBeforeMs = 0)
        val mixed = ExtractSave.saveExtraction(ctx, "mix_", output("ex2", "ex3"), setOf("ex2", "ex3"), mapOf("ex2" to "a", "ex3" to "zzz"), MemoryTier.SHORT, ReviewState.OK)
        assertEquals(ExtractSaveResult.StaleClaim, mixed)
        assertEquals(listOf("EXTRACTING", "EXTRACTING"), db.frameStateDao().byIds(listOf("ex2", "ex3")).map { it.state })
        assertTrue(MemoryGraph.get(ctx).memories.all().none { it.id.startsWith("mix_") })
    }
}
