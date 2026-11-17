package com.rokid.context.orchestration.retrieval

import com.rokid.context.ai.contract.TimeAnchorOutput
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Retrieval-quality spec section 3, the LLM fallback: asked ONLY when the rules found no window but the question still holds a time cue ("on Diwali", "while the guest
 * was over", "after breakfast"). The model sees the question text and today's date and nothing else, never a memory ([ask] is built that way by the caller, through the
 * normal text-call gates). Strict contract: any problem (an invalid reply, a bad range, a timeout, an exception) is NO window, never a guess. One call per (question,
 * local date): a valid answer (a window or none) and a contract failure are cached; a timeout or a transport error is not, so the next ask may try again.
 *
 * [ask]: the single model call (null = the reply broke its contract; may throw). [anchor]: for `kind=event`, the capture time span of the memories matching the event words,
 * as one episode `(start, end)` (null = none found); it reads times only and gives the model nothing.
 */
class LlmWindowResolver(
    private val ask: suspend (question: String, nowMs: Long, zone: ZoneId) -> TimeAnchorOutput?,
    private val anchor: suspend (event: String, nowMs: Long) -> Pair<Long, Long>? = { _, _ -> null },
    private val timeoutMs: Long = TIMEOUT_MS,
) : WindowResolver {
    private val cache = object : LinkedHashMap<String, TimeWindow?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TimeWindow?>?) = size > CACHE_SIZE
    }

    /** How many model calls this resolver has made (tests, and the "one cheap call" budget check). */
    @Volatile var calls: Int = 0
        private set

    override suspend fun resolve(question: String, nowMs: Long, zone: ZoneId): TimeWindow? {
        if (!hasCue(question)) return null
        val key = question.trim().lowercase(Locale.ROOT) + "|" + Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        calls++
        val out = try {
            withTimeout(timeoutMs) { ask(question, nowMs, zone) }
        } catch (_: TimeoutCancellationException) {
            return null
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (_: Exception) {
            return null
        }
        val w = out?.let { toWindow(it, question, nowMs, zone) }
        synchronized(cache) { cache[key] = w }
        return w
    }

    private suspend fun toWindow(o: TimeAnchorOutput, question: String, nowMs: Long, zone: ZoneId): TimeWindow? = when (o.kind) {
        "date", "range" -> {
            val from = parse(o.from, zone, endOfDay = false)
            val to = parse(o.to, zone, endOfDay = true)
            if (from == null || to == null) null else make(from, to, o.label, nowMs, zone)
        }
        "event" -> eventWindow(o.event, question, nowMs, zone)
        else -> null
    }

    /** `from < to`, `to <= now + 1 day` (then clipped to now), a span of at most [MAX_SPAN_DAYS]; anything else is no window. */
    private fun make(fromMs: Long, toMs: Long, label: String, nowMs: Long, zone: ZoneId): TimeWindow? {
        if (fromMs >= toMs || toMs > nowMs + DAY_MS || toMs - fromMs > MAX_SPAN_DAYS * DAY_MS) return null
        val to = minOf(toMs, nowMs)
        if (fromMs >= to) return null
        return TimeWindow(fromMs, to, rangeLabel(fromMs, to, zone), safe(label) ?: "the asked time", TimeWindow.Source.LLM)
    }

    /** `kind=event`: the episode the event words match, then before / after / during it as the question says (a fixed [EVENT_SPAN_MS] either side). */
    private suspend fun eventWindow(event: String, question: String, nowMs: Long, zone: ZoneId): TimeWindow? {
        val words = safe(event) ?: return null
        val (start, end) = anchor(words, nowMs) ?: return null
        val q = question.lowercase(Locale.ROOT)
        val (from, to) = when {
            Regex("\\bafter\\b").containsMatchIn(q) -> end to end + EVENT_SPAN_MS
            Regex("\\bbefore\\b").containsMatchIn(q) -> start - EVENT_SPAN_MS to start
            else -> start - EVENT_PAD_MS to end + EVENT_PAD_MS
        }
        return make(from, to, words, nowMs, zone)
    }

    private fun rangeLabel(fromMs: Long, toMs: Long, zone: ZoneId): String =
        LABEL.format(Instant.ofEpochMilli(fromMs).atZone(zone)) + " - " + LABEL.format(Instant.ofEpochMilli(toMs).atZone(zone))

    /** `yyyy-MM-ddTHH:mm` (or a bare date: its start, or for an end bound the start of the next day) as epoch ms; null when it does not parse. */
    private fun parse(s: String, zone: ZoneId, endOfDay: Boolean): Long? = try {
        if (s.length <= 10) LocalDate.parse(s).let { (if (endOfDay) it.plusDays(1) else it).atStartOfDay(zone).toInstant().toEpochMilli() }
        else LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) { null }

    companion object {
        const val TIMEOUT_MS = 3_000L
        const val CACHE_SIZE = 64
        const val MAX_SPAN_DAYS = 400L
        private const val DAY_MS = 86_400_000L
        const val EVENT_SPAN_MS = 3 * 3_600_000L
        const val EVENT_PAD_MS = 15 * 60_000L
        private val LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.US)
        private val SAFE = Regex("[^A-Za-z0-9 ()'&,.-]")
        private val MONTH_OR_DAY = Regex("^(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)[a-z]*$")
        private val NOT_AN_EVENT = setOf("or", "and", "that", "this", "it", "then", "i", "we", "you", "noon", "midnight", "now", "today", "yesterday", "tonight", "the", "a", "my", "an")

        /** A model text that may reach the answer model's note line: plain characters only, at most 40 of them; blank = none. */
        fun safe(s: String): String? = SAFE.replace(s, " ").replace(Regex("\\s+"), " ").trim().take(40).takeIf { it.isNotEmpty() }

        private val ON_NAME = Regex("(?<![A-Za-z])on\\s+([A-Z][a-z]+)")
        private val ON_BIRTHDAY = Regex("\\bon\\s+(?:my|his|her|our|the)\\s+(?:birthday|anniversary)\\b", RegexOption.IGNORE_CASE)
        private val WHEN_CLAUSE = Regex("\\bwhen\\s+(?:i|we|the|my|someone|somebody)\\b", RegexOption.IGNORE_CASE)
        private val WHILE_CLAUSE = Regex("\\bwhile\\s+(?:the|i|we|my|someone|somebody)\\b", RegexOption.IGNORE_CASE)
        private val COMPARE = Regex("\\b(?:before or after|after or before)\\b", RegexOption.IGNORE_CASE)
        private val BEFORE_AFTER = Regex("\\b(?:before|after|during)\\s+(?:(?:the|my|a|an)\\s+)?([a-z]+)\\b", RegexOption.IGNORE_CASE)

        /** Spec section 3: a temporal cue the rules cannot read. Only worth a model call when one is there. */
        fun hasCue(question: String): Boolean {
            if (ON_NAME.findAll(question).any { !MONTH_OR_DAY.matches(it.groupValues[1].lowercase(Locale.ROOT)) }) return true
            if (ON_BIRTHDAY.containsMatchIn(question) || WHEN_CLAUSE.containsMatchIn(question) || WHILE_CLAUSE.containsMatchIn(question)) return true
            // "before or after the fruit" compares two things of the same time; it names no time to look up
            return BEFORE_AFTER.findAll(COMPARE.replace(question, " ")).any { m -> m.groupValues[1].lowercase(Locale.ROOT).let { it !in NOT_AN_EVENT && !it.first().isDigit() } }
        }
    }
}
