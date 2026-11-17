package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.DayBucket
import com.rokid.context.phone.ui.activity.ActivityModel
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.preview.PauseSpan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A chip on an hour that a pause overlaps: "Paused 14:05-14:40", or "Privacy zone" (never the zone's name or place). */
data class PauseChip(val label: String)

/** One entry of an expanded day: an hour with activity (or a pause), or a folded run of quiet hours. */
sealed interface HourEntry
data class QuietRun(val label: String) : HourEntry
data class HourNode(
    val startMs: Long, val endMs: Long, val label: String, val count: Int, val pauses: List<PauseChip>,
    /** The "Earlier photos" group: memories created this day whose photo was taken before it (see `Timeline.sections`). */
    val earlier: Boolean = false,
    val expanded: Boolean = false, val page: Int = 0, val pages: Int = 0, val rows: List<ListItem>? = null,
) : HourEntry
data class DayNode(val date: LocalDate, val title: String, val count: Int, val expanded: Boolean, val hours: List<HourEntry>?)
data class TreePane(val days: List<DayNode> = emptyList(), val loaded: Boolean = false)

/**
 * The pure model of the broken-down view (activity pagination A5): days newest first, hours of a day, folded quiet runs, pause chips. All in the zone's own hours (DST-safe: an hour slot is an instant
 * range, so a 23- or 25-hour day has 23 or 25 slots). No Android, no database.
 */
object DayHourTree {
    /** The hour slots of [date] in [zone]: contiguous instant ranges of one hour, 23 or 25 of them on a DST day. */
    data class Slot(val startMs: Long, val endMs: Long, val label: String)

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun slots(date: LocalDate, zone: ZoneId): List<Slot> {
        val day = DayBucket.bounds(date, zone)
        val out = ArrayList<Slot>(25)
        var start: ZonedDateTime = Instant.ofEpochMilli(day.first).atZone(zone)
        while (start.toInstant().toEpochMilli() <= day.last) {
            val next = start.plusHours(1)
            val end = minOf(next.toInstant().toEpochMilli(), day.last + 1)
            out += Slot(start.toInstant().toEpochMilli(), end, HHMM.format(start))
            start = next
        }
        return out
    }

    /** How many items per local day of [zone] (the day counts; a day with none is absent). */
    fun dayCounts(times: List<Long>, zone: ZoneId): Map<LocalDate, Int> = times.groupingBy { DayBucket.dayOf(it, zone) }.eachCount()

    /** "Today", "Yesterday" or "Tue 12 Nov", then the count. */
    fun dayTitle(date: LocalDate, today: LocalDate, count: Int): String {
        val name = when (date) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> DAY.format(date) }
        return "$name · $count ${if (count == 1) "moment" else "moments"}"
    }

    /**
     * The entries of one expanded day from the display times of its memories ([times], see `ActivityData.momentTimes`): an hour with items or a pause, in newest-first order; consecutive hours with
     * neither fold into one "No activity 01:00-06:00" run; times before the day go to a final "Earlier photos" node. An hour that has only a pause shows the chip and no count ([HourNode.count] 0).
     */
    fun hours(date: LocalDate, zone: ZoneId, times: List<Long>, pauses: List<PauseSpan>): List<HourEntry> {
        val slots = slots(date, zone)
        val dayStart = slots.first().startMs
        val counts = IntArray(slots.size)
        var earlier = 0
        for (t in times) {
            if (t < dayStart) { earlier++; continue }
            val i = slots.indexOfLast { it.startMs <= t }
            if (i >= 0 && t < slots[i].endMs) counts[i]++
        }
        val out = ArrayList<HourEntry>()
        var quiet = ArrayList<Slot>()
        fun flush() {
            if (quiet.isEmpty()) return
            // quiet is collected newest first: the run reads oldest to newest.
            out += QuietRun("No activity ${quiet.last().label}-${slotEndLabel(quiet.first(), zone, slots.last().endMs)}"); quiet = ArrayList()
        }
        for (i in slots.indices.reversed()) {
            val chips = chips(slots[i], pauses, zone)
            if (counts[i] == 0 && chips.isEmpty()) { quiet += slots[i]; continue }
            flush()
            out += HourNode(slots[i].startMs, slots[i].endMs, slots[i].label, counts[i], chips)
        }
        flush()
        if (earlier > 0) out += HourNode(Long.MIN_VALUE, dayStart, "Earlier photos", earlier, emptyList(), earlier = true)
        return out
    }

    /** The end of a slot as a clock time; the end of the day reads "24:00", not "00:00". */
    private fun slotEndLabel(s: Slot, zone: ZoneId, dayEnd: Long) = if (s.endMs == dayEnd) "24:00" else HHMM.format(Instant.ofEpochMilli(s.endMs).atZone(zone))

    /** The chips of [slot]: a manual or glasses pause clipped to the hour, and "Privacy zone" once for any zone pause that overlaps it. */
    fun chips(slot: Slot, pauses: List<PauseSpan>, zone: ZoneId): List<PauseChip> {
        val hit = pauses.filter { it.startMs < slot.endMs && (it.endMs == null || it.endMs > slot.startMs) }
        val manual = hit.filterNot { it.privacyZone }.sortedBy { it.startMs }.map { p ->
            val from = maxOf(p.startMs, slot.startMs); val to = minOf(p.endMs ?: slot.endMs, slot.endMs)
            PauseChip("Paused ${HHMM.format(Instant.ofEpochMilli(from).atZone(zone))}-${if (to == dayEndOf(slot, zone)) "24:00" else HHMM.format(Instant.ofEpochMilli(to).atZone(zone))}")
        }
        return manual + if (hit.any { it.privacyZone }) listOf(PauseChip("Privacy zone")) else emptyList()
    }

    private fun dayEndOf(slot: Slot, zone: ZoneId): Long = DayBucket.bounds(DayBucket.dayOf(slot.startMs, zone), zone).last + 1

    /** The day's own title helper for the Activity wording (kept next to [dayTitle] so both views name days alike). */
    fun shortDay(date: LocalDate, today: LocalDate): String = ActivityModel.dayLabel(date, today)
}
