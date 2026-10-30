package com.rokid.context.voice.commands

/** Pause or resume capture by voice (v0.9.9). Whole-utterance, exact phrases only, so "why did you pause" is a question for Ask. Pure Kotlin, no I/O; the text is never stored or logged. */
enum class CaptureCommand { PAUSE, RESUME }

object CaptureCommandParser {
    val PAUSE_PHRASES = listOf("pause capture", "pause capturing", "stop capturing", "pause the camera")
    val RESUME_PHRASES = listOf("resume capture", "resume capturing", "start capturing", "unpause")
    /** Same polite starts as the mark commands, plus "please" at the end. */
    private val FILLERS = setOf("please", "ok", "okay", "hey", "um", "uh", "so")
    private const val MAX_CHARS = 100

    fun parse(utterance: String): CaptureCommand? {
        if (utterance.length > MAX_CHARS) return null
        val words = utterance.lowercase().replace('’', '\'').replace(Regex("[.!,]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .dropWhile { it in FILLERS }.dropLastWhile { it == "please" }
        val text = words.joinToString(" ")
        return when (text) {
            in PAUSE_PHRASES -> CaptureCommand.PAUSE
            in RESUME_PHRASES -> CaptureCommand.RESUME
            else -> null
        }
    }
}
