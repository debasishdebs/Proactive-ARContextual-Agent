package com.rokid.context.orchestration.proactive

import com.rokid.context.orchestration.proactive.generators.MarkInfo
import com.rokid.context.orchestration.proactive.generators.PinInfo
import com.rokid.context.orchestration.proactive.generators.PlaceRecallGenerator
import com.rokid.context.orchestration.proactive.generators.PlaceVisit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class PlaceRecallGeneratorTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = 1_800_000_000_000L
    private fun visit(id: String, key: String, name: String, ts: Long, parent: String? = null, frame: String? = "f_$id") = PlaceVisit(id, frame, key, name, parent, ts, 0.8)
    private val gen = { h: FakeHistory -> PlaceRecallGenerator(h) { zone } }
    private suspend fun run(h: FakeHistory, blocked: suspend (String) -> Boolean = { false }) = gen(h).generate(now, blocked)

    @Test fun recallsTheLastEarlierVisitToTheSamePlace() = runBlocking {
        val h = FakeHistory(listOf(visit("now", "wd:Q1", "Golconda", now - 60_000), visit("old1", "wd:Q1", "Golconda", now - 6 * DAY), visit("old2", "wd:Q1", "Golconda", now - 20 * DAY)))
        val c = run(h).candidates.single()
        assertEquals("f2:wd:Q1", c.key); assertEquals(2, c.facts.earlierVisits); assertEquals(now - 6 * DAY, c.facts.lastVisitMs)
        assertEquals(listOf("mem:old1", "frame:f_old1"), c.sourceIds)
        assertTrue(Templates.placeRecall(c.facts, zone).startsWith("Golconda: last visit "))
    }

    @Test fun nothingFromTheVisitInProgressOrWithoutHistory() = runBlocking {
        val h = FakeHistory(listOf(visit("now", "wd:Q1", "Golconda", now - 60_000), visit("earlier_today", "wd:Q1", "Golconda", now - 3_600_000)))
        assertTrue(run(h).candidates.isEmpty()) // an hour ago is the same day: not a recall
        assertTrue(run(FakeHistory(listOf(visit("old", "wd:Q1", "Golconda", now - 6 * DAY)))).candidates.isEmpty()) // no arrival in the window
    }

    @Test fun theParentSiteVisitCounts() = runBlocking {
        val h = FakeHistory(listOf(visit("now", "wd:Q2", "Bala Hissar", now - 1_000, parent = "Golconda"), visit("site", "wd:Q1", "Golconda", now - 9 * DAY)))
        assertEquals(listOf("mem:site", "frame:f_site"), run(h).candidates.single().sourceIds)
    }

    @Test fun pinNoteAndMarksAreSources() = runBlocking {
        val pin = "user:00000000-0000-0000-0000-000000000001"
        val h = FakeHistory(listOf(visit("now", pin, "Lion's Paw", now - 1_000), visit("old", pin, "Lion's Paw", now - 3 * DAY)), listOf(PinInfo(pin, "Lion's Paw", "sunset call from the rock")), listOf(MarkInfo("mk1", "f_old", now - 3 * DAY)))
        val c = run(h).candidates.single()
        assertTrue(c.sourceIds.containsAll(listOf("mem:old", "pin:$pin", "mark:mk1")))
        assertEquals("sunset call from the rock", c.facts.note); assertEquals(1, c.facts.markCount)
        assertTrue(Templates.placeRecall(c.facts, zone).contains("Your note: “sunset call from the rock”"))
        assertTrue(c.confidence > 0.9)
    }

    @Test fun aPlaceInsideAZoneIsDroppedAndFailsClosed() = runBlocking {
        val h = FakeHistory(listOf(visit("now", "wd:Q1", "Golconda", now - 1_000), visit("old", "wd:Q1", "Golconda", now - 6 * DAY)))
        val g = run(h) { true }
        assertTrue(g.candidates.isEmpty()); assertEquals(1, g.zoneDropped)
        assertEquals(1, run(h) { throw IllegalStateException("unreadable") }.zoneDropped)
    }

    @Test fun renderRebuildsTheCardFromLiveSourcesOnly() = runBlocking {
        val h = FakeHistory(listOf(visit("now", "wd:Q1", "Golconda", now - 1_000), visit("old", "wd:Q1", "Golconda", now - 6 * DAY)))
        val g = gen(h)
        val c = g.generate(now) { false }.candidates.single()
        val rec = SuggestionRecord("sg_1", now, c.key, c.family, TriggerKind.PLACE_ARRIVAL, 0.7, c.anchorKey, c.sourceIds, PlaceRecallGenerator.factors(c))
        assertTrue(g.render(rec, now)!!.text.startsWith("Golconda: last visit"))
        h.visits = h.visits.filter { it.memoryId != "old" } // forgotten
        assertNull(g.render(rec, now))
    }

    @Test fun templatesNeverNameAPersonOrClaimAFact() {
        val t = Templates.placeRecall(PlaceFacts("Golconda", now, 3, null, 0), zone)
        assertTrue("3 earlier visits." in t); assertTrue(!t.contains("safe") && !t.contains(" off"))
        assertEquals("from 2 items · ${Templates.date(now, zone)}", Templates.sourceLabel(2, now, zone))
        assertTrue(Templates.placeRecall(PlaceFacts("X", now, 1, "a".repeat(500), 0), zone).length <= Templates.MAX_TEXT)
    }
}
