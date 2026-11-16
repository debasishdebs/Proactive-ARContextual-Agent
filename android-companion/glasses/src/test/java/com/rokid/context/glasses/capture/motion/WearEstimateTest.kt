package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.WearEstimate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** v1.0 wear fallback: "not worn (estimated)" from 10 min of no head micro-motion and no interaction, only while the system wear check is not on. */
class WearEstimateTest {
    private val lines = mutableListOf<String>()
    private val w = WearDetector(log = { lines += it })
    private var t = 100_000L
    private var gyroAt = 0L
    private var accAt = 0L
    /** When false the IMU "delivers" nothing new: the stamps stay where they were. */
    private var delivering = true
    private var gyroLagMs = 100L
    private var accLagMs = 100L

    init { w.imuSampleAt = { gyroAt }; w.accSampleAt = { accAt } }

    /** One second as the sensor feed reports it: fresh gyro and acceleration samples (unless [delivering] is off), then the tick. */
    private fun sec(gyro: Float, acc: Float) {
        if (delivering) { gyroAt = t - gyroLagMs; accAt = t - accLagMs }
        w.onSecond(gyro, acc, t); t += 1000L
    }

    /** [n] seconds of a desk: gyro and acceleration at the noise floor. */
    private fun desk(n: Int) = repeat(n) { sec(0.004f, 0.0001f) }
    private val windowS = (WearEstimate.WINDOW_MS / 1000L).toInt()

    @Test fun estimatesNotWornAfterTheWindowOfNearZeroMotionWhenTheWearCheckIsOff() {
        w.onWearCheck(false); w.onRemote(true) // the firmware pins the sensor to "worn"; the check is off
        desk(windowS - 1); assertFalse(w.estimated.value)
        desk(1); assertTrue(w.estimated.value)
        assertTrue(lines.any { it.startsWith("wear estimate: ${WearEstimate.WORD}") }, lines.toString())
    }

    @Test fun anUnknownWearCheckNeverEstimates() {
        desk(windowS * 2); assertFalse(w.estimated.value, "no wear check said in this session: no estimate (Codex wear r1 P1-4)")
        w.onWearCheck(false); assertTrue(w.estimated.value, "once the check is said OFF the long quiet run counts")
    }

    @Test fun neverWhileTheWearCheckIsOn() {
        w.onWearCheck(true); w.onRemote(true)
        desk(windowS * 3); assertFalse(w.estimated.value, "the real sensor wins")
        w.onWearCheck(true); w.reset() // unknown wear but the check is on: the sensor will speak
        desk(windowS); assertFalse(w.estimated.value)
    }

    @Test fun aRealWearValueWithTheCheckStateUnknownWins() {
        w.onRemote(true) // an old phone: no wearCheck field
        desk(windowS * 2); assertFalse(w.estimated.value)
    }

    @Test fun theWearCheckTurningOnEndsTheEstimateAtOnce() {
        w.onWearCheck(false); desk(windowS); assertTrue(w.estimated.value)
        w.onWearCheck(true); assertFalse(w.estimated.value)
        w.onWearCheck(false); assertTrue(w.estimated.value, "the quiet run itself was not broken")
    }

    @Test fun breathingLevelMotionKeepsWorn() {
        // A very still wearer: every second is a park "still" second (below 0.05 rad/s), but pulse/breathing/swallowing peaks reach 0.03 rad/s now and then.
        w.onWearCheck(false)
        repeat(windowS * 3) { i -> sec(if (i % 120 == 0) 0.03f else 0.012f, 0.0008f) }
        assertFalse(w.estimated.value)
        assertTrue(w.parked.value, "capture may still park (battery wave A ruling); only the estimate needs real stillness")
        assertTrue(lines.any { it.startsWith("wear estimate: still") && it.contains("not quiet") }, "the tuning line says why: $lines")
    }

    @Test fun smallAccelerationKeepsWornToo() {
        w.onWearCheck(false)
        repeat(windowS * 2) { i -> sec(0.004f, if (i % 300 == 0) 0.006f else 0.0001f) }
        assertFalse(w.estimated.value)
    }

    @Test fun anyInteractionResetsTheTimer() {
        w.onWearCheck(false)
        desk(windowS - 10)
        w.onInteraction() // a tap
        desk(windowS - 1); assertFalse(w.estimated.value, "the window counts again from the tap")
        desk(1); assertTrue(w.estimated.value)
    }

    @Test fun interactionOrMovementFlipsBackToWornImmediately() {
        w.onWearCheck(false); desk(windowS); assertTrue(w.estimated.value)
        w.onInteraction(); assertFalse(w.estimated.value)
        assertTrue(lines.last().startsWith("wear estimate: worn again (interaction)"), lines.toString())
        desk(windowS); assertTrue(w.estimated.value)
        sec(0.08f, 0f); assertFalse(w.estimated.value)
        assertTrue(lines.last().startsWith("wear estimate: worn again (motion)"), lines.toString())
    }

    @Test fun aSessionRestartStartsTheRunAgain() {
        w.onWearCheck(false); desk(windowS); assertTrue(w.estimated.value)
        w.resetStillRun(); assertFalse(w.estimated.value)
        desk(windowS - 1); assertFalse(w.estimated.value)
        desk(1); assertTrue(w.estimated.value)
    }

    @Test fun theParkRuleIsUnchanged() {
        w.onWearCheck(true); w.onRemote(true)
        repeat(599) { sec(0f, 0f) }; assertFalse(w.parked.value)
        sec(0f, 0f); assertTrue(w.parked.value)
        w.onInteraction(); assertTrue(w.parked.value, "an interaction is the estimate's signal; park still ends only on motion")
    }

    @Test fun logLinesAreIdsFreeAndFixed() {
        w.onWearCheck(false); desk(windowS); w.onInteraction()
        assertEquals(2, lines.count { it.startsWith("wear estimate:") }, lines.toString())
        assertTrue(lines.all { l -> l.none { it == '/' } })
    }

    // ---- Codex wear r2 P1-1: missing, stale or thin sensor data is never quiet time ----

    @Test fun noAccelerationSamplesNeverEstimate() {
        w.accSampleAt = null; w.onWearCheck(false)
        desk(windowS * 2); assertFalse(w.estimated.value, "fresh gyro alone is not enough: the acceleration stream is missing")
    }

    @Test fun staleSamplesNeverEstimate() {
        w.onWearCheck(false); gyroLagMs = 600_000L // timestamps advance, but 10 minutes behind the clock
        desk(windowS * 2); assertFalse(w.estimated.value)
        gyroLagMs = 100L; accLagMs = 600_000L
        desk(windowS * 2); assertFalse(w.estimated.value)
    }

    @Test fun twoQuietObservationsTenMinutesApartDoNotCount() {
        w.onWearCheck(false); desk(1)
        delivering = false; t += WearEstimate.WINDOW_MS; desk(1)
        assertFalse(w.estimated.value)
    }

    @Test fun coverageIsEnforcedContinuouslyAfterTheRunReachedTheWindow() {
        w.onWearCheck(false); desk(windowS); assertTrue(w.estimated.value)
        // the feed now delivers only one second in four (25 %), never 3 s apart: the old latch kept the estimate for ever
        repeat(120) { i -> delivering = i % 4 == 0; desk(1) }
        assertFalse(w.estimated.value, "coverage fell far below ${WearEstimate.MIN_COVERAGE_PCT} % over the last minute")
        assertTrue(lines.any { it.contains("worn again (no_imu_data)") }, lines.toString())
    }

    @Test fun aStalledFeedEndsAnActiveEstimate() {
        w.onWearCheck(false); desk(windowS); assertTrue(w.estimated.value)
        delivering = false; desk(5); assertFalse(w.estimated.value)
    }
}
