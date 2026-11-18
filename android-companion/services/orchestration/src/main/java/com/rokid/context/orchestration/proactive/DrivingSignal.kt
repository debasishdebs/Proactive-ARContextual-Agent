package com.rokid.context.orchestration.proactive

enum class DrivingState { DRIVING, NOT_DRIVING, UNKNOWN }

/** The state plus a plain sentence for the Suggestions screen. Never carries a place, a coordinate or a raw reading. */
data class DrivingAssessment(val state: DrivingState, val reason: String) {
    /** Ruling 10: only a positive NOT_DRIVING lets the loop run; DRIVING and UNKNOWN both drop. */
    val blocks: Boolean get() = state != DrivingState.NOT_DRIVING
}

/** What a fresh positive reading says the wearer is doing. */
enum class Calm(val words: String) { STILL("still"), WALKING("walking"), SLOW("moving slowly") }

/**
 * v3.0 T14 / ruling 10: the conservative driving state, derived only from signals the phone already collects (the context hub's evidence and
 * decision). Memory only, counts and times only: no coordinates, no raw readings.
 *
 * - DRIVING: a vehicle was suspected or seen (vehicle activity / car link / watch or glasses vehicle / speed >= [FAST_MPS]) within [VEHICLE_WINDOW_MS].
 * - NOT_DRIVING: a FRESH ([FRESH_MS]) positive still / walking / slow reading AND no vehicle suspicion in the last [VEHICLE_WINDOW_MS] AND the hub
 *   has been observed continuously for at least [VEHICLE_WINDOW_MS] (so the absence of suspicion is actually known).
 * - UNKNOWN otherwise (no data, stale data, tracking only just started or not running). A vehicle always wins over a calm reading.
 */
class DrivingTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private class Run(var lastAt: Long, var since: Long)

    private var lastBeat: Long? = null
    private var monitoredSince: Long? = null
    private var suspectedNow = false
    private var lastVehicleAt: Long? = null
    private val calm = HashMap<Calm, Run>()

    /** The hub is alive and just evaluated: [suspected] is its fused "a vehicle is suspected" flag. */
    @Synchronized fun heartbeat(suspected: Boolean) {
        val now = clock()
        val b = lastBeat
        if (b == null || now - b > BEAT_GAP_MS || now < b) monitoredSince = now
        lastBeat = now; suspectedNow = suspected
        if (suspected) lastVehicleAt = now
    }

    /** Any vehicle evidence, observed at [atMs] (a future time counts as now). */
    @Synchronized fun vehicle(atMs: Long) {
        val at = minOf(atMs, clock())
        lastVehicleAt = maxOf(lastVehicleAt ?: Long.MIN_VALUE, at)
    }

    /** A speed estimate in metres per second: fast is a vehicle, very slow is a calm reading, anything between says nothing. */
    @Synchronized fun speed(atMs: Long, mps: Float) {
        if (!mps.isFinite() || mps < 0f) return
        if (mps >= FAST_MPS) vehicle(atMs) else if (mps < SLOW_MPS) calm(atMs, Calm.SLOW)
    }

    @Synchronized fun calm(atMs: Long, kind: Calm) {
        val now = clock()
        val at = minOf(atMs, now)
        val r = calm[kind]
        if (r == null || at - r.lastAt > FRESH_MS) { calm[kind] = Run(at, at); return }
        if (at > r.lastAt) r.lastAt = at
    }

    @Synchronized fun assess(): DrivingAssessment {
        val now = clock()
        val v = lastVehicleAt
        if (v != null && now - v <= VEHICLE_WINDOW_MS) {
            val live = suspectedNow && lastBeat?.let { now - it <= BEAT_GAP_MS } == true
            return DrivingAssessment(DrivingState.DRIVING,
                if (live) "Driving: a vehicle is suspected now: suggestions are paused" else "Driving: a vehicle was detected ${minutes(now - v)} ago: suggestions are paused")
        }
        val fresh = calm.entries.filter { now - it.value.lastAt in 0..FRESH_MS }.maxByOrNull { it.value.lastAt }
        val beat = lastBeat; val since = monitoredSince
        if (fresh == null) return unknown("no recent motion data")
        if (beat == null || since == null || now - beat > BEAT_GAP_MS || now < beat) return unknown("motion tracking is not running")
        if (now - since < VEHICLE_WINDOW_MS) return unknown("motion tracking only just started")
        val run = fresh.value
        return DrivingAssessment(DrivingState.NOT_DRIVING, "Not driving: ${fresh.key.words} for ${minutes(run.lastAt - run.since).let { if (it == "under 1 min") "1 min" else it }}")
    }

    private fun unknown(why: String) = DrivingAssessment(DrivingState.UNKNOWN, "Unknown: $why: suggestions are paused")

    private fun minutes(ms: Long): String { val m = ms.coerceAtLeast(0) / 60_000L; return if (m < 1) "under 1 min" else "$m min" }

    companion object {
        const val FRESH_MS = 2 * 60_000L
        const val VEHICLE_WINDOW_MS = 10 * 60_000L
        const val BEAT_GAP_MS = 60_000L
        /** About 25 km/h, the fusion's own "fast" threshold, and about 7 m/s as ruled. */
        const val FAST_MPS = 7f
        const val SLOW_MPS = 2f
        /** The glasses' share of the last five minutes spent still that counts as "enough coverage". */
        const val STILL_FRAC = 0.85f
    }
}

/** The process-wide tracker: the context hub feeds it, the proactive gate and the Suggestions screen read it. Every read fails closed. */
object DrivingSignal {
    @Volatile var tracker: DrivingTracker = DrivingTracker()
    fun assess(): DrivingAssessment = try { tracker.assess() } catch (e: Exception) { DrivingAssessment(DrivingState.UNKNOWN, "Unknown: the driving check failed: suggestions are paused") }
    /** The gate: true = drop. */
    fun blocks(): Boolean = assess().blocks
}
