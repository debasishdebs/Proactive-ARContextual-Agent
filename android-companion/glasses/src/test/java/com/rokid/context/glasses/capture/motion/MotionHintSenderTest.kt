package com.rokid.context.glasses.capture.motion

import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.MotionState
import kotlin.test.Test
import kotlin.test.assertEquals

class MotionHintSenderTest {
    private var now = 0L
    private val sent = ArrayList<MotionHint>()
    private val s = MotionHintSender({ now }) { sent += it; true }
    private fun run(fromS: Int, toS: Int, st: MotionState) { for (t in fromS..toS) { now = t * 1_000L; s.onSecond(st, now) } }

    @Test fun stillSendsEverySixtySecondsWithFractionAfterSixtySlots() {
        run(1, 120, MotionState.STILL)
        assertEquals(listOf(60_000L, 120_000L), sent.map { it.atMs })
        assertEquals(-1f, sent[0].stillFrac5m); assertEquals(1f, sent[1].stillFrac5m)
        assertEquals(MotionState.STILL, sent[0].state)
    }

    @Test fun walkingEntryIsImmediateAndExitWaitsFiveSeconds() {
        run(1, 120, MotionState.STILL); sent.clear()
        run(121, 130, MotionState.STILL)
        now = 130_000; s.onSecond(MotionState.WALKING, now)
        assertEquals(listOf(MotionState.WALKING), sent.map { it.state })
        run(131, 131, MotionState.WALKING)
        now = 132_000; s.onSecond(MotionState.STILL, now)
        assertEquals(1, sent.size)
        run(133, 134, MotionState.STILL); assertEquals(1, sent.size)
        now = 136_000; s.onSecond(MotionState.STILL, now)
        assertEquals(listOf(MotionState.WALKING, MotionState.STILL), sent.map { it.state })
    }

    @Test fun stillFractionIsTheExactShareOfThePreviousSeconds() {
        run(1, 100, MotionState.STILL); run(101, 120, MotionState.HEAD_MOVING)
        assertEquals(100f / 119f, sent.last().stillFrac5m) // 100 still of the 119 seconds before the send at 120 s
    }

    @Test fun aFailedSendIsRetriedNextSecondAndForceNextSendsAtOnce() {
        var ok = false
        val sent2 = ArrayList<MotionHint>()
        val t = MotionHintSender({ now }) { sent2 += it; ok }
        now = 5_000; t.onSecond(MotionState.WALKING, now)
        now = 6_000; t.onSecond(MotionState.WALKING, now)
        assertEquals(2, sent2.size) // both attempts failed, so it tried again
        ok = true; now = 7_000; t.onSecond(MotionState.WALKING, now)
        now = 8_000; t.onSecond(MotionState.WALKING, now)
        assertEquals(3, sent2.size)
        t.forceNext(); now = 9_000; t.onSecond(MotionState.WALKING, now)
        assertEquals(4, sent2.size)
    }

    @Test fun aLongSamplingGapExpiresTheStillWindow() {
        run(1, 300, MotionState.STILL); sent.clear()
        now = 900_000L // ten minutes without a call, then a moving second
        s.onSecond(MotionState.HEAD_MOVING, now)
        assertEquals(1, sent.size)
        assertEquals(-1f, sent[0].stillFrac5m, "no recent coverage: unknown, not 1.0")
    }

    @Test fun samplesOlderThanFiveMinutesDoNotCount() {
        run(1, 300, MotionState.STILL); sent.clear()
        run(301, 420, MotionState.HEAD_MOVING)
        // the window at 420 s is 121..419 s: 180 still (121..300) of 299
        assertEquals(180f / 299f, sent.last().stillFrac5m)
    }

    @Test fun theClassifierHashIsEchoedAndAChangeIsSentAtOnce() {
        var hash: String? = "default"
        val out = ArrayList<MotionHint>()
        val t = MotionHintSender({ now }, classifierHash = { hash }) { out += it; true }
        for (sec in 1..3) { now = sec * 1_000L; t.onSecond(MotionState.STILL, now) }
        assertEquals(listOf("default"), out.map { it.classifierHash }, "the first hint carries it")
        hash = "0123456789ab"
        now = 4_000L; t.onSecond(MotionState.STILL, now)
        assertEquals(listOf("default", "0123456789ab"), out.map { it.classifierHash }, "a change does not wait for the 60 s refresh")
        now = 5_000L; t.onSecond(MotionState.STILL, now)
        assertEquals(2, out.size)
    }

    @Test fun anUnreportedHashStaysNullLikeAnOldGlasses() {
        val out = ArrayList<MotionHint>()
        val t = MotionHintSender({ now }) { out += it; true }
        now = 1_000L; t.onSecond(MotionState.STILL, now); now = 61_000L; t.onSecond(MotionState.STILL, now)
        assertEquals(listOf<String?>(null), out.map { it.classifierHash }.distinct())
    }

    @Test fun theWearEstimateRidesTheHintAndAChangeIsSentAtOnce() {
        var est: Boolean? = false
        val out = ArrayList<MotionHint>()
        val t = MotionHintSender({ now }, wearEst = { est }) { out += it; true }
        for (sec in 1..3) { now = sec * 1_000L; t.onSecond(MotionState.STILL, now) }
        assertEquals(listOf<Boolean?>(false), out.map { it.wearEst }, "the first hint carries it")
        est = true
        now = 4_000L; t.onSecond(MotionState.STILL, now)
        assertEquals(listOf<Boolean?>(false, true), out.map { it.wearEst }, "the estimate does not wait for the 60 s refresh")
        est = false
        now = 5_000L; t.onSecond(MotionState.STILL, now)
        assertEquals(listOf<Boolean?>(false, true, false), out.map { it.wearEst }, "worn again is sent at once too")
        now = 6_000L; t.onSecond(MotionState.STILL, now)
        assertEquals(3, out.size)
    }
}
