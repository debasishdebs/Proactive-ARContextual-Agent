package com.rokid.context.work.frames.place.replay

import com.rokid.context.memory.place.CellOutcome
import com.rokid.context.memory.place.Geo
import com.rokid.context.memory.place.PlaceCandidates
import com.rokid.context.memory.place.PlaceEntity
import com.rokid.context.work.frames.place.PlaceFrame
import com.rokid.context.work.frames.place.PlaceFrameStore

/**
 * The frame store of the replay: the same query as `FrameMetaDao.newestDwell` evaluated as of replay time [now] (plan 4.3): the dwell row with the greatest
 * `createdAt` in `[since, now]`, ties by frameId, not tombstoned at [now]. The capture time is `capturedAt`.
 */
class ReplayFrameStore(frames: List<ReplayFrame>, private val now: () -> Long) : PlaceFrameStore {
    private val dwell = frames.filter { it.dwell }.sortedWith(compareBy({ it.createdAt }, { it.frameId }))
    private val byId = frames.associateBy { it.frameId }
    /** The frame the sampler was last given (reset by the runner before each poke). */
    var lastReturned: ReplayFrame? = null

    /** The newest dwell frame in `[since, now]`, without recording it. */
    fun peek(since: Long): ReplayFrame? {
        val t = now()
        var lo = 0; var hi = dwell.size // first index with createdAt > t
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (dwell[mid].createdAt <= t) lo = mid + 1 else hi = mid }
        var i = lo - 1
        while (i >= 0 && dwell[i].createdAt >= since) { if (!tombstonedNow(dwell[i])) return dwell[i]; i-- }
        return null
    }

    override suspend fun newestDwell(sinceReceivedMs: Long): PlaceFrame? = peek(sinceReceivedMs)?.also { lastReturned = it }?.let { PlaceFrame(it.frameId, it.capturedAt) }
    override suspend fun tombstoned(frameId: String): Boolean = byId[frameId]?.let { tombstonedNow(it) } ?: false
    private fun tombstonedNow(f: ReplayFrame) = f.tombAt != -1L && f.tombAt <= now()
}

enum class Activation {
    /** A pin exists from its recorded creation time (LOG / INFERRED); pins of UNKNOWN creation time are always on, flagged. */
    AS_CREATED,
    /** Every pin exists for the whole trip: the "next visit" question. */
    ALWAYS,
}

/** `PlacePack.candidates` without Room: the active pins (and, for calibration, the ASSUMED sites), cut by the real [Geo]. */
class ReplayPinPack(private val bundle: ReplayBundle, private val activation: Activation, private val sitesOn: Boolean, private val now: () -> Long) {
    fun activePins(at: Long = now()): List<ReplayPin> = bundle.pins.filter { activation == Activation.ALWAYS || it.activeFrom < 0 || it.activeFrom <= at }

    private fun entity(p: ReplayPin) = PlaceEntity(p.id, p.parentId, p.name, p.kind, p.lat, p.lon, p.radiusM, "", "user", "user", 0, false, true, 0, 0)
    private fun entity(s: ReplaySite) = PlaceEntity(s.id, null, s.name, s.kind, s.lat, s.lon, s.radiusM, "", "assumed", "assumed", 0, false, false, 0, 0)

    @Suppress("UNUSED_PARAMETER")
    suspend fun candidates(lat: Double, lon: Double, networkUp: Boolean): PlaceCandidates {
        val pins = activePins().map { entity(it) }
        val all = pins + if (sitesOn) bundle.sites.map { entity(it) } else emptyList()
        val hits = Geo.candidates(all, lat, lon).map { it.place }
        val expanded = if (hits.isEmpty()) hits else Geo.expandSubPlaces(hits, pins.filter { it.parentId != null })
        return PlaceCandidates(expanded, CellOutcome.FRESH)
    }

    /** The name of a place id of this pack (for the report). */
    fun nameOf(id: String): String? = bundle.pins.firstOrNull { it.id == id }?.name ?: bundle.sites.firstOrNull { it.id == id }?.name
}
