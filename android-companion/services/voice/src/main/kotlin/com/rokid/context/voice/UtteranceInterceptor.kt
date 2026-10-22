package com.rokid.context.voice

/** What an [UtteranceInterceptor] decided for one final transcript. */
sealed interface InterceptResult {
    /** Not ours: the ask continues unchanged. */
    data object PassThrough : InterceptResult
    /** Consumed (e.g. a mark): no model call, the ask ends [VoiceOutcome.MARKED]; [hud] (a fixed short text, never the transcript) is toasted when not null. */
    data class Handled(val hud: String? = null) : InterceptResult
    /** Ask only [question] (the clauses nothing matched). */
    data class Ask(val question: String) : InterceptResult
}

/**
 * Runs on the final transcript before `Thinking` is published. [genuineFinal] is false for a partial standing in for a
 * final (timeout or recognizer error): such text must not be acted on as a command. Runs on the pipeline's thread.
 */
fun interface UtteranceInterceptor {
    fun intercept(text: String, source: VoiceSource, genuineFinal: Boolean): InterceptResult

    companion object { val PASS_THROUGH = UtteranceInterceptor { _, _, _ -> InterceptResult.PassThrough } }
}
