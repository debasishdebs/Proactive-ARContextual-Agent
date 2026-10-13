package com.rokid.context.voice

/** Spec §0.1: English is primary; Hindi is the only other target and needs its on-device pack. */
enum class SpeechLanguage(val tag: String, val label: String) {
    ENGLISH("en-US", "English"),
    HINDI("hi-IN", "Hindi"),
}

/** Settings › Devices › Phone camera & mic. [phoneMic] = Home may ask by voice with the phone mic. */
data class VoiceSettings(val language: SpeechLanguage = SpeechLanguage.ENGLISH, val phoneMic: Boolean = true)
