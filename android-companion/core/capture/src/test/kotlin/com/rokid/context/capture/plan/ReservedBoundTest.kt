package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextState
import kotlin.test.Test
import kotlin.test.assertEquals

/** S83: independent literals, not a re-statement of the rule. Rows: state, bound when not resting, bound when WorkRest is resting. */
class ReservedBoundTest {
    @Test fun boundsPerStateAndResting() {
        val expected = listOf(
            Triple(ContextState.UNKNOWN, 60_000L, 300_000L),
            Triple(ContextState.WALKING, 60_000L, 60_000L),
            Triple(ContextState.REST, 300_000L, 300_000L),
            Triple(ContextState.VEHICLE_PASSENGER, 300_000L, 300_000L),
            Triple(ContextState.VEHICLE_DRIVING, 300_000L, 300_000L),
            Triple(ContextState.FLIGHT, 300_000L, 300_000L),
        )
        assertEquals(ContextState.entries.toSet(), expected.map { it.first }.toSet())
        for ((s, active, resting) in expected) {
            assertEquals(active, reservedBoundMs(s, false), "$s not resting")
            assertEquals(resting, reservedBoundMs(s, true), "$s resting")
        }
    }
}
