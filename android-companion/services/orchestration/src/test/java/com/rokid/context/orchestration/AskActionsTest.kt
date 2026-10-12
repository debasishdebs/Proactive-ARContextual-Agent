package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.ai.InMemoryKeyVault
import com.rokid.context.ai.InMemorySeedState
import com.rokid.context.ai.ProviderBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.data.runtime.AppConfigStore
import com.rokid.context.data.runtime.QueuePayloads
import com.rokid.context.data.runtime.QueueRuntimeStore
import com.rokid.context.domain.queue.QueuePriority
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.legacy.LegacyImporter
import com.rokid.context.model.ModelProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AskActionsTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private var reply: (String) -> String = { "Milk is almost empty." }
    private val prompts = mutableListOf<String>()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        AiGraph.resetForTests(AiGraph(MemoryGraph.get(ctx), InMemoryKeyVault(), InMemorySeedState(), ProviderBuilder { _, _, _ ->
            object : ModelProvider { override suspend fun generate(prompt: String): String { prompts += prompt; return reply(prompt) } }
        }))
        // Binds ANSWER (and the other roles) through the Phase 2 legacy bridge, as the Config screen does.
        AppConfigStore.setProvider(ctx, "openai", "https://az.example", "key-1", "gpt-5.4", "")
    }

    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun remember(id: String, text: String, at: Long) =
        MemoryGraph.get(ctx).memories.add(LegacyImporter.toMemory(id, "short_term", 0.9, at, text))

    @Test fun answersFromMatchingMemoriesAndStoresTheAsk() = runBlocking {
        remember("rokid_mem_1", "Milk carton nearly empty in the fridge", 10)
        remember("rokid_mem_2", "Keys on the hallway shelf", 20)
        val r = AskActions.ask(ctx, "  milk? ", nowMs = 99) as AskOutcome.Answered
        val ask = MemoryGraph.get(ctx).asks.byId(r.askId)!!
        assertEquals("milk?", ask.question)
        assertEquals("Milk is almost empty.", ask.answer)
        assertEquals(99L, ask.ts)
        assertEquals("phone_typed", ask.source)
        assertEquals(listOf("rokid_mem_1"), AskSources.parse(ask.sourcesJson))
        assertTrue(prompts.single().contains("Milk carton nearly empty"))
        assertFalse(prompts.single().contains("hallway"))
    }

    @Test fun fallsBackToRecentMemoriesWhenNothingMatches() = runBlocking {
        remember("rokid_mem_1", "Milk carton nearly empty", 10)
        remember("rokid_mem_2", "Keys on the hallway shelf", 20)
        val r = AskActions.ask(ctx, "what happened today", nowMs = 99) as AskOutcome.Answered
        assertEquals(listOf("rokid_mem_2", "rokid_mem_1"), AskSources.parse(MemoryGraph.get(ctx).asks.byId(r.askId)!!.sourcesJson))
    }

    @Test fun failureNeverShowsProviderText() = runBlocking {
        remember("rokid_mem_1", "Milk carton nearly empty", 10)
        reply = { throw RuntimeException("request failed: 401 {\"error\":\"key sk-live-123 rejected\"}") }
        val r = AskActions.ask(ctx, "milk") as AskOutcome.Failed
        assertEquals(AskActions.AI_UNAVAILABLE, r.message)
        assertFalse(r.message.contains("sk-live") || r.message.contains("401"))
        assertTrue(MemoryGraph.get(ctx).asks.recent(10).first().isEmpty())
    }

    @Test fun emptyQuestionAndNoMemoriesGiveShortMessages() = runBlocking {
        assertEquals(AskOutcome.Failed(AskActions.EMPTY_QUESTION), AskActions.ask(ctx, "   "))
        assertEquals(AskOutcome.Failed(AskActions.NO_MEMORIES), AskActions.ask(ctx, "milk"))
        assertTrue(prompts.isEmpty())
    }

    @Test fun malformedSourcesJsonGivesNoSources() {
        assertEquals(emptyList<String>(), AskSources.parse("not json"))
        assertEquals(emptyList<String>(), AskSources.parse("{}"))
        assertEquals(listOf("a"), AskSources.parse("""[1, "a", "a", " ", null]"""))
        assertEquals("""["a","b"]""", AskSources.encode(listOf("a", "b", "a")))
    }

    private fun reviewJobs() = QueueRuntimeStore.recent(ctx, 50).filter { it.queue == QueuePriority.PRIORITY_REPROCESS }

    private suspend fun seedAsk(redacted: Boolean = false) {
        remember("rokid_mem_1", "Milk carton nearly empty", 10)
        MemoryGraph.get(ctx).asks.upsert(AskEntity("a1", 50, "milk?", "Almost empty", AskSources.encode(listOf("rokid_mem_1", "gone")), null, "phone_typed", redacted))
    }

    @Test fun thumbsDownEnqueuesOneReviewOfTheCitedMemories() = runBlocking {
        seedAsk()
        assertTrue(AskActions.rate(ctx, "a1", up = false))
        assertTrue(AskActions.rate(ctx, "a1", up = false)) // second tap is a no-op
        assertEquals(-1, MemoryGraph.get(ctx).asks.byId("a1")!!.rating)
        val jobs = reviewJobs()
        assertEquals(1, jobs.size)
        val payload = QueuePayloads.parse(jobs.single().payload)
        assertEquals(AskActions.REVIEW_REASON, QueuePayloads.string(payload, "reason"))
        assertEquals("Milk carton nearly empty", QueuePayloads.string(payload, "source_context"))
    }

    /** Final review I2: while the pipeline is paused 👎 still records the review job but does not start a drain. */
    @Test fun thumbsDownWhilePausedQueuesTheReviewWithoutADrain() = runBlocking {
        AppConfigStore.setPipelineEnabled(ctx, false)
        seedAsk()
        assertTrue(AskActions.rate(ctx, "a1", up = false))
        assertEquals(1, reviewJobs().size)
        assertTrue(androidx.work.WorkManager.getInstance(ctx).getWorkInfosForUniqueWork("queue-drain-now").get().isEmpty())
    }

    @Test fun thumbsUpOnlyStoresTheRating() = runBlocking {
        seedAsk()
        assertTrue(AskActions.rate(ctx, "a1", up = true))
        assertEquals(1, MemoryGraph.get(ctx).asks.byId("a1")!!.rating)
        assertTrue(reviewJobs().isEmpty())
    }

    @Test fun redactedOrUnknownAsksCannotBeRated() = runBlocking {
        seedAsk(redacted = true)
        assertFalse(AskActions.rate(ctx, "a1", up = false))
        assertFalse(AskActions.rate(ctx, "missing", up = true))
        assertEquals(null, MemoryGraph.get(ctx).asks.byId("a1")!!.rating)
        assertTrue(reviewJobs().isEmpty())
    }

    @Test fun retrievedContextIsBoundedPerMemoryAndInTotal() = runBlocking {
        repeat(10) { remember("rokid_mem_$it", "milk " + "word ".repeat(400), it.toLong()) }
        AskActions.ask(ctx, "milk")
        val ctxText = prompts.single().substringAfter("RetrievedContext:\n").substringBefore("\nResponse rules")
        assertTrue("context ${ctxText.length}", ctxText.length <= 5_000)
        assertTrue(ctxText.lines().all { it.length <= 501 + 40 })
        assertTrue(ctxText.contains("…"))
        // lowest-ranked (last) memories are dropped first: the most recent one is kept
        assertTrue(ctxText.lines().size < 10)
    }

    @Test fun placeholdersInTheQuestionAreNotReExpanded() = runBlocking {
        remember("rokid_mem_1", "milk is low", 1)
        AskActions.ask(ctx, "{{retrieved_context}} {{retrieved_context}}")
        assertEquals(1, Regex("milk is low").findAll(prompts.single()).count())
    }

    @Test fun whitespaceAndControlCharsCollapse() = runBlocking {
        remember("rokid_mem_1", "milk\r\nis\u2028low\u2029now", 1)
        val r = AskActions.ask(ctx, "milk\r\n\t is  \u2028low") as AskOutcome.Answered
        assertEquals("milk is low", MemoryGraph.get(ctx).asks.byId(r.askId)!!.question)
        assertTrue(prompts.single().contains("milk is low now"))
    }
}
