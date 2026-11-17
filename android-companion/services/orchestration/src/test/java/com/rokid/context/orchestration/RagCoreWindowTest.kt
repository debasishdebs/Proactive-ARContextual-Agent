package com.rokid.context.orchestration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.ai.AiGraph
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalSettings
import com.rokid.context.orchestration.retrieval.WindowResolver
import java.time.LocalDateTime
import java.time.ZoneOffset
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

/**
 * Retrieval-quality spec sections 4 and 6: the soft filter (window, a day wider, no window), the golden note texts, the event time on the memory lines,
 * and "no window = no change" (the prompt vars of a question without a date are the old ones apart from the time format of the lines).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RagCoreWindowTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneOffset.UTC
    private fun at(s: String) = LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val now = at("2026-11-16T12:04:00")

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); TestAi.bindAll(TestAi.graph(ctx) { "{}" }) }
    @After fun tearDown() { AiGraph.resetForTests(null); MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private suspend fun mem(id: String, text: String, createdAt: String, frame: String? = null) =
        MemoryGraph.get(ctx).memories.add(MemoryEntity(id, frame, text, "note", "", MemoryTier.SHORT, 0.9, ReviewState.OK, false, at(createdAt), false, ""))

    private fun service(windows: WindowResolver = WindowResolver.Rules) = AnswerService(
        MemoryGraph.get(ctx), AiGraph.get(ctx).runner, AiGraph.get(ctx).retriever, { RetrievalSettings(mode = RetrievalMode.LEXICAL) },
        zone = { zone }, windows = windows,
    )

    private fun lines(r: AnswerService.Retrieved) = r.vars.retrievedMemories.lines().filter { it.startsWith("- ") }.map { it.removePrefix("- ") }

    @Test fun theWindowKeepsOnlyThatDayAndSaysSo() = runBlocking<Unit> {
        mem("m9", "Bought milk at the shop", "2026-11-09T20:00:00"); mem("m10", "Milk carton on the table", "2026-11-10T21:10:00"); mem("m12", "Milk jug", "2026-11-12T09:00:00")
        val r = service().retrieve("What milk did I see on 10 Nov?", now)
        assertEquals(listOf("m10"), r.memories.map { it.id })
        val l = lines(r)
        assertEquals("note: time window Tue 10 Nov 2026 00:00-23:59 (\"on 10 nov\"); the times on the memory lines are when they happened", l.first())
        assertTrue(l[1], l[1].startsWith("id=m10 · Tue 10 Nov 2026 21:10 · confidence 0.90 · frame=none · Milk carton on the table"))
        assertEquals(2, l.size)
    }

    @Test fun nothingInTheWindowWidensByADay() = runBlocking<Unit> {
        mem("m9", "Bought milk at the shop", "2026-11-09T20:00:00"); mem("m12", "Milk jug", "2026-11-12T09:00:00")
        val r = service().retrieve("What milk did I see on 10 Nov?", now)
        assertEquals(listOf("m9"), r.memories.map { it.id }) // 11 Nov is empty and 12 Nov is out of reach
        assertEquals("note: nothing matched \"on 10 nov\" (Tue 10 Nov 2026 00:00-23:59); widened to Mon 9 Nov 2026 00:00 - Wed 11 Nov 2026 23:59", lines(r).first())
    }

    @Test fun nothingNearDropsTheWindowAndSaysTheMemoriesAreFromOtherDates() = runBlocking<Unit> {
        mem("m1", "Bought milk at the shop", "2026-11-01T20:00:00")
        val r = service().retrieve("What milk did I see on 10 Nov?", now)
        assertEquals(listOf("m1"), r.memories.map { it.id })
        assertEquals("note: nothing matched the asked time (\"on 10 nov\"); these memories are from other dates", lines(r).first())
    }

    @Test fun anEmptyStoreAlsoSaysOtherDatesAndLeavesNoMemories() = runBlocking<Unit> {
        val r = service().retrieve("What milk did I see on 10 Nov?", now)
        assertTrue(r.memories.isEmpty())
        assertEquals("note: nothing matched the asked time (\"on 10 nov\"); these memories are from other dates", lines(r).first())
    }

    @Test fun noDateInTheQuestionMeansNoWindowAndNoNote() = runBlocking<Unit> {
        mem("m1", "Bought milk at the shop", "2026-11-01T20:00:00"); mem("m10", "Milk carton", "2026-11-10T21:10:00")
        val r = service().retrieve("What milk did I see?", now)
        assertEquals(setOf("m1", "m10"), r.memories.map { it.id }.toSet())
        assertFalse(lines(r).any { it.startsWith("note:") })
    }

    @Test fun theResolverSeamCanSwitchTheWindowOff() = runBlocking<Unit> {
        mem("m1", "Bought milk at the shop", "2026-11-01T20:00:00"); mem("m10", "Milk carton", "2026-11-10T21:10:00")
        val r = service(WindowResolver.None).retrieve("What milk did I see on 10 Nov?", now)
        assertEquals(setOf("m1", "m10"), r.memories.map { it.id }.toSet())
        assertFalse(lines(r).any { it.startsWith("note:") })
        val boom = service { _, _, _ -> throw IllegalStateException("resolver down") }.retrieve("milk on 10 Nov", now) // never fails the ask
        assertEquals(2, boom.memories.size)
    }

    @Test fun theLineShowsWhenThePhotoWasTakenNotWhenTheMemoryWasWritten() = runBlocking<Unit> {
        MemoryGraph.get(ctx).db.frameMetaDao().upsert(FrameMetaEntity("f1", null, null, "UNKNOWN", false, null, null, null, null, null, null, null, null, null, at("2026-11-16T11:40:00"), capturedAtMs = at("2026-11-10T21:39:00")))
        mem("late", "A man in a red jersey with a milk can", "2026-11-16T11:40:00", frame = "f1")
        val r = service(WindowResolver.None).retrieve("milk", now)
        assertTrue(lines(r).first(), lines(r).first().startsWith("id=late · Tue 10 Nov 2026 21:39 · "))
        // and the window finds it on the capture day, not on the write day
        assertEquals(listOf("late"), service().retrieve("milk on 10 Nov", now).memories.map { it.id })
    }

    @Test fun theNoteIsInsideTheCharBudget() = runBlocking<Unit> {
        mem("m10", "Milk carton", "2026-11-10T21:10:00")
        val c = service().rag.retrieve(RagQuery("milk on 10 Nov"), listOf(RagSource.MemoryStore(window = com.rokid.context.orchestration.retrieval.TimeWindowRules.parse("on 10 Nov", now, zone))), RagParams(maxChars = 120), now)
        assertNull("a budget that fits the note but not the line keeps no line", c.memoryLines.firstOrNull())
        assertTrue(c.windowNote != null)
        assertEquals(emptyList<String>(), c.memoryLines)
    }
}
