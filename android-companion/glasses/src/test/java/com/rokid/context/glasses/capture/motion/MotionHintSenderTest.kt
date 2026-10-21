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
}
