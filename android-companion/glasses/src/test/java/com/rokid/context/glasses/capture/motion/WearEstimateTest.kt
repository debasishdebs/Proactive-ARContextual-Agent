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
    private var t = 0L

    /** [n] seconds of a desk: gyro and acceleration at the noise floor. */
    private fun desk(n: Int) = repeat(n) { w.onSecond(0.004f, 0.0001f, t); t += 1000L }
    private val windowS = (WearEstimate.WINDOW_MS / 1000L).toInt()

    @Test fun estimatesNotWornAfterTheWindowOfNearZeroMotionWhenTheWearCheckIsOff() {
        w.onWearCheck(false); w.onRemote(true) // the firmware pins the sensor to "worn"; the check is off
        desk(windowS - 1); assertFalse(w.estimated.value)
        desk(1); assertTrue(w.estimated.value)
        assertTrue(lines.any { it.startsWith("wear estimate: ${WearEstimate.WORD}") }, lines.toString())
    }

    @Test fun estimatesWithNoWearSignalAtAll() {
        desk(windowS); assertTrue(w.estimated.value, "check state unknown and no wear value: unknown because of the check")
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
        repeat(windowS * 3) { i -> w.onSecond(if (i % 120 == 0) 0.03f else 0.012f, 0.0008f, t); t += 1000L }
        assertFalse(w.estimated.value)
        assertTrue(w.parked.value, "capture may still park (battery wave A ruling); only the estimate needs real stillness")
        assertTrue(lines.any { it.startsWith("wear estimate: still") && it.contains("not quiet") }, "the tuning line says why: $lines")
    }

    @Test fun smallAccelerationKeepsWornToo() {
        w.onWearCheck(false)
        repeat(windowS * 2) { i -> w.onSecond(0.004f, if (i % 300 == 0) 0.006f else 0.0001f, t); t += 1000L }
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
        w.onSecond(0.08f, 0f, t); t += 1000L; assertFalse(w.estimated.value)
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
        repeat(599) { w.onSecond(0f, 0f, t); t += 1000L }; assertFalse(w.parked.value)
        w.onSecond(0f, 0f, t); assertTrue(w.parked.value)
        w.onInteraction(); assertTrue(w.parked.value, "an interaction is the estimate's signal; park still ends only on motion")
    }

    @Test fun logLinesAreIdsFreeAndFixed() {
        w.onWearCheck(false); desk(windowS); w.onInteraction()
        assertEquals(2, lines.count { it.startsWith("wear estimate:") }, lines.toString())
        assertTrue(lines.all { l -> l.none { it == '/' } })
    }
}
