package com.rokid.context.orchestration.retrieval

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.regex.Pattern

/**
 * A half-open event-time window `[fromMs, toMs)` an Ask question named (retrieval-quality spec section 3). [label] is the human range
 * ("Tue 10 Nov 2026 00:00-23:59"), [phrase] the words of the question that gave it (built only from the fixed grammar below: digits, month and weekday
 * names, a few keywords, so it is safe to show to the model). [nightly]: an overnight window (tonight, last night, night of ...), for the later
 * night-extent ordering rule. [source]: who resolved it (the LLM fallback of spec section 3 arrives later).
 */
data class TimeWindow(
    val fromMs: Long, val toMs: Long, val label: String, val phrase: String,
    val source: Source = Source.RULES, val nightly: Boolean = false,
) {
    enum class Source { RULES, LLM }
}

/** Who turns a question into a [TimeWindow]: rules now, the optional LLM fallback (spec section 3) later. A seam for the Ask path. */
fun interface WindowResolver {
    suspend fun resolve(question: String, nowMs: Long, zone: ZoneId): TimeWindow?

    companion object {
        /** The rules only: pure, local, no I/O. */
        val Rules = WindowResolver { q, now, zone -> TimeWindowRules.parse(q, now, zone) }
        val None = WindowResolver { _, _, _ -> null }
    }
}

/**
 * Retrieval-quality spec section 3: the date and time words of a question as a [TimeWindow], by rules only (pure Kotlin, no I/O, the ask's local zone).
 * Every day is `[start of day, next start of day)` through [ZonedDateTime], so a DST day is 23 or 25 hours. Every window is clipped to now and a window
 * that is empty after the clip is no window. "last" is a window only before night/week/month/a weekday/a count; "when did I last", "the last time" and
 * "latest" are ordering words (canvas ordering, a later package) and never match here. A future date is never a window; numeric dates (10/11) are not parsed.
 */
object TimeWindowRules {
    private const val NIGHT_FROM = 18
    private const val NIGHT_TO = 6
    private const val DAY_START_HOUR = 6 // before this hour "today" and "tonight" mean the night that is still going on

    private val MONTHS = mapOf(
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6, "july" to 7, "august" to 8, "september" to 9,
        "october" to 10, "november" to 11, "december" to 12, "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "jun" to 6, "jul" to 7, "aug" to 8,
        "sept" to 9, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private val NUMBER_WORDS = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "twelve" to 12)

    private val MONTH_RX = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private val DAY_RX = "(?<![:\\d])(\\d{1,2})(?:st|nd|rd|th)?"
    private val WEEKDAY_FULL = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private val WEEKDAY_ANY = WEEKDAYS.keys.sortedByDescending { it.length }.joinToString("|")
    private val NUM_RX = "\\d{1,3}|" + NUMBER_WORDS.keys.joinToString("|")

    private fun rx(s: String) = Pattern.compile(s, Pattern.CASE_INSENSITIVE)
    private val NIGHT_OF = rx("\\bnight of $DAY_RX(?:\\s*(?:-|–|—|to|/|and)\\s*(\\d{1,2})(?:st|nd|rd|th)?)?\\s+(?:of\\s+)?($MONTH_RX)\\b")
    private val DATE_DM = rx("(?:\\bon\\s+)?(?:\\bthe\\s+)?$DAY_RX\\s+(?:of\\s+)?($MONTH_RX)\\b(?:,?\\s+(\\d{4})\\b)?")
    private val DATE_MD = rx("(?:\\bon\\s+)?\\b($MONTH_RX)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b(?:,?\\s+(\\d{4})\\b)?")
    private val WEEKDAY = rx("\\b(?:(on|last|this|past)\\s+)?($WEEKDAY_ANY)\\b")
    private val LAST_NIGHT = rx("\\blast night\\b")
    private val TONIGHT = rx("\\btonight\\b")
    private val THIS_PART = rx("\\bthis (morning|afternoon|evening)\\b")
    private val YESTERDAY = rx("\\byesterday\\b")
    private val TODAY = rx("\\btoday\\b")
    private val DAYS_AGO = rx("\\b($NUM_RX)\\s+days?\\s+ago\\b")
    private val LAST_N = rx("\\b(?:in|over|during|within|for)?\\s*the\\s+(?:last|past)\\s+($NUM_RX)\\s+(hour|day|week)s?\\b")
    private val THIS_WEEK = rx("\\bthis week\\b")
    private val LAST_WEEK = rx("\\blast week\\b")
    private val THIS_MONTH = rx("\\bthis month\\b")
    private val LAST_MONTH = rx("\\blast month\\b")
    private val PART = rx("\\b(morning|afternoon|evening)\\b")
    private val CLOCK = rx("(?:\\b(around|at|about|near|before|after)\\s+)?(?:\\b(\\d{1,2}):(\\d{2})(?:\\s*([ap]m))?\\b|\\b(\\d{1,2})\\s*([ap]m)\\b|\\b(noon|midnight)\\b)")

    private val LABEL_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)
    private val LABEL_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** A resolved base: a calendar day ([date] set, so a part of the day or a clock time can narrow it) or any other range. */
    private class Base(val phrase: String, val from: ZonedDateTime, val to: ZonedDateTime, val date: LocalDate? = null, val nightly: Boolean = false, val relative: Boolean = false)

    private enum class ClockMode { AROUND, BEFORE, AFTER }
    private class Clock(val phrase: String, val time: LocalTime, val mode: ClockMode)

    fun parse(question: String, nowMs: Long, zone: ZoneId): TimeWindow? {
        if (question.isBlank()) return null
        val text = question.lowercase(Locale.ROOT).replace(Regex("a\\.m\\.?"), "am").replace(Regex("p\\.m\\.?"), "pm").replace(Regex("\\s+"), " ")
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        val clock = clockOf(text)
        val part = PART.matcher(text).let { if (it.find()) it.group(1) else null }

        val base = baseOf(text, now, zone)
        val w: Triple<ZonedDateTime, ZonedDateTime, String>? = when {
            // A clock time narrows a calendar day (named, or today) and stands alone when nothing else is named.
            clock != null && (base == null || base.date != null) -> {
                val date = base?.date ?: now.toLocalDate()
                var r = clockWindow(clock, date, zone)
                if (base == null && r.first.toInstant().toEpochMilli() > nowMs) r = clockWindow(clock, date.minusDays(1), zone) // that time is still ahead: it was yesterday's
                Triple(r.first, r.second, listOfNotNull(base?.phrase, clock.phrase).joinToString(" "))
            }
            base == null -> null
            part != null && base.date != null -> {
                var d = base.date
                var r = partWindow(part, d, zone)
                if (r.first.toInstant().toEpochMilli() > nowMs && base.relative && now.hour < DAY_START_HOUR) { d = d.minusDays(1); r = partWindow(part, d, zone) } // "this morning" at 01:22
                Triple(r.first, r.second, if (base.phrase.endsWith(part)) base.phrase else base.phrase + " " + part)
            }
            else -> Triple(base.from, base.to, base.phrase)
        }
        if (w == null) return null
        val nightly = base?.nightly == true && clock == null
        return finish(w.first, w.second, w.third, nowMs, zone, nightly)
    }

    /** [from, to) clipped to now; an empty window is no window. */
    private fun finish(from: ZonedDateTime, to: ZonedDateTime, phrase: String, nowMs: Long, zone: ZoneId, nightly: Boolean): TimeWindow? {
        val f = from.toInstant().toEpochMilli()
        val t = minOf(to.toInstant().toEpochMilli(), nowMs)
        if (f >= t) return null
        return TimeWindow(f, t, label(from, java.time.Instant.ofEpochMilli(t).atZone(zone), to.toInstant().toEpochMilli() == t), phrase.trim(), TimeWindow.Source.RULES, nightly)
    }

    /** One step wider (spec section 4, step 2): a day each side, clipped to now. Null when the window cannot grow (it already ends at now and starts at 0). */
    fun widened(w: TimeWindow, nowMs: Long, zone: ZoneId): TimeWindow? {
        val from = java.time.Instant.ofEpochMilli(w.fromMs).atZone(zone).minusDays(1)
        val toRaw = java.time.Instant.ofEpochMilli(w.toMs).atZone(zone).plusDays(1)
        val toMs = minOf(toRaw.toInstant().toEpochMilli(), nowMs)
        val fromMs = from.toInstant().toEpochMilli()
        if (fromMs >= toMs) return null
        return w.copy(fromMs = fromMs, toMs = toMs, label = label(from, java.time.Instant.ofEpochMilli(toMs).atZone(zone), toMs == toRaw.toInstant().toEpochMilli()), nightly = false)
    }

    /** `Tue 10 Nov 2026 00:00-23:59`, or `Sun 15 Nov 2026 18:00 - Mon 16 Nov 2026 06:00` across days. [boundary]: [to] is a calendar boundary (shown as the minute before). */
    private fun label(from: ZonedDateTime, to: ZonedDateTime, boundary: Boolean): String {
        val end = if (boundary) to.minusMinutes(1) else to
        val d0 = from.toLocalDate(); val d1 = end.toLocalDate()
        val a = LABEL_DAY.format(from) + " " + LABEL_HM.format(from)
        return if (d0 == d1) a + "-" + LABEL_HM.format(end) else a + " - " + LABEL_DAY.format(end) + " " + LABEL_HM.format(end)
    }

    private fun day(d: LocalDate, zone: ZoneId) = d.atStartOfDay(zone)

    private fun dayBase(phrase: String, d: LocalDate, zone: ZoneId, relative: Boolean = false) = Base(phrase, day(d, zone), day(d.plusDays(1), zone), d, relative = relative)

    private fun baseOf(text: String, now: ZonedDateTime, zone: ZoneId): Base? {
        val today = now.toLocalDate()
        // 1. night of 7-8 Nov / night of 7 Nov: that evening to the next morning.
        NIGHT_OF.matcher(text).let { m ->
            if (m.find()) latestDate(m.group(1).toInt(), MONTHS.getValue(m.group(3).lowercase(Locale.ROOT)), null, today)?.let { d ->
                return Base(m.group().trim(), day(d, zone).withHour(NIGHT_FROM), day(d.plusDays(1), zone).withHour(NIGHT_TO), nightly = true)
            }
        }
        // 2. an explicit date.
        for (p in listOf(DATE_DM, DATE_MD)) {
            val m = p.matcher(text)
            if (!m.find()) continue
            val dm = p === DATE_DM
            val dayN = (if (dm) m.group(1) else m.group(2)).toInt()
            val month = MONTHS.getValue((if (dm) m.group(2) else m.group(1)).lowercase(Locale.ROOT))
            val year = m.group(3)?.toInt()
            latestDate(dayN, month, year, today)?.let { return dayBase(m.group().trim(), it, zone) }
        }
        // 3. a weekday (the latest one that is not in the future; "last <weekday>" is strictly before today).
        WEEKDAY.matcher(text).let { m ->
            while (m.find()) {
                val word = m.group(2).lowercase(Locale.ROOT)
                val prefix = m.group(1)?.lowercase(Locale.ROOT)
                if (word !in WEEKDAY_FULL.split("|") && prefix != "on" && prefix != "last") continue // "sat", "sun", "mon" alone are not a day
                val dow = WEEKDAYS.getValue(word)
                var back = (today.dayOfWeek.value - dow.value + 7) % 7
                if (prefix == "last" && back == 0) back = 7
                return dayBase(m.group().trim(), today.minusDays(back.toLong()), zone)
            }
        }
        // 4. nights.
        LAST_NIGHT.matcher(text).let { m -> if (m.find()) return night(m.group(), now, zone) }
        TONIGHT.matcher(text).let { m -> if (m.find()) return night(m.group(), now, zone) }
        // 5. today, yesterday, this morning ...
        THIS_PART.matcher(text).let { m -> if (m.find()) return dayBase(m.group(), today, zone, relative = true) }
        YESTERDAY.matcher(text).let { m -> if (m.find()) return dayBase(m.group(), today.minusDays(1), zone) }
        TODAY.matcher(text).let { m ->
            if (m.find()) {
                // Before 06:00 the night that is still going on belongs to "today".
                val from = if (now.hour < DAY_START_HOUR) day(today.minusDays(1), zone) else day(today, zone)
                return Base(m.group(), from, now.plusSeconds(1), today, relative = true)
            }
        }
        DAYS_AGO.matcher(text).let { m -> if (m.find()) number(m.group(1))?.let { n -> if (n in 1..400) return dayBase(m.group(), today.minusDays(n.toLong()), zone) } }
        // 6. counted spans, weeks and months.
        LAST_N.matcher(text).let { m ->
            if (m.find()) number(m.group(1))?.takeIf { it in 1..400 }?.let { n ->
                val from = when (m.group(2).lowercase(Locale.ROOT)) { "hour" -> now.minusHours(n.toLong()); "day" -> now.minusDays(n.toLong()); else -> now.minusWeeks(n.toLong()) }
                return Base(m.group().trim(), from, now.plusSeconds(1))
            }
        }
        val monday = day(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), zone)
        THIS_WEEK.matcher(text).let { m -> if (m.find()) return Base(m.group(), monday, now.plusSeconds(1)) }
        LAST_WEEK.matcher(text).let { m -> if (m.find()) return Base(m.group(), monday.minusWeeks(1), monday) }
        val first = day(today.withDayOfMonth(1), zone)
        THIS_MONTH.matcher(text).let { m -> if (m.find()) return Base(m.group(), first, now.plusSeconds(1)) }
        LAST_MONTH.matcher(text).let { m -> if (m.find()) return Base(m.group(), first.minusMonths(1), first) }
        return null
    }

    /** tonight / last night: 18:00 to 06:00. Before 06:00 both mean the night that is still going on. */
    private fun night(phrase: String, now: ZonedDateTime, zone: ZoneId): Base {
        val today = now.toLocalDate()
        val isLast = phrase.contains("last")
        val startDate = when {
            now.hour < DAY_START_HOUR -> today.minusDays(1) // the night in progress, for both words
            isLast -> today.minusDays(1)
            else -> today
        }
        val from = day(startDate, zone).withHour(NIGHT_FROM)
        return Base(phrase, from, day(startDate.plusDays(1), zone).withHour(NIGHT_TO), nightly = true)
    }

    private fun number(s: String): Int? = s.toIntOrNull() ?: NUMBER_WORDS[s.lowercase(Locale.ROOT)]

    /** The latest occurrence of day/month that is not after [today] (an explicit [year] is taken as it is); null for an impossible date or a future one with a year. */
    private fun latestDate(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? {
        fun make(y: Int) = try { LocalDate.of(y, month, day) } catch (_: java.time.DateTimeException) { null }
        if (year != null) return make(year)?.takeIf { !it.isAfter(today) }
        val thisYear = make(today.year)
        if (thisYear != null && !thisYear.isAfter(today)) return thisYear
        return make(today.year - 1)?.takeIf { !it.isAfter(today) }
    }

    private fun clockOf(text: String): Clock? {
        val m = CLOCK.matcher(text)
        while (m.find()) {
            val time = when {
                m.group(7) != null -> if (m.group(7) == "noon") LocalTime.NOON else LocalTime.MIDNIGHT
                m.group(2) != null -> hm(m.group(2).toInt(), m.group(3).toInt(), m.group(4))
                else -> hm(m.group(5).toInt(), 0, m.group(6))
            } ?: continue
            val mode = when (m.group(1)) { "before" -> ClockMode.BEFORE; "after" -> ClockMode.AFTER; else -> ClockMode.AROUND }
            return Clock(m.group().trim(), time, mode)
        }
        return null
    }

    private fun hm(h: Int, min: Int, ampm: String?): LocalTime? {
        if (min !in 0..59) return null
        if (ampm == null) return if (h in 0..23) LocalTime.of(h, min) else null
        if (h !in 1..12) return null
        return LocalTime.of(h % 12 + if (ampm == "pm") 12 else 0, min)
    }

    private fun clockWindow(c: Clock, date: LocalDate, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime> {
        val at = date.atTime(c.time).atZone(zone)
        return when (c.mode) {
            ClockMode.AROUND -> at.minusMinutes(30) to at.plusMinutes(30)
            ClockMode.BEFORE -> day(date, zone) to at
            ClockMode.AFTER -> at to day(date.plusDays(1), zone)
        }
    }

    private fun partWindow(part: String, date: LocalDate, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime> {
        val (a, b) = when (part) { "morning" -> 5 to 12; "afternoon" -> 12 to 17; else -> 17 to 22 }
        return day(date, zone).withHour(a) to day(date, zone).withHour(b)
    }
}
