package com.rokid.context.work.frames.place.replay

import com.rokid.context.memory.place.Geo
import com.rokid.context.work.frames.place.PlaceFix
import com.rokid.context.work.frames.place.PlaceOutcome

/**
 * Test-side what-ifs (plan section 6). Each changes exactly one thing AROUND the real sampler; none changes app code. When a fix is built for real its overlay is deleted and the same replay
 * must give the same verdict (the overlay is the executable spec). F5 is the [CaptionText] match stage.
 */
data class Overlays(val f1: Boolean = false, val f2Ms: Long? = null) {
    val label: String get() = when {
        this == HEAD -> "HEAD"
        this == ALL -> "ALL"
        else -> listOfNotNull(if (f1) "F1" else null, f2Ms?.let { "F2(${it / 60_000}min)" }).joinToString("+")
    }

    companion object {
        val HEAD = Overlays()
        val F1 = Overlays(f1 = true)
        fun f2(min: Long) = Overlays(f2Ms = min * 60_000)
        /** F1 + F2(10 min) (F3 and F4 are built into the sampler; + F5 is the CaptionText stage, chosen by the run). */
        val ALL = Overlays(f1 = true, f2Ms = 10 * 60_000)
        val SINGLES = listOf(F1, f2(10))
    }
}

/** F1: the pin-first card, a pure function evaluated at every poke that has a fix, whatever the sampler said. Two or more qualifying pins give `ambiguous(n)`, never a guess. */
class PinFirstRule(private val slackM: Double = 10.0, private val maxAccM: Float = 25f, private val visitMs: Long = 60 * 60_000L, private val leaveM: Double = 50.0, private val leaveMs: Long = 10 * 60_000L) {
    sealed class Decision(val text: String) {
        object None : Decision("-")
        data class Card(val pin: ReplayPin) : Decision("card ${pin.name}")
        data class Visit(val pin: ReplayPin) : Decision("visit")
        data class Ambiguous(val n: Int) : Decision("ambiguous($n)")
    }

    private class VisitState(val startedAt: Long, var outsideSince: Long? = null)
    private val visits = HashMap<String, VisitState>()

    fun decide(now: Long, fix: PlaceFix?, pins: List<ReplayPin>): Decision {
        if (fix != null) {
            for (p in pins) visits[p.id]?.let { v ->
                if (Geo.distanceM(fix.lat, fix.lon, p.lat, p.lon) > p.radiusM + leaveM) { v.outsideSince = v.outsideSince ?: now } else v.outsideSince = null
            }
        }
        visits.entries.removeAll { (_, v) -> now - v.startedAt >= visitMs || (v.outsideSince?.let { now - it >= leaveMs } == true) }
        if (fix == null || fix.ageMs !in 0..60_000 || fix.accuracyM.isNaN() || fix.accuracyM > maxAccM) return Decision.None
        val q = pins.filter { Geo.distanceM(fix.lat, fix.lon, it.lat, it.lon) + fix.accuracyM <= it.radiusM + slackM }
        if (q.isEmpty()) return Decision.None
        if (q.size >= 2) return Decision.Ambiguous(q.size)
        val p = q[0]
        if (visits.containsKey(p.id)) return Decision.Visit(p)
        visits[p.id] = VisitState(now)
        return Decision.Card(p)
    }
}
