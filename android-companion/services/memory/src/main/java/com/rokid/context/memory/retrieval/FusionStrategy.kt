package com.rokid.context.memory.retrieval

/** spec §4.6 hybrid fusion. Each strategy carries its own config; ids missing from one list score 0 there. */
sealed interface FusionStrategy {
    fun fuse(lexical: List<Hit>, semantic: List<Hit>): Map<String, Double>

    /** Min-max normalised scores, `semanticWeight` × semantic + (1 − weight) × lexical (default 60/40). */
    data class WeightedScore(val semanticWeight: Double = 0.6) : FusionStrategy {
        override fun fuse(lexical: List<Hit>, semantic: List<Hit>): Map<String, Double> {
            val l = minMax(lexical); val s = minMax(semantic)
            return (l.keys + s.keys).associateWith { semanticWeight * (s[it] ?: 0.0) + (1 - semanticWeight) * (l[it] ?: 0.0) }
        }
    }

    /** Reciprocal rank fusion: Σ 1 / (k + rank), rank from 1 (default k = 60); equal scores rank by id. */
    data class Rrf(val k: Int = 60) : FusionStrategy {
        override fun fuse(lexical: List<Hit>, semantic: List<Hit>): Map<String, Double> {
            val out = HashMap<String, Double>()
            for (list in listOf(lexical, semantic)) {
                list.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.id }).forEachIndexed { i, h -> out[h.id] = (out[h.id] ?: 0.0) + 1.0 / (k + i + 1) }
            }
            return out
        }
    }

    /** CombMNZ: (sum of normalised scores) × (number of retrievers that returned the id). */
    data object CombMnz : FusionStrategy {
        override fun fuse(lexical: List<Hit>, semantic: List<Hit>): Map<String, Double> {
            val l = minMax(lexical); val s = minMax(semantic)
            return (l.keys + s.keys).associateWith { id -> ((l[id] ?: 0.0) + (s[id] ?: 0.0)) * ((if (id in l) 1 else 0) + (if (id in s) 1 else 0)) }
        }
    }

    companion object {
        fun minMax(hits: List<Hit>): Map<String, Double> {
            if (hits.isEmpty()) return emptyMap()
            val lo = hits.minOf { it.score }; val hi = hits.maxOf { it.score }
            return hits.associate { it.id to if (hi == lo) 1.0 else (it.score - lo) / (hi - lo) }
        }
    }
}
