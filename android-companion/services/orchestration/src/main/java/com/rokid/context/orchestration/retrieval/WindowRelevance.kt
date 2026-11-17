package com.rokid.context.orchestration.retrieval

import com.rokid.context.memory.db.SearchNormalizer
import com.rokid.context.memory.retrieval.RetrievalMode
import com.rokid.context.memory.retrieval.RetrievalResult
import com.rokid.context.memory.retrieval.ScoredMemory

/**
 * Whether the result of one time window is relevant enough to stop widening (retrieval-quality spec section 4). A hit alone is not enough: the lexical
 * retriever matches ANY term (so "the" and "on" match), and the vector scan returns its nearest rows however far they are. Conservative: when unsure, widen
 * (the widened step still holds the in-window rows, and the last step always wins).
 *  - LEXICAL: a hit whose text holds a CONTENT term of the question (stop words, the date phrase and one-letter terms do not count).
 *  - SEMANTIC: a hit whose similarity is at least [SEMANTIC_FLOOR] (the retriever's shifted cosine, so 0.675 = cosine 0.35).
 *  - HYBRID: either of the two.
 *  - A question with no content term at all (only a date: "what happened on 10 Nov?") has nothing to match, so any hit in the window counts.
 */
object WindowRelevance {
    const val SEMANTIC_FLOOR = 0.675

    private val STOP = setOf(
        "the", "and", "are", "was", "were", "been", "did", "does", "have", "has", "had", "you", "your", "mine", "its", "our", "his", "her", "who", "what", "when", "where", "which",
        "why", "how", "that", "this", "there", "then", "than", "with", "from", "for", "any", "some", "about", "ago", "last", "see", "saw", "seen", "tell", "show", "was", "can", "could",
        "would", "should", "yesterday", "today", "tonight", "morning", "evening", "afternoon", "night", "week", "day", "time", "ever", "not", "but", "into", "onto", "off", "out",
    )

    fun relevant(question: String, datePhrase: String?, result: RetrievalResult): Boolean {
        if (result.hits.isEmpty()) return false
        val terms = contentTerms(question, datePhrase)
        if (terms.isEmpty()) return true
        return result.hits.any { hitRelevant(it, terms, result.modeUsed) }
    }

    private fun hitRelevant(h: ScoredMemory, terms: List<String>, mode: RetrievalMode): Boolean {
        val semantic = mode != RetrievalMode.LEXICAL && (h.semantic ?: 0.0) >= SEMANTIC_FLOOR
        if (semantic) return true
        if (mode == RetrievalMode.SEMANTIC) return false
        val words = SearchNormalizer.normalize(h.memory.text).split(' ')
        return terms.any { t -> words.any { it.startsWith(t) } }
    }

    internal fun contentTerms(question: String, datePhrase: String?): List<String> {
        val q = if (datePhrase.isNullOrBlank()) question else question.replace(datePhrase, " ", ignoreCase = true)
        return SearchNormalizer.normalize(q).split(' ').filter { it.codePointCount(0, it.length) >= 3 && it !in STOP && it.any { c -> !c.isDigit() } }
            .map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }.distinct()
    }
}
