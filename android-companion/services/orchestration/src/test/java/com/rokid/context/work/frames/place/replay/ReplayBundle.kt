package com.rokid.context.work.frames.place.replay

import java.io.File

/** One frame_meta row of the bundle (times are epoch ms UTC). */
data class ReplayFrame(
    val frameId: String, val createdAt: Long, val capturedAt: Long, val dwell: Boolean, val motion: String, val sessionId: String,
    /** -1 = never tombstoned; otherwise the frame is invisible from this time on. */
    val tombAt: Long, val photo: Boolean, val nearDup: Boolean, val text: String,
)

data class ReplayFix(val tMs: Long, val lat: Double, val lon: Double, val src: String, val file: String)

data class ReplayPin(val id: String, val name: String, val kind: String, val lat: Double, val lon: Double, val radiusM: Int, val parentId: String?, val activeFrom: Long, val activeSrc: String)

/** An ASSUMED pack site (never a measured position): used for calibration only, never for backfill. */
data class ReplaySite(val id: String, val name: String, val kind: String, val lat: Double, val lon: Double, val radiusM: Int, val src: String)

data class TruthSpan(val label: String, val target: String, val startMs: Long, val endMs: Long, val source: String, val testable: String) {
    val isTestable get() = testable == "yes"
    val assumedFixOnly get() = testable.startsWith("assumed")
    /** The target place name ("SITE:" prefix removed), or null for "-". */
    val targetName: String? get() = target.removePrefix("SITE:").takeIf { it != "-" && it.isNotBlank() }
    operator fun contains(t: Long) = t in startMs..endMs
}

data class HistoricalMatch(val frameId: String, val capturedAt: Long, val name: String, val conf: Float)

/** The offline replay bundle: plain TSV files (see tools/replay/build_bundle.py). Only *.tsv are ever read: no photo, no database, no key file. */
class ReplayBundle(
    val frames: List<ReplayFrame>, val fixes: List<ReplayFix>, val pins: List<ReplayPin>, val historical: List<HistoricalMatch>,
    val truth: List<TruthSpan>, val sites: List<ReplaySite>,
) {
    val framesById: Map<String, ReplayFrame> by lazy { frames.associateBy { it.frameId } }
    fun spanAt(t: Long): TruthSpan? = truth.firstOrNull { t in it }
    fun copy(frames: List<ReplayFrame> = this.frames, fixes: List<ReplayFix> = this.fixes, pins: List<ReplayPin> = this.pins, historical: List<HistoricalMatch> = this.historical, truth: List<TruthSpan> = this.truth, sites: List<ReplaySite> = this.sites) =
        ReplayBundle(frames, fixes, pins, historical, truth, sites)

    companion object {
        /** The only files the loader opens. */
        val FILES = listOf("frames.tsv", "fixes.tsv", "pins.tsv", "historical.tsv", "truth.tsv", "sites.tsv")

        private fun rows(dir: File, name: String, cols: Int): List<List<String>> {
            require(name in FILES)
            val f = File(dir, name)
            if (!f.isFile) return emptyList()
            return f.readLines().drop(1).filter { it.isNotBlank() }.map { l -> l.split("\t", limit = cols).let { p -> p + List((cols - p.size).coerceAtLeast(0)) { "" } } }
        }

        fun load(dir: File): ReplayBundle = ReplayBundle(
            frames = rows(dir, "frames.tsv", 10).map { ReplayFrame(it[0], it[1].toLong(), it[2].toLong(), it[3] == "1", it[4], it[5], it[6].toLong(), it[7] == "1", it[8] == "1", it[9]) }.sortedWith(compareBy({ it.createdAt }, { it.frameId })),
            fixes = rows(dir, "fixes.tsv", 5).map { ReplayFix(it[0].toLong(), it[1].toDouble(), it[2].toDouble(), it[3], it[4]) }.sortedBy { it.tMs },
            pins = rows(dir, "pins.tsv", 9).map { ReplayPin(it[0], it[1], it[2], it[3].toDouble(), it[4].toDouble(), it[5].toInt(), it[6].takeIf { p -> p != "-" && p.isNotBlank() }, it[7].toLong(), it[8]) },
            historical = rows(dir, "historical.tsv", 4).map { HistoricalMatch(it[0], it[1].toLong(), it[2], it[3].toFloat()) },
            truth = rows(dir, "truth.tsv", 6).map { TruthSpan(it[0], it[1], it[2].toLong(), it[3].toLong(), it[4], it[5]) },
            sites = rows(dir, "sites.tsv", 7).map { ReplaySite(it[0], it[1], it[2], it[3].toDouble(), it[4].toDouble(), it[5].toInt(), it[6]) },
        )
    }
}
