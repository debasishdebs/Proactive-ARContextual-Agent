package com.rokid.context.orchestration.proactive

enum class DrivingState { DRIVING, NOT_DRIVING, UNKNOWN }

/** The state plus a plain sentence for the Suggestions screen. Never carries a place, a coordinate or a raw reading. */
data class DrivingAssessment(val state: DrivingState, val reason: String) {
    /** Ruling 10: only a positive NOT_DRIVING lets the loop run; DRIVING and UNKNOWN both drop. */
    val blocks: Boolean get() = state != DrivingState.NOT_DRIVING
}

/** What a fresh positive reading says the wearer is doing. */
enum class Calm(val words: String) { STILL("still"), WALKING("walking"), SLOW("moving slowly") }

/** Which independent source gave a calm reading. NOT_DRIVING needs two different ones (Codex v3 r3 P2). */
enum class CalmSource { PHONE_ACTIVITY, PHONE_STEPS, SPEED, WATCH }

/** A MONOTONIC millisecond clock (production: `SystemClock.elapsedRealtime`). The tracker never reads the wall clock (Codex v3 r3 P1-3). */
fun interface Clock { fun elapsedMs(): Long }

/**
 * v3.0 T14 / ruling 10: the conservative driving state, derived only from signals the phone already collects (the context hub's evidence and
 * decision). Memory only, counts and times only: no coordinates, no raw readings. Every window is measured on a monotonic [Clock], so a wall-clock
 * jump (forward or back) never shortens protection. Readings come in as an AGE (how long ago the thing was observed), never as a wall time.
 *
 * - DRIVING: a vehicle was suspected or seen (vehicle activity / car link / watch or glasses vehicle hint / speed >= [FAST_MPS]) within [VEHICLE_WINDOW_MS].
 * - NOT_DRIVING: at least TWO different PHONE-side [CalmSource]s (the glasses' clock cannot prove absolute age, so a glasses hint is never calm evidence) gave a FRESH ([FRESH_MS]) calm reading, at least one of them a moving one (walking / slow: a seated
 *   driver is still, so still readings alone prove nothing), AND no vehicle suspicion in the last [VEHICLE_WINDOW_MS] AND the hub has been observed
 *   continuously for at least [VEHICLE_WINDOW_MS] (so the absence of suspicion is actually known).
 * - UNKNOWN otherwise (no data, one source, stale data, tracking only just started or not running). A vehicle always wins over a calm reading.
 *
 * [version] increases on EVERY update; [commitIfNotDriving] lets the writer decide and write atomically against updates.
 */
class DrivingTracker(private val clock: Clock = Clock { android.os.SystemClock.elapsedRealtime() }) {
    private var lastBeat: Long? = null
    private var monitoredSince: Long? = null
    private var suspectedNow = false
    private var lastVehicleAt: Long? = null
    private val calm = HashMap<CalmSource, Pair<Long, Calm>>()
    private var ver = 0L

    /** Bumped by every update (also ones that change nothing visible): a reader that saw version N and sees N again knows nothing arrived in between. */
    val version: Long @Synchronized get() = ver

    /** The hub is alive and just evaluated: [suspected] is its fused "a vehicle is suspected" flag. */
    @Synchronized fun heartbeat(suspected: Boolean) {
        val was = assessLocked().state
        try { heartbeatLocked(suspected) } finally { notifyIfBlocking(was) }
    }
    private fun heartbeatLocked(suspected: Boolean) {
        ver++
        val now = clock.elapsedMs()
        val b = lastBeat
        if (b == null || now - b > BEAT_GAP_MS || now < b) monitoredSince = now
        lastBeat = now; suspectedNow = suspected
        if (suspected) lastVehicleAt = now
    }

    /** Any vehicle evidence, observed [ageMs] ago (a negative age counts as now). */
    @Synchronized fun vehicle(ageMs: Long = 0) {
        val was = assessLocked().state
        try { vehicleLocked(ageMs) } finally { notifyIfBlocking(was) }
    }
    private fun vehicleLocked(ageMs: Long) {
        ver++
        val at = clock.elapsedMs() - ageMs.coerceAtLeast(0)
        lastVehicleAt = maxOf(lastVehicleAt ?: Long.MIN_VALUE, at)
    }

    /** A speed estimate in metres per second observed [ageMs] ago: fast is a vehicle, very slow is a calm reading, anything between says nothing. */
    @Synchronized fun speed(mps: Float, ageMs: Long = 0) {
        ver++
        if (!mps.isFinite() || mps < 0f) return
        if (mps >= FAST_MPS) vehicle(ageMs) else if (mps < SLOW_MPS) calm(CalmSource.SPEED, Calm.SLOW, ageMs)
    }

    /** A calm reading from [source], observed [ageMs] ago (a negative age counts as now). Only the newest reading of a source is kept. */
    @Synchronized fun calm(source: CalmSource, kind: Calm, ageMs: Long = 0) {
        val was = assessLocked().state
        try { calmLocked(source, kind, ageMs) } finally { notifyIfBlocking(was) }
    }
    private fun calmLocked(source: CalmSource, kind: Calm, ageMs: Long) {
        ver++
        val at = clock.elapsedMs() - ageMs.coerceAtLeast(0)
        val old = calm[source]
        if (old == null || at >= old.first) calm[source] = at to kind
    }

    /** v3.1 r2: called (under the tracker's lock: keep it cheap and non-blocking) when an update moved the state from NOT_DRIVING to DRIVING or UNKNOWN. The default tells the suggestion surfaces to re-derive at once. */
    @Volatile var onBlocking: () -> Unit = { SuggestionGateEvents.closed() }
    private fun notifyIfBlocking(was: DrivingState) { if (was == DrivingState.NOT_DRIVING && assessLocked().state != DrivingState.NOT_DRIVING) try { onBlocking() } catch (_: Exception) { } }

    @Synchronized fun assess(): DrivingAssessment = assessLocked()

    /**
     * The commit fence (Codex v3 r3 P1-2): under the tracker's lock, runs [write] only if [version] is still [expectedVersion] and the state is NOT_DRIVING;
     * nothing can be accepted between the check and the write. Returns false (and does not write) otherwise. [write] must not block or suspend.
     */
    @Synchronized fun commitIfNotDriving(expectedVersion: Long, write: () -> Unit): Boolean {
        if (ver != expectedVersion || assessLocked().state != DrivingState.NOT_DRIVING) return false
        write()
        return true
    }

    private fun assessLocked(): DrivingAssessment {
        val now = clock.elapsedMs()
        val v = lastVehicleAt
        if (v != null && now - v <= VEHICLE_WINDOW_MS) {
            val live = suspectedNow && lastBeat?.let { now - it <= BEAT_GAP_MS } == true
            return DrivingAssessment(DrivingState.DRIVING,
                if (live) "Driving: a vehicle is suspected now: suggestions are paused" else "Driving: a vehicle was detected ${minutes(now - v)} ago: suggestions are paused")
        }
        val fresh = calm.filter { now - it.value.first in 0..FRESH_MS }
        val beat = lastBeat; val since = monitoredSince
        if (fresh.isEmpty()) return unknown("no recent motion data")
        if (beat == null || since == null || now - beat > BEAT_GAP_MS || now < beat) return unknown("motion tracking is not running")
        if (now - since < VEHICLE_WINDOW_MS) return unknown("motion tracking only just started")
        if (fresh.size < 2) return unknown("calm evidence needs two phone motion sources")
        val kinds = fresh.values.map { it.second }.toSet()
        if (kinds.all { it == Calm.STILL }) return unknown("only still readings")
        return DrivingAssessment(DrivingState.NOT_DRIVING, "Not driving: ${fresh.size} motion sources agree (${kinds.sortedBy { it.ordinal }.joinToString(" and ") { it.words }})")
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
    }
}

/** The process-wide tracker: the context hub feeds it, the proactive gate and the Suggestions screen read it. Every read fails closed. */
object DrivingSignal {
    @Volatile var tracker: DrivingTracker = DrivingTracker()
    /** The version the next [commitIfNotDriving] must match; a throw is -1 (never matches). */
    fun version(): Long = try { tracker.version } catch (e: Exception) { -1L }
    /** Fails closed: false (and no write) on a throw, a changed version or any state but NOT_DRIVING. */
    fun commitIfNotDriving(expectedVersion: Long, write: () -> Unit): Boolean = try { tracker.commitIfNotDriving(expectedVersion, write) } catch (e: Exception) { false }
    fun assess(): DrivingAssessment = try { tracker.assess() } catch (e: Exception) { DrivingAssessment(DrivingState.UNKNOWN, "Unknown: the driving check failed: suggestions are paused") }
    /** The gate: true = drop. */
    fun blocks(): Boolean = assess().blocks
}
