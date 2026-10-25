package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.9.6 slice C wire: a mark touches capture with ADDITIVE optional fields and one additive channel; `PROTOCOL_VERSION` stays 1 and an old peer decodes and ignores them. */
class MarkPhotoWireTest {
    private inline fun <reified T> ok(text: String): T = assertIs<Decoded.Ok<T>>(Wire.decode<T>(text)).value

    @Test fun theVersionIsStillOneAndTheChannelIsNew() {
        assertEquals(1, PROTOCOL_VERSION)
        assertEquals("pc_mark_photo", Channels.MARK_PHOTO)
    }

    @Test fun aMarkSaysItWillTakeAPhotoAndAnOldMarkSaysNothing() {
        val m = MarkMessage(epoch = "e", seq = 3, atMs = 1, atElapsedMs = 2, photo = true)
        assertTrue(ok<MarkMessage>(Wire.encode(m)).photo)
        assertFalse(ok<MarkMessage>("""{"v":1,"epoch":"e","seq":1,"atMs":1,"atElapsedMs":2}""").photo, "an old glasses build sends no photo field")
    }

    @Test fun anOldPhoneIgnoresThePhotoFieldOfAMark() {
        @kotlinx.serialization.Serializable data class OldMark(val v: Int = PROTOCOL_VERSION, val epoch: String, val seq: Long, val atMs: Long, val atElapsedMs: Long)
        val old = ok<OldMark>(Wire.encode(MarkMessage(epoch = "e", seq = 3, atMs = 1, atElapsedMs = 2, photo = true)))
        assertEquals(3L, old.seq)
    }

    @Test fun aFrameNamesItsMarkAndAnOldFrameHasNone() {
        val chunks = FrameCodec.split("f1", ByteArray(40) { 1 }, 7L, markRef = "ep:4", chunkChars = 16)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.meta.markRef == "ep:4" }, "every chunk carries the reference")
        assertEquals("ep:4", ok<FrameMeta>(Wire.encode(chunks.first().meta)).markRef)
        assertNull(ok<FrameMeta>("""{"v":1,"frameId":"f1","chunkIdx":0,"chunkCount":1,"crc32":1,"capturedAtMs":2}""").markRef)
        assertNull(FrameCodec.split("f2", ByteArray(8) { 1 }, 7L).first().meta.markRef)
    }

    @Test fun aPhotoFailureRoundTripsAndAWrongVersionIsRefused() {
        val r = MarkPhotoResult(epoch = "e", seq = 9, reason = MarkPhotoReason.CAMERA_BUSY)
        assertEquals(r, ok<MarkPhotoResult>(Wire.encode(r)))
        assertIs<Decoded.VersionMismatch>(Wire.decode<MarkPhotoResult>("""{"v":2,"epoch":"e","seq":1,"reason":"x"}"""))
    }

    @Test fun theHoldDefaultsToThreeSecondsAndIsClampedToTwoToFour() {
        assertEquals(3_000, HudSettings().markPhotoHoldMs)
        assertEquals(3_000, ok<HudSettings>("""{"v":1}""").markPhotoHoldMs, "an old phone sends none: the default")
        assertEquals(2_000, HudSettings(markPhotoHoldMs = 100).normalized().markPhotoHoldMs)
        assertEquals(4_000, HudSettings(markPhotoHoldMs = 60_000).normalized().markPhotoHoldMs)
        assertEquals(2_500, HudSettings(markPhotoHoldMs = 2_500).normalized().markPhotoHoldMs)
    }

    @Test fun aReasonFromTheWireIsCutToAShortToken() {
        assertEquals("camera_busy", MarkPhotoReason.clean("camera_busy"))
        assertEquals("ab_1", MarkPhotoReason.clean("a b-_1 \n<x>".dropLast(4)), "spaces, dashes and markup are dropped")
        assertEquals(32, MarkPhotoReason.clean("x".repeat(500)).length)
        assertEquals(MarkPhotoReason.NO_FRAME, MarkPhotoReason.clean("   "))
    }
}
