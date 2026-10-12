package com.rokid.context.memory

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Local-day buckets computed at query time in the device's current zone (Review Focus #4). */
object DayBucket {
    /** [first] = start of [date] in [zone]; [last] = start of the next day − 1 ms. DST-safe. */
    fun bounds(date: LocalDate, zone: ZoneId): LongRange {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start until next
    }

    fun dayOf(epochMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
}
