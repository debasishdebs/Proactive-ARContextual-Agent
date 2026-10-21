package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.contract.ProposedAction
import com.rokid.context.ai.contract.ShoppingLine
import com.rokid.context.connectors.ProposalIntake
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.ProposalOrigin
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.model.ModelImageInput
import com.rokid.context.model.ModelProvider
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoSource
import java.time.ZoneOffset
import com.rokid.context.memory.retrieval.RetrievalSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AnswerServiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var ai: AiGraph
    private var reply = """{"answer":"Added milk.","sources":["rokid_mem_1"],"proposedAction":{"type":"SHOPPING_ADD","items":[{"name":"milk"}]}}"""

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        ai = TestAi.graph(ctx) { reply }
        TestAi.bindAll(ai)
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    /** G10 (MVP section 9.5): no match says so with an ETA and triggers a priority reprocess, once per no-match ask. */
    @Test fun noMatchSaysSoAndQueuesAPriorityReprocess() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        var reprocess = 0
        val service = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, onNoMatch = { reprocess++ })
        assertEquals(AskOutcome.Failed(AskActions.NO_MEMORIES), service.answer("where are my keys", "phone_typed"))
        assertEquals("I don't have anything on that yet \u2014 I'll look again in your next photos", AskActions.NO_MEMORIES)
        assertEquals(1, reprocess)
        assertEquals("an empty question is not a no-match", AskOutcome.Failed(AskActions.EMPTY_QUESTION), service.answer("  ", "phone_typed"))
        assertEquals(1, reprocess)
        mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        service.answer("milk?", "phone_typed")
        assertEquals("a match triggers nothing", 1, reprocess)
    }

    /** "note that…" (user notes): an action command still reaches the model when there is no memory to answer from. */
    @Test fun anActionCommandWithNoMemoriesStillReachesTheModel() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        reply = """{"answer":"Noted.","sources":[],"proposedAction":{"type":"NOTE_ADD","text":"the car is on level 3"}}"""
        var reprocess = 0
        val actions = mutableListOf<ProposedAction>()
        val service = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, onNoMatch = { reprocess++ },
            proposals = ProposalIntake { _, _, a, _ -> actions += a; "p-note" })
        val r = service.answer("Note that the car is on level 3", "glasses_voice") as AskOutcome.Answered
        assertEquals(ProposedAction.NoteAdd("the car is on level 3"), r.proposedAction)
        assertEquals("p-note", r.proposalId)
        assertEquals(listOf<ProposedAction>(ProposedAction.NoteAdd("the car is on level 3")), actions)
        assertEquals("Noted.", mem.asks.byId(r.askId)!!.answer)
        assertEquals("an action command is not a no-match", 0, reprocess)
    }

    @Test fun proposedActionGoesToIntakeAfterCommit() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        val calls = mutableListOf<Pair<ProposalOrigin, String>>()
        val intake = ProposalIntake { origin, originId, action, _ ->
            assertNotNull("ask row committed before the intake runs", mem.asks.byId(originId))
            assertEquals(ProposedAction.ShoppingAdd(listOf(ShoppingLine("milk", null, null))), action)
            calls += origin to originId
            "p-fake"
        }
        val service = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, proposals = intake)
        val r = service.answerOrThrow("add milk", "phone_typed", nowMs = 99)
        assertEquals(listOf(ProposalOrigin.ASK to r.askId), calls)
        assertEquals("p-fake", r.proposalId)
    }

    @Test fun failingIntakeStillAnswersAndCancellationPropagates() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        val boom = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, proposals = ProposalIntake { _, _, _, _ -> error("db") })
        val r = boom.answerOrThrow("add milk", "phone_typed", nowMs = 99)
        assertEquals(null, r.proposalId)
        assertNotNull(mem.asks.byId(r.askId))
        val cancel = AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() },
            proposals = ProposalIntake { _, _, _, _ -> throw kotlinx.coroutines.CancellationException("left") })
        val thrown = runCatching { cancel.answerOrThrow("add milk", "phone_typed", nowMs = 100) }.exceptionOrNull()
        org.junit.Assert.assertTrue("$thrown", thrown is kotlinx.coroutines.CancellationException)
    }

    @Test fun sourceLinesGetTheAsksNow() = runBlocking {
        val mem = MemoryGraph.get(ctx)
        mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        val seen = mutableListOf<Long>()
        val sources = AnswerService.sourcesFrom(com.rokid.context.connectors.ContextSourceProvider { _, now -> seen += now; emptyList() })
        AnswerService(mem, ai.runner, ai.retriever, { RetrievalSettings() }, sources = sources).answerOrThrow("add milk", "phone_typed", nowMs = 1_234)
        assertEquals(listOf(1_234L), seen)
    }

    // ---- Wave 3 Task 3: photo hits in ANSWER -------------------------------------------------------------------------------------------

    private class Seen { var prompt = ""; var images: List<ModelImageInput> = emptyList() }

    private fun recording(seen: Seen, reply: suspend () -> String) {
        ai = AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider {
                override suspend fun generate(prompt: String): String { seen.prompt = prompt; return reply() }
                override suspend fun generateWithImages(prompt: String, images: List<ModelImageInput>): String { seen.prompt = prompt; seen.images = images; return reply() }
            }
        }).also { AiGraph.resetForTests(it) }
        TestAi.bindAll(ai)
    }

    private val t0 = 1_760_000_000_000L // 2025-10-09 08:53 UTC
    private fun img(tag: String) = ModelImageInput("image/jpeg", tag)

    private fun hit(id: String, stored: Boolean = true, ts: Long? = t0, receivedAt: Long? = t0 + 5 * 3_600_000L, caption: String? = null, captionId: String? = null) =
        PhotoHit(id, PhotoSource.IMAGE, 0.5, ts = if (stored) ts else ts, receivedAt = receivedAt, photoStored = stored, described = caption != null,
            captionMemoryId = captionId, captionText = caption, clusterId = null)

    private suspend fun moment(id: String, stored: Boolean = true) {
        val db = MemoryGraph.get(ctx).db
        if (stored) db.frameDao().insertIgnore(FrameEntity(id, t0, "/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1))
    }

    private fun service(
        photos: List<PhotoHit> = emptyList(), images: Map<String, ModelImageInput> = emptyMap(), imageCalls: MutableList<List<String>> = mutableListOf(),
        photoFn: (suspend (String, Long) -> List<PhotoHit>)? = null,
    ): AnswerService {
        val fn: suspend (String, Long) -> List<PhotoHit> = photoFn ?: { _, _ -> photos }
        return AnswerService(MemoryGraph.get(ctx), ai.runner, ai.retriever, { RetrievalSettings() }, zone = { ZoneOffset.UTC },
        photos = fn, photoImages = { ids: List<String> -> imageCalls += ids; ids.mapNotNull { id -> images[id]?.let { id to it } }.toMap() })
    }

    @Test fun twoPhotoLinesAndImagesReachAnswer() = runBlocking {
        val seen = Seen(); recording(seen) { """{"answer":"On the desk.","sources":["frame:f1"]}""" }
        moment("f1"); moment("f2")
        val r = service(listOf(hit("f1", caption = "A desk with keys"), hit("f2")), mapOf("f1" to img("A"), "f2" to img("B"))).answerOrThrow("where are my keys", "phone_typed", nowMs = 5)
        assertEquals("f1", r.photoFrameId)
        assertEquals(listOf(img("A"), img("B")), seen.images)
        assertTrue(seen.prompt, "id=frame:f1 · 2025-10-09 08:53 · photo · image=1 · A desk with keys" in seen.prompt)
        assertTrue(seen.prompt, "id=frame:f2 · 2025-10-09 08:53 · photo · image=2 · no caption yet" in seen.prompt)
    }

    @Test fun imagesFollowCiteOrderNotMapOrder() = runBlocking {
        val seen = Seen(); recording(seen) { """{"answer":"x","sources":["frame:f1"]}""" }
        moment("f1"); moment("f2")
        val calls = mutableListOf<List<String>>()
        // The map is built in reverse: only the cite order may decide the image order.
        service(listOf(hit("f1"), hit("f2")), linkedMapOf("f2" to img("B"), "f1" to img("A")), calls).answerOrThrow("q", "phone_typed", nowMs = 5)
        assertEquals(listOf(img("A"), img("B")), seen.images)
        assertTrue(seen.prompt, "frame:f1 · 2025-10-09 08:53 · photo · image=1" in seen.prompt && "frame:f2 · 2025-10-09 08:53 · photo · image=2" in seen.prompt)
        assertEquals(listOf(listOf("f1"), listOf("f2")), calls)
    }

    @Test fun unreadableFirstPhotoShiftsIndexExplicitly() = runBlocking {
        val seen = Seen(); recording(seen) { """{"answer":"x","sources":["frame:f2"]}""" }
        moment("f1"); moment("f2")
        service(listOf(hit("f1"), hit("f2")), mapOf("f2" to img("B"))).answerOrThrow("q", "phone_typed", nowMs = 5)
        assertEquals(listOf(img("B")), seen.images)
        assertTrue(seen.prompt, "frame:f1 · 2025-10-09 08:53 · photo · no caption yet" in seen.prompt)
        assertTrue(seen.prompt, "frame:f2 · 2025-10-09 08:53 · photo · image=1" in seen.prompt)
    }

    @Test fun photoOnlyAskAnswers() = runBlocking {
        val seen = Seen(); recording(seen) { """{"answer":"Keys on the desk.","sources":["frame:f1"]}""" }
        moment("f1")
        val r = service(listOf(hit("f1")), mapOf("f1" to img("A"))).answerOrThrow("keys?", "phone_typed", nowMs = 5)
        assertEquals("Keys on the desk.", MemoryGraph.get(ctx).asks.byId(r.askId)!!.answer)
        assertEquals("""["frame:f1"]""", MemoryGraph.get(ctx).asks.byId(r.askId)!!.sourcesJson)
    }

    @Test fun photoFailureDoesNotFailAsk() = runBlocking {
        recording(Seen()) { """{"answer":"Milk is low.","sources":["rokid_mem_1"]}""" }
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        val boom = service(photoFn = { _, _ -> error("scan failed") })
        assertEquals("Milk is low.", MemoryGraph.get(ctx).asks.byId(boom.answerOrThrow("milk?", "phone_typed", nowMs = 5).askId)!!.answer)
        MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)) // no memories at all now
        recording(Seen()) { """{"answer":"x","sources":[]}""" }
        val none = service(photoFn = { _, _ -> error("scan failed") })
        val thrown = runCatching { none.answerOrThrow("anything", "phone_typed", nowMs = 6) }.exceptionOrNull()
        assertTrue("$thrown", thrown is NoMemoriesException)
    }

    @Test fun photoCiteIdsAreCitable() = runBlocking {
        recording(Seen()) { """{"answer":"x","sources":["frame:other"]}""" }
        moment("f1")
        assertEquals("a source outside the prompt's ids is rejected", AskOutcome.Failed(AskActions.FAILED), service(listOf(hit("f1"))).answer("q", "phone_typed", 5))
        recording(Seen()) { """{"answer":"x","sources":["frame:f1"]}""" }
        assertTrue(service(listOf(hit("f1"))).answer("q", "phone_typed", 5) is AskOutcome.Answered)
    }

    @Test fun firstCitedPhotoReturned() = runBlocking {
        recording(Seen()) { """{"answer":"x","sources":["rokid_mem_1","frame:f2","frame:f1"]}""" }
        val mem = MemoryGraph.get(ctx); mem.memories.add(LegacyImporter.toMemory("rokid_mem_1", "short_term", 0.9, 10, "Milk carton nearly empty"))
        moment("f1"); moment("f2")
        val r = service(listOf(hit("f1", ts = t0 + 1_000), hit("f2", ts = t0 + 2_000))).answerOrThrow("q", "phone_typed", nowMs = 5)
        assertEquals("f2", r.photoFrameId)
        assertEquals(t0 + 2_000, r.photoAtMs)
    }

    @Test fun captionMemoryOfStoredPhotoGivesTheHudPhoto() = runBlocking {
        recording(Seen()) { """{"answer":"x","sources":["rokid_cap1"]}""" }
        val mem = MemoryGraph.get(ctx); moment("f1")
        mem.memories.add(LegacyImporter.toMemory("rokid_cap1", "short_term", 0.9, 10, "Keys on a desk").copy(frameId = "f1"))
        val r = service(photoFn = { _, _ -> emptyList() }).answerOrThrow("keys on a desk", "phone_typed", nowMs = 5)
        assertEquals("f1", r.photoFrameId); assertEquals(t0, r.photoAtMs)
    }

    @Test fun forgottenPhotoDuringAnswerRedacts() = runBlocking {
        val mem = MemoryGraph.get(ctx); moment("f1")
        val forget = ForgetService(mem.db, ProposalRepository(mem.db.proposalDao(), mem.db.actionDao(), mem.db)) { 1_000 }
        recording(Seen()) { forget.forgetMoment("f1"); """{"answer":"Keys by the lamp.","sources":["frame:f1"]}""" }
        val r = service(listOf(hit("f1")), mapOf("f1" to img("A"))).answerOrThrow("keys?", "phone_typed", nowMs = 5)
        val ask = mem.asks.byId(r.askId)!!
        assertTrue(ask.redacted); assertEquals("[]", ask.sourcesJson)
        assertEquals(null, r.photoFrameId); assertEquals(null, r.photoAtMs)
    }

    @Test fun textBudgetLeavesRoomForPhotos() = runBlocking {
        val seen = Seen(); recording(seen) { """{"answer":"x","sources":["frame:f1"]}""" }
        val mem = MemoryGraph.get(ctx); moment("f1"); moment("f2")
        repeat(30) { mem.memories.add(LegacyImporter.toMemory("rokid_big_$it", "short_term", 0.9, 10L + it, "keys " + "long text ".repeat(60))) }
        service(listOf(hit("f1", caption = "c".repeat(400)), hit("f2"))).answerOrThrow("keys", "phone_typed", nowMs = 5)
        assertTrue(seen.prompt, "id=frame:f1" in seen.prompt && "id=frame:f2" in seen.prompt)
        val block = seen.prompt.substringAfter("Retrieved memories").substringBefore("Other context")
        assertTrue("block ${block.length}", block.length < 5_800)
        assertTrue("caption clipped to 300", "c".repeat(301) !in seen.prompt)
    }

    @Test fun noImagesWhenPhotoExpired() = runBlocking {
        val seen = Seen(); recording(seen) { """{"answer":"x","sources":["frame:f1"]}""" }
        moment("f1", stored = false)
        val calls = mutableListOf<List<String>>()
        val r = service(listOf(hit("f1", stored = false)), mapOf("f1" to img("A")), calls).answerOrThrow("q", "phone_typed", nowMs = 5)
        assertEquals(emptyList<ModelImageInput>(), seen.images); assertEquals(emptyList<List<String>>(), calls)
        assertTrue(seen.prompt, "id=frame:f1" in seen.prompt && "image=" !in seen.prompt)
        assertEquals("an expired photo gives no HUD time", null, r.photoFrameId)
    }

    /** User ruling 2026-10-22 over S9: an expired photo's line shows the capture time when known; only the fallback says "received". */
    @Test fun expiredPhotoLineSaysReceivedOnlyWithoutCaptureTime() = runBlocking {
        val seen = Seen(); recording(seen) { """{"answer":"x","sources":["frame:f1"]}""" }
        moment("f1", stored = false); moment("f2", stored = false)
        service(listOf(hit("f1", stored = false, ts = null), hit("f2", stored = false, ts = t0 + 60_000))).answerOrThrow("q", "phone_typed", nowMs = 5)
        assertTrue(seen.prompt, "id=frame:f1 · received 2025-10-09 13:53 · photo · " in seen.prompt)
        assertTrue(seen.prompt, "id=frame:f2 · 2025-10-09 08:54 · photo · " in seen.prompt)
    }

    @Test fun captionedPhotoIsListedOnceAsThePhotoLine() = runBlocking {
        val seen = Seen(); recording(seen) { """{"answer":"x","sources":["rokid_cap1"]}""" }
        val mem = MemoryGraph.get(ctx); moment("f1")
        mem.memories.add(LegacyImporter.toMemory("rokid_cap1", "short_term", 0.9, 10, "Keys on a desk").copy(frameId = "f1"))
        service(listOf(hit("f1", caption = "Keys on a desk", captionId = "rokid_cap1")), mapOf("f1" to img("A"))).answerOrThrow("keys on a desk", "phone_typed", nowMs = 5)
        assertEquals(1, Regex("id=rokid_cap1 ").findAll(seen.prompt).count())
        assertTrue(seen.prompt, "id=rokid_cap1 · 2025-10-09 08:53 · photo · image=1 · Keys on a desk" in seen.prompt)
        assertTrue("no confidence text line for it", "confidence 0.90 · frame=f1" !in seen.prompt)
    }
}
