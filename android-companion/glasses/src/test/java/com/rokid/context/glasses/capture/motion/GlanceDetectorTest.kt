package com.rokid.context.glasses.capture.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlanceDetectorTest {
    private val fired = mutableListOf<Long>()

    /** Feeds a 50 Hz trace of (durationMs to magnitude) segments from [from]; returns the end time. */
    private fun run(d: GlanceDetector, from: Long, vararg segs: Pair<Long, Float>, still: Boolean = true): Long {
        var t = from
        for ((ms, mag) in segs) {
            val end = t + ms
            while (t < end) { if (d.onGyro(mag, t, 20, still)) fired += t; t += 20 }
        }
        return t
    }

    @Test fun realTurnThenSettleIsOneGlance() {
        val d = GlanceDetector(GlanceParams())
        run(d, 0, 1000L to 0f, 400L to 1.2f, 4000L to 0f)
        val lastAbove = 1380L
        assertEquals(1, fired.size)
        assertTrue(kotlin.math.abs(fired[0] - (lastAbove + 1_500)) <= 20, "fired=$fired")
        assertEquals(1, d.glances); assertEquals(0, d.micro)
    }

    @Test fun shortJerkIsMicro() {
        val d = GlanceDetector(GlanceParams())
        run(d, 0, 1000L to 0f, 100L to 2f, 5000L to 0f)
        assertTrue(fired.isEmpty()); assertEquals(1, d.micro); assertEquals(0, d.glances)
    }

    @Test fun slowSmallTurnIsMicro() {
        val d = GlanceDetector(GlanceParams())
        run(d, 0, 1000L to 0f, 320L to 0.7f, 5000L to 0f)
        assertTrue(fired.isEmpty()); assertEquals(1, d.micro)
    }

    @Test fun jitterBelowOnsetNeverStartsABout() {
        val d = GlanceDetector(GlanceParams())
        run(d, 0, 10_000L to 0.5f)
        assertTrue(fired.isEmpty()); assertEquals(0, d.micro)
    }

    @Test fun releaseHysteresisBridgesTheTurn() {
        val d = GlanceDetector(GlanceParams())
        run(d, 0, 1000L to 0f, 200L to 1.0f, 150L to 0.4f, 200L to 1.0f, 4000L to 0f)
        assertEquals(1, fired.size); assertEquals(0, d.micro)
    }

    @Test fun microDuringSettlePostponesTheGlance() {
        val d = GlanceDetector(GlanceParams())
        // turn 1000..1380, jerk at 2180..2260 (last above 2240)
        run(d, 0, 1000L to 0f, 400L to 1.2f, 780L to 0f, 100L to 2f, 4000L to 0f)
        assertEquals(1, fired.size, "fired=$fired")
        assertTrue(kotlin.math.abs(fired[0] - (2240L + 1_500)) <= 20, "fired=$fired")
        assertEquals(1, d.micro)
    }

    @Test fun walkEndThenStillIsAGlance() {
        val d = GlanceDetector(GlanceParams())
        d.onWalking(5_000)
        run(d, 5_020, 3000L to 0f)
        assertEquals(1, fired.size)
        assertTrue(kotlin.math.abs(fired[0] - 6_500) <= 20, "fired=$fired")
    }

    @Test fun noGlanceWhileNotStill() {
        val d = GlanceDetector(GlanceParams())
        val t = run(d, 0, 1000L to 0f, 400L to 1.2f, 3000L to 0f, still = false)
        assertTrue(fired.isEmpty())
        assertTrue(d.onGyro(0f, t, 20, true))
    }

    /** A [lookMs] bout of 1.2 rad/s at [hz], then stillness; the detector's view of the same look at either sample rate. */
    private fun lookAt(hz: Int, lookMs: Long): GlanceDetector {
        val d = GlanceDetector(GlanceParams())
        val dt = 1000L / hz
        var t = 0L
        while (t < 1000) { d.onGyro(0f, t, dt, true); t += dt }
        val end = t + lookMs
        while (t < end) { d.onGyro(1.2f, t, dt, true); t += dt }
        while (t < end + 4000) { if (d.onGyro(0f, t, dt, true)) fired += t; t += dt }
        return d
    }

    @Test fun boutLengthDoesNotDependOnTheSampleRate() {
        for (hz in listOf(20, 50)) for (look in listOf(300L, 320L, 349L)) {
            fired.clear()
            val d = lookAt(hz, look)
            assertEquals(1, fired.size, "hz=$hz look=$look fired=$fired")
            assertEquals(0, d.micro, "hz=$hz look=$look")
        }
    }

    @Test fun aShortLookIsMicroAtEitherSampleRate() {
        for (hz in listOf(20, 50)) {
            fired.clear()
            val d = lookAt(hz, 240)
            assertTrue(fired.isEmpty(), "hz=$hz fired=$fired"); assertEquals(1, d.micro, "hz=$hz")
        }
    }

    @Test fun legacyMatchesOldDwell() {
        val d = GlanceDetector(GlanceParams.LEGACY)
        run(d, 0, 1000L to 0f, 20L to 2f, 3000L to 0f)
        assertEquals(listOf(2000L), fired)
    }
}
