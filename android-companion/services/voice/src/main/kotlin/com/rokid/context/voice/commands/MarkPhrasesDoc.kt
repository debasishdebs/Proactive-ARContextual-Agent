package com.rokid.context.voice.commands

// Original to this project (generator for the S85 phrase docs).

/**
 * Renders the user-facing voice-mark phrase tables as markdown, from [MarkPhrases] and [VoiceCommandGroup] only.
 * `docs/voice-marks.md` and the README embed this text between marker comments; `VoiceMarksDocTest` fails on drift.
 * Regenerate with `VOICE_MARKS_REGEN=1 ./gradlew --no-daemon :services:voice:testDebugUnitTest --tests '*VoiceMarksDocTest'`
 * (the docs and the variable are declared test inputs, so the task re-runs after a hand edit or a regen).
 * Every phrase and intent word is written in backticks; nothing else in a block is.
 */
internal object MarkPhrasesDoc {
    const val DOC_BEGIN = "<!-- voice-marks:begin -->"
    const val DOC_END = "<!-- voice-marks:end -->"
    const val README_BEGIN = "<!-- voice-marks-readme:begin -->"
    const val README_END = "<!-- voice-marks-readme:end -->"

    private fun list(phrases: Collection<String>) = phrases.joinToString("\n") { "- `$it`" }

    fun render(): String = buildString {
        appendLine("#### Mark a moment")
        appendLine("Saves a mark for the last 10 seconds up to the moment you pressed push-to-talk.")
        appendLine(list(MarkPhrases.MARK))
        appendLine()
        appendLine("#### Phrases that must stand alone")
        appendLine("These also start ordinary sentences or notes. They count as a mark only when nothing but an intent word follows, so \"note that the meeting is at five\" stays a note and goes to the assistant.")
        appendLine(list(MarkPhrases.MARK_STANDALONE.sortedBy { MarkPhrases.MARK.indexOf(it) }))
        appendLine()
        appendLine("#### I missed that")
        appendLine("Does the same as a mark: saves the previous 10 seconds. It is not a mark if other words follow, so \"I missed that bus\" is a normal sentence.")
        appendLine(list(MarkPhrases.RETRO))
        appendLine()
        appendLine("#### Undo")
        appendLine("Deletes the mark from this listen, or your newest voice or tap mark from the last 2 minutes.")
        appendLine(list(MarkPhrases.UNDO))
        appendLine()
        appendLine("#### Polite starts you can add before any command")
        appendLine(list(MarkPhrases.FILLERS))
        appendLine()
        appendLine("#### Intent words (what you were doing)")
        appendLine("Add one after a mark command, for example \"mark that, I'm driving\". With \"mark that\", \"mark this\" or \"mark it\", other words in the same sentence still save a plain mark; any other words in a later sentence are not part of the mark.")
        appendLine()
        appendLine("| Word | Label on the mark | Intent label |")
        appendLine("| --- | --- | --- |")
        MarkPhrases.INTENTS.forEach { appendLine("| `${it.word}` | ${it.canvasLabel} | ${it.intentLabel} |") }
        appendLine()
        appendLine("#### Small words allowed between a command and its intent word")
        appendLine(list(MarkPhrases.INTENT_FILLERS.sorted()))
        appendLine()
        appendLine("#### Negations (the mark is saved plain, with no intent)")
        appendLine(list(MarkPhrases.NEGATIONS.sorted()))
        appendLine()
        appendLine("#### Question words (a sentence starting with one is never a command)")
        appendLine(list(MarkPhrases.QUESTION_WORDS.sorted()))
        appendLine()
        appendLine("#### Exceptions to the question rule")
        append(list(MarkPhrases.POLITE_PREFIXES.map { it.joinToString(" ") }))
        appendLine()
    }

    /** Compact list for the README, from the same data (the command groups and their examples). */
    fun renderCompact(): String =
        VoiceCommandGroup.values().joinToString("\n") { "- **${it.label}**: ${it.examples}" } + "\n"
}
