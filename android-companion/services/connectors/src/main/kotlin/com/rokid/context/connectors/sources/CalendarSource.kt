package com.rokid.context.connectors.sources

import android.content.ContentResolver
import android.content.ContentUris
import android.provider.CalendarContract
import com.rokid.context.core.model.TextHygiene
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** [allDay]: `Instances.ALL_DAY`; then [beginMs]/[endMs] are UTC midnights (end exclusive), as CalendarContract defines them. */
data class CalendarEvent(val title: String, val beginMs: Long, val endMs: Long, val location: String?, val allDay: Boolean = false)

/**
 * Calendar (R-64): `Instances` from now to now + 24 h, at most 5, sorted by begin:
 * `"event · <title> · <EEE HH:mm>–<HH:mm>[ · <location>]"`, title ≤ 80, location ≤ 60.
 * BUG-R2-3: an all-day event is a span of UTC dates, `"event . <title> . all day <EEE d MMM>[-<EEE d MMM>]"` (the same separators), kept when
 * one of its dates is a local date of the window. Ruling: while a timed event remains today, all-day events follow
 * every timed one (so none reads as the "next meeting"); when none remains, today's all-day events lead.
 */
class CalendarSource(private val zone: ZoneId, private val events: suspend (Long, Long) -> List<CalendarEvent>) : ContextSource {
    override val kind = SourceKind.CALENDAR
    override val permissions = SourcePermissions.of(SourceKind.CALENDAR)

    override suspend fun lines(question: String?, nowMs: Long): List<String> {
        val to = nowMs + DAY_MS
        // A day of slack each side: an all-day event's UTC span can sit outside [now, to) in a far-off zone.
        val all = events(nowMs - DAY_MS, to + DAY_MS)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        val timed = all.filter { !it.allDay && it.endMs > nowMs && it.beginMs < to }.sortedBy { it.beginMs }
        val allDay = all.filter { it.allDay && it.firstDay() <= lastDay && it.endDayExclusive() > today }.sortedBy { it.beginMs }
        val timedLeftToday = timed.any { Instant.ofEpochMilli(it.beginMs).atZone(zone).toLocalDate() <= today }
        val (todayAllDay, laterAllDay) = allDay.partition { it.firstDay() <= today }
        val ordered = if (timedLeftToday) timed + allDay else todayAllDay + timed + laterAllDay
        return ordered.take(MAX).map(::line)
    }

    private fun CalendarEvent.firstDay(): LocalDate = Instant.ofEpochMilli(beginMs).atZone(ZoneOffset.UTC).toLocalDate()
    private fun CalendarEvent.endDayExclusive(): LocalDate =
        Instant.ofEpochMilli(endMs).atZone(ZoneOffset.UTC).toLocalDate().coerceAtLeast(firstDay().plusDays(1))

    private fun line(e: CalendarEvent): String {
        if (e.allDay) return allDayLine(e)
        val title = clean(e.title).take(MAX_TITLE)
        val start = DAY_TIME.withZone(zone).format(Instant.ofEpochMilli(e.beginMs))
        val end = TIME.withZone(zone).format(Instant.ofEpochMilli(e.endMs))
        val loc = e.location?.let(::clean)?.take(MAX_LOCATION)?.takeIf { it.isNotEmpty() }
        return "event \u00b7 $title \u00b7 $start\u2013$end" + (loc?.let { " \u00b7 $it" } ?: "")
    }

    private fun allDayLine(e: CalendarEvent): String {
        val title = clean(e.title).take(MAX_TITLE)
        val first = e.firstDay()
        val last = e.endDayExclusive().minusDays(1)
        val span = DATE.format(first) + if (last > first) "\u2013" + DATE.format(last) else ""
        val loc = e.location?.let(::clean)?.take(MAX_LOCATION)?.takeIf { it.isNotEmpty() }
        return "event \u00b7 $title \u00b7 all day $span" + (loc?.let { " \u00b7 $it" } ?: "")
    }

    private fun clean(s: String) = s.replace(TextHygiene.SEPARATORS, " ").trim()

    companion object {
        private const val DAY_MS = 24 * 3_600_000L
        private const val MAX = 5
        private const val MAX_TITLE = 80
        private const val MAX_LOCATION = 60
        private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

        fun resolver(cr: ContentResolver): suspend (Long, Long) -> List<CalendarEvent> = { from, to ->
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
            val cols = arrayOf(
                CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY,
            )
            cr.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                val out = mutableListOf<CalendarEvent>()
                while (c.moveToNext() && out.size < 50) out += CalendarEvent(c.getString(0).orEmpty(), c.getLong(1), c.getLong(2), c.getString(3), allDay = c.getInt(4) == 1)
                out
            }.orEmpty()
        }
    }
}
