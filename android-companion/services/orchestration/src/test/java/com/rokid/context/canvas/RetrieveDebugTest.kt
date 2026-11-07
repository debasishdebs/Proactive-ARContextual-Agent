package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RetrieveDebugTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val mem get() = MemoryGraph.get(ctx)
    private val modelCalls = AtomicInteger()
    private lateinit var ai: AiGraph
    private fun service(mode: RetrievalMode = RetrievalMode.LEXICAL) = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings(mode = mode) })
    private suspend fun add(id: String, text: String, at: Long) = mem.memories.add(LegacyImporter.toMemory(id, "short_term", 0.9, at, text))
    private fun rd(gate: DataOpGate = DataOpGate(), gen: () -> Long = { 1L }, mode: RetrievalMode = RetrievalMode.LEXICAL, answers: AnswerService = service(mode)) =
        RetrieveDebug(answers, { gate }, gen, { 1_000L }, live = { com.rokid.context.memory.forget.ForgetFence.liveIds(mem.db, it) })

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestAi.graph(ctx) { modelCalls.incrementAndGet(); "{}" }.also { TestAi.bindAll(it) }
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    @Test fun rankedHitsWithoutTextAndWithoutAModelCall() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); add("m2", "milk bottle on the shelf", 20); add("m3", "house keys on the hook", 30)
        val before = mem.db.memoryDao().count()
        val r = rd().run("milk fridge", null, null, null, snippets = false) as RetrieveDebug.Result.Ok
        assertEquals("LEXICAL", r.mode); assertFalse(r.semanticUnavailable); assertFalse(r.fallback)
        assertEquals(setOf("m1", "m2"), r.hits.map { it.memoryId }.toSet())
        assertEquals(listOf(1, 2), r.hits.map { it.rank })
        assertTrue(r.hits.all { it.snippet == null && it.score > 0 && it.reviewState.isNotEmpty() })
        assertEquals(0, modelCalls.get())
        assertEquals(before, mem.db.memoryDao().count())
    }

    @Test fun modeTopKAndNotAfterOverride() = runBlocking<Unit> {
        for (i in 1..5) add("m$i", "milk number $i", i * 10L)
        val r = rd().run("milk", RetrievalMode.LEXICAL, 2, null, false) as RetrieveDebug.Result.Ok
        assertEquals(2, r.hits.size)
        val old = rd().run("milk", null, 30, 25, false) as RetrieveDebug.Result.Ok
        assertEquals(setOf("m1", "m2"), old.hits.map { it.memoryId }.toSet())
        val sem = rd().run("milk", RetrievalMode.SEMANTIC, null, null, false) as RetrieveDebug.Result.Ok
        assertTrue(sem.mode == "SEMANTIC" || sem.mode == "LEXICAL") // no embedder in a test: it says so
        if (sem.mode != "SEMANTIC") assertTrue(sem.semanticUnavailable)
        assertEquals(0, modelCalls.get())
    }

    @Test fun snippetsOnlyOnRequestAndAtMost80Chars() = runBlocking<Unit> {
        add("m1", "milk " + "x".repeat(300), 10)
        val on = rd().run("milk", null, null, null, true) as RetrieveDebug.Result.Ok
        assertEquals(80, on.hits.single().snippet!!.length)
        val off = rd().run("milk", null, null, null, false) as RetrieveDebug.Result.Ok
        assertNull(off.hits.single().snippet)
    }

    @Test fun failsClosedDuringAnExportAndAWipe() = runBlocking<Unit> {
        add("m1", "milk", 10)
        val gate = DataOpGate()
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val exp = GlobalScope.launch { gate.export { inside.complete(Unit); release.await() } }
        inside.await()
        assertEquals(RetrieveDebug.Result.Busy, rd(gate).run("milk", null, null, null, false))
        release.complete(Unit); exp.join()
        assertTrue(rd(gate).run("milk", null, null, null, false) is RetrieveDebug.Result.Ok)
        val gens = ArrayDeque(listOf(1L, 2L))
        assertEquals(RetrieveDebug.Result.Busy, rd(gen = { gens.removeFirst() }).run("milk", null, null, null, false))
    }

    /** Review fix 2026-11-07: a fact Forget that completes after the retrieval read (no gate, no wipe generation change) must not leak its id or snippet. */
    @Test fun aForgetCompletingMidRetrievalIsFencedOut() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); add("m2", "milk bottle on the shelf", 20)
        val forget = com.rokid.context.memory.forget.ForgetService(mem.db, com.rokid.context.memory.ProposalRepository(mem.db.proposalDao(), mem.db.actionDao(), mem.db)) { 1_000L }
        val seen = mutableListOf<String>()
        val rd = RetrieveDebug(service(), { DataOpGate() }, { 1L }, { 1_000L }, live = { ids ->
            seen += ids
            forget.forgetFact("m1") // completes after the retriever read m1, before the result leaves
            com.rokid.context.memory.forget.ForgetFence.liveIds(mem.db, ids)
        })
        val r = rd.run("milk", null, null, null, snippets = true) as RetrieveDebug.Result.Ok
        assertTrue("the retriever did return m1", "m1" in seen)
        assertEquals(listOf("m2"), r.hits.map { it.memoryId }); assertEquals(listOf(1), r.hits.map { it.rank })
        assertTrue(r.hits.none { it.snippet!!.contains("carton") })
    }
}
