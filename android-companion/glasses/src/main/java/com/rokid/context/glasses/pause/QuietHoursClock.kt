package com.rokid.context.glasses.pause

import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.QuietHours
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Quiet hours on the glasses, offline (spec §5.5): the last phone settings ([settings], persisted by HudSettingsCache)
 * evaluated in the phone-sent `zoneId` (system zone if absent or unknown). Same occurrence math as the phone's QuietWindow.
 */
class QuietHoursClock(private val settings: () -> HudSettings?) {
    /** The occurrence containing [nowMs] as `start until end` (end exclusive), or null. */
    fun occurrenceAt(nowMs: Long): LongRange? {
        val s = settings() ?: return null
        val q = s.quietHours ?: return null
        val zone = s.zoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        return occurrenceAt(q, nowMs, zone)
    }

    companion object {
        /**
         * Local start/end minutes on the right dates; a wrap past midnight ends the next day; a time in a DST gap moves
         * to the first valid instant; an overlap takes the earlier offset; `start == end` is no window.
         */
        fun occurrenceAt(q: QuietHours, nowMs: Long, zone: ZoneId): LongRange? {
            if (q.startMinute == q.endMinute || q.startMinute !in 0..1439 || q.endMinute !in 0..1439) return null
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
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
            if (transition != null && transition.isGap) return transition.instant.toEpochMilli()
            return ZonedDateTime.of(local, zone).toInstant().toEpochMilli()
        }
    }
}
