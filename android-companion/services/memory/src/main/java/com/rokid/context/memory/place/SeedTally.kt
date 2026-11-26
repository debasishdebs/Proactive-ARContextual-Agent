package com.rokid.context.memory.place

/**
 * The one definition of seeding progress numbers (screens, notification and logs all use it). Counts only, never which cell or where.
 *
 * - total = cells of the area; processed = ok + failed (a cell is processed once it has an outcome); waiting = total - processed.
 * - OVERALL % = processed / total. OK % and FAILED % are shares of the PROCESSED cells (not of the total), and always add up to 100.
 * - Classification: a cell stored with complete=false because Overpass failed (a PARTIAL cell) counts as OK (the Wikipedia and Wikidata text is there; owner ruling 27 Nov: it is DONE for seeding,
 *   not refetched for 30 days, and does not keep the area FAILED) and is ALSO reported on its own as "without OSM extras" (a subset of ok, never added on top). A cell that was already fresh counts as ok. A cell that stored nothing after its tries is failed; a retry that stores it moves failed -> ok.
 */
object SeedTally {
    fun donePct(processed: Int, total: Int): Int = if (total <= 0) 0 else (processed.toLong().coerceIn(0, total.toLong()) * 100 / total).toInt()
    fun okPct(ok: Int, failed: Int): Int = if (ok + failed <= 0) 0 else ((ok.toLong() * 100 + (ok + failed) / 2) / (ok + failed)).toInt().coerceIn(0, 100)
    fun failedPct(ok: Int, failed: Int): Int = if (ok + failed <= 0) 0 else 100 - okPct(ok, failed)

    /** "25% done (525 of 2100) · 38% ok (200) · 62% failed (325) · 12 without OSM extras"; the ok/failed parts appear once a cell is processed, the last part only when above 0. */
    fun describe(processed: Int, total: Int, failed: Int, partial: Int): String {
        val ok = (processed - failed).coerceAtLeast(0)
        val sb = StringBuilder("${donePct(processed, total)}% done ($processed of $total)")
        if (processed > 0) sb.append(" · ${okPct(ok, failed)}% ok ($ok) · ${failedPct(ok, failed)}% failed ($failed)")
        if (partial > 0) sb.append(" · $partial without OSM extras")
        return sb.toString()
    }

    /** The line of a finished area: "12 cells without OSM extras", or null when none. */
    fun noOsmLine(partial: Int): String? = if (partial <= 0) null else "$partial cell${if (partial == 1) "" else "s"} without OSM extras"
}
