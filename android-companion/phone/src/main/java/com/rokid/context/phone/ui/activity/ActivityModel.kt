package com.rokid.context.phone.ui.activity

import com.rokid.context.memory.DayBucket
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MomentDao
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.core.model.MarkPhotoReason
import com.rokid.context.memory.moment.NoPhotoReason
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

data class Tiles(val frames: Int, val memories: Int, val answers: Int, val nudges: Int, val scenes: Int = 0)
data class TimelineRow(val memoryId: String, val time: String, val text: String, val confidence: Double, val framePath: String?, val frameId: String?, val atMs: Long = 0L)
data class TimelineSection(val label: String, val rows: List<TimelineRow>)
data class DayBar(val date: LocalDate, val label: String, val count: Int)
data class DayRow(val date: LocalDate, val title: String, val subtitle: String)

private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

object Timeline {
    /** Leading section for memories whose photo was taken on another day (a backlog extracted on this day). */
    const val EARLIER = "Earlier days"
    /** The one section of an alphabetical order. */
    const val ALL = "All memories"
    private val ORDER = listOf("Morning", "Afternoon", "Evening")
    private val DAY_HHMM = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)

    fun partOfDay(hour: Int): String = when {
        hour < 12 -> "Morning"
        hour < 17 -> "Afternoon"
        else -> "Evening"
    }

    /**
     * Sections and rows in [order]. [day] is the day the memories were extracted on (the query window). [frames] must
     * hold each memory's own frame, whatever day it was captured.
     * Ruling BUG-1: a row's time is its photo's capture time (the moment the memory is about), falling back to
     * createdAt when it has no frame; a photo from another day goes under [EARLIER] with its date shown.
     * Sort ruling: the time orders keep the grouping and sort both the sections and the rows within each by time, so
     * [EARLIER] (photos older than the day) leads under Oldest first and closes the list under Latest first. The
     * alphabetical orders flatten everything into one [ALL] section (a part-of-day heading would split the A-Z run).
     */
    fun sections(memories: List<MemoryEntity>, frames: List<FrameEntity>, zone: ZoneId, day: LocalDate, order: ActivitySort = ActivitySort.DEFAULT): List<TimelineSection> {
        val byId = frames.associateBy { it.id }
        val rows = memories.map { m ->
            val frame = m.frameId?.let(byId::get)
            val at = Instant.ofEpochMilli(frame?.ts ?: m.createdAt).atZone(zone)
            val otherDay = at.toLocalDate() != day
            val label = if (otherDay) EARLIER else partOfDay(at.hour)
            val time = (if (otherDay) DAY_HHMM else HHMM).format(at)
            Triple(label, at.toInstant().toEpochMilli(), TimelineRow(m.id, time, m.text, m.confidence, frame?.path, m.frameId, at.toInstant().toEpochMilli()))
        }.sortedBy { it.second }
        order.titleOrder<TimelineRow> { it.text }?.let { cmp ->
            return if (rows.isEmpty()) emptyList() else listOf(TimelineSection(ALL, rows.map { it.third }.sortedWith(cmp)))
        }
        val latest = order == ActivitySort.LATEST_FIRST
        val grouped = (if (latest) rows.asReversed() else rows).groupBy({ it.first }, { it.third })
        val labels = listOf(EARLIER) + ORDER
        return (if (latest) labels.asReversed() else labels).mapNotNull { label -> grouped[label]?.let { TimelineSection(label, it) } }
    }

    /**
     * The memories that are still visible NOW. The memories and frames flows are separate Room queries: after one Forget transaction (memory and frame rows), the
     * frames flow can emit first while the memories list is still the old one. [lookup] is a live read, done after both flows emitted, so a forgotten memory's
     * caption is never mapped to the UI for that intermediate emission. A live read that fails keeps the list as it was (the next emission corrects it).
     */
    suspend fun stillVisible(memories: List<MemoryEntity>, lookup: suspend (List<String>) -> List<MemoryEntity>): List<MemoryEntity> {
        if (memories.isEmpty()) return memories
        val live = try { lookup(memories.map { it.id }) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Exception) { return memories }
        val ok = live.filter { it.reviewState == com.rokid.context.memory.db.ReviewState.OK }.mapTo(HashSet()) { it.id }
        return memories.filter { it.id in ok }
    }

    /** [dayFrames] plus the frames of [memories] captured outside that window (looked up by frameId). */
    suspend fun framesFor(memories: List<MemoryEntity>, dayFrames: List<FrameEntity>, lookup: suspend (List<String>) -> List<FrameEntity>): List<FrameEntity> {
        val have = dayFrames.mapTo(HashSet()) { it.id }
        val missing = memories.mapNotNull { it.frameId }.filter { it !in have }.distinct()
        return if (missing.isEmpty()) dayFrames else dayFrames + lookup(missing)
    }
}

/** One mark as the Activity tab shows it (read-only): when, how it was made, whether a Moment holds it and, for a Moment with no photo, why. Fixed words only; a mark carries no text. */
data class MarkRowUi(val labelId: String, val atMs: Long, val time: String, val code: String, val joined: String, val reason: String?,
    /** What a tap previews: the linked photo, the caption of its Moment, and the day. */
    val frameId: String? = null, val memoryId: String? = null, val date: LocalDate? = null,
    /** The fixed token behind [reason] (`camera_busy`, `pending`, `unknown`...); null when the mark has no reason. */
    val reasonCode: String? = null,
)

object MarkRows {
    private val DAY_HHMM = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH)
    const val EMPTY = "No marks yet. A tap or a voice mark on your glasses will show up here."

    /**
     * v0.9.7 slice D (user ruling): a mark shows ONE distinction, "During a call" (a phone call was active as it was made, the `;call` tag of its provenance); every other mark has no label.
     * How it was made (tap, voice, chat) is deliberately not shown.
     */
    fun callLabel(provenance: String): String = if (provenance.endsWith(";call")) "During a call" else ""

    /** Whether a Moment holds the mark, and which kind. Exhaustive over the Moment type: a new type must say what it shows. */
    fun joinedLabel(type: MomentType?): String = when (type) {
        null -> "Not in a Moment yet"
        MomentType.FRAME -> "In a Moment with a photo"
        MomentType.MARK -> "Moment with no photo"
    }

    /**
     * Only a mark with no photo has a reason. A fallback Moment says why ([errorReason], v0.9.6 slice C); a Moment from before it says "reason unknown". A mark no Moment holds yet shows what its own photo state says:
     * still on its way, or the reason it will have none.
     */
    fun reasonLabel(type: MomentType?, reason: NoPhotoReason?, errorReason: String? = null, photo: String? = null): String? = when (type) {
        MomentType.FRAME -> null
        MomentType.MARK -> "No photo: " + (errorReason?.let(::reasonWords) ?: when (reason) { null, NoPhotoReason.UNKNOWN -> "reason unknown" })
        null -> when (photo) {
            null, MarkPhotoReason.OK -> null
            MarkPhotoReason.PENDING -> "Photo on its way"
            else -> "No photo: ${reasonWords(photo)}"
        }
    }

    /** The token [reasonLabel] words, for the previewer's status line. */
    fun reasonCode(type: MomentType?, errorReason: String? = null, photo: String? = null): String? = when (type) {
        MomentType.FRAME -> null
        MomentType.MARK -> errorReason ?: "unknown"
        null -> when (photo) { null, MarkPhotoReason.OK -> null; else -> photo }
    }

    /** The reason tokens as plain words (a token this build does not know is shown as it is: it is a short code, never text a user wrote). */
    fun reasonWords(token: String): String = when (token) {
        MarkPhotoReason.NO_PHOTO_SUPPORT -> "these glasses cannot take a photo for a mark"
        MarkPhotoReason.RETRO -> "a mark said afterwards has no photo"
        MarkPhotoReason.PHONE_MARK -> "a mark made on the phone has no camera"
        MarkPhotoReason.TIMEOUT -> "the photo never arrived"
        MarkPhotoReason.NO_CAPTURE_SERVICE -> "the glasses camera service was not running"
        MarkPhotoReason.CAMERA_BUSY -> "the camera was busy"
        MarkPhotoReason.CAMERA_BIND_FAILED -> "the camera could not start"
        MarkPhotoReason.NO_FRAME -> "the camera gave no picture"
        MarkPhotoReason.HEAT -> "the glasses were too warm"
        MarkPhotoReason.NOT_STORED -> "the glasses could not save it"
        MarkPhotoReason.CANCELLED -> "the photo was cancelled"
        MarkPhotoReason.RATE_LIMITED -> "too many photo requests in a minute"
        MarkPhotoReason.SESSION_CLOSED -> "the glasses link had closed"
        MarkPhotoReason.UNKNOWN_SESSION -> "the glasses link was not ready"
        MarkPhotoReason.PAUSE_ZONE -> "photos are not allowed in a pause zone"
        else -> token
    }

    /** [withDay] adds the weekday and date (Week and Month). Order is the one the rows come in (newest first). */
    fun rows(rows: List<MomentDao.MarkRow>, zone: ZoneId, withDay: Boolean): List<MarkRowUi> = rows.map { r ->
        val at = Instant.ofEpochMilli(r.atMs).atZone(zone)
        MarkRowUi(r.labelId, r.atMs, (if (withDay) DAY_HHMM else HHMM).format(at), callLabel(r.provenance), joinedLabel(r.momentType), reasonLabel(r.momentType, r.noPhotoReason, r.errorReason, r.photo), r.frameId, r.memoryId, at.toLocalDate(), reasonCode(r.momentType, r.errorReason, r.photo))
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

    /** Days with activity in [order]: by date for the time orders, by the row's title for the alphabetical ones. */
    fun dayRows(
        window: PeriodWindow, frameCounts: Map<LocalDate, Int>, memoryCounts: Map<LocalDate, Int>, today: LocalDate, order: ActivitySort = ActivitySort.DEFAULT,
    ): List<DayRow> {
        val rows = window.days.filter { (frameCounts[it] ?: 0) + (memoryCounts[it] ?: 0) > 0 }.sortedDescending().map { d ->
            DayRow(d, dayLabel(d, today), "${plural(frameCounts[d] ?: 0, "frame")} \u00b7 ${plural(memoryCounts[d] ?: 0, "memory", "memories")}")
        }
        return order.titleOrder<DayRow> { it.title }?.let(rows::sortedWith) ?: if (order == ActivitySort.OLDEST_FIRST) rows.asReversed() else rows
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
    val sort: ActivitySort = ActivitySort.DEFAULT,
    /** The marks of the period, newest first (read-only). */
    val marks: List<MarkRowUi> = emptyList(),
    /** The level the page browses (Photos, Moments, Scenes); the default is the page as it always was. */
    val granularity: Granularity = Granularity.DEFAULT,
)
