package io.github.pca.nexusbridge

import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusTtsCallbacks
import com.anezium.rokidbus.client.plugin.NexusTtsDoneReason
import com.anezium.rokidbus.client.plugin.NexusTtsSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/**
 * The Nexus plugin (headless). It draws nothing by itself: it hands its SDK client to [BridgeHost] and relays notice taps and the end of a speech. It declares stt, tts and assistant, but registers NO speech
 * session and NO assist takeover; those stay off until T5 and the user's switch.
 */
class BridgeNexusService : NexusPluginService() {
    private var ttsSession: NexusTtsSession? = null

    private val ttsCallbacks = object : NexusTtsCallbacks {
        override fun onTtsStarted(utteranceId: String) = Unit
        override fun onTtsDone(utteranceId: String, reason: NexusTtsDoneReason) { BridgeHost.renderer.speechDone() }
    }

    override fun onCreate() {
        super.onCreate()
        BridgeHost.bus = SdkNexusBusClient(client = { nexusClient }, tts = { ttsSession ?: nexusTtsSession(ttsCallbacks).also { ttsSession = it } })
    }

    override fun onDestroy() {
        BridgeHost.bus = null
        ttsSession = null
        super.onDestroy()
    }

    override fun onNexusNoticeAction(id: String) { BridgeHost.renderer.noticeAction(id) }

    override fun onNexusOpen() = Unit
    override fun onNexusClose() = Unit
    override fun onNexusInput(event: NexusInputEvent) = Unit
}
