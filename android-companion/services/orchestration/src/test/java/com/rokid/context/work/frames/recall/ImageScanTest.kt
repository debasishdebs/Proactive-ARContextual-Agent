package com.rokid.context.work.frames.recall

import com.rokid.context.memory.retrieval.VectorCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageScanTest {
    private fun row(rowid: Long, id: String, v: FloatArray) = rowid to (id to VectorCodec.encode(v))

    /** Serves [rows] (any order) as keyset pages of [ImageScan.PAGE], newest rowid first, recording the cursors. */
    private class Pager(rows: List<Pair<Long, Pair<String, ByteArray>>>, val cursors: MutableList<Long> = mutableListOf()) {
        private val sorted = rows.sortedByDescending { it.first }
        val fn: suspend (Long) -> List<Pair<Long, Pair<String, ByteArray>>> = { before ->
            cursors += before
            sorted.filter { it.first < before }.take(ImageScan.PAGE)
        }
    }

    private val q = floatArrayOf(1f, 0f)

    @Test fun ordersByScoreThenId() = runBlocking {
        val rows = listOf(row(1, "b", floatArrayOf(0.5f, 0f)), row(2, "a", floatArrayOf(0.5f, 0f)), row(3, "z", floatArrayOf(1f, 0f)))
        val r = ImageScan.topK(q, 10, Pager(rows).fn, { 0L })
        assertEquals(listOf("z", "a", "b"), r.hits.map { it.first })
        assertEquals(1.0, r.hits.first().second, 1e-6)
    }

    @Test fun boundedHeapKeepsTopK() = runBlocking {
        val rows = (1L..50L).map { row(it, "f%02d".format(it), floatArrayOf(it / 100f, 0f)) }
        val r = ImageScan.topK(q, 3, Pager(rows).fn, { 0L })
        assertEquals(listOf("f50", "f49", "f48"), r.hits.map { it.first })
        assertEquals(50, r.scanned); assertFalse(r.truncated)
    }

    @Test fun skipsWrongDimensionRows() = runBlocking {
        val rows = listOf(row(1, "ok", floatArrayOf(1f, 0f)), row(2, "wide", floatArrayOf(1f, 0f, 0f)), row(3, "bad", floatArrayOf(1f)))
        val r = ImageScan.topK(q, 10, Pager(rows).fn, { 0L })
        assertEquals(listOf("ok"), r.hits.map { it.first })
        assertEquals(3, r.scanned)
    }

    @Test fun stopsAtMaxScanAndFlagsTruncated() = runBlocking {
        val rows = (1L..30L).map { row(it, "f$it", floatArrayOf(1f, 0f)) }
        val r = ImageScan.topK(q, 5, Pager(rows).fn, { 0L }, maxScan = 10)
        assertEquals(10, r.scanned); assertTrue(r.truncated); assertFalse(r.timedOut)
        // exactly maxScan rows and nothing after them is not truncated
        val exact = ImageScan.topK(q, 5, Pager(rows.take(10)).fn, { 0L }, maxScan = 10)
        assertEquals(10, exact.scanned); assertFalse(exact.truncated)
    }

    @Test fun stopsAtBudgetWithFakeClock() = runBlocking {
        val rows = (1L..(ImageScan.PAGE * 3L)).map { row(it, "f$it", floatArrayOf(1f, 0f)) }
        var now = 0L
        val pager = Pager(rows)
        val r = ImageScan.topK(q, 5, { b -> pager.fn(b).also { now += 600 } }, { now }, budgetMs = 1_000)
        assertTrue(r.timedOut); assertTrue(r.truncated)
        assertEquals(2 * ImageScan.PAGE, r.scanned) // pages fetched at t=0 and t=600; t=1200 is past the budget
    }

    @Test fun pagesByVectorRowidDesc() = runBlocking {
        val n = ImageScan.PAGE + 7L
        val pager = Pager((1L..n).map { row(it, "f$it", floatArrayOf(1f, 0f)) })
        val r = ImageScan.topK(q, 5, pager.fn, { 0L })
        assertEquals(n.toInt(), r.scanned)
        // keyset: first page from MAX, then from the lowest rowid of the previous page; the short second page ends the scan
        assertEquals(listOf(Long.MAX_VALUE, n - ImageScan.PAGE + 1), pager.cursors)
    }

    @Test fun nonFiniteScoresAreSkipped() = runBlocking {
        val rows = listOf(row(1, "nan", floatArrayOf(Float.NaN, 0f)), row(2, "inf", floatArrayOf(Float.POSITIVE_INFINITY, 0f)), row(3, "ok", floatArrayOf(0.5f, 0f)))
        val r = ImageScan.topK(q, 10, Pager(rows).fn, { 0L })
        assertEquals(listOf("ok"), r.hits.map { it.first })
    }

    @Test fun tieEvictionAtTheBoundKeepsTheSmallerId() = runBlocking {
        val rows = listOf(row(1, "b", floatArrayOf(0.5f, 0f)), row(2, "a", floatArrayOf(0.5f, 0f)), row(3, "c", floatArrayOf(0.5f, 0f)))
        assertEquals(listOf("a"), ImageScan.topK(q, 1, Pager(rows).fn, { 0L }).hits.map { it.first })
        assertEquals(listOf("a", "b"), ImageScan.topK(q, 2, Pager(rows).fn, { 0L }).hits.map { it.first })
    }

    @Test fun hugeKDoesNotOverflowTheHeap() = runBlocking {
        val r = ImageScan.topK(q, Int.MAX_VALUE, Pager(listOf(row(1, "a", floatArrayOf(1f, 0f)))).fn, { 0L })
        assertEquals(listOf("a"), r.hits.map { it.first })
    }

    @Test fun budgetExpiredWithNoRowsLeftIsNotTruncated() = runBlocking {
        val rows = (1L..ImageScan.PAGE.toLong()).map { row(it, "f$it", floatArrayOf(1f, 0f)) }
        var now = 0L
        val pager = Pager(rows)
        val r = ImageScan.topK(q, 5, { b -> pager.fn(b).also { now += 5_000 } }, { now }, budgetMs = 1_000)
        assertEquals(ImageScan.PAGE, r.scanned); assertFalse(r.truncated); assertFalse(r.timedOut)
    }
}
