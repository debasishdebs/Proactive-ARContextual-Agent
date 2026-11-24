package com.rokid.context.work.frames.place.replay

import com.rokid.context.memory.place.Geo
import com.rokid.context.work.frames.place.PlaceFix
import com.rokid.context.work.frames.place.PlaceSampler

enum class PosSrc { CAUSAL, INTERP, NEAREST, ASSUMED }

/** A position and where it came from. [dtMs] = the time distance to the nearest real fix (0 for ASSUMED). */
data class TrackPos(val lat: Double, val lon: Double, val src: PosSrc, val dtMs: Long, val fixSrc: String) {
    val assumed get() = src == PosSrc.ASSUMED
    fun label() = if (assumed) "ASSUMED" else "$fixSrc/${src.name} ${dtMs / 1000}s"
}

/** The media-GPS track (plan 4.1). null from [near] means UNKNOWN: a position is never invented (R4). */
class ReplayTrack(fixes: List<ReplayFix>) {
    private val f = fixes.sortedBy { it.tMs }

    /** The newest fix at or before [t] (its age is `t - fix.tMs`); never a future fix. */
    fun causal(t: Long): TrackPos? = before(t)?.let { TrackPos(it.lat, it.lon, PosSrc.CAUSAL, t - it.tMs, it.src) }

    /** Interpolation only with a fix within [nMs] on both sides, <= 200 m apart and <= 2 m/s; else the single side within [nMs]; else null (UNKNOWN). */
    fun near(t: Long, nMs: Long = FIVE_MIN): TrackPos? {
        val b = before(t); val a = after(t)
        val bOk = b != null && t - b.tMs <= nMs; val aOk = a != null && a.tMs - t <= nMs
        if (bOk && aOk) {
            b!!; a!!
            if (a.tMs == b.tMs) return TrackPos(b.lat, b.lon, PosSrc.INTERP, 0, b.src)
            val d = Geo.distanceM(b.lat, b.lon, a.lat, a.lon)
            val speed = d / ((a.tMs - b.tMs) / 1000.0)
            if (d > MAX_INTERP_M || speed > MAX_SPEED_MS) return null
            val k = (t - b.tMs).toDouble() / (a.tMs - b.tMs)
            return TrackPos(b.lat + (a.lat - b.lat) * k, b.lon + (a.lon - b.lon) * k, PosSrc.INTERP, minOf(t - b.tMs, a.tMs - t), if (b.src == a.src) b.src else "MIXED")
        }
        if (bOk) return TrackPos(b!!.lat, b.lon, PosSrc.NEAREST, t - b.tMs, b.src)
        if (aOk) return TrackPos(a!!.lat, a.lon, PosSrc.NEAREST, a.tMs - t, a.src)
        return null
    }

    /** What GPS backfill may use: the track only, never an ASSUMED position (plan 9). */
    fun backfill(t: Long, nMs: Long = FIVE_MIN): TrackPos? = near(t, nMs)

    private fun before(t: Long) = f.lastOrNull { it.tMs <= t }
    private fun after(t: Long) = f.firstOrNull { it.tMs >= t }

    companion object {
        const val FIVE_MIN = 5 * 60_000L
        const val MAX_INTERP_M = 200.0
        const val MAX_SPEED_MS = 2.0
    }
}

/** Plan 4.2: how the fix handed to the sampler is made from the track. */
sealed class FixScenario(val label: String) {
    object AsRecorded : FixScenario("AS_RECORDED")
    object Outdoor10 : FixScenario("OUTDOOR_10M")
    object Indoor60 : FixScenario("INDOOR_60M")
    object Stale : FixScenario("STALE")
    /** H4: "some good fixes, mostly bad": a good fix (acc 15, its real age) when a media fix lies at most 60 s before t (causal); else near(t, N) at acc 60 and age 3 s; else UNKNOWN. */
    object IndoorMediaGood : FixScenario("INDOOR_MEDIA_GOOD") { const val GOOD_MAX_AGE_MS = 60_000L }
    /** Antera / Ramoji style spans (truth testable = assumed-fix-only): the target's centre, labelled ASSUMED on every row; other frames use near(t, N) at [accM]. */
    data class AssumedPin(val accM: Float) : FixScenario("ASSUMED_PIN_${accM.toInt()}M")
}

/** What the fix lambda produced for one attempt (kept for the report row). */
data class FixResult(val fix: PlaceFix?, val pos: TrackPos?, val accM: Float, val f2: Boolean) {
    val assumed get() = pos?.assumed == true
}

class ReplayFixSource(private val bundle: ReplayBundle, private val track: ReplayTrack, private val scenario: FixScenario, private val nMs: Long = ReplayTrack.FIVE_MIN) {
    /** The fix for the frame [f] at replay time [now]. */
    fun fixAt(now: Long, f: ReplayFrame?): FixResult {
        val t = f?.createdAt ?: now
        var pos: TrackPos?; val acc: Float; var age: Long
        when (scenario) {
            FixScenario.AsRecorded -> { pos = track.causal(t); acc = 15f; age = pos?.dtMs ?: 0L }
            FixScenario.Outdoor10 -> { pos = track.near(t, nMs); acc = 10f; age = 3_000 }
            FixScenario.Indoor60 -> { pos = track.near(t, nMs); acc = 60f; age = 3_000 }
            FixScenario.Stale -> { pos = track.near(t, nMs); acc = 10f; age = 61_000 }
            FixScenario.IndoorMediaGood -> {
                val c = track.causal(t)
                if (c != null && c.dtMs <= FixScenario.IndoorMediaGood.GOOD_MAX_AGE_MS) { pos = c; acc = 15f; age = c.dtMs } else { pos = track.near(t, nMs); acc = 60f; age = 3_000 }
            }
            is FixScenario.AssumedPin -> { pos = assumedCentre(t) ?: track.near(t, nMs); acc = scenario.accM; age = 3_000 }
        }
        if (pos != null && scenario == FixScenario.Stale) age = maxOf(61_000L, pos.dtMs)
        if (pos == null) return FixResult(null, null, acc, false)
        return FixResult(PlaceFix(pos.lat, pos.lon, age, acc), pos, acc, false)
    }

    private fun assumedCentre(t: Long): TrackPos? {
        val span = bundle.truth.firstOrNull { t in it && it.assumedFixOnly } ?: return null
        val name = span.targetName ?: return null
        val c = bundle.pins.firstOrNull { it.name == name }?.let { it.lat to it.lon } ?: bundle.sites.firstOrNull { it.name == name }?.let { it.lat to it.lon } ?: return null
        return TrackPos(c.first, c.second, PosSrc.ASSUMED, 0, "-")
    }
}
