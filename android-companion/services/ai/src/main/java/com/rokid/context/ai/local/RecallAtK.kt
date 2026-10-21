package com.rokid.context.ai.local

import com.rokid.context.memory.retrieval.VectorMath

/**
 * Pure recall@k for the photo 256-vs-768 decision test (spec §F.5). Harness-only: the Matryoshka truncation to a second
 * image width lives here and nowhere in production code (the stored width is [NomicVision.STORED_DIMS]).
 */
object RecallAtK {
    /** A typed query with the photos the user says it should hit. [text] is for the caller's own bookkeeping; this object never logs it. */
    data class Query(val text: String, val relevant: Set<String>, val small: Boolean)

    /** [overall] and [small] are fractions in 0..1 over the queries that could be judged ([queries], [smallQueries]). */
    data class Result(val dims: Int, val overall: Double, val small: Double, val queries: Int, val smallQueries: Int, val photos: Int)

    /** Raw 768 CLS -> L2 -> first [dims] -> L2. */
    fun imageVector(rawCls: FloatArray, dims: Int): FloatArray = NomicText.shorten(VectorMath.l2(rawCls), dims)

    /** Raw mean-pooled 768 -> LayerNorm -> first [dims] -> L2. */
    fun queryVector(rawText: FloatArray, dims: Int): FloatArray = NomicText.shorten(NomicText.layerNorm(rawText), dims)

    /**
     * recall@k = share of queries with at least one relevant photo in the top k by cosine. [images] are raw CLS vectors by photo id,
     * query vectors are raw text outputs. A query none of whose relevant photos is among [images] cannot hit and is left out of the counts.
     */
    fun evaluate(images: Map<String, FloatArray>, queries: List<Pair<Query, FloatArray>>, dims: Int, k: Int = 3): Result {
        val vecs = images.mapValues { imageVector(it.value, dims) }
        var judged = 0; var hits = 0; var smallJudged = 0; var smallHits = 0
        for ((q, raw) in queries) {
            if (q.relevant.none { it in vecs }) continue
            val qv = queryVector(raw, dims)
            val top = vecs.entries
                .map { it.key to VectorMath.cosine(qv, it.value) }
                .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                .take(k)
            val hit = top.any { it.first in q.relevant }
            judged++; if (hit) hits++
            if (q.small) { smallJudged++; if (hit) smallHits++ }
        }
        return Result(
            dims, if (judged == 0) 0.0 else hits.toDouble() / judged, if (smallJudged == 0) 0.0 else smallHits.toDouble() / smallJudged,
            judged, smallJudged, images.size,
        )
    }

    private const val EPS = 1e-9

    /** The §F.5 rule: 256 only if overall drops <= 3 points AND small drops <= 5 points versus 768 (and there is a small subset to judge). */
    fun choose256(r768: Result, r256: Result): Boolean {
        if (r768.smallQueries == 0 || r256.smallQueries == 0) return false
        return (r768.overall - r256.overall) * 100.0 <= 3.0 + EPS && (r768.small - r256.small) * 100.0 <= 5.0 + EPS
    }
}
