package com.rokid.context.phone.hub

import com.rokid.context.core.log.PcLog
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.CapturePlanAck
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.TraceChunk
import com.rokid.context.link.AckVerdict
import com.rokid.context.link.PlanAckGate
import com.rokid.context.link.PlanPeer
import com.rokid.context.link.PlanPeerTracker
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.MotionHint
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.MotionContextMessage
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PlaceCardInfo
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.Wire
import com.rokid.context.core.model.carriesData
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.GlassesEvent
import com.rokid.context.link.GlassesLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.UUID

/**
 * Phone hub (spec §1.7): handles glasses messages, keeps the pause set, ingests frames and broadcasts `StateFrame`s.
 * Runs on [scope] (Main in the app, so [GlassesLink] calls stay on its thread); disk and DB work hop to [io].
 * The phone is the source of truth for everything except whether capture is allowed (pause union, spec §5.5).
 * Never reconnects by itself after a close on the glasses: only [requestReconnect] or the link's app watcher do (§1.7).
 */
class Hub(
    private val scope: CoroutineScope,
    val link: GlassesLink,
    private val pauses: HubPauses,
    private val settings: HubSettingsStore,
    private val ingestor: FrameIngestor,
    private val sources: HubSources,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Starts [ContextHubService] (which calls [start]); used when Reconnect is tapped while the hub is not running. */
    private val startService: () -> Unit = {},
    /** Voice (Phase 6): glasses voice events go here; its overlay goes into every StateFrame. */
    private val voice: HubVoice = HubVoice.None,
    /** Actions (Phase 7): HUD Confirm/Undo go here; its Confirm/undo-toast overlay goes into every StateFrame. */
    private val actions: HubActions = HubActions.None,
    /** Nudges (Phase 7): the latest nudge line and the HUD nudge overlay. */
    private val nudges: HubNudges = HubNudges.None,
    /** Fix wave H2: guards for reopening the glasses app after Rokid closes it; null = never reopen (tests, no phone services). */
    private val reopenGuards: RelaunchGuards? = null,
    /** "Reopen after Rokid closes it" (the same switch as "Keep running when nearby"). */
    private val reopenEnabled: () -> Boolean = { true },
    /** Fix wave I2: the wearer's exit, persisted: a phone restart must never reopen an app the wearer exited. */
    private val standDown: StandDown = InMemoryStandDown(),
    private val relaunchStore: RelaunchStore = InMemoryRelaunchStore(),
    /** Battery wave A §7: true while the phone screen is on and unlocked; forwarded as `pc_phone_ctx`. */
    private val phoneUse: StateFlow<Boolean> = MutableStateFlow(false),
    /** Wave B: the context hub's `pc_motion_ctx` message; null = nothing to say (a revoke is sent on the way to null). */
    private val motionContext: StateFlow<MotionContextMessage?> = MutableStateFlow(null),
    /** Wave B: a `pc_motion_hint` from the glasses goes to the context hub. */
    private val onMotionHint: (MotionHint) -> Unit = {},
    /** Wave B: the context hub starts and stops with this hub (both idempotent on its side). */
    private val ctxStart: () -> Unit = {},
    private val ctxStop: () -> Unit = {},
    /** Wave B: the current `applyToCapture` setting, used by the synthesized UNKNOWN revoke (null = the last sent value's). */
    private val applyToCapture: (() -> Boolean)? = null,
    /** Slice 3a: this phone install's id; an ack on `pc_capture_plan_ack` must echo it (S74). Wired by a later task. */
    private val phoneInstall: () -> String = { "" },
    /** Slice 3a: an ack of this install and this session (the caller matches its outstanding gen). Stale acks never reach it. */
    private val onPlanAck: (CapturePlanAck) -> Unit = {},
    /** Slice 3a: the capabilities in the glasses' unsolicited caps ack of this session. */
    private val onPlanCaps: (List<String>) -> Unit = {},
    /** Slice 3a: a `pc_mark`, accepted whatever its age (occurrence time, not freshness). Idempotency is the receiver's. */
    private val onMark: suspend (MarkMessage, com.rokid.context.canvas.marks.MarkArrival) -> Boolean = { _, _ -> true },
    /** v0.9.6 slice C: a `pc_mark_photo` (the photo of a mark could not be taken). Best effort: nothing is acknowledged, a lost report is the recorder's photo timeout. */
    private val onMarkPhoto: suspend (com.rokid.context.core.model.MarkPhotoResult) -> Unit = {},
    /** A glasses `ListenStart` reached the phone (phone clock): the mark recorder joins it with a `listen = true` mark within 3 s. */
    private val onGlassesListenStart: suspend (Long) -> Unit = {},
    /** Slice 3a: a `pc_trace_chunk` (the transfer contract is a later task). */
    private val onTraceChunk: suspend (TraceChunk) -> Unit = {},
    /** Slice 3a: a glasses session is ready (its [sessionStartMs] is passed): the plan sender and the trace control re-send their desired state. */
    private val onSessionReady: suspend (Long) -> Unit = {},
    /** LGC4P: the Developer-mode switch (`DeveloperModeStore.enabled`), read at every settings push as `HudSettings.diagRecord`; never read from the stored settings. */
    private val devMode: StateFlow<Boolean> = MutableStateFlow(false),
    /** LGC4P: re-sends the trace control while the glasses have not reported the diagnostic flag enabled; null = none (tests). */
    private val diagControl: com.rokid.context.phone.hub.plan.DiagControlRetry? = null,
    /** LGC6-min: where every `Hello`'s `diagFlagState` / `traceBlocker` goes (the retry reads it); null = nobody listens (tests, no trace services). */
    private val diagStatus: com.rokid.context.phone.hub.plan.MutableGlassesDiagStatus? = null,
    /** v0.9.9: a pair exists (Hi Rokid authorized this app). With [reopenEnabled] it gates every AUTOMATIC connect ([BackgroundPolicy.linkMachineryAllowed]); a tapped Reconnect never asks. */
    private val paired: () -> Boolean = { true },
    /** v0.9.9: a positive signal for the watchdog's back-off (link up, wear event, app in front, a tapped Reconnect). */
    private val onPositiveSignal: () -> Unit = {},
    /** v0.9.9: our app is in front: the glasses link connects as before even with the setting off (only the BACKGROUND stops, see [BackgroundPolicy]). */
    private val inFront: () -> Boolean = { false },
    /** Pause zones: the phone is within a zone's approach band or inside it; sent at every settings push as `HudSettings.zoneApproach` (the glasses pause locally if the link drops). */
    private val zoneApproach: () -> Boolean = { false },
    /** Pause zones: a zone pause could not be stored (or the zones cannot be read) and is held in memory; sent at every settings push as `HudSettings.zoneEnforce` so the glasses pause too. */
    private val zoneEnforce: () -> Boolean = { false },
    /** Pause zones: called at EVERY [start] (first start, a restart after [stop], a recovery in a surviving process): the zones are judged again from "nothing known". */
    private val zoneStart: () -> Unit = {},
    /** Place Guide PG3: the newest place card (PlaceGraph.port.card) and the way to drop it. Never stored; it expires after [PlaceCardInfo.SHOW_MS]. */
    private val placeCards: StateFlow<com.rokid.context.work.frames.place.PlaceCard?> = MutableStateFlow(null),
    private val placeClear: () -> Unit = {},
    /** B2: where the place card sits on the glasses HUD (Settings > Place Guide, Developer mode). CENTRE (the default) sends nothing extra. */
    private val placeSide: () -> com.rokid.context.core.model.PlaceSide = { com.rokid.context.core.model.PlaceSide.CENTRE },
    /** B3: the delivery state of a place card (spec 2026-11-24): SENT when a state frame carrying it was sent, NOT_SENT_* when it ended (12 s, replaced, withdrawn) without one. [cardId] is the card's own id; [state] a [com.rokid.context.memory.place.CardDelivery] value. Writes the phone-only card history. */
    private val placeDelivery: (cardId: String, state: String) -> Unit = { _, _ -> },
    /** Clears the card only if it is still the instance [cardId]; false = a different (newer) card or none, nothing cleared. Atomic in production (PlaceCardHolder.clearIf). */
    private val placeClearIf: (cardId: String) -> Boolean = { id -> if (placeCards.value?.cardId == id) { placeClear(); true } else false },
    /** v3.1: the delivered suggestion card (the same wire overlay as a place card, id `sg_...`); [suggestionClearIf] drops it by id; [onSuggestionDismissed] = the wearer dismissed it by head motion (feedback + withdraw everywhere). */
    private val suggestionCards: StateFlow<SuggestionHudHolder.Shown?> = MutableStateFlow(null),
    private val suggestionClearIf: (id: String) -> Boolean = { false },
    private val onSuggestionDismissed: (id: String) -> Unit = {},
    /** v2.0 T7: who draws the HUD cards (the Rokid Nexus opt-in choice, default PCA's glasses app). Read at every settings push (`HudSettings.hudHost`) and every frame; never stored with the HUD settings. */
    private val hudHost: () -> com.rokid.context.core.model.HudHost = { com.rokid.context.core.model.HudHost.PCA },
    /** v2.0: the hub stopped (the Nexus bridge hides everything it shows). */
    private val onStopped: () -> Unit = {},
) : GlassesStatusSource {
    private fun linkAllowed(): Boolean = inFront() || BackgroundPolicy.linkMachineryAllowed(reopenEnabled(), paired())
    /** Phone clock of the last tapped Reconnect (0 = none): keeps the hub service wanted for [BackgroundPolicy.USER_ASK_HOLD_MS]. */
    @Volatile private var userAskedAtMs = 0L
    fun busy(): Boolean = userAskedRecently() || voice.overlay.value != null
    fun userAskedRecently(): Boolean = userAskedAtMs != 0L && clock() - userAskedAtMs < BackgroundPolicy.USER_ASK_HOLD_MS
    /** A positive signal: the watchdog's and the relauncher's back-off start again. */
    fun positiveSignal() { relauncher?.onPositiveSignal(); onPositiveSignal() }

    private val relauncher: GlassesRelauncher? = reopenGuards?.let {
        // Fix wave I4: the relauncher's connect is automatic: the link does not retry it by itself (guards, backoff and the hourly cap are ours).
        GlassesRelauncher(scope, link.state, reopenEnabled, it, reopen = { link.connectAutomatic() }, now = clock, log = { m -> com.rokid.context.core.log.PcLog.i(GlassesRelauncher.TAG, m) }, standDown = standDown, store = relaunchStore, io = io)
    }
    init { relauncher?.let { link.setAutoOpenOwner(it) } } // review hub-wp2 r4: the link reports the end of an automatic open to its retry owner explicitly
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state
    private val _status = MutableStateFlow(_state.value.toGlassesStatus())
    override val status: StateFlow<GlassesStatus> = _status
    private val _hudSettings = MutableStateFlow(settings.read())
    /** The phone's persisted HUD settings (pauses are merged in only when pushed). Settings screens observe this. */
    val hudSettings: StateFlow<HudSettings> = _hudSettings
    private val _activePauses = MutableStateFlow<List<PauseRecord>>(emptyList())
    /** Active pause records in phone clock, refreshed on every broadcast. */
    val activePauses: StateFlow<List<PauseRecord>> = _activePauses
    private var seq = 0L
    /** Written by the hello handler, read on the frame path: the one clock offset holder ([HubState.offset] mirrors it). */
    @Volatile private var offset: ClockOffset? = null
    /** Glasses-clock originals of pauses stored via [ClockOffset.anchoredAt] before any offset; re-converted on the first hello. */
    private val anchoredBeforeOffset = LinkedHashMap<String, PauseRecord>()
    private var lastHelloRequestAtMs: Long? = null
    /** Frames acked ok since the last hello: each left the glasses' buffer once (a resend of a lost ack is not counted again). */
    private val ackedSinceHello = HashSet<String>()
    private var jobs: List<Job> = emptyList()
    /** Review I2: the pop-up shown instead of opening the HUD while the glasses app is minimised. */
    private var minimisedHint: Overlay? = null
    private var hintJob: Job? = null
    private val broadcaster = StateBroadcaster(scope, onError = { PcLog.w(TAG, "broadcast failed: ${it.javaClass.simpleName}") }) { broadcast() }

    init { scope.launch { guarded("pauses") { refreshPauses() } } }

    val started: Boolean get() = jobs.isNotEmpty()

    /** Review hub-wp2 r3: guarded by default. Only a [userInitiated] start (a tapped Reconnect, the app in front) connects at once; every other start (an AutoStop wake, a sticky restart, the watchdog's hub recovery) connects through the call guard and the hourly cap ([GlassesRelauncher.connectGuarded]). */
    fun start(userInitiated: Boolean = false) {
        if (started) return
        ctxStart()
        zoneStart()
        jobs = listOf(
            scope.launch { link.state.collect { s -> onLinkState(s) } },
            scope.launch { link.events.collect { guarded("event ${it::class.simpleName}") { onEvent(it) } } },
            scope.launch { link.ready.collect { guarded("ready") { onReady() } } },
            // null = unknown again (session closed, offline, BT lost): the stale value is dropped, never re-sent (fix wave I1).
            scope.launch { link.wearing.collect { w -> wearEstimate.onWear(w); if (w != null) positiveSignal(); lastWorn = w; _wearUnknownSince.value = if (w != null) null else (_wearUnknownSince.value ?: clock()); if (w != null) { lastWornAtMs = clock(); PcLog.i(TAG, "wear=$w"); guarded("wear") { sendWear() } } else PcLog.i(TAG, "wear unknown") } },
            // v1.0 wear fallback: a new read of the glasses' system wear check gates the estimate and goes to the glasses with the wear value.
            scope.launch { link.wearCheck.collect { wearCheck -> wearEstimate.onWearCheck(wearCheck); PcLog.i(TAG, "wear check on=$wearCheck"); guarded("wear") { sendWear() } } },
            scope.launch { phoneUse.collect { guarded("phone_ctx") { sendPhoneContext() } } },
            scope.launch { motionContext.collect { m -> guarded("motion_ctx") { onMotionContext(m) } } },
            scope.launch { guarded("prune") { withContext(io) { pauses.pruneHistory() } } },
            scope.launch { voice.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { actions.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { nudges.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { nudges.latest.drop(1).collect { broadcaster.markDirty() } },
            // Place Guide PG3: a new card re-broadcasts at once and starts its 12 s life; a cleared card re-broadcasts so the glasses drop it. The card that already exists when the hub (re)starts is
            // NOT skipped: its expiry is scheduled too (a card must never outlive its 12 s because the hub restarted under it).
            scope.launch { placeCards.collect { c -> onPlaceCard(c) } },
            scope.launch { suggestionCards.collect { c -> onSuggestionCard(c) } },
            // The gate's reason travels in pc_settings (the glasses toast it on tap = Ask, spec §5.1.1).
            scope.launch { voice.askBlockedReason.drop(1).collect { guarded("settings") { pushSettings() } } },
            // LGC4P: Developer mode on/off republishes the settings at once (diagRecord) and restarts (on) or ends (off) the control re-send.
            scope.launch { devMode.drop(1).collect { guarded("settings") { pushSettings(); broadcaster.markDirty(); diagControl?.restart() } } },
            broadcaster.start(),
        ) + listOfNotNull(relauncher?.start())
        // Fix wave I2: after a phone restart the wearer's exit still stands; only an explicit open ends it.
        if (standDown.active) {
            PcLog.i(TAG, "wearer exited the glasses app earlier: not connecting, watching for a reopen")
            set { it.copy(closedByUser = true) }
            link.watchForReopen() // N4: the wearer's own reopen must still be noticed
        }
        else if (linkAllowed()) { val r = relauncher; if (!userInitiated && r != null) r.connectGuarded() else link.connectOnce() }
        else PcLog.i(TAG, "glasses link off (setting off or not paired): not connecting")
    }

    fun stop() {
        voice.onLinkLost() // first (preflight F15): the link is closing, so an open glasses listen can't complete
        // The glasses must not keep applying a context after this session: tell them UNKNOWN before the collector and the link go.
        ctxStop()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { guarded("motion_ctx") { onMotionContext(null) } }
        diagControl?.cancel() // LGC4P: the retry lives on the graph scope; the link-state collector is cancelled before link.close() so it would never hear the loss
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        relauncher?.stop()
        link.close()
        try { onStopped() } catch (e: Exception) { PcLog.w(TAG, "stop hook failed (${e.javaClass.simpleName})") }
    }

    /**
     * Explicit Reconnect. A hub whose service is down only starts the service (never a silent no-op): its [start]
     * connects once from Idle, so a second connect here would race it.
     */
    override fun requestReconnect() {
        // Review I2: a CUSTOM_APP connect runs appStart, which would pull the HUD over Maps while minimised. The wearer
        // gets a pop-up there instead (a Confirm is the only thing that may open the app, glasses side, spec 1.7).
        userAskedAtMs = clock()
        positiveSignal()
        relauncher?.onExplicitOpen()
        standDown.clear(clock())
        set { it.copy(closedByUser = false) }
        if (started && link.state.value == LinkState.MINIMISED) {
            showMinimisedHint()
            link.probeAlive() // fix wave I1: if the glasses are dead the link says so and opens the app for real
            return
        }
        if (started) link.connect() else startService()
    }

    private fun showMinimisedHint() {
        PcLog.i(TAG, "open on glasses while minimised: pop-up, no appStart")
        minimisedHint = Overlay.Toast(OPEN_WHILE_MINIMISED)
        broadcaster.markDirty()
        hintJob?.cancel()
        hintJob = scope.launch { delay(MINIMISED_HINT_MS); minimisedHint = null; broadcaster.markDirty() }
    }

    fun onAppResumed() { positiveSignal(); if (!standDown.active && linkAllowed()) link.connectOnResume() }

    /** A settings change from the phone: persisted, pushed now (when linked) and reflected in the next frame. */
    fun updateSettings(transform: (HudSettings) -> HudSettings) {
        val before = settings.read()
        settings.write(transform(before))
        _hudSettings.value = settings.read()
        // BUG-R2-6: quiet hours off ends tonight's running quiet pause now (written first, so it is never re-minted).
        val quietOff = before.quietHours != null && _hudSettings.value.quietHours == null
        scope.launch {
            guarded("settings") {
                if (quietOff) withContext(io) { pauses.endQuietHours() }.also { PcLog.i(TAG, "quiet hours off: ended ${it.size} pause(s)") }
                pushSettings(); broadcaster.markDirty()
                if (quietOff) refreshPauses()
            }
        }
    }

    /** A phone pause (capture only): a new PHONE record from now until [untilMs] (null = until resumed), pushed now (on [scope]). */
    suspend fun pausePhone(untilMs: Long?): PauseRecord = pausePhoneAcked(untilMs).first

    /** [pausePhone] plus whether the settings push was accepted by the link (true only then; false = the glasses follow on reconnect). */
    suspend fun pausePhoneAcked(untilMs: Long?): Pair<PauseRecord, Boolean> {
        val r = PauseRecord(UUID.randomUUID().toString(), PauseOrigin.PHONE, createdAtMs = clock(), untilMs = untilMs)
        withContext(io) { pauses.add(r) }
        PcLog.i(TAG, "phone pause added")
        val pushed = onHubScope { pushSettings().also { broadcaster.markDirty() } }
        refreshPauses()
        return r to pushed
    }

    /** Ends only the named records (spec §5.5), then pushes the settings and a frame. */
    suspend fun resume(ids: Set<String>) { resumeAcked(ids) }

    /** [resume] plus whether the settings push was accepted by the link (true only then). */
    suspend fun resumeAcked(ids: Set<String>): Boolean {
        withContext(io) { pauses.resume(ids) }
        PcLog.i(TAG, "phone resume for ${ids.size} pause(s)")
        val pushed = onHubScope { pushSettings().also { broadcaster.markDirty() } }
        refreshPauses()
        return pushed
    }

    /** Pause zones: a zone record was added or ended, or the approach flag changed: push the settings (the glasses hear it at once) and a frame. */
    suspend fun zonesChanged() {
        // Fail closed: a push the link did not accept (or that threw) is reported to the zone controller, which keeps its publish pending and retries; connected glasses may still be capturing.
        var pushed = false
        guarded("zones") { pushed = pushSettings(); broadcaster.markDirty(); refreshPauses() }
        if (!pushed) throw com.rokid.context.phone.zone.ZonePublishDeferred()
    }

    /** Something a frame shows changed outside the hub's own flows: send a fresh frame (≤ 4 Hz). */
    fun refresh() {
        scope.launch { guarded("pauses") { refreshPauses() } }
        broadcaster.markDirty()
    }

    /**
     * Runs [block] on [scope]'s dispatcher, keeping the caller's job: [pausePhone]/[resume] are called from IO
     * (PausePipelineControl), and [GlassesLink.send] must stay on the link's thread (final review I3).
     */
    private suspend fun <T> onHubScope(block: suspend () -> T): T = withContext(scope.coroutineContext.minusKey(Job)) { block() }

    /** [activePauses] without waiting for a broadcast (works before [start] too). */
    private suspend fun refreshPauses() { _activePauses.value = withContext(io) { pauses.active() } }

    /**
     * One failing event (disk/DB full, …) is logged by class name and shown as a [UserFacingError]; it never ends
     * collection or reaches the scope (master Review Focus 5).
     */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PcLog.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            set { it.copy(error = storageErrorFor(e)) }
        }
    }

    private fun onLinkState(s: LinkState) {
        wearEstimate.onLink(s.carriesData)
        // Minimised keeps the glasses session (and its clock offset) on the background link; push-to-talk needs the HUD.
        if (s != LinkState.CONNECTED) voice.onLinkLost()
        if (!s.carriesData) { newSession(); diagControl?.cancel() }
        if (s == LinkState.CONNECTED) standDown.connected()
        if (s.carriesData) positiveSignal()
        if (!s.carriesData) set(markDirty = false) { it.copy(iconBlocked = false) } // a stale "no icon" row must not outlive the link
        set { it.copy(link = s, offset = offset, closedByUser = if (s == LinkState.CONNECTED) false else it.closedByUser) }
    }

    /** The Rokid SDK's wear state, newest first seen on this phone; forwarded to the glasses as `pc_wear` (they gate on it). */
    private var lastWorn: Boolean? = null
    private val _wearUnknownSince = MutableStateFlow<Long?>(clock())
    /** Phone clock ms since the wear signal went missing (hub start counts); null while it is available. For the Wear detection card. */
    val wearUnknownSince: StateFlow<Long?> = _wearUnknownSince
    private var lastWornAtMs = 0L
    /** Send order of `pc_wear` (Codex wear r2 P1-2): strictly increasing, so a late message can never override a newer one on the glasses. */
    private var wearSeq = 0L
    /** Random per hub start, and the number of link sessions opened since (`pc_wear` carries both: Codex wear r4 retired-session fence, independent of the wall clock). */
    private val phoneEpoch: Long = kotlin.random.Random.nextLong().let { if (it == 0L) 1L else it }
    private var sessionNo = 0L
    @Synchronized private fun nextWearSeq(): Long { wearSeq += 1; return wearSeq }

    /**
     * Codex r1 finding 4: the wear check travels even with no wear value (worn = null: the message then only carries the check; an old glasses build ignores it).
     * Without a wear value and without a check there is nothing to say.
     */
    private suspend fun sendWear() {
        val w = lastWorn
        val check = link.wearCheck.value
        if (w == null && check == null) return
        link.send(Channels.WEAR, Wire.encode(com.rokid.context.core.model.WearMessage(worn = w, atMs = if (w != null) lastWornAtMs else clock(), sessionStartMs = sessionStartMs, wearCheck = check, seq = nextWearSeq(), phoneEpoch = phoneEpoch, sessionNo = sessionNo)))
    }

    /** v1.0 wear fallback: the glasses' "not worn (estimated)", gated by the system wear check ([GlassesWearEstimate]); AutoStop reads it. */
    private var wearExpiryJob: Job? = null
    val wearEstimate: GlassesWearEstimate = GlassesWearEstimate(clock = { android.os.SystemClock.elapsedRealtime() }, scheduleExpiry = { ms ->
        // one timer: each refresh from the glasses replaces it; the estimate expires FRESH_MS after the last one
        wearExpiryJob?.cancel()
        wearExpiryJob = scope.launch { delay(ms); wearEstimate.tick() }
    })

    /** The last `pc_motion_ctx` this session sent; a null from the context hub ends it with an explicit UNKNOWN. */
    private var lastMotionCtx: MotionContextMessage? = null

    private suspend fun onMotionContext(m: MotionContextMessage?) {
        if (m != null) { sendMotionContext(m); return }
        val last = lastMotionCtx ?: return
        lastMotionCtx = null
        // The hub's own revoke can be conflated away on its way to null: send one here unless the glasses already hold UNKNOWN.
        if (last.state != ContextState.UNKNOWN) sendMotionContext(last.copy(state = ContextState.UNKNOWN, confidence = 0f, reasons = emptyList(), sinceMs = clock(), atMs = clock(), apply = applyToCapture?.invoke() ?: last.apply))
    }

    private suspend fun sendMotionContext(m: MotionContextMessage) {
        val out = m.copy(sessionStartMs = sessionStartMs)
        lastMotionCtx = out
        link.send(Channels.MOTION_CONTEXT, Wire.encode(out))
    }

    /** `pc_phone_ctx`: always sent; the glasses gate on their `pauseWhenPhoneInUse` setting. */
    private suspend fun sendPhoneContext() {
        link.send(Channels.PHONE_CONTEXT, Wire.encode(com.rokid.context.core.model.PhoneContextMessage(inUse = phoneUse.value, atMs = clock(), sessionStartMs = sessionStartMs)))
    }

    private val ackGate = PlanAckGate(phoneInstall) { sessionStartMs }
    private val peerTracker = PlanPeerTracker()
    /** Acks dropped as stale since the app started (another install, another session, or pre-session). */
    val planAckStale: Int get() = ackGate.ackStale
    /** Whether the glasses app understands capture plans; TOO_OLD = it never sent a caps ack ("Glasses app too old for canvas plans"). */
    fun planPeer(): PlanPeer = peerTracker.state(clock())

    /** Phone clock of the last message from the glasses app or session start; null before any. Read by the [GlassesWatchdog]. */
    @Volatile var lastHeardAtMs: Long? = null
        private set

    // Watchdog hooks (all on the hub's thread).
    /** One guarded reopen attempt for the watchdog; see [GlassesRelauncher.attemptNow]. */
    fun watchdogRelaunch(): String? = relauncher?.attemptNow() ?: "no relauncher"
    /** v0.9.7 slice D: this glasses build answers `pc_mark_photo_request` (its last Hello said so). */
    @Volatile var peerMarkPhoto: Boolean = false
        private set
    /** v0.9.7 slice D: the Rokid SDK's wear state (null = unknown), the link carries data, and capture is not paused: the inputs of a phone-origin mark's photo decision. */
    fun wornNow(): Boolean? = lastWorn
    fun carriesData(): Boolean = _state.value.link.carriesData
    fun captureRunning(): Boolean = _activePauses.value.isEmpty()
    /** v0.9.7 slice D: asks the glasses for the photo of the phone-origin mark [ref]; false = it could not be sent (the caller records the reason). */
    suspend fun requestMarkPhoto(ref: String): Boolean = link.send(Channels.MARK_PHOTO_REQUEST, Wire.encode(com.rokid.context.core.model.MarkPhotoRequest(ref = ref, atMs = clock(), sessionStartMs = sessionStartMs)))

    /** Review hub-wp2 r2 P1-1: an automatic session open waits for the call guard (settle included), the setting and the hourly cap like every reopen; a blocked one is retried by the watchdog's own ladder. */
    fun watchdogReconnect() {
        val r = relauncher
        // Review hub-wp2 r3 P1-2/P2-5: the automatic policy (a failure is the relauncher's to retry, never the link's timers) and eligibility read before a quota slot is reserved.
        if (r == null) link.connectOnResume() else r.attemptNow(launch = link::reconnectAutomatic, eligible = link::reconnectEligible)
    }
    /** A silent minimised app only moves the link to CLOSED_ON_GLASSES; the reopen after it is the relauncher's guarded one (a wearer's tap is the one that opens at once). */
    fun watchdogProbe() = link.probeAlive(openIfGone = false)
    fun standingDown(): Boolean = standDown.active
    /** The Home banner's watchdog line ("Glasses not connected · last heard 7 min ago"), null = nothing to say. */
    fun setWatchdogNote(note: String?) { if (_state.value.watchdogNote != note) set { it.copy(watchdogNote = note) } }
    /** AutoStop's line ("Stopped: glasses not worn since 14:05. Tap to start"); the banner's tap is a Reconnect, which starts the hub. Null = none. */
    fun setAutoStopNote(note: String?) { if (_state.value.autoStopNote != note) set { it.copy(autoStopNote = note) } }

    /** Phone clock when the current glasses session began (a `pc_wear` stamped before it is stale; the glasses ignore it). */
    private var sessionStartMs = 0L

    /** `pc_auto_open` while our own reopen is fresh: the glasses keep that start in icon mode. */
    private suspend fun sendAutoOpen() {
        if (relauncher?.reopenedRecently() != true) return
        link.send(Channels.AUTO_OPEN, Wire.encode(com.rokid.context.core.model.AutoOpen(atMs = clock())))
    }

    /**
     * The offset belongs to one glasses session (no RTC there: the clock can jump at a reboot). Until the new
     * session's hello, pauses are anchored at receipt. Anchored originals of the old session are in its clock: dropped.
     */
    private fun newSession() {
        sessionStartMs = clock()
        sessionNo += 1
        offset = null
        anchoredBeforeOffset.clear()
        lastHelloRequestAtMs = null
        ackedSinceHello.clear()
    }

    private fun set(markDirty: Boolean = true, f: (HubState) -> HubState) {
        _state.update(f)
        _status.value = _state.value.toGlassesStatus()
        if (markDirty) broadcaster.markDirty()
    }

    /** `pc_settings` first, then a `pc_state` through the broadcaster (≤ 4 Hz, Global Constraint). */
    private suspend fun onReady() {
        newSession()
        lastHeardAtMs = clock()
        peerTracker.onSessionOpen(sessionStartMs)
        set(markDirty = false) { it.copy(versionMismatch = false, offset = null) }
        pushSettings()
        if (lastWorn != null) lastWornAtMs = clock() // the SDK's value is current for this session (it is dropped on every loss)
        sendWear()
        sendPhoneContext()
        // A fresh envelope (new atMs, this session's start), same state and sinceMs: the glasses drop a stale or pre-session stamp.
        motionContext.value?.let { sendMotionContext(it.copy(atMs = clock())) }
        sendAutoOpen()
        try { onSessionReady(sessionStartMs) } catch (e: CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "session-ready hook failed: ${e.javaClass.simpleName}") }
        diagControl?.restart(newSession = true) // LGC4P: a new glasses session knows nothing of the last status
        broadcaster.markDirty()
    }

    /** True only when the link accepted the `pc_settings` send. */
    private suspend fun pushSettings(): Boolean {
        val (active, resumed) = withContext(io) { pauses.active() to pauses.recentlyResumedIds() }
        val pushed = StateFrameBuilder.settingsFor(
            settings.read(), active, _state.value.offset, voice.askBlockedReason.value, resumed = resumed, zoneId = ZoneId.systemDefault().id,
        // LGC4P: the Developer-mode switch at push time, never the stored value (a restart would otherwise re-send false and switch recording off).
        ).let { it.copy(
            diagRecord = devMode.value,
            zoneApproach = zoneApproach(), // pause zones: computed at push time, never the stored value
            zoneEnforce = zoneEnforce(),
            hudHost = hudHost(), // v2.0 T7: computed at push time; the glasses draw no popups or cards while it is NEXUS
            // v0.9.7 B: the developer sensor recording is a diagnostics feature: its rate goes on the wire only while Developer mode is on (and only a preset); the stored value alone never decides
            sensorRecHz = if (devMode.value) it.sensorRecHz.takeIf { hz -> hz in com.rokid.context.core.model.SENSOR_REC_PRESETS_HZ } ?: 0 else 0,
        ) }
        return link.send(Channels.SETTINGS, Wire.encode(pushed))
    }

    private suspend fun onEvent(e: GlassesEvent) {
        val now = clock()
        lastHeardAtMs = now // any message proves the glasses app is alive (the watchdog's heartbeat)
        when (e) {
            is GlassesEvent.HelloReceived -> {
                val h = e.hello
                peerMarkPhoto = h.markPhotoRequest
                diagStatus?.onHello(h)
                peerTracker.onHello(now)
                val measured = ClockOffset.fromHello(h.glassesNowMs, now)
                offset = measured
                val anchored = anchoredBeforeOffset.values.map(measured::recordToPhone)
                anchoredBeforeOffset.clear()
                ackedSinceHello.clear()
                withContext(io) {
                    anchored.forEach { pauses.reanchor(it) }
                    // Resumes the wearer made while the link was down (their pc_cmd may never have arrived).
                    h.resumedAtMs.forEach { (id, at) -> pauses.resume(setOf(id), measured.toPhone(at)) }
                    pauses.union(h.pauses.map(measured::recordToPhone))
                }
                PcLog.i(TAG, "hello battery=${h.glassesBatteryPct} buffered=${h.bufferSize} resumed=${h.resumedAtMs.size} offsetMs=${measured.glassesMinusPhoneMs}")
                set {
                    it.copy(
                        versionMismatch = false, offset = measured, glassesBatteryPct = h.glassesBatteryPct ?: it.glassesBatteryPct,
                        bufferedFrames = h.bufferSize, nextCaptureAtMs = h.nextCaptureAtMs?.let(measured::toPhone),
                        rokidCloses = h.rokidCloses, lastRestartGapSec = h.lastRestartGapSec,
                        unverifiedCloses = h.unverifiedCloses, otherCloses = h.otherCloses, iconBlocked = h.iconBlocked,
                    )
                }
                // Fix wave I2: the glasses repeat the wearer's exit in every hello, in case `pc_user_exit` was lost with the session.
                // N4: a hello that does not repeat a fresh exit means the app is running again (the wearer opened it): the stand-down is over.
                if (standDown.active && h.userExitAtMs?.let { now - measured.toPhone(it) in 0..EXIT_STAMP_FRESH_MS } != true) {
                    PcLog.i(TAG, "glasses app is running again: stand-down over")
                    standDown.connected(); set { it.copy(closedByUser = false) }
                }
                h.userExitAtMs?.let { at ->
                    val exitAt = measured.toPhone(at)
                    // A recent exit only: an old stamp the glasses still carry is not news (and an explicit open since then wins).
                    if (exitAt > standDown.clearedAtMs && now - exitAt in 0..EXIT_STAMP_FRESH_MS && !standDown.active) onUserExit("hello")
                }
                pushSettings()
                sendWear()
                sendAutoOpen()
            }
            is GlassesEvent.Cmd -> onCommand(e.command, now)
            GlassesEvent.UserExit -> onUserExit("message")
            is GlassesEvent.Frame -> {
                val r = withContext(io) { ingestor.onChunk(e.chunk, offset, now) }
                r.ack?.let { link.send(Channels.FRAME_ACK, Wire.encode(it)) }
                if (r.held) requestHello(now)
                val delivered = r.ack?.takeIf { it.ok }?.let { ackedSinceHello.add(it.frameId) } == true
                set { s ->
                    s.copy(
                        bufferedFrames = if (delivered) (s.bufferedFrames - 1).coerceAtLeast(0) else s.bufferedFrames,
                        glassesBatteryPct = e.chunk.meta.batteryPct ?: s.glassesBatteryPct,
                        lastFrameAtMs = r.ingestedAtMs ?: s.lastFrameAtMs,
                        error = r.error ?: if (r.ingestedAtMs != null) null else s.error,
                    )
                }
            }
            is GlassesEvent.Outdated -> {
                PcLog.w(TAG, "glasses protocol v=${e.remote}, phone v=$PROTOCOL_VERSION")
                set { it.copy(versionMismatch = true) }
            }
            is GlassesEvent.Audio -> voice.onAudio(e.pcm) // never logged: it is the wearer's voice
            GlassesEvent.ListenEnd -> voice.onListenEnd()
            is GlassesEvent.MotionHintReceived -> {
                wearEstimate.onHint(e.hint.wearEst)
                // Codex v3 r3 P1-1: a hint of another (older) glasses session may still warn of a vehicle, but is never calm evidence: its session is stripped (unknown)
                val h = e.hint
                onMotionHint(if (h.sessionStartMs == 0L || h.sessionStartMs == sessionStartMs) h else { PcLog.i(TAG, "motion hint of another session: not calm evidence"); h.copy(sessionStartMs = 0L) })
            }
            is GlassesEvent.PlanAckReceived -> {
                peerTracker.onCapsAck() // any decoded plan ack proves the glasses speak the channel, stale or not
                when (ackGate.classify(e.ack)) {
                    AckVerdict.CAPS -> onPlanCaps(e.ack.caps)
                    AckVerdict.MATCH -> onPlanAck(e.ack)
                    // count only; a line for the first and then every 20th, so a reconnect storm stays quiet
                    AckVerdict.STALE -> ackGate.ackStale.let { n -> if (n == 1 || n % 20 == 0) PcLog.i(TAG, "stale pc_capture_plan_ack ignored (count=$n)") }
                }
            }
            is GlassesEvent.MarkReceived -> onMarkReceived(e.mark, now)
            is GlassesEvent.MarkPhotoReceived -> try { onMarkPhoto(e.result) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (x: Exception) { PcLog.w(TAG, "mark photo report not recorded (${x.javaClass.simpleName})") }
            is GlassesEvent.TraceChunkReceived -> onTraceChunk(e.chunk)
        }
    }

    /**
     * No freshness test: only `v` was gated on the way in (occurrence time, not a staleness stamp). The mark is mapped to phone time with the hello offset
     * (receipt time while there is none) and acknowledged only after the recorder handled it; a store failure sends no ack, so the glasses re-send.
     */
    private suspend fun onMarkReceived(m: MarkMessage, now: Long) {
        val off = offset
        val arrival = com.rokid.context.canvas.marks.MarkArrival(off?.toPhone(m.atMs) ?: now, mapped = off != null, receivedAtMs = now)
        val handled = try { onMark(m, arrival) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "mark not recorded (${e.javaClass.simpleName})"); false }
        if (handled) link.send(Channels.MARK_ACK, Wire.encode(com.rokid.context.core.model.MarkAck(epoch = m.epoch, seqs = listOf(m.seq))))
    }

    private fun onUserExit(how: String) {
        PcLog.i(TAG, "wearer exited the glasses app on purpose ($how): not reopening")
        standDown.enter(clock())
        relauncher?.onUserExit(); set { it.copy(closedByUser = true) }
    }

    private suspend fun onCommand(c: Command, now: Long) {
        when (c) {
            is Command.Pause -> {
                val r = offset?.recordToPhone(c.pause)
                    ?: ClockOffset.anchoredAt(c.pause, now).also { anchoredBeforeOffset[c.pause.pauseId] = c.pause }
                withContext(io) { pauses.add(r) }
                PcLog.i(TAG, "pause added origin=${r.origin}")
                pushSettings(); broadcaster.markDirty()
            }
            is Command.Resume -> {
                withContext(io) { pauses.resume(c.clears) }
                PcLog.i(TAG, "resume asked for ${c.clears.size} pause(s)")
                pushSettings(); broadcaster.markDirty()
            }
            Command.ListenStart -> { PcLog.i(TAG, "listen start"); try { onGlassesListenStart(clock()) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { PcLog.w(TAG, "listen join failed (${e.javaClass.simpleName})") }; voice.onListenStart() }
            Command.Cancel -> { PcLog.i(TAG, "voice cancel"); voice.onCancel() }
            is Command.Confirm -> { PcLog.i(TAG, "confirm answered"); actions.onConfirm(c.proposalId, c.yes) }
            is Command.DismissPlace -> onPlaceDismissed(c.title, c.cardId)
            is Command.Undo -> { PcLog.i(TAG, "undo asked"); actions.onUndo(c.proposalId) }
            else -> PcLog.i(TAG, "command ${c::class.simpleName} is handled in a later phase")
        }
    }

    /** A held frame means no offset or a stale one: ask the glasses for a fresh `pc_hello`, at most once per window. */
    private suspend fun requestHello(now: Long) {
        val last = lastHelloRequestAtMs
        if (last != null && now >= last && now - last < HELLO_REQUEST_MIN_INTERVAL_MS) return
        if (link.send(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()))) {
            lastHelloRequestAtMs = now
            PcLog.i(TAG, "frame held: asked the glasses for a fresh hello")
        }
    }

    /** Computed on every change/heartbeat; sent only while linked. Sent even to an outdated app so it can show "Update". */
    private suspend fun broadcast() {
        val now = clock()
        val active = withContext(io) { pauses.active() }
        _activePauses.value = active
        val today = withContext(io) { sources.today() }
        val depth = withContext(io) { sources.queueDepth() }
        val health = withContext(io) { sources.aiHealth(now) }
        val s = _state.value // after the IO reads: the newest snapshot gets the next seq
        val frame = StateFrameBuilder.build(
            HubInputs(
                seq = ++seq, nowMs = now, link = s.link, activePauses = active, offset = s.offset, settings = settings.read(),
                glassesBatteryPct = s.glassesBatteryPct, nextCaptureAtMs = s.nextCaptureAtMs, queueDepth = depth,
                lastFrameAtMs = s.lastFrameAtMs, today = today, aiHealth = health, overlay = overlay(), error = s.error,
                latestNudge = nudges.latest.value, withdrawnAskIds = recentlyWithdrawn(clock()), // Codex r1 P2: the time NOW, after the IO reads, never the snapshot taken before them
            ),
        )
        set(markDirty = false) { it.copy(lastFrame = frame) }
        if (s.link.carriesData && link.send(Channels.STATE, Wire.encode(frame))) { actions.onShown(frame.overlay); onPlaceFrameSent(frame.overlay) }
    }

    private var placeExpiry: Job? = null

    /** The card whose delivery is not settled yet (a place card, never a suggestion). Guarded by [placeLock]. */
    private var placePending: String? = null
    private val placeLock = Any()

    /** B3: a state frame carrying the pending place card was sent: SENT, once. (A sent frame is the phone's delivery; a glasses dismissal is not proof of anything and changes nothing.) */
    private fun onPlaceFrameSent(o: Overlay?) {
        val id = (o as? Overlay.Nudge)?.place?.id ?: return
        val hit = synchronized(placeLock) { if (placePending == id) { placePending = null; true } else false }
        if (hit) placeDelivery(id, com.rokid.context.memory.place.CardDelivery.SENT)
    }

    /** B3: the card ended (12 s, replaced or withdrawn) and no frame carried it: why it never reached the glasses. */
    private fun resolvePlacePending() {
        val id = synchronized(placeLock) { placePending.also { placePending = null } } ?: return
        val d = com.rokid.context.memory.place.CardDelivery
        val why = when {
            !_state.value.link.carriesData -> d.NOT_SENT_LINK
            hudHost() == com.rokid.context.core.model.HudHost.NEXUS -> d.NOT_SENT_HOST
            zoneApproach() || zoneEnforce() -> d.NOT_SENT_ZONE
            else -> d.NOT_SENT_BUSY // another message (an answer, a toast) had the glasses
        }
        placeDelivery(id, why)
    }

    private fun onPlaceCard(c: com.rokid.context.work.frames.place.PlaceCard?) {
        placeExpiry?.cancel(); placeExpiry = null
        resolvePlacePending() // the card before this one (replaced, withdrawn or expired) that no frame carried
        if (c != null) {
            if (c.confidence == com.rokid.context.work.frames.place.PlaceConfidence.PHOTO) PcLog.i(TAG, "place card up (photo)") else PcLog.i(TAG, "place card up (nearby)")
            if (c.cardId.isNotEmpty()) synchronized(placeLock) { placePending = c.cardId }
            placeExpiry = scope.launch { delay(PlaceCardInfo.SHOW_MS); resolvePlacePending(); placeClear() }
        }
        broadcaster.markDirty()
    }

    /**
     * The glasses dismissed or timed out a card: with [cardId] (a current glasses build) only that instance is cleared, atomically, so a delayed dismissal never clears a newer card that has
     * the same title; without it (an older glasses build) the title decides, as before.
     */
    private fun onPlaceDismissed(title: String, cardId: String?) {
        if (cardId != null && cardId.startsWith(SUGGESTION_ID_PREFIX)) { onSuggestionDismissedOnGlasses(cardId); return }
        if (cardId != null) { if (placeClearIf(cardId)) PcLog.i(TAG, "place card dismissed on glasses"); return }
        val c = placeCards.value ?: return
        if (c.title == title) { PcLog.i(TAG, "place card dismissed on glasses"); placeClear() }
    }

    /**
     * Place Guide: the place card as a [Overlay.Nudge] carrying [PlaceCardInfo] (its text is "title: line" for an older glasses build), or null. Never while a pause zone
     * is approached or enforced (the card is built from a photo; defence in depth, the sampler already refuses).
     */
    fun placeOverlay(): Overlay? {
        val c = placeCards.value ?: return null
        if (zoneApproach() || zoneEnforce()) return null
        val info = PlaceCardInfo(c.title, c.line, c.sourceLabel, nearby = c.confidence == com.rokid.context.work.frames.place.PlaceConfidence.NEARBY, id = c.cardId.ifEmpty { null }, side = com.rokid.context.core.model.PlaceSide.toWire(placeSide()))
        return Overlay.Nudge("${c.title}: ${c.line}", info)
    }

    private var suggestionExpiry: Job? = null

    private fun onSuggestionCard(c: SuggestionHudHolder.Shown?) {
        suggestionExpiry?.cancel(); suggestionExpiry = null
        if (c != null) {
            PcLog.i(TAG, "suggestion card up")
            val id = c.card.id
            suggestionExpiry = scope.launch { delay(suggestionCapMs(c.card.quietInMs)); suggestionClearIf(id) } // expiry is NOT a dismissal: no feedback
        }
        broadcaster.markDirty()
    }

    /**
     * The glasses dismissed a suggestion card. Within the card's life (a head-motion onset after 2.5 s, before the 12 s expiry) it is the wearer's dismissal: feedback and withdrawal from every surface. The glasses' own
     * expiry reports the same command at about 12 s; that only clears the card.
     */
    private fun onSuggestionDismissedOnGlasses(cardId: String) {
        val shownAt = suggestionCards.value?.takeIf { it.card.id == cardId }?.atMs
        val early = shownAt != null && clock() - shownAt < PlaceCardInfo.SHOW_MS - SUGGESTION_EXPIRY_SLACK_MS
        if (!suggestionClearIf(cardId)) return
        if (early) { PcLog.i(TAG, "suggestion card dismissed on glasses"); onSuggestionDismissed(cardId) } else PcLog.i(TAG, "suggestion card expired on glasses")
    }

    /** v3.1 r5 P1-2: the card's life: [PlaceCardInfo.SHOW_MS], cut to end [SUGGESTION_QUIET_MARGIN_MS] before the next quiet-hours start (0 = never drawn). */
    private fun suggestionCapMs(quietInMs: Long?): Long = minOf(PlaceCardInfo.SHOW_MS, quietInMs?.minus(SUGGESTION_QUIET_MARGIN_MS) ?: PlaceCardInfo.SHOW_MS).coerceAtLeast(0L)

    /** The suggestion card as a [Overlay.Nudge] carrying [PlaceCardInfo] (text "title: line" for an older glasses build), or null. Never while a pause zone is approached or enforced (defence in depth; the delivery gate refused too). */
    fun suggestionOverlay(): Overlay? {
        val shown = suggestionCards.value ?: return null
        val c = shown.card
        if (zoneApproach() || zoneEnforce()) return null
        val d = suggestionDeadline(shown)
        return Overlay.Nudge("${c.title}: ${c.line}", PlaceCardInfo(c.title, c.line, "Suggestion \u00b7 ${c.sourceLabel}", nearby = false, id = c.id, side = com.rokid.context.core.model.PlaceSide.toWire(placeSide()), maxShowMs = d.maxShowMs, hideAtMs = d.hideAtMs))
    }

    private class SuggestionDeadline(val id: String, val atMs: Long, val maxShowMs: Long?, val hideAtMs: Long?, val session: Long = -1L, val off: ClockOffset? = null)
    private var deadlineCache: SuggestionDeadline? = null

    /**
     * v3.1 r6 P1-2: the card's deadline, decided ONCE per card (so the frame stays byte-stable and the glasses do not re-show it). The quiet-hours boundary is an absolute phone instant; the glasses hold no clock offset, so the
     * phone converts it with the hello-measured offset (the one it converts pauses with) minus [SUGGESTION_QUIET_MARGIN_MS] of slack and sends it as [PlaceCardInfo.hideAtMs]; the glasses compare it with their own clock at the
     * first draw. [PlaceCardInfo.maxShowMs] stays as a duration fallback (an older glasses build ignores both). Offset unknown: fail closed, a quiet-bounded card is not drawn at all (0).
     */
    private fun suggestionDeadline(s: SuggestionHudHolder.Shown): SuggestionDeadline {
        val off = offset
        // r7 P1-2: valid for one clock session AND one measured offset (every hello measures a new one): a changed glasses clock never reuses the old converted deadline.
        deadlineCache?.takeIf { it.id == s.card.id && it.atMs == s.atMs && it.session == sessionNo && it.off === off }?.let { return it }
        val boundary = s.card.quietAtMs ?: s.card.quietInMs?.plus(s.atMs)
        val dur = suggestionCapMs(s.card.quietInMs).takeIf { it < PlaceCardInfo.SHOW_MS }
        val d = when {
            boundary == null -> SuggestionDeadline(s.card.id, s.atMs, dur, null, sessionNo, off)
            off != null -> SuggestionDeadline(s.card.id, s.atMs, dur, boundary + off.glassesMinusPhoneMs - SUGGESTION_QUIET_MARGIN_MS, sessionNo, off)
            // r7 P1-1: no usable offset: a quiet-bounded card cannot be given an absolute deadline, and a late draw could cross the boundary: fail closed, never drawn (0)
            else -> SuggestionDeadline(s.card.id, s.atMs, 0L, null, sessionNo, off)
        }
        deadlineCache = d
        return d
    }

    private fun overlay(): Overlay? {
        // rc1 fix: never a frame of an Answer a Forget withdrew, even if the voice side has not cleared it yet
        // Codex z1 r1 P1-3: the final zone filter of the answer overlay, read at frame time: no Answer (stored text) is ever sent to the glasses while a zone is approached, entered or unreadable
        val v = voice.overlay.value.takeUnless { o -> o is Overlay.Answer && ((o.askId != null && o.askId in recentlyWithdrawn(clock())) || zoneApproach() || zoneEnforce()) }
        // PG3 maps the place card (PlaceGraph.port.card) onto an additive wire overlay and passes it here; until then no place overlay exists on the wire.
        // v2.0 T7: each component is filtered BEFORE the precedence rule, so an undo toast still shows when the answer it belongs to is drawn by Nexus.
        val host = hudHost()
        return pickOverlay(forHost(v, host), actions.overlay.value, minimisedHint ?: forHost(nudges.overlay.value, host), place = forHost(placeOverlay(), host) ?: forHost(suggestionOverlay(), host))
    }

    /** Ask ids a committed Forget redacted or deleted, with the phone time they were withdrawn (newest last); bounded by [WITHDRAWN_MAX] and [WITHDRAWN_HINT_MS]. */
    private val withdrawn = LinkedHashMap<String, Long>()

    /**
     * rc1 fix: a committed Forget redacted or deleted [askIds]. The next frame (sent at once) names them in `withdrawnAskIds` for [WITHDRAWN_HINT_MS], so glasses that
     * still show or hold an Answer of one of them (the phone side already Idle after its hold, a frame missed on a link gap) drop it; no frame carries such an Answer.
     */
    fun withdrawAsks(askIds: Collection<String>) {
        if (askIds.isEmpty()) return
        val now = clock()
        synchronized(withdrawn) { askIds.forEach { withdrawn.remove(it); withdrawn[it] = now }; prune(now) }
        broadcaster.markDirty()
    }

    private fun recentlyWithdrawn(now: Long): List<String> = synchronized(withdrawn) { prune(now); withdrawn.keys.reversed() }

    /** Only entries older than the hint window go; one newer than [now] (withdrawn while a caller held an older time) is always kept (Codex r1 P2). */
    private fun prune(now: Long) {
        withdrawn.entries.removeAll { now - it.value >= WITHDRAWN_HINT_MS }
        while (withdrawn.size > WITHDRAWN_MAX) withdrawn.remove(withdrawn.keys.first())
    }

    companion object {
        /** B2: the stored Place Guide side applies only with Developer mode on; otherwise CENTRE (nothing extra on the wire). The stored value is not even read when off. */
        fun gatedPlaceSide(devMode: Boolean, stored: () -> com.rokid.context.core.model.PlaceSide): com.rokid.context.core.model.PlaceSide =
            if (devMode) stored() else com.rokid.context.core.model.PlaceSide.CENTRE

        /**
         * v2.0 T7: while Rokid Nexus draws the cards, no frame carries an answer, nudge or place-card overlay (an older glasses build, which does not know `hudHost`, would otherwise draw them a second time).
         * PCA's own input overlays (listening, confirm, toasts, the pause splash) stay. The default host changes nothing.
         */
        fun forHost(o: Overlay?, host: com.rokid.context.core.model.HudHost): Overlay? =
            if (host == com.rokid.context.core.model.HudHost.NEXUS && (o is Overlay.Answer || o is Overlay.Nudge)) null else o

        const val TAG = "ContextHub"

        /**
         * Precedence (Phase 7): voice Listening > actions (Confirm/undo toast) > other voice overlays > place card > nudge (Place Guide, PG2: `place ?: nudge`).
         * BUG-R2-5: an undo toast and an Answer at once (either order) become the Answer carrying the undo, so the wearer
         * sees both and a double-tap undoes while the window is open.
         */
        fun pickOverlay(voice: Overlay?, actions: Overlay?, nudge: Overlay?, place: Overlay? = null): Overlay? {
            if (voice is Overlay.Listening) return voice
            if (voice is Overlay.Answer && actions is Overlay.Toast && actions.undoProposalId != null) {
                return voice.copy(undoProposalId = actions.undoProposalId)
            }
            return actions ?: voice ?: (place ?: nudge)
        }
        const val HELLO_REQUEST_MIN_INTERVAL_MS = 10_000L
        /** A suggestion card's id (`sg_<uuid>`, the record id) on the wire's PlaceCardInfo.id. */
        const val SUGGESTION_ID_PREFIX = "sg_"
        /** A dismissal this close to the 12 s expiry is the expiry (the glasses' own timer), not the wearer. */
        const val SUGGESTION_EXPIRY_SLACK_MS = 1_500L
        /** The glasses start their own clock a link-latency after the phone: the card ends this long before the quiet-hours start. */
        const val SUGGESTION_QUIET_MARGIN_MS = 3_000L
        /** How long a withdrawn ask stays in the frames' `withdrawnAskIds`: six heartbeats, so a glasses app that reconnects within it still hears it. */
        const val WITHDRAWN_HINT_MS = 60_000L
        const val WITHDRAWN_MAX = 20
        /** Glasses pop-up for "Open on glasses" while minimised: the HUD stays in the background. */
        const val OPEN_WHILE_MINIMISED = "Running in the background \u00b7 open it on your glasses"
        const val MINIMISED_HINT_MS = 5_000L
        /** A hello's repeated wearer-exit stamp counts only this long: the lost-message case ends in a reopen about 30 s after the exit. */
        const val EXIT_STAMP_FRESH_MS = 90_000L
    }
}
