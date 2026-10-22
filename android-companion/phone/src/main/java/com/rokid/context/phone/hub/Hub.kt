package com.rokid.context.phone.hub

import android.util.Log
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
    private val onMark: suspend (MarkMessage) -> Unit = {},
    /** Slice 3a: a `pc_trace_chunk` (the transfer contract is a later task). */
    private val onTraceChunk: suspend (TraceChunk) -> Unit = {},
) : GlassesStatusSource {
    private val relauncher: GlassesRelauncher? = reopenGuards?.let {
        // Fix wave I4: the relauncher's connect is automatic: the link does not retry it by itself (guards, backoff and the hourly cap are ours).
        GlassesRelauncher(scope, link.state, reopenEnabled, it, reopen = { link.connectAutomatic() }, now = clock, log = { m -> Log.i(GlassesRelauncher.TAG, m) }, standDown = standDown)
    }
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
    private val broadcaster = StateBroadcaster(scope, onError = { Log.w(TAG, "broadcast failed: ${it.javaClass.simpleName}") }) { broadcast() }

    init { scope.launch { guarded("pauses") { refreshPauses() } } }

    val started: Boolean get() = jobs.isNotEmpty()

    fun start() {
        if (started) return
        ctxStart()
        jobs = listOf(
            scope.launch { link.state.collect { s -> onLinkState(s) } },
            scope.launch { link.events.collect { guarded("event ${it::class.simpleName}") { onEvent(it) } } },
            scope.launch { link.ready.collect { guarded("ready") { onReady() } } },
            // null = unknown again (session closed, offline, BT lost): the stale value is dropped, never re-sent (fix wave I1).
            scope.launch { link.wearing.collect { w -> lastWorn = w; if (w != null) { lastWornAtMs = clock(); Log.i(TAG, "wear=$w"); guarded("wear") { sendWear() } } else Log.i(TAG, "wear unknown") } },
            scope.launch { phoneUse.collect { guarded("phone_ctx") { sendPhoneContext() } } },
            scope.launch { motionContext.collect { m -> guarded("motion_ctx") { onMotionContext(m) } } },
            scope.launch { guarded("prune") { withContext(io) { pauses.pruneHistory() } } },
            scope.launch { voice.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { actions.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { nudges.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { nudges.latest.drop(1).collect { broadcaster.markDirty() } },
            // The gate's reason travels in pc_settings (the glasses toast it on tap = Ask, spec §5.1.1).
            scope.launch { voice.askBlockedReason.drop(1).collect { guarded("settings") { pushSettings() } } },
            broadcaster.start(),
        ) + listOfNotNull(relauncher?.start())
        // Fix wave I2: after a phone restart the wearer's exit still stands; only an explicit open ends it.
        if (standDown.active) {
            Log.i(TAG, "wearer exited the glasses app earlier: not connecting, watching for a reopen")
            set { it.copy(closedByUser = true) }
            link.watchForReopen() // N4: the wearer's own reopen must still be noticed
        }
        else link.connectOnce()
    }

    fun stop() {
        voice.onLinkLost() // first (preflight F15): the link is closing, so an open glasses listen can't complete
        // The glasses must not keep applying a context after this session: tell them UNKNOWN before the collector and the link go.
        ctxStop()
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { guarded("motion_ctx") { onMotionContext(null) } }
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        relauncher?.stop()
        link.close()
    }

    /**
     * Explicit Reconnect. A hub whose service is down only starts the service (never a silent no-op): its [start]
     * connects once from Idle, so a second connect here would race it.
     */
    override fun requestReconnect() {
        // Review I2: a CUSTOM_APP connect runs appStart, which would pull the HUD over Maps while minimised. The wearer
        // gets a pop-up there instead (a Confirm is the only thing that may open the app, glasses side, spec 1.7).
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
        Log.i(TAG, "open on glasses while minimised: pop-up, no appStart")
        minimisedHint = Overlay.Toast(OPEN_WHILE_MINIMISED)
        broadcaster.markDirty()
        hintJob?.cancel()
        hintJob = scope.launch { delay(MINIMISED_HINT_MS); minimisedHint = null; broadcaster.markDirty() }
    }

    fun onAppResumed() { if (!standDown.active) link.connectOnResume() }

    /** A settings change from the phone: persisted, pushed now (when linked) and reflected in the next frame. */
    fun updateSettings(transform: (HudSettings) -> HudSettings) {
        val before = settings.read()
        settings.write(transform(before))
        _hudSettings.value = settings.read()
        // BUG-R2-6: quiet hours off ends tonight's running quiet pause now (written first, so it is never re-minted).
        val quietOff = before.quietHours != null && _hudSettings.value.quietHours == null
        scope.launch {
            guarded("settings") {
                if (quietOff) withContext(io) { pauses.endQuietHours() }.also { Log.i(TAG, "quiet hours off: ended ${it.size} pause(s)") }
                pushSettings(); broadcaster.markDirty()
                if (quietOff) refreshPauses()
            }
        }
    }

    /** A phone pause (capture only): a new PHONE record from now until [untilMs] (null = until resumed), pushed now (on [scope]). */
    suspend fun pausePhone(untilMs: Long?): PauseRecord {
        val r = PauseRecord(UUID.randomUUID().toString(), PauseOrigin.PHONE, createdAtMs = clock(), untilMs = untilMs)
        withContext(io) { pauses.add(r) }
        Log.i(TAG, "phone pause added ${r.pauseId}")
        onHubScope { pushSettings(); broadcaster.markDirty() }
        refreshPauses()
        return r
    }

    /** Ends only the named records (spec §5.5), then pushes the settings and a frame. */
    suspend fun resume(ids: Set<String>) {
        withContext(io) { pauses.resume(ids) }
        Log.i(TAG, "phone resume for ${ids.size} pause(s)")
        onHubScope { pushSettings(); broadcaster.markDirty() }
        refreshPauses()
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
    private suspend fun onHubScope(block: suspend () -> Unit) = withContext(scope.coroutineContext.minusKey(Job)) { block() }

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
            Log.w(TAG, "$what failed: ${e.javaClass.simpleName}")
            set { it.copy(error = storageErrorFor(e)) }
        }
    }

    private fun onLinkState(s: LinkState) {
        // Minimised keeps the glasses session (and its clock offset) on the background link; push-to-talk needs the HUD.
        if (s != LinkState.CONNECTED) voice.onLinkLost()
        if (!s.carriesData) newSession()
        if (s == LinkState.CONNECTED) standDown.connected()
        if (!s.carriesData) set(markDirty = false) { it.copy(iconBlocked = false) } // a stale "no icon" row must not outlive the link
        set { it.copy(link = s, offset = offset, closedByUser = if (s == LinkState.CONNECTED) false else it.closedByUser) }
    }

    /** The Rokid SDK's wear state, newest first seen on this phone; forwarded to the glasses as `pc_wear` (they gate on it). */
    private var lastWorn: Boolean? = null
    private var lastWornAtMs = 0L

    private suspend fun sendWear() {
        val w = lastWorn ?: return
        link.send(Channels.WEAR, Wire.encode(com.rokid.context.core.model.WearMessage(worn = w, atMs = lastWornAtMs, sessionStartMs = sessionStartMs)))
    }

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
        peerTracker.onSessionOpen(sessionStartMs)
        set(markDirty = false) { it.copy(versionMismatch = false, offset = null) }
        pushSettings()
        if (lastWorn != null) lastWornAtMs = clock() // the SDK's value is current for this session (it is dropped on every loss)
        sendWear()
        sendPhoneContext()
        // A fresh envelope (new atMs, this session's start), same state and sinceMs: the glasses drop a stale or pre-session stamp.
        motionContext.value?.let { sendMotionContext(it.copy(atMs = clock())) }
        sendAutoOpen()
        broadcaster.markDirty()
    }

    private suspend fun pushSettings() {
        val (active, resumed) = withContext(io) { pauses.active() to pauses.recentlyResumedIds() }
        val pushed = StateFrameBuilder.settingsFor(
            settings.read(), active, _state.value.offset, voice.askBlockedReason.value, resumed = resumed, zoneId = ZoneId.systemDefault().id,
        )
        link.send(Channels.SETTINGS, Wire.encode(pushed))
    }

    private suspend fun onEvent(e: GlassesEvent) {
        val now = clock()
        when (e) {
            is GlassesEvent.HelloReceived -> {
                val h = e.hello
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
                Log.i(TAG, "hello battery=${h.glassesBatteryPct} buffered=${h.bufferSize} resumed=${h.resumedAtMs.size} offsetMs=${measured.glassesMinusPhoneMs}")
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
                    Log.i(TAG, "glasses app is running again: stand-down over")
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
                Log.w(TAG, "glasses protocol v=${e.remote}, phone v=$PROTOCOL_VERSION")
                set { it.copy(versionMismatch = true) }
            }
            is GlassesEvent.Audio -> voice.onAudio(e.pcm) // never logged: it is the wearer's voice
            GlassesEvent.ListenEnd -> voice.onListenEnd()
            is GlassesEvent.MotionHintReceived -> onMotionHint(e.hint)
            is GlassesEvent.PlanAckReceived -> {
                peerTracker.onCapsAck() // any decoded plan ack proves the glasses speak the channel, stale or not
                when (ackGate.classify(e.ack)) {
                    AckVerdict.CAPS -> onPlanCaps(e.ack.caps)
                    AckVerdict.MATCH -> onPlanAck(e.ack)
                    // count only; a line for the first and then every 20th, so a reconnect storm stays quiet
                    AckVerdict.STALE -> ackGate.ackStale.let { n -> if (n == 1 || n % 20 == 0) Log.i(TAG, "stale pc_capture_plan_ack ignored (count=$n)") }
                }
            }
            is GlassesEvent.MarkReceived -> onMark(e.mark) // no freshness test: only `v` was gated on the way in
            is GlassesEvent.TraceChunkReceived -> onTraceChunk(e.chunk)
        }
    }

    private fun onUserExit(how: String) {
        Log.i(TAG, "wearer exited the glasses app on purpose ($how): not reopening")
        standDown.enter(clock())
        relauncher?.onUserExit(); set { it.copy(closedByUser = true) }
    }

    private suspend fun onCommand(c: Command, now: Long) {
        when (c) {
            is Command.Pause -> {
                val r = offset?.recordToPhone(c.pause)
                    ?: ClockOffset.anchoredAt(c.pause, now).also { anchoredBeforeOffset[c.pause.pauseId] = c.pause }
                withContext(io) { pauses.add(r) }
                Log.i(TAG, "pause added ${r.pauseId} origin=${r.origin}")
                pushSettings(); broadcaster.markDirty()
            }
            is Command.Resume -> {
                withContext(io) { pauses.resume(c.clears) }
                Log.i(TAG, "resume asked for ${c.clears.size} pause(s)")
                pushSettings(); broadcaster.markDirty()
            }
            Command.ListenStart -> { Log.i(TAG, "listen start"); voice.onListenStart() }
            Command.Cancel -> { Log.i(TAG, "voice cancel"); voice.onCancel() }
            is Command.Confirm -> { Log.i(TAG, "confirm answered"); actions.onConfirm(c.proposalId, c.yes) }
            is Command.Undo -> { Log.i(TAG, "undo asked"); actions.onUndo(c.proposalId) }
            else -> Log.i(TAG, "command ${c::class.simpleName} is handled in a later phase")
        }
    }

    /** A held frame means no offset or a stale one: ask the glasses for a fresh `pc_hello`, at most once per window. */
    private suspend fun requestHello(now: Long) {
        val last = lastHelloRequestAtMs
        if (last != null && now >= last && now - last < HELLO_REQUEST_MIN_INTERVAL_MS) return
        if (link.send(Channels.HELLO_REQUEST, Wire.encode(HelloRequest()))) {
            lastHelloRequestAtMs = now
            Log.i(TAG, "frame held: asked the glasses for a fresh hello")
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
                latestNudge = nudges.latest.value,
            ),
        )
        set(markDirty = false) { it.copy(lastFrame = frame) }
        if (s.link.carriesData && link.send(Channels.STATE, Wire.encode(frame))) actions.onShown(frame.overlay)
    }

    private fun overlay(): Overlay? = pickOverlay(voice.overlay.value, actions.overlay.value, minimisedHint ?: nudges.overlay.value)

    companion object {
        const val TAG = "ContextHub"

        /**
         * Precedence (Phase 7): voice Listening > actions (Confirm/undo toast) > other voice overlays > nudge.
         * BUG-R2-5: an undo toast and an Answer at once (either order) become the Answer carrying the undo, so the wearer
         * sees both and a double-tap undoes while the window is open.
         */
        fun pickOverlay(voice: Overlay?, actions: Overlay?, nudge: Overlay?): Overlay? {
            if (voice is Overlay.Listening) return voice
            if (voice is Overlay.Answer && actions is Overlay.Toast && actions.undoProposalId != null) {
                return voice.copy(undoProposalId = actions.undoProposalId)
            }
            return actions ?: voice ?: nudge
        }
        const val HELLO_REQUEST_MIN_INTERVAL_MS = 10_000L
        /** Glasses pop-up for "Open on glasses" while minimised: the HUD stays in the background. */
        const val OPEN_WHILE_MINIMISED = "Running in the background \u00b7 open it on your glasses"
        const val MINIMISED_HINT_MS = 5_000L
        /** A hello's repeated wearer-exit stamp counts only this long: the lost-message case ends in a reopen about 30 s after the exit. */
        const val EXIT_STAMP_FRESH_MS = 90_000L
    }
}
