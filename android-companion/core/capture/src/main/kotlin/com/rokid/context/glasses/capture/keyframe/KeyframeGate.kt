package com.rokid.context.glasses.capture.keyframe

import com.rokid.context.core.model.MotionState

/** A copy of one camera frame (640x480 NV21, about 460 KB), kept only while it is a window candidate. */
class Nv21Frame(val w: Int, val h: Int, val rotation: Int, val data: ByteArray)

/** One sample: [seq] numbers it within the session, [thumb] is what the hashes see, [pixels] is what gets encoded if it wins. */
class Candidate(val seq: Long, val thumb: Thumb, val pixels: Nv21Frame)

sealed interface GateResult {
    /** [candidate] is the winner: G3 encodes its pixels, never a later frame. */
    data class Keep(val candidate: Candidate, val dhash: Long, val phash: Long, val blur: Float, val dwell: Boolean, val motion: MotionState = MotionState.STILL) : GateResult
    /** [reason] is BLUR | DUPLICATE | THROTTLED | WINDOW | CAPPED. */
    data class Skip(val reason: String) : GateResult
}

/**
 * The plan-tunable gate values (slice 3a, S68). The defaults are today's constants, so no plan means no change. [ringCompareAll] true
 * = a sample must differ from each of the last 5 keyframes (today); false = from the newest one only.
 */
data class GateParams(val hamming: Int = KeyframeGate.HAMMING, val bigChange: Int = KeyframeGate.BIG_CHANGE, val ringCompareAll: Boolean = true)

/** Sees every frame the gate scored, before the keep decision (the IMU/replay recorders). [d] and [p] are 0 when [passedBlur] is false. */
fun interface ScoreListener { fun onScored(seq: Long, d: Long, p: Long, blur: Float, passedBlur: Boolean) }

/** The last-5 keyframe ring and the last keep time, persisted so a service restart does not re-keep the same scene. */
interface GateStore {
    fun lastKeyframes(): List<Pair<Long, Long>>
    fun push(d: Long, p: Long)
    fun lastKeptAt(): Long
    fun setLastKeptAt(t: Long)
}

/**
 * Keyframe test (spec section P1.1): a sample is NEW when min(Hamming dHash, Hamming pHash) > [HAMMING] against each of the
 * last 5 keyframes. A new scene may follow the previous keyframe after 0.5 s, anything else after 1.2 s. While moving,
 * samples are collected in windows of up to 1.5 s (at most 3 kept, losers released at once) and the sharpest wins.
 * A dwell (glance) sample compares to the whole ring like any other, and still needs blur >= [BLUR_FLOOR].
 * Rate cap (battery wave A3, spec section 5): with a `capPerMin`, at most that many keeps per rolling minute, up to twice
 * that many only when a glance (dwell) frame differs from every ring keyframe by at least [BIG_CHANGE] of 64 bits (never with [hardCap], used while resting); non-glance keeps stop at cap - 1 (at least 1) with no bonus; forced keeps are exempt.
 */
class KeyframeGate(private val store: GateStore, private val clock: () -> Long, private val params: () -> GateParams = { GateParams() }) {
    /** Keeps the trailing-lambda form `KeyframeGate(store) { now }` (the clock) working next to the params provider. */
    constructor(store: GateStore, clock: () -> Long) : this(store, clock, { GateParams() })

    private class Scored(val c: Candidate, val d: Long, val p: Long, val blur: Float, val motion: MotionState)

    // Thread safety: offer() runs on the camera analysis executor, flushWindow() on the session timer or dwell
    // coroutine, recordKeep() on whichever thread handles "Capture now". They all share the window and the store, so each
    // public entry point is @Synchronized (cheap: hashing is the only real work and it is a few ms).
    private val window = ArrayList<Scored>(MAX_CANDIDATES + 1)
    private var windowStart = 0L
    private var lastScoredHashes: Pair<Long, Long>? = null
    /** Times of every keep (forced included) in the last 60 s. */
    private val keptTimes = ArrayDeque<Long>()
    @Volatile private var duplicates = 0
    @Volatile private var capped = 0

    /** DUPLICATE and CAPPED skips since the previous call (the heartbeat); resets both. */
    @Synchronized fun takeSkipCounts(): Pair<Int, Int> = (duplicates to capped).also { duplicates = 0; capped = 0 }

    @Volatile private var scoreListener: ScoreListener? = null
    private var lastOfferNow = 0L

    /** Null removes it. Called on the offering thread, under the gate lock: keep it cheap and never call back into the gate. */
    fun setScoreListener(l: ScoreListener?) { scoreListener = l }

    /** The current new-scene threshold (the plan's, else [HAMMING]); the session's same-scene backoff uses the same one. */
    fun hammingNow(): Int = params().hamming

    /** The gate values in force now (the IMU trace records them at its start). */
    fun paramsNow(): GateParams = params()

    /**
     * The clock reading [offer] took for the sample it is scoring, or [flushWindow] took for its decision. Valid only on the calling thread, from inside a
     * [ScoreListener] call (it runs under the gate lock, in [offer]) or right after a [flushWindow] that returned a result, with nothing else touching the gate
     * in between (the session holds its own lock): it is the `sc.nowMs` / `fl.nowMs` the trace records, so replay sees the exact decision time.
     */
    fun lastOfferNowMs(): Long = lastOfferNow

    /** What the IMU trace needs of the gate at a clean start: the ring (newest last), the last keep time (null = never), the keeps in the last minute (ascending) and whether the window is empty. */
    class TraceState(val ring: List<Pair<Long, Long>>, val lastKeptAt: Long?, val keptAt: List<Long>, val windowEmpty: Boolean)

    @Synchronized
    fun traceState(now: Long): TraceState = TraceState(
        store.lastKeyframes(), store.lastKeptAt().takeIf { it > 0L },
        keptTimes.filter { now - it in 0 until CAP_WINDOW_MS }.sorted(), window.isEmpty(),
    )

    /** True when no scored candidate is pending in the window (a clean trace boundary). */
    @Synchronized fun windowEmpty(): Boolean = window.isEmpty()

    /** (dHash, pHash) of the last frame that passed the blur check, or null before any. */
    @Synchronized fun lastHashes(): Pair<Long, Long>? = lastScoredHashes

    /**
     * When a timer check may power up the camera again: the time the oldest keep leaves the rolling minute, once the minute
     * already holds the non-glance keep budget (max(1, cap - 1), the same limit [finish] applies); null while budget remains.
     */
    @Synchronized
    fun timerHoldUntil(capPerMin: Int, now: Long): Long? {
        if (capPerMin == Int.MAX_VALUE) return null
        val live = keptTimes.filter { now - it in 0 until CAP_WINDOW_MS }
        return if (live.size >= maxOf(1, capPerMin - 1)) live.min() + CAP_WINDOW_MS else null
    }

    /** Candidates currently held (their pixel copies stay alive). */
    @Synchronized internal fun retainedCount(): Int = window.size

    /**
     * [force] is "Capture now": the frame is kept whatever the ring and throttle say (the blur floor still applies),
     * recorded like any keep so the next sample of the same scene is a duplicate, and any pending window is released.
     * A STILL sample first closes the pending moving window (its winner is never a dwell keep); if that window keeps a
     * frame, that is the result (the still sample could not be kept in the same instant anyway: 0.5 s throttle).
     * Otherwise the still sample is judged on its own, as a dwell when [dwellArmed].
     */
    @Synchronized
    fun offer(c: Candidate, motion: MotionState, dwellArmed: Boolean, force: Boolean = false, capPerMin: Int = Int.MAX_VALUE, hardCap: Boolean = false): GateResult {
        val now = clock()
        lastOfferNow = now
        val blur = ImageHashes.blur(c.thumb)
        if (blur < BLUR_FLOOR) { scoreListener?.onScored(c.seq, 0L, 0L, blur, false); return Skip(BLUR) }
        val d = ImageHashes.dHash(c.thumb); val p = ImageHashes.pHash(c.thumb)
        scoreListener?.onScored(c.seq, d, p, blur, true)
        return decide(c, d, p, blur, motion, dwellArmed, force, capPerMin, hardCap, now)
    }

    /** The decision for a scored sample, shared by [offer] and [offerScored] (one path, so a replay cannot drift from the live gate). */
    private fun decide(c: Candidate, d: Long, p: Long, blur: Float, motion: MotionState, dwellArmed: Boolean, force: Boolean, capPerMin: Int, hardCap: Boolean, now: Long): GateResult {
        val s = Scored(c, d, p, blur, motion)
        lastScoredHashes = s.d to s.p
        if (force) {
            window.clear()
            recordKeep(c, s.d, s.p, now)
            return GateResult.Keep(c, s.d, s.p, blur, dwell = false, motion)
        }
        if (motion == MotionState.STILL) {
            if (window.isNotEmpty()) {
                val closed = finish(window.toList().also { window.clear() }, dwell = false, now, capPerMin, hardCap)
                if (closed is GateResult.Keep) return closed
            }
            return finish(listOf(s), dwell = dwellArmed, now, capPerMin, hardCap)
        }
        addToWindow(s, now)
        return if (now - windowStart >= WINDOW_MS) finish(window.toList().also { window.clear() }, dwell = false, now, capPerMin, hardCap) else Skip(WINDOW)
    }

    /** Replay entry (Tasks 16, 20): the same decision as [offer] from a recorded score; the candidate carries no pixels. */
    @Synchronized
    fun offerScored(seq: Long, d: Long, p: Long, blur: Float, motion: MotionState, dwellArmed: Boolean, force: Boolean, cap: Int, hard: Boolean, nowMs: Long): GateResult {
        if (blur < BLUR_FLOOR) return Skip(BLUR)
        return decide(Candidate(seq, Thumb(0, 0, ByteArray(0)), Nv21Frame(0, 0, 0, ByteArray(0))), d, p, blur, motion, dwellArmed, force, cap, hard, nowMs)
    }

    /** At window end: the best pending candidate, or null when nothing is pending. */
    @Synchronized
    fun flushWindow(capPerMin: Int = Int.MAX_VALUE, hardCap: Boolean = false): GateResult? =
        if (window.isEmpty()) null else { val now = clock(); lastOfferNow = now; finish(window.toList().also { window.clear() }, dwell = false, now, capPerMin, hardCap) }

    /** Records a keep decided elsewhere (forced capture): pushes the ring and the last keep time. */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun recordKeep(candidate: Candidate, dhash: Long, phash: Long, nowMs: Long) {
        store.push(dhash, phash)
        store.setLastKeptAt(nowMs)
        noteKept(nowMs)
    }

    private fun noteKept(now: Long) {
        keptTimes.addLast(now)
        while (keptTimes.isNotEmpty() && now - keptTimes.first() >= CAP_WINDOW_MS) keptTimes.removeFirst()
    }

    private fun addToWindow(s: Scored, now: Long) {
        if (window.isEmpty()) windowStart = now
        window += s
        if (window.size > MAX_CANDIDATES) window.remove(window.minBy { it.blur })
    }

    private fun finish(pool: List<Scored>, dwell: Boolean, now: Long, capPerMin: Int, hardCap: Boolean = false): GateResult {
        val gp = params() // read once per decision: the threshold, the bonus and the ring rule always agree
        val ring = store.lastKeyframes().let { if (gp.ringCompareAll) it else it.takeLast(1) }
        val fresh = pool.filter { s -> ring.all { (d, p) -> minOf(hamming(s.d, d), hamming(s.p, p)) > gp.hamming } }
        val last = store.lastKeptAt()
        val gap = if (last <= 0L) Long.MAX_VALUE else now - last // never kept (a fresh store): nothing to throttle against
        // A clock that moved back (negative gap) does not throttle, or capture would stop until it caught up.
        fun within(ms: Long) = gap in 0 until ms
        val best = fresh.maxByOrNull { it.blur }
            ?: return if (within(MIN_GAP_ANY_MS)) Skip(THROTTLED) else { duplicates++; Skip(DUPLICATE) }
        if (capPerMin != Int.MAX_VALUE) {
            val k = keptTimes.count { now - it < CAP_WINDOW_MS }
            val dMin = ring.minOfOrNull { (d, p) -> minOf(hamming(best.d, d), hamming(best.p, p)) } ?: 64
            // A non-glance keep (timer or stream) stops at cap - 1 with no big-change bonus: one glance slot a minute stays free.
            val over = if (dwell) k.toLong() >= (if (hardCap) 1L else 2L) * capPerMin || (k >= capPerMin && dMin < gp.bigChange)
            else k >= maxOf(1, capPerMin - 1)
            if (over) { capped++; return Skip(CAPPED) }
        }
        // Fix wave K1: a dwell keyframe (head settled) keeps the short gap; any other keep is rate-capped, wider while moving.
        val moving = best.motion == MotionState.WALKING || best.motion == MotionState.HEAD_MOVING
        if (within(if (dwell) MIN_GAP_NEW_MS else if (moving) NON_DWELL_MOVING_GAP_MS else NON_DWELL_GAP_MS)) return Skip(THROTTLED)
        store.push(best.d, best.p)
        store.setLastKeptAt(now)
        noteKept(now)
        return GateResult.Keep(best.c, best.d, best.p, best.blur, dwell, best.motion)
    }

    private fun hamming(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    private fun Skip(reason: String) = GateResult.Skip(reason)

    companion object {
        const val BLUR_FLOOR = 60f
        const val HAMMING = 10
        const val WINDOW_MS = 1_500L
        const val MAX_CANDIDATES = 3
        const val MIN_GAP_NEW_MS = 500L
        const val MIN_GAP_ANY_MS = 1_200L
        /** The non-dwell keyframe rate cap (fix wave K1): at most one per 3 s, one per 6 s while WALKING / HEAD_MOVING. Dwell and forced keeps are exempt. */
        const val NON_DWELL_GAP_MS = 3_000L
        const val NON_DWELL_MOVING_GAP_MS = 6_000L
        /** Hash distance (of 64 bits) from every ring keyframe above which a frame may exceed the per-minute cap, up to twice it. */
        const val BIG_CHANGE = 22
        const val CAP_WINDOW_MS = 60_000L
        const val CAPPED = "CAPPED"
        const val BLUR = "BLUR"; const val DUPLICATE = "DUPLICATE"; const val THROTTLED = "THROTTLED"; const val WINDOW = "WINDOW"
    }
}
