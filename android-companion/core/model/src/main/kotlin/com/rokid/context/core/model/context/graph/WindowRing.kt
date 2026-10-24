package com.rokid.context.core.model.context.graph

/**
 * One window term over a one-second signal (3c C1b): a ring of the last [seconds] one-second slots of Int32 values (milli-units, see [quantise]),
 * with an exact Long running sum and, for [AGG_MAX], a monotonic deque of slot indexes. No allocation after construction.
 *
 * FULL-HORIZON: the term answers only when every one of the last [seconds] slots was written with a valid value. A partial window (warming up) and a
 * window holding a gap both answer UNAVAILABLE, never a partial value and never a zero. A missing second is [pushInvalid], not 0.
 * Integer sums make the incremental sum bit-identical to a full recompute (replay parity). Not thread-safe: the caller serialises.
 */
class WindowRing(val seconds: Int, val agg: Int) {
    init { require(seconds in MIN_SECONDS..MAX_SECONDS) { "window must be $MIN_SECONDS-$MAX_SECONDS s" }; require(agg in AGG_MAX..AGG_FRAC) { "agg" } }

    private val ring = IntArray(seconds)
    private val dq: IntArray? = if (agg == AGG_MAX) IntArray(seconds) else null
    private var dqHead = 0
    private var dqSize = 0
    /** Slots written so far (valid or not) since the last [reset]. */
    private var n = 0
    /** Index of the newest invalid slot, or -1. */
    private var lastInvalid = -1
    private var sum = 0L

    /** Slots written since the last reset, for "warming (n/N s)". */
    val filled: Int get() = minOf(n, seconds)

    /** True when the last [seconds] slots all hold a valid value. */
    val ready: Boolean get() = n >= seconds && lastInvalid < n - seconds

    fun reset() { java.util.Arrays.fill(ring, 0); dqHead = 0; dqSize = 0; n = 0; lastInvalid = -1; sum = 0L }

    /** Writes the next second as a valid value [q] (a quantised value, or 0/1 for [AGG_FRAC]). */
    fun push(q: Int) {
        if (n == Int.MAX_VALUE) reset()
        expire()   // the slot being overwritten leaves the window first, so no deque entry reads a stale value
        val pos = n % seconds
        sum -= ring[pos]
        ring[pos] = q; sum += q
        val d = dq
        if (d != null) {
            while (dqSize > 0 && ring[d[(dqHead + dqSize - 1) % seconds] % seconds] <= q) dqSize--
            d[(dqHead + dqSize) % seconds] = n; dqSize++
        }
        n++
    }

    /** Writes the next second as missing: it counts in the horizon (time passed) but holds no value and blocks every answer until it leaves the window. */
    fun pushInvalid() {
        if (n == Int.MAX_VALUE) reset()
        expire()
        val pos = n % seconds
        sum -= ring[pos]
        ring[pos] = 0
        lastInvalid = n
        n++
    }

    private fun expire() {
        val d = dq ?: return
        while (dqSize > 0 && d[dqHead] <= n - seconds) { dqHead = (dqHead + 1) % seconds; dqSize-- }
    }

    /** The term's value, or NaN while not [ready]. mean = (sum / n) / 1000; frac = sum / n over 0/1 slots; max = max / 1000. */
    fun value(): Double {
        if (!ready) return Double.NaN
        return when (agg) {
            AGG_MEAN -> (sum.toDouble() / seconds) / 1000.0
            AGG_FRAC -> sum.toDouble() / seconds
            else -> ring[dq!![dqHead] % seconds] / 1000.0
        }
    }

    /** Writes the value into [out]: a number, or UNAVAILABLE while warming or holding a gap. */
    fun read(out: Sample, atMs: Long, ageMs: Long): Sample {
        val v = value()
        return if (v.isNaN()) out.setUnavailable() else out.setNum(v, atMs, ageMs, 1.0, 0)
    }

    companion object {
        const val AGG_MAX = 0
        const val AGG_MEAN = 1
        const val AGG_FRAC = 2
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 300
        /** The most distinct window terms in one plan. */
        const val MAX_TERMS = 4
        private const val CLAMP = 2_000_000L

        /** `Math.round(x * 1000)` clamped to +-2e6, or null for a non-finite [x]. Java and JS round halves toward +infinity. */
        fun quantise(x: Double): Int? = if (x.isFinite()) Math.round(x * 1000.0).coerceIn(-CLAMP, CLAMP).toInt() else null

        /** [quantise] without boxing (the 1 Hz feed allocates nothing): [invalid] for a non-finite [x]. */
        fun quantiseOr(x: Double, invalid: Int): Int = if (x.isFinite()) Math.round(x * 1000.0).coerceIn(-CLAMP, CLAMP).toInt() else invalid

        fun aggCode(name: String): Int? = when (name) { "max" -> AGG_MAX; "mean" -> AGG_MEAN; "frac" -> AGG_FRAC; else -> null }

        /** The bytes one term holds: the Int32 ring, plus the Int32 index deque for max. */
        fun stateBytes(seconds: Int, agg: Int): Int = 4 * seconds * (if (agg == AGG_MAX) 2 else 1)
    }
}
