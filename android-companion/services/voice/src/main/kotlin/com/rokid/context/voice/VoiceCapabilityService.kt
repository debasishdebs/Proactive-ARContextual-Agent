package com.rokid.context.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * The §5.1.1 gate as a live value. [refresh] re-reads the platform; with `allowProbe` (the app is in the foreground)
 * it also probes external audio when no verdict exists for this OS build + app version + language, or when the last
 * one came from the production guard. The probe briefly uses the recognizer (the mic indicator shows): it is never run
 * from the background. One evaluation at a time.
 */
class VoiceCapabilityService(
    private val platform: VoicePlatform,
    private val records: ProbeRecords,
    private val language: () -> SpeechLanguage,
    private val scope: CoroutineScope,
    /**
     * Whether the app may use the mic for glasses audio now (the hub's FGS type, fix round 2). While false, glasses
     * asks are blocked ([VoiceCapabilities.micHeld]) and the guard records nothing: a recognizer without mic access
     * must never produce a stored verdict.
     */
    private val micHeld: StateFlow<Boolean>,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = {},
) {
    private val _caps = MutableStateFlow(VoiceCapabilities.UNKNOWN)
    val capabilities: StateFlow<VoiceCapabilities> = _caps.asStateFlow()
    private val mutex = Mutex()

    init { scope.launch { micHeld.collect { held -> _caps.update { it.copy(micHeld = held) } } } }

    private fun publish(c: VoiceCapabilities) { _caps.value = c.copy(micHeld = micHeld.value) }

    fun refresh(allowProbe: Boolean = false): Job = scope.launch(work) { mutex.withLock { evaluate(allowProbe) } }

    /** The guard fired: glasses voice is off now (fail closed); the next foreground refresh probes again. */
    fun onFallbackSuspected(): Job = scope.launch(work) {
        if (!micHeld.value) { log("guard skipped: mic not held"); return@launch }
        mutex.withLock {
            records.write(ProbeRecord(fingerprint(), ProbeVerdict.UNSUPPORTED, fromGuard = true))
            log("guard fired: glasses voice off until the next probe")
            evaluate(allowProbe = false)
        }
    }

    private fun fingerprint() = "${platform.fingerprint()}|${language().tag}"

    private suspend fun evaluate(allowProbe: Boolean) {
        val tag = language().tag
        // API 31–32 can't list packs, and a failed query is not proof of absence: both count as installed (ruling); the
        // recognizer then reports a missing pack as ERROR_LANGUAGE_UNAVAILABLE ("Download speech pack").
        val packs = if (platform.sdk < VoiceGate.GLASSES_MIN_SDK) null else try {
            platform.installedPacks()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            null
        }
        val packInstalled = packs?.let { PackMatch.installed(tag, it) } ?: true
        val fp = fingerprint()
        val record = records.read()?.takeIf { it.fingerprint == fp }
        fun inputs(probe: ProbeVerdict?, probing: Boolean) =
            VoiceInputs(platform.sdk, platform.onDeviceAvailable(), platform.micGranted(), packInstalled, probe, probing)
        val caps = VoiceGate.decide(inputs(record?.verdict, probing = false))
        publish(caps)
        val wantsProbe = allowProbe && platform.sdk >= VoiceGate.GLASSES_MIN_SDK && caps.phone == VoiceStatus.SUPPORTED &&
            (record == null || record.fromGuard)
        if (!wantsProbe) return
        publish(VoiceGate.decide(inputs(record?.verdict, probing = true)))
        val outcome = try {
            platform.runProbe(tag)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log("probe failed (${e.javaClass.simpleName})")
            null
        }
        val verdict = outcome?.let(ExternalAudioProbe::verdict)
        if (outcome != null) log("probe fed=${outcome.fedBytes} accepted=${outcome.acceptedBytes} capacity=${outcome.capacityBytes} peerClosed=${outcome.peerClosed} verdict=$verdict")
        if (verdict != null) records.write(ProbeRecord(fp, verdict))
        publish(VoiceGate.decide(inputs(verdict ?: record?.verdict, probing = false)))
    }
}
