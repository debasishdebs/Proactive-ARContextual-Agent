package com.rokid.context.memory.retrieval

import com.rokid.context.memory.retrieval.MatchCalibration.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchCalibrationTest {
    // Three pins on the first three axes of a 4-wide space; the 4th axis is "noise".
    private val refs = mapOf(
        "A" to listOf(floatArrayOf(1f, 0f, 0f, 0f)),
        "B" to listOf(floatArrayOf(0f, 1f, 0f, 0f)),
        "C" to listOf(floatArrayOf(0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, 0.9f, 0.1f)),
    )

    /** A probe at angle given by [s]: s on its own axis, the rest on the noise axis (cosine = s / norm). */
    private fun near(axis: Int, s: Float, noise: Float = 0.2f): FloatArray = FloatArray(4).also { it[axis] = s; it[3] = noise }

    @Test fun `separable data gives a perfect best threshold and clean distributions`() {
        val probes = listOf(
            Probe("p1", near(0, 1f), "A"), Probe("p2", near(0, 0.9f), "A"),
            Probe("p3", near(1, 1f), "B"), Probe("p4", near(2, 1f), "C"),
            Probe("far", floatArrayOf(0f, 0f, 0f, 1f), null), // a place no pin is right for
        )
        val r = MatchCalibration.evaluate(MatchCalibration.trials(probes, refs))
        assertEquals(5, r.probes); assertEquals(4, r.probesWithTruth); assertEquals(3, r.pins); assertEquals(4, r.top1Correct)
        assertEquals(4, r.positives.n); assertEquals(11, r.negatives.n) // 4 positives, 5 probes x 3 pins - 4
        val best = r.best!!
        assertEquals(1.0, best.precision, 0.0); assertEquals(1.0, best.recall, 0.0)
        assertTrue("the far probe must stay under the threshold", best.threshold > (r.curve.first().threshold))
        assertTrue(r.positives.p10 > r.negatives.p90)
        assertEquals(best.threshold, r.acceptAtPrecision)
    }

    @Test fun `a confusing probe lowers precision and the best threshold trades it off`() {
        // p3 looks most like pin A although it belongs to B: a wrong top-1 at a high score.
        val probes = listOf(
            Probe("p1", near(0, 1f), "A"), Probe("p2", near(1, 1f), "B"),
            Probe("p3", floatArrayOf(0.95f, 0.3f, 0f, 0f), "B"),
            Probe("p4", near(2, 0.5f, 0.8f), "C"),
        )
        val r = MatchCalibration.evaluate(MatchCalibration.trials(probes, refs))
        assertEquals(4, r.probesWithTruth)
        assertTrue(r.top1Correct < 4)
        val lowest = r.curve.first()
        assertTrue("showing every top-1 is not perfect precision", lowest.precision < 1.0)
        assertTrue(r.best!!.f1 >= lowest.f1)
        assertTrue(r.rejectBelow != null)
    }

    @Test fun `a pin with no usable reference vector is skipped and nothing throws on empty input`() {
        val t = MatchCalibration.trials(listOf(Probe("p", near(0, 1f), "A")), mapOf("A" to listOf(floatArrayOf(1f, 0f, 0f, 0f)), "Z" to listOf(floatArrayOf(0f, 0f, 0f, 0f)), "W" to emptyList(), "Y" to listOf(floatArrayOf(1f, 0f))))
        assertEquals(listOf("A"), t.single().scores.map { it.first })
        val e = MatchCalibration.evaluate(emptyList())
        assertNull(e.best); assertNull(e.acceptAtPrecision); assertEquals(0, e.positives.n)
        assertTrue(MatchCalibration.report("none", e).contains("bestThreshold=none"))
    }

    @Test fun `an ungrounded probe only ever adds a wrong card`() {
        val r = MatchCalibration.evaluate(MatchCalibration.trials(listOf(Probe("x", near(0, 1f), null)), refs))
        assertEquals(0, r.probesWithTruth)
        assertEquals(0.0, r.best!!.recall, 0.0)
        assertEquals(0.0, r.best!!.precision, 0.0)
    }

    @Test fun `the distribution percentiles are exact on a known list`() {
        val d = MatchCalibration.dist((1..11).map { it * 10 })
        assertEquals(11, d.n); assertEquals(10, d.min); assertEquals(110, d.max); assertEquals(60, d.p50); assertEquals(60, d.mean); assertEquals(20, d.p10); assertEquals(100, d.p90)
    }

    @Test fun `the report is ascii and carries counts and milli scores only`() {
        val probes = listOf(Probe("secret-probe-name", near(0, 1f), "pin-private-name"))
        val s = MatchCalibration.report("siglip2", MatchCalibration.evaluate(MatchCalibration.trials(probes, mapOf("pin-private-name" to listOf(floatArrayOf(1f, 0f, 0f, 0f))))))
        assertTrue(s.all { it.code in 9..126 })
        assertFalse(s.contains("secret")); assertFalse(s.contains("private"))
        assertTrue(s.contains("space=siglip2")); assertTrue(s.contains("bestThreshold="))
    }

    @Test fun `scores match the scorer's rounding`() {
        val q = near(0, 0.6f, 0.8f) // cosine with A = 0.6
        val t = MatchCalibration.trials(listOf(Probe("p", q, "A")), refs).single()
        assertEquals(600, t.scores.single { it.first == "A" }.second)
        assertEquals(CandidateScorer.score(q, refs.map { (id, v) -> CandidateScorer.Candidate(id, imageRefs = v) }).ranked.associate { it.id to it.milli }, t.scores.toMap())
    }

    @Test fun `centroid and medoid are built from synthetic vectors`() {
        val c = MatchCalibration.centroid(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))!!
        assertEquals(c[0], c[1], 1e-6f); assertEquals(1.0, Math.sqrt((c[0] * c[0] + c[1] * c[1]).toDouble()), 1e-5)
        val m = MatchCalibration.medoid(listOf(floatArrayOf(1f, 0f), floatArrayOf(0.9f, 0.1f), floatArrayOf(0f, 1f)))!!
        assertEquals(0.9f / Math.sqrt(0.82).toFloat(), m[0], 1e-5f) // the middle vector is closest to both
        assertNull(MatchCalibration.centroid(emptyList())); assertNull(MatchCalibration.medoid(listOf(floatArrayOf(0f, 0f))))
    }

    @Test fun `leave-one-out centroid beats a bad single reference and never scores a probe against itself`() {
        // Pin A: the reference points off to the side (axis 3), the probes cluster on axis 0. Pin B is on axis 1.
        val groups = mapOf(
            "A" to listOf(floatArrayOf(0.2f, 0f, 0f, 1f), floatArrayOf(1f, 0.1f, 0f, 0f), floatArrayOf(1f, -0.1f, 0f, 0f), floatArrayOf(0.9f, 0f, 0.1f, 0f)),
            "B" to listOf(floatArrayOf(0f, 1f, 0f, 0f), floatArrayOf(0.1f, 1f, 0f, 0f), floatArrayOf(0f, 0.9f, 0.1f, 0f)),
        )
        val none = listOf(floatArrayOf(0f, 0f, 0f, 1f))
        val single = MatchCalibration.evaluate(MatchCalibration.looTrials(groups, none, MatchCalibration.RefMode.SINGLE))
        val cen = MatchCalibration.evaluate(MatchCalibration.looTrials(groups, none, MatchCalibration.RefMode.CENTROID))
        val med = MatchCalibration.evaluate(MatchCalibration.looTrials(groups, none, MatchCalibration.RefMode.MEDOID))
        assertEquals(5, single.probesWithTruth); assertEquals(6, single.probes) // 3 + 2 pin probes + 1 none, same for every mode
        assertEquals(6, cen.probes); assertEquals(6, med.probes)
        assertTrue(cen.positives.p50 > single.positives.p50)
        assertTrue(cen.top1Correct >= single.top1Correct); assertEquals(5, cen.top1Correct); assertEquals(5, med.top1Correct)
        // leave-one-out: a pin with one probe and one ref, the probe held out leaves the ref alone, never the probe itself
        val tiny = MatchCalibration.looTrials(mapOf("A" to listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f))), emptyList(), MatchCalibration.RefMode.CENTROID)
        assertEquals(0, tiny.single().scores.single().second) // the probe (0,1) vs the ref alone (1,0)
    }

    @Test fun `margin sweep and accept at 95 and 98`() {
        // Truth A wins by a big margin (2 trials); the wrong pin wins by a tiny margin (1 trial, truth B).
        val trials = listOf(
            MatchCalibration.Trial("A", listOf("A" to 900, "B" to 300)),
            MatchCalibration.Trial("A", listOf("A" to 800, "B" to 400)),
            MatchCalibration.Trial("B", listOf("A" to 700, "B" to 690)),
            MatchCalibration.Trial("A", listOf("A" to 650)), // no runner-up: margin null, always passes
        )
        assertEquals(10, trials[2].margin); assertNull(trials[3].margin)
        val sweep = MatchCalibration.marginSweep(trials)
        assertEquals(listOf(0, 10, 400, 600), sweep.map { it.minMargin })
        assertEquals(4, sweep[0].shown); assertEquals(3, sweep[0].correct)
        assertEquals(4, sweep[1].shown) // the 10-milli lead still passes
        assertEquals(1.0, sweep[2].precision, 0.0); assertEquals(3, sweep[2].shown)
        assertEquals(400, MatchCalibration.marginForPrecision(trials, 0.95))
        val at = MatchCalibration.acceptAt(trials)
        assertEquals(setOf(0.95, 0.98), at.keys)
        assertEquals(800, at[0.95]); assertEquals(800, at[0.98]) // 700 is wrong, so 650 and 700 are out
        val r = MatchCalibration.evaluate(trials)
        assertEquals(800, r.acceptAt95); assertTrue(MatchCalibration.report("t", r).contains("acceptAt95=800 acceptAt98=800"))
        assertTrue(MatchCalibration.marginReport("t", trials).contains("marginForPrecision95=400"))
    }
}
