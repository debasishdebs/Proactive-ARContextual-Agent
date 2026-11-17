package com.rokid.context.orchestration.proactive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Card text is a template over stored facts: no model writes it (design 2.2, ruling 7). It states what was SEEN and WHEN; it never names a person and never claims a fact about the world. */
object Templates {
    const val MAX_TEXT = 200
    private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun date(ms: Long, zone: ZoneId): String = DAY.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun placeRecall(f: PlaceFacts, zone: ZoneId): String {
        val sb = StringBuilder(oneLine(f.placeName)).append(": last visit ").append(date(f.lastVisitMs, zone)).append('.')
        if (f.earlierVisits > 1) sb.append(' ').append(f.earlierVisits).append(" earlier visits.")
        f.note?.let { oneLine(it) }?.takeIf { it.isNotEmpty() }?.let { sb.append(" Your note: “").append(it).append('”') }
        if (f.markCount > 0) sb.append(if (f.note.isNullOrBlank()) " " else " ").append(f.markCount).append(if (f.markCount == 1) " mark from then." else " marks from then.")
        return sb.toString().take(MAX_TEXT)
    }

    /** "from 2 memories · 12 Nov" (the HUD / feed source label). */
    fun sourceLabel(sourceCount: Int, lastMs: Long, zone: ZoneId): String = "from $sourceCount ${if (sourceCount == 1) "item" else "items"} · ${date(lastMs, zone)}"

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
