package com.rokid.context.phone.ui.list.paging

import com.rokid.context.memory.Cursor
import com.rokid.context.memory.DayBucket
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.list.ListItem
import com.rokid.context.phone.ui.list.PagedRow
import com.rokid.context.phone.ui.preview.ActivityData
import com.rokid.context.phone.ui.preview.ActivityPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

/** Where the small saved parts of the view live (production: a SavedStateHandle); strings only, so they survive process death. */
interface StateStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
}

/**
 * The broken-down view of one period (activity pagination A5): days newest first, an expanded day lists its hours, an expanded hour shows pages of 25 moments that REPLACE each other. At most [MAX_OPEN_HOURS] hours stay open
 * (the least recently used one closes). Counts are live: the day counts follow the kind's times flow, the hour counts and the open pages re-read after a change (debounced 300 ms).
 * Memory held: the day counts, the hour counts of the expanded days, and 25 rows per open hour.
 */
class BreakdownModel(
    private val kind: ListKind,
    private val data: ActivityData,
    private val range: LongRange,
    private val zone: () -> ZoneId,
    private val today: () -> LocalDate,
    private val summarize: suspend (ActivityPage) -> List<PagedRow>,
    private val store: StateStore,
) {
    private val lock = Mutex()
    private val _tree = MutableStateFlow(TreePane())
    val tree: StateFlow<TreePane> get() = _tree

    private var times: List<Long> = emptyList()
    private val expandedDays = LinkedHashSet<LocalDate>()
    /** Open hours, least recently used first. The key is the hour's start (`Long.MIN_VALUE` = the day's Earlier group) within its day. */
    private val open = LinkedHashMap<HourKey, HourPager<PagedRow>>()
    private data class HourKey(val day: LocalDate, val startMs: Long)
    private var dayEntries: Map<LocalDate, List<HourEntry>> = emptyMap()
    private var started = false

    /** Starts following the counts: the first emission builds the tree and re-opens the saved hours; every later one (and every debounced change signal) re-reads what is expanded. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        restore()
        scope.launch {
            var first = true
            data.times(kind, range).collect { t ->
                lock.withLock { times = t; rebuild(reloadDays = true); if (first) { first = false; reopenSaved() } }
            }
        }
        scope.launch { data.listChanges(kind).debounce(KeysetPager.DEBOUNCE_MS).collect { lock.withLock { rebuild(reloadDays = true) } } }
    }

    /** Expands or collapses [date]; collapsing it closes its open hours. */
    suspend fun toggleDay(date: LocalDate) = lock.withLock {
        if (expandedDays.remove(date)) open.keys.removeAll { it.day == date } else expandedDays += date
        rebuild(reloadDays = false); save()
    }

    /** Opens [startMs]'s hour of [day] (page 1), or closes it. Opening a fifth hour closes the least recently used one. */
    suspend fun toggleHour(day: LocalDate, startMs: Long) = lock.withLock {
        val key = HourKey(day, startMs)
        if (open.remove(key) == null) {
            val node = dayEntries[day].orEmpty().filterIsInstance<HourNode>().firstOrNull { it.startMs == startMs } ?: return@withLock
            val pager = pagerFor(DayBucket.bounds(day, zone()), node)
            open[key] = pager
            while (open.size > MAX_OPEN_HOURS) open.remove(open.keys.first())
            rebuild(reloadDays = false) // the hour shows at once, its rows as skeletons of the known count
            pager.open(node.count)
        }
        rebuild(reloadDays = false); save()
    }

    suspend fun nextPage(day: LocalDate, startMs: Long) = lock.withLock { page(HourKey(day, startMs)) { it.next() } }
    suspend fun previousPage(day: LocalDate, startMs: Long) = lock.withLock { page(HourKey(day, startMs)) { it.previous() } }

    private suspend fun page(k: HourKey, move: suspend (HourPager<PagedRow>) -> Unit) {
        val p = open[k] ?: return
        move(p); open.remove(k); open[k] = p // used last
        rebuild(reloadDays = false); save()
    }

    private fun pagerFor(day: LongRange, e: HourNode): HourPager<PagedRow> {
        val hour = if (e.earlier) Long.MIN_VALUE..(e.endMs - 1) else e.startMs..(e.endMs - 1)
        return HourPager(Cursor.head(hour), { it.cursor }, HourPager.PAGE_SIZE) { after, n -> summarize(data.momentHourPage(day, hour, after, n)) }
    }

    /** Recomputes everything the tree shows from the latest counts: the days, the hours of the expanded days (re-read when [reloadDays]) and the open pages. */
    private suspend fun rebuild(reloadDays: Boolean) {
        val z = zone(); val now = today()
        val counts = DayHourTree.dayCounts(times, z)
        // A day outside the period never shows; today is expanded the first time.
        val days = (counts.keys + listOfNotNull(now.takeIf { counts.isEmpty() || now in counts })).toSortedSet(compareByDescending { it }).toList()
        if (days.firstOrNull() == now && !initDone) { expandedDays += now; initDone = true }
        if (reloadDays) dayEntries = emptyMap()
        val entries = HashMap(dayEntries)
        for (d in days.filter { it in expandedDays }) {
            if (reloadDays || d !in entries) {
                val dr = DayBucket.bounds(d, z)
                entries[d] = DayHourTree.hours(d, z, data.momentTimes(dr), data.pauses(dr))
            }
        }
        entries.keys.retainAll(days.toSet()); dayEntries = entries
        // Pagers of hours that no longer exist (a zone change, a forgotten hour) close; the rest re-read their page.
        open.keys.toList().forEach { k ->
            val node = entries[k.day]?.filterIsInstance<HourNode>()?.firstOrNull { it.startMs == k.startMs }
            if (node == null) open.remove(k) else if (reloadDays && open.getValue(k).state.value.loaded) open.getValue(k).refresh(node.count)
        }
        _tree.value = TreePane(days.map { d -> dayNode(d, counts[d] ?: 0, now, entries[d]) }, loaded = true, focusDay = open.keys.lastOrNull()?.day, focusHourStart = open.keys.lastOrNull()?.startMs)
    }
    private var initDone = false

    private fun dayNode(d: LocalDate, count: Int, now: LocalDate, hours: List<HourEntry>?): DayNode {
        val expanded = d in expandedDays
        val withPages = hours?.map { e ->
            if (e !is HourNode) e else {
                val p = open[HourKey(d, e.startMs)]?.state?.value
                when {
                    p == null -> e
                    !p.loaded -> e.copy(expanded = true, pages = (e.count + HourPager.PAGE_SIZE - 1) / HourPager.PAGE_SIZE, rows = null)
                    else -> e.copy(expanded = true, page = p.page, pages = p.pages, rows = p.rows.mapNotNull { it.item })
                }
            }
        }
        return DayNode(d, DayHourTree.dayTitle(d, now, count), count, expanded, if (expanded) withPages else null)
    }

    // ---- Saved state (small: dates, hour starts, the page and its cursor stack) ----------------------------------------------------------------------

    private fun save() {
        store.set(K_DAYS, expandedDays.joinToString(","))
        store.set(K_INIT, if (initDone) "1" else "0")
        store.set(K_HOURS, open.entries.joinToString("#") { (k, p) -> val s = p.snapshot(); "${k.day};${k.startMs};${s.page};" + s.starts.joinToString(",") { "${it.key}|${it.id}" } })
    }

    private fun restore() {
        store.get(K_DAYS)?.split(',')?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { expandedDays += it }
        initDone = store.get(K_INIT) == "1"
        saved = store.get(K_HOURS).orEmpty().split('#').mapNotNull { raw ->
            val f = raw.split(';'); if (f.size != 4) return@mapNotNull null
            val day = runCatching { LocalDate.parse(f[0]) }.getOrNull() ?: return@mapNotNull null
            val start = f[1].toLongOrNull() ?: return@mapNotNull null; val page = f[2].toIntOrNull() ?: return@mapNotNull null
            val starts = f[3].split(',').mapNotNull { c -> c.split('|').takeIf { it.size == 2 }?.let { p -> p[0].toLongOrNull()?.let { Cursor(it, p[1]) } } }
            Triple(HourKey(day, start), page, starts)
        }
    }
    private var saved: List<Triple<HourKey, Int, List<Cursor>>> = emptyList()

    /** After the first rebuild: re-opens the saved hours at their saved page (those that still exist). */
    private suspend fun reopenSaved() {
        for ((k, page, starts) in saved) {
            val node = dayEntries[k.day]?.filterIsInstance<HourNode>()?.firstOrNull { it.startMs == k.startMs } ?: continue
            val pager = pagerFor(DayBucket.bounds(k.day, zone()), node)
            pager.open(node.count, HourSnapshot(page, starts))
            open[k] = pager
        }
        saved = emptyList(); rebuild(reloadDays = false)
    }

    companion object {
        const val MAX_OPEN_HOURS = 4
        const val K_DAYS = "bd.days"; const val K_INIT = "bd.init"; const val K_HOURS = "bd.hours"
    }
}
