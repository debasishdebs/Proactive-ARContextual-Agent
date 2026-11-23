package com.rokid.context.work.frames.place.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTrackTest {
    private val lat0 = 10.0
    private val lon = 20.0
    private fun north(m: Double) = lat0 + m / 111_320.0
    private fun fix(tSec: Long, m: Double) = ReplayFix(tSec * 1000, north(m), lon, "CAMERA", "f")

    @Test fun causalNeverUsesFutureFix() {
        val t = ReplayTrack(listOf(fix(100, 0.0), fix(200, 50.0)))
        assertNull(t.causal(99_000))
        assertEquals(0L, t.causal(100_000)!!.dtMs)
        assertEquals(50_000L, t.causal(150_000)!!.dtMs)
        assertEquals(north(0.0), t.causal(150_000)!!.lat, 1e-9)
        assertEquals(PosSrc.CAUSAL, t.causal(150_000)!!.src)
    }

    @Test fun nearestOneSideWithinN() {
        val t = ReplayTrack(listOf(fix(1000, 0.0)))
        val p = t.near(1000_000 + 120_000, 300_000)!!
        assertEquals(PosSrc.NEAREST, p.src); assertEquals(120_000L, p.dtMs)
        val q = t.near(1000_000 - 120_000, 300_000)!!
        assertEquals(PosSrc.NEAREST, q.src)
        assertNull(t.near(1000_000 + 301_000, 300_000))
    }

    @Test fun speedAbove2msIsUnknown() {
        // 100 m in 20 s = 5 m/s: both sides in range, but the pair is not a walking pace.
        val fast = ReplayTrack(listOf(fix(100, 0.0), fix(120, 100.0)))
        assertNull(fast.near(110_000, 300_000))
        // 100 m in 100 s = 1 m/s: interpolated, halfway at 50 s.
        val slow = ReplayTrack(listOf(fix(100, 0.0), fix(200, 100.0)))
        val p = slow.near(150_000, 300_000)!!
        assertEquals(PosSrc.INTERP, p.src); assertEquals(north(50.0), p.lat, 1e-6)
        // more than 200 m apart is also UNKNOWN, however slow
        val far = ReplayTrack(listOf(fix(0, 0.0), fix(250, 250.0)))
        assertNull(far.near(125_000, 300_000))
    }

    @Test fun backfillNeverUsesAnAssumedPosition() {
        val t = ReplayTrack(emptyList())
        assertNull(t.backfill(1_000))
        assertTrue(TrackPos(1.0, 2.0, PosSrc.ASSUMED, 0, "-").assumed)
        assertNotNull(ReplayTrack(listOf(fix(1, 0.0))).backfill(2_000))
    }
}
