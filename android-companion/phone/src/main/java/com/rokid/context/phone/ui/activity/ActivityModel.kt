package com.rokid.context.phone.ui.activity

import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.phone.ui.status.StatusUi
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Period {
    TODAY, WEEK, MONTH;

    companion object {
        /** Route argument `activity?period=` (spec §2.1); anything unknown means Today. */
        fun parse(raw: String?): Period = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: TODAY
    }
}

/** Local dates [start, endExclusive). Millis come from [DayBucket.bounds] so DST days are 23/25 h. */
data class PeriodWindow(val period: Period, val start: LocalDate, val endExclusive: LocalDate) {
    val days: List<LocalDate> get() = generateSequence(start) { it.plusDays(1) }.takeWhile { it.isBefore(endExclusive) }.toList()

    fun millis(zone: ZoneId): LongRange = DayBucket.bounds(start, zone).first until DayBucket.bounds(endExclusive, zone).first

    companion object {
        fun of(period: Period, today: LocalDate): PeriodWindow = when (period) {
            Period.TODAY -> PeriodWindow(period, today, today.plusDays(1))
            Period.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).let { PeriodWindow(period, it, it.plusDays(7)) }
            Period.MONTH -> today.withDayOfMonth(1).let { PeriodWindow(period, it, it.plusMonths(1)) }
        }
    }
}

data class Tiles(val frames: Int, val memories: Int, val answers: Int, val nudges: Int)
data class TimelineRow(val memoryId: String, val time: String, val text: String, val confidence: Double, val framePath: String?, val frameId: String?)
data class TimelineSection(val label: String, val rows: List<TimelineRow>)
data class DayBar(val date: LocalDate, val label: String, val count: Int)
data class DayRow(val date: LocalDate, val title: String, val subtitle: String)

private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

object Timeline {
    /** Leading section for memories whose photo was taken on another day (a backlog extracted on this day). */
    const val EARLIER = "Earlier days"
    private val ORDER = listOf("Morning", "Afternoon", "Evening")
    private val DAY_HHMM = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

    fun partOfDay(hour: Int): String = when {
        hour < 12 -> "Morning"
        hour < 17 -> "Afternoon"
        else -> "Evening"
    }

    /**
     * Sections in day order, rows oldest first (the day reads as a story). [day] is the day the memories were
     * extracted on (the query window). [frames] must hold each memory's own frame, whatever day it was captured.
     * Ruling BUG-1: a row's time is its photo's capture time (the moment the memory is about), falling back to
     * createdAt when it has no frame; a photo from another day goes under [EARLIER] with its date shown.
     */
    fun sections(memories: List<MemoryEntity>, frames: List<FrameEntity>, zone: ZoneId, day: LocalDate): List<TimelineSection> {
        val byId = frames.associateBy { it.id }
        val rows = memories.map { m ->
            val frame = m.frameId?.let(byId::get)
            val at = Instant.ofEpochMilli(frame?.ts ?: m.createdAt).atZone(zone)
            val otherDay = at.toLocalDate() != day
            val label = if (otherDay) EARLIER else partOfDay(at.hour)
            val time = (if (otherDay) DAY_HHMM else HHMM).format(at)
            Triple(label, at.toInstant().toEpochMilli(), TimelineRow(m.id, time, m.text, m.confidence, frame?.path, m.frameId))
        }.sortedBy { it.second }.groupBy({ it.first }, { it.third })
        return (listOf(EARLIER) + ORDER).mapNotNull { label -> rows[label]?.let { TimelineSection(label, it) } }
    }

    /** [dayFrames] plus the frames of [memories] captured outside that window (looked up by frameId). */
    suspend fun framesFor(memories: List<MemoryEntity>, dayFrames: List<FrameEntity>, lookup: suspend (List<String>) -> List<FrameEntity>): List<FrameEntity> {
        val have = dayFrames.mapTo(HashSet()) { it.id }
        val missing = memories.mapNotNull { it.frameId }.filter { it !in have }.distinct()
        return if (missing.isEmpty()) dayFrames else dayFrames + lookup(missing)
    }
}

object ActivityModel {
    private val SHORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val SHORT_DAY_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
    private val MONTH_TICKS = setOf(1, 8, 15, 22, 29)

    fun countByDay(times: List<Long>, window: PeriodWindow, zone: ZoneId): Map<LocalDate, Int> =
        times.groupingBy { DayBucket.dayOf(it, zone) }.eachCount().filterKeys { !it.isBefore(window.start) && it.isBefore(window.endExclusive) }

    fun bars(window: PeriodWindow, frameCounts: Map<LocalDate, Int>): List<DayBar> = window.days.map { d ->
        val label = when (window.period) {
            Period.WEEK -> d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH)
            Period.MONTH -> if (d.dayOfMonth in MONTH_TICKS) "${d.dayOfMonth}" else ""
            Period.TODAY -> ""
        }
        DayBar(d, label, frameCounts[d] ?: 0)
    }

    fun dayRows(window: PeriodWindow, frameCounts: Map<LocalDate, Int>, memoryCounts: Map<LocalDate, Int>, today: LocalDate): List<DayRow> =
        window.days.filter { (frameCounts[it] ?: 0) + (memoryCounts[it] ?: 0) > 0 }.sortedDescending().map { d ->
            DayRow(d, dayLabel(d, today), "${plural(frameCounts[d] ?: 0, "frame")} · ${plural(memoryCounts[d] ?: 0, "memory", "memories")}")
        }

    /** Ported from Live Fit `DailyActivity.dayLabel`. */
    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(if (date.year == today.year) SHORT_DAY else SHORT_DAY_YEAR)
    }

    fun header(window: PeriodWindow, today: LocalDate): String = when (window.period) {
        Period.TODAY -> today.format(SHORT_DAY)
        Period.WEEK -> "Week of ${window.start.format(DAY_MONTH)}"
        Period.MONTH -> window.start.format(MONTH_YEAR)
    }

    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"
}

data class ActivityUiState(
    val period: Period = Period.TODAY,
    val header: String = "",
    val status: StatusUi = StatusUi.Initial,
    val tiles: Tiles = Tiles(0, 0, 0, 0),
    /** Today only. */
    val sections: List<TimelineSection> = emptyList(),
    /** Week / Month only. */
    val bars: List<DayBar> = emptyList(),
    val dayRows: List<DayRow> = emptyList(),
    val today: LocalDate? = null,
)
