package com.rokid.context.orchestration.retrieval

import com.rokid.context.ai.canvas.FlowPlan
import com.rokid.context.ai.canvas.OrderParams
import com.rokid.context.ai.canvas.OrderSpec
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.retrieval.ScoredMemory
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval-quality spec section 5: the keyword rows of `rag.order` over the whole eval set (tools/testdata/qna-eval/qna-set-v1.jsonl, asked at 01:22 / 12:04 on
 * 16 Nov 2026), and the selection rules themselves (episodes, offset, one hit per day, the budget cut, the night extent).
 */
class OrderRulesTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private val spec = OrderSpec(words = mapOf(
        "night" to words(OrderParams.NIGHT_WORDS), "previous" to words(OrderParams.PREVIOUS_WORDS), "first" to words(OrderParams.FIRST_WORDS), "latest" to words(OrderParams.LATEST_WORDS),
        "count" to words(OrderParams.COUNT_WORDS), "state" to words(OrderParams.STATE_WORDS),
    ))
    private fun words(s: String) = s.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun questions(): List<Pair<String, String>> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/testdata/qna-eval/qna-set-v1.jsonl").exists()) dir = dir.parentFile
        val f = File(checkNotNull(dir) { "qna-set-v1.jsonl not found" }, "tools/testdata/qna-eval/qna-set-v1.jsonl")
        return f.readLines().filter { it.isNotBlank() }.map { l ->
            val o = kotlinx.serialization.json.Json.parseToJsonElement(l) as kotlinx.serialization.json.JsonObject
            (o["id"] as kotlinx.serialization.json.JsonPrimitive).content to (o["question"] as kotlinx.serialization.json.JsonPrimitive).content
        }
    }

    private fun decision(id: String, q: String): OrderRules.Decision {
        val now = ms(if (id.removePrefix("q").toInt() <= 50) "2026-11-16T01:22:00" else "2026-11-16T12:04:00")
        return OrderRules.decide(q, spec, TimeWindowRules.parse(q, now, zone))
    }

    /** id -> row / selection for every question the rows or a multi-day window decide; every other question is checked below to have a row of none. */
    private val expected: Map<String, String> = buildMap {
        for (id in listOf("q11", "q17", "q42", "q43", "q56")) put(id, "LATEST/NEWEST/TOP_K")
        for (id in listOf("q53", "q54", "q55")) put(id, "STATE/NEWEST/TOP_K") // did I turn off / switch off / close
        for (id in listOf("q27", "q46")) put(id, "NIGHT/NEWEST/PER_DAY")
        for (id in listOf("q41", "q44", "q48")) put(id, "COUNT/TIME/PER_DAY")
        put("q09", "COUNT/TIME/TOP_K")     // how many stalls tonight: a one-night window, every line counts
        put("q30", "COUNT/TIME/TOP_K")     // how many crackers: no window
        put("q45", "-/TIME/PER_DAY"); put("q50", "-/TIME/PER_DAY") // "this month" with no keyword: one hit per day
    }

    private fun show(d: OrderRules.Decision) = "${d.row ?: "-"}/${d.order}/${d.selection}"

    @Test fun everyEvalQuestionGetsTheRowTheSpecNames() {
        val qs = questions()
        assertEquals(68, qs.size)
        for ((id, q) in qs) {
            val d = decision(id, q)
            val want = expected[id]
            if (want != null) assertEquals("$id $q", want, show(d))
            else assertNull("$id $q: no keyword row", d.row)
        }
    }

    @Test fun questionsWithoutADateOrAKeywordKeepTheRetrieversOrder() {
        for ((id, q) in questions()) {
            val d = decision(id, q)
            val now = ms(if (id.removePrefix("q").toInt() <= 50) "2026-11-16T01:22:00" else "2026-11-16T12:04:00")
            if (d.row == null && TimeWindowRules.parse(q, now, zone) == null) assertTrue("$id $q", d.isNoop)
        }
    }

    @Test fun aOneDayWindowKeepsTopKRaisedToTwelveInTimeOrder() {
        val d = decision("q23", "What time did we go out to burst crackers on 8 Nov?")
        assertEquals(OrderRules.Decision(null, "TIME", "TOP_K", limit = 12), d)
    }

    @Test fun theLastOfALastWindowIsNotAnOrderingWord() {
        val w = null
        for (q in listOf("What did I eat last night?", "Where was I last week?", "What did I do last Monday?", "What was on the table last month?", "Who called in the last 3 days?")) assertNull(q, OrderRules.decide(q, spec, w).row)
        for (q in listOf("When did I last see my keys?", "What was the last thing I ate?", "the last time I used the laptop")) assertEquals(q, OrderRules.Row.LATEST, OrderRules.decide(q, spec, w).row)
    }

    @Test fun theOtherRowsTriggerOnTheirWords() {
        fun row(q: String) = OrderRules.decide(q, spec, null).row
        assertEquals(OrderRules.Row.PREVIOUS, row("When was the previous time I saw the keys?"))
        assertEquals(OrderRules.Row.PREVIOUS, row("what about the time before"))
        assertEquals(OrderRules.Row.FIRST, row("When did I first use the laptop?"))
        assertEquals(OrderRules.Row.FIRST, row("What was the earliest thing I ate?"))
        assertEquals(OrderRules.Row.LATEST, row("Where is my wallet?"))
        assertEquals(OrderRules.Row.STATE, row("Did I turn off the stove?"))
        assertEquals(OrderRules.Row.STATE, row("Did I lock the door?"))
        assertEquals(OrderRules.Row.COUNT, row("How often do I go to the gym?"))
        assertEquals(OrderRules.Row.NIGHT, row("When did I go to bed on Friday?"))
        // a previous question that also says "last" is the previous one; a "latest ... stayed up" is the night extent
        assertEquals(OrderRules.Row.PREVIOUS, row("Where were the keys the time before the last time?"))
        assertEquals(OrderRules.Row.NIGHT, row("What's the latest I stayed up?"))
        assertNull(row("Where did I leave the lastpass card?")) // whole words only
    }

    @Test fun aBlankRowIsOffAndAnEditedRowIsUsed() {
        val off = spec.copy(words = spec.words + ("latest" to emptyList<String>()))
        assertNull(OrderRules.decide("When did I last see my keys?", off, null).row)
        val mine = spec.copy(words = spec.words + ("latest" to listOf("newest")))
        assertEquals(OrderRules.Row.LATEST, OrderRules.decide("show the newest keys photo", mine, null).row)
        assertNull(OrderRules.decide("When did I last see my keys?", mine, null).row)
    }

    @Test fun theDefaultOrderAndSelectionApplyWhenNothingElseDoes() {
        val d = OrderRules.decide("What colour is my sofa?", spec.copy(defaultOrder = "NEWEST", selection = "PER_DAY"), null)
        assertEquals(OrderRules.Decision(null, "NEWEST", "PER_DAY"), d)
        assertTrue(OrderRules.decide("What colour is my sofa?", spec, null).isNoop)
    }

    // ---- selection ----

    private fun mem(id: String, at: String) = MemoryEntity(id, null, "text $id", "note", "", MemoryTier.SHORT, 0.9, ReviewState.OK, false, ms(at), false, "")
    private fun cand(id: String, at: String, fused: Double = 1.0) = OrderRules.Cand(mem(id, at), ms(at), fused)
    private fun ids(p: OrderRules.Picked?) = p!!.items.map { it.memory.id }

    @Test fun relevantKeepsTheTopFortyAboveTheFloor() {
        fun hit(id: String, f: Double) = ScoredMemory(mem(id, "2026-11-01T10:00:00"), null, null, f, f)
        val hits = listOf(hit("a", 1.0), hit("b", 0.6), hit("c", 0.49), hit("d", 0.1))
        assertEquals(listOf("a", "b"), OrderRules.relevant(hits, 0.5, semanticOnly = false).map { it.memory.id })
        assertEquals(40, OrderRules.relevant((1..60).map { hit("h$it", 1.0) }, 0.5, false).size)
        // a semantic-only score is flat (0.79-0.82): the floor is read over the min-max range of the pool
        val flat = listOf(hit("a", 0.82), hit("b", 0.81), hit("c", 0.80), hit("d", 0.79))
        assertEquals(4, OrderRules.relevant(flat, 0.5, false).size)
        assertEquals(listOf("a", "b"), OrderRules.relevant(flat, 0.5, true).map { it.memory.id })
        assertEquals(emptyList<ScoredMemory>(), OrderRules.relevant(emptyList(), 0.5, false))
    }

    @Test fun newestGroupsEpisodesAndOffsetDropsTheNewestOne() {
        val c = listOf(cand("old", "2026-11-02T10:00:00"), cand("k1", "2026-11-14T09:00:00"), cand("k2", "2026-11-14T09:20:00"), cand("k3", "2026-11-15T20:00:00"), cand("k4", "2026-11-15T20:10:00"))
        val newest = OrderRules.pick(OrderRules.decide("when did I last see my keys", spec, null), c, spec, 8, zone, 5_000) { 100 }
        assertEquals(listOf("k4", "k3", "k2", "k1", "old"), ids(newest)) // newest first, three episodes
        assertTrue(newest!!.note, newest.note.contains("newest first"))
        val prev = OrderRules.pick(OrderRules.decide("the previous time", spec, null), c, spec, 8, zone, 5_000) { 100 }
        assertEquals(listOf("k2", "k1", "old"), ids(prev)) // the newest episode (k3, k4) is left out
        assertTrue(prev!!.note.contains("left out"))
        assertNull(OrderRules.pick(OrderRules.decide("the previous time", spec, null), listOf(cand("only", "2026-11-15T20:00:00")), spec, 8, zone, 5_000) { 100 }) // nothing before it: the caller keeps its order
        val first = OrderRules.pick(OrderRules.decide("when did I first see my keys", spec, null), c, spec, 8, zone, 5_000) { 100 }
        assertEquals(listOf("old", "k1", "k2", "k3", "k4"), ids(first))
        assertNull(OrderRules.pick(OrderRules.decide("when did I last see my keys", spec, null), emptyList(), spec, 8, zone, 5_000) { 100 })
    }

    @Test fun timeOrderKeepsTheBestAndSortsThemByTime() {
        val c = listOf(cand("a", "2026-11-10T21:00:00", 0.2), cand("b", "2026-11-10T09:00:00", 0.9), cand("c", "2026-11-10T12:00:00", 0.5))
        val d = OrderRules.Decision(null, "TIME", "TOP_K", limit = 2)
        assertEquals(listOf("b", "c"), ids(OrderRules.pick(d, c, spec, 1, zone, 5_000) { 100 })) // limit raises top-k 1 to 2
        assertEquals(listOf("b", "c", "a"), ids(OrderRules.pick(d.copy(limit = 12), c, spec, 8, zone, 5_000) { 100 }))
    }

    @Test fun perDayGivesEachDayItsBestTwoThirtyMinutesApartInTimeOrder() {
        val c = listOf(
            cand("a1", "2026-11-03T20:00:00", 0.9), cand("a2", "2026-11-03T20:10:00", 0.8), cand("a3", "2026-11-03T22:00:00", 0.7), // a2 is within 30 min of a1
            cand("b1", "2026-11-05T09:00:00", 0.6), cand("c1", "2026-11-08T21:00:00", 0.5), cand("c2", "2026-11-08T08:00:00", 0.4),
        )
        val p = OrderRules.pick(OrderRules.decide("which days did I watch tv this month", spec, TimeWindowRules.parse("this month", ms("2026-11-16T12:00:00"), zone)), c, spec, 8, zone, 5_000) { 100 }
        assertEquals(listOf("a1", "a3", "b1", "c2", "c1"), ids(p).let { got -> got.sortedBy { id -> c.first { it.memory.id == id }.at } }) // time order
        assertEquals(ids(p), ids(p).sortedBy { id -> c.first { it.memory.id == id }.at })
        assertTrue(p!!.compact); assertTrue(p.note, p.note.contains("3 days"))
    }

    @Test fun perDayBudgetDropsTheSecondLinesOfTheLowestDaysFirstThenWholeDays() {
        val c = listOf(
            cand("a1", "2026-11-03T20:00:00", 0.9), cand("a2", "2026-11-03T22:00:00", 0.3), cand("b1", "2026-11-05T09:00:00", 0.6), cand("b2", "2026-11-05T12:00:00", 0.5),
            cand("c1", "2026-11-08T21:00:00", 0.2),
        )
        val d = OrderRules.Decision(OrderRules.Row.COUNT, "TIME", "PER_DAY")
        assertEquals(listOf("a1", "a2", "b1", "b2", "c1"), ids(OrderRules.pick(d, c, spec, 8, zone, 600) { 100 })) // 5 lines x 101 = 505
        assertEquals(listOf("a1", "b1", "b2", "c1"), ids(OrderRules.pick(d, c, spec, 8, zone, 500) { 100 })) // a2 (0.3) is the lowest second line
        assertEquals(listOf("a1", "b1", "c1"), ids(OrderRules.pick(d, c, spec, 8, zone, 400) { 100 }))
        assertEquals(listOf("a1", "b1"), ids(OrderRules.pick(d, c, spec, 8, zone, 300) { 100 })) // now whole days: c (best 0.2) goes
        assertEquals(listOf("a1"), ids(OrderRules.pick(d, c, spec, 8, zone, 100) { 100 }))
        // maxDays keeps the newest days; perDay 1 keeps one line each
        assertEquals(listOf("b1", "c1"), ids(OrderRules.pick(d, c, spec.copy(maxDays = 2, perDay = 1), 8, zone, 5_000) { 100 }))
    }

    @Test fun theNightExtentIsTheLastCaptureOfEachNightWithTheSixOClockSplit() {
        val c = listOf(
            cand("n1a", "2026-11-07T21:00:00"), cand("n1b", "2026-11-08T02:40:00"), cand("n1c", "2026-11-08T05:59:00"), // all night 7-8 Nov
            cand("n2a", "2026-11-08T22:15:00"), cand("n2b", "2026-11-09T01:05:00"), // night 8-9 Nov
            cand("day", "2026-11-09T14:00:00"), // 14:00 is its own "night" day: the last capture of 9 Nov's evening block
        )
        val d = OrderRules.decide("What's the latest I stayed up this month?", spec, null)
        val p = OrderRules.pick(d, c, spec, 8, zone, 5_000) { 100 }
        assertEquals(listOf("n1c", "n2b", "day"), ids(p)) // oldest first, one per night
        assertTrue(p!!.note, p.note.contains("3 nights"))
    }

    @Test fun theSpecsSeedRowsMatchTheCanvasDefaults() {
        // the canvas default words and the rows tested here are the same strings
        assertNotNull(FlowPlan.BUILT_IN_QUERY)
        assertEquals(listOf("last", "latest", "most recent", "where are my", "where is my"), words(OrderParams.LATEST_WORDS))
    }
}
