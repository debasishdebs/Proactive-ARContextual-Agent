package com.rokid.context.core.model.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextPoliciesTest {
    @Test fun everyStateHasARow() {
        for (s in ContextState.values()) assertTrue(ContextPolicies.DEFAULT.containsKey(s), s.name)
    }

    @Test fun restRow() {
        val p = ContextPolicies.of(ContextState.REST)
        assertEquals(2, p.capPerMin)
        assertEquals(30_000L, p.minSettledCheckMs); assertEquals(60_000L, p.minQuietCheckMs)
        assertEquals(30_000L, p.minWalkCheckMs); assertEquals(120_000L, p.minStillCapMs)
        assertTrue(p.forceSettle); assertFalse(p.pause); assertTrue(p.nudges)
    }

    @Test fun vehicleAndFlightRows() {
        // user ruling: vehicle classification never pauses or throttles capture; only DRIVING suppresses nudges
        val u = ContextPolicies.of(ContextState.UNKNOWN)
        val pa = ContextPolicies.of(ContextState.VEHICLE_PASSENGER)
        assertEquals(u, pa); assertNull(pa.capPerMin); assertFalse(pa.forceSettle); assertTrue(pa.nudges)
        val dr = ContextPolicies.of(ContextState.VEHICLE_DRIVING)
        assertEquals(u.copy(nudges = false), dr); assertNull(dr.capPerMin); assertFalse(dr.nudges)
        val f = ContextPolicies.of(ContextState.FLIGHT)
        assertEquals(1, f.capPerMin); assertEquals(120_000L, f.minSettledCheckMs); assertEquals(300_000L, f.minQuietCheckMs)
        assertEquals(120_000L, f.minWalkCheckMs); assertEquals(300_000L, f.minStillCapMs)
        for (s in ContextState.values()) assertFalse(ContextPolicies.of(s).pause, s.name)
    }

    @Test fun walkingEqualsUnknown() {
        val u = ContextPolicies.of(ContextState.UNKNOWN)
        assertEquals(u, ContextPolicies.of(ContextState.WALKING))
        assertNull(u.capPerMin); assertFalse(u.forceSettle); assertEquals(0L, u.minStillCapMs)
    }

    @Test fun missingKeyFallsBackToUnknownRow() {
        assertEquals(ContextPolicies.of(ContextState.UNKNOWN), ContextPolicies.of(ContextState.FLIGHT, emptyMap()))
    }
}
