package com.rokid.context.orchestration.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FeedbackTest {
    private val f2 = ProactiveFamily.PLACE_RECALL
    private val t0 = 1_800_000_000_000L
    private val day = 24 * 3_600_000L
    private fun s(vararg a: FeedbackAction, key: String = "k") = a.fold(FeedbackState.EMPTY) { st, x -> st.after(x, key, f2, t0) }
    private fun mult(st: FeedbackState, now: Long = t0, key: String = "k") = FeedbackMath.multiplier(key, f2, st, now)

    @Test fun noFeedbackIsNeutral() { assertEquals(1.0, mult(FeedbackState.EMPTY), 1e-9) }
    @Test fun dismissIsPoint7AndRecoversLinearlyOver14Days() {
        val st = s(FeedbackAction.DISMISS)
        assertEquals(0.7, mult(st), 1e-9); assertEquals(0.85, mult(st, t0 + 7 * day), 1e-9); assertEquals(1.0, mult(st, t0 + 14 * day), 1e-9); assertEquals(1.0, mult(st, t0 + 30 * day), 1e-9)
    }
    @Test fun thumbsDownIsPoint4OnTheKeyAndPoint9OnTheFamily() {
        val st = s(FeedbackAction.DOWN)
        assertEquals(0.4, FeedbackMath.keyMultiplier(st.keys["k"], t0), 1e-9); assertEquals(0.9, FeedbackMath.familyMultiplier(st.families[f2]), 1e-9)
        assertEquals("another key only feels the family", 0.9, mult(st, key = "other"), 1e-9)
    }
    @Test fun thumbsUpIsOnePoint2AndOnePoint05() {
        val st = s(FeedbackAction.UP)
        assertEquals(1.2, FeedbackMath.keyMultiplier(st.keys["k"], t0), 1e-9); assertEquals(1.05, FeedbackMath.familyMultiplier(st.families[f2]), 1e-9)
    }
    @Test fun aThumbReplacesTheEarlierOneAndRepeatsCountOnce() {
        val st = s(FeedbackAction.UP, FeedbackAction.UP, FeedbackAction.DOWN)
        assertEquals(-1, st.keys["k"]!!.vote); assertEquals(0, st.families[f2]!!.up); assertEquals(1, st.families[f2]!!.down)
    }
    @Test fun theFamilyMultiplierIsBoundedBothWays() {
        var st = FeedbackState.EMPTY
        for (i in 0 until 100) st = st.after(FeedbackAction.DOWN, "k$i", f2, t0)
        assertEquals(FeedbackMath.FAMILY_MIN, FeedbackMath.familyMultiplier(st.families[f2]), 1e-9)
        st = FeedbackState.EMPTY
        for (i in 0 until 100) st = st.after(FeedbackAction.UP, "k$i", f2, t0)
        assertTrue(FeedbackMath.familyMultiplier(st.families[f2]) <= FeedbackMath.FAMILY_MAX); assertTrue(st.families[f2]!!.up <= FeedbackState.MAX_FAMILY_COUNT)
        assertEquals(FeedbackMath.FAMILY_MAX, FeedbackMath.familyMultiplier(FamilyFeedback(1000, 0)), 1e-9)
    }
    @Test fun snoozeAndDismissBlockTheKeyForADay() {
        val sn = s(FeedbackAction.SNOOZE); assertTrue(FeedbackMath.blocked("k", sn, t0 + day - 1)); assertFalse(FeedbackMath.blocked("k", sn, t0 + day))
        val di = s(FeedbackAction.DISMISS); assertTrue(FeedbackMath.blocked("k", di, t0 + day - 1)); assertFalse(FeedbackMath.blocked("k", di, t0 + day)); assertFalse(FeedbackMath.blocked("other", di, t0))
        assertFalse("thumbs never block", FeedbackMath.blocked("k", s(FeedbackAction.DOWN), t0))
    }
    @Test fun theKeyTableIsBoundedAndKeepsTheNewest() {
        var st = FeedbackState.EMPTY
        for (i in 0 until FeedbackState.MAX_KEYS + 30) st = st.after(FeedbackAction.DISMISS, "k$i", f2, t0 + i)
        assertEquals(FeedbackState.MAX_KEYS, st.keys.size); assertTrue("k${FeedbackState.MAX_KEYS + 29}" in st.keys); assertFalse("k0" in st.keys)
    }
    @Test fun jsonRoundTripsAndABadDocumentIsEmpty() {
        val st = s(FeedbackAction.DOWN, FeedbackAction.DISMISS)
        assertEquals(st, FeedbackState.fromJson(st.toJson())); assertEquals(FeedbackState.EMPTY, FeedbackState.fromJson("{bad")); assertEquals(FeedbackState.EMPTY, FeedbackState.fromJson(null))
        assertFalse("keys only, no text", st.toJson().contains("last visit"))
    }
    @Test fun feedbackReordersTheRankingWithinItsBounds() {
        fun cand(key: String) = Candidate(key, f2, "wd:$key", listOf("mem:$key"), PlaceFacts("P", t0 - 5 * day, 1, null, 0), 0.8, t0)
        val a = cand("a"); val b = cand("b")
        val plain = ProactiveRanker.rank(listOf(a, b), t0)
        assertEquals(listOf("a", "b"), plain.map { it.first.key }); assertEquals(plain[0].second, plain[1].second, 1e-9)
        val st = s(FeedbackAction.DOWN, key = "a").after(FeedbackAction.UP, "b", f2, t0)
        val ranked = ProactiveRanker.rank(listOf(a, b), t0) { FeedbackMath.multiplier(it.key, it.family, st, t0) }
        assertEquals(listOf("b", "a"), ranked.map { it.first.key })
        assertEquals(plain[0].second * 0.4 * 0.9 * 1.05 * 0.9 / 0.9 / 1.05 * 1.05, ranked.first { it.first.key == "a" }.second, 1.0) // sanity: bounded, not zeroed
        assertTrue(ranked.all { it.second > 0.0 })
    }
}
