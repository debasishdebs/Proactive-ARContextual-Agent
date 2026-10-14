package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FrameCodecTest {
    private fun jpeg(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }

    /** Phase 0 sample: 20 396 B → 27 196 Base64 chars, below SAFE_CHUNK_CHARS, so one message. */
    @Test fun phaseZeroSampleFitsInOneChunk() {
        val chunks = FrameCodec.split("f1", jpeg(20_396), capturedAtMs = 5)
        assertEquals(1, chunks.size)
        assertEquals(27_196, chunks.single().chunk.length)
        assertEquals(1, chunks.single().meta.chunkCount)
    }

    @Test fun largeFrameSplitsAtSafeChunkChars() {
        val data = jpeg(60_000) // 80 000 Base64 chars
        val chunks = FrameCodec.split("f2", data, capturedAtMs = 5)
        assertEquals(listOf(32_768, 32_768, 14_464), chunks.map { it.chunk.length })
        assertEquals(listOf(0, 1, 2), chunks.map { it.meta.chunkIdx })
        assertTrue(chunks.all { it.meta.chunkCount == 3 && it.meta.crc32 == FrameCodec.crc32(data) }, "every chunk carries the whole-frame CRC")
    }

    @Test fun joinRestoresTheBytes() {
        val original = jpeg(9_999)
        val chunks = FrameCodec.split("f3", original, capturedAtMs = 1, chunkChars = 1_000)
        val r = FrameCodec.join(chunks.first().meta, chunks.map { it.chunk })
        assertIs<JoinResult.Ok>(r)
        assertContentEquals(original, r.jpeg)
    }

    @Test fun alteredChunkIsACrcMismatch() {
        val c = FrameCodec.split("f4", jpeg(3_000), capturedAtMs = 1).single()
        val altered = c.chunk.replaceRange(10, 11, if (c.chunk[10] == 'A') "B" else "A")
        assertEquals(JoinResult.CrcMismatch, FrameCodec.join(c.meta, listOf(altered)))
    }

    @Test fun invalidBase64IsReported() {
        val meta = FrameCodec.split("f5", jpeg(10), capturedAtMs = 1).single().meta
        assertEquals(JoinResult.BadBase64, FrameCodec.join(meta, listOf("**not base64**")))
    }

    @Test fun frameIdsAreFileNameSafe() {
        assertTrue(FrameCodec.isValidFrameId("g1793000000000_a1b2c3"))
        assertFalse(FrameCodec.isValidFrameId("../etc"))
        assertFalse(FrameCodec.isValidFrameId(""))
        assertFalse(FrameCodec.isValidFrameId("a".repeat(65)))
        assertFailsWith<IllegalArgumentException> { FrameCodec.split("bad/id", jpeg(10), capturedAtMs = 1) }
    }

    @Test fun metaRoundTripsOnTheWire() {
        val meta = FrameCodec.split("f6", jpeg(10), capturedAtMs = 42, luma = 90, phash = "ff", batteryPct = 81).single().meta
        assertEquals(Decoded.Ok(meta), Wire.decode<FrameMeta>(Wire.encode(meta)))
    }

    @Test fun helloWithoutNextCaptureStillDecodes() {
        val old = """{"v":$PROTOCOL_VERSION,"glassesBatteryPct":80,"bufferSize":2,"glassesNowMs":9,"pauses":[]}"""
        assertEquals(Decoded.Ok(Hello(glassesBatteryPct = 80, bufferSize = 2, glassesNowMs = 9, pauses = emptyList())), Wire.decode<Hello>(old))
    }

    @Test fun codecSplitJoinPreservesKeyframeMeta() {
        val data = jpeg(60_000)
        val chunks = FrameCodec.split("k1", data, capturedAtMs = 5, luma = 90, phash = "0123456789abcdef", batteryPct = 80,
            motion = MotionState.HEAD_MOVING, dwell = true, blur = 33.25f, dhash = "fedcba9876543210", width = 640, sampleSeq = 42L, sessionId = "sess")
        assertEquals(3, chunks.size)
        for (c in chunks) {
            val m = c.meta
            assertEquals(MotionState.HEAD_MOVING, m.motion); assertTrue(m.dwell); assertEquals(33.25f, m.blur)
            assertEquals("fedcba9876543210", m.dhash); assertEquals("0123456789abcdef", m.phash); assertEquals(640, m.width)
            assertEquals(42L, m.sampleSeq); assertEquals("sess", m.sessionId); assertEquals(90, m.luma); assertEquals(80, m.batteryPct)
            assertEquals(m, assertIs<Decoded.Ok<FrameMeta>>(Wire.decode<FrameMeta>(Wire.encode(m))).value)
        }
        assertContentEquals(data, assertIs<JoinResult.Ok>(FrameCodec.join(chunks[0].meta, chunks.map { it.chunk })).jpeg)
    }
}
