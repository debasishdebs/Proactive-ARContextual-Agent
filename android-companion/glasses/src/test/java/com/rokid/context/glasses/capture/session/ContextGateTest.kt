package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.MotionContextMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContextGateTest {
    private fun msg(s: ContextState, apply: Boolean = true, reasons: List<String> = emptyList()) = MotionContextMessage(state = s, atMs = 1, apply = apply, reasons = reasons)
    private fun ContextGate.eff(now: Long, local: MotionState = MotionState.STILL, walk: Long? = null, off: List<String> = emptyList()) = effective(now, local, walk, off)

    @Test fun noMessageIsUnknown() { assertEquals(ContextState.UNKNOWN, ContextGate().eff(0)); assertNull(ContextGate().raw()) }

    @Test fun freshPassengerIsItselfUntilTheTtlRunsOut() {
        val g = ContextGate(); g.onMessage(msg(ContextState.VEHICLE_PASSENGER), 1_000)
        assertEquals(ContextState.VEHICLE_PASSENGER, g.eff(2_000))
        assertEquals(ContextState.VEHICLE_PASSENGER, g.eff(181_000))
        assertEquals(ContextState.UNKNOWN, g.eff(181_001))
    }

    @Test fun applyFalseAndPhoneMotionOffGiveUnknownButRawStays() {
        val g = ContextGate(); g.onMessage(msg(ContextState.VEHICLE_PASSENGER, apply = false), 0)
        assertEquals(ContextState.UNKNOWN, g.eff(1)); assertEquals(ContextState.VEHICLE_PASSENGER, g.raw())
        g.onMessage(msg(ContextState.REST), 0)
        assertEquals(ContextState.UNKNOWN, g.eff(1, off = listOf("phone_motion")))
        assertEquals(ContextState.REST, g.eff(1, off = listOf("watch_livefit")))
    }

    @Test fun localWalkingNowVetoesAndCounts() {
        val g = ContextGate(); g.onMessage(msg(ContextState.REST), 0)
        assertEquals(ContextState.UNKNOWN, g.eff(1_000, local = MotionState.WALKING))
        assertEquals(1, g.takeVetoes()); assertEquals(0, g.takeVetoes())
    }

    @Test fun recentWalkVetoWindowIsFifteenSeconds() {
        val g = ContextGate(); g.onMessage(msg(ContextState.VEHICLE_DRIVING), 0)
        assertEquals(ContextState.UNKNOWN, g.eff(30_000, walk = 16_000))
        assertEquals(ContextState.VEHICLE_DRIVING, g.eff(30_000, walk = 14_000))
        assertEquals(ContextState.VEHICLE_DRIVING, g.eff(30_000, walk = 15_000))
    }

    @Test fun theVetoAppliesWhateverTheReasons() {
        val g = ContextGate(); g.onMessage(msg(ContextState.VEHICLE_PASSENGER, reasons = listOf("SPEED", "AR_VEHICLE", "ANDROID_AUTO")), 0)
        assertEquals(ContextState.UNKNOWN, g.eff(10_000, walk = 5_000))
        g.onMessage(msg(ContextState.FLIGHT, reasons = listOf("SPEED")), 0)
        assertEquals(ContextState.UNKNOWN, g.eff(10_000, walk = 5_000))
    }

    @Test fun aWalkingMessageIsNeverVetoed() {
        val g = ContextGate(); g.onMessage(msg(ContextState.WALKING), 0)
        assertEquals(ContextState.WALKING, g.eff(1_000, local = MotionState.WALKING))
        assertEquals(0, g.takeVetoes())
    }

    @Test fun resetGivesUnknownAndAgeIsFromReceipt() {
        val g = ContextGate(); g.onMessage(msg(ContextState.REST), 1_000)
        assertEquals(4_000L, g.ageMs(5_000))
        g.onMessage(null, 6_000)
        assertEquals(ContextState.UNKNOWN, g.eff(6_001)); assertNull(g.ageMs(7_000)); assertNull(g.raw())
    }

    @Test fun aLongVetoSpellCountsOnce() {
        val g = ContextGate(); g.onMessage(msg(ContextState.REST), 0)
        repeat(5) { g.eff(1_000L + it, local = MotionState.WALKING) }
        assertEquals(1, g.takeVetoes())
    }
}
