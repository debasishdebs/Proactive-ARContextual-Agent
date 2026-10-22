package com.rokid.context.voice

/** Recognizer callbacks, always on Main. Partials and finals are user speech: never log them. */
interface SttListener {
    fun onPartial(text: String)
    fun onFinal(result: SttResult)
}

sealed interface SttResult {
    /** [genuineFinal] false = a partial standing in for a final (recognizer error or final timeout): never to be acted on as a command. */
    data class Text(val text: String, val genuineFinal: Boolean = true) : SttResult
    data object NoSpeech : SttResult
    /** Spec §5.1.1 guard: output while our audio can't have been read; never answered. */
    data object FallbackSuspected : SttResult
    data object PackMissing : SttResult
    data object NoPermission : SttResult
    data object Failed : SttResult
}

/** One utterance. [feed] never blocks; [end] = no more audio (the recognizer finishes); [cancel] = drop it, no final. */
interface SttSession {
    fun feed(pcm: ByteArray)
    fun end()
    fun cancel()
}

/** On-device recognition only (spec §5.1.1): glasses PCM through `EXTRA_AUDIO_SOURCE` (API 33+), or the phone mic (31+). */
interface SpeechEngine {
    fun startExternal(localeTag: String, listener: SttListener): SttSession
    fun startPhoneMic(localeTag: String, listener: SttListener): SttSession
}
