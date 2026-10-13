package com.rokid.context.voice

/** Where a voice ask came from; [askSource] is stored in `ask.source`. */
enum class VoiceSource(val askSource: String) { GLASSES("glasses_voice"), PHONE("phone_voice") }

sealed interface AnswerResult {
    data class Answered(val askId: String, val text: String) : AnswerResult
    /** [hudText] for the glasses (short), [phoneText] for Home: fixed strings, never provider text. */
    data class Failed(val hudText: String, val phoneText: String) : AnswerResult
}

/** The answer path (spec §4.7); the phone binds it to `AskActions.askFrom` (Task 10). */
fun interface Answerer {
    suspend fun answer(question: String, source: VoiceSource): AnswerResult
}

sealed interface VoiceState {
    val source: VoiceSource?

    data object Idle : VoiceState { override val source: VoiceSource? get() = null }
    /** [ended] = no more audio is taken; waiting for the recognizer's final. */
    data class Listening(override val source: VoiceSource, val partial: String, val ended: Boolean = false) : VoiceState
    data class Thinking(override val source: VoiceSource, val question: String) : VoiceState
    data class Answer(override val source: VoiceSource, val askId: String, val question: String, val text: String, val speaking: Boolean) : VoiceState
    data class Problem(override val source: VoiceSource, val hudText: String, val phoneText: String) : VoiceState
}

/**
 * What Home shows for the phone mic (spec §2.4, §5.1.1). [micOff] = turned off in Settings, or not loaded yet;
 * [settingsLoading] = the switch has not loaded (both set by the phone adapter).
 */
data class PhoneVoiceState(
    val status: VoiceStatus = VoiceStatus.CHECKING,
    val listening: Boolean = false,
    val partial: String = "",
    val thinking: Boolean = false,
    val error: String? = null,
    val lastAskId: String? = null,
    val micOff: Boolean = false,
    val settingsLoading: Boolean = false,
)

data class VoiceTimings(
    /** Glasses audio stopped without `pc_listen_end` (a stall or a lost end): the utterance ends with what was heard. */
    val audioIdleMs: Long = 3_000,
    /** After the end of audio, the recognizer's final is awaited this long; then the last partial is used. */
    val finalTimeoutMs: Long = 6_000,
    /**
     * Audio intake cap for one listen (glasses stream or phone mic). Then the [finalTimeoutMs] wait sits on top, so the
     * worst-case listen is 20 s + 6 s = 26 s before the ask is answered from the last partial or ends as unheard.
     */
    val maxListenMs: Long = 20_000,
    /** Thinking is bounded: no answer within this ends the ask with [VoiceCopy.TOO_LONG]; a late answer is ignored. */
    val answerTimeoutMs: Long = 60_000,
    /** An Answer stays this long after speech ends (or after it is shown, when not spoken). */
    val answerHoldMs: Long = 30_000,
    /** A glasses problem toast, and a Home voice error, clear after this. */
    val toastMs: Long = 4_000,
)

/** Every voice string the HUD or Home can show (short, fixed; spec §5.1 step 5, §6.3). */
object VoiceCopy {
    const val COULDNT_HEAR_HUD = "Couldn't hear that"
    const val COULDNT_HEAR_PHONE = "Couldn't hear that — try again"
    const val NOT_SUPPORTED_HUD = VoiceStatusText.NOT_SUPPORTED_HUD
    const val NOT_SUPPORTED_PHONE = "Voice isn't supported on this phone — type your question"
    const val PACK_HUD = VoiceStatusText.PACK_HUD
    const val PACK_PHONE = "Download the speech pack in Settings › Phone camera & mic"
    const val MIC_HUD = VoiceStatusText.MIC_HUD
    const val MIC_PHONE = "Allow the microphone to ask by voice"
    const val PHONE_BUSY_HUD = "Phone is listening — try again"
    const val GLASSES_BUSY_PHONE = "Your glasses are listening — try again"
    const val FAILED_HUD = "Couldn't answer that"
    const val FAILED_PHONE = "Couldn't answer that — try again"
    /** Thinking ran past [VoiceTimings.answerTimeoutMs]: the same copy on the HUD and Home. */
    const val TOO_LONG = "That took too long — try again"

    fun phoneHint(s: VoiceStatus): String = when (s) {
        VoiceStatus.NEEDS_MIC_PERMISSION -> MIC_PHONE
        VoiceStatus.NEEDS_SPEECH_PACK -> PACK_PHONE
        else -> NOT_SUPPORTED_PHONE
    }
}
