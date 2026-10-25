package com.rokid.context.core.model.context.graph

/**
 * One window term over a one-second signal (3c C1b, tolerance 3d): a ring of the last [seconds] one-second slots of Int32 values (milli-units, see
 * [quantise]), with an exact Long running sum over the VALID slots and, for [AGG_MAX], a monotonic deque of slot indexes. No allocation after construction.
 *
 * WARM-UP: the term never answers before the full horizon of [seconds] slots has elapsed (valid or not) since the last [reset]. TOLERANCE: once it
 * has, it answers when `valid * 100 >= minValidPct * seconds` (integers; 100 = every second must be valid, the strict window). max, mean and frac are
 * over the valid slots only (frac = valid ones / valid slots); a missing second is [pushInvalid], never a zero. Integer sums make the incremental sum
 * bit-identical to a full recompute (replay parity). An invalid slot is the sentinel [GAP] in the ring (quantised values are clamped to +-2e6), so
 * tolerance needs no extra state bytes. Not thread-safe: the caller serialises.
 */
class WindowRing(val seconds: Int, val agg: Int, val minValidPct: Int = 100) {
    init {
        require(seconds in MIN_SECONDS..MAX_SECONDS) { "window must be $MIN_SECONDS-$MAX_SECONDS s" }; require(agg in AGG_MAX..AGG_FRAC) { "agg" }
        require(minValidPct in MIN_VALID_PCT..100) { "minValidPct must be $MIN_VALID_PCT-100" }
    }

    private val ring = IntArray(seconds).also { java.util.Arrays.fill(it, GAP) }
    private val dq: IntArray? = if (agg == AGG_MAX) IntArray(seconds) else null
    private var dqHead = 0
    private var dqSize = 0
    /** Slots written so far (valid or not) since the last [reset]. */
    private var n = 0
    /** Valid slots among the last [seconds]. */
    private var valid = 0
    private var sum = 0L

    /** Slots written since the last reset, for "warming (n/N s)". */
    val filled: Int get() = minOf(n, seconds)

    /** Valid seconds in the window now (for the editor's "k of N seconds"). */
    val validSeconds: Int get() = valid

    /** True when the full horizon has elapsed and enough of its seconds hold a valid value. */
    val ready: Boolean get() = n >= seconds && valid * 100L >= minValidPct.toLong() * seconds

    fun reset() { java.util.Arrays.fill(ring, GAP); dqHead = 0; dqSize = 0; n = 0; valid = 0; sum = 0L }

    /** Writes the next second as a valid value [q] (a quantised value, or 0/1 for [AGG_FRAC]). */
    fun push(q: Int) {
        if (n == Int.MAX_VALUE) reset()
        expire()   // the slot being overwritten leaves the window first, so no deque entry reads a stale value
        val pos = n % seconds
        leave(pos)
        ring[pos] = q; sum += q; valid++
        val d = dq
        if (d != null) {
            while (dqSize > 0 && ring[d[(dqHead + dqSize - 1) % seconds] % seconds] <= q) dqSize--
            d[(dqHead + dqSize) % seconds] = n; dqSize++
        }
        n++
    }

    /** Writes the next second as missing: it counts in the horizon (time passed) but holds no value; the window answers only while enough other seconds are valid. */
    fun pushInvalid() {
        if (n == Int.MAX_VALUE) reset()
        expire()
        val pos = n % seconds
        leave(pos)
        ring[pos] = GAP
        n++
    }

    private fun leave(pos: Int) { val old = ring[pos]; if (old != GAP) { sum -= old; valid-- } }

    private fun expire() {
        val d = dq ?: return
        while (dqSize > 0 && d[dqHead] <= n - seconds) { dqHead = (dqHead + 1) % seconds; dqSize-- }
    }

    /** The term's value, or NaN while not [ready]. mean = (sum / valid) / 1000; frac = sum / valid over the valid 0/1 slots; max = max over the valid slots / 1000. */
    fun value(): Double {
        if (!ready) return Double.NaN
        return when (agg) {
            AGG_MEAN -> (sum.toDouble() / valid) / 1000.0
            AGG_FRAC -> sum.toDouble() / valid
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
        /** The lowest tolerance a term may ask for: at least half of the horizon's seconds must hold a sensor value. */
        const val MIN_VALID_PCT = 50
        /** The most distinct window terms in one plan. */
        const val MAX_TERMS = 4
        private const val CLAMP = 2_000_000L
        private const val GAP = Int.MIN_VALUE

        /** `Math.round(x * 1000)` clamped to +-2e6, or null for a non-finite [x]. Java and JS round halves toward +infinity. */
        fun quantise(x: Double): Int? = if (x.isFinite()) Math.round(x * 1000.0).coerceIn(-CLAMP, CLAMP).toInt() else null

        /** [quantise] without boxing (the 1 Hz feed allocates nothing): [invalid] for a non-finite [x]. */
        fun quantiseOr(x: Double, invalid: Int): Int = if (x.isFinite()) Math.round(x * 1000.0).coerceIn(-CLAMP, CLAMP).toInt() else invalid

        fun aggCode(name: String): Int? = when (name) { "max" -> AGG_MAX; "mean" -> AGG_MEAN; "frac" -> AGG_FRAC; else -> null }

        /** The bytes one term holds: the Int32 ring, plus the Int32 index deque for max. */
        fun stateBytes(seconds: Int, agg: Int): Int = 4 * seconds * (if (agg == AGG_MAX) 2 else 1)
    }
}
