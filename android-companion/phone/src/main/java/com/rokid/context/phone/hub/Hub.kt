package com.rokid.context.phone.hub

import android.util.Log
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.Overlay
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.PROTOCOL_VERSION
import com.rokid.context.core.model.Wire
import com.rokid.context.link.ClockOffset
import com.rokid.context.link.GlassesEvent
import com.rokid.context.link.GlassesLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
) : GlassesStatusSource {
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
    private val broadcaster = StateBroadcaster(scope, onError = { Log.w(TAG, "broadcast failed: ${it.javaClass.simpleName}") }) { broadcast() }

    val started: Boolean get() = jobs.isNotEmpty()

    fun start() {
        if (started) return
        jobs = listOf(
            scope.launch { link.state.collect { s -> onLinkState(s) } },
            scope.launch { link.events.collect { guarded("event ${it::class.simpleName}") { onEvent(it) } } },
            scope.launch { link.ready.collect { guarded("ready") { onReady() } } },
            scope.launch { guarded("prune") { withContext(io) { pauses.pruneHistory() } } },
            scope.launch { voice.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { actions.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { nudges.overlay.drop(1).collect { broadcaster.markDirty() } },
            scope.launch { nudges.latest.drop(1).collect { broadcaster.markDirty() } },
            // The gate's reason travels in pc_settings (the glasses toast it on tap = Ask, spec §5.1.1).
            scope.launch { voice.askBlockedReason.drop(1).collect { guarded("settings") { pushSettings() } } },
            broadcaster.start(),
        )
        link.connectOnce()
    }

    fun stop() {
        voice.onLinkLost() // first (preflight F15): the link is closing, so an open glasses listen can't complete
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        link.close()
    }

    /**
     * Explicit Reconnect. A hub whose service is down only starts the service (never a silent no-op): its [start]
     * connects once from Idle, so a second connect here would race it.
     */
    override fun requestReconnect() {
        if (started) link.connect() else startService()
    }

    fun onAppResumed() = link.connectOnResume()

    /** A settings change from the phone: persisted, pushed now (when linked) and reflected in the next frame. */
    fun updateSettings(transform: (HudSettings) -> HudSettings) {
        settings.write(transform(settings.read()))
        _hudSettings.value = settings.read()
        scope.launch { guarded("settings") { pushSettings(); broadcaster.markDirty() } }
    }

    /** A phone pause (capture only): a new PHONE record from now until [untilMs] (null = until resumed), pushed now. */
    suspend fun pausePhone(untilMs: Long?): PauseRecord {
        val r = PauseRecord(UUID.randomUUID().toString(), PauseOrigin.PHONE, createdAtMs = clock(), untilMs = untilMs)
        withContext(io) { pauses.add(r) }
        Log.i(TAG, "phone pause added ${r.pauseId}")
        pushSettings(); broadcaster.markDirty()
        return r
    }

    /** Ends only the named records (spec §5.5), then pushes the settings and a frame. */
    suspend fun resume(ids: Set<String>) {
        withContext(io) { pauses.resume(ids) }
        Log.i(TAG, "phone resume for ${ids.size} pause(s)")
        pushSettings(); broadcaster.markDirty()
    }

    /** Something a frame shows changed outside the hub's own flows: send a fresh frame (≤ 4 Hz). */
    fun refresh() = broadcaster.markDirty()

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
        if (s != LinkState.CONNECTED) { newSession(); voice.onLinkLost() }
        set { it.copy(link = s, offset = offset) }
    }

    /**
     * The offset belongs to one glasses session (no RTC there: the clock can jump at a reboot). Until the new
     * session's hello, pauses are anchored at receipt. Anchored originals of the old session are in its clock: dropped.
     */
    private fun newSession() {
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
        set(markDirty = false) { it.copy(versionMismatch = false, offset = null) }
        pushSettings()
        broadcaster.markDirty()
    }

    private suspend fun pushSettings() {
        val active = withContext(io) { pauses.active() }
        val resumed = withContext(io) { pauses.recentlyResumedIds() }
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
                    )
                }
                pushSettings()
            }
            is GlassesEvent.Cmd -> onCommand(e.command, now)
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
        }
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
        if (s.link == LinkState.CONNECTED) link.send(Channels.STATE, Wire.encode(frame))
    }

    /** Precedence (Phase 7): voice Listening > actions (Confirm/undo toast) > other voice overlays > nudge. */
    private fun overlay(): Overlay? {
        val v = voice.overlay.value
        return (v as? Overlay.Listening) ?: actions.overlay.value ?: v ?: nudges.overlay.value
    }

    companion object {
        const val TAG = "ContextHub"
        const val HELLO_REQUEST_MIN_INTERVAL_MS = 10_000L
    }
}
