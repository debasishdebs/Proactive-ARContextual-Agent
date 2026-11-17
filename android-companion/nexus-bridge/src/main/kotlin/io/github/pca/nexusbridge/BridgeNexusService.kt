package io.github.pca.nexusbridge

import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSkillInvocation
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusTtsCallbacks
import com.anezium.rokidbus.client.plugin.NexusTtsDoneReason
import com.anezium.rokidbus.client.plugin.NexusTtsSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import com.anezium.rokidbus.shared.skills.SkillDispatch
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * The Nexus plugin (headless). It draws nothing by itself: it hands its SDK client to [BridgeHost] and relays notice taps and the end of a speech. It declares stt, tts and assistant, but opens NO speech
 * session and holds NO assist takeover unless the user switched the ask on in PCA (feature bits STT and ASSIST). T5: `AI_ASSIST` -> [BridgeRenderer.assistOpened]. T6: the four skills answer through
 * [BridgeHost.skills]. The service never touches the microphone: Nexus owns audio.
 */
class BridgeNexusService : NexusPluginService() {
    private var ttsSession: NexusTtsSession? = null

    private val ttsCallbacks = object : NexusTtsCallbacks {
        override fun onTtsStarted(utteranceId: String) = Unit
        override fun onTtsDone(utteranceId: String, reason: NexusTtsDoneReason) { BridgeHost.renderer.speechDone() }
    }

    override fun onCreate() {
        super.onCreate()
        BridgeHost.bus = SdkNexusBusClient(
            client = { nexusClient },
            tts = { ttsSession ?: nexusTtsSession(ttsCallbacks).also { ttsSession = it } },
            speech = { cb -> nexusSpeechSession(cb) },
        )
    }

    override fun onDestroy() {
        BridgeHost.renderer.stopListening()
        BridgeHost.bus = null
        ttsSession = null
        super.onDestroy()
    }

    /** The hub (re)registered the plugin: make its takeover switch match PCA's (OFF unless the user switched the ask on). */
    override fun onNexusRegistrationState(result: Int) { BridgeHost.renderer.applyTakeover() }

    override fun onNexusOpen(openType: String) {
        if (openType == PluginOpenTypes.AI_ASSIST) BridgeHost.renderer.assistOpened()
    }

    private val skillWorkers = Executors.newCachedThreadPool { r -> Thread(r, "pca-skill-reply").apply { isDaemon = true } }

    override fun onNexusSkillInvoked(invocation: NexusSkillInvocation) {
        // Never on the main thread: the phone may take seconds. Exactly one answer per invocation.
        skillWorkers.execute {
            when (val reply = BridgeHost.skills.invoke(invocation.operationId)) {
                is SkillReply.Complete -> invocation.complete(JSONObject().put("state", reply.state))
                is SkillReply.Failed -> invocation.fail(reply.code, if (reply.dispatch == SkillDispatchState.UNKNOWN) SkillDispatch.UNKNOWN else SkillDispatch.NONE)
            }
        }
    }

    override fun onNexusNoticeAction(id: String) { BridgeHost.renderer.noticeAction(id) }

    override fun onNexusOpen() = Unit      // a deliberate open from the launcher: nothing to draw
    override fun onNexusClose() = Unit
    override fun onNexusInput(event: NexusInputEvent) = Unit
}
