package com.rokid.context.capture.trace

/**
 * FROZEN (trace schema 1). Maps glasses `elapsedRealtime` to wall time for one trace, from the manifest's two samples (start and end).
 *
 * - The glasses wall offset (`wall - elapsed`) is interpolated linearly between the samples. Outside `[start, end]` the nearest sample's
 *   offset is held, for at most [SLACK_MS] (marks and flushes land just outside a trace); farther away the map answers null.
 * - Phone wall time = glasses wall time + `phoneOffsetMs` (null until the phone has synchronised: then [phoneWall] is null).
 * - **Reboot epoch:** `elapsedRealtime` restarts at every boot, so a map answers only for its own `bootId`; any other boot id gets null,
 *   never a guess. [resolve] picks the right map out of several traces by boot id and time.
 * - Uncertainty = the phone sync uncertainty plus the observed drift between the two samples (null when not synchronised).
 */
class ClockMap private constructor(
    val bootId: String, private val startE: Long, private val endE: Long, private val startOffset: Long, private val endOffset: Long,
    private val phoneOffset: Long?, val uncertaintyMs: Long?,
) {
    /** True when [elapsedMs] on [boot] can be mapped (inside the trace or within [SLACK_MS] of it). */
    fun covers(elapsedMs: Long, boot: String): Boolean = boot == bootId && elapsedMs >= 0 && elapsedMs >= startE - SLACK_MS && elapsedMs <= endE + SLACK_MS

    fun glassesWall(elapsedMs: Long, boot: String): Long? {
        if (!covers(elapsedMs, boot)) return null
        val off = when {
            elapsedMs <= startE -> startOffset
            elapsedMs >= endE -> endOffset
            else -> startOffset + Math.round((endOffset - startOffset).toDouble() * (elapsedMs - startE).toDouble() / (endE - startE).toDouble())
        }
        return elapsedMs + off
    }

    fun phoneWall(elapsedMs: Long, boot: String): Long? {
        val po = phoneOffset ?: return null
        return glassesWall(elapsedMs, boot)?.plus(po)
    }

    /** Distance in ms from the trace window (0 inside). */
    private fun distance(e: Long): Long = when { e < startE -> startE - e; e > endE -> e - endE; else -> 0 }

    companion object {
        const val SLACK_MS = 60_000L

        fun of(m: Manifest): ClockMap {
            val so = m.startWallMs - m.startElapsedMs; val eo = m.endWallMs - m.endElapsedMs
            return ClockMap(m.bootId, m.startElapsedMs, m.endElapsedMs, so, eo, m.phoneOffsetMs, m.offsetUncertaintyMs?.plus(Math.abs(eo - so)))
        }

        /** The map of [bootId] closest to [elapsedMs] that covers it, or null (a different boot never matches). */
        fun resolve(maps: List<ClockMap>, bootId: String, elapsedMs: Long): ClockMap? =
            maps.filter { it.covers(elapsedMs, bootId) }.minByOrNull { it.distance(elapsedMs) }
    }
}
