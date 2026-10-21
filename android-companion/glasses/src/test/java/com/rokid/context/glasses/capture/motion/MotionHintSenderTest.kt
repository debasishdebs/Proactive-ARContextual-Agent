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

    @Test fun stillFractionReflectsWalkingSeconds() {
        run(1, 100, MotionState.STILL); run(101, 120, MotionState.HEAD_MOVING)
        val f = sent.last().stillFrac5m
        assertEquals(true, f in 0.5f..1f, "frac=$f")
    }
}
