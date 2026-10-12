package com.rokid.context.memory

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.NudgeEntity
import com.rokid.context.memory.db.ProposalEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.SummaryTraceEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FeedRepositoriesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val memories = MemoryRepository(db.memoryDao())
    private val frames = FrameRepository(db.frameDao())
    private val traces = TraceRepository(db.traceDao())
    private val timeline = TimelineRepository(db.frameViewDao(), db.memoryViewDao(), db.traceViewDao())
    private val edits = MemoryEdits(db.memoryViewDao(), memories)
    private val asks = AskRepository(db.askDao())
    private val nudges = NudgeRepository(db.nudgeDao())
    private val proposals = ProposalRepository(db.proposalDao())
    private val day = DayBucket.bounds(LocalDate.of(2026, 10, 12), ZoneId.of("Asia/Kolkata"))

    @After fun close() = db.close()

    private fun m(id: String, text: String, at: Long, review: ReviewState = ReviewState.OK) =
        MemoryEntity(id, null, text, "short_term", "", MemoryTier.SHORT, 0.8, review, false, at, false, searchText = "")

    @Test fun visibleMemoriesExcludeReviewInvalidatedAndOtherDays() = runTest {
        memories.add(m("ok", "Keys on hallway shelf", day.first + 1))
        memories.add(m("rev", "Critic low confidence", day.first + 2, ReviewState.NEEDS_REVIEW))
        memories.add(m("inv", "Forgotten", day.first + 3, ReviewState.INVALIDATED))
        memories.add(m("old", "Yesterday", day.first - 1))
        assertEquals(listOf("ok"), timeline.visibleMemoriesIn(day).first().map { it.id })
        assertEquals(listOf(day.first + 1), timeline.visibleMemoryTimesIn(day).first())
        assertEquals(listOf("ok", "old"), timeline.recentVisibleMemories(10).first().map { it.id })
    }

    @Test fun memoryFlowUpdatesLiveAfterInsert() = runBlocking {
        val sawEmpty = CompletableDeferred<Unit>()
        val next = async(Dispatchers.IO) {
            timeline.visibleMemoriesIn(day).onEach { if (it.isEmpty()) sawEmpty.complete(Unit) }.first { it.isNotEmpty() }
        }
        sawEmpty.await()
        memories.add(m("new", "Fresh", day.first + 5))
        assertEquals(listOf("new"), withTimeout(5_000) { next.await() }.map { it.id })
    }

    @Test fun framesInRangeAreOldestFirstAndBounded() = runTest {
        frames.add(FrameEntity("b", day.first + 20, "/b.jpg", "rokid_glasses"))
        frames.add(FrameEntity("a", day.first + 10, "/a.jpg", "rokid_glasses"))
        frames.add(FrameEntity("next", day.last + 1, "/n.jpg", "rokid_glasses"))
        assertEquals(listOf("a", "b"), timeline.framesIn(day).first().map { it.id })
        assertEquals(2, timeline.frameTimesIn(day).first().size)
        assertEquals("/a.jpg", timeline.frame("a")?.path)
        assertEquals(listOf("a"), timeline.framesByIds(listOf("a", "a", "zzz")).map { it.id })
    }

    @Test fun editTextReindexesSearchAndRejectsBlankOrUnknown() = runTest {
        memories.add(m("m1", "Milk carton nearly empty", day.first))
        assertTrue(edits.editText("m1", "  Oat\tmilk bought\n "))
        assertEquals("Oat milk bought", timeline.memory("m1").first()?.text)
        assertEquals(listOf("m1"), memories.search("oat", 5).map { it.id })
        assertTrue(memories.search("carton", 5).isEmpty())
        assertFalse(edits.editText("m1", "   "))
        assertFalse(edits.editText("nope", "text"))
    }

    @Test fun trustSetsTheFlag() = runTest {
        memories.add(m("m1", "Keys", day.first))
        assertTrue(edits.trust("m1"))
        assertTrue(timeline.memory("m1").first()!!.trusted)
        assertFalse(edits.trust("missing"))
    }

    @Test fun asksAreNewestFirstAndRatingPersists() = runTest {
        asks.upsert(AskEntity("a1", day.first + 1, "q1", "a1", "[]", null, "phone_typed", false))
        asks.upsert(AskEntity("a2", day.first + 2, "q2", "a2", "[]", null, "phone_typed", false))
        assertEquals(listOf("a2", "a1"), asks.recent(10).first().map { it.id })
        asks.setRating("a1", -1)
        assertEquals(-1, asks.observe("a1").first()?.rating)
        assertEquals(null, asks.observe("missing").first())
        assertEquals(2, asks.timesIn(day).first().size)
    }

    @Test fun nudgesProposalsAndTracesReadBack() = runTest {
        assertTrue(nudges.addIgnore(NudgeEntity("n1", day.first + 3, "Milk low", "fridge", "[]", 1, "h1", "phone", false)))
        assertFalse(nudges.addIgnore(NudgeEntity("n1", day.first + 3, "dup", "", "[]", 1, "h1", "phone", false)))
        assertEquals(listOf("Milk low"), nudges.recent(5).first().map { it.text })
        assertEquals(listOf(day.first + 3), nudges.timesIn(day).first())
        proposals.addIgnore(ProposalEntity("p1", "ASK", "a1", "SHOPPING_ADD", "{}", "PENDING", 9, null, null, 1))
        assertEquals(listOf("p1"), proposals.forOrigin("a1").first().map { it.id })
        assertTrue(proposals.forOrigin("other").first().isEmpty())
        traces.upsert(SummaryTraceEntity("s1", "TRUSTED", 5, "ok"))
        assertEquals(listOf("s1"), timeline.recentTraces(10).first().map { it.summaryId })
    }

    @Test fun emptyIdListsNeverQuery() = runTest {
        assertTrue(timeline.memoriesByIds(emptyList()).isEmpty())
        assertTrue(timeline.framesByIds(emptyList()).isEmpty())
    }
}
