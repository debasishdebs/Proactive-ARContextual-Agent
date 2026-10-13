package com.rokid.context.phone.voice

import com.rokid.context.core.model.Overlay
import com.rokid.context.phone.hub.HubVoice
import com.rokid.context.voice.PhoneVoiceState
import com.rokid.context.voice.SpeechLanguage
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoiceCapabilityService
import com.rokid.context.voice.VoicePipeline
import com.rokid.context.voice.VoiceSettings
import com.rokid.context.voice.VoiceSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PipelineHubVoice(private val pipeline: VoicePipeline, capabilities: StateFlow<VoiceCapabilities>, scope: CoroutineScope) : HubVoice {
    override val overlay: StateFlow<Overlay?> = pipeline.overlay
    override val askBlockedReason: StateFlow<String?> =
        capabilities.map { it.askBlockedReason }.stateIn(scope, SharingStarted.Eagerly, capabilities.value.askBlockedReason)
    override fun onListenStart() = pipeline.glassesListenStart()
    override fun onAudio(pcm: ByteArray) = pipeline.glassesAudio(pcm)
    override fun onListenEnd() = pipeline.glassesListenEnd()
    override fun onCancel() = pipeline.glassesCancel()
    override fun onLinkLost() = pipeline.linkLost()
}

/**
 * Home's mic: the pipeline's phone state plus Settings' "Use the phone mic for Ask" switch. [settings] is null until the
 * stored values have loaded, and the switch then counts as off (privacy ruling, fix round 1): a tap is a no-op with the
 * [LOADING] note, never an ask the user turned off. [PhoneVoiceState.micOff] is applied on top of every pipeline update
 * (the pipeline publishes it as false). Turning the switch off while Home is listening or thinking cancels that ask; a
 * glasses ask is left alone.
 */
class PipelinePhoneVoice(private val pipeline: VoicePipeline, private val settings: StateFlow<VoiceSettings?>, scope: CoroutineScope) : PhoneVoice {
    private val loadingTapped = MutableStateFlow(false)

    override val state: StateFlow<PhoneVoiceState> = combine(pipeline.phone, settings, loadingTapped) { p, s, tapped -> view(p, s, tapped) }
        .stateIn(scope, SharingStarted.Eagerly, view(pipeline.phone.value, settings.value, false))

    init {
        scope.launch {
            settings.collect { s ->
                if (s != null) loadingTapped.value = false
                val p = pipeline.phone.value
                if (s?.phoneMic != true && (p.listening || p.thinking)) pipeline.phoneCancel()
            }
        }
    }

    private fun view(p: PhoneVoiceState, s: VoiceSettings?, tapped: Boolean) =
        p.copy(micOff = s?.phoneMic != true, error = if (s == null && tapped) LOADING else p.error)

    override fun start() {
        val s = settings.value
        if (s == null) { loadingTapped.value = true; return }
        if (s.phoneMic) pipeline.phoneStart()
    }
    override fun stop() = pipeline.phoneStop()
    override fun cancel() = pipeline.phoneCancel()

    companion object {
        const val LOADING = "Voice is still loading, try again in a moment"
    }
}

/** The store's settings once loaded, null before (the defaults must not count as the user's choice). */
internal fun VoiceSettingsStore.loadedSettings(scope: CoroutineScope): StateFlow<VoiceSettings?> =
    combine(settings, loaded) { s, isLoaded -> s.takeIf { isLoaded } }
        .stateIn(scope, SharingStarted.Eagerly, settings.value.takeIf { loaded.value })

/**
 * Settings › Phone camera & mic over the stores. A new language needs its own pack and probe (spec §5.1.1): once the
 * choice is stored, [onForeground] re-checks with a probe (the user is in Settings, so the app is in front).
 */
internal class StoreVoiceControls(
    private val store: VoiceSettingsStore,
    private val service: VoiceCapabilityService,
    private val scope: CoroutineScope,
    private val onForeground: () -> Unit,
    private val download: suspend (localeTag: String) -> PackDownload,
    private val openSettings: () -> Unit,
) : VoiceControls {
    override val capabilities: StateFlow<VoiceCapabilities> = service.capabilities
    override val settings: StateFlow<VoiceSettings> = store.settings

    override fun setLanguage(language: SpeechLanguage) {
        val stored = store.update { it.copy(language = language) }
        scope.launch { stored.join(); onForeground() }
    }

    override fun setPhoneMic(on: Boolean) { store.update { it.copy(phoneMic = on) } }
    override fun recheck() = onForeground()

    /**
     * A download can take up to a minute and finish after the app left the front (fix round 1), so the gate is then
     * re-read without a probe and the foreground is not re-entered; the next resume probes if still needed.
     */
    override suspend fun downloadPack(): PackDownload {
        val r = download(settings.value.language.tag)
        service.refresh()
        return r
    }

    override fun openSpeechSettings() = openSettings()
}

/**
 * The gate for the current language, re-read on every language change, without a probe (preflight F2): that includes
 * the stored language loading after a sticky background restart. The first value is the initial read, so a load that
 * lands before collection starts is never missed. The probe uses the mic, so it runs only from the foreground
 * ([StoreVoiceControls], app resume).
 */
internal fun watchLanguage(scope: CoroutineScope, settings: StateFlow<VoiceSettings>, capabilities: VoiceCapabilityService): Job =
    scope.launch { settings.map { it.language }.distinctUntilChanged().collect { capabilities.refresh() } }
