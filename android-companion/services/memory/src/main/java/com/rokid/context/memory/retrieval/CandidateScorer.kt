package com.rokid.context.memory.retrieval

/**
 * Pure ranking of Place Guide candidates against a photo vector (plan 2026-11-20 §3.3). No I/O, no clock, no randomness: the same input always gives the same output, last-bit float noise included
 * (scores are rounded to 1e-3 BEFORE any comparison). Raw scores of the two channels are never mixed:
 *  - IMAGE channel: a candidate's score is the max cosine of the photo with its reference photo vectors.
 *  - TEXT channel: the cosine of the photo with the candidate's text anchor vector (name + kind).
 * The decision channel is IMAGE when at least one candidate has a usable image reference, otherwise TEXT. Candidates without the decision channel are not ranked (their rank is null).
 */
object CandidateScorer {
    enum class Channel { IMAGE, TEXT, NONE }

    /** [edgeGapM] and [distanceM] only break ties (nearer first), then [id] ascending. */
    class Candidate(
        val id: String,
        val imageRefs: List<FloatArray> = emptyList(),
        val textAnchor: FloatArray? = null,
        val edgeGapM: Double = 0.0,
        val distanceM: Double = 0.0,
    )

    /** [milli] = round(cosine * 1000). */
    data class Scored(val id: String, val milli: Int)

    class Result(val channel: Channel, val ranked: List<Scored>, val top: List<Scored>, val margin: Int?) {
        /** 1-based rank of [id] on the decision channel, null when it was not ranked. */
        fun rankOf(id: String): Int? = ranked.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1)
        val top1: Scored? get() = ranked.firstOrNull()
        companion object { val EMPTY = Result(Channel.NONE, emptyList(), emptyList(), null) }
    }

    /** [k] is clamped to at least 1. [margin] = s1 - s2 in milli, null with fewer than two ranked candidates. */
    fun score(query: FloatArray, candidates: List<Candidate>, k: Int = 3): Result {
        if (!VectorMath.usable(query) || !query.all { it.isFinite() }) return Result.EMPTY
        val q = VectorMath.l2(query)
        fun sane(v: FloatArray) = v.size == q.size && v.all { it.isFinite() } && VectorMath.usable(v)
        val imgScores = HashMap<String, Int>()
        val txtScores = HashMap<String, Int>()
        for (c in candidates) {
            val best = c.imageRefs.filter(::sane).maxOfOrNull { VectorMath.cosine(q, it) }
            if (best != null) imgScores[c.id] = milli(best)
            val t = c.textAnchor
            if (t != null && sane(t)) txtScores[c.id] = milli(VectorMath.cosine(q, t))
        }
        val channel = if (imgScores.isNotEmpty()) Channel.IMAGE else if (txtScores.isNotEmpty()) Channel.TEXT else return Result.EMPTY
        val scores = if (channel == Channel.IMAGE) imgScores else txtScores
        val order = compareByDescending<Pair<Candidate, Int>> { it.second }.thenBy { it.first.edgeGapM }.thenBy { it.first.distanceM }.thenBy { it.first.id }
        // a duplicated id keeps its first occurrence only
        val ranked = candidates.filter { it.id in scores }.distinctBy { it.id }.map { it to scores.getValue(it.id) }.sortedWith(order).map { Scored(it.first.id, it.second) }
        val margin = if (ranked.size >= 2) ranked[0].milli - ranked[1].milli else null
        return Result(channel, ranked, ranked.take(k.coerceAtLeast(1)), margin)
    }

    private fun milli(c: Double): Int = Math.round(c * 1000.0).toInt()
}
