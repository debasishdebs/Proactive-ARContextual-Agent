package com.rokid.context.phone.ui.photos

import com.rokid.context.work.frames.recall.DescribeResult
import com.rokid.context.work.frames.recall.ImageSearchState
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoSource
import com.rokid.context.work.frames.recall.RecallResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One grid tile. [path] is null when the photo is not stored ([expired]: metadata only). [timeLabel] is the capture time (the stored `frame.ts`, or
 * `frame_meta.capturedAtMs` once expired); an expired photo without a capture time says "received HH:mm" (user ruling 2026-10-22 over S9).
 * [date] (yyyy-MM-dd) opens the frame viewer; [captionMemoryId] is the memory "Open" goes to when the photo is gone (S19).
 */
data class PhotoTile(
    val frameId: String, val path: String?, val timeLabel: String, val score: String, val badge: String /* "Image" | "Caption" */,
    val described: Boolean, val expired: Boolean, val caption: String?, val clusterId: String?, val date: String,
    val captionMemoryId: String? = null,
)

data class PhotoSearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val tiles: List<PhotoTile> = emptyList(),
    val banner: String? = null,
    val note: String? = null,
    val describing: Boolean = false,
    val status: String? = null,
)

object PhotoSearchModel {
    const val CAPTIONS_ONLY = "Searching captions only. For photo search, turn on Nomic in Settings → AI → On-device models."
    const val SEARCH_FAILED = "Couldn't search \u2014 try again"
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY_CLOCK = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.ENGLISH)

    /** Image hits first, then caption hits (never merged, R2). Scores print as `%.2f` in Locale.US. */
    fun tiles(r: RecallResult, paths: Map<String, String>, zone: ZoneId, today: LocalDate): List<PhotoTile> =
        (r.image + r.captions).map { h -> tile(h, paths[h.frameId], zone, today) }

    private fun tile(h: PhotoHit, path: String?, zone: ZoneId, today: LocalDate): PhotoTile {
        val at = h.ts ?: h.receivedAt
        val local = at?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val clock = local?.let { if (it.toLocalDate() == today) CLOCK.format(it) else DAY_CLOCK.format(it) }.orEmpty()
        val label = if (h.ts == null && at != null) "received $clock" else clock
        return PhotoTile(
            frameId = h.frameId, path = path.takeIf { h.photoStored }, timeLabel = label, score = "%.2f".format(Locale.US, h.score),
            badge = if (h.source == PhotoSource.IMAGE) "Image" else "Caption", described = h.described, expired = !h.photoStored,
            caption = h.captionText?.trim()?.takeIf { it.isNotEmpty() }, clusterId = h.clusterId, date = local?.toLocalDate()?.toString().orEmpty(),
            captionMemoryId = h.captionMemoryId,
        )
    }

    /** No usable image space (not set up, or the scan could not run): the caption fallback is all there is. A timeout keeps its partial hits and says so in [note]. */
    fun banner(r: RecallResult): String? =
        if (r.imageState == ImageSearchState.NO_IMAGE_SPACE || r.imageState == ImageSearchState.FAILED) CAPTIONS_ONLY else null

    fun note(r: RecallResult): String? {
        val cut = if (r.truncated || r.imageState == ImageSearchState.TIMED_OUT) "Searched the ${r.scanned} most recently indexed photos" else null
        return if (r.image.isEmpty() && r.captions.isEmpty()) listOfNotNull("No photos match", cut).joinToString(" · ") else cut
    }

    fun status(d: DescribeResult): String = when (d) {
        is DescribeResult.Saved -> "Described · ${d.memories} ${if (d.memories == 1) "memory" else "memories"} saved"
        DescribeResult.Expired -> "Photo expired"
        DescribeResult.Busy -> "Already being described — check back in a minute"
        DescribeResult.Unavailable -> "AI unavailable — try again later"
        DescribeResult.Wiped -> "Memory was cleared"
    }
}
