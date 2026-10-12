package com.rokid.context.link

import com.rokid.context.core.model.FrameAckReason
import com.rokid.context.core.model.FrameChunk
import com.rokid.context.core.model.FrameCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FrameReassemblerTest {
    private val jpeg = ByteArray(5_000) { (it % 251).toByte() }
    /** 5 000 B → 6 668 Base64 chars → four chunks of ≤ 2 000. */
    private fun chunks(id: String = "f1") = FrameCodec.split(id, jpeg, capturedAtMs = 7, chunkChars = 2_000)

    @Test fun singleChunkFrameCompletesAtOnce() {
        val r = FrameReassembler().accept(FrameCodec.split("f1", jpeg, capturedAtMs = 7).single(), nowMs = 0)
        assertIs<Assembly.Complete>(r)
        assertContentEquals(jpeg, r.jpeg)
    }

    @Test fun chunksInAnyOrderComplete() {
        val r = FrameReassembler()
        val c = chunks()
        assertEquals(Assembly.Pending, r.accept(c[2], 0))
        assertEquals(Assembly.Pending, r.accept(c[0], 1))
        assertEquals(Assembly.Pending, r.accept(c[3], 2))
        val done = r.accept(c[1], 3)
        assertIs<Assembly.Complete>(done)
        assertContentEquals(jpeg, done.jpeg)
        assertEquals(0, r.pendingCount)
    }

    @Test fun crcMismatchIsRejected() {
        val r = FrameReassembler()
        val c = chunks()
        val bad = c[1].copy(chunk = c[1].chunk.replaceRange(5, 6, if (c[1].chunk[5] == 'A') "B" else "A"))
        r.accept(c[0], 0); r.accept(bad, 0); r.accept(c[2], 0)
        assertEquals(Assembly.Rejected("f1", FrameAckReason.CRC), r.accept(c[3], 0))
    }

    @Test fun partialFrameIsDiscardedAfterTenSecondsWithoutAChunk() {
        val r = FrameReassembler()
        val c = chunks()
        r.accept(c[0], 0); r.accept(c[1], 4_000)
        assertEquals(0, r.sweep(13_999))
        assertEquals(1, r.sweep(14_000))
        assertEquals(0, r.pendingCount)
        r.accept(c[2], 14_001)
        assertEquals(Assembly.Pending, r.accept(c[3], 14_002), "the discarded half cannot complete")
    }

    @Test fun retryFromChunkZeroRestartsTheFrame() {
        val r = FrameReassembler()
        val c = chunks()
        r.accept(c[0], 0); r.accept(c[1], 1) // attempt 1 lost chunks 2 and 3
        c.forEachIndexed { i, ch ->
            val out = r.accept(ch, 10L + i)
            if (i < 3) assertEquals(Assembly.Pending, out) else assertIs<Assembly.Complete>(out)
        }
    }

    @Test fun badIndexesAreIgnored() {
        val m = chunks()[0].meta
        assertEquals(Assembly.Ignored, FrameReassembler().accept(FrameChunk(m.copy(chunkIdx = 4), "AAAA"), 0))
        assertEquals(Assembly.Ignored, FrameReassembler().accept(FrameChunk(m.copy(chunkCount = 0, chunkIdx = 0), "AAAA"), 0))
        assertEquals(Assembly.Ignored, FrameReassembler().accept(FrameChunk(m.copy(chunkCount = 65, chunkIdx = 0), "AAAA"), 0))
    }

    @Test fun twoFramesInterleave() {
        val r = FrameReassembler()
        val a = chunks("fa"); val b = chunks("fb")
        for (i in 0 until 3) { r.accept(a[i], 0); r.accept(b[i], 0) }
        assertIs<Assembly.Complete>(r.accept(b[3], 0))
        assertIs<Assembly.Complete>(r.accept(a[3], 0))
    }
}
