package com.rokid.context.ai.canvas

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A five-field cron expression (`minute hour day-of-month month day-of-week`) for `trigger.schedule`: `*`, numbers, `a-b` ranges, `/step` and comma lists; weekday 0 or 7 is Sunday;
 * no names, no seconds. Like standard cron, when both day fields are restricted a day matches if either does. Local time of the phone's zone. Pure and deterministic.
 * The floor [MIN_INTERVAL_MIN] keeps a schedule from spamming model calls and matches WorkManager's own 15 minute period (the app checks schedules on that cadence).
 */
class CronSpec private constructor(
    private val minutes: Set<Int>, private val hours: Set<Int>, private val doms: Set<Int>, private val months: Set<Int>, private val dows: Set<Int>,
    private val domAny: Boolean, private val dowAny: Boolean,
) {
    private val times: List<Int> = hours.flatMap { h -> minutes.map { m -> h * 60 + m } }.sorted()

    private fun dayMatches(d: LocalDate): Boolean {
        if (d.monthValue !in months) return false
        val dom = d.dayOfMonth in doms
        val dow = d.dayOfWeek.value % 7 in dows
        return if (domAny || dowAny) dom && dow else dom || dow
    }

    /** The first occurrence strictly after [afterMs], or null when none falls in the next 366 days (e.g. 31 February). */
    fun nextAfter(afterMs: Long, zone: ZoneId): Long? {
        val start = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(afterMs), zone)
        for (i in 0..366) {
            val day = start.toLocalDate().plusDays(i.toLong())
            if (!dayMatches(day)) continue
            for (t in times) {
                val at = ZonedDateTime.of(day, LocalTime.of(t / 60, t % 60), zone).toInstant().toEpochMilli()
                if (at > afterMs) return at
            }
        }
        return null
    }

    /** The smallest gap in minutes between two occurrences (within a day, or across the midnight of two matching days); [Int.MAX_VALUE] when a day holds at most one and no two days are adjacent. */
    fun minIntervalMin(): Int {
        var min = Int.MAX_VALUE
        for (i in 1 until times.size) min = minOf(min, times[i] - times[i - 1])
        if (times.isEmpty()) return min
        val wrap = DAY_MIN - times.last() + times.first()
        val base = LocalDate.of(2025, 1, 1)
        val adjacent = (0..366).any { dayMatches(base.plusDays(it.toLong())) && dayMatches(base.plusDays(it + 1L)) }
        return if (adjacent) minOf(min, wrap) else min
    }

    companion object {
        const val MIN_INTERVAL_MIN = 15
        const val MAX_LEN = 64
        private const val DAY_MIN = 1440
        const val DEFAULT = "0 9 * * *"

        /** The expression, or null when it is not valid cron of this form or runs more often than [MIN_INTERVAL_MIN]. */
        fun parse(expr: String): CronSpec? = (parseOrReason(expr) as? Parsed.Ok)?.spec

        sealed interface Parsed { data class Ok(val spec: CronSpec) : Parsed; data class Bad(val reason: String) : Parsed }

        /** A fixed reason text (never the expression itself) or the spec. */
        fun parseOrReason(expr: String): Parsed {
            if (expr.length > MAX_LEN) return Parsed.Bad("cron: too long")
            val f = expr.trim().split(Regex("\\s+"))
            if (f.size != 5) return Parsed.Bad("cron: five fields (minute hour day month weekday)")
            val m = field(f[0], 0, 59) ?: return Parsed.Bad("cron: minute field is not valid")
            val h = field(f[1], 0, 23) ?: return Parsed.Bad("cron: hour field is not valid")
            val dm = field(f[2], 1, 31) ?: return Parsed.Bad("cron: day-of-month field is not valid")
            val mo = field(f[3], 1, 12) ?: return Parsed.Bad("cron: month field is not valid")
            val dw = field(f[4], 0, 7)?.map { it % 7 }?.toSet() ?: return Parsed.Bad("cron: weekday field is not valid")
            val spec = CronSpec(m, h, dm, mo, dw, f[2] == "*", f[4] == "*")
            if (spec.times.isEmpty()) return Parsed.Bad("cron: never runs")
            if (spec.minIntervalMin() < MIN_INTERVAL_MIN) return Parsed.Bad("cron: runs more often than every $MIN_INTERVAL_MIN minutes")
            return Parsed.Ok(spec)
        }

        private fun field(s: String, lo: Int, hi: Int): Set<Int>? {
            val out = sortedSetOf<Int>()
            for (part in s.split(',')) {
                val (range, stepText) = part.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
                val step = if (stepText == null) 1 else stepText.toIntOrNull()?.takeIf { it in 1..hi } ?: return null
                val (a, b) = when {
                    range == "*" -> lo to hi
                    '-' in range -> (range.substringBefore('-').toIntOrNull() ?: return null) to (range.substringAfter('-').toIntOrNull() ?: return null)
                    else -> (range.toIntOrNull() ?: return null).let { it to if (stepText != null) hi else it }
                }
                if (a < lo || b > hi || a > b) return null
                var v = a
                while (v <= b) { out += v; v += step }
            }
            return out.takeIf { it.isNotEmpty() }
        }
    }
}
