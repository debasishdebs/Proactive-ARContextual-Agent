package com.rokid.context.phone.zone

/**
 * The in-memory enforcement flag of the pause zones (fail closed when persistence fails): while the controller reads a zone as INSIDE but could not store its pause record (or cannot read its
 * zones), [since] is the start of that pause (phone wall ms, backdated like a record). Frame admission, the phone camera, the voice replies and the settings push to the glasses (`zoneEnforce`)
 * all consult it, so a full disk or a broken store never opens capture. Process-wide; only [ZoneController] writes it.
 */
object ZoneHold {
    @Volatile private var sinceMs: Long? = null
    fun since(): Long? = sinceMs
    val held: Boolean get() = sinceMs != null
    internal fun set(since: Long?) { sinceMs = since }
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
    private var persist: (peak: Long, rollbackMs: Long, rollbackAtMs: Long) -> Unit = { _, _, _ -> }

    @Synchronized
    fun restore(peakMs: Long, rollbackMs: Long, atMs: Long, save: (Long, Long, Long) -> Unit) { peak = peakMs; rollback = rollbackMs; rollbackAt = atMs; persist = save }

    @Synchronized
    fun observe(now: Long) {
        var dirty = false
        if (peak - now > TOLERANCE_MS) { rollback += peak - now; rollbackAt = now; peak = now; dirty = true }
        else if (now > peak) peak = now
        if (rollback > 0 && now - rollbackAt > KEEP_MS) { rollback = 0; dirty = true }
        if (dirty || now - lastSaved > 60_000L) { lastSaved = now; persist(peak, rollback, rollbackAt) }
    }

    @Synchronized fun widenMs(): Long = rollback

    /** Tests. */
    @Synchronized fun reset() { peak = 0; rollback = 0; rollbackAt = 0; lastSaved = 0; persist = { _, _, _ -> } }
}
