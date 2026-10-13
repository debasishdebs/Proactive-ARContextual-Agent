package com.rokid.context.phone.ui.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.context.core.model.LinkState
import com.rokid.context.phone.voice.PackDownload
import com.rokid.context.ai.UrlNormalizer
import com.rokid.context.prompt.Persona
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
import kotlinx.coroutines.withContext

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

    init {
        viewModelScope.launch(work) {
            val seed = deps.ai.seeded()
            local.update { it.copy(ai = it.ai.copy(seed = seed)) }
        }
    }

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
            Step.AI -> if (st.ai.canContinue) continueAi()
            Step.PERSONA -> savePersona()
            Step.DONE -> finish(onDone)
            else -> next()
        }
    }

    /** The text button under it ("Set up later"). */
    fun secondary() {
        when (local.value.step) {
            Step.GLASSES -> skipGlasses()
            Step.AI -> go(Step.PERSONA)
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

    // --- AI endpoint -------------------------------------------------------------------------------------------

    private fun editAi(f: (AiStepState) -> AiStepState) = local.update { it.copy(ai = f(it.ai).let { n -> n.copy(result = null, urlError = null) }) }

    fun selectSeed() = editAi { it.copy(preset = null) }
    fun selectPreset(p: AiPreset) = editAi { it.copy(preset = p, model = p.defaultModel, apiVersion = "", url = "", key = "") }
    fun onUrl(v: String) = editAi { it.copy(url = v) }
    fun onApiVersion(v: String) = editAi { it.copy(apiVersion = v) }
    fun onModel(v: String) = editAi { it.copy(model = v) }
    fun onKey(v: String) = editAi { it.copy(key = v) }

    /** Test: the seed as it is, or the typed endpoint (saved, key to the vault only, then one tiny call). */
    fun testAi() {
        val ai = local.value.ai
        if (ai.testing) return
        val preset = ai.preset
        val draft = if (preset == null) null else {
            val url = UrlNormalizer.normalize(ai.url, preset.kind, hasKey = preset.hasKey && ai.key.isNotBlank()).getOrNull()
            if (url == null) {
                local.update { it.copy(ai = it.ai.copy(urlError = "Check the address", result = null)) }
                return
            }
            EndpointDraft(preset, url, ai.apiVersion.takeIf { preset == AiPreset.AZURE && it.isNotBlank() }, ai.model.trim(), ai.key.takeIf { preset.hasKey && it.isNotBlank() })
        }
        if (draft == null && ai.seed == null) return
        local.update { it.copy(ai = it.ai.copy(testing = true, result = null, urlError = null)) }
        viewModelScope.launch(work) {
            val outcome = try {
                val r = if (draft != null) deps.ai.testDraft(draft).getOrThrow() else deps.ai.testSeed(ai.seed!!)
                TestOutcome(r.ok, if (r.ok) "Works \u00B7 ${r.latencyMs} ms" else r.message)
            } catch (c: kotlin.coroutines.cancellation.CancellationException) {
                throw c
            } catch (e: Exception) {
                TestOutcome(false, "Check the address, key and model") // never the provider's or the exception's text
            }
            local.update { it.copy(ai = it.ai.copy(testing = false, result = outcome, key = if (outcome.ok) "" else it.ai.key)) }
        }
    }

    private fun continueAi() {
        val preset = local.value.ai.preset
        viewModelScope.launch(work) {
            if (preset != null) deps.ai.use(preset.endpointId)
            withContext(main) { go(Step.PERSONA) }
        }
    }

    // --- Persona & language ------------------------------------------------------------------------------------

    fun setLanguage(l: ReplyLanguage) = local.update { it.copy(persona = it.persona.copy(language = l)) }
    fun setStyle(s: PersonaStyle) = local.update { it.copy(persona = it.persona.copy(style = s)) }

    private fun savePersona() {
        val p = local.value.persona
        viewModelScope.launch(work) {
            deps.persona().set(Persona(p.style.text, p.language))
            withContext(main) { go(Step.GESTURES) }
        }
    }

    private fun finish(onDone: () -> Unit) {
        viewModelScope.launch(work) {
            deps.markComplete()
            withContext(main) { onDone() }
        }
    }

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
        downloadPack = ::downloadPack, openSpeechSettings = ::openSpeechSettings, steps = StepCallbacks(
            selectSeed = ::selectSeed, selectPreset = ::selectPreset, onUrl = ::onUrl, onApiVersion = ::onApiVersion,
            onModel = ::onModel, onKey = ::onKey, test = ::testAi, setLanguage = ::setLanguage, setStyle = ::setStyle,
        ),
    )

    private companion object { const val K_STEP = "step" }
}
