package com.rokid.context.canvas.trace

import com.rokid.context.capture.trace.ClockMap
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.memory.db.TraceClock

/**
 * The interval of one trace on the PHONE clock (spec 7.2), from the trace's own [ClockMap]; the trace format is frozen, only the phone-side row is derived. Mapped (both ends have a
 * phone time): [TraceClock.OFFSET_ESTIMATED] with the map's uncertainty, which includes drift and sample ages of up to 120 s and is NEVER capped. Otherwise (no phone offset or samples,
 * a wall or phone clock jump, an elapsed time the map does not cover) the glasses wall times are kept only as coarse evidence and the trace is [TraceClock.UNMAPPED], i.e. unbounded.
 */
object TracePhoneClock {
    data class Interval(val phoneStartMs: Long, val phoneEndMs: Long?, val clockQuality: String, val uncertaintyMs: Long)

    fun of(m: Manifest): Interval {
        val map = ClockMap.of(m)
        val s = map.phoneWall(m.startElapsedMs, m.bootId)
        val e = map.phoneWall(m.endElapsedMs, m.bootId)
        if (s == null || e == null) return Interval(m.startWallMs, m.endWallMs, TraceClock.UNMAPPED, TraceClock.UNBOUNDED_MS)
        return Interval(minOf(s, e), maxOf(s, e), TraceClock.OFFSET_ESTIMATED, map.uncertaintyMs.coerceIn(0, TraceClock.UNBOUNDED_MS))
    }
}
