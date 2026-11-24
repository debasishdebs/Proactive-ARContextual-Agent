package com.rokid.context.phone.ui.list

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A date and time range chosen by the user, in the phone's local time, to the minute. Both ends are included: the end covers the whole last minute. */
data class DateTimeRange(val start: LocalDateTime, val end: LocalDateTime)

/** The pure maths behind the list's date and time filter: building a range, turning it into epoch milliseconds, narrowing a period, saving it, and naming it. */
object RangeFilter {
    /** A range no row can be in (what a period that does not meet the filter reads). */
    val NONE: LongRange = 0L..-1L

    private val SHOWN = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
    private val CLOCK = Regex("([01]?\\d|2[0-3]):([0-5]\\d)")

    /** The range from [start] to [end], cut to the minute; null when [start] is after [end] (a range that cannot hold anything is refused). */
    fun of(start: LocalDateTime, end: LocalDateTime): DateTimeRange? {
        val s = start.withSecond(0).withNano(0); val e = end.withSecond(0).withNano(0)
        return if (s.isAfter(e)) null else DateTimeRange(s, e)
    }

    /** [day] at [time] as a local date and time. */
    fun at(day: LocalDate, time: LocalTime): LocalDateTime = day.atTime(time)

    /** The epoch milliseconds the range covers in [zone], both ends included: from the start minute to the last millisecond of the end minute. */
    fun millis(r: DateTimeRange, zone: ZoneId): LongRange =
        r.start.atZone(zone).toInstant().toEpochMilli()..(r.end.plusMinutes(1).atZone(zone).toInstant().toEpochMilli() - 1)

    /** [period] narrowed to [filter] (null filter keeps it whole); [NONE] when they do not meet. */
    fun narrow(period: LongRange, filter: LongRange?): LongRange {
        if (filter == null) return period
        val lo = maxOf(period.first, filter.first); val hi = minOf(period.last, filter.last)
        return if (lo > hi) NONE else lo..hi
    }

    /** Reads "H:mm" or "HH:mm" (24 hour); null for anything else. */
    fun parseTime(text: String): LocalTime? {
        val m = CLOCK.matchEntire(text.trim()) ?: return null
        return LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    fun formatTime(t: LocalTime): String = "%02d:%02d".format(Locale.ENGLISH, t.hour, t.minute)

    /** "5 Nov 09:00 to 7 Nov 17:30" (plain words, no symbols). */
    fun label(r: DateTimeRange): String = "${SHOWN.format(r.start)} to ${SHOWN.format(r.end)}"

    /** The small string kept in the saved state. */
    fun encode(r: DateTimeRange): String = "${r.start}|${r.end}"

    /** Reads [encode]'s string back; null when it is missing or damaged. */
    fun decode(raw: String?): DateTimeRange? {
        val f = raw?.split('|') ?: return null
        if (f.size != 2) return null
        val s = runCatching { LocalDateTime.parse(f[0]) }.getOrNull() ?: return null
        val e = runCatching { LocalDateTime.parse(f[1]) }.getOrNull() ?: return null
        return of(s, e)
    }
}
