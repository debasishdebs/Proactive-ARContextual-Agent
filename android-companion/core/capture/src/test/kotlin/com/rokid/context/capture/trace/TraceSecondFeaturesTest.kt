package com.rokid.context.capture.trace

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.ContextState
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-second features of a trace on the phone clock. Expected numbers are worked out by hand from the samples below (not produced by running the code under test). The manifest has NO clock
 * drift and a constant +250 ms phone offset, so phone wall = elapsed + 1_700_000_000_250: a phone second starts where elapsed is 750 modulo 1000.
 */
class TraceSecondFeaturesTest {
    private val boot = "42"
    private val base = TraceFixtures.manifest()
    // glasses wall offset 1_700_000_000_000 at both ends (no drift); phone samples give exactly +250 at both ends
    private val m = base.copy(endWallMs = 1_700_000_012_000, startPhoneWallMs = 1_700_000_001_250, endPhoneWallMs = 1_700_000_012_250, startPhoneAgeMs = 0, endPhoneAgeMs = 0, phoneOffsetMs = 250)
    private val s0 = 1_700_000_001L // phone second of elapsed 750..1749

    private fun gyro(tMs: Long, sMs: Long, x: Float, y: Float, z: Float) = TraceEvent.Gyro(tMs, sMs, x, y, z)
    private fun accel(tMs: Long, sMs: Long, y: Float) = TraceEvent.Accel(tMs, sMs, 0f, y, 0f)

    /** Four gyro samples 100 ms apart with magnitudes 3, 4, 0 and 1 rad/s, delivered inside elapsed 2800..2830 (phone second s0 + 2). */
    private fun gyroSecond(tBase: Long = 2800, batched: Boolean = false): List<TraceEvent> {
        fun t(i: Int) = if (batched) tBase else tBase + 10L * i
        return listOf(gyro(t(0), 3000, 3f, 0f, 0f), gyro(t(1), 3100, 0f, 4f, 0f), gyro(t(2), 3200, 0f, 0f, 0f), gyro(t(3), 3300, 0f, 0f, 1f))
    }

    @Test fun theClassifierThresholdsOfTheTraceAreRestoredForTheReplayedFeatures() {
        val edited = m.copy(snapshot = m.snapshot.copy(classifier = mapOf("headMovingRadS" to 1.2)))
        val slow = (0 until 4).map { gyro(2800L + 10L * it, 3000L + 100L * it, 0f, 0f, 1f) }   // 1 rad/s throughout
        assertEquals(MotionState.STILL, TraceSecondFeatures.compute(edited, slow.asSequence()).single().state, "1 rad/s is under the edited 1.2 threshold")
        assertEquals(MotionState.HEAD_MOVING, TraceSecondFeatures.compute(m, slow.asSequence()).single().state, "an older trace replays with the defaults")
    }

    @Test fun theSevenFeaturesOfAGyroSecondAreExact() {
        val r = TraceSecondFeatures.compute(m, gyroSecond().asSequence()).single()
        assertEquals(s0 + 2, r.phoneSecond)
        assertEquals(4.0, r.gyroMax, 0.0)
        assertEquals(2.0, r.gyroMean, 1e-6)                    // (3 + 4 + 0 + 1) / 4
        assertEquals(sqrt(6.5), r.gyroRms, 1e-6)               // sqrt((9 + 16 + 0 + 1) / 4)
        assertEquals(0.75, r.aboveOnFrac, 1e-9)                // 3, 4 and 1 are over the 0.6 rad/s onset, 0 is not
        assertEquals(0.5, r.rotRad, 1e-6)                      // travelled = 4 * 0.1 s + 1 * 0.1 s (the first sample has no dt)
        assertEquals(0.0, r.accVar, 0.0); assertEquals(0.0, r.stepHz, 0.0)
        assertEquals(4, r.nGyro); assertEquals(0, r.nAcc)
        assertEquals(MotionState.HEAD_MOVING, r.state)         // the last sample (1 rad/s) is over the head-moving threshold
    }

    @Test fun aWalkingSecondHasTheAccelerationVarianceTheStepFrequencyAndTheWalkingState() {
        // 20 samples 50 ms apart, y = +2 for five samples, then -2 for five, and so on: mean 0, variance 4, three mean crossings over 950 ms
        val acc = (0 until 20).map { i -> accel(2800L + i * 2, 3000L + i * 50L, if ((i / 5) % 2 == 0) 2f else -2f) }
        val r = TraceSecondFeatures.compute(m, acc.asSequence()).single()
        assertEquals(4.0, r.accVar, 1e-5)
        assertEquals(3 / 2.0 / 0.95, r.stepHz, 1e-9)
        assertEquals(MotionState.WALKING, r.state)
        assertEquals(20, r.nAcc); assertEquals(0, r.nGyro)
    }

    @Test fun theSampleSpacingIsTheSensorsNotTheDeliverysSoABatchKeepsItsRotation() {
        val r = TraceSecondFeatures.compute(m, gyroSecond(batched = true).asSequence()).single()
        assertEquals(0.5, r.rotRad, 1e-6, "all four delivered at one instant: the 0.1 s spacing of sMs still counts")
    }

    @Test fun aSampleIsPlacedInThePhoneSecondOfItsDeliveryNotTheElapsedMultiple() {
        // elapsed 2740 -> phone ..._002_990 (second s0 + 1); elapsed 2760 -> phone ..._003_010 (second s0 + 2): the boundary is at elapsed 750 mod 1000
        val ev = listOf(gyro(2740, 5000, 1f, 0f, 0f), gyro(2760, 5010, 1f, 0f, 0f))
        val rows = TraceSecondFeatures.compute(m, ev.asSequence())
        assertEquals(listOf(s0 + 1, s0 + 2), rows.map { it.phoneSecond }); assertEquals(listOf(1, 1), rows.map { it.nGyro })
    }

    @Test fun theLastContextAtOrBeforeTheSecondIsCarriedAndNullBeforeTheFirst() {
        val ev = listOf(
            gyro(1_800, 1000, 1f, 0f, 0f), gyro(2_800, 2000, 1f, 0f, 0f), TraceEvent.Ctx(3_000, ContextState.REST),
            gyro(3_800, 3000, 1f, 0f, 0f), TraceEvent.Ctx(3_900, ContextState.WALKING), gyro(4_800, 4000, 1f, 0f, 0f),
        )
        val rows = TraceSecondFeatures.compute(m, ev.asSequence())
        assertEquals(listOf(s0 + 1, s0 + 2, s0 + 3, s0 + 4), rows.map { it.phoneSecond })
        assertNull(rows[0].context, "no cx yet")
        assertEquals(listOf(ContextState.REST, ContextState.WALKING, ContextState.WALKING), rows.drop(1).map { it.context }, "a cx inside the second counts for it")
    }

    @Test fun aSampleTheClockMapCannotPlaceGivesNoRow() {
        val far = gyro(12_000 + ClockMap.SLACK_MS + 1, 1, 1f, 0f, 0f)
        assertTrue(TraceSecondFeatures.compute(m, sequenceOf(far)).isEmpty())
    }

    @Test fun aTraceWithNoPhoneOffsetGivesNoRows() {
        val noPhone = m.copy(startPhoneWallMs = null, endPhoneWallMs = null, startPhoneAgeMs = null, endPhoneAgeMs = null, phoneOffsetMs = null, offsetUncertaintyMs = null)
        assertTrue(TraceSecondFeatures.compute(noPhone, gyroSecond().asSequence()).isEmpty())
    }

    @Test fun aWallClockJumpGivesNoRows() {
        val jumped = m.copy(endWallMs = m.endWallMs + ClockMap.MAX_WALL_JUMP_MS + 1)
        assertTrue(TraceSecondFeatures.compute(jumped, gyroSecond().asSequence()).isEmpty())
    }

    @Test fun aSecondWithOnlyAContextChangeHasNoRow() {
        assertTrue(TraceSecondFeatures.compute(m, sequenceOf(TraceEvent.Ctx(3_000, ContextState.REST))).isEmpty())
    }

    @Test fun theResultIsDeterministic() {
        val ev = gyroSecond() + (0 until 20).map { i -> accel(2830L + i, 3400L + i * 50L, if (i % 2 == 0) 1f else -1f) }
        assertEquals(TraceSecondFeatures.compute(m, ev.asSequence()), TraceSecondFeatures.compute(m, ev.asSequence()))
    }

    @Test fun theClassifierStateCarriesAcrossSecondsAsItDoesLive() {
        // a head turn (1 rad/s) at the very end of one second and a quiet sample 100 ms later in the next: HEAD_MOVING is held for 250 ms, so the next second still reads HEAD_MOVING
        val ev = listOf(gyro(2_740, 5000, 1f, 0f, 0f), gyro(2_760, 5100, 0f, 0f, 0f))
        val rows = TraceSecondFeatures.compute(m, ev.asSequence())
        assertEquals(listOf(MotionState.HEAD_MOVING, MotionState.HEAD_MOVING), rows.map { it.state })
    }
}
