package com.rokid.context.voice

import com.rokid.context.core.model.TextHygiene

/** Voice text hygiene for the HUD and the phone; the answer path applies its own (AskText + data blocks) on top. */
object VoiceText {
    const val MAX_CHARS = 500

    /** Separators collapse to one space ([TextHygiene.SEPARATORS], preflight F14: one shared set). ZWJ/ZWNJ (Cf) are kept. */
    fun clean(text: String?, max: Int = MAX_CHARS): String = clip(text.orEmpty().replace(TextHygiene.SEPARATORS, " ").trim(), max)

    /** At most [max] chars, never ending on half a surrogate pair. */
    fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (max > 0 && Character.isHighSurrogate(text[max - 1])) max - 1 else max
        return text.substring(0, end).trimEnd()
    }
}

object FinalTranscript {
    /** The first non-blank result, else the last non-empty partial (phase 0: a null final after complete partials). */
    fun pick(results: List<String?>?, lastPartial: String?): String? =
        results.orEmpty().firstNotNullOfOrNull { r -> VoiceText.clean(r).takeIf { it.isNotEmpty() } }
            ?: VoiceText.clean(lastPartial).takeIf { it.isNotEmpty() }
}
