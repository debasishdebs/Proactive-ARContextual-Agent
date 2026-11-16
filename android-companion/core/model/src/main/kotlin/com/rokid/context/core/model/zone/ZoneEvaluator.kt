package com.rokid.context.core.model.zone

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** OUTSIDE < APPROACH < INSIDE (the order is used: the overall state is the maximum). */
enum class ZoneState { OUTSIDE, APPROACH, INSIDE }

/** Why a zone reads INSIDE although no fix says so (fail closed). */
enum class ZoneReason { NONE, NO_FIX, STALE_FIX, LOCATION_OFF }

/** One location fix. [timeMs] is on the same monotonic clock as the `nowMs` given to the evaluator (the caller uses elapsed realtime). Never stored or logged. */
data class ZoneFix(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float)

/** [state] is the effective state (fail closed applied); [measured] is what the fixes say; [distanceM] is the distance to the box edge at the last fix (0 inside the box), null before any fix. */
data class ZoneStatus(
    val zoneId: String, val state: ZoneState, val measured: ZoneState, val reason: ZoneReason, val distanceM: Double?, val bufferM: Int,
    /** The monotonic time of the last fix that proved the phone NOT inside this zone (OUTSIDE or APPROACH); null = never since the evaluator started. The start of the uncertainty window. */
    val lastClearMs: Long? = null,
)

data class ZoneEvaluation(val zones: List<ZoneStatus>) {
    val overall: ZoneState = zones.maxOfOrNull { it.state } ?: ZoneState.OUTSIDE
    fun state(zoneId: String): ZoneState? = zones.firstOrNull { it.zoneId == zoneId }?.state
    /** Zones whose effective state is INSIDE: capture is paused for each. */
    val inside: List<ZoneStatus> get() = zones.filter { it.state == ZoneState.INSIDE }
}

/**
 * The pause-zone rules (spec 2026-11-16 §B2), pure and single-threaded (the caller serializes). Per ENABLED zone:
 * - **enter** INSIDE at once when the fix is within `bufferM` of the box edge; the fix's accuracy (at most [ENTRY_CREDIT_CAP_M]) counts against the user, so a vague fix near the zone enters;
 * - **leave** INSIDE only after [LEAVE_FIXES] consecutive fixes farther than `bufferM + [LEAVE_HYSTERESIS_M]` with accuracy <= [MAX_LEAVE_ACCURACY_M]; any other fix restarts the count;
 * - **APPROACH** while within `bufferM + [APPROACH_MARGIN_M]` (entered at once, left with the same hysteresis and count), else OUTSIDE.
 * Fail closed: a zone reads INSIDE when it has no fix yet (hub start, recovery via [restart], a zone just added with no usable last fix), when its state is >= APPROACH and the last fix
 * is older than [STALE_MS] (an OUTSIDE zone: older than [OUTSIDE_STALE_MS]), or when its state is >= APPROACH and location is off or revoked ([onLocationAvailable]). The fail-closed reading is
 * LATCHED: the zone's state becomes INSIDE and leaves it only through the normal exit (two qualifying fixes), so restoring location access or one vague far fix never clears it.
 * A fix older than [STALE_MS] on arrival, not strictly newer than the newest (a replayed fix never counts twice), or not finite is ignored.
 * Only the last fix is held in memory; the evaluator never stores or logs coordinates. An antimeridian-crossing box is not supported.
 */
class ZoneEvaluator {
    private class Z(val zone: PauseZone) {
        /** Starts INSIDE and unevaluated: no fix has proved anything yet (fail closed, latched). */
        var state = ZoneState.INSIDE
        var evaluated = false
        var outside = 0
        var distanceM: Double? = null
        var lastClearMs: Long? = null
        /** Why the zone was latched INSIDE with no fix saying so (stale, location off); cleared by the next fix. */
        var latchedBy = ZoneReason.NONE
    }

    private val zs = LinkedHashMap<String, Z>()
    private var last: ZoneFix? = null
    private var locationOk = true

    /** The enabled, valid zones; the state of a zone with the same geometry is kept, any other starts unevaluated and is judged by the last fix when that is still fresh. */
    fun setZones(zones: List<PauseZone>, nowMs: Long) {
        val keep = zones.filter { it.enabled && it.isValid() }.associateBy { it.id }
        zs.keys.retainAll(keep.keys)
        val fresh = mutableListOf<Z>()
        for ((id, zone) in keep) {
            val old = zs[id]
            if (old != null && old.zone == zone) continue
            zs[id] = Z(zone).also { fresh += it }
        }
        val f = last
        // A zone added while a fresh fix exists is judged by it at once (that fix already is the evidence; a far one needs no second).
        if (f != null && nowMs - f.timeMs <= STALE_MS) fresh.forEach { it.outside = LEAVE_FIXES - 1; apply(it, f) }
    }

    /** The hub (re)started or recovered: nothing is known any more. Every zone reads INSIDE (NO_FIX, latched) until two qualifying fixes say otherwise; the old fix is forgotten. */
    fun restart() {
        last = null
        zs.values.forEach { it.state = ZoneState.INSIDE; it.evaluated = false; it.outside = 0; it.distanceM = null; it.lastClearMs = null; it.latchedBy = ZoneReason.NONE }
    }

    val zoneCount: Int get() = zs.size

    /** Location access is on (true) or off/revoked (false). */
    fun onLocationAvailable(ok: Boolean, nowMs: Long? = null) {
        if (!ok && nowMs != null) latch(nowMs) // the loss is judged against the access it had; nothing restores a latched zone but fixes
        locationOk = ok
    }

    /** True when the fix was used. */
    fun onFix(fix: ZoneFix, nowMs: Long): Boolean {
        if (!fix.lat.isFinite() || !fix.lon.isFinite() || fix.lat !in -90.0..90.0 || fix.lon !in -180.0..180.0) return false
        if (nowMs - fix.timeMs > STALE_MS) return false
        if (last?.let { fix.timeMs <= it.timeMs } == true) return false
        latch(nowMs) // a gap that went stale before this fix latches first, so this fix counts as the first exit fix, not a free one
        last = fix
        zs.values.forEach { apply(it, fix) }
        return true
    }

    private fun apply(z: Z, fix: ZoneFix) {
        val d = distanceToBoxM(z.zone, fix.lat, fix.lon)
        z.distanceM = d
        z.evaluated = true
        z.latchedBy = ZoneReason.NONE
        val acc = if (fix.accuracyM.isNaN() || fix.accuracyM < 0f) Double.MAX_VALUE else fix.accuracyM.toDouble()
        val entryD = max(0.0, d - min(acc, ENTRY_CREDIT_CAP_M))
        val buffer = z.zone.bufferM.toDouble()
        val approach = buffer + APPROACH_MARGIN_M
        val good = acc <= MAX_LEAVE_ACCURACY_M
        when {
            entryD <= buffer -> { z.state = ZoneState.INSIDE; z.outside = 0 }
            z.state == ZoneState.INSIDE -> {
                if (good && d > buffer + LEAVE_HYSTERESIS_M) z.outside++ else z.outside = 0
                if (z.outside >= LEAVE_FIXES) { z.state = if (d <= approach) ZoneState.APPROACH else ZoneState.OUTSIDE; z.outside = 0 }
            }
            entryD <= approach -> { z.state = ZoneState.APPROACH; z.outside = 0 }
            z.state == ZoneState.APPROACH -> {
                if (good && d > approach + LEAVE_HYSTERESIS_M) z.outside++ else z.outside = 0
                if (z.outside >= LEAVE_FIXES) { z.state = ZoneState.OUTSIDE; z.outside = 0 }
            }
            z.state == ZoneState.OUTSIDE -> Unit
        }
        if (z.state != ZoneState.INSIDE) z.lastClearMs = fix.timeMs
    }

    private fun reasonOf(z: Z, nowMs: Long): ZoneReason {
        val f = last
        return when {
            f == null || !z.evaluated -> ZoneReason.NO_FIX
            z.state >= ZoneState.APPROACH && !locationOk -> ZoneReason.LOCATION_OFF
            nowMs - f.timeMs > (if (z.state >= ZoneState.APPROACH) STALE_MS else OUTSIDE_STALE_MS) -> ZoneReason.STALE_FIX
            else -> z.latchedBy // INSIDE by a fix, or latched earlier by a stale reading / lost access (NONE for a plain fix)
        }
    }

    /** Latches every zone that reads fail-closed right now as INSIDE with a fresh exit count. */
    private fun latch(nowMs: Long) {
        for (z in zs.values) if (reasonOf(z, nowMs).let { it == ZoneReason.LOCATION_OFF || it == ZoneReason.STALE_FIX }) { z.latchedBy = reasonOf(z, nowMs); z.state = ZoneState.INSIDE; z.outside = 0 }
    }

    fun evaluate(nowMs: Long): ZoneEvaluation {
        latch(nowMs)
        return ZoneEvaluation(zs.values.map { z ->
            val reason = reasonOf(z, nowMs)
            ZoneStatus(z.zone.id, if (reason == ZoneReason.NONE) z.state else ZoneState.INSIDE, z.state, reason, z.distanceM, z.zone.bufferM, z.lastClearMs)
        })
    }

    companion object {
        const val STALE_MS = 3 * 60_000L
        /** An OUTSIDE zone is judged by a fix this old at most (the far cadence is 5 min); older and nothing proves we are still outside. */
        const val OUTSIDE_STALE_MS = 15 * 60_000L
        const val LEAVE_HYSTERESIS_M = 200.0
        const val LEAVE_FIXES = 2
        const val MAX_LEAVE_ACCURACY_M = 100.0
        const val ENTRY_CREDIT_CAP_M = 1_000.0
        /** APPROACH reaches this far beyond the buffer (also where the high-accuracy cadence starts). */
        const val APPROACH_MARGIN_M = 2_000.0

        /** Metres from the point to the nearest point of the box (0 inside it). */
        fun distanceToBoxM(zone: PauseZone, lat: Double, lon: Double): Double {
            val cLat = lat.coerceIn(zone.south, zone.north)
            val cLon = lon.coerceIn(zone.west, zone.east)
            return haversineM(lat, lon, cLat, cLon)
        }

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(sqrt(a))
        }
    }
}

enum class FixPriority { HIGH, BALANCED }

/** How the location shell asks for fixes. */
data class FixCadence(val priority: FixPriority, val intervalMs: Long)

/** The fix rate follows the distance to the nearest zone (spec §B2). */
object TripCadence {
    const val FAR_M = 25_000.0
    const val FAR_INTERVAL_MS = 5 * 60_000L
    const val NEAR_INTERVAL_MS = 30_000L
    const val HIGH_INTERVAL_MS = 10_000L
    val HIGH = FixCadence(FixPriority.HIGH, HIGH_INTERVAL_MS)

    /** Null = no zone exists, so no fix is wanted. HIGH 10 s with no fix yet, a fail-closed or >= APPROACH zone, or within buffer + 2 km of a zone; BALANCED 30 s within 25 km; else BALANCED 5 min. */
    fun select(e: ZoneEvaluation): FixCadence? {
        if (e.zones.isEmpty()) return null
        var out = FixCadence(FixPriority.BALANCED, FAR_INTERVAL_MS)
        for (z in e.zones) {
            val d = z.distanceM
            if (d == null || z.state != ZoneState.OUTSIDE || z.measured != ZoneState.OUTSIDE || d <= z.bufferM + ZoneEvaluator.APPROACH_MARGIN_M) return HIGH
            if (d <= FAR_M) out = FixCadence(FixPriority.BALANCED, NEAR_INTERVAL_MS)
        }
        return out
    }
}
