package com.rokid.context.phone.voice

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.AskActions
import com.rokid.context.phone.hub.ContextHubService
import com.rokid.context.phone.hub.HubVoice
import com.rokid.context.voice.PrefsProbeRecords
import com.rokid.context.voice.VoiceCapabilityService
import com.rokid.context.voice.VoicePipeline
import com.rokid.context.voice.VoiceSettingsStore
import com.rokid.context.voice.android.AndroidOnDeviceStt
import com.rokid.context.voice.android.AndroidSpeaker
import com.rokid.context.voice.android.AndroidVoicePlatform
import com.rokid.context.voice.android.AudioRoutes
import com.rokid.context.voice.android.SpeechPacks
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Process-wide voice (no DI in v1): settings, the §5.1.1 gate, the on-device recognizer, phone TTS and the one
 * [VoicePipeline]. Built on Main without I/O (prefs open on IO, TTS on first answer). The pipeline runs on Main.
 * It may be built by a sticky background restart of the hub service: nothing here probes until [onForeground].
 */
class VoiceGraph private constructor(private val app: Context) {
    private val errors = CoroutineExceptionHandler { _, t -> Log.w(TAG, "voice job failed: ${t.javaClass.simpleName}") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)

    val settings = VoiceSettingsStore({ app.getSharedPreferences("voice_settings", Context.MODE_PRIVATE) }, scope)
    val capabilities = VoiceCapabilityService(
        AndroidVoicePlatform(app), PrefsProbeRecords({ app.getSharedPreferences("voice_probe", Context.MODE_PRIVATE) }),
        language = { settings.settings.value.language }, scope = scope, micHeld = ContextHubService.micHeld, log = { Log.i(TAG, it) },
    )
    val pipeline = VoicePipeline(
        scope, AndroidOnDeviceStt(app), capabilities.capabilities, { settings.settings.value.language },
        AppAnswerer(
            ask = { q, source -> AskActions.askFrom(app, q, source) },
            answerText = { id -> withContext(Dispatchers.IO) { MemoryGraph.get(app).asks.byId(id)?.takeUnless { it.redacted }?.answer } },
            offNotice = { id ->
                withContext(Dispatchers.IO) {
                    MemoryGraph.get(app).proposals.byId(id)?.takeIf { it.state == com.rokid.context.memory.ProposalState.SUPPRESSED.name }
                        ?.let { com.rokid.context.connectors.actions.ActionParams.typeOf(it.type) }?.let(com.rokid.context.connectors.actions.ActionCopy::offNotice)
                }
            },
        ),
        AndroidSpeaker(app), bluetoothMedia = { AudioRoutes.hasBluetoothMedia(app) },
        onFallbackSuspected = { capabilities.onFallbackSuspected() },
        log = { Log.i(TAG, it) },
    )
    /** G7: Battery Saver, for the glasses tap's blocked reason (the ask path refuses the ask itself). */
    private val batterySaver = com.rokid.context.orchestration.BatterySaver.observe(app)
        .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, com.rokid.context.orchestration.BatterySaver.isOn(app))
    val hubVoice: HubVoice = PipelineHubVoice(pipeline, capabilities.capabilities, scope, batterySaver)
    val phoneVoice: PhoneVoice = PipelinePhoneVoice(pipeline, settings.loadedSettings(scope), scope)
    val controls: VoiceControls = StoreVoiceControls(
        settings, capabilities, scope, onForeground = { onForeground() },
        download = { tag ->
            when (SpeechPacks.download(app, tag)) {
                SpeechPacks.Download.SUCCESS -> PackDownload.DONE
                SpeechPacks.Download.SCHEDULED -> PackDownload.SCHEDULED
                SpeechPacks.Download.FAILED -> PackDownload.FAILED
                SpeechPacks.Download.UNSUPPORTED -> PackDownload.NEEDS_SETTINGS
            }
        },
        openSettings = {
            runCatching { app.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.i(TAG, "speech settings unavailable (${it.javaClass.simpleName})") }
        },
    )

    init {
        watchLanguage(scope, settings.settings, capabilities) // the first read and every language change; never a probe
    }

    /**
     * The app is in front: re-read the gate, probe external audio if needed, take the mic FGS type if just granted or
     * missing after a sticky restart. [resume] numbers an activity resume; null (Settings) counts as the latest one.
     */
    fun onForeground(resume: Long? = null) {
        capabilities.refresh(allowProbe = true)
        ContextHubService.refreshForegroundTypes(app, resume)
    }

    /** A typed Ask's answer, mirrored to the HUD when linked (spec §2.4). */
    fun mirrorTyped(askId: String) {
        scope.launch {
            val a = withContext(Dispatchers.IO) { MemoryGraph.get(app).asks.byId(askId) } ?: return@launch
            if (!a.redacted) pipeline.showTypedAnswer(a.id, a.question, a.answer)
        }
    }

    companion object {
        const val TAG = "PcVoice"
        @Volatile private var graph: VoiceGraph? = null

        fun get(context: Context): VoiceGraph = graph ?: synchronized(this) { graph ?: VoiceGraph(context.applicationContext).also { graph = it } }
    }
}
