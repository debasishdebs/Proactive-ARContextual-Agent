package com.rokid.context.work.frames.place

import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.PlaceEntity

/**
 * F1: the pin-first (location-first) card for the user's OWN pins. A pure decision over the fix the sampler already took: no model call, no photo, no network. The sampler publishes the card and starts the visit.
 *
 * Eligible pins have a radius of at least [MIN_RADIUS_M] (the small pins are object or reference pins and never make a location card). The fix must be fresh (not a reused one), at most [MAX_AGE_MS] old and
 * at most [MAX_ACC_M] inaccurate. One pin inside and no other neighbour gives a single card; when more pins are close (a room next to a room) the answer is a cluster: the model path may still confirm one room,
 * and only when it does not is the honest "several pins" card shown. Never a single guessed room.
 */
object PinFirst {
    const val MIN_RADIUS_M = 15
    const val MAX_AGE_MS = 60_000L
    const val MAX_ACC_M = 25f
    const val SLACK_M = 10.0
    const val NEIGHBOUR_M = 30.0
    const val LEAVE_EXTRA_M = 50.0
    const val LEAVE_MS = 10 * 60_000L
    const val VISIT_MAX_MS = 3 * 3_600_000L

    sealed class Decision(val token: String) {
        object Off : Decision("off")
        object None : Decision("none")
        object SmallRadius : Decision("small_radius")
        object NotFresh : Decision("not_fresh")
        object Visit : Decision("visit")
        /** One pin inside and no other neighbour. */
        data class Single(val pin: PlaceEntity, val distanceM: Double) : Decision("card")
        /** Some pin inside, others close: [members] (nearest first) includes [inside] (the nearest pin inside). */
        data class Cluster(val inside: PlaceEntity, val members: List<PlaceEntity>) : Decision("cluster(${members.size})")
    }

    /**
     * [pins] are the user pins among the candidates with their centre distance from the fix. [fixSrc] is `fresh` or `reused`. [visits] is read as of [now].
     */
    fun decide(fix: PlaceFix?, fixSrc: String, pins: List<Pair<PlaceEntity, Double>>, visits: PinVisits, now: Long): Decision {
        if (fix == null || pins.isEmpty()) return Decision.None
        if (fixSrc != "fresh" || fix.ageMs !in 0..MAX_AGE_MS || fix.accuracyM.isNaN() || fix.accuracyM > MAX_ACC_M) return Decision.NotFresh
        val acc = fix.accuracyM.toDouble()
        fun inside(p: Pair<PlaceEntity, Double>) = p.second + acc <= p.first.radiusM + SLACK_M
        val eligible = pins.filter { it.first.radiusM >= MIN_RADIUS_M }.sortedWith(compareBy({ it.second - it.first.radiusM }, { it.first.id }))
        val insideNow = eligible.filter(::inside)
        if (insideNow.isEmpty()) return if (pins.any { it.first.radiusM < MIN_RADIUS_M && inside(it) }) Decision.SmallRadius else Decision.None
        val members = eligible.filter { it.second - it.first.radiusM <= acc + NEIGHBOUR_M }
        val ids = members.map { it.first.id }
        if (visits.has(ids, now)) return Decision.Visit
        return if (insideNow.size == 1 && members.size == 1) Decision.Single(insideNow[0].first, insideNow[0].second) else Decision.Cluster(insideNow[0].first, members.map { it.first })
    }
}

/**
 * The visits of pin-first cards, in memory only (never stored, never logged). A visit starts at the card and ends after [PinFirst.LEAVE_MS] outside `r + 50 m` of every member, or after [PinFirst.VISIT_MAX_MS].
 * Within a visit there is no second card for a pin, or for a cluster that contains one of its pins. A member of unknown position (seeded after a restart) only ends by the time cap.
 */
class PinVisits {
    data class Spot(val id: String, val lat: Double, val lon: Double, val radiusM: Int)
    private class Visit(val spots: List<Spot>, val startedAt: Long, var outsideSince: Long? = null)
    private val visits = ArrayList<Visit>()

    fun begin(spots: List<Spot>, now: Long) = synchronized(visits) { visits += Visit(spots, now) }

    /** Moves the visits' clocks with a fix: outside every known member starts the leave timer, coming back inside resets it. */
    fun update(lat: Double, lon: Double, now: Long) = synchronized(visits) {
        for (v in visits) {
            val known = v.spots.filter { !it.lat.isNaN() }
            if (known.isEmpty()) continue
            val outside = known.all { Geo.distanceM(lat, lon, it.lat, it.lon) > it.radiusM + PinFirst.LEAVE_EXTRA_M }
            if (outside) { if (v.outsideSince == null) v.outsideSince = now } else v.outsideSince = null
        }
        expire(now)
    }

    /** True when a live visit holds any of [ids]. */
    fun has(ids: Collection<String>, now: Long): Boolean = synchronized(visits) { expire(now); visits.any { v -> v.spots.any { it.id in ids } } }

    fun clear() = synchronized(visits) { visits.clear() }

    private fun expire(now: Long) { visits.removeAll { now - it.startedAt >= PinFirst.VISIT_MAX_MS || (it.outsideSince?.let { o -> now - o >= PinFirst.LEAVE_MS } == true) } }
}

/** The label of a cluster card: what the member names share, else the common site, else "Your pins". Pure. */
object ClusterLabel {
    const val FALLBACK = "Your pins"
    private val STOP = setOf("museum", "gallery", "room", "hall", "fort", "show", "light", "sound", "kitchen", "bar", "the", "and", "of")

    private fun tokens(name: String): List<String> {
        val spaced = name.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        return spaced.split(Regex("[^A-Za-z0-9]+")).map { it.lowercase() }.filter { t -> t.count { it.isLetter() } >= 3 && t !in STOP }
    }

    /** [names] nearest first; [parentNames] the site each member hangs under (null = none). */
    fun of(names: List<String>, parentNames: List<String?> = emptyList()): String {
        val per = names.map { tokens(it).toSet() }
        val count = HashMap<String, Int>()
        for (s in per) for (t in s) count[t] = (count[t] ?: 0) + 1
        val shared = count.filter { (_, c) -> c >= 2 && c * 2 > names.size }.keys
        if (shared.isNotEmpty()) {
            val ordered = LinkedHashSet<String>()
            for (n in names) for (t in tokens(n)) if (t in shared) ordered += t
            return ordered.joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        }
        val parent = parentNames.takeIf { it.size == names.size && it.isNotEmpty() && it.all { p -> !p.isNullOrBlank() } }?.map { it!! }?.distinct()
        if (parent != null && parent.size == 1) return parent[0]
        return FALLBACK
    }
}
