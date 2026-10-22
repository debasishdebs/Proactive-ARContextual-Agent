package com.rokid.context.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.Endpoint
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.ai.Role
import com.rokid.context.ai.RoutedReply
import com.rokid.context.ai.run.RoleRunner
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.model.CallClass
import com.rokid.context.model.LanguageModel
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.model.ModelReply
import com.rokid.context.model.ModelRequest
import com.rokid.context.orchestration.AnswerService
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.orchestration.TestAi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskDebugTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val mem get() = MemoryGraph.get(ctx)
    private val dao get() = mem.db.canvasDao()
    private val prompts = mutableListOf<String>()
    private val requests = mutableListOf<ModelRequest>()
    private val classes = mutableListOf<CallClass>()
    private var gate: CompletableDeferred<Unit>? = null
    private var entered: CompletableDeferred<Unit>? = null
    private var problems = emptyList<String>()
    private var topK = 8
    private var failProvider = false
    private lateinit var ai: AiGraph

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String) = error("unused")
                override suspend fun complete(request: ModelRequest): ModelReply {
                    prompts += request.prompt; requests += request
                    if (failProvider) throw java.io.IOException("secret provider text")
                    entered?.complete(Unit); gate?.await()
                    val cite = Regex("id=(\\S+) \u00b7").find(request.prompt)?.groupValues?.get(1)
                    return ModelReply("""{"answer":"In the fridge","sources":[${cite?.let { "\"$it\"" } ?: ""}],"proposedAction":null}""", 120, 12)
                }
            }
        }).also { AiGraph.resetForTests(it); TestAi.bindAll(it) }
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun add(id: String, text: String, at: Long) = mem.memories.add(LegacyImporter.toMemory(id, "short_term", 0.9, at, text))
    private suspend fun ask(id: String = "a1", ts: Long = 1_000, q: String = "milk", sources: String = "[\"m1\"]", redacted: Boolean = false, rating: Int? = null) =
        mem.db.askDao().upsert(AskEntity(id, ts, q, "In the fridge", sources, rating, "phone_typed", redacted))

    private fun answers() = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings(mode = RetrievalMode.LEXICAL, topK = topK) })
    private fun recordingRunner(): RoleRunner {
        val rec = object : LanguageModel {
            override suspend fun complete(role: Role, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply = ai.router.complete(role, callClass.also { classes += it }, build)
            override suspend fun completeFromFrames(callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply = ai.router.completeFromFrames(callClass.also { classes += it }, build)
            override suspend fun completeOn(endpointId: String, model: String, callClass: CallClass, build: (Endpoint) -> ModelRequest): RoutedReply = ai.router.completeOn(endpointId, model, callClass, build)
        }
        return RoleRunner(rec, ai.prompts, ai.persona)
    }
    private fun debug(photos: AskDebugPhotos = AskDebugPhotos.Refuse) =
        AskDebug(dao, answers(), recordingRunner(), ai.prompts, { ai.persona }, { _, _ -> problems }, { ZoneOffset.UTC }, photos)
    private fun count(t: String) = mem.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$t`").use { it.moveToFirst(); it.getInt(0) }

    // ---- view ----

    @Test fun viewShowsScoresCitedAndInPrompt() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); add("m2", "milk bottle on the shelf", 20); add("m3", "milk later on", 5_000)
        ask(rating = 1)
        val v = debug().view("a1")!!
        assertEquals("a1", v.askId); assertEquals(1_000L, v.ts); assertEquals("milk", v.question); assertEquals("In the fridge", v.answer)
        assertEquals(listOf("m1"), v.cited); assertEquals(1, v.rating)
        assertEquals(setOf("m1", "m2"), v.hits.map { it.memoryId }.toSet()) // m3 was created after the ask
        val m1 = v.hits.single { it.memoryId == "m1" }; val m2 = v.hits.single { it.memoryId == "m2" }
        assertTrue(m1.cited); assertFalse(m2.cited)
        assertTrue(m1.inPrompt && m2.inPrompt)
        assertTrue(m1.lexical!! > 0 && m1.score > 0 && m1.semantic == null)
        assertEquals("milk carton in the fridge", m1.text)
        assertEquals("LEXICAL", v.mode); assertFalse(v.fallback)
        assertEquals(v.hits.sortedByDescending { it.score }.map { it.memoryId }, v.hits.map { it.memoryId })
    }

    @Test fun hitsBeyondThePromptBoundAreNotInPrompt() = runBlocking<Unit> {
        topK = 15
        repeat(15) { add("m$it", "milk " + "x".repeat(600) + " $it", 10L + it) } // clipped to 500 chars a line: about 9 fit in 5000
        ask(sources = "[]")
        val v = debug().view("a1")!!
        assertEquals(15, v.hits.size)
        val inPrompt = v.hits.filter { it.inPrompt }
        assertTrue(inPrompt.size in 1..14)
        assertEquals(v.hits.take(inPrompt.size).map { it.memoryId }, inPrompt.map { it.memoryId }) // the best ones are the kept ones
        inPrompt.forEach { assertTrue(v.composedPrompt.contains("id=${it.memoryId} \u00b7")) }
        v.hits.filter { !it.inPrompt }.forEach { assertFalse(v.composedPrompt.contains("id=${it.memoryId} \u00b7")) }
    }

    @Test fun fallbackWhenNothingMatches() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10)
        ask(q = "zzzz", sources = "[]")
        val v = debug().view("a1")!!
        assertTrue(v.fallback)
        assertEquals(listOf("m1"), v.hits.map { it.memoryId })
        assertTrue(v.hits.single().inPrompt)
        assertNull(v.hits.single().lexical)
    }

    @Test fun redactedRefused() = runBlocking<Unit> {
        add("m1", "milk", 10); ask(redacted = true)
        try { debug().view("a1"); fail() } catch (e: IllegalStateException) { assertEquals("redacted", e.message) }
    }

    @Test fun unknownIsNull() = runBlocking<Unit> { assertNull(debug().view("nope")) }

    @Test fun composedPromptHasQuestionAndMemories() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val v = debug().view("a1")!!
        assertTrue(v.composedPrompt.contains("milk"))
        assertTrue(v.composedPrompt.contains("BEGIN_DATA memories"))
        assertTrue(v.composedPrompt.contains("id=m1 \u00b7"))
        assertTrue(v.composedPrompt.contains("milk carton in the fridge"))
        assertTrue(v.composedPrompt.contains("BEGIN_DATA question"))
        assertTrue(v.composedPrompt.contains(v.activeTemplate.lines().first { it.isNotBlank() }.take(24)))
        assertTrue(v.activeVersion >= 1)
        assertTrue(v.composedPrompt.contains("1970-01-01T00:00")) // {{now}} is the ask's time in the injected zone
    }

    @Test fun listIsNewestFirst() = runBlocking<Unit> {
        ask("a1", ts = 5); ask("a2", ts = 9)
        assertEquals(listOf("a2", "a1"), debug().list().map { it.id })
        assertEquals(1, debug().list(1).size)
    }

    // ---- replay ----

    @Test fun replayUsesOverrideAndExperimentClass() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val r = debug().replay("a1", "OVERRIDE {{question}} {{retrieved_memories}}") as AskDebug.Replay.Ok
        assertTrue(prompts.single().contains("OVERRIDE"))
        assertTrue(prompts.single().contains("id=m1 \u00b7"))
        assertEquals(listOf(CallClass.EXPERIMENT), classes)
        assertEquals("In the fridge", r.answer); assertEquals(listOf("m1"), r.sources)
        assertEquals(120, r.promptTokens); assertEquals(12, r.completionTokens); assertEquals("gpt", r.model)
        assertTrue(r.latencyMs >= 0)
    }

    @Test fun replaySavesNothing() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val before = listOf("ask", "proposal", "memory", "nudge").map(::count)
        debug().replay("a1", "T {{question}} {{retrieved_memories}}")
        assertEquals(before, listOf("ask", "proposal", "memory", "nudge").map(::count))
        assertEquals("In the fridge", mem.db.askDao().byId("a1")!!.answer)
    }

    @Test fun replayIncompatibleFails() = runBlocking<Unit> {
        add("m1", "milk", 10); ask(); problems = listOf("schema")
        assertEquals(AskDebug.Replay.Failed("incompatible"), debug().replay("a1", "T"))
        assertTrue(prompts.isEmpty())
    }

    @Test fun replayRefusesRedactedAndUnknown() = runBlocking<Unit> {
        add("m1", "milk", 10); ask(redacted = true)
        assertEquals(AskDebug.Replay.Failed("redacted"), debug().replay("a1", "T"))
        assertEquals(AskDebug.Replay.Failed("not_found"), debug().replay("nope", "T"))
        assertTrue(prompts.isEmpty())
    }

    @Test fun replayModelFailureIsAFixedKind() = runBlocking<Unit> {
        add("m1", "milk", 10); ask()
        failProvider = true
        val r = debug().replay("a1", "T {{retrieved_memories}}") as AskDebug.Replay.Failed
        assertTrue(r.error in setOf("TRANSIENT", "NOT_SET", "AUTH", "REJECTED", "INVALID_OUTPUT")) // a kind name, never provider text
    }

    // ---- replay fence (S35) ----

    private suspend fun pausedReplay(d: AskDebug, during: suspend () -> Unit): AskDebug.Replay = coroutineScope {
        gate = CompletableDeferred(); entered = CompletableDeferred()
        val job = async(Dispatchers.Default) { d.replay("a1", "T {{question}} {{retrieved_memories}}") }
        withTimeout(10_000) { entered!!.await() }
        during()
        gate!!.complete(Unit)
        withTimeout(10_000) { job.await() }
    }

    @Test fun forgetDuringReplayReturnsNoText() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val r = pausedReplay(debug()) { mem.db.openHelper.writableDatabase.execSQL("UPDATE memory SET reviewState = 'INVALIDATED' WHERE id = 'm1'") }
        assertEquals(AskDebug.Replay.Failed("forgotten"), r)
    }

    @Test fun wipeDuringReplay() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val r = pausedReplay(debug()) { MemoryWipe.generation.incrementAndGet() }
        assertEquals(AskDebug.Replay.Failed("forgotten"), r)
    }

    @Test fun redactDuringReplay() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val r = pausedReplay(debug()) { mem.db.openHelper.writableDatabase.execSQL("UPDATE ask SET redacted = 1 WHERE id = 'a1'") }
        assertEquals(AskDebug.Replay.Failed("forgotten"), r)
    }

    @Test fun anUndisturbedReplayIsOk() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        assertTrue(pausedReplay(debug()) { } is AskDebug.Replay.Ok)
    }

    // ---- view fence: the GET must not return content forgotten while it was being built ----

    private class SlowGate(val entered: CompletableDeferred<Unit>, val go: CompletableDeferred<Unit>) : AskDebugPhotos {
        override suspend fun allowed(): Boolean { entered.complete(Unit); go.await(); return false }
        override suspend fun recall(question: String, nowMs: Long) = AskPhotoRecall(emptyList(), emptySet(), emptySet())
    }

    private suspend fun pausedView(during: suspend () -> Unit): Result<AskDebug.View?> = coroutineScope {
        val entered = CompletableDeferred<Unit>(); val go = CompletableDeferred<Unit>()
        val d = debug(SlowGate(entered, go))
        val job = async(Dispatchers.Default) { runCatching { d.view("a1") } }
        withTimeout(10_000) { entered.await() }
        during()
        go.complete(Unit)
        withTimeout(10_000) { job.await() }
    }

    @Test fun viewIsRefusedWhenTheAskIsRedactedWhileLoading() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val r = pausedView { mem.db.openHelper.writableDatabase.execSQL("UPDATE ask SET redacted = 1 WHERE id = 'a1'") }
        assertTrue(r.exceptionOrNull() is AskRedacted)
    }

    @Test fun viewIsRefusedAfterAWipeWhileLoading() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        assertTrue(pausedView { MemoryWipe.generation.incrementAndGet() }.exceptionOrNull() is AskRedacted)
    }

    @Test fun viewIsRefusedWhenAnyReturnedHitIsForgottenEvenOutsideThePrompt() = runBlocking<Unit> {
        topK = 15
        repeat(15) { add("m$it", "milk " + "x".repeat(600) + " $it", 10L + it) } // clipped to 500 chars a line: only about 9 fit the prompt bound
        ask(sources = "[]")
        val outside = debug().view("a1")!!.hits.first { !it.inPrompt }.memoryId // precondition: a hit shown but NOT in the prompt
        val r = pausedView { mem.db.openHelper.writableDatabase.execSQL("UPDATE memory SET reviewState = 'INVALIDATED' WHERE id = '$outside'") }
        assertTrue(r.exceptionOrNull() is AskRedacted)
    }

    @Test fun viewOfAnAskDeletedWhileLoadingIsNotFound() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        assertNull(pausedView { mem.db.openHelper.writableDatabase.execSQL("DELETE FROM ask WHERE id = 'a1'") }.getOrThrow())
    }

    @Test fun anUndisturbedViewStillReturns() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        assertEquals("a1", pausedView { }.getOrThrow()!!.askId)
    }

    // ---- the Ask photo gate seam (wave 3 AskPhotoPolicy): refuses by default, images only after the gate says yes ----

    private class FakePhotos(var allow: Boolean, var throwOnAllowed: Boolean = false, val endpoint: String? = "az", val imageCount: Int? = null) : AskDebugPhotos {
        var allowedCalls = 0; var recalls = 0; var imageLoads = 0
        val frames = mutableSetOf("f9")
        override suspend fun allowed(): Boolean { allowedCalls++; if (throwOnAllowed) throw IllegalStateException("prefs"); return allow }
        override suspend fun recall(question: String, nowMs: Long): AskPhotoRecall {
            recalls++
            return AskPhotoRecall(listOf("id=frame:f9 \u00b7 1970-01-01 00:00 \u00b7 photo \u00b7 image=1 \u00b7 a blue mug"), setOf("frame:f9"), frames.toSet(),
                images = { imageLoads++; listOf(ModelImageInput("image/jpeg", "AAAA")) }, imageEndpointId = endpoint, imageCount = imageCount)
        }
    }

    @Test fun replaySendsNoPhotosByDefault() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val d = debug()
        assertFalse(d.view("a1")!!.composedPrompt.contains("photo"))
        d.replay("a1", "T {{question}} {{retrieved_memories}}")
        assertTrue(requests.single().images.isEmpty())
        assertFalse(prompts.single().contains("frame:f9"))
    }

    @Test fun refusingGateNeverRecallsOrSendsImages() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val p = FakePhotos(allow = false)
        val d = debug(p)
        d.view("a1"); d.replay("a1", "T {{question}} {{retrieved_memories}}")
        assertTrue(p.allowedCalls >= 2)
        assertEquals(0, p.recalls); assertEquals(0, p.imageLoads)
        assertTrue(requests.single().images.isEmpty())
    }

    @Test fun gateFailureFailsClosed() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val p = FakePhotos(allow = true, throwOnAllowed = true)
        val r = debug(p).replay("a1", "T {{question}} {{retrieved_memories}}")
        assertTrue(r is AskDebug.Replay.Ok)
        assertEquals(0, p.recalls); assertEquals(0, p.imageLoads)
        assertTrue(requests.single().images.isEmpty())
    }

    @Test fun allowedGateAttachesPhotosOnlyToTheReplayCall() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val p = FakePhotos(allow = true)
        val d = debug(p)
        val v = d.view("a1")!!
        assertTrue(v.composedPrompt.contains("id=frame:f9 \u00b7"))     // the view shows the photo line ...
        assertEquals(0, p.imageLoads)                              // ... but never loads or sends an image
        val r = d.replay("a1", "T {{question}} {{retrieved_memories}}") as AskDebug.Replay.Ok
        assertEquals(1, p.imageLoads)
        assertEquals(1, requests.single().images.size)
        assertTrue(prompts.single().contains("id=frame:f9 \u00b7"))
        assertEquals("In the fridge", r.answer)
    }

    @Test fun aReplayWhoseImagesDifferFromThePromisedNumberingIsRefused() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val r = debug(FakePhotos(allow = true, imageCount = 2)).replay("a1", "T {{question}} {{retrieved_memories}}") // the lines promise 2 images, 1 loads
        assertEquals(AskDebug.Replay.Failed("attachments_changed"), r)
        assertTrue(requests.isEmpty())
    }

    @Test fun aGateWithoutACheckedEndpointSendsNoImage() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val p = FakePhotos(allow = true, endpoint = null)
        debug(p).replay("a1", "T {{question}} {{retrieved_memories}}")
        assertEquals(0, p.imageLoads); assertTrue(requests.single().images.isEmpty())
    }

    @Test fun photoForgottenDuringReplayReturnsNoText() = runBlocking<Unit> {
        add("m1", "milk carton in the fridge", 10); ask()
        val r = pausedReplay(debug(FakePhotos(allow = true))) { mem.db.forgetDao().insertTombstone(ForgetTombstoneEntity("f9", 1)) }
        assertEquals(AskDebug.Replay.Failed("forgotten"), r)
    }
}
