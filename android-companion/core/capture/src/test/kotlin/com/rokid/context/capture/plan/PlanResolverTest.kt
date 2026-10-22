package com.rokid.context.capture.plan

import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
import com.rokid.context.glasses.capture.motion.GlanceParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class PlanResolverTest {
    @Test fun identityEqualsDefaultPoliciesExactly() {
        val id = PlanResolver.identity()
        assertEquals(PlanState.entries.toSet(), id.policy.keys)
        for (s in PlanState.entries) {
            val d = ContextPolicies.DEFAULT.getValue(ContextState.valueOf(s.name))
            val r = id.policy.getValue(s)
            assertEquals(d.capPerMin, r.capPerMin, "$s cap")
            assertEquals(d.minSettledCheckMs, r.timerPhotoGapsMs.settledMs, "$s settled")
            assertEquals(d.minQuietCheckMs, r.timerPhotoGapsMs.quietMs, "$s quiet")
            assertEquals(d.minWalkCheckMs, r.timerPhotoGapsMs.walkMs, "$s walk")
            assertEquals(d.minStillCapMs, r.timerPhotoGapsMs.stillCapMs, "$s stillCap")
            assertEquals(d.forceSettle, r.forceSettle, "$s forceSettle")
            assertEquals(d.nudges, r.nudges, "$s nudges")
            assertEquals(d, PlanResolver.toContextPolicy(r), "$s whole row")
        }
        val rest = id.policy.getValue(PlanState.REST)
        assertEquals(2, rest.capPerMin)
        assertEquals(TimerPhotoGaps(30_000, 60_000, 30_000, 120_000), rest.timerPhotoGapsMs)
        assertTrue(rest.forceSettle)
        val flight = id.policy.getValue(PlanState.FLIGHT)
        assertEquals(1, flight.capPerMin)
        assertEquals(TimerPhotoGaps(120_000, 300_000, 120_000, 300_000), flight.timerPhotoGapsMs)
        val g = GlanceParams()
        assertEquals(GlanceBlock(g.onsetRadS, g.releaseRadS, g.bridgeMs, g.minBoutMs, g.minBoutRad, g.settleMs, g.noveltyWindowMs, g.noveltyMinRad, g.bigTurnRad), id.glance)
        assertEquals(GateBlock(KeyframeGate.BIG_CHANGE, KeyframeGate.HAMMING, "all"), id.gate)
        // the identity plan is valid under the bounds, with no clamping needed
        assertEquals(emptyList(), PlanValidator.check(PlanFixtures.text()).first)
    }

    @Test fun identityVehicleRowsUnchangedNoHardFlag() {
        val id = PlanResolver.identity()
        for (s in listOf(PlanState.VEHICLE_PASSENGER, PlanState.VEHICLE_DRIVING)) {
            val r = id.policy.getValue(s)
            assertNull(r.capPerMin)
            assertEquals(TimerPhotoGaps(0, 0, 0, 0), r.timerPhotoGapsMs)
            assertFalse(r.forceSettle)
        }
        assertTrue(id.policy.getValue(PlanState.VEHICLE_PASSENGER).nudges)
        assertFalse(id.policy.getValue(PlanState.VEHICLE_DRIVING).nudges)
        assertFalse(PlanResolver.toContextPolicy(id.policy.getValue(PlanState.VEHICLE_DRIVING)).pause)
    }

    @Test fun toContextPolicyRoundTrip() {
        for ((_, p) in ContextPolicies.DEFAULT) assertEquals(p, PlanResolver.toContextPolicy(PlanResolver.rowOf(p)))
    }

    @Test fun resolveFillsFromBaseAndKeepsExplicitNullCap() {
        val base = PlanResolver.identity()
        val partial = JsonObject(
            mapOf(
                "glance" to JsonObject(mapOf("onsetRadS" to JsonPrimitive(0.8))),
                "policy" to JsonObject(mapOf("REST" to JsonObject(mapOf("capPerMin" to JsonNull)))),
            ),
        )
        val r = PlanResolver.resolve(partial, base).getOrThrow()
        assertEquals(0.8f, r.glance.onsetRadS)
        assertEquals(base.glance.bridgeMs, r.glance.bridgeMs)
        assertNull(r.policy.getValue(PlanState.REST).capPerMin)
        assertEquals(base.policy.getValue(PlanState.REST).timerPhotoGapsMs, r.policy.getValue(PlanState.REST).timerPhotoGapsMs)
        assertEquals(base.policy.getValue(PlanState.FLIGHT), r.policy.getValue(PlanState.FLIGHT))
        assertTrue(PlanResolver.resolve(JsonObject(mapOf("bogus" to JsonPrimitive(1))), base).isFailure)
    }
}
