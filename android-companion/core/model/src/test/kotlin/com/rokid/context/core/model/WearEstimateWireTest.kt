package com.rokid.context.core.model

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v1.0 wear fallback: additive optional fields only, PROTOCOL_VERSION stays 1, old builds ignore them. */
class WearEstimateWireTest {
    /** The MotionHint as a pre-v1.0 build declares it (no wearEst). */
    @Serializable private data class OldMotionHint(val state: MotionState = MotionState.UNKNOWN, val atMs: Long, val v: Int = PROTOCOL_VERSION, val stillFrac5m: Float = -1f, val classifierHash: String? = null)
    /** The WearMessage as a pre-v1.0 build declares it (no wearCheck). */
    @Serializable private data class OldWearMessage(val v: Int = PROTOCOL_VERSION, val worn: Boolean, val atMs: Long, val sessionStartMs: Long = 0L)

    @Test fun protocolVersionStaysOne() = assertEquals(1, PROTOCOL_VERSION)

    @Test fun motionHintRoundTripsWithAndWithoutTheEstimate() {
        for (h in listOf(MotionHint(MotionState.STILL, 5, wearEst = true), MotionHint(MotionState.STILL, 5, wearEst = false), MotionHint(MotionState.STILL, 5))) {
            assertEquals(h, assertIs<Decoded.Ok<MotionHint>>(Wire.decode<MotionHint>(Wire.encode(h))).value)
        }
        // an old glasses build never sends it: null, never a guess
        assertNull(assertIs<Decoded.Ok<MotionHint>>(Wire.decode<MotionHint>("""{"v":1,"state":"STILL","atMs":3,"stillFrac5m":0.5}""")).value.wearEst)
        // no estimate field on the wire when it is null (an old phone sees exactly the old shape)
        assertFalse(Wire.encode(MotionHint(MotionState.STILL, 5)).contains("wearEst"))
    }

    @Test fun anOldPhoneIgnoresTheEstimate() {
        val old = assertIs<Decoded.Ok<OldMotionHint>>(Wire.decode<OldMotionHint>(Wire.encode(MotionHint(MotionState.STILL, 7, stillFrac5m = 1f, wearEst = true)))).value
        assertEquals(OldMotionHint(MotionState.STILL, 7, stillFrac5m = 1f), old)
    }

    @Test fun wearMessageRoundTripsWithAndWithoutTheWearCheck() {
        for (m in listOf(WearMessage(worn = true, atMs = 9, sessionStartMs = 1, wearCheck = false), WearMessage(worn = true, atMs = 9, wearCheck = true), WearMessage(worn = false, atMs = 9))) {
            assertEquals(m, assertIs<Decoded.Ok<WearMessage>>(Wire.decode<WearMessage>(Wire.encode(m))).value)
        }
        assertNull(assertIs<Decoded.Ok<WearMessage>>(Wire.decode<WearMessage>("""{"v":1,"worn":true,"atMs":5,"sessionStartMs":2}""")).value.wearCheck)
        val old = assertIs<Decoded.Ok<OldWearMessage>>(Wire.decode<OldWearMessage>(Wire.encode(WearMessage(worn = true, atMs = 9, sessionStartMs = 1, wearCheck = false)))).value
        assertEquals(OldWearMessage(worn = true, atMs = 9, sessionStartMs = 1), old)
    }

    @Test fun aWearCheckUpdateTravelsWithoutAWearValueAndAnOldGlassesBuildIgnoresIt() {
        val m = WearMessage(atMs = 9, sessionStartMs = 1, wearCheck = true)
        val text = Wire.encode(m)
        assertFalse(text.contains("\"worn\""), text)
        assertEquals(m, assertIs<Decoded.Ok<WearMessage>>(Wire.decode<WearMessage>(text)).value)
        assertFalse(Wire.decode<OldWearMessage>(text) is Decoded.Ok<*>, "an old glasses build cannot decode it: ignored, as when nothing was sent")
    }

    @Test fun theEstimateAppliesOnlyAfterAPositiveWearCheckOff() {
        assertTrue(WearEstimate.applies(wearCheck = false, sensorLive = false), "check said off: the pinned 'worn' is not a real signal")
        assertFalse(WearEstimate.applies(wearCheck = null, sensorLive = false), "unknown = no estimate (conservative)")
        assertFalse(WearEstimate.applies(wearCheck = true, sensorLive = false), "check on: the real sensor speaks")
        assertFalse(WearEstimate.applies(wearCheck = false, sensorLive = true), "a real wear change was seen: the sensor works after all")
    }

    @Test fun tunablesAreTheRuledValues() {
        assertEquals(600_000L, WearEstimate.WINDOW_MS)
        assertEquals("not worn (estimated)", WearEstimate.WORD)
        assertEquals(3_000L, WearEstimate.MAX_GAP_MS)
        assertEquals(90L, WearEstimate.MIN_COVERAGE_PCT)
        assertEquals(180_000L, WearEstimate.FRESH_MS)
    }
}
