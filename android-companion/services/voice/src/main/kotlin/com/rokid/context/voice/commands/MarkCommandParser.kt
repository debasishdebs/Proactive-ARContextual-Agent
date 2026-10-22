package com.rokid.context.voice.commands

// Adapted from Live Fit services/voice/src/main/kotlin/com/debasish/livefit/services/voice/CommandParser.kt@4529735
// and CompositeCommands.kt@4529735 (ParsedUtterance, parseUtterance) (Apache-2.0, same owner). Pure Kotlin, no Android.

sealed interface MarkCommand {
    /** [intent] is the canvas label of one clear intent word, else null (a plain MOMENT). */
    data class Mark(val intent: String?) : MarkCommand
    /** "I missed that": the previous ~10 s. [intent] as for [Mark], from a clean trailing intent word. */
    data class Retro(val intent: String? = null) : MarkCommand
    data object Undo : MarkCommand
}

/**
 * One utterance, parsed per clause (the Live Fit `ParsedUtterance` shape).
 * [commands] are the matched clauses in spoken order; [notUnderstood] are the clauses that matched nothing, in
 * spoken order and untouched (so "forget it" or "what's this building" are never consumed by a mark).
 * [original] is the recognised text. Use [askText] for what to forward to Ask.
 */
data class ParsedUtterance(val commands: List<MarkCommand>, val notUnderstood: List<String>, val original: String = "") {
    /**
     * What Ask should receive: the whole original utterance when no command matched (so no word is lost or
     * re-spaced), else the unmatched clauses joined in order. Null when there is nothing to ask.
     */
    val askText: String? get() = (if (commands.isEmpty()) original.trim() else notUnderstood.joinToString(" ")).ifBlank { null }
}

/**
 * Maps one recognised utterance to commands using only the [MarkPhrases] tables (S85). Pure Kotlin, no Android,
 * no I/O; the utterance is never stored or logged here.
 *
 * - Input over [MAX_INPUT_CHARS] is not a command; the whole text is unmatched.
 * - Each clause is matched on its own. A command matches only at the start of its clause, after optional fillers.
 * - A clause that starts with a question word is never a command; only "can you" / "could you" + a command is.
 * - Directly after a command, following clauses made only of intent words, fillers and negations belong to it
 *   ("mark that, I'm driving"). Any other clause stays unmatched and is never folded in.
 * - Mark: one clean intent word gives that intent, negation, two intents or leftover words give a plain mark.
 *   Standalone phrases ([MarkPhrases.MARK_STANDALONE]) are a mark only when nothing else follows in their clause
 *   and the next clause is not unrelated speech; else the whole thing falls through (so "note that the meeting is
 *   at five" keeps its note text).
 * - Retro and Undo need a clean clause too ("I missed that bus" is not a command); Retro keeps a clean intent.
 */
object MarkCommandParser {
    const val MAX_INPUT_CHARS = 500

    fun parseUtterance(utterance: String): ParsedUtterance {
        if (utterance.length > MAX_INPUT_CHARS) return ParsedUtterance(emptyList(), listOf(utterance.trim()), utterance)
        val clauses = EnglishClauses.split(utterance)
        val toks = clauses.map(::tokens)
        val commands = mutableListOf<MarkCommand>(); val missed = mutableListOf<String>()
        var i = 0
        while (i < clauses.size) {
            val m = toks[i].takeIf { it.isNotEmpty() }?.let(::matchClause)
            if (m == null) { missed += clauses[i]; i++; continue }
            var end = i + 1
            val folded = mutableListOf<String>()
            while (end < clauses.size && isIntentShaped(toks[end])) { folded += toks[end]; end++ }
            val nextIsUnrelated = end < clauses.size && toks[end].let { it.isNotEmpty() && matchClause(it) == null }
            val command = build(m, folded, nextIsUnrelated)
            if (command == null) { missed += clauses[i]; i++ } else { commands += command; i = end }
        }
        return ParsedUtterance(commands, missed, utterance)
    }

    private enum class Kind { MARK, RETRO, UNDO }

    private class Match(val kind: Kind, val standalone: Boolean, val rest: List<String>)

    private val nonLetters = Regex("[^a-z' ]")
    private val spaces = Regex("\\s+")
    private val markPhrases = MarkPhrases.MARK.map { it.split(' ') to (it in MarkPhrases.MARK_STANDALONE) }
    private val retroPhrases = MarkPhrases.RETRO.map { it.split(' ') }
    private val undoPhrases = MarkPhrases.UNDO.map { it.split(' ') }
    private val intentsByLength = MarkPhrases.INTENTS.map { it to it.word.split(' ') }.sortedByDescending { it.second.size }

    private fun tokens(clause: String): List<String> =
        clause.lowercase().replace('’', '\'').replace(nonLetters, " ").trim().split(spaces).filter { it.isNotEmpty() }

    private fun matchClause(raw: List<String>): Match? {
        var t = raw.dropWhile { it in MarkPhrases.FILLERS }
        if (t.isEmpty()) return null
        if (t.first() in MarkPhrases.QUESTION_WORDS) {
            val polite = MarkPhrases.POLITE_PREFIXES.firstOrNull { t.take(it.size) == it } ?: return null
            t = t.drop(polite.size).dropWhile { it in MarkPhrases.FILLERS }
        }
        markPhrases.firstOrNull { t.take(it.first.size) == it.first }?.let { return Match(Kind.MARK, it.second, t.drop(it.first.size)) }
        retroPhrases.firstOrNull { t.take(it.size) == it }?.let { return Match(Kind.RETRO, false, t.drop(it.size)) }
        undoPhrases.firstOrNull { t.take(it.size) == it }?.let { return Match(Kind.UNDO, false, t.drop(it.size)) }
        return null
    }

    /** A clause of only intent words, fillers and negations (never a question): it can complete the command before it. */
    private fun isIntentShaped(clause: List<String>): Boolean =
        clause.isNotEmpty() && clause.first() !in MarkPhrases.QUESTION_WORDS && readIntents(clause).leftover.isEmpty()

    private fun build(m: Match, folded: List<String>, nextIsUnrelated: Boolean): MarkCommand? {
        val same = readIntents(m.rest)
        val all = readIntents(m.rest + folded)
        val intent = if (all.clean && all.found.size == 1) all.found.first().canvasLabel else null
        return when (m.kind) {
            Kind.MARK ->
                if (m.standalone && (same.leftover.isNotEmpty() || nextIsUnrelated)) null else MarkCommand.Mark(intent)
            Kind.RETRO -> if (same.leftover.isEmpty() && !same.negated) MarkCommand.Retro(intent) else null
            Kind.UNDO -> if (same.leftover.isEmpty() && !same.negated) MarkCommand.Undo else null
        }
    }

    private class Read(val found: Set<IntentEntry>, val leftover: List<String>, val negated: Boolean) {
        val clean get() = leftover.isEmpty() && !negated
    }

    private fun readIntents(words: List<String>): Read {
        val found = linkedSetOf<IntentEntry>(); val leftover = mutableListOf<String>(); var negated = false
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val hit = intentsByLength.firstOrNull { (_, p) -> words.subList(i, minOf(words.size, i + p.size)) == p }
            when {
                hit != null -> { found += hit.first; i += hit.second.size; continue }
                w in MarkPhrases.NEGATIONS || w.endsWith("n't") -> negated = true
                w in MarkPhrases.INTENT_FILLERS || w in MarkPhrases.FILLERS -> Unit
                else -> leftover += w
            }
            i++
        }
        return Read(found, leftover, negated)
    }
}
