package io.github.pca.nexusbridge

import io.github.pca.nexusport.NexusAction
import io.github.pca.nexusport.NexusResult

/**
 * PCA's OWN view of the Nexus plugin client: the only thing [BridgeRenderer] knows about Nexus, so the renderer is tested against a fake (upstream has no public fake transport) and a different
 * transport (Option B, over the Nexus bus) can replace [SdkNexusBusClient] without touching anything else. Every method returns a `NexusResult` code and never throws.
 */
interface NexusBusClient {
    fun showNotice(title: String, body: String, ttlMs: Long, wake: Boolean, actions: List<NexusAction>): Int
    fun hideNotice(): Int
    fun showPin(title: String, lines: List<String>, ttlMs: Long): Int
    fun hidePin(): Int
    fun speak(text: String): Int
    fun stopSpeech(): Int
    /** Asks the hub to forget every notice shown. [onAccepted] runs once the phone hub holds the request. */
    fun forgetNotices(onAccepted: () -> Unit): Int

    // ---- T5: the assist-button ask. Defaults keep older fakes compiling. ----

    /** Opens ONE Nexus speech-to-text session (utterance mode). The microphone belongs to Nexus: the bridge never records. Results arrive on [cb] (main thread in the SDK). */
    fun startListening(cb: ListenCallbacks): Int = NexusResult.NOT_AVAILABLE
    fun stopListening(): Int = NexusResult.OK

    /** Moves the hub's assist-takeover switch for this plugin (Plugin access). PCA keeps it OFF unless the user switched the ask on. */
    fun setAssistTakeover(enabled: Boolean): Int = NexusResult.OK
}

/** What a Nexus speech session reports. [onFinal] carries the transcript: it must never be logged. */
interface ListenCallbacks {
    fun onStarted()
    fun onFinal(text: String)
    fun onStopped(reason: ListenStop)
}

enum class ListenStop { COMPLETED, CANCELLED, NO_SPEECH, DENIED_BUSY, FAILED }
