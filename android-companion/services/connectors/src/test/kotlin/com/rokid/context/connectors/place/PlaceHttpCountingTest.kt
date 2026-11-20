package com.rokid.context.connectors.place

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Place cell fetches count in the mobile-data ledger by the bytes that came over the wire (before gzip is unpacked). */
class PlaceHttpCountingTest {
    @Test fun countsTheWireBytesNotTheUnpackedText() {
        val text = "x".repeat(50_000)
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()
        val n = AtomicLong()
        val out = GZIPInputStream(CountingInputStream(ByteArrayInputStream(gz), n)).use { it.readBytes() }
        assertEquals(50_000, out.size)
        assertEquals(gz.size.toLong(), n.get())
        assertTrue(n.get() < 1_000, "the compressed size is what was received")
    }

    @Test fun countsPlainBytesReadOneByOneAndInBlocks() {
        val n = AtomicLong()
        val s = CountingInputStream(ByteArrayInputStream(ByteArray(10)), n)
        s.read(); s.read(ByteArray(4), 0, 4); s.readBytes()
        assertEquals(10L, n.get())
    }

    @Test fun theDefaultConstructorStillWorksWithNoGate() {
        UrlConnectionPlaceHttp()
    }
}
