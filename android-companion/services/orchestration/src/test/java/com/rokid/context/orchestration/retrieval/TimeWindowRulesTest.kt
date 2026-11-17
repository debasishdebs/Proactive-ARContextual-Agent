package com.rokid.context.orchestration.retrieval

import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retrieval-quality spec section 3: the date-window rules, with fixed clocks. The eval set is read from `tools/testdata/qna-eval/qna-set-v1.jsonl`:
 * q01-q50 were asked at 01:22 and q51-q68 at 12:04 on 16 Nov 2026 (Asia/Kolkata), and EVERY question gets an expected window or an expected "none".
 */
class TimeWindowRulesTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun ms(zone: ZoneId, s: String) = LocalDateTime.parse(s.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli()
    private fun show(zone: ZoneId, ms: Long) = fmt.format(java.time.Instant.ofEpochMilli(ms).atZone(zone))
    private fun win(q: String, now: String, zone: ZoneId = kolkata): String? =
        TimeWindowRules.parse(q, ms(zone, now), zone)?.let { show(zone, it.fromMs) + " .. " + show(zone, it.toMs) }

    // ---- the eval set: every question ----

    private val morning = "2026-11-16 12:04"
    private val night = "2026-11-16 01:22"

    /** id -> expected `from .. to` (half-open, clipped to the ask time), for the questions that carry a date or a relative time. */
    private val expected: Map<String, String> = buildMap {
        for (id in listOf("q01", "q02", "q04", "q05", "q09")) put(id, "2026-11-15 18:00 .. 2026-11-16 01:22") // tonight, asked after midnight: the night still going on
        put("q16", "2026-11-12 00:00 .. 2026-11-13 00:00")
        put("q19", "2026-11-12 00:00 .. 2026-11-13 00:00")
        put("q23", "2026-11-08 00:00 .. 2026-11-09 00:00")
        put("q25", "2026-11-08 02:00 .. 2026-11-08 03:00")                 // around 2:30 AM on 8 Nov: +-30 min
        put("q27", "2026-11-07 18:00 .. 2026-11-08 06:00")                 // night of 7-8 Nov
        for (id in listOf("q31", "q34", "q35", "q37", "q38")) put(id, "2026-11-10 00:00 .. 2026-11-11 00:00")
        put("q33", "2026-11-10 20:40 .. 2026-11-10 21:40")                 // around 21:10 on 10 Nov
        put("q49", "2026-11-15 00:00 .. 2026-11-16 00:00")
        for (id in listOf("q41", "q44", "q45", "q46", "q48", "q50")) put(id, "2026-11-01 00:00 .. 2026-11-16 01:22") // this month
        for (id in listOf("q51", "q52", "q66")) put(id, "2026-11-16 00:00 .. 2026-11-16 12:04") // today, asked at 12:04
        for (id in listOf("q58", "q64", "q67")) put(id, "2026-11-16 05:00 .. 2026-11-16 12:00") // this morning
        put("q63", "2026-11-16 11:07 .. 2026-11-16 12:04")                 // around 11:37, clipped to the ask
        put("q68", "2026-11-16 11:30 .. 2026-11-16 12:04")                 // around noon, clipped to the ask
    }

    private fun evalQuestions(): List<Pair<String, String>> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "tools/testdata/qna-eval/qna-set-v1.jsonl").exists()) dir = dir.parentFile
        val f = File(checkNotNull(dir) { "qna-set-v1.jsonl not found above ${File("").absolutePath}" }, "tools/testdata/qna-eval/qna-set-v1.jsonl")
        return f.readLines().filter { it.isNotBlank() }.map { l ->
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(l)!!.groupValues[1]
            val q = kotlinx.serialization.json.Json.parseToJsonElement(l).let { (it as kotlinx.serialization.json.JsonObject)["question"] as kotlinx.serialization.json.JsonPrimitive }.content
            id to q
        }
    }

    @Test fun everyEvalQuestionHasItsExpectedWindowOrNone() {
        val qs = evalQuestions()
        assertEquals(68, qs.size)
        val bad = mutableListOf<String>()
        for ((id, q) in qs) {
            val now = if (id.removePrefix("q").toInt() <= 50) night else morning
            val got = win(q, now)
            if (got != expected[id]) bad += "$id \"$q\": expected ${expected[id]} got $got"
        }
        assertEquals(bad.joinToString("\n"), 0, bad.size)
        assertEquals(expected.keys, qs.map { it.first }.filter { it in expected }.toSet()) // every expectation names a real question
    }

    @Test fun questionsWithoutADateCueGetNoWindow() {
        // q11/q17/q42/q43/q56 are ORDERING questions ("last", "latest"): never a window here.
        for (q in listOf("When did I last see my keys?", "When did I last use my laptop?", "What's the latest I stayed up this month and when did I last sleep",
            "Where are my car keys?", "What was the last time I used the PlayStation controller?", "Did I burst crackers on Diwali?", "Who came by after breakfast?",
            "What did I do before the guest arrived?", "Which evenings did I go out?", "May I see the keys?", "What is my flat number?")) {
            val w = win(q, morning)
            if (q.contains("this month")) assertNotNull(w) else assertNull(q, w)
        }
        assertNull(win("", morning)); assertNull(win("   ", morning))
    }

    // ---- section 3 table: every phrase ----

    @Test fun relativeDaysAndPartsOfDay() {
        val now = "2026-11-16 15:30" // a Monday afternoon
        assertEquals("2026-11-16 00:00 .. 2026-11-16 15:30", win("what happened today", now))
        assertEquals("2026-11-15 00:00 .. 2026-11-16 00:00", win("what happened yesterday", now))
        assertEquals("2026-11-15 05:00 .. 2026-11-15 12:00", win("yesterday morning", now))
        assertEquals("2026-11-15 17:00 .. 2026-11-15 22:00", win("yesterday evening", now))
        assertEquals("2026-11-16 05:00 .. 2026-11-16 12:00", win("this morning", now))
        assertEquals("2026-11-16 12:00 .. 2026-11-16 15:30", win("this afternoon", now))
        assertNull(win("this evening", now)) // still ahead of the ask: never a future window
        assertEquals("2026-11-15 18:00 .. 2026-11-16 06:00", win("what did I do last night", now))
        assertNull(win("tonight", now))
    }

    @Test fun beforeSixTodayAndTonightMeanTheNightStillGoingOn() {
        val now = "2026-11-16 01:22"
        assertEquals("2026-11-15 00:00 .. 2026-11-16 01:22", win("today", now))
        assertEquals("2026-11-15 18:00 .. 2026-11-16 01:22", win("tonight", now))
        assertEquals("2026-11-15 18:00 .. 2026-11-16 01:22", win("last night", now))
        assertEquals("2026-11-15 05:00 .. 2026-11-15 12:00", win("this morning", now)) // 05:00 today is ahead: yesterday's
        assertTrue(TimeWindowRules.parse("tonight", ms(kolkata, now), kolkata)!!.nightly)
    }

    @Test fun explicitDates() {
        val now = "2026-11-16 12:04"
        for (q in listOf("on 10 Nov", "10 Nov", "Nov 10", "10th November", "on the 10th of November", "NOVEMBER 10", "10 nov")) assertEquals(q, "2026-11-10 00:00 .. 2026-11-11 00:00", win("what happened $q?", now))
        assertEquals("2026-11-10 17:00 .. 2026-11-10 22:00", win("10 Nov evening", now))
        assertEquals("2026-11-16 00:00 .. 2026-11-16 12:04", win("on 16 Nov", now)) // today, clipped to the ask
        assertEquals("2025-12-20 00:00 .. 2025-12-21 00:00", win("on 20 Dec", now))   // the latest occurrence not after now
        assertEquals("2026-03-02 00:00 .. 2026-03-03 00:00", win("2 March 2026", now))
        assertNull(win("on 30 Feb", now))
        assertNull(win("on 20 Dec 2026", now)) // an explicit future year is never a window
        assertEquals("2026-12-28 00:00 .. 2026-12-29 00:00", win("on 28 Dec", "2027-01-05 09:00")) // across New Year
    }

    @Test fun nightOf() {
        val now = "2026-11-16 12:04"
        assertEquals("2026-11-07 18:00 .. 2026-11-08 06:00", win("night of 7-8 Nov", now))
        assertEquals("2026-11-07 18:00 .. 2026-11-08 06:00", win("the night of 7 Nov", now))
        assertTrue(TimeWindowRules.parse("night of 7-8 Nov", ms(kolkata, now), kolkata)!!.nightly)
    }

    @Test fun weekdays() {
        val monday = "2026-11-16 12:04"
        assertEquals("2026-11-16 00:00 .. 2026-11-16 12:04", win("on Monday", monday))           // today when it is that weekday
        assertEquals("2026-11-09 00:00 .. 2026-11-10 00:00", win("last Monday", monday))         // a week back
        assertEquals("2026-11-13 00:00 .. 2026-11-14 00:00", win("on Friday", monday))
        assertEquals("2026-11-13 00:00 .. 2026-11-14 00:00", win("last Fri", monday))
        assertNull(win("the sun was out", monday)) // bare short names are words, not days
        assertEquals("2026-11-15 00:00 .. 2026-11-16 00:00", win("on sun", monday))
    }

    @Test fun weeksMonthsAndCounts() {
        val now = "2026-11-18 12:04" // a Wednesday
        assertEquals("2026-11-16 00:00 .. 2026-11-18 12:04", win("this week", now))
        assertEquals("2026-11-09 00:00 .. 2026-11-16 00:00", win("last week", now))
        assertEquals("2026-11-01 00:00 .. 2026-11-18 12:04", win("this month", now))
        assertEquals("2026-10-01 00:00 .. 2026-11-01 00:00", win("last month", now))
        assertEquals("2026-11-15 00:00 .. 2026-11-16 00:00", win("3 days ago", now))
        assertEquals("2026-11-16 00:00 .. 2026-11-17 00:00", win("two days ago", now))
        assertEquals("2026-11-18 09:04 .. 2026-11-18 12:04", win("in the last 3 hours", now))
        assertEquals("2026-11-15 12:04 .. 2026-11-18 12:04", win("in the last 3 days", now))
        assertEquals("2026-11-04 12:04 .. 2026-11-18 12:04", win("over the past two weeks", now))
    }

    @Test fun clockTimes() {
        val now = "2026-11-16 12:04"
        assertEquals("2026-11-16 08:30 .. 2026-11-16 09:30", win("around 9:00", now))
        assertEquals("2026-11-15 20:30 .. 2026-11-15 21:30", win("at 9 pm", now))     // 21:00 today is ahead of 12:04 only when later; 9 pm today has not happened: yesterday
        assertEquals("2026-11-16 11:30 .. 2026-11-16 12:04", win("about noon", now))
        assertEquals("2026-11-15 23:30 .. 2026-11-16 00:30", win("around midnight", now))
        assertEquals("2026-11-08 02:00 .. 2026-11-08 03:00", win("around 2:30 AM on 8 Nov", now))
        assertEquals("2026-11-08 02:00 .. 2026-11-08 03:00", win("around 2:30 a.m. on 8 Nov", now))
        assertEquals("2026-11-10 00:00 .. 2026-11-10 21:10", win("before 21:10 on 10 Nov", now))
        assertEquals("2026-11-10 21:10 .. 2026-11-11 00:00", win("after 9:10 pm on 10 Nov", now))
        assertEquals("2026-11-16 00:00 .. 2026-11-16 08:00", win("before 8:00 today", now))
        assertEquals("2026-11-16 09:00 .. 2026-11-16 12:04", win("after 9 am", now))
        assertEquals("2026-11-15 08:30 .. 2026-11-15 09:30", win("yesterday at 9:00", now))
        // a clock time next to a week or a night keeps the wider window
        assertEquals("2026-11-09 00:00 .. 2026-11-16 00:00", win("around 5 pm last week", now))
        assertNull(win("at 25:99", now))
    }

    @Test fun combinedDayAndPart() {
        assertEquals("2026-11-10 05:00 .. 2026-11-10 12:00", win("on 10 Nov morning", "2026-11-16 12:04"))
        assertEquals("2026-11-13 12:00 .. 2026-11-13 17:00", win("on Friday afternoon", "2026-11-16 12:04"))
    }

    @Test fun windowsAreClippedAndLabelled() {
        val now = ms(kolkata, "2026-11-16 12:04")
        val w = TimeWindowRules.parse("on 10 Nov", now, kolkata)!!
        assertEquals("Tue 10 Nov 2026 00:00-23:59", w.label)
        assertEquals("on 10 nov", w.phrase)
        assertEquals(TimeWindow.Source.RULES, w.source)
        assertTrue(!w.nightly)
        val today = TimeWindowRules.parse("today", now, kolkata)!!
        assertEquals("Mon 16 Nov 2026 00:00-12:04", today.label)
        assertEquals(now, today.toMs)
        assertEquals("Sat 7 Nov 2026 18:00 - Sun 8 Nov 2026 05:59", TimeWindowRules.parse("night of 7-8 Nov", now, kolkata)!!.label)
    }

    @Test fun widenedIsADayEitherSideClippedToNow() {
        val now = ms(kolkata, "2026-11-16 12:04")
        val w = TimeWindowRules.parse("on 10 Nov", now, kolkata)!!
        val wide = TimeWindowRules.widened(w, now, kolkata)!!
        assertEquals("2026-11-09 00:00 .. 2026-11-12 00:00", show(kolkata, wide.fromMs) + " .. " + show(kolkata, wide.toMs))
        assertEquals("Mon 9 Nov 2026 00:00 - Wed 11 Nov 2026 23:59", wide.label)
        val today = TimeWindowRules.parse("today", now, kolkata)!!
        assertEquals(now, TimeWindowRules.widened(today, now, kolkata)!!.toMs)
    }

    @Test fun dstDaysAreTwentyThreeOrTwentyFiveHours() {
        val now = "2026-10-26 10:00" // the day after Europe/Berlin leaves DST (25 Oct 2026)
        val end = TimeWindowRules.parse("on 25 Oct", ms(berlin, now), berlin)!!
        assertEquals(25 * 3_600_000L, end.toMs - end.fromMs)
        assertEquals("2026-10-25 00:00 .. 2026-10-26 00:00", show(berlin, end.fromMs) + " .. " + show(berlin, end.toMs))
        val spring = TimeWindowRules.parse("on 29 Mar", ms(berlin, "2026-03-30 10:00"), berlin)!!
        assertEquals(23 * 3_600_000L, spring.toMs - spring.fromMs)
        // "tonight" across the autumn change is 18:00 -> 06:00 local, not 12 elapsed hours
        val night = TimeWindowRules.parse("night of 24 Oct", ms(berlin, now), berlin)!!
        assertEquals("2026-10-24 18:00 .. 2026-10-25 06:00", show(berlin, night.fromMs) + " .. " + show(berlin, night.toMs))
        assertEquals(13 * 3_600_000L, night.toMs - night.fromMs)
    }

    @Test fun sameNowSameZoneIsDeterministicAndPure() {
        val now = ms(kolkata, "2026-11-16 12:04")
        assertEquals(TimeWindowRules.parse("on 10 Nov at 9 pm", now, kolkata), TimeWindowRules.parse("on 10 Nov at 9 pm", now, kolkata))
        // the other zone sees another local date for the same instant
        assertEquals("2026-11-16 00:00 .. 2026-11-16 12:04", win("today", "2026-11-16 12:04"))
        val utc = ZoneId.of("UTC")
        assertEquals("2026-11-16 00:00 .. 2026-11-16 06:34", show(utc, TimeWindowRules.parse("today", now, utc)!!.fromMs) + " .. " + show(utc, TimeWindowRules.parse("today", now, utc)!!.toMs))
    }
}
