package com.rokid.context.voice

/**
 * One recognizer session's rules (pure, so they are unit-tested): partials are cleaned and remembered; the final is the
 * first non-blank result, else the last partial (phase 0); any output while [fallbackSuspected] holds aborts with
 * [SttResult.FallbackSuspected]; exactly one final, then [onFinished]; [cancel] reports nothing.
 */
class SttSessionCore(private val listener: SttListener, private val fallbackSuspected: () -> Boolean) {
    var onFinished: () -> Unit = {}
    private var lastPartial: String? = null
    var finished: Boolean = false
        private set

    fun onPartial(results: List<String?>?) {
        if (finished) return
        if (fallbackSuspected()) { finish(SttResult.FallbackSuspected); return }
        val text = results.orEmpty().firstNotNullOfOrNull { r -> VoiceText.clean(r).takeIf { it.isNotEmpty() } } ?: return
        lastPartial = text
        listener.onPartial(text)
    }

    fun onResults(results: List<String?>?) {
        if (finished) return
        finish(if (fallbackSuspected()) SttResult.FallbackSuspected else FinalTranscript.pick(results, lastPartial)?.let { SttResult.Text(it) } ?: SttResult.NoSpeech)
    }

    fun onError(code: Int) {
        if (finished) return
        val partial = lastPartial
        finish(
            when {
                fallbackSuspected() -> SttResult.FallbackSuspected
                partial != null -> SttResult.Text(partial) // complete partials, then an error instead of a final
                code == ERROR_NO_MATCH || code == ERROR_SPEECH_TIMEOUT -> SttResult.NoSpeech
                code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE -> SttResult.PackMissing
                code == ERROR_INSUFFICIENT_PERMISSIONS -> SttResult.NoPermission
                else -> SttResult.Failed
            },
        )
    }

    fun cancel() {
        if (finished) return
        finished = true
        onFinished()
    }

    private fun finish(r: SttResult) {
        finished = true
        listener.onFinal(r)
        onFinished()
    }

    companion object {
        // android.speech.SpeechRecognizer values, repeated so this class stays plain JVM code.
        const val ERROR_CLIENT = 5
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_NO_MATCH = 7
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
