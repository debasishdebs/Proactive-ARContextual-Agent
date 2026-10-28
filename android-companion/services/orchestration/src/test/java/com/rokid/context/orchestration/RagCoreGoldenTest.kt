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
import kotlinx.serialization.json.put
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

    // ---- v0.9.8 slice B: the default pipelines' compiled plans give the very same goldens as the built-in flows ----

    private fun planOf(p: com.rokid.context.ai.canvas.Pipeline) = (com.rokid.context.ai.canvas.FlowPlan.compile(p) as com.rokid.context.ai.canvas.FlowPlan.Compiled.Ok).plan
    private val queryPlan get() = planOf(com.rokid.context.ai.canvas.DefaultPipelines.defaultQuery())

    @Test fun defaultQueryPipelineGivesTheAskGoldens() = runBlocking<Unit> {
        seed()
        val a = service(false).retrieve("milk?", now, queryPlan)
        golden("ask_plain", a.vars, "${a.memories.map { it.id }}|${a.photos}|${a.images}|${a.imageEndpointId}|${a.ids}")
        val b = service(true, photoHits).retrieve("milk?", now, queryPlan)
        golden("ask_photos", b.vars, "${b.memories.map { it.id }}|${b.photos}|${b.images}|${b.imageEndpointId}|${b.ids}")
        val c = service(false).retrieve("zzzqqq", now, queryPlan)
        golden("ask_fallback", c.vars, "${c.memories.map { it.id }}|${c.photos}|${c.images}|${c.ids}")
    }

    @Test fun defaultNudgePipelineGivesTheNudgeGoldens() = runBlocking<Unit> {
        seed()
        val sources = ContextSourceProvider { q, _ -> if (q == null) listOf("event · Dentist · Mon 19:00") else emptyList() }
        val inputs = NudgeInputs(MemoryGraph.get(ctx), sources) { ZoneId.of("Asia/Kolkata") }
        val plan = planOf(com.rokid.context.ai.canvas.DefaultPipelines.defaultNudge())
        val p = inputs.build(now, plan = plan)
        golden("nudge_plain", p.vars, "${p.inputIds.sorted()}")
        val e = inputs.build(now, "extra line", plan = plan)
        golden("nudge_extra", e.vars, "${e.inputIds.sorted()}")
    }

    @Test fun thePlansMeanTheSourcesAndParamsOfToday() {
        assertEquals(listOf(RagSource.MemoryStore(), RagSource.PhotoStore()), queryPlan.askSources())
        assertEquals(RagParams(), queryPlan.ragParams())
        val n = planOf(com.rokid.context.ai.canvas.DefaultPipelines.defaultNudge())
        assertEquals(listOf(RagSource.RecentWindow(6 * 3_600_000L), RagSource.NudgeHistory(24 * 3_600_000L)), n.nudgeSources(false))
        assertEquals(RagSource.PhotoStore(), n.nudgeSources(true).last())
        val base = RagParams(maxRecent = 10, maxChars = 1_000_000)
        assertEquals(base, n.ragParams(base))
        // a pipeline's own fields replace the base's, the rest stay
        val tuned = queryPlan.copy(rag = kotlinx.serialization.json.buildJsonObject { put("topK", 3); put("mode", "LEXICAL"); put("recencyBoost", false) })
        assertEquals(RagParams(mode = RetrievalMode.LEXICAL, topK = 3, recencyBoost = false), tuned.ragParams())
    }

    /** The canvas `rag.retrieve` schema (the ai module cannot see [RagParams]) names the same fields, types, ranges and options as [RagParams.SCHEMA]. */
    @Test fun theNodeSchemaMirrorsRagParamsSchema() {
        val node = com.rokid.context.ai.canvas.RagRetrieveParams.schema.specs.associateBy { it.name }
        assertEquals(RagParams.SCHEMA.map { it.name }, com.rokid.context.ai.canvas.RagRetrieveParams.ragNames)
        for (f in RagParams.SCHEMA) {
            val s = node.getValue(f.name)
            assertEquals(f.name, f.type, s.kind.wire)
            assertEquals(f.name, f.min, s.min); assertEquals(f.name, f.max, s.max); assertEquals(f.name, f.options, s.options)
        }
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

    // ---- slice A review fixes ----

    private fun meta(vararg ids: String) = runBlocking {
        ids.forEach { MemoryGraph.get(ctx).db.frameMetaDao().upsert(com.rokid.context.memory.db.FrameMetaEntity(it, null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1)) }
    }

    private fun core(hits: List<PhotoHit> = emptyList(), endpoint: suspend () -> String? = { "az" }, loads: MutableList<String>? = null, byVector: (Long?) -> Unit = {}, windowed: (suspend (String, Long, Long?, Long) -> List<PhotoHit>)? = null) =
        RagCore(MemoryGraph.get(ctx), AiGraph.get(ctx).retriever, { RetrievalSettings() }, { zone },
            photos = { _, _ -> hits }, photosWindowed = windowed, photosAsOf = { _, _ -> hits }, photosByVector = { _, _, na, _ -> byVector(na); hits },
            photosAllowed = { true }, imageEndpointId = endpoint,
            photoImages = { ids -> loads?.addAll(ids); ids.associateWith { ModelImageInput("image/jpeg", "AAAA") } })

    @Test fun noCheckedEndpointMeansNoAttachmentAndNoDecrypt() = runBlocking<Unit> {
        meta("f1")
        for (ep in listOf<suspend () -> String?>({ null }, { throw java.io.IOException("down") })) {
            val loads = mutableListOf<String>()
            val c = core(listOf(hit("f1", 0.9)), ep, loads).retrieve(RagQuery("mug"), listOf(RagSource.PhotoStore()), RagParams(), now)
            assertEquals(1, c.photos.size); assertEquals(emptyList<Any>(), c.images); assertEquals(null, c.imageEndpointId); assertEquals(emptyList<String>(), loads)
            assertEquals(false, c.photoLines.any { it.contains("image=") })
        }
    }

    @Test fun aPhotoForgottenAfterEnrichmentIsDropped() = runBlocking<Unit> {
        meta("f1") // f2 has no frame_meta: forgotten between the recall and the prompt (a deliberate fence the old Ask path lacked)
        val c = core(listOf(hit("f1", 0.9), hit("f2", 0.85))).retrieve(RagQuery("mug"), listOf(RagSource.PhotoStore()), RagParams(), now)
        assertEquals(listOf("frame:f1"), c.photos.map { it.citeId })
    }

    @Test fun historicalNudgesAreFencedAndTheirSourcesJoinTheIds() = runBlocking<Unit> {
        seed()
        val mem = MemoryGraph.get(ctx)
        mem.nudges.addIgnore(com.rokid.context.memory.db.NudgeEntity("n1", now - 60_000L, "milk is low", "", "[\"rokid_mem_1\"]", 1, "h1", "glance", false))
        mem.nudges.addIgnore(com.rokid.context.memory.db.NudgeEntity("n2", now - 50_000L, "car is on 3", "", "[\"rokid_mem_3\"]", 1, "h2", "glance", false))
        val srcs = listOf(RagSource.NudgeHistory(86_400_000L))
        val c = core().retrieve(RagQuery(), srcs, RagParams(), now)
        assertEquals(listOf("car is on 3", "milk is low"), c.history); assertEquals(setOf("rokid_mem_1", "rokid_mem_3"), c.ids)
        mem.db.memoryDao().insert(mem.memories.recent(10).first { it.id == "rokid_mem_3" }.copy(reviewState = com.rokid.context.memory.db.ReviewState.INVALIDATED))
        val d = core().retrieve(RagQuery(), srcs, RagParams(), now)
        assertEquals(listOf("milk is low"), d.history); assertEquals(setOf("rokid_mem_1"), d.ids)
    }

    @Test fun photoCutoffAndWindowApplyToVectorSearch() = runBlocking<Unit> {
        meta("old", "late", "fresh")
        var seen: Long? = -1L
        val hits = listOf(hit("old", 0.9).copy(ts = now - 10_000_000L, receivedAt = now - 10_000_000L), hit("late", 0.9).copy(ts = now + 5_000L, receivedAt = now + 5_000L), hit("fresh", 0.9).copy(ts = now - 1_000L, receivedAt = now - 1_000L))
        val c = core(hits, byVector = { seen = it })
        val q = RagQuery(vector = floatArrayOf(1f))
        val cut = c.retrieve(q, listOf(RagSource.PhotoStore(notAfterMs = now)), RagParams(), now)
        assertEquals(now, seen); assertEquals(setOf("frame:old", "frame:fresh"), cut.photos.map { it.citeId }.toSet())
        val win = c.retrieve(q, listOf(RagSource.PhotoStore(notAfterMs = now, windowMs = 60_000L)), RagParams(), now)
        assertEquals(listOf("frame:fresh"), win.photos.map { it.citeId })
    }

    @Test fun recentAndExplicitObeyTheSharedBounds() = runBlocking<Unit> {
        seed()
        val c = core()
        val recent = RagSource.RecentWindow(6 * 3_600_000L)
        val both = listOf(recent, RagSource.Explicit(listOf("rokid_mem_1", "rokid_mem_2", "rokid_mem_3")), recent)
        assertEquals(RagContext(), c.retrieve(RagQuery("milk?"), both, RagParams(maxChars = 0), now))
        assertEquals(1, c.retrieve(RagQuery(), both, RagParams(maxRecent = 1), now).recent.size)
        val three = c.retrieve(RagQuery(), both, RagParams(), now)
        assertEquals(3, three.recent.size); assertEquals(three.recent.size, three.recentLines.size)
        val one = three.recentLines.first().length
        val tight = c.retrieve(RagQuery(), both, RagParams(maxChars = one + 1), now)
        assertEquals(1, tight.recentLines.size)
    }

    @Test fun oneBudgetAcrossSourcesWhateverTheirOrder() = runBlocking<Unit> {
        seed()
        val mem = MemoryGraph.get(ctx)
        mem.nudges.addIgnore(com.rokid.context.memory.db.NudgeEntity("n1", now - 60_000L, "x".repeat(200), "", "[]", 1, "h1", "glance", false))
        val recent = RagSource.RecentWindow(6 * 3_600_000L); val store = RagSource.MemoryStore(); val hist = RagSource.NudgeHistory(86_400_000L)
        val c = core()
        fun total(x: RagContext) = (x.memoryLines + x.recentLines + x.photoLines + x.history).sumOf { it.length + 1 }
        for (bound in listOf(150, 300, 450, 800)) {
            val a = c.retrieve(RagQuery("milk?"), listOf(recent, store, hist), RagParams(maxChars = bound), now)
            val b = c.retrieve(RagQuery("milk?"), listOf(hist, store, recent), RagParams(maxChars = bound), now)
            assertEquals(a, b)
            assertEquals(true, total(a) <= bound)
        }
        val h = c.retrieve(RagQuery(), listOf(hist), RagParams(maxChars = 100), now)
        assertEquals(emptyList<String>(), h.history)
    }

    @Test fun forgettingADependencyRedactsTheDerivativeNudge() = runBlocking<Unit> {
        seed()
        val g = MemoryGraph.get(ctx)
        // The new nudge cites B but was written from a history nudge sourced from A.
        g.nudges.addIgnore(com.rokid.context.memory.db.NudgeEntity("n_new", now, "t", "", AskSources.encodeWithDeps(listOf("rokid_mem_2"), listOf("rokid_mem_1")), 1, "h", "glance", false))
        assertEquals(listOf("rokid_mem_2"), AskSources.parse(g.db.nudgeGateDao().lastNudges(0).single().sourceIdsJson))
        com.rokid.context.memory.forget.ForgetService(g.db, g.proposals).forgetFact("rokid_mem_1")
        val row = g.db.nudgeGateDao().lastNudges(0)
        assertEquals(true, row.isEmpty()) // redacted rows are left out of the history
    }

    @Test fun aTextWindowReachesTheSearchBeforeTopK() = runBlocking<Unit> {
        meta("fresh")
        var bound = -1L
        val h = listOf(hit("fresh", 0.9).copy(ts = now - 1_000L, receivedAt = now - 1_000L))
        val c = core(h, windowed = { _, _, _, nb -> bound = nb; h })
        val r = c.retrieve(RagQuery("mug"), listOf(RagSource.PhotoStore(windowMs = 60_000L)), RagParams(), now)
        assertEquals(now - 60_000L, bound); assertEquals(1, r.photos.size)
        // no windowed search bound: nothing is searched (never an unwindowed top-k filtered afterwards)
        assertEquals(0, core(h).retrieve(RagQuery("mug"), listOf(RagSource.PhotoStore(windowMs = 60_000L)), RagParams(), now).photos.size)
    }

    @Test fun photosSpendTheSameBudgetAndStayAligned() = runBlocking<Unit> {
        meta("f1", "f2")
        val hs = listOf(hit("f1", 0.9, cap = "first"), hit("f2", 0.88, cap = "second"))
        val c = core(hs)
        val full = c.retrieve(RagQuery("mug"), listOf(RagSource.PhotoStore()), RagParams(), now)
        assertEquals(2, full.images.size); assertEquals(3, full.photoLines.size)
        val cost = full.photoLines.sumOf { it.length + 1 }
        val tight = c.retrieve(RagQuery("mug"), listOf(RagSource.PhotoStore()), RagParams(maxChars = cost - 1), now)
        assertEquals(1, tight.images.size); assertEquals(1, tight.photos.size)
        assertEquals(true, tight.photoLines.any { it.contains("image=1") } && tight.photoLines.none { it.contains("image=2") })
        assertEquals(true, tight.photoLines.sumOf { it.length + 1 } <= cost - 1)
        val none = c.retrieve(RagQuery("mug"), listOf(RagSource.PhotoStore()), RagParams(maxChars = 1), now)
        assertEquals(emptyList<Any>(), none.photos); assertEquals(emptyList<Any>(), none.images); assertEquals(emptyList<String>(), none.photoLines)
    }

    @Test fun aWindowedTextPhotoSourceWorksThroughAnswerService() = runBlocking<Unit> {
        meta("fresh")
        val h = listOf(hit("fresh", 0.9).copy(ts = now - 1_000L, receivedAt = now - 1_000L))
        var bound = -1L
        val svc = AnswerService(
            MemoryGraph.get(ctx), AiGraph.get(ctx).runner, AiGraph.get(ctx).retriever, { RetrievalSettings() }, zone = { zone },
            photosWindowed = { _, _, _, nb -> bound = nb; h }, photosAllowed = { true },
        )
        val r = svc.rag.retrieve(RagQuery("mug"), listOf(RagSource.PhotoStore(windowMs = 60_000L)), RagParams(), now)
        assertEquals(1, r.photos.size); assertEquals(now - 60_000L, bound)
    }
}
