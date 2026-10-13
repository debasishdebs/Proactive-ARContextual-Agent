package com.rokid.context.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Quiet-hours occurrence math in a zone (phone clock instants). The one implementation: the phone pause set (HubPauses)
 * and the nudge quiet-hours gate (NudgeGates) both use it, so they agree across DST changes.
 */
object QuietWindow {
    /**
     * The occurrence containing [nowMs], as `start until end` (end exclusive), or null. The local start/end minutes are
     * placed on the right dates; a wrap past midnight ends the next day. A local time in a DST gap moves forward to the
     * first valid instant; in an overlap the earlier offset is used. `start == end` is no window.
     */
    fun occurrenceAt(q: QuietHours, nowMs: Long, zone: ZoneId): LongRange? {
        if (q.startMinute == q.endMinute || q.startMinute !in 0..1439 || q.endMinute !in 0..1439) return null
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        for (startDay in listOf(today.minusDays(1), today)) {
            val endDay = if (q.startMinute < q.endMinute) startDay else startDay.plusDays(1)
            val start = instant(startDay, q.startMinute, zone)
            val end = instant(endDay, q.endMinute, zone)
            if (end > start && nowMs in start until end) return start until end
        }
        return null
    }

    private fun instant(day: LocalDate, minute: Int, zone: ZoneId): Long {
        val local = LocalDateTime.of(day, LocalTime.of(minute / 60 % 24, minute % 60))
        val transition = zone.rules.getTransition(local)
        // Gap: the first valid instant after it (the transition), not ZonedDateTime's shift by the gap length.
        if (transition != null && transition.isGap) return transition.instant.toEpochMilli()
        return ZonedDateTime.of(local, zone).toInstant().toEpochMilli()
    }
}
