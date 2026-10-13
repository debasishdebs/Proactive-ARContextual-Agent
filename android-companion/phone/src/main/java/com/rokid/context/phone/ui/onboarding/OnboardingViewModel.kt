package com.rokid.context.phone.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.LinkState
import com.rokid.context.phone.voice.PackDownload
import com.rokid.context.prompt.ReplyLanguage
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Step { WELCOME, PERMISSIONS, GLASSES, AI, PERSONA, GESTURES, DONE }
enum class PermState { NOT_ASKED, GRANTED, DENIED, DENIED_FOREVER }
enum class VoiceAction { NONE, DOWNLOAD, OPEN_SETTINGS }
enum class PersonaStyle(val label: String, val text: String) {
    CONCISE("Concise", "A concise assistant. Short, direct answers."),
    FRIENDLY("Friendly", "A warm, friendly assistant."),
    DETAILED("Detailed", "A thorough assistant that explains its answers."),
}

data class VoiceLine(val text: String, val action: VoiceAction = VoiceAction.NONE)
data class PersonaState(val language: ReplyLanguage = ReplyLanguage.ENGLISH, val style: PersonaStyle = PersonaStyle.CONCISE)

data class OnboardingUiState(
    val step: Step,
    val mic: PermState,
    val notifications: PermState,
    /** Null until the mic was decided; then the glasses-voice status (the \u00A75.1.1 gate). */
    val voice: VoiceLine?,
    val glasses: LinkState,
    val ai: AiStepState,
    val persona: PersonaState,
) {
    /** The progress bar fills one segment per step reached. */
    val filledSegments: Int get() = step.ordinal + 1
}

class OnboardingViewModel(
    private val deps: OnboardingDeps,
    private val work: CoroutineDispatcher = Dispatchers.IO,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private data class Local(
        val step: Step, val mic: PermState, val notifications: PermState, val micDecided: Boolean,
        val ai: AiStepState, val persona: PersonaState,
    )

    private val local = MutableStateFlow(
        Local(
            step = saved.get<Int>(K_STEP)?.let { Step.entries.getOrNull(it) } ?: Step.WELCOME,
            mic = PermState.NOT_ASKED,
            notifications = if (deps.sdk >= 33) PermState.NOT_ASKED else PermState.GRANTED,
            micDecided = false, ai = AiStepState(), persona = PersonaState(),
        ),
    )

    val state: StateFlow<OnboardingUiState> = combine(local, deps.link, deps.voice.capabilities) { l, link, caps ->
        OnboardingUiState(l.step, l.mic, l.notifications, if (l.micDecided) voiceLine(caps) else null, link, l.ai, l.persona)
    }.stateIn(
        viewModelScope, SharingStarted.Eagerly,
        OnboardingUiState(local.value.step, local.value.mic, local.value.notifications, null, LinkState.NOT_AUTHORIZED, local.value.ai, local.value.persona),
    )

    private fun go(step: Step) {
        saved[K_STEP] = step.ordinal
        local.update { it.copy(step = step) }
    }

    fun next() { Step.entries.getOrNull(local.value.step.ordinal + 1)?.let(::go) }

    /** System back: false on WELCOME (the caller leaves the app). */
    fun back(): Boolean {
        val i = local.value.step.ordinal
        if (i == 0) return false
        go(Step.entries[i - 1])
        return true
    }

    /** The bottom button. DONE finishes through [onDone] once the flag is stored. */
    fun primary(onDone: () -> Unit) {
        val st = state.value
        when (st.step) {
            Step.GLASSES -> if (st.glasses == LinkState.CONNECTED) next() else authorizeGlasses()
            else -> next()
        }
    }

    /** The text button under it ("Set up later"). */
    fun secondary() {
        when (local.value.step) {
            Step.GLASSES -> skipGlasses()
            else -> Unit
        }
    }

    // --- Permissions -------------------------------------------------------------------------------------------

    /**
     * RECORD_AUDIO was answered (or found granted on resume). The voice gate is re-read with a probe only while the app
     * is in front ([foreground], the Phase 6 rule F2): the probe uses the recognizer and the mic indicator shows.
     */
    fun onMicResult(granted: Boolean, canAskAgain: Boolean, foreground: Boolean) {
        local.update {
            it.copy(mic = if (granted) PermState.GRANTED else if (canAskAgain) PermState.DENIED else PermState.DENIED_FOREVER, micDecided = true)
        }
        if (foreground) deps.voice.recheck()
    }

    fun onNotificationsResult(granted: Boolean, canAskAgain: Boolean) {
        local.update { it.copy(notifications = if (granted) PermState.GRANTED else if (canAskAgain) PermState.DENIED else PermState.DENIED_FOREVER) }
    }

    fun openAppSettings() = deps.openAppSettings()

    fun downloadPack() {
        viewModelScope.launch {
            if (deps.voice.downloadPack() == PackDownload.NEEDS_SETTINGS) deps.voice.openSpeechSettings()
        }
    }

    fun openSpeechSettings() = deps.voice.openSpeechSettings()

    // --- Glasses -----------------------------------------------------------------------------------------------

    fun authorizeGlasses() = deps.authorize()
    fun skipGlasses() = go(Step.AI)

    private fun voiceLine(c: VoiceCapabilities): VoiceLine = when (c.glasses) {
        VoiceStatus.SUPPORTED -> VoiceLine("Voice: Supported")
        VoiceStatus.CHECKING -> VoiceLine("Checking voice\u2026")
        VoiceStatus.NOT_VERIFIED -> VoiceLine("Voice: Not checked yet")
        VoiceStatus.NEEDS_MIC_PERMISSION -> VoiceLine("Voice: Needs microphone permission")
        VoiceStatus.NEEDS_SPEECH_PACK ->
            VoiceLine("Voice: Needs speech pack", if (c.canDownloadPack) VoiceAction.DOWNLOAD else VoiceAction.OPEN_SETTINGS)
        VoiceStatus.NEEDS_NEWER_ANDROID -> VoiceLine("Voice: Needs Android 13+ for glasses voice")
        VoiceStatus.NO_ON_DEVICE_RECOGNIZER, VoiceStatus.EXTERNAL_AUDIO_UNSUPPORTED ->
            VoiceLine("Voice: Not supported on this phone \u2014 you can type questions")
    }

    fun callbacks(requestMic: () -> Unit, requestNotifications: () -> Unit, onDone: () -> Unit): WizardCallbacks = WizardCallbacks(
        onPrimary = { primary(onDone) }, onSecondary = ::secondary, onBack = ::back,
        requestMic = requestMic, requestNotifications = requestNotifications, openAppSettings = ::openAppSettings,
        downloadPack = ::downloadPack, openSpeechSettings = ::openSpeechSettings, steps = StepCallbacks(),
    )

    private companion object { const val K_STEP = "step" }
}
