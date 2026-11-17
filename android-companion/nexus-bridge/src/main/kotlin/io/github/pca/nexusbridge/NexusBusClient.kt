package io.github.pca.nexusbridge

import io.github.pca.nexusport.NexusAction

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
}
