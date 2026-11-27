package com.rokid.context.memory.retrieval

/**
 * Pure: the query of Layer 1 built from the dwell frame plus up to [MultiFrameConfig.maxExtra] other frames of the same glasses session from the [MultiFrameConfig.windowMs] before it that ALREADY have a vector.
 * An extra frame is kept only when its cosine to the dwell frame is at least [MultiFrameConfig.minSimilarityMilli] ("little scene change"; no scene-break detection) and the glasses did not move in the window
 * ([moved]; then nothing extra is kept). Of the qualifying frames the newest ones are kept. Dimension, non-finite and zero vectors are dropped, never thrown on.
 */
object MultiFrameQuery {
    class Frame(val id: String, val atMs: Long, val vector: FloatArray?)

    /** [vectors]: the dwell vector first, then the kept extras (newest first). [k] = vectors.size. */
    class Query(val vectors: List<FloatArray>, val mode: QueryMode) {
        val k: Int get() = vectors.size
        /** The vectors a place is scored with: BEST_OF_K = every kept frame (the best one counts); MEAN = the normalised mean as the only one. */
        fun scoringVectors(): List<FloatArray> = if (mode == QueryMode.BEST_OF_K || vectors.size <= 1) vectors else listOf(mean(vectors) ?: vectors.first())
    }

    /** Null when the dwell vector itself is unusable (no query at all). */
    fun build(dwell: FloatArray?, dwellId: String, dwellAtMs: Long, others: List<Frame>, moved: Boolean, cfg: MultiFrameConfig = MultiFrameConfig()): Query? {
        if (dwell == null || !sane(dwell)) return null
        val kept = ArrayList<FloatArray>()
        kept += dwell
        if (!moved && cfg.maxExtra > 0) {
            val extras = others.asSequence()
                .filter { it.id != dwellId && it.vector != null && it.atMs <= dwellAtMs && dwellAtMs - it.atMs <= cfg.windowMs }
                .filter { val v = it.vector!!; v.size == dwell.size && sane(v) && Math.round(VectorMath.cosine(dwell, v) * 1000.0).toInt() >= cfg.minSimilarityMilli }
                .sortedWith(compareByDescending<Frame> { it.atMs }.thenBy { it.id })
                .take(cfg.maxExtra).map { it.vector!! }.toList()
            kept += extras
        }
        return Query(kept, cfg.mode)
    }

    /** Normalised mean of the normalised vectors; null when the sum has no direction. */
    internal fun mean(vs: List<FloatArray>): FloatArray? {
        val n = vs.first().size
        val sum = DoubleArray(n)
        for (v in vs) { val u = VectorMath.l2(v); for (i in 0 until n) sum[i] += u[i].toDouble() }
        val f = FloatArray(n) { (sum[it] / vs.size).toFloat() }
        return if (VectorMath.usable(f)) VectorMath.l2(f) else null
    }

    private fun sane(v: FloatArray) = v.isNotEmpty() && v.all { it.isFinite() } && VectorMath.usable(v)
}
