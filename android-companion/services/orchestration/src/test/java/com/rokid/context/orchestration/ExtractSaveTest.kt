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
import org.junit.Assert.assertFalse
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
        val batch = ExtractSave.extract(ai.runner, listOf(photo("ex1"), photo("ex2"), photo("ex3")), CallClass.EXTRACT, com.rokid.context.orchestration.AllLive)
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
        ids.map { Fact("Mug in $it", "object", 0.9, emptyList(), listOf(it)) }, captions = ids.map { FrameCaption(it, "Counter $it") }, provenance = true, imagesDelivered = true,
    )

    // codex r10: a caption (or a trusted fact) whose photo was never proven delivered to the model is never saved as a memory
    @Test fun aResultWithoutDeliveryProofIsNeverSavedAsAMemory() = runBlocking<Unit> {
        claim(listOf("ex1"), "c1", abandonBeforeMs = 0)
        val r = ExtractSave.saveExtraction(ctx, "p_", output("ex1").copy(imagesDelivered = false), setOf("ex1"), mapOf("ex1" to "c1"), MemoryTier.SHORT, ReviewState.OK)
        assertEquals(ExtractSaveResult.Undelivered, r)
        assertTrue(db.memoryDao().all().isEmpty())
        assertEquals("EXTRACTING", db.frameStateDao().get("ex1")?.state) // the claim is untouched: the pipeline retries with backoff
        // captions without proof are refused even when the facts would go to review
        assertEquals(ExtractSaveResult.Undelivered, ExtractSave.saveExtraction(ctx, "p_", output("ex1").copy(imagesDelivered = false), setOf("ex1"), null, MemoryTier.SHORT, ReviewState.NEEDS_REVIEW))
        assertTrue(db.memoryDao().all().isEmpty())
    }

    @Test fun theExtractionCarriesTheRoutersDeliveryProof() = runBlocking<Unit> {
        val ai = TestAi.graph(ctx) { p -> reply(sentIds(p).single()) }
        TestAi.bindAll(ai)
        assertTrue(ExtractSave.extract(ai.runner, listOf(photo("ex1")), CallClass.EXTRACT, AllLive).output.imagesDelivered)
        assertEquals(setOf("ex1"), AllLive.checked.last())
    }

    // v18 WP11a: the model that answered is recorded with every fact and caption the save writes, in the save's transaction
    @Test fun theModelThatAnsweredIsRecordedWithEveryMemoryTheSaveWrites() = runBlocking<Unit> {
        val ai = TestAi.graph(ctx) { p -> reply(sentIds(p).single()) }
        TestAi.bindAll(ai)
        val batch = ExtractSave.extract(ai.runner, listOf(photo("ex1")), CallClass.EXTRACT, AllLive)
        assertEquals("gpt", batch.output.model); assertEquals(batch.promptVersion, batch.output.promptRev)
        claim(listOf("ex1"), "c1", abandonBeforeMs = 0)
        assertTrue(ExtractSave.saveExtraction(ctx, "p_", batch.output, setOf("ex1"), mapOf("ex1" to "c1"), MemoryTier.SHORT, ReviewState.OK) is ExtractSaveResult.Saved)
        val rows = db.modelAttributionDao().ofRefs("MEMORY", listOf("p_0", "p_c0")).associateBy { it.refId }
        assertEquals(setOf("p_0", "p_c0"), rows.keys); assertTrue(rows.values.all { it.model == "gpt" && it.promptRev == batch.promptVersion.toString() && it.modelVersion == null })
    }

    @Test fun anOutputWithNoKnownModelRecordsNothingAndClearsAnOlderRow() = runBlocking<Unit> {
        claim(listOf("ex1"), "c1", abandonBeforeMs = 0)
        ExtractSave.saveExtraction(ctx, "p_", output("ex1").copy(model = "old-model"), setOf("ex1"), mapOf("ex1" to "c1"), MemoryTier.SHORT, ReviewState.OK)
        assertEquals(2, db.modelAttributionDao().count())
        ExtractSave.saveExtraction(ctx, "p_", output("ex1"), setOf("ex1"), null, MemoryTier.SHORT, ReviewState.OK) // the same ids, written without a model name
        assertEquals(0, db.modelAttributionDao().count())
    }

    @Test fun frameSourceWritten() = runBlocking<Unit> {
        claim(listOf("ex1", "ex2"), "c1", abandonBeforeMs = 0)
        val out = ExtractOutput(
            listOf(Fact("Mug on the counter", "object", 0.9, emptyList(), listOf("ex1", "ex2"))),
            captions = listOf(FrameCaption("ex1", "Counter one"), FrameCaption("ex2", "Counter two")), provenance = true,imagesDelivered = true
        )
        val r = ExtractSave.saveExtraction(ctx, "p_", out, setOf("ex1", "ex2"), mapOf("ex1" to "c1", "ex2" to "c1"), MemoryTier.SHORT, ReviewState.OK)
        assertEquals(3, (r as ExtractSaveResult.Saved).memories.size)
        val src = db.frameSourceDao()
        assertEquals(setOf("ex1", "ex2"), src.forMemory("p_0").filter { it.role != "CARRIED" }.map { it.frameId }.toSet())
        assertEquals(listOf("ex1"), src.forMemory("p_c0").filter { it.role != "CARRIED" }.map { it.frameId })
        assertEquals(listOf("ex2"), src.forMemory("p_c1").filter { it.role != "CARRIED" }.map { it.frameId })
        assertTrue((src.forMemory("p_0").filter { it.role != "CARRIED" } + src.forMemory("p_c0").filter { it.role != "CARRIED" }).all { it.role == "FACT" })
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
        assertTrue(db.frameSourceDao().forMemory("old_0").filter { it.role != "CARRIED" }.isEmpty() && db.frameSourceDao().forMemory("old_c0").filter { it.role != "CARRIED" }.isEmpty())
        assertEquals("EXTRACTED", db.frameStateDao().get("ex1")!!.state)

        // And the other way round: a two-frame batch whose second claim is stale rolls back the first frame's CAS too.
        claim(listOf("ex2", "ex3"), "a", abandonBeforeMs = 0)
        val mixed = ExtractSave.saveExtraction(ctx, "mix_", output("ex2", "ex3"), setOf("ex2", "ex3"), mapOf("ex2" to "a", "ex3" to "zzz"), MemoryTier.SHORT, ReviewState.OK)
        assertEquals(ExtractSaveResult.StaleClaim, mixed)
        assertEquals(listOf("EXTRACTING", "EXTRACTING"), db.frameStateDao().byIds(listOf("ex2", "ex3")).map { it.state })
        assertTrue(MemoryGraph.get(ctx).memories.all().none { it.id.startsWith("mix_") })
    }

    private suspend fun useOldFactsSchema(ai: AiGraph) {
        val v = ai.prompts.active(com.rokid.context.ai.contract.PromptKey.VISION_EXTRACT)
        ai.prompts.save(com.rokid.context.ai.contract.PromptKey.VISION_EXTRACT,
            com.rokid.context.prompt.PromptDraft(v.template, com.rokid.context.ai.contract.DefaultSchemas.text(com.rokid.context.ai.contract.PromptKey.EXPLICIT_REMEMBER)), "old", false)
    }

    /** An edited prompt that kept the facts-only schema still extracts: single photos only, facts attributed to the photo sent. */
    @Test fun oldSchemaPromptStillSavesFactsForSinglePhotos() = runBlocking<Unit> {
        val ai = TestAi.graph(ctx) { p ->
            prompts += p
            val id = sentIds(p).first()
            """{"facts":[{"text":"Mug in $id","type":"object","confidence":0.9,"tags":[]}]}"""
        }
        TestAi.bindAll(ai)
        useOldFactsSchema(ai)
        assertFalse(ExtractSave.promptIsCurrent(ai.runner.activeSchema(com.rokid.context.ai.contract.PromptKey.VISION_EXTRACT)))
        val batch = ExtractSave.extract(ai.runner, listOf(photo("ex1"), photo("ex2")), CallClass.EXTRACT, com.rokid.context.orchestration.AllLive)
        assertEquals(2, prompts.size) // never a batch call
        assertTrue(prompts.all { sentIds(it).distinct().size == 1 })
        assertEquals(listOf(listOf("ex1"), listOf("ex2")), batch.output.facts.map { it.sourceFrameIds })
        assertTrue(batch.output.captions.isEmpty())
        val r = ExtractSave.saveExtraction(ctx, "old_", batch.output, setOf("ex1", "ex2"), null, MemoryTier.SHORT, ReviewState.OK)
        assertEquals(2, (r as ExtractSaveResult.Saved).memories.size)
        assertEquals(listOf("ex2"), db.frameSourceDao().forMemory("old_1").filter { it.role != "CARRIED" }.map { it.frameId })
    }

    @Test fun promptIsCurrentOnlyForTheProvenanceSchema() {
        assertTrue(ExtractSave.promptIsCurrent(com.rokid.context.ai.contract.DefaultSchemas.text(com.rokid.context.ai.contract.PromptKey.VISION_EXTRACT)))
        assertFalse(ExtractSave.promptIsCurrent(com.rokid.context.ai.contract.DefaultSchemas.text(com.rokid.context.ai.contract.PromptKey.EXPLICIT_REMEMBER)))
        assertFalse(ExtractSave.promptIsCurrent(null))
        assertFalse(ExtractSave.promptIsCurrent("not json"))
    }

    /** More than 3 photos are split into batches of at most 3 (the schema caps captions at 3). */
    @Test fun moreThanThreePhotosSplitIntoBatches() = runBlocking<Unit> {
        val ai = TestAi.graph(ctx) { p ->
            prompts += p
            val ids = sentIds(p).distinct()
            val facts = ids.joinToString(",") { """{"text":"Mug in $it","type":"object","confidence":0.9,"tags":[],"source_frame_ids":["$it"]}""" }
            val caps = ids.joinToString(",") { """{"frame_id":"$it","caption":"Counter $it"}""" }
            """{"facts":[$facts],"captions":[$caps]}"""
        }
        TestAi.bindAll(ai)
        val batch = ExtractSave.extract(ai.runner, (1..5).map { photo("ex$it") }, CallClass.EXTRACT, com.rokid.context.orchestration.AllLive)
        assertEquals(listOf(listOf("ex1", "ex2", "ex3"), listOf("ex4", "ex5")), prompts.map { sentIds(it).distinct() })
        assertEquals(5, batch.output.captions.size)
        assertTrue(batch.output.provenance)
    }

    /** Captions are the only text left once a photo is deleted: they are saved LONG-term whatever tier the facts get. */
    @Test fun captionsAreSavedLongTerm() = runBlocking<Unit> {
        ExtractSave.saveExtraction(ctx, "t_", output("ex1"), setOf("ex1"), null, MemoryTier.SHORT, ReviewState.OK)
        val byId = MemoryGraph.get(ctx).memories.all().associateBy { it.id }
        assertEquals(MemoryTier.SHORT, byId.getValue("t_0").tier)
        assertEquals(MemoryTier.LONG, byId.getValue("t_c0").tier)
    }
}
