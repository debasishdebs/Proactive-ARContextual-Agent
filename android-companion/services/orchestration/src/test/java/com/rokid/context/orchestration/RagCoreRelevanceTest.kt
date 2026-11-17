package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.Embedder
import com.rokid.context.memory.EmbeddingSpaces
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.BruteForceVectorIndex
import com.rokid.context.memory.retrieval.LexicalRetriever
import com.rokid.context.memory.retrieval.MemoryRetriever
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalResult
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.memory.retrieval.ScoredMemory
import com.rokid.context.memory.retrieval.SemanticRetriever
import com.rokid.context.orchestration.retrieval.OrderRules
import com.rokid.context.orchestration.retrieval.WindowRelevance
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Codex r7: an unrelated hit inside the asked window must not stop the widening (lexical, semantic, hybrid), and a state question on the built-in plan lists its
 * relevant memories newest first so the latest state is never cut by top-k.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RagCoreRelevanceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneOffset.UTC
    private fun at(s: String) = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = at("2026-11-16T12:04:00")

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); TestAi.bindAll(TestAi.graph(ctx) { "{}" }) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, text: String, createdAt: String) =
        MemoryGraph.get(ctx).memories.add(MemoryEntity(id, null, text, "note", "", MemoryTier.SHORT, 0.9, ReviewState.OK, false, at(createdAt), false, ""))

    private fun service(mode: RetrievalMode, retriever: MemoryRetriever = AiGraph.get(ctx).retriever) =
        AnswerService(MemoryGraph.get(ctx), AiGraph.get(ctx).runner, retriever, { RetrievalSettings(mode = mode) }, zone = { zone })

    /** A 2-d space: the query is (1,0); a memory in the vector map is (1,0) when relevant, (0,1) when unrelated. */
    private suspend fun semanticRetriever(vararg vectors: Pair<String, FloatArray>): MemoryRetriever {
        val db = MemoryGraph.get(ctx).db
        val spaces = EmbeddingSpaces(db.embeddingDao())
        val embedder = object : Embedder {
            override val fingerprint = "fp"
            override suspend fun embedDocuments(texts: List<String>) = texts.map { floatArrayOf(1f, 0f) }
            override suspend fun embedQuery(text: String): FloatArray = floatArrayOf(1f, 0f)
        }
        val s = spaces.startBuilding("fp", "{}", 2, "[]", 1)
        vectors.forEach { (id, v) -> spaces.put(id, s.id, v) }
        assertTrue(spaces.swapIfComplete(s.id))
        return MemoryRetriever(LexicalRetriever(db.retrievalDao()), SemanticRetriever(spaces, BruteForceVectorIndex(db.embeddingDao())) { embedder }, db.memoryViewDao())
    }

    private val q = "Where are my keys on 10 Nov?"
    private fun ids(r: AnswerService.Retrieved) = r.memories.map { it.id }
    private fun note(r: AnswerService.Retrieved) = r.vars.retrievedMemories.lines().first { it.startsWith("- note:") }

    private suspend fun unrelatedInWindowRelevantOutside() {
        mem("cat", "The cat is on the sofa", "2026-11-10T21:00:00") // shares only function words with the question
        mem("keys", "Keys on the hook by the door", "2026-11-09T20:00:00") // the day before: inside the widened window
    }

    @Test fun lexicalUnrelatedHitInTheWindowDoesNotHideTheRelevantMemoryOutsideIt() = runBlocking<Unit> {
        unrelatedInWindowRelevantOutside()
        val r = service(RetrievalMode.LEXICAL).retrieve(q, now)
        assertTrue(ids(r).toString(), "keys" in ids(r))
        assertTrue(note(r), note(r).contains("widened to"))
    }

    @Test fun semanticUnrelatedHitInTheWindowDoesNotHideTheRelevantMemoryOutsideIt() = runBlocking<Unit> {
        unrelatedInWindowRelevantOutside()
        val retriever = semanticRetriever("cat" to floatArrayOf(0f, 1f), "keys" to floatArrayOf(1f, 0f))
        val r = service(RetrievalMode.SEMANTIC, retriever).retrieve(q, now)
        assertTrue(ids(r).toString(), "keys" in ids(r))
        assertTrue(note(r), note(r).contains("widened to"))
    }

    @Test fun hybridUnrelatedHitInTheWindowDoesNotHideTheRelevantMemoryOutsideIt() = runBlocking<Unit> {
        unrelatedInWindowRelevantOutside()
        val retriever = semanticRetriever("cat" to floatArrayOf(0f, 1f), "keys" to floatArrayOf(1f, 0f))
        val r = service(RetrievalMode.HYBRID, retriever).retrieve(q, now)
        assertTrue(ids(r).toString(), "keys" in ids(r))
        assertTrue(note(r), note(r).contains("widened to"))
    }

    @Test fun aRelevantHitInTheWindowStillStopsTheWidening() = runBlocking<Unit> {
        mem("k10", "Keys on the hook", "2026-11-10T21:00:00"); mem("keys", "Keys on the hook by the door", "2026-11-09T20:00:00")
        val retriever = semanticRetriever("k10" to floatArrayOf(1f, 0f), "keys" to floatArrayOf(1f, 0f))
        for (mode in listOf(RetrievalMode.LEXICAL, RetrievalMode.SEMANTIC, RetrievalMode.HYBRID)) {
            val r = service(mode, retriever).retrieve(q, now)
            assertEquals(mode.name, listOf("k10"), ids(r))
            assertTrue(note(r), note(r).contains("time window"))
        }
    }

    @Test fun aDateOnlyQuestionKeepsAnyHitInTheWindow() = runBlocking<Unit> {
        mem("m10", "Milk carton on the table", "2026-11-10T21:00:00")
        assertEquals(listOf("m10"), ids(service(RetrievalMode.LEXICAL).retrieve("What happened on 10 Nov?", now)))
    }

    private fun hit(text: String, lexical: Double?, semantic: Double?) = ScoredMemory(MemoryEntity("x", null, text, "note", "", MemoryTier.SHORT, 0.9, ReviewState.OK, false, 0, false, ""), lexical, semantic, 1.0, 1.0)

    private fun sm(id: String, f: Double) = ScoredMemory(MemoryEntity(id, null, id, "note", "", MemoryTier.SHORT, 0.9, ReviewState.OK, false, 0, false, ""), null, null, f, f)

    @Test fun r12ProbeScoresAreRescaledSoThePrimaryHitSurvivesTheRelevanceFloor() {
        val first = RetrievalResult(listOf(sm("right", 0.0328), sm("p2", 0.0320)), RetrievalMode.HYBRID, false)
        val probe = RetrievalResult(listOf(sm("noise", 0.693), sm("n2", 0.40)), RetrievalMode.LEXICAL, false)
        val merged = RagCore.withProbe(first, probe, 10)
        assertEquals(listOf("right", "noise", "p2", "n2"), merged.hits.map { it.memory.id })
        assertEquals(0.0328, merged.hits.first { it.memory.id == "noise" }.fused, 1e-9)
        val rel = OrderRules.relevant(merged.hits, 0.5, false).map { it.memory.id }
        assertTrue(rel.toString(), "right" in rel && "p2" in rel) // before the fix the cutoff was 0.3465 and the primary hits were dropped
    }

    @Test fun r12NoProbeHitsOrAnEmptyListLeaveScoresUntouched() {
        val first = RetrievalResult(listOf(sm("a", 0.0328)), RetrievalMode.HYBRID, false)
        assertEquals(listOf(0.0328), RagCore.withProbe(first, RetrievalResult(emptyList(), RetrievalMode.LEXICAL, false), 5).hits.map { it.fused })
        val both = RagCore.withProbe(first, RetrievalResult(listOf(sm("b", 0.0)), RetrievalMode.LEXICAL, false), 5)
        assertEquals(listOf("a", "b"), both.hits.map { it.memory.id }) // Hinglish: both still returned
    }

    @Test fun theRelevanceCheckPerMode() {
        fun r(mode: RetrievalMode, h: ScoredMemory) = WindowRelevance.relevant("Where are my keys on 10 Nov?", "on 10 nov", RetrievalResult(listOf(h), mode, false))
        assertFalse(r(RetrievalMode.LEXICAL, hit("The cat is on the sofa", 1.0, null)))
        assertTrue(r(RetrievalMode.LEXICAL, hit("Spare key on the hook", 1.0, null))) // "keys" -> "key" prefix
        assertFalse(r(RetrievalMode.SEMANTIC, hit("Keys on the hook", null, 0.6))) // below the floor, whatever the words say
        assertTrue(r(RetrievalMode.SEMANTIC, hit("Anything", null, 0.9)))
        assertTrue(r(RetrievalMode.HYBRID, hit("Anything", 0.2, 0.9)))
        assertTrue(r(RetrievalMode.HYBRID, hit("Keys on the hook", 0.9, null))) // not embedded yet: the words decide
        assertFalse(r(RetrievalMode.HYBRID, hit("The cat is on the sofa", 0.3, 0.5)))
        assertFalse(WindowRelevance.relevant("keys", null, RetrievalResult(emptyList(), RetrievalMode.LEXICAL, false)))
    }

    @Test fun stateQuestionsAreRecognised() {
        listOf("Did I turn off the stove?", "Is the door closed?", "Are the lights on?", "Did I leave the gas on", "did i lock the door").forEach { assertTrue(it, OrderRules.isStateQuestion(it)) }
        listOf("Where are my keys?", "What did I eat yesterday?", "Who was at the party").forEach { assertFalse(it, OrderRules.isStateQuestion(it)) }
    }

    @Test fun aStateQuestionOnTheBuiltInPlanListsTheNewestRelevantMemoriesFirst() = runBlocking<Unit> {
        for (d in 1..12) mem("old$d", "Stove burner lit under the pot", "2026-11-%02dT10:00:00".format(d))
        mem("new", "Stove burner is off, pot on the counter", "2026-11-15T08:00:00")
        val r = service(RetrievalMode.LEXICAL).retrieve("Did I turn off the stove burner?", now)
        assertEquals("new", ids(r).first())
        val l = r.vars.retrievedMemories.lines().filter { it.startsWith("- ") }
        assertTrue(l.first(), l.first().contains("listed newest first"))
        // a question that is not about a state keeps the retriever's own order and note
        mem("keys", "Keys on the hook", "2026-11-14T08:00:00")
        val other = service(RetrievalMode.LEXICAL).retrieve("Where are my keys?", now)
        assertFalse(other.vars.retrievedMemories.contains("listed newest first"))
    }
}
