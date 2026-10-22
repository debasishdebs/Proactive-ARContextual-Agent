package com.rokid.context.capture.trace

/**
 * FROZEN (trace schema 1). Maps glasses `elapsedRealtime` to wall time for one trace, from the manifest's two samples (start and end).
 *
 * - The glasses wall offset (`wall - elapsed`) is interpolated linearly between the samples. Outside `[start, end]` the nearest sample's
 *   offset is held, for at most [SLACK_MS] (marks and flushes land just outside a trace); farther away the map answers null.
 * - **Wall jump (I1):** when the offset moved by more than [MAX_WALL_JUMP_MS] between the samples, the glasses wall clock was corrected during the trace
 *   (NTP, link sync, a manual set). Interpolating would give a confidently wrong time, so [wallJump] is true and every wall answer is null.
 * - Phone wall time = glasses wall time + the phone offset. The offset: the recorder's own phone samples (`startPhoneWallMs`/`endPhoneWallMs`), interpolated like the glasses offset, take precedence when present; the intake offset is the fallback.
 *   With neither: null.
 * - **Reboot epoch:** `elapsedRealtime` restarts at every boot, so a map answers only for its own `bootId`; any other boot id gets null,
 *   never a guess. [resolve] picks the right map out of several traces by boot id and time.
 * - **Phone samples (I3):** each carries its age; a sample older than [MAX_PHONE_AGE_MS] is not used ([phoneSampleStale], intake offset fallback). The observed
 *   phone-offset drift between the samples above [MAX_PHONE_JUMP_MS] means a clock correction: [phoneJump], no phone time.
 * - [uncertaintyMs] = the phone sync uncertainty (0 when unknown) plus the observed glasses drift, the phone-offset drift and the sample age. It is never null: the drift is
 *   known from the manifest alone.
 */
class ClockMap private constructor(
    val bootId: String, private val startE: Long, private val endE: Long, private val startOffset: Long, private val endOffset: Long,
    private val phoneStart: Long?, private val phoneEnd: Long?, val uncertaintyMs: Long, val wallJump: Boolean,
    /** True when the recorder's phone samples were older than [MAX_PHONE_AGE_MS] (not used; the intake offset is the fallback) . */
    val phoneSampleStale: Boolean, /** True when the phone clock moved more than [MAX_PHONE_JUMP_MS] relative to the glasses clock between the samples (no phone time). */ val phoneJump: Boolean,
) {
    /** True when [elapsedMs] on [boot] can be mapped (inside the trace or within [SLACK_MS] of it). */
    fun covers(elapsedMs: Long, boot: String): Boolean = boot == bootId && elapsedMs >= 0 && elapsedMs >= startE - SLACK_MS && elapsedMs <= endE + SLACK_MS

    private fun interp(a: Long, b: Long, elapsedMs: Long): Long = when {
        elapsedMs <= startE -> a
        elapsedMs >= endE -> b
        else -> a + Math.round((b - a).toDouble() * (elapsedMs - startE).toDouble() / (endE - startE).toDouble())
    }

    fun glassesWall(elapsedMs: Long, boot: String): Long? {
        if (wallJump || !covers(elapsedMs, boot)) return null
        return elapsedMs + interp(startOffset, endOffset, elapsedMs)
    }

    fun phoneWall(elapsedMs: Long, boot: String): Long? {
        if (phoneJump) return null
        val gw = glassesWall(elapsedMs, boot) ?: return null
        // the trace's own phone samples win: an intake-time offset is wrong when the wall clock was corrected between recording and intake
        val off = if (phoneStart != null && phoneEnd != null) interp(phoneStart, phoneEnd, elapsedMs) else fixedPhoneOffset ?: return null
        return gw + off
    }

    private var fixedPhoneOffset: Long? = null

    /** Distance in ms from the trace window (0 inside). */
    private fun distance(e: Long): Long = when { e < startE -> startE - e; e > endE -> e - endE; else -> 0 }

    companion object {
        const val SLACK_MS = 60_000L
        /** A glasses wall offset change bigger than this between the start and end samples means the wall clock was set mid-trace. */
        const val MAX_WALL_JUMP_MS = 2_000L
        /** A phone sample older than this (the age of the latest phone message at the sample instant) is not trusted. */
        const val MAX_PHONE_AGE_MS = 120_000L
        /** The phone-minus-glasses wall offset moving more than this between the two samples means a phone or glasses clock correction. */
        const val MAX_PHONE_JUMP_MS = 2_000L

        fun of(m: Manifest): ClockMap {
            val so = m.startWallMs - m.startElapsedMs; val eo = m.endWallMs - m.endElapsedMs
            val drift = Math.abs(eo - so)
            val haveSamples = m.startPhoneWallMs != null && m.endPhoneWallMs != null
            val stale = haveSamples && (m.startPhoneAgeMs!! > MAX_PHONE_AGE_MS || m.endPhoneAgeMs!! > MAX_PHONE_AGE_MS)
            val use = haveSamples && !stale
            // phone wall at the sample instant = the message's atMs + its age
            val ps = if (use) m.startPhoneWallMs!! + m.startPhoneAgeMs!! - m.startWallMs else null
            val pe = if (use) m.endPhoneWallMs!! + m.endPhoneAgeMs!! - m.endWallMs else null
            val phoneDrift = if (ps != null && pe != null) Math.abs(pe - ps) else 0L
            val ageUnc = if (use) maxOf(m.startPhoneAgeMs!!, m.endPhoneAgeMs!!) else 0L
            val map = ClockMap(
                m.bootId, m.startElapsedMs, m.endElapsedMs, so, eo, ps, pe, (m.offsetUncertaintyMs ?: 0L) + drift + phoneDrift + ageUnc,
                drift > MAX_WALL_JUMP_MS, stale, phoneDrift > MAX_PHONE_JUMP_MS,
            )
            map.fixedPhoneOffset = m.phoneOffsetMs
            return map
        }

        /** The map of [bootId] closest to [elapsedMs] that covers it, or null (a different boot never matches). */
        fun resolve(maps: List<ClockMap>, bootId: String, elapsedMs: Long): ClockMap? =
            maps.filter { it.covers(elapsedMs, bootId) }.minByOrNull { it.distance(elapsedMs) }
    }
}
