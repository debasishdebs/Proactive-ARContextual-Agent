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
    /**
     * An UNMAPPED trace whose COARSE evidence overlaps a live Forget range when it is imported: stored (so the user does not lose it and a later remap can promote it) but HIDDEN from every
     * reader, replay and export input; selected by every range Forget like UNMAPPED, wiped by Delete all. Promoted to a normal quality when the backfill maps it.
     */
    const val QUARANTINED = "QUARANTINED"
    /** The documented uncertainty of the glasses wall clock against the phone clock when nothing maps it: 24 hours either side. */
    const val COARSE_UNCERTAINTY_MS = 24L * 3600 * 1000
    /** The uncertainty stored for an [UNMAPPED] trace: large enough to cover any range, small enough that adding it never overflows. */
    const val UNBOUNDED_MS = Long.MAX_VALUE / 4

    /** True when the trace may overlap `[fromMs, toMs]` on the phone clock. */
    fun overlaps(t: CanvasTraceEntity, fromMs: Long, toMs: Long): Boolean {
        if (t.clockQuality == UNMAPPED || t.clockQuality == QUARANTINED) return true
        val unc = t.uncertaintyMs.coerceIn(0, UNBOUNDED_MS)
        if (t.phoneStartMs - unc > toMs) return false
        val end = t.phoneEndMs ?: return true
        return end + unc >= fromMs
    }

    /** True when the trace may overlap any of [ranges] (the persisted forget fences). */
    fun overlapsAny(t: CanvasTraceEntity, ranges: List<LongRange>): Boolean = ranges.any { overlaps(t, it.first, it.last) }
}

/**
 * Coarse admission evidence of a trace WITHOUT a phone mapping: the glasses wall interval widened by [TraceClock.COARSE_UNCERTAINTY_MS] and the import receipt time. It overlaps a Forget
 * range when either does; then the trace is admitted as QUARANTINED instead of refused.
 */
object CoarseTraceEvidence {
    fun overlapsAny(t: CanvasTraceEntity, ranges: List<LongRange>): Boolean {
        val from = t.startedAtMs - TraceClock.COARSE_UNCERTAINTY_MS
        val to = (t.endedAtMs ?: t.startedAtMs) + TraceClock.COARSE_UNCERTAINTY_MS
        return ranges.any { (it.first <= to && it.last >= from) || t.createdAt in it }
    }
}
