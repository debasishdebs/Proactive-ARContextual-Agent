package com.rokid.context.memory.db

/**
 * The phone-clock interval of a stored trace and the ONE overlap rule every selection uses (spec 7.2, 7.3). A trace is selected by a range `[from, to]` when
 * `phoneStart - uncertainty <= to && phoneEnd + uncertainty >= from`; the uncertainty is NOT capped (a ClockMap carries sample ages of up to 120 s). An [UNMAPPED] trace has no
 * usable phone mapping: it is treated as unbounded and is selected by every range, and glasses wall times are never compared with a phone range. Numbers and enum names only.
 */
object TraceClock {
    /** The row is on the phone clock already (hub traces) or mapped exactly. */
    const val EXACT = "EXACT"
    /** Mapped through the trace's ClockMap; [CanvasTraceEntity.uncertaintyMs] is the map's uncertainty. */
    const val OFFSET_ESTIMATED = "OFFSET_ESTIMATED"
    /** No usable mapping (no phone samples or offset, a wall or phone clock jump): unbounded. */
    const val UNMAPPED = "UNMAPPED"
    /** The uncertainty stored for an [UNMAPPED] trace: large enough to cover any range, small enough that adding it never overflows. */
    const val UNBOUNDED_MS = Long.MAX_VALUE / 4

    /** True when the trace may overlap `[fromMs, toMs]` on the phone clock. */
    fun overlaps(t: CanvasTraceEntity, fromMs: Long, toMs: Long): Boolean {
        if (t.clockQuality == UNMAPPED) return true
        val unc = t.uncertaintyMs.coerceIn(0, UNBOUNDED_MS)
        if (t.phoneStartMs - unc > toMs) return false
        val end = t.phoneEndMs ?: return true
        return end + unc >= fromMs
    }

    /** True when the trace may overlap any of [ranges] (the persisted forget fences). */
    fun overlapsAny(t: CanvasTraceEntity, ranges: List<LongRange>): Boolean = ranges.any { overlaps(t, it.first, it.last) }
}
