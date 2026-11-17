package com.rokid.context.phone.nexus

import android.content.Context
import android.content.Intent
import com.rokid.context.canvas.marks.VoiceMark
import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.HudHost
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.carriesData
import com.rokid.context.core.model.popupMs
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.ForgetListener
import com.rokid.context.memory.forget.ForgetListeners
import com.rokid.context.orchestration.AskSources
import com.rokid.context.orchestration.AskZoneFence
import com.rokid.context.phone.hub.Hub
import com.rokid.context.provenance.ProvenanceGate
import com.rokid.context.work.frames.place.PlaceGraph
import io.github.pca.nexusport.HudEvent
import io.github.pca.nexusport.NexusActionHandler
import io.github.pca.nexusport.TripReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * v2.0: wires the Nexus provider into the running app. FLAG-OFF: [attach] builds the provider but it binds to nothing until the user picks Nexus as the HUD host (Settings > Rokid Nexus). Every close event of
 * PCA calls [trip]; it is a no-op while nothing is connected.
 * Close events wired here: pause (the hub's pause set becomes non-empty), not worn, hub stop, Forget (any user path: `ForgetListeners.userForget`), Delete all (`onDeleteAll`) and mode off (the host switch).
 * The zone hold calls [trip] from `ZoneGraph`. Logs: the sink's literal lines only.
 */
object NexusGraph {
    private const val TAG = "Nexus"

    /** What the screen and the tests read; null before the hub exists. */
    class Runtime internal constructor(val provider: NexusProvider, val feeder: NexusFeeder, val store: NexusHostStore)

    @Volatile var runtime: Runtime? = null
        private set

    /** What the settings screen reads besides the install state: the connection and the last HUD answer. */
    data class Evidence(val bound: Boolean = false, val bindRefused: Boolean = false, val lastCode: Int? = null, val pcaLinkUp: Boolean = false)
    private val _evidence = kotlinx.coroutines.flow.MutableStateFlow(Evidence())
    val evidence: kotlinx.coroutines.flow.StateFlow<Evidence> = _evidence
    private var forgetListener: ForgetListener? = null

    /** A close event. Safe before [attach] and while Nexus is not the host (nothing is connected then). */
    fun trip(reason: TripReason) { runtime?.provider?.trip(reason) }

    /** The hub stopped: everything on Nexus is withdrawn and the pin says Stopped. */
    fun onHubStopped() {
        val r = runtime ?: return
        r.provider.trip(TripReason.LINK_LOST)
        r.feeder.capture(HudEvent.CaptureState.State.STOPPED)
    }

    /** The recorder accepted a tap mark from the glasses. */
    fun onMarkSaved(ref: String) { runtime?.feeder?.markSaved(ref) }

    /** Delete all and every user Forget close the HUD; retention expiry ([ForgetListener.onFramesForgotten], [ForgetListener.onRangeForgotten]) deliberately does not. */
    internal fun closeListener(trip: (TripReason) -> Unit): ForgetListener = object : ForgetListener {
        override suspend fun onUserForget() = trip(TripReason.FORGET)
        override suspend fun onDeleteAll() = trip(TripReason.DELETE_ALL)
    }

    /** The nudge rows to look at: only a popup row (`toast`), not redacted, and fresh (it is the one PCA itself would pop up now). */
    internal fun freshNudge(row: com.rokid.context.memory.db.NudgeEntity?, nowMs: Long): NexusNudge? =
        row?.takeIf { !it.redacted && it.shownOn == "toast" && nowMs - it.ts in 0..FRESH_MS }?.let { NexusNudge(it.id, it.text, sourcesOf(it.sourceIdsJson)) }

    internal fun sourcesOf(json: String): Set<String> = try {
        val a = JSONArray(json); (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
    } catch (_: Exception) { emptySet() }

    private const val FRESH_MS = 30_000L

    /** Builds the provider over the real fences and starts the feeds. Called once, when the hub is built. */
    @Synchronized fun attach(app: Context, scope: CoroutineScope, hub: Hub) {
        if (runtime != null) return
        val store = NexusHostStore.of(app)
        val gates = PcaNexusGates(
            sourcesLive = { ids -> ProvenanceGate.forGraph(MemoryGraph.get(app)).allLive(ids) },
            zoneFence = { PlaceGraph.fence.blocked() },
            askFence = { AskZoneFence.blocked() },
            paused = { !hub.captureRunning() },
            worn = { hub.wornNow() == true || (hub.wornNow() == null && hub.wearEstimate.active.value) },
        )
        val provider = NexusProvider(ServiceBridgeLink(app.applicationContext), store::current, gates, AppActions(app, scope), scope, log = { PcLog.i(TAG, it) })
        val popup = { hub.hudSettings.value.popupMs() }
        val feeder = NexusFeeder(
            scope, provider, popupMs = popup,
            askSources = { id -> MemoryGraph.get(app).asks.byId(id)?.takeIf { !it.redacted }?.let { AskSources.parseAll(it.sourcesJson).toSet() } },
            pinLive = { provider.liveSlots().hasPin() },
            log = { PcLog.i(TAG, it) },
        )
        runtime = Runtime(provider, feeder, store)

        // Delete all and every user Forget close the HUD (and ask Nexus to wipe its history). Retention expiry does not.
        forgetListener?.let { ForgetListeners.unregister(it) }
        ForgetListeners.register(closeListener(::trip).also { forgetListener = it })

        scope.launch { store.host.drop(1).collect { h ->
            provider.refresh() // a switch away from Nexus is a MODE_OFF trip inside
            if (h != HudHost.NEXUS) feeder.reset()
            hub.updateSettings { it } // pushes `pc_settings.hudHost` and a fresh frame: the old host's cards go
        } }
        provider.refresh()

        scope.launch { var was = false; hub.activePauses.collect { p ->
            val now = p.isNotEmpty()
            if (now && !was) provider.trip(TripReason.PAUSE)
            was = now
            feeder.capture(if (now) HudEvent.CaptureState.State.PAUSED else HudEvent.CaptureState.State.CAPTURING)
        } }
        scope.launch { hub.link.wearing.collect { w -> if (w == false) provider.trip(TripReason.NOT_WORN) } }
        feeder.startPolling()
        scope.launch { kotlinx.coroutines.flow.combine(provider.bound, provider.bind, provider.lastCode, hub.link.state) { b, bind, code, link ->
            Evidence(bound = b, bindRefused = bind == BridgeLink.Bind.REFUSED, lastCode = code, pcaLinkUp = link.carriesData)
        }.collect { _evidence.value = it } }

        scope.launch { PlaceGraph.port.card.collect { feeder.placeCard(it) } }
        scope.launch { com.rokid.context.phone.voice.VoiceGraph.get(app).hubVoice.overlay.collect { o: Overlay? -> feeder.answer(o) } }
        scope.launch { MemoryGraph.get(app).nudges.recent(1).collect { rows -> feeder.nudge(freshNudge(rows.firstOrNull(), System.currentTimeMillis())) } }
    }

    /** What a tap on a Nexus notice does in PCA. Gets PCA's own ref, never card text. */
    private class AppActions(private val app: Context, private val scope: CoroutineScope) : NexusActionHandler {
        /** PCA's own phone-origin mark path (and its refusals), as if the user had marked on the phone. */
        override fun mark(ref: String) {
            scope.launch {
                try { com.rokid.context.phone.canvas.MarkGraph.recorder(app).applyVoice(listOf(VoiceMark.Mark(null)), glasses = false, nowMs = System.currentTimeMillis()) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "nexus mark failed (${e.javaClass.simpleName})") }
            }
        }

        /** Opens PCA. Best effort: Android may refuse a background start. */
        override fun more(ref: String) {
            try { app.startActivity(Intent(app, com.rokid.context.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { PcLog.w(TAG, "nexus more failed (${e.javaClass.simpleName})") }
        }
    }
}
