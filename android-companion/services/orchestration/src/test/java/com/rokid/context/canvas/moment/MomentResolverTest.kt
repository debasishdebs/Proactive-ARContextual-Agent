package com.rokid.context.canvas.moment

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomentResolverTest {
    private class FakeSource(
        var frames: List<MomentFrame> = emptyList(), var memories: List<Pair<String, MomentRef>> = emptyList(), var marks: List<MomentMark> = emptyList(),
    ) : MomentSource {
        override suspend fun frames(fromMs: Long, toMs: Long, limit: Int) = frames.filter { it.ts in fromMs..toMs }.sortedBy { it.ts }.take(limit)
        override suspend fun framesNewest(fromMs: Long, toMs: Long, limit: Int) = frames.filter { it.ts in fromMs..toMs }.sortedBy { it.ts }.takeLast(limit)
        override suspend fun memories(frameIds: List<String>) = memories.filter { it.first in frameIds }
        override suspend fun marks(fromMs: Long, toMs: Long) = marks.filter { it.atMs in fromMs..toMs }
    }

    private fun f(id: String, ts: Long, rev: String = "r") = MomentFrame(id, ts, rev)
    private val sec = 1_761_000_000L
    private val ms = sec * 1000

    @Test fun framesOfOneSecondShareOneMomentAndNeighbouringSecondsDoNot() = runBlocking<Unit> {
        val r = MomentResolver(FakeSource(frames = listOf(f("a", ms + 5), f("b", ms + 999), f("c", ms + 1000), f("d", ms - 1))))
        val w = r.window(ms - 1000, ms + 1999, 50)
        assertEquals(listOf(MomentId.format(sec - 1), MomentId.format(sec), MomentId.format(sec + 1)), w.map { it.id })
        assertEquals(listOf("a", "b"), w[1].frames.map { it.id })
        assertEquals(ms, w[1].startMs)
    }

    @Test fun memoriesAndMarksOfThatSecondAreAttachedAsReferences() = runBlocking<Unit> {
        val src = FakeSource(
            frames = listOf(f("a", ms + 1), f("z", ms + 5000)),
            memories = listOf("a" to MomentRef("m1", "v1"), "z" to MomentRef("m2", "v1")),
            marks = listOf(MomentMark("L1", ms + 500, "k1"), MomentMark("L2", ms + 2500, "k1")),
        )
        val m = MomentResolver(src).resolve(listOf(MomentId.format(sec))).single()
        assertEquals(listOf("m1"), m.memories.map { it.id })
        assertEquals(listOf("L1"), m.marks.map { it.id })
    }

    @Test fun aSecondWithNoFrameIsNotAMoment() = runBlocking<Unit> {
        val src = FakeSource(frames = listOf(f("a", ms)), marks = listOf(MomentMark("L", ms + 3000, "k")))
        assertEquals(listOf(MomentId.format(sec)), MomentResolver(src).window(ms, ms + 9000, 50).map { it.id })
        assertTrue(MomentResolver(src).resolve(listOf(MomentId.format(sec + 3))).isEmpty())
    }

    @Test fun resolveKeepsTheRequestedOrderDropsMissingAndBadIds() = runBlocking<Unit> {
        val src = FakeSource(frames = listOf(f("a", ms), f("b", ms + 2000)))
        val ids = listOf(MomentId.format(sec + 2), "nope", MomentId.format(sec), MomentId.format(sec + 7), MomentId.format(sec))
        assertEquals(listOf(MomentId.format(sec + 2), MomentId.format(sec)), MomentResolver(src).resolve(ids).map { it.id })
    }

    @Test fun hashIsStableAndIndependentOfSourceOrder() = runBlocking<Unit> {
        val one = FakeSource(frames = listOf(f("a", ms + 1), f("b", ms + 2)), memories = listOf("a" to MomentRef("m1", "v"), "b" to MomentRef("m2", "v")))
        val two = FakeSource(frames = listOf(f("b", ms + 2), f("a", ms + 1)), memories = listOf("b" to MomentRef("m2", "v"), "a" to MomentRef("m1", "v")))
        val x = MomentResolver(one).resolve(listOf(MomentId.format(sec))).single()
        val y = MomentResolver(two).resolve(listOf(MomentId.format(sec))).single()
        assertEquals(x.contentHash, y.contentHash); assertEquals(x.contentRev, y.contentRev); assertEquals(64, x.contentHash.length)
        assertEquals(x.contentHash, MomentResolver(one).resolve(listOf(MomentId.format(sec))).single().contentHash)
    }

    @Test fun hashMovesWhenAnyMemberOrItsRevisionMoves() = runBlocking<Unit> {
        suspend fun hash(src: FakeSource) = MomentResolver(src).resolve(listOf(MomentId.format(sec))).single().contentHash
        val base = FakeSource(frames = listOf(f("a", ms)), memories = listOf("a" to MomentRef("m1", "v1")), marks = listOf(MomentMark("L", ms, "k1")))
        val h = hash(base)
        assertNotEquals("a late frame", h, hash(FakeSource(base.frames + f("b", ms + 1), base.memories, base.marks)))
        assertNotEquals("a frame revision", h, hash(FakeSource(listOf(f("a", ms, "r2")), base.memories, base.marks)))
        assertNotEquals("a memory revision", h, hash(FakeSource(base.frames, listOf("a" to MomentRef("m1", "v2")), base.marks)))
        assertNotEquals("a new memory", h, hash(FakeSource(base.frames, base.memories + ("a" to MomentRef("m3", "v1")), base.marks)))
        assertNotEquals("a corrected mark", h, hash(FakeSource(base.frames, base.memories, listOf(MomentMark("L", ms, "k2")))))
        assertNotEquals("a removed mark", h, hash(FakeSource(base.frames, base.memories, emptyList())))
        assertNotEquals("a removed frame changes the member count too", h, hash(FakeSource(listOf(f("a", ms), f("q", ms + 9)), base.memories, base.marks)))
    }

    @Test fun partsAreNotConfusableAcrossKinds() = runBlocking<Unit> {
        suspend fun hash(src: FakeSource) = MomentResolver(src).resolve(listOf(MomentId.format(sec))).single().contentHash
        val a = hash(FakeSource(frames = listOf(f("a", ms)), memories = listOf("a" to MomentRef("x", "v"))))
        val b = hash(FakeSource(frames = listOf(f("a", ms)), marks = listOf(MomentMark("x", ms, "v"))))
        assertNotEquals(a, b)
    }

    @Test fun bindingKeyCarriesIdRevAndHash() = runBlocking<Unit> {
        val m = MomentResolver(FakeSource(frames = listOf(f("a", ms)))).resolve(listOf(MomentId.format(sec))).single()
        assertEquals("${m.id}:${m.contentRev}:${m.contentHash}", m.bindingKey)
        assertEquals(1, m.contentRev)
    }

    @Test fun windowLimitKeepsTheNewestMoments() = runBlocking<Unit> {
        val src = FakeSource(frames = (0..9).map { f("f$it", ms + it * 1000L) })
        assertEquals((7..9).map { MomentId.format(sec + it) }, MomentResolver(src).window(ms, ms + 9999, 3).map { it.id })
    }

    @Test fun aSecondOverTheFrameCapIsRefusedNotTruncated() = runBlocking<Unit> {
        val many = (0..MomentResolver.FRAME_CAP).map { f("f$it", ms + (it % 1000)) } // cap + 1 photos in one second
        val e = runCatching { MomentResolver(FakeSource(frames = many)).resolve(listOf(MomentId.format(sec))) }.exceptionOrNull()
        assertTrue("got $e", e is MomentTooBig)
        assertEquals(1, MomentResolver(FakeSource(frames = many.take(MomentResolver.FRAME_CAP))).resolve(listOf(MomentId.format(sec))).size)
    }
}
