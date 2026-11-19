package com.rokid.context.orchestration.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ShadowEvalTest {
    private val utc = ZoneId.of("UTC")
    private val t0 = 1_800_000_000_000L
    private val day = 24 * 3_600_000L
    private val f2 = ProactiveFamily.PLACE_RECALL
    private fun rec(n: Int, ts: Long) = SuggestionRecord("id$n", ts, "f2:wd:Q$n", f2, TriggerKind.TICK, 0.8, "wd:Q$n", listOf("mem:m$n"), listOf("visits=2"))
    private fun week(perDay: Int = 1, days: Int = 7) = (0 until days).flatMap { d -> (0 until perDay).map { k -> rec(d * 10 + k, t0 + d * day + k * 3_600_000L) } }
    private fun fb(up: Int, down: Int) = FeedbackState(families = mapOf(f2 to FamilyFeedback(up, down)))
    private fun input(records: List<SuggestionRecord> = week(), feedback: FeedbackState = fb(4, 1), full: Boolean = true) =
        ShadowEvalInput(records, mapOf("runs" to 300, "would_show" to records.size, "drop_cap_reached" to 4, "drop_global_cap" to 2, "drop_wear" to 9, "weird text" to 5), feedback, zone = utc,
            quietAt = if (full) { _: Long -> false } else null, zoneBlocked = if (full) { _: SuggestionRecord -> false } else null,
            drivingAt = if (full) { _: Long -> false } else null, wornAt = if (full) { _: Long -> true } else null)

    @Test fun aCleanWeekPasses() {
        val r = ShadowEval.evaluate(input())
        assertEquals(EvalVerdict.PASS, r.verdict); assertEquals(7, r.observedDays); assertEquals(7, r.wouldShowTotal); assertEquals(0, r.leaks.total()); assertEquals(6, r.budgetExhausted)
        assertEquals(mapOf("CAP_REACHED" to 4, "GLOBAL_CAP" to 2, "WEAR" to 9), r.refusedByGate); assertEquals(mapOf("f2" to 7), r.perCategory); assertEquals(0.8, r.usefulRate!!, 1e-9)
    }
    @Test fun aLeakFailsAndEachKindIsCounted() {
        val rs = week(); val bad = rs[0].ts
        val r = ShadowEval.evaluate(input(rs).copy(quietAt = { it == bad }, drivingAt = { it == bad }, wornAt = { it != bad }, zoneBlocked = { it.id == "id0" }))
        assertEquals(EvalVerdict.FAIL, r.verdict); assertEquals(LeakCounts(1, 1, 1, 1), r.leaks); assertEquals(1, r.days.first().leaks.quiet)
    }
    @Test fun aThrowingPredicateCountsAsALeak() {
        val r = ShadowEval.evaluate(input().copy(zoneBlocked = { throw IllegalStateException() }))
        assertEquals(EvalVerdict.FAIL, r.verdict); assertEquals(7, r.leaks.zone)
    }
    @Test fun overTheDailyCapFails() { assertEquals(EvalVerdict.FAIL, ShadowEval.evaluate(input(week(perDay = 4))).verdict) }
    @Test fun lowUsefulRateFails() { assertEquals(EvalVerdict.FAIL, ShadowEval.evaluate(input(feedback = fb(2, 4))).verdict) }
    @Test fun fewDaysFewThumbsNoRecordsOrUncheckedInvariantsAreNotEnoughData() {
        assertEquals(EvalVerdict.NOT_ENOUGH_DATA, ShadowEval.evaluate(input(week(days = 3))).verdict)
        assertEquals(EvalVerdict.NOT_ENOUGH_DATA, ShadowEval.evaluate(input(feedback = fb(1, 0))).verdict)
        assertEquals(EvalVerdict.NOT_ENOUGH_DATA, ShadowEval.evaluate(input(emptyList())).verdict)
        val u = ShadowEval.evaluate(input(full = false)); assertEquals(EvalVerdict.NOT_ENOUGH_DATA, u.verdict); assertTrue(u.toText().contains("quiet=UNCHECKED"))
    }
    @Test fun aLeakBeatsMissingData() { assertEquals(EvalVerdict.FAIL, ShadowEval.evaluate(input(week(days = 2)).copy(quietAt = { true })).verdict) }
    @Test fun feedbackRatesAreCountsOverWouldShow() {
        val st = FeedbackState().after(FeedbackAction.DISMISS, "a", f2, t0).after(FeedbackAction.SNOOZE, "b", f2, t0)
        val r = ShadowEval.evaluate(input(feedback = st).copy(neverCount = 7))
        assertEquals(1, r.dismissed); assertEquals(1, r.snoozed); assertEquals(1.0 / 7, r.dismissRate!!, 1e-9); assertEquals(1.0, r.neverRate!!, 1e-9)
    }
    @Test fun dailyCountersGiveAPerDayGateHistogram() {
        val d0 = java.time.Instant.ofEpochMilli(t0).atZone(utc).toLocalDate().toString()
        val r = ShadowEval.evaluate(input().copy(dailyCounters = mapOf(d0 to mapOf("runs" to 40, "drop_zone" to 3, "junk" to 9))))
        val d = r.days.first { it.day == d0 }; assertEquals(40, d.candidates); assertEquals(mapOf("ZONE" to 3), d.refusedByGate)
    }
    @Test fun theReportHoldsNoFreeTextFromTheRecords() {
        val secret = listOf("GolcondaFortSECRET", "Q1135340SECRET", "memSECRETid", "photoSECRETid", "Cafe Zurich SECRET")
        val rs = week().map { r -> r.copy(key = "f2:" + secret[0], anchorKey = "wd:" + secret[1], sourceIds = listOf("mem:" + secret[2], "frame:" + secret[3]), factors = listOf(secret[4])) }
        val st = FeedbackState().after(FeedbackAction.DISMISS, secret[0], f2, t0)
        val text = ShadowEval.evaluate(input(rs, st).copy(counters = mapOf("runs" to 5, "drop_" + secret[0] to 3, secret[4] to 2))).toText()
        secret.forEach { assertFalse("leaked $it", text.contains(it)) }
        assertFalse(text.contains("SECRET")); assertTrue(text.startsWith("shadow-eval verdict="))
    }
    @Test fun invalidDayKeysAreDroppedAndOnlyCounted() {
        val d0 = java.time.Instant.ofEpochMilli(t0).atZone(utc).toLocalDate().toString()
        val r = ShadowEval.evaluate(input().copy(dailyCounters = mapOf("Golconda Fort SECRET" to mapOf("runs" to 1), "2026-13-45" to mapOf("runs" to 1), "2026-1-5" to mapOf("runs" to 1), d0 to mapOf("runs" to 40))))
        assertEquals(3, r.invalidDayKeys); assertEquals(7, r.observedDays)
        val text = r.toText()
        assertFalse(text.contains("SECRET")); assertFalse(text.contains("Golconda")); assertTrue(text.contains("invalid day keys: 3"))
    }
    @Test fun aSnapshotAboveTheRetainedRecordsIsUsedAndFailsOverTheCap() {
        val d0 = java.time.Instant.ofEpochMilli(t0).atZone(utc).toLocalDate().toString()
        val r = ShadowEval.evaluate(input().copy(dailyCounters = mapOf(d0 to mapOf("runs" to 9, "would_show" to 4))))
        assertEquals(4, r.days.first { it.day == d0 }.wouldShow); assertEquals(4, r.maxShownInADay); assertEquals(1, r.incompleteDays)
        assertEquals(EvalVerdict.FAIL, r.verdict)
    }
    @Test fun anIncompleteSnapshotWithinTheCapIsNotEnoughDataNeverPass() {
        val d0 = java.time.Instant.ofEpochMilli(t0).atZone(utc).toLocalDate().toString()
        val r = ShadowEval.evaluate(input().copy(dailyCounters = mapOf(d0 to mapOf("would_show" to 2))))
        assertEquals(EvalVerdict.NOT_ENOUGH_DATA, r.verdict); assertTrue(r.reasons.any { it.contains("incomplete") })
    }
}
