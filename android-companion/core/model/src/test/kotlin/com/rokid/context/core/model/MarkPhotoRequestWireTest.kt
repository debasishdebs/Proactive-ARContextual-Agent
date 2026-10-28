package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** v0.9.7 slice D wire: a phone-origin mark asks the glasses for its photo on ONE additive channel; PROTOCOL_VERSION stays 1, an old glasses ignores the channel and the new Hello flag is optional. */
class MarkPhotoRequestWireTest {
    @Test fun theVersionIsStillOneAndTheChannelIsNew() {
        assertEquals(1, PROTOCOL_VERSION)
        assertEquals("pc_mark_photo_request", Channels.MARK_PHOTO_REQUEST)
    }

    @Test fun aRequestRoundTripsAndItsReferenceIsAValidMarkRef() {
        val r = MarkPhotoRequest(ref = MarkRef.of("phone", 1_700_000_000_123))
        val back = assertIs<Decoded.Ok<MarkPhotoRequest>>(Wire.decode<MarkPhotoRequest>(Wire.encode(r))).value
        assertEquals(r.ref, back.ref)
        assertTrue(MarkRef.isValid(back.ref))
        assertEquals("mk:phone:1700000000123", MarkRef.labelId(back.ref))
    }

    @Test fun anOldGlassesHelloSaysNothingAndMeansNoSupport() {
        val old = """{"v":1,"glassesBatteryPct":50,"bufferSize":0,"glassesNowMs":1,"pauses":[]}"""
        assertFalse(assertIs<Decoded.Ok<Hello>>(Wire.decode<Hello>(old)).value.markPhotoRequest)
        val h = Hello(glassesBatteryPct = 1, bufferSize = 0, glassesNowMs = 1, pauses = emptyList(), markPhotoRequest = true)
        assertTrue(assertIs<Decoded.Ok<Hello>>(Wire.decode<Hello>(Wire.encode(h))).value.markPhotoRequest)
    }

    @Test fun thePhoneSideReasonsAreShortTokensTheCleanerKeeps() {
        for (t in listOf(MarkPhotoReason.GLASSES_NOT_CONNECTED, MarkPhotoReason.GLASSES_NOT_WORN, MarkPhotoReason.CAMERA_BUSY, MarkPhotoReason.NO_PHOTO_SUPPORT, MarkPhotoReason.TIMEOUT)) assertEquals(t, MarkPhotoReason.clean(t))
    }
}
