package com.rokid.context.canvas.window

import com.rokid.context.memory.db.CanvasRunItemEntity

/**
 * Canvas v2.1 P2 (design section 1.4): the row model behind the per-node inspector. PURE over the `canvas_run_item` rows a run already holds: nothing new is stored, so every frame Forget, mark undo and Delete all
 * that reaches those rows reaches this view too. The browser overlay (`inspectview.js`, `?run=<id>`) only draws what these functions return (device check pending: the WebView layout).
 */
object NodeInspector {
    const val OUTPUT_CAP_BYTES = 4096
    const val DEFAULT_PAGE = 50
    const val MAX_PAGE = 200

    data class Badge(val nodeId: String, val ok: Int, val error: Int, val skipped: Int, val branches: Map<String, Int>, val medianLatencyMs: Long?)

    /**
     * One badge per node of [nodeIds]. A frame without a row for a node counts as skipped (a node never writes a row for an item that an upstream branch or error dropped). [frameCount] is the number of frames of the run.
     */
    fun badges(items: List<CanvasRunItemEntity>, nodeIds: List<String>, frameCount: Int): List<Badge> {
        val byNode = items.groupBy { it.nodeId }
        return nodeIds.map { id ->
            val rows = byNode[id].orEmpty()
            val ok = rows.count { it.status == "OK" }
            val err = rows.count { it.status != "OK" }
            val branches = rows.mapNotNull { it.branch }.groupingBy { it }.eachCount().toSortedMap()
            Badge(id, ok, err, (frameCount - rows.map { it.frameId }.toSet().size).coerceAtLeast(0), branches, median(rows.mapNotNull { it.latencyMs }))
        }
    }

    data class Row(
        val frameId: String, val status: String, val branch: String?, val output: String?, val outputTruncated: Boolean, val error: String?,
        val model: String?, val promptTokens: Int?, val completionTokens: Int?, val latencyMs: Long?, val imageDelivered: Boolean?,
        /** The upstream node's output for the same frame (the incoming edge's value), truncated the same way; null when the node has no upstream row. */
        val input: String?,
    )

    data class Page(val rows: List<Row>, /** Pass back as `after` for the next page; null = the last page. */ val next: String?)

    /** Paged rows of one node, ordered by frame id. [upstream] is the node id whose rows feed [node] (null for a root). */
    fun page(items: List<CanvasRunItemEntity>, node: String, upstream: String?, after: String?, limit: Int): Page {
        val n = limit.coerceIn(1, MAX_PAGE)
        val mine = items.filter { it.nodeId == node }.sortedBy { it.frameId }.filter { after == null || it.frameId > after }
        val up = if (upstream == null) emptyMap() else items.filter { it.nodeId == upstream }.associateBy { it.frameId }
        val slice = mine.take(n)
        val rows = slice.map { r ->
            val (out, cut) = cap(r.outputJson)
            Row(r.frameId, r.status, r.branch, out, cut, r.error, r.model, r.promptTokens, r.completionTokens, r.latencyMs, r.imageDelivered, cap(up[r.frameId]?.outputJson).first)
        }
        return Page(rows, if (mine.size > n) slice.last().frameId else null)
    }

    /** Cuts at [OUTPUT_CAP_BYTES] UTF-8 bytes without splitting a character. */
    fun cap(s: String?): Pair<String?, Boolean> {
        if (s == null) return null to false
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= OUTPUT_CAP_BYTES) return s to false
        var end = OUTPUT_CAP_BYTES
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) to true
    }

    private fun median(v: List<Long>): Long? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
