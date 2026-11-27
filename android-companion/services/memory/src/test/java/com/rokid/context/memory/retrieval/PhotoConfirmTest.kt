package com.rokid.context.memory.retrieval

import com.rokid.context.memory.retrieval.PhotoConfirm.Level
import com.rokid.context.memory.retrieval.PhotoConfirm.Outcome
import com.rokid.context.memory.retrieval.PhotoConfirm.PlaceRefs
import com.rokid.context.memory.retrieval.PhotoConfirm.Ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** Pure decision tests with exact cosines: the query is (1,0); a reference at angle gives a chosen milli score. */
class PhotoConfirmTest {
    private val q = floatArrayOf(1f, 0f)
    /** A unit vector whose cosine with (1,0) rounds to [milli] / 1000. */
    private fun ref(milli: Int, kind: RefKind = RefKind.OWN): Ref { val c = milli / 1000.0; return Ref(kind, floatArrayOf(c.toFloat(), sqrt(1 - c * c).toFloat())) }
    private fun place(id: String, vararg r: Ref) = PlaceRefs(id, r.toList())
    private fun decide(vararg p: PlaceRefs, cfg: PhotoMatchConfig = PhotoMatchConfig.DEFAULT) = PhotoConfirm.decide(listOf(q), p.toList(), cfg)

    @Test fun `example defaults are the design numbers and clamp stays inside`() {
        val d = PhotoMatchConfig.DEFAULT
        assertEquals(KindThresholds(945, 846, 20), d.own); assertEquals(KindThresholds(967, 879, 20), d.ownCentroid); assertEquals(KindThresholds(945, 0, 20), d.web)
        assertTrue(d.web.floorOff)
        assertEquals(KindThresholds(999, 999, 200), KindThresholds(5000, 5000, 5000).clamped())
        assertEquals(KindThresholds(500, 500, 0), KindThresholds(1, 2, -5).clamped())
        assertEquals(KindThresholds(700, 700, 20), KindThresholds(700, 900, 20).clamped()) // floor never above high
        assertEquals(KindThresholds(700, 0, 20), KindThresholds(700, -3, 20).clamped())
    }

    @Test fun `HIGH is inclusive and a single place accepts`() {
        val r = decide(place("a", ref(945)))
        assertEquals(Outcome.ACCEPT, r.outcome); assertEquals("a", r.accepted!!.placeId); assertEquals(listOf("a"), r.candidates); assertNull(r.margin)
        assertEquals(Outcome.MEDIUM, decide(place("a", ref(944))).outcome)
    }

    @Test fun `margin is inclusive and a thin lead sends both to the narrowed list`() {
        val ok = decide(place("a", ref(960)), place("b", ref(940)))
        assertEquals(Outcome.ACCEPT, ok.outcome); assertEquals(20, ok.margin)
        val thin = decide(place("a", ref(960)), place("b", ref(941)))
        assertEquals(Outcome.MEDIUM, thin.outcome); assertEquals(listOf("a", "b"), thin.candidates); assertEquals(19, thin.margin)
    }

    @Test fun `two places at HIGH accept nothing and both stay`() {
        val r = decide(place("a", ref(990)), place("b", ref(950)), place("c", ref(600)))
        assertEquals(Outcome.MEDIUM, r.outcome); assertNull(r.accepted); assertEquals(listOf("a", "b"), r.candidates)
        assertEquals(Outcome.MEDIUM, decide(place("a", ref(950)), place("b", ref(950))).outcome) // an exact tie
    }

    @Test fun `FLOOR is inclusive and below it a place is dropped`() {
        val r = decide(place("a", ref(846)), place("b", ref(845)))
        assertEquals(listOf("a"), r.candidates); assertEquals(Level.MID, r.scores[0].level); assertEquals(Level.BELOW, r.scores[1].level)
        val all = decide(place("a", ref(500)), place("b", ref(100)))
        assertEquals(Outcome.BELOW, all.outcome); assertTrue(all.candidates.isEmpty())
    }

    @Test fun `places without a reference stay in the list`() {
        val r = decide(place("a", ref(500)), place("b"), place("c", ref(900)))
        assertEquals(Outcome.MEDIUM, r.outcome); assertEquals(listOf("b", "c"), r.candidates); assertEquals(1, r.noRef); assertEquals(2, r.withRef)
        val none = decide(place("a"), place("b"))
        assertEquals(Outcome.NO_REF, none.outcome); assertEquals(listOf("a", "b"), none.candidates)
        val lone = decide(place("a", ref(500)), place("b")) // the only referenced place is below the floor; the no-reference one stays
        assertEquals(Outcome.BELOW, lone.outcome); assertEquals(listOf("b"), lone.candidates)
    }

    @Test fun `any reference matching matches the whole place and a bad side does not drop it`() {
        val r = decide(place("a", ref(300), ref(950), ref(400)), place("b", ref(930)))
        assertEquals(Outcome.ACCEPT, r.outcome); assertEquals("a", r.accepted!!.placeId); assertEquals(20, r.margin)
        val keep = decide(place("a", ref(300), ref(900))) // one side far, another side mid: the place is mid, not below
        assertEquals(Level.MID, keep.scores[0].level)
    }

    @Test fun `each kind has its own HIGH and FLOOR`() {
        assertEquals(Outcome.MEDIUM, decide(place("a", ref(950, RefKind.OWN_CENTROID))).outcome) // centroid needs 967
        assertEquals(Outcome.ACCEPT, decide(place("a", ref(967, RefKind.OWN_CENTROID))).outcome)
        assertEquals(Level.BELOW, decide(place("a", ref(870, RefKind.OWN_CENTROID))).scores[0].level) // centroid floor 879
        assertEquals(Level.MID, decide(place("a", ref(870, RefKind.OWN))).scores[0].level)
        assertEquals(Outcome.ACCEPT, decide(place("a", ref(945, RefKind.WEB))).outcome)
    }

    @Test fun `the WEB floor is off so a low web photo never drops a place`() {
        val r = decide(place("a", ref(100, RefKind.WEB)))
        assertEquals(Level.MID, r.scores[0].level); assertEquals(listOf("a"), r.candidates); assertEquals(Outcome.MEDIUM, r.outcome)
        val mixed = decide(place("a", ref(100, RefKind.WEB), ref(500, RefKind.OWN)))
        assertEquals(Level.MID, mixed.scores[0].level) // the web reference keeps the place; the low own reference alone would drop it
        assertEquals(Level.BELOW, decide(place("a", ref(500, RefKind.OWN))).scores[0].level)
    }

    @Test fun `the margin of the winning reference's kind applies`() {
        val cfg = PhotoMatchConfig(own = KindThresholds(945, 846, 20), ownCentroid = KindThresholds(967, 879, 60), web = KindThresholds(945, 0, 5))
        assertEquals(Outcome.MEDIUM, decide(place("a", ref(980, RefKind.OWN_CENTROID)), place("b", ref(930)), cfg = cfg).outcome) // lead 50 < 60
        assertEquals(Outcome.ACCEPT, decide(place("a", ref(980, RefKind.OWN_CENTROID)), place("b", ref(900)), cfg = cfg).outcome)
        assertEquals(Outcome.ACCEPT, decide(place("a", ref(946, RefKind.WEB)), place("b", ref(941)), cfg = cfg).outcome) // web margin 5, lead 5
        assertEquals(Outcome.MEDIUM, decide(place("a", ref(946, RefKind.WEB)), place("b", ref(942)), cfg = cfg).outcome) // lead 4
    }

    @Test fun `a custom config is clamped before use`() {
        val wild = PhotoMatchConfig(KindThresholds(10, 5, 9999), KindThresholds(5000, 5000, -1), KindThresholds(700, 800, 0))
        val r = decide(place("a", ref(999)), cfg = wild)
        assertEquals(Outcome.ACCEPT, r.outcome)
    }

    @Test fun `empty and unusable inputs never throw`() {
        assertEquals(Outcome.SKIP, PhotoConfirm.decide(emptyList(), listOf(place("a", ref(990)))).outcome)
        assertEquals(Outcome.SKIP, PhotoConfirm.decide(listOf(floatArrayOf()), listOf(place("a", ref(990)))).outcome)
        assertEquals(Outcome.SKIP, PhotoConfirm.decide(listOf(floatArrayOf(0f, 0f)), listOf(place("a", ref(990)))).outcome)
        assertEquals(Outcome.SKIP, PhotoConfirm.decide(listOf(floatArrayOf(Float.NaN, 1f)), listOf(place("a", ref(990)))).outcome)
        val skip = PhotoConfirm.decide(emptyList(), listOf(place("a"), place("b")))
        assertEquals(listOf("a", "b"), skip.candidates)
        assertEquals(Outcome.NO_REF, PhotoConfirm.decide(listOf(q), emptyList()).outcome)
        val bad = decide(place("a", Ref(RefKind.OWN, floatArrayOf()), Ref(RefKind.OWN, floatArrayOf(0f, 0f)), Ref(RefKind.OWN, floatArrayOf(1f, 0f, 0f)), Ref(RefKind.OWN, floatArrayOf(Float.POSITIVE_INFINITY, 0f))))
        assertEquals(Outcome.NO_REF, bad.outcome); assertEquals(Level.NO_REF, bad.scores[0].level)
    }

    @Test fun `best of k scores each query vector and keeps the best`() {
        val far = floatArrayOf(0f, 1f)
        val r = PhotoConfirm.decide(listOf(far, q), listOf(place("a", ref(950))))
        assertEquals(Outcome.ACCEPT, r.outcome)
        val mean = PhotoConfirm.decide(listOf(far), listOf(place("a", ref(950))))
        assertEquals(Outcome.BELOW, mean.outcome)
    }

    @Test fun `duplicate place ids merge and the order of equal inputs does not matter`() {
        val r = decide(place("a", ref(400)), place("a", ref(950)))
        assertEquals(Outcome.ACCEPT, r.outcome); assertEquals(1, r.scores.size)
        val x = decide(place("a", ref(960)), place("b", ref(940))); val y = decide(place("b", ref(940)), place("a", ref(960)))
        assertEquals(x.accepted!!.placeId, y.accepted!!.placeId); assertEquals(x.margin, y.margin)
    }

    @Test fun `buckets are fixed words`() {
        assertEquals("na", PhotoConfirm.scoreBucket(null)); assertEquals("lt945", PhotoConfirm.scoreBucket(944)); assertEquals("ge970", PhotoConfirm.scoreBucket(970))
        assertEquals("na", PhotoConfirm.marginBucket(null)); assertEquals("neg", PhotoConfirm.marginBucket(-1)); assertEquals("lt25", PhotoConfirm.marginBucket(24)); assertEquals("ge50", PhotoConfirm.marginBucket(50))
    }
}
