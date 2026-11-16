package com.rokid.context.phone.zone

/**
 * The in-memory enforcement flag of the pause zones (fail closed when persistence fails): while the controller reads a zone as INSIDE but could not store its pause record (or cannot read its
 * zones), [since] is the start of that pause (phone wall ms, backdated like a record). Frame admission, the phone camera, the voice replies and the settings push to the glasses (`zoneEnforce`)
 * all consult it, so a full disk or a broken store never opens capture. Process-wide; only [ZoneController] writes it.
 */
object ZoneHold {
    @Volatile private var sinceMs: Long? = null
    @Volatile private var closed: List<LongRange> = emptyList()
    fun since(): Long? = sinceMs
    val held: Boolean get() = sinceMs != null

    /** Controller init and tests: forgets everything (the open hold and the retained closed intervals). */
    internal fun set(since: Long?) { sinceMs = since; closed = emptyList() }

    /** The controller's judgement: the open hold ([since], null = none). The retained closed intervals are kept. */
    internal fun update(since: Long?) { sinceMs = since }

    /** An interval whose pause record could not be stored and that has ended (the phone left the zone): kept in memory so frames taken inside it are still dropped. Expires with the retained history. */
    @Synchronized internal fun retain(startMs: Long, endMs: Long, nowMs: Long) {
        closed = closed.filter { it.last >= nowMs - KEEP_MS } + listOf(startMs..maxOf(startMs, endMs))
    }

    /** True when [fromMs]..[toMs] meets the open hold or a retained closed interval. */
    fun covers(fromMs: Long, toMs: Long): Boolean = (sinceMs?.let { toMs >= it } == true) || closed.any { fromMs <= it.last && toMs >= it.first }

    private const val KEEP_MS = 7 * 24 * 3_600_000L
}

/**
 * Detects a backwards move of the phone's wall clock (a user or network time correction). Pause intervals are stored in phone time; frames are mapped into it by an offset measured at a
 * hello, so after a rollback of D ms an inside-zone frame can map up to D before the start of an interval that began before the move. [widenMs] is the total rollback still inside the
 * retained history: zone admission looks that far ahead, and a clock that cannot be trusted fails closed instead of admitting. Process-wide; persisted through [persist] (the peak and the total).
 */
object ClockGuard {
    private const val TOLERANCE_MS = 2_000L
    private const val KEEP_MS = 7 * 24 * 3_600_000L
    private var peak = 0L
    private var rollback = 0L
    private var rollbackAt = 0L
    private var lastSaved = 0L
    /** The wall-minus-elapsed offset at its highest: elapsed time does not move with the wall clock, so a rollback shows at the first observation after it even when the clock had already advanced past the old peak. */
    private var peakOffset = Long.MIN_VALUE
    private var unsaved = false
    private var persist: (peak: Long, rollbackMs: Long, rollbackAtMs: Long) -> Boolean = { _, _, _ -> true }

    /** Monotonic ms (elapsed realtime); the graph sets it. The default (none) leaves only the wall-clock peak. */
    @Volatile var elapsedSource: () -> Long = { 0L }

    @Synchronized
    fun restore(peakMs: Long, rollbackMs: Long, atMs: Long, save: (Long, Long, Long) -> Boolean) { peak = peakMs; rollback = rollbackMs; rollbackAt = atMs; peakOffset = Long.MIN_VALUE; unsaved = false; persist = save }

    @Synchronized
    fun observe(now: Long) {
        var dirty = false
        val offset = now - elapsedSource()
        val back = maxOf(peak - now, if (peakOffset == Long.MIN_VALUE) 0L else peakOffset - offset)
        if (back > TOLERANCE_MS) { rollback += back; rollbackAt = now; peak = now; peakOffset = offset; dirty = true }
        else { if (now > peak) peak = now; if (offset > peakOffset) peakOffset = offset }
        if (rollback > 0 && now - rollbackAt > KEEP_MS) { rollback = 0; dirty = true }
        if (dirty || unsaved || now - lastSaved > 60_000L) {
            lastSaved = now
            // A clock state that cannot be stored could be lost with the process: until it is stored the window is the whole history (fail closed).
            unsaved = !(try { persist(peak, rollback, rollbackAt) } catch (e: Exception) { false })
        }
    }

    /** The look-ahead of the admission checks: the rollback total, or the whole retained history while a clock state could not be stored. */
    @Synchronized fun widenMs(): Long = if (unsaved) KEEP_MS else rollback

    /** Tests. */
    @Synchronized fun reset() { peak = 0; rollback = 0; rollbackAt = 0; lastSaved = 0; peakOffset = Long.MIN_VALUE; unsaved = false; persist = { _, _, _ -> true }; elapsedSource = { 0L } }
}
