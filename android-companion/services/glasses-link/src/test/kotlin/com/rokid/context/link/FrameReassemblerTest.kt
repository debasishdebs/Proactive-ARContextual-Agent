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

    /** Fix round 1: a NACK (not silence), so the glasses drop the frame after two instead of resending it forever. */
    @Test fun badIndexesAreRejected() {
        val m = chunks()[0].meta
        val nack = Assembly.Rejected("f1", FrameAckReason.DECODE)
        assertEquals(nack, FrameReassembler().accept(FrameChunk(m.copy(chunkIdx = 4), "AAAA"), 0))
        assertEquals(nack, FrameReassembler().accept(FrameChunk(m.copy(chunkCount = 0, chunkIdx = 0), "AAAA"), 0))
        assertEquals(nack, FrameReassembler().accept(FrameChunk(m.copy(chunkCount = 65, chunkIdx = 0), "AAAA"), 0))
    }

    @Test fun twoFramesInterleave() {
        val r = FrameReassembler()
        val a = chunks("fa"); val b = chunks("fb")
        for (i in 0 until 3) { r.accept(a[i], 0); r.accept(b[i], 0) }
        assertIs<Assembly.Complete>(r.accept(b[3], 0))
        assertIs<Assembly.Complete>(r.accept(a[3], 0))
    }

    @Test fun aFifthConcurrentFrameEvictsTheStalestPartial() {
        val r = FrameReassembler()
        val frames = (1..5).map { chunks("p$it") }
        frames.forEachIndexed { i, c -> r.accept(c[0], i.toLong()) }
        assertEquals(4, r.pendingCount)
        val stalest = frames[0]
        (1..3).forEach { assertEquals(Assembly.Pending, r.accept(stalest[it], 10L + it)) }
        val newest = frames[4]
        (1..2).forEach { r.accept(newest[it], 20L + it) }
        assertIs<Assembly.Complete>(r.accept(newest[3], 30))
    }

    @Test fun anOversizeChunkIsRejectedAndDiscardsThePartial() {
        val r = FrameReassembler()
        val c = chunks()
        r.accept(c[0], 0)
        assertEquals(Assembly.Rejected("f1", FrameAckReason.DECODE), r.accept(FrameChunk(c[1].meta, "A".repeat(70_000)), 1))
        assertEquals(0, r.pendingCount)
    }

    @Test fun aDuplicateChunkIsIdempotent() {
        val r = FrameReassembler()
        val c = chunks()
        r.accept(c[0], 0); r.accept(c[1], 1); r.accept(c[1], 2); r.accept(c[2], 3)
        val done = r.accept(c[3], 4)
        assertIs<Assembly.Complete>(done)
        assertContentEquals(jpeg, done.jpeg)
    }

    @Test fun aChangedCrcOrCountRestartsTheFrame() {
        val r = FrameReassembler()
        val old = chunks()
        r.accept(old[0], 0); r.accept(old[1], 1); r.accept(old[2], 2)
        val other = ByteArray(5_000) { (it % 13).toByte() }
        val fresh = FrameCodec.split("f1", other, capturedAtMs = 7, chunkChars = 2_000)
        assertEquals(Assembly.Pending, r.accept(fresh[3], 3), "a new crc discards the old parts")
        (0..1).forEach { assertEquals(Assembly.Pending, r.accept(fresh[it], 4L + it)) }
        val done = r.accept(fresh[2], 6)
        assertIs<Assembly.Complete>(done)
        assertContentEquals(other, done.jpeg)
        val longer = FrameCodec.split("f2", jpeg, capturedAtMs = 7, chunkChars = 1_000)
        r.accept(chunks("f2")[0], 7)
        (1 until longer.size).forEach { assertEquals(Assembly.Pending, r.accept(longer[it], 8L + it), "a new count restarts") }
        assertIs<Assembly.Complete>(r.accept(longer[0], 30))
    }
}
