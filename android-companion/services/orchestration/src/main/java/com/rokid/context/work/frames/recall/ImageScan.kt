package com.rokid.context.work.frames.recall

import com.rokid.context.memory.retrieval.VectorCodec
import java.util.PriorityQueue

/**
 * Brute-force cosine over stored image vectors (wave 3, R1). Pure: no Android and no DB; the pages come from a lambda so the scan can sit
 * behind a future ANN index. Vectors are unit length, so cosine is the dot product.
 */
object ImageScan {
    const val PAGE = 500
    const val MAX_SCAN = 50_000
    const val BUDGET_MS = 2_000L

    /** [hits] are best first (score desc, then frameId asc). [timedOut] = the time budget ended the scan; [truncated] = rows remained when it stopped. */
    data class ScanResult(val hits: List<Pair<String, Double>>, val scanned: Int, val truncated: Boolean, val timedOut: Boolean = false)

    /**
     * Scores rows from [pages] (called with the last rowid seen, newest first; the first call gets Long.MAX_VALUE) against [query] into a bounded
     * heap of [k]. Rows whose decoded size differs from the query are skipped. Stops at [maxScan] rows or when [nowMs] passes start + [budgetMs]
     * (`truncated = true`), and when a page is shorter than [PAGE].
     */
    suspend fun topK(
        query: FloatArray,
        k: Int,
        pages: suspend (beforeRowid: Long) -> List<Pair<Long, Pair<String, ByteArray>>>,
        nowMs: () -> Long,
        maxScan: Int = MAX_SCAN,
        budgetMs: Long = BUDGET_MS,
    ): ScanResult {
        if (k <= 0) return ScanResult(emptyList(), 0, false)
        val start = nowMs()
        // Head = the worst kept hit: lowest score, and among equals the greatest frameId.
        val heap = PriorityQueue<Pair<String, Double>>(minOf(k, 256) + 1) { a, b -> if (a.second != b.second) a.second.compareTo(b.second) else b.first.compareTo(a.first) }
        val buf = FloatArray(query.size)
        var before = Long.MAX_VALUE
        var scanned = 0
        var truncated = false
        var timedOut = false
        scan@ while (true) {
            if (nowMs() - start >= budgetMs) {
                // Out of time: one more page only tells whether rows were left, so a scan that had just finished is not reported as cut short.
                if (pages(before).isNotEmpty()) { truncated = true; timedOut = true }
                break
            }
            val page = pages(before)
            if (page.isEmpty()) break
            for ((rowid, row) in page) {
                if (scanned >= maxScan) { truncated = true; break@scan }
                scanned++
                before = rowid
                if (!VectorCodec.decodeInto(row.second, buf)) continue
                var dot = 0.0
                for (i in buf.indices) dot += (buf[i] * query[i]).toDouble()
                if (!dot.isFinite()) continue // a NaN row would outrank everything and never be evicted
                val cand = row.first to dot
                if (heap.size < k) heap.add(cand)
                else if (heap.peek().let { w -> cand.second > w.second || (cand.second == w.second && cand.first < w.first) }) { heap.poll(); heap.add(cand) }
            }
            if (page.size < PAGE) break
        }
        val hits = heap.toList().sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
        return ScanResult(hits, scanned, truncated, timedOut)
    }
}
