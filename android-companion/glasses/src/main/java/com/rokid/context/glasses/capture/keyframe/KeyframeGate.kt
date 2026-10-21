package com.rokid.context.glasses.capture.keyframe

import android.content.SharedPreferences
import com.rokid.context.core.model.MotionState

/** A copy of one camera frame (640x480 NV21, about 460 KB), kept only while it is a window candidate. */
class Nv21Frame(val w: Int, val h: Int, val rotation: Int, val data: ByteArray)

/** One sample: [seq] numbers it within the session, [thumb] is what the hashes see, [pixels] is what gets encoded if it wins. */
class Candidate(val seq: Long, val thumb: Thumb, val pixels: Nv21Frame)

sealed interface GateResult {
    /** [candidate] is the winner: G3 encodes its pixels, never a later frame. */
    data class Keep(val candidate: Candidate, val dhash: Long, val phash: Long, val blur: Float, val dwell: Boolean, val motion: MotionState = MotionState.STILL) : GateResult
    /** [reason] is BLUR | DUPLICATE | THROTTLED | WINDOW. */
    data class Skip(val reason: String) : GateResult
}

/** The last-5 keyframe ring and the last keep time, persisted so a service restart does not re-keep the same scene. */
interface GateStore {
    fun lastKeyframes(): List<Pair<Long, Long>>
    fun push(d: Long, p: Long)
    fun lastKeptAt(): Long
    fun setLastKeptAt(t: Long)
}

/** [GateStore] over the "capture_guard" prefs (the ring is one string, oldest first). */
class PrefsGateStore(private val prefs: SharedPreferences) : GateStore {
    override fun lastKeyframes(): List<Pair<Long, Long>> =
        (prefs.getString(KEY_RING, null) ?: "").split(',').mapNotNull { e ->
            val parts = e.split(':')
            if (parts.size != 2) null else runCatching { parts[0].toULong(16).toLong() to parts[1].toULong(16).toLong() }.getOrNull()
        }.takeLast(RING)

    override fun push(d: Long, p: Long) {
        val next = (lastKeyframes() + (d to p)).takeLast(RING)
        prefs.edit().putString(KEY_RING, next.joinToString(",") { "${it.first.toULong().toString(16)}:${it.second.toULong().toString(16)}" }).apply()
    }

    override fun lastKeptAt(): Long = prefs.getLong(KEY_LAST_KEPT_AT, 0L)
    override fun setLastKeptAt(t: Long) { prefs.edit().putLong(KEY_LAST_KEPT_AT, t).apply() }

    companion object {
        const val RING = 5
        private const val KEY_RING = "keyframe_ring"
        private const val KEY_LAST_KEPT_AT = "last_kept_at"
    }
}

/**
 * Keyframe test (spec section P1.1): a sample is NEW when min(Hamming dHash, Hamming pHash) > [HAMMING] against each of the
 * last 5 keyframes. A new scene may follow the previous keyframe after 0.5 s, anything else after 1.2 s. While moving,
 * samples are collected in windows of up to 1.5 s (at most 3 kept, losers released at once) and the sharpest wins.
 * A dwell sample compares to the last keyframe only, and still needs blur >= [BLUR_FLOOR].
 */
class KeyframeGate(private val store: GateStore, private val clock: () -> Long) {
    private class Scored(val c: Candidate, val d: Long, val p: Long, val blur: Float, val motion: MotionState)

    // Thread safety: offer() runs on the camera analysis executor, flushWindow() on the session timer or dwell
    // coroutine, recordKeep() on whichever thread handles "Capture now". They all share the window and the store, so each
    // public entry point is @Synchronized (cheap: hashing is the only real work and it is a few ms).
    private val window = ArrayList<Scored>(MAX_CANDIDATES + 1)
    private var windowStart = 0L
    private var lastScoredHashes: Pair<Long, Long>? = null

    /** (dHash, pHash) of the last frame that passed the blur check, or null before any. */
    @Synchronized fun lastHashes(): Pair<Long, Long>? = lastScoredHashes

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
    fun offer(c: Candidate, motion: MotionState, dwellArmed: Boolean, force: Boolean = false): GateResult {
        val now = clock()
        val blur = ImageHashes.blur(c.thumb)
        if (blur < BLUR_FLOOR) return Skip(BLUR)
        val s = Scored(c, ImageHashes.dHash(c.thumb), ImageHashes.pHash(c.thumb), blur, motion)
        lastScoredHashes = s.d to s.p
        if (force) {
            window.clear()
            recordKeep(c, s.d, s.p, now)
            return GateResult.Keep(c, s.d, s.p, blur, dwell = false, motion)
        }
        if (motion == MotionState.STILL) {
            if (window.isNotEmpty()) {
                val closed = finish(window.toList().also { window.clear() }, dwell = false, now)
                if (closed is GateResult.Keep) return closed
            }
            return finish(listOf(s), dwell = dwellArmed, now)
        }
        addToWindow(s, now)
        return if (now - windowStart >= WINDOW_MS) finish(window.toList().also { window.clear() }, dwell = false, now) else Skip(WINDOW)
    }

    /** At window end: the best pending candidate, or null when nothing is pending. */
    @Synchronized
    fun flushWindow(): GateResult? =
        if (window.isEmpty()) null else finish(window.toList().also { window.clear() }, dwell = false, clock())

    /** Records a keep decided elsewhere (forced capture): pushes the ring and the last keep time. */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun recordKeep(candidate: Candidate, dhash: Long, phash: Long, nowMs: Long) {
        store.push(dhash, phash)
        store.setLastKeptAt(nowMs)
    }

    private fun addToWindow(s: Scored, now: Long) {
        if (window.isEmpty()) windowStart = now
        window += s
        if (window.size > MAX_CANDIDATES) window.remove(window.minBy { it.blur })
    }

    private fun finish(pool: List<Scored>, dwell: Boolean, now: Long): GateResult {
        val ring = store.lastKeyframes()
        val against = if (dwell) ring.takeLast(1) else ring
        val fresh = pool.filter { s -> against.all { (d, p) -> minOf(hamming(s.d, d), hamming(s.p, p)) > HAMMING } }
        val last = store.lastKeptAt()
        val gap = if (last <= 0L) Long.MAX_VALUE else now - last // never kept (a fresh store): nothing to throttle against
        // A clock that moved back (negative gap) does not throttle, or capture would stop until it caught up.
        fun within(ms: Long) = gap in 0 until ms
        val best = fresh.maxByOrNull { it.blur }
            ?: return Skip(if (within(MIN_GAP_ANY_MS)) THROTTLED else DUPLICATE)
        // Fix wave K1: a dwell keyframe (head settled) keeps the short gap; any other keep is rate-capped, wider while moving.
        val moving = best.motion == MotionState.WALKING || best.motion == MotionState.HEAD_MOVING
        if (within(if (dwell) MIN_GAP_NEW_MS else if (moving) NON_DWELL_MOVING_GAP_MS else NON_DWELL_GAP_MS)) return Skip(THROTTLED)
        store.push(best.d, best.p)
        store.setLastKeptAt(now)
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
        const val BLUR = "BLUR"; const val DUPLICATE = "DUPLICATE"; const val THROTTLED = "THROTTLED"; const val WINDOW = "WINDOW"
    }
}
