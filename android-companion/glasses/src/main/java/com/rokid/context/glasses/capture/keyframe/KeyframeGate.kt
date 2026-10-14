package com.rokid.context.glasses.capture.keyframe

import android.content.SharedPreferences
import com.rokid.context.core.model.MotionState

/** A copy of one camera frame (640x480 NV21, about 460 KB), kept only while it is a window candidate. */
class Nv21Frame(val w: Int, val h: Int, val rotation: Int, val data: ByteArray)

/** One sample: [seq] numbers it within the session, [thumb] is what the hashes see, [pixels] is what gets encoded if it wins. */
class Candidate(val seq: Long, val thumb: Thumb, val pixels: Nv21Frame)

sealed interface GateResult {
    /** [candidate] is the winner: G3 encodes its pixels, never a later frame. */
    data class Keep(val candidate: Candidate, val dhash: Long, val phash: Long, val blur: Float, val dwell: Boolean) : GateResult
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
    private class Scored(val c: Candidate, val d: Long, val p: Long, val blur: Float)

    private val window = ArrayList<Scored>(MAX_CANDIDATES + 1)
    private var windowStart = 0L

    /** Candidates currently held (their pixel copies stay alive). */
    internal fun retainedCount(): Int = window.size

    fun offer(c: Candidate, motion: MotionState, dwellArmed: Boolean): GateResult {
        val now = clock()
        val blur = ImageHashes.blur(c.thumb)
        if (blur < BLUR_FLOOR) return Skip(BLUR)
        val s = Scored(c, ImageHashes.dHash(c.thumb), ImageHashes.pHash(c.thumb), blur)
        if (motion == MotionState.STILL) {
            // A still sample closes any pending window together with itself, so a settle right after a move is not lost.
            addToWindow(s, now)
            return finish(dwell = dwellArmed, now)
        }
        addToWindow(s, now)
        return if (now - windowStart >= WINDOW_MS) finish(dwell = false, now) else Skip(WINDOW)
    }

    /** At window end: the best pending candidate, or null when nothing is pending. */
    fun flushWindow(): GateResult? = if (window.isEmpty()) null else finish(dwell = false, clock())

    private fun addToWindow(s: Scored, now: Long) {
        if (window.isEmpty()) windowStart = now
        window += s
        if (window.size > MAX_CANDIDATES) window.remove(window.minBy { it.blur })
    }

    private fun finish(dwell: Boolean, now: Long): GateResult {
        val pool = window.toList()
        window.clear()
        val ring = store.lastKeyframes()
        val against = if (dwell) ring.takeLast(1) else ring
        val fresh = pool.filter { s -> against.all { (d, p) -> minOf(hamming(s.d, d), hamming(s.p, p)) > HAMMING } }
        val gap = now - store.lastKeptAt()
        // A clock that moved back (negative gap) does not throttle, or capture would stop until it caught up.
        fun within(ms: Long) = gap in 0 until ms
        val best = fresh.maxByOrNull { it.blur }
            ?: return Skip(if (within(MIN_GAP_ANY_MS)) THROTTLED else DUPLICATE)
        if (within(MIN_GAP_NEW_MS)) return Skip(THROTTLED)
        store.push(best.d, best.p)
        store.setLastKeptAt(now)
        return GateResult.Keep(best.c, best.d, best.p, best.blur, dwell)
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
        const val BLUR = "BLUR"; const val DUPLICATE = "DUPLICATE"; const val THROTTLED = "THROTTLED"; const val WINDOW = "WINDOW"
    }
}
