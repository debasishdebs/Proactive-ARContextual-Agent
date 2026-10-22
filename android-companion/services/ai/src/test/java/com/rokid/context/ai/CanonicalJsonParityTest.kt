package com.rokid.context.ai

import com.rokid.context.capture.plan.CanonicalJson
import com.rokid.context.capture.plan.PlanJson
import com.rokid.context.capture.plan.PlanResolver
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the canonical text and hash that the phone-side PlanCompiler (Task 7) must reproduce byte for byte. It runs the same
 * :core:capture code on the same JVM, so it is a change detector for the pinned values, not an independent phone/glasses check; only
 * the identity hash is also asserted in :core:capture (PlanJsonTest).
 */
class CanonicalJsonParityTest {
    @Test fun canonicalTextIsPinned() {
        val src = """{ "c":"\u0001\u00e9\"\\","b":{"y":[1.0,2.50,0.0,-0.0],"x":0.50},"a":1.000,"n":null,"t":true }"""
        assertEquals("{\"a\":1,\"b\":{\"x\":0.5,\"y\":[1,2.5,0,0]},\"c\":\"\\u0001\u00e9\\\"\\\\\",\"n\":null,\"t\":true}", CanonicalJson.encode(Json.parseToJsonElement(src)))
    }

    @Test fun identityPlanHashAndTextArePinned() {
        val c = PlanResolver.identity()
        assertEquals(PINNED_IDENTITY_HASH, PlanJson.configHash(c))
        assertEquals(PINNED_IDENTITY_CONFIG, CanonicalJson.encode(PlanJson.configJson(c)))
    }

    private companion object {
        const val PINNED_IDENTITY_HASH = "f5782e0d8610"
        const val PINNED_IDENTITY_CONFIG =
            "{\"ctxBound\":true,\"gate\":{\"bigChange\":22,\"hamming\":10,\"ringCompare\":\"all\"},\"glance\":{\"bigTurnRad\":0.8,\"bridgeMs\":250,\"minBoutMs\":300,\"minBoutRad\":0.26,\"noveltyMinRad\":0.3,\"noveltyWindowMs\":20000,\"onsetRadS\":0.6,\"releaseRadS\":0.3,\"settleMs\":1500},\"phoneFusion\":{\"enterRestMs\":120000,\"enterUnknownMs\":60000,\"enterVehicleMs\":30000,\"fastConfirmGapMs\":45000,\"fastKmh\":25,\"fastTtlMs\":120000,\"hintStillTtlMs\":120000,\"stillFrac\":0.85,\"stillFracWithImage\":0.7,\"switchDriverMs\":60000,\"vehicleHoldMs\":180000},\"policy\":{\"FLIGHT\":{\"capPerMin\":1,\"forceSettle\":true,\"nudges\":true,\"timerPhotoGapsMs\":{\"quietMs\":300000,\"settledMs\":120000,\"stillCapMs\":300000,\"walkMs\":120000}},\"REST\":{\"capPerMin\":2,\"forceSettle\":true,\"nudges\":true,\"timerPhotoGapsMs\":{\"quietMs\":60000,\"settledMs\":30000,\"stillCapMs\":120000,\"walkMs\":30000}},\"VEHICLE_DRIVING\":{\"capPerMin\":null,\"forceSettle\":false,\"nudges\":false,\"timerPhotoGapsMs\":{\"quietMs\":0,\"settledMs\":0,\"stillCapMs\":0,\"walkMs\":0}},\"VEHICLE_PASSENGER\":{\"capPerMin\":null,\"forceSettle\":false,\"nudges\":true,\"timerPhotoGapsMs\":{\"quietMs\":0,\"settledMs\":0,\"stillCapMs\":0,\"walkMs\":0}}}}"
    }
}
