package com.rokid.context.voice.commands

sealed interface MarkCommand {
    /** [intent] is the canvas label of one clear intent word, else null (a plain MOMENT). */
    data class Mark(val intent: String?) : MarkCommand
    data object Retro : MarkCommand
    data object Undo : MarkCommand
}

/**
 * Maps one recognised utterance to a [MarkCommand] using only the [MarkPhrases] tables (S85). Pure Kotlin, no
 * Android, no I/O; the utterance is never stored or logged here.
 *
 * - A command matches only at the start of a clause, after optional fillers.
 * - A clause that starts with a question word is never a command; only "can you" / "could you" + a command is.
 * - An intent is read only after a command, through optional fillers. Negation, two different intents or any
 *   other word after a Mark gives `Mark(null)`. Retro and Undo ignore a clean trailing intent but pass through
 *   (null) when other words follow, so "I missed that bus" still reaches Ask.
 */
object MarkCommandParser {
    fun parse(utterance: String): MarkCommand? {
        val clauses = EnglishClauses.split(utterance).map(::tokens).filter { it.isNotEmpty() }
        for ((i, clause) in clauses.withIndex()) {
            val (kind, rest) = matchClause(clause) ?: continue
            val remainder = rest + clauses.drop(i + 1).flatten()
            return build(kind, remainder)
        }
        return null
    }

    private enum class Kind { MARK, RETRO, UNDO }

    private fun tokens(clause: String): List<String> =
        clause.lowercase().replace('\u2019', '\'').replace(Regex("[^a-z' ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun matchClause(raw: List<String>): Pair<Kind, List<String>>? {
        var t = raw.dropWhile { it in MarkPhrases.FILLERS }
        if (t.isEmpty()) return null
        if (t.first() in MarkPhrases.QUESTION_WORDS) {
            val polite = MarkPhrases.POLITE_PREFIXES.firstOrNull { t.take(it.size) == it } ?: return null
            t = t.drop(polite.size).dropWhile { it in MarkPhrases.FILLERS }
        }
        fun find(phrases: List<String>, kind: Kind): Pair<Kind, List<String>>? =
            phrases.map { it.split(' ') }.firstOrNull { t.take(it.size) == it }?.let { kind to t.drop(it.size) }
        return find(MarkPhrases.MARK, Kind.MARK) ?: find(MarkPhrases.RETRO, Kind.RETRO) ?: find(MarkPhrases.UNDO, Kind.UNDO)
    }

    private fun build(kind: Kind, remainder: List<String>): MarkCommand? {
        val read = readIntents(remainder)
        return when (kind) {
            Kind.MARK -> MarkCommand.Mark(if (read.clean && read.found.size == 1) read.found.first().canvasLabel else null)
            Kind.RETRO -> if (read.leftover.isEmpty() && !read.negated) MarkCommand.Retro else null
            Kind.UNDO -> if (read.leftover.isEmpty() && !read.negated) MarkCommand.Undo else null
        }
    }

    private class Read(val found: Set<IntentEntry>, val leftover: List<String>, val negated: Boolean) {
        val clean get() = leftover.isEmpty() && !negated
    }

    private fun readIntents(remainder: List<String>): Read {
        val found = linkedSetOf<IntentEntry>(); val leftover = mutableListOf<String>(); var negated = false
        val byLength = MarkPhrases.INTENTS.sortedByDescending { it.word.count { c -> c == ' ' } }
        var i = 0
        while (i < remainder.size) {
            val w = remainder[i]
            val hit = byLength.firstOrNull { e -> e.word.split(' ').let { p -> remainder.subList(i, minOf(remainder.size, i + p.size)) == p } }
            when {
                hit != null -> { found += hit; i += hit.word.split(' ').size; continue }
                w in MarkPhrases.NEGATIONS || w.endsWith("n't") -> negated = true
                w in MarkPhrases.INTENT_FILLERS || w in MarkPhrases.FILLERS -> Unit
                else -> leftover += w
            }
            i++
        }
        return Read(found, leftover, negated)
    }
}
