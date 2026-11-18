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
import com.rokid.context.phone.ui.status.asPauseHub
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
    data class Evidence(val bound: Boolean = false, val bindRefused: Boolean = false, val lastCode: Int? = null, val pcaLinkUp: Boolean = false, val bridgeUntrusted: Boolean = false)
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

    /** v3.1: a delivered suggestion goes to Nexus (only called while Nexus is the host; the sink's gates apply as they are). False = Nexus is not running or it was already sent. */
    fun suggestion(card: com.rokid.context.orchestration.proactive.DeliveredCard): Boolean = runtime?.feeder?.suggestion(card) ?: false

    /** v3.1: takes suggestions off Nexus by record id (dismissed or forgotten elsewhere). */
    fun withdrawSuggestions(ids: Collection<String>) { val r = runtime ?: return; r.feeder.cancelSuggestions(ids); ids.forEach { r.provider.withdraw(it) } }

    fun withdrawAllSuggestions() { val r = runtime ?: return; withdrawSuggestions(r.feeder.suggestionRefs()) }

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
            passFactory = { fencePass(PlaceGraph.fence, AskZoneFence::ticket, AskZoneFence::blocked) },
        )
        val popup = { hub.hudSettings.value.popupMs() }
        // The assist-button ask: PCA's ONE text backend (AppAnswerer over AskActions.askFrom, all its gates), delivered only through the sink. Both are inert while their switch is off.
        val answerer = com.rokid.context.phone.voice.AppAnswerer(
            ask = { q, _ -> com.rokid.context.orchestration.AskActions.askFrom(app, q, com.rokid.context.orchestration.AskActions.TYPED_SOURCE) },
            answerText = { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MemoryGraph.get(app).asks.byId(id)?.takeUnless { it.redacted }?.answer } },
            deliveryBlocked = AskZoneFence::blocked,
        )
        var providerRef: NexusProvider? = null // the ask handler delivers through the provider, which is built after it
        val asks = PcaNexusAsk(
            scope, gates, { q -> answerer.answer(q, com.rokid.context.voice.VoiceSource.PHONE) }, HudShow { e -> providerRef?.show(e) },
            askSources = { id -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MemoryGraph.get(app).asks.byId(id)?.takeIf { !it.redacted }?.let { AskSources.parseAll(it.sourcesJson).toSet() } } },
            popupMs = popup, speak = { store.switchesNow().tts }, log = { PcLog.i(TAG, it) },
        )
        val skills = PcaNexusSkills(
            gates,
            com.rokid.context.phone.voice.HubCaptureControl({ com.rokid.context.phone.hub.HubGraph.get(app).asPauseHub { com.rokid.context.phone.hub.HubPauses(MemoryGraph.get(app).db.pauseDao()).stored() } }, log = { PcLog.i(TAG, it) }),
            zonePaused = { com.rokid.context.phone.zone.ZoneHold.held || hub.activePauses.value.any { com.rokid.context.core.model.zone.ZonePause.isZoneRecord(it.pauseId) } },
            paused = { !hub.captureRunning() },
            stopped = { !hub.started },
            mark = { com.rokid.context.phone.canvas.MarkGraph.recorder(app).applyVoice(listOf(VoiceMark.Mark(null)), glasses = false, nowMs = System.currentTimeMillis()) },
            log = { PcLog.i(TAG, it) },
        )
        val provider = NexusProvider(ServiceBridgeLink(app.applicationContext), store::current, gates, AppActions(app, scope), scope, log = { PcLog.i(TAG, it) },
            switches = store::switchesNow, asks = asks, skills = skills, debt = store)
        providerRef = provider
        val feeder = NexusFeeder(
            scope, provider, popupMs = popup,
            askSources = { id -> MemoryGraph.get(app).asks.byId(id)?.takeIf { !it.redacted }?.let { AskSources.parseAll(it.sourcesJson).toSet() } },
            pinLive = { provider.liveSlots().hasPin() },
            log = { PcLog.i(TAG, it) },
            suggestionGate = { id -> com.rokid.context.orchestration.proactive.ProactiveGraph.delivery(app).publishable(id, com.rokid.context.orchestration.proactive.Surface.NEXUS) },
            suggestionFinalGate = { id -> com.rokid.context.orchestration.proactive.ProactiveGraph.delivery(app).publishableNow(id, com.rokid.context.orchestration.proactive.Surface.NEXUS) },
            withdrawOut = { id -> provider.withdraw(id) },
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
        // Any switch change pushes the feature bits at once (turning the voice ask off stops an open listening).
        scope.launch { store.switches.drop(1).collect { provider.refreshFeatures() } }

        scope.launch { var was = false; hub.activePauses.collect { p ->
            val now = p.isNotEmpty()
            if (now && !was) provider.trip(TripReason.PAUSE)
            was = now
            feeder.capture(if (now) HudEvent.CaptureState.State.PAUSED else HudEvent.CaptureState.State.CAPTURING)
        } }
        scope.launch { hub.link.wearing.collect { w -> if (w == false) provider.trip(TripReason.NOT_WORN) } }
        feeder.startPolling()
        // The bridge package was added, replaced or removed: its signer is verified again (a replaced bridge must not keep a connection it earned as another APK).
        try {
            app.applicationContext.registerReceiver(object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) { if (i?.data?.schemeSpecificPart == NexusPackages.BRIDGE) provider.onPackageChanged() }
            }, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
            })
        } catch (e: Exception) { PcLog.w(TAG, "nexus package receiver failed (${e.javaClass.simpleName})") }
        scope.launch { kotlinx.coroutines.flow.combine(provider.bound, provider.bind, provider.lastCode, hub.link.state) { b, bind, code, link ->
            Evidence(bound = b, bindRefused = bind == BridgeLink.Bind.REFUSED, lastCode = code, pcaLinkUp = link.carriesData, bridgeUntrusted = bind == BridgeLink.Bind.UNTRUSTED)
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

        /** A suggestion dismissed on Nexus is dismissed everywhere (feedback written, withdrawn from every other surface). Another kind of notice is none of its business. */
        override fun dismiss(ref: String) {
            if (!ref.startsWith(com.rokid.context.phone.hub.Hub.SUGGESTION_ID_PREFIX)) return
            scope.launch { try { com.rokid.context.orchestration.proactive.ProactiveGraph.actions(app).dismiss(ref) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "nexus dismiss failed (${e.javaClass.simpleName})") } }
        }

        /** Opens PCA. Best effort: Android may refuse a background start. */
        override fun more(ref: String) {
            try { app.startActivity(Intent(app, com.rokid.context.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { PcLog.w(TAG, "nexus more failed (${e.javaClass.simpleName})") }
        }
    }
}
