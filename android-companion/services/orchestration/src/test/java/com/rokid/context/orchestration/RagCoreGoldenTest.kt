package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.connectors.ContextSourceProvider
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.FusionStrategy
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.model.ModelImageInput
import com.rokid.context.orchestration.nudge.NudgeInputs
import com.rokid.context.prompt.PromptVars
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoSource
import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v0.9.8 slice A: the PromptVars of Ask and Nudge are byte-identical before and after the RagCore refactor. The goldens in
 * `src/test/resources/golden/ragcore` were captured from the pre-refactor code; a missing golden is written and fails the run once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RagCoreGoldenTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val now = 1_800_000_000_000L
    private val zone = ZoneOffset.UTC

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); TestAi.bindAll(TestAi.graph(ctx) { "{}" }) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private fun golden(name: String, vars: PromptVars, extra: String = "") {
        val actual = "question=${vars.question}\n---\nmemories=${vars.retrievedMemories}\n---\nrecent=${vars.recentContext}\n---\nsources=${vars.contextSources}\n---\n$extra"
        val f = File("src/test/resources/golden/ragcore/$name.txt")
        if (!f.exists()) { f.writeText(actual); throw AssertionError("golden $name captured; rerun") }
        assertEquals(f.readText(), actual)
    }

    private suspend fun seed() {
        val m = MemoryGraph.get(ctx).memories
        m.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, now - 3_600_000L, "Milk carton nearly empty"))
        m.add(LegacyImporter.toMemory("rokid_mem_2", "short_term", 0.8, now - 7_200_000L, "Bought  bread and milk at the corner shop"))
        m.add(LegacyImporter.toMemory("rokid_mem_3", "short_term", 0.7, now - 30 * 3_600_000L, "Parked the car on level 3"))
    }

    private fun hit(id: String, score: Double, stored: Boolean = true, cap: String? = null, capId: String? = null) =
        PhotoHit(id, PhotoSource.IMAGE, score, now - 1_000L, now - 500L, stored, cap != null, capId, cap, null)

    private fun service(photos: Boolean, hits: List<PhotoHit> = emptyList()) = AnswerService(
        MemoryGraph.get(ctx), AiGraph.get(ctx).runner, AiGraph.get(ctx).retriever, { RetrievalSettings() },
        sources = ContextSources { q -> listOf("event · Dentist · $q") }, zone = { zone },
        photosAllowed = { photos }, imageEndpointId = { "az" }, photos = { _, _ -> hits },
        photoImages = { ids -> ids.associateWith { ModelImageInput("image/jpeg", "AAAA") } },
    )

    private val photoHits = listOf(
        hit("f1", 0.9, cap = "A  red mug on a desk", capId = "rokid_mem_2"), hit("f2", 0.8, stored = false), hit("f3", 0.05),
    )

    @Test fun askRetrieveWithoutPhotos() = runBlocking<Unit> {
        seed(); val r = service(false).retrieve("milk?", now)
        golden("ask_plain", r.vars, "${r.memories.map { it.id }}|${r.photos}|${r.images}|${r.imageEndpointId}|${r.ids}")
    }

    @Test fun askRetrieveWithPhotos() = runBlocking<Unit> {
        seed(); val r = service(true, photoHits).retrieve("milk?", now)
        golden("ask_photos", r.vars, "${r.memories.map { it.id }}|${r.photos}|${r.images}|${r.imageEndpointId}|${r.ids}")
    }

    @Test fun askRetrieveFallback() = runBlocking<Unit> {
        seed(); val r = service(false).retrieve("zzzqqq", now)
        golden("ask_fallback", r.vars, "${r.memories.map { it.id }}|${r.photos}|${r.images}|${r.ids}")
    }

    @Test fun askRetrieveScoredHitAndFallback() = runBlocking<Unit> {
        seed()
        val hitR = service(false).retrieveScored("milk?", now)
        golden("scored_hit", hitR.retrieved.vars, "${hitR.retrieved.memories.map { it.id }}|${hitR.hits.map { it.memory.id }}|${hitR.mode}|${hitR.semanticUnavailable}|${hitR.fallback}|${hitR.lines}")
        val fb = service(false).retrieveScored("zzzqqq", now, notAfterMs = now - 4_000_000L)
        golden("scored_fallback", fb.retrieved.vars, "${fb.retrieved.memories.map { it.id }}|${fb.hits.size}|${fb.mode}|${fb.semanticUnavailable}|${fb.fallback}|${fb.lines}")
    }

    @Test fun nudgeBuild() = runBlocking<Unit> {
        seed()
        val sources = ContextSourceProvider { q, _ -> if (q == null) listOf("event · Dentist · Mon 19:00") else emptyList() }
        val inputs = NudgeInputs(MemoryGraph.get(ctx), sources) { ZoneId.of("Asia/Kolkata") }
        val p = inputs.build(now)
        golden("nudge_plain", p.vars, "${p.inputIds.sorted()}")
        val e = inputs.build(now, "extra line")
        golden("nudge_extra", e.vars, "${e.inputIds.sorted()}")
    }

    /** An all-null [RagParams] changes nothing: the settings come out as the clamped user settings, and a retrieval equals one with the defaults spelled out. */
    @Test fun allNullParamsEqualToday() = runBlocking<Unit> {
        val all = listOf(
            RetrievalSettings(), RetrievalSettings(fusion = FusionStrategy.Rrf(30), topK = 3), RetrievalSettings(fusion = FusionStrategy.CombMnz, mode = RetrievalMode.LEXICAL),
            RetrievalSettings(fusion = FusionStrategy.WeightedScore(0.3), recencyBoost = false, halfLifeDays = 9.0, minConfidence = 0.5), RetrievalSettings(topK = 99, halfLifeDays = Double.NaN),
        )
        for (st in all) assertEquals(st.clamped(), RagParams().over(st))
        seed()
        val core = RagCore(MemoryGraph.get(ctx), AiGraph.get(ctx).retriever, { RetrievalSettings() }, { zone })
        val srcs = listOf(RagSource.MemoryStore(), RagSource.RecentWindow(6 * 3_600_000L), RagSource.NudgeHistory(86_400_000L))
        for (q in listOf("milk?", "zzzqqq")) {
            val a = core.retrieve(RagQuery(q), srcs, RagParams(), now)
            val spelled = RagParams(RetrievalMode.HYBRID, RagFusion.WEIGHTED, 0.6, null, 8, null, 0.0, true, 3.0, 5_000, 10)
            assertEquals(a, core.retrieve(RagQuery(q), srcs, spelled, now))
        }
        assertEquals(RagParams::class.java.declaredFields.count { !java.lang.reflect.Modifier.isStatic(it.modifiers) }, RagParams.SCHEMA.size)
    }
}
