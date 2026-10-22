package com.rokid.context.capture.trace

import com.rokid.context.core.model.TraceChunk
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChunkCodecTest {
    private val id = TraceFixtures.ID
    private fun fail(r: Result<*>): String = (r.exceptionOrNull() as? TraceFormatException)?.code ?: "NOT_A_TRACE_FORMAT_FAILURE:${r.exceptionOrNull()}:${r.getOrNull()}"
    private fun decodeText(text: String, seq: Int = 0, traceId: String = id, bounds: TraceBounds = TraceBounds.DEFAULT) =
        ChunkCodec.decode(traceId, seq, TraceFixtures.sha(text.toByteArray()), TraceFixtures.gzipB64(text.toByteArray()), bounds)

    @Test fun goldenPayloadIsByteExact() {
        assertEquals(TraceFixtures.goldenPayload, String(ChunkCodec.payload(id, 0, TraceFixtures.events), Charsets.UTF_8))
        assertEquals(TraceFixtures.GOLDEN_RAW_BYTES, ChunkCodec.payload(id, 0, TraceFixtures.events).size)
        assertEquals(TraceFixtures.GOLDEN_SHA, TraceFixtures.sha(ChunkCodec.payload(id, 0, TraceFixtures.events)))
    }

    @Test fun encodeThenDecodeRoundTrips() {
        val enc = ChunkCodec.encode(id, 0, TraceFixtures.events)
        assertEquals(TraceFixtures.GOLDEN_SHA, enc.sha256)
        assertEquals(17, enc.eventCount); assertEquals(1000, enc.firstTMs); assertEquals(12_000, enc.lastTMs)
        assertTrue(enc.b64.length <= TraceBounds.MAX_B64_CHARS)
        val dec = ChunkCodec.decode(id, 0, enc.sha256, enc.b64).getOrThrow()
        assertEquals(TraceFixtures.events, dec.events)
        assertEquals(TraceFixtures.GOLDEN_RAW_BYTES, dec.rawBytes)
    }

    @Test fun foreignGzipOfGoldenDecodes() {
        val dec = ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, TraceFixtures.GOLDEN_B64).getOrThrow()
        assertEquals(TraceFixtures.events, dec.events)
    }

    @Test fun decodesAWireChunk() {
        val enc = ChunkCodec.encode(id, 0, TraceFixtures.events)
        val wire = TraceChunk(traceId = id, kind = "chunk", seq = 0, count = 1, sha256 = enc.sha256, b64 = enc.b64)
        assertEquals(17, ChunkCodec.decode(wire).getOrThrow().events.size)
        assertEquals("chunk_count", fail(ChunkCodec.decode(wire.copy(count = 0))))
        assertEquals("chunk_count", fail(ChunkCodec.decode(wire.copy(count = 4097))))
        assertEquals("seq", fail(ChunkCodec.decode(wire.copy(seq = 1))))   // seq must be < count
        assertEquals("kind", fail(ChunkCodec.decode(wire.copy(kind = "manifest"))))
    }

    @Test fun gunzipBombRejected() {
        val bomb = ByteArray(40 * 1024 * 1024)   // 40 MB of zeros gzips to about 40 KB
        val b64 = TraceFixtures.gzipB64(bomb)
        assertTrue(b64.length < TraceBounds.MAX_B64_CHARS, "the bomb must fit the b64 bound for the test to mean anything")
        assertEquals("gunzip_too_big", fail(ChunkCodec.decode(id, 0, TraceFixtures.sha(bomb), b64)))
    }

    @Test fun gunzipLimitIsStreamed() {
        // one byte over the limit is refused, exactly the limit is not refused for size
        val text = TraceFixtures.goldenPayload
        val tight = TraceBounds(maxRawBytes = TraceFixtures.GOLDEN_RAW_BYTES)
        assertTrue(decodeText(text, bounds = tight).isSuccess)
        assertEquals("gunzip_too_big", fail(decodeText(text, bounds = TraceBounds(maxRawBytes = TraceFixtures.GOLDEN_RAW_BYTES - 1))))
    }

    @Test fun tooManyEventsRejected() {
        // the header claims more events than the bound: refused before any event line is read
        val hdr = """["PCT1","$id",0,200001]""" + "\n" + """["pr",1]""" + "\n"
        assertEquals("too_many_events", fail(decodeText(hdr)))
        val small = TraceBounds(maxEventsPerChunk = 15)
        assertEquals("too_many_events", fail(decodeText(TraceFixtures.goldenPayload, bounds = small)))
        assertFailsWith<TraceFormatException> { ChunkCodec.encode(id, 0, TraceFixtures.events, small) }
    }

    @Test fun badTraceIdRejected() {
        val bad = listOf("", "short", "UPPERCASE-ID-1234", "has space in id", "trace_0001_under", "a".repeat(65), "trace-0001\n", "trace-0001-" + 233.toChar() + "-x")
        for (b in bad) {
            assertEquals("bad_trace_id", fail(ChunkCodec.decode(b, 0, TraceFixtures.GOLDEN_SHA, TraceFixtures.GOLDEN_B64)), b)
            assertFailsWith<TraceFormatException>(b) { ChunkCodec.encode(b, 0, TraceFixtures.events) }
        }
        assertTrue(TraceBounds.isTraceId("a".repeat(8)) && TraceBounds.isTraceId("a".repeat(64)) && !TraceBounds.isTraceId("a".repeat(7)))
    }

    @Test fun tooManyChunksRejected() {
        assertEquals("seq", fail(ChunkCodec.decode(id, 4096, TraceFixtures.GOLDEN_SHA, TraceFixtures.GOLDEN_B64)))
        assertEquals("seq", fail(ChunkCodec.decode(id, -1, TraceFixtures.GOLDEN_SHA, TraceFixtures.GOLDEN_B64)))
        assertFailsWith<TraceFormatException> { ChunkCodec.encode(id, 4096, TraceFixtures.events) }
        assertEquals("seq", fail(ChunkCodec.decode(id, 4, TraceFixtures.GOLDEN_SHA, TraceFixtures.GOLDEN_B64, TraceBounds(maxChunks = 4))))
    }

    @Test fun b64Bounds() {
        assertEquals("b64_too_long", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, "A".repeat(90_001))))
        assertEquals("b64_invalid", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, "not base64 !!")))
        assertEquals("b64_invalid", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, TraceFixtures.GOLDEN_B64.substring(0, 20) + "\n" + TraceFixtures.GOLDEN_B64.substring(20))))
        // encode refuses what decode would refuse
        val big = List(20_000) { TraceEvent.Gyro(it.toLong(), it.toLong(), (it % 7) * 0.37f, it * 0.011f, -it * 0.003f) }
        assertFailsWith<TraceFormatException> { ChunkCodec.encode(id, 0, big, TraceBounds(maxRawBytes = 100_000)) }
        assertFailsWith<TraceFormatException> { ChunkCodec.encode(id, 0, big, TraceBounds(maxB64Chars = 1_000)) }
    }

    @Test fun base64MustBePadded() {
        val padded = TraceFixtures.GOLDEN_B64
        assertTrue(padded.length % 4 == 0)
        // find a chunk whose base64 needs padding: the JDK decoder would take it unpadded, the frozen format does not
        val enc = (1..12).map { n -> ChunkCodec.encode(id, 0, List(n) { TraceEvent.PreRollEnd(5) }) }.first { it.b64.endsWith("=") }
        assertTrue(ChunkCodec.decode(id, 0, enc.sha256, enc.b64).isSuccess)
        assertEquals("b64_invalid", fail(ChunkCodec.decode(id, 0, enc.sha256, enc.b64.trimEnd('='))))
        assertEquals("b64_invalid", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, padded + "A")))
        assertEquals("b64_invalid", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, padded.replace('+', '-').replace('/', '_') + "AAA")))
        assertTrue(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, padded).isSuccess)
    }

    @Test fun gzipDamageRejected() {
        val raw = Base64.getDecoder().decode(TraceFixtures.GOLDEN_B64)
        assertEquals("gzip_invalid", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, Base64.getEncoder().encodeToString(raw.copyOf(raw.size - 9)))))
        assertEquals("gzip_invalid", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, Base64.getEncoder().encodeToString("plain text".toByteArray()))))
        assertEquals("gzip_invalid", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA, "")))
    }

    @Test fun checksumMismatchRejected() {
        assertEquals("sha_mismatch", fail(ChunkCodec.decode(id, 0, "0".repeat(64), TraceFixtures.GOLDEN_B64)))
        assertEquals("sha_invalid", fail(ChunkCodec.decode(id, 0, TraceFixtures.GOLDEN_SHA.uppercase(), TraceFixtures.GOLDEN_B64)))
        assertEquals("sha_invalid", fail(ChunkCodec.decode(id, 0, "abc", TraceFixtures.GOLDEN_B64)))
    }

    @Test fun headerBindsPayloadToItsTraceAndSeq() {
        assertEquals("header_mismatch", fail(decodeText(TraceFixtures.goldenPayload, seq = 1)))              // payload says seq 0
        assertEquals("header_mismatch", fail(decodeText(TraceFixtures.goldenPayload, traceId = "trace-0002-other")))
        assertEquals("bad_header", fail(decodeText(TraceFixtures.goldenPayload.replace("PCT1", "PCT2"))))
        assertEquals("bad_header", fail(decodeText("")))
        assertEquals("bad_header", fail(decodeText("""["PCT1","$id",0,0]""" + "\n")))   // an empty chunk is not a chunk
    }

    @Test fun structureIsStrict() {
        val g = TraceFixtures.goldenPayload
        assertEquals("line_count", fail(decodeText(g.replace(",17]", ",18]"))))
        assertEquals("line_count", fail(decodeText(g + """["pr",12001]""" + "\n")))
        assertEquals("bad_payload", fail(decodeText(g.trimEnd('\n'))))                                   // missing final newline
        assertEquals("bad_payload", fail(decodeText(g.replace("\n", "\r\n"))))
        assertEquals("bad_payload", fail(decodeText(g.replace("\n[\"cx\"", "\n\n[\"cx\""))))             // blank line
        assertEquals("bad_event", fail(decodeText(g.replace("\"REST\"", "\"MARS\""))))
        assertEquals("bad_event", fail(decodeText(g.replace("""["pr",11000]""", """["pr", 11000]"""))))
        assertEquals("bad_payload", fail(decodeText(String(byteArrayOf(0x5b, 0x22, 0x70, 0x72, 0x22, 0x2c, 0x31, 0x5d, 0x0a, 0xff.toByte()), Charsets.ISO_8859_1))))
    }

    @Test fun timeMustNotGoBackwards() {
        val g = TraceFixtures.goldenPayload.replace("""["fl",2700,"window"]""", """["fl",2599,"window"]""")
        assertEquals("not_monotonic", fail(decodeText(g)))
        assertFailsWith<TraceFormatException> { ChunkCodec.encode(id, 0, listOf(TraceEvent.PreRollEnd(5), TraceEvent.PreRollEnd(4))) }
        assertTrue(ChunkCodec.encode(id, 0, listOf(TraceEvent.PreRollEnd(5), TraceEvent.PreRollEnd(5))).eventCount == 2)  // equal times are fine
    }

    @Test fun emptyChunkRefusedOnEncode() {
        assertFailsWith<TraceFormatException> { ChunkCodec.encode(id, 0, emptyList()) }
    }
}
