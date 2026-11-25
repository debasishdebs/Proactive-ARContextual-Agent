package com.rokid.context.canvas.window

import java.security.MessageDigest

/**
 * Canvas v2.1 P0 (design 2026-11-24, section 1.2): the deterministic frame picker of a stored-window Test run. PURE: no database, no clock, no Android. The caller (the route, flag `canvasTestRun`)
 * reads the frame rows of the window and hands them in as [PickerFrame]; the picked ids go to the existing `CanvasRunner.estimate/start`, whose 50-photo and 200-call caps stay. Same input, same ids.
 */
data class PickerFrame(
    val frameId: String,
    val capturedAtMs: Long,
    val dwell: Boolean,
    val nearDup: Boolean,
    /** A frame the Forget or retention already removed (the row may still be visible to a racing read). */
    val tombstoned: Boolean,
    val photoPresent: Boolean,
    /** True when retention v3 thinned the photo (reported apart from a Forget). */
    val photoThinnedByRetention: Boolean = false,
)

enum class PickMode(val token: String) {
    /** One frame per time bucket, chosen by a seeded hash. */
    EVEN("even"),
    /** Dwell frames first (thinned evenly when there are more than n), then an even fill. */
    DWELL("dwell"),
    /** Every dwell frame in time order up to n, no thinning, then an even fill. */
    ALL_DWELL_FIRST("all_dwell_first");

    companion object { fun parse(t: String?): PickMode? = entries.firstOrNull { it.token == t } }
}

data class PickResult(
    val ids: List<String>,
    /** Frames that were candidates (live, photo present, not near-dup unless asked). */
    val of: Int,
    val excludedTombstoned: Int,
    val excludedNoPhoto: Int,
    val excludedRetention: Int,
    val excludedNearDup: Int,
    val seed: String,
) {
    /** The plain line the estimate card shows. Fixed words and counts only. */
    val note: String get() = "picked ${ids.size} of $of"
}

object WindowFramePicker {
    const val MAX_PHOTOS = 50

    /** The default seed: the same window and pipeline always pick the same frames. */
    fun defaultSeed(fromMs: Long, toMs: Long, pipelineHash: String): String = sha("window:$fromMs:$toMs:$pipelineHash").take(16)

    fun pick(
        frames: List<PickerFrame>, fromMs: Long, toMs: Long, mode: PickMode, n: Int, seed: String, includeNearDup: Boolean = false,
    ): PickResult {
        require(toMs > fromMs) { "bad_window" }
        val cap = n.coerceIn(0, MAX_PHOTOS)
        val inWin = frames.filter { it.capturedAtMs in fromMs until toMs }.distinctBy { it.frameId }
        val tomb = inWin.count { it.tombstoned }
        val live = inWin.filter { !it.tombstoned }
        val noPhoto = live.count { !it.photoPresent && !it.photoThinnedByRetention }
        val thinned = live.count { !it.photoPresent && it.photoThinnedByRetention }
        val withPhoto = live.filter { it.photoPresent }
        val nearDup = if (includeNearDup) 0 else withPhoto.count { it.nearDup }
        val cands = (if (includeNearDup) withPhoto else withPhoto.filter { !it.nearDup }).sortedWith(compareBy({ it.capturedAtMs }, { it.frameId }))
        val chosen: List<PickerFrame> = if (cap == 0 || cands.isEmpty()) emptyList() else when (mode) {
            PickMode.EVEN -> even(cands, fromMs, toMs, cap, seed)
            PickMode.DWELL -> {
                val dwell = cands.filter { it.dwell }
                val first = if (dwell.size > cap) even(dwell, fromMs, toMs, cap, seed) else dwell
                first + fill(cands, first, fromMs, toMs, cap - first.size, seed)
            }
            PickMode.ALL_DWELL_FIRST -> {
                val first = cands.filter { it.dwell }.take(cap)
                first + fill(cands, first, fromMs, toMs, cap - first.size, seed)
            }
        }
        val ids = chosen.sortedWith(compareBy({ it.capturedAtMs }, { it.frameId })).map { it.frameId }
        return PickResult(ids, cands.size, tomb, noPhoto, thinned, nearDup, seed)
    }

    /** Time-stratified: [cap] buckets over the window, one frame per non-empty bucket by seeded hash; leftover quota goes to the lowest-hash unused frames. */
    private fun even(c: List<PickerFrame>, fromMs: Long, toMs: Long, cap: Int, seed: String): List<PickerFrame> {
        val k = minOf(cap, c.size)
        val span = toMs - fromMs
        val buckets = HashMap<Int, MutableList<PickerFrame>>()
        for (f in c) {
            val b = (((f.capturedAtMs - fromMs).toDouble() / span) * k).toInt().coerceIn(0, k - 1)
            buckets.getOrPut(b) { mutableListOf() }.add(f)
        }
        val out = ArrayList<PickerFrame>()
        for (b in buckets.keys.sorted()) {
            val list = buckets.getValue(b)
            out.add(list[(hashLong("$seed:$b") % list.size).toInt()])
        }
        if (out.size < k) out.addAll(fill(c, out, fromMs, toMs, k - out.size, seed))
        return out
    }

    private fun fill(c: List<PickerFrame>, already: List<PickerFrame>, fromMs: Long, toMs: Long, quota: Int, seed: String): List<PickerFrame> {
        if (quota <= 0) return emptyList()
        val used = already.map { it.frameId }.toSet()
        val rest = c.filter { it.frameId !in used }
        if (rest.size <= quota) return rest
        val evenly = even(rest, fromMs, toMs, quota, "$seed:fill")
        return evenly.take(quota)
    }

    private fun hashLong(s: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xff)
        return v and Long.MAX_VALUE
    }

    private fun sha(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
