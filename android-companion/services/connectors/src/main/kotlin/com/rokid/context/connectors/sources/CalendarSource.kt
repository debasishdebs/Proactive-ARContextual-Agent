package com.rokid.context.connectors.sources

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import com.rokid.context.core.model.TextHygiene
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CalendarEvent(val title: String, val beginMs: Long, val endMs: Long, val location: String?)

/**
 * Calendar (R-64): `Instances` from now to now + 24 h, at most 5, sorted by begin:
 * `"event · <title> · <EEE HH:mm>–<HH:mm>[ · <location>]"`, title ≤ 80, location ≤ 60.
 */
class CalendarSource(private val zone: ZoneId, private val events: suspend (Long, Long) -> List<CalendarEvent>) : ContextSource {
    override val kind = SourceKind.CALENDAR
    override val permissions = listOf(android.Manifest.permission.READ_CALENDAR)

    override suspend fun lines(question: String?, nowMs: Long): List<String> {
        val to = nowMs + DAY_MS
        return events(nowMs, to).filter { it.endMs > nowMs && it.beginMs < to }.sortedBy { it.beginMs }.take(MAX).map(::line)
    }

    private fun line(e: CalendarEvent): String {
        val title = clean(e.title).take(MAX_TITLE)
        val start = DAY_TIME.withZone(zone).format(Instant.ofEpochMilli(e.beginMs))
        val end = TIME.withZone(zone).format(Instant.ofEpochMilli(e.endMs))
        val loc = e.location?.let(::clean)?.take(MAX_LOCATION)?.takeIf { it.isNotEmpty() }
        return "event \u00b7 $title \u00b7 $start\u2013$end" + (loc?.let { " \u00b7 $it" } ?: "")
    }

    private fun clean(s: String) = s.replace(TextHygiene.SEPARATORS, " ").trim()

    companion object {
        private const val DAY_MS = 24 * 3_600_000L
        private const val MAX = 5
        private const val MAX_TITLE = 80
        private const val MAX_LOCATION = 60
        private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

        fun resolver(cr: ContentResolver): suspend (Long, Long) -> List<CalendarEvent> = { from, to ->
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
            val cols = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION)
            cr.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                val out = mutableListOf<CalendarEvent>()
                while (c.moveToNext() && out.size < 50) out += CalendarEvent(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getString(3))
                out
            }.orEmpty()
        }
    }
}
