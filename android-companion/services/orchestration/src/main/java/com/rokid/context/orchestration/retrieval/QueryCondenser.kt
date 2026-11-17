package com.rokid.context.orchestration.retrieval

import com.rokid.context.ai.contract.CondenseOutput
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.Optional
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Retrieval-quality spec section 10 (T10), the `rag.condense` node: the search query of a question without its date words, question words and pronouns, so the embedding of the
 * question is about the topic and not about "did I see ... on 12 Nov?" (spec section 13). One cheap model call that sees the question text and today's date and nothing else, never
 * a memory ([ask] is built that way by the caller, through the normal text-call gates and the Ask fence). Strict contract: any problem (a broken reply, a blank query, a timeout, an
 * exception) is NO condensing, the raw question is searched; it never fails an Ask. One call per (question, local date): the model's reply (or its contract failure) is cached;
 * a timeout or a transport error is not, so the next ask may try again.
 *
 * For a question that is not English (Hindi, Hinglish, another Indian language) the query is the English translation, so the English captions match, and [Condensed.probe] is the
 * question as it was asked: a second word search (a voice transcript may be in the user's language), whose hits are added to the first.
 */
class QueryCondenser(
    private val ask: suspend (question: String, nowMs: Long, zone: ZoneId) -> CondenseOutput?,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
    /** [query]: what the embedding and the word search use instead of the question. [probe]: a second word search in the question's own language (null = none). [lang]: the language code the model gave ("" = unknown). */
    data class Condensed(val query: String, val probe: String?, val lang: String)

    private val cache = object : LinkedHashMap<String, CondenseOutput?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CondenseOutput?>?) = size > CACHE_SIZE
    }

    /** How many model calls this condenser has made (tests, and the "one cheap call" budget check). */
    @Volatile var calls: Int = 0
        private set

    /** Null = search the question as asked. [translate] false: the model's query is used as it is, with no second own-language search. */
    suspend fun condense(question: String, nowMs: Long, zone: ZoneId, translate: Boolean = true): Condensed? {
        val key = question.trim().lowercase(Locale.ROOT) + "|" + Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val cached = synchronized(cache) { if (cache.containsKey(key)) Optional.ofNullable(cache[key]) else null }
        val out = if (cached != null) cached.orElse(null) else {
            calls++
            val fresh = try {
                withTimeout(timeoutMs) { ask(question, nowMs, zone) }
            } catch (_: TimeoutCancellationException) {
                return null
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: com.rokid.context.orchestration.AskZoneBlockedException) {
                throw e
            } catch (_: Exception) {
                return null
            }
            synchronized(cache) { cache[key] = fresh }
            fresh
        }
        return out?.let { shape(it, question, translate) }
    }

    private fun shape(o: CondenseOutput, question: String, translate: Boolean): Condensed? {
        val query = clean(o.query) ?: return null
        val lang = o.lang.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_').takeIf { LANG.matches(it) } ?: ""
        val foreign = translate && (if (lang.isEmpty()) hasNonLatinLetters(question) else lang != "en")
        val own = question.trim().takeIf { foreign && it.isNotEmpty() && !it.equals(query, ignoreCase = true) }
        return Condensed(query, own, lang)
    }

    /** A blank language code is judged by the script: a non-Latin question (Devanagari and the like) is foreign whatever the model said. */
    private fun hasNonLatinLetters(question: String) = question.any { it.code > 0x24F && Character.isLetter(it) }

    companion object {
        const val TIMEOUT_MS = 3_000L
        const val CACHE_SIZE = 64
        const val MAX_WORDS = 12
        private val LANG = Regex("[a-z]{2,3}")

        /** A model text that becomes a search query: letters, digits and a few joiners only (any script), at most [MAX_WORDS] words; blank = none. */
        fun clean(s: String): String? {
            val words = s.replace(Regex("[^\\p{L}\\p{N}\\p{M} '&.-]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }.take(MAX_WORDS)
            return words.joinToString(" ").take(120).takeIf { it.isNotBlank() }
        }
    }
}
