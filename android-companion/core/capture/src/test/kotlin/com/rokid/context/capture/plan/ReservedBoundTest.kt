package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState
import kotlin.test.Test
import kotlin.test.assertEquals

class ReservedBoundTest {
    @Test fun boundsPerStateAndResting() {
        for (s in ContextState.entries) for (rest in listOf(true, false)) {
            val rest300 = s in setOf(ContextState.REST, ContextState.VEHICLE_PASSENGER, ContextState.VEHICLE_DRIVING, ContextState.FLIGHT) ||
                (rest && s != ContextState.WALKING)
            assertEquals(if (rest300) 300_000L else 60_000L, reservedBoundMs(s, rest), "$s resting=$rest")
        }
        assertEquals(60_000L, reservedBoundMs(ContextState.UNKNOWN, false))
        assertEquals(300_000L, reservedBoundMs(ContextState.UNKNOWN, true))   // S83a WorkRest resting while UNKNOWN
        assertEquals(60_000L, reservedBoundMs(ContextState.WALKING, true))
    }
}
