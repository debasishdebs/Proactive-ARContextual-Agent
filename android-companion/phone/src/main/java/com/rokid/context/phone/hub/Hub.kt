package com.rokid.context.phone.hub

import android.util.Log
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.GlassesStatusSource
import com.rokid.context.core.model.HelloRequest
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.LinkState
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
) : GlassesStatusSource {
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state
    private val _status = MutableStateFlow(_state.value.toGlassesStatus())
    override val status: StateFlow<GlassesStatus> = _status
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
            broadcaster.start(),
        )
        link.connectOnce()
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        link.close()
    }

    /** Explicit Reconnect: a hub whose service is down is started too, so the tap is never a silent no-op. */
    override fun requestReconnect() {
        if (!started) startService()
        link.connect()
    }

    fun onAppResumed() = link.connectOnResume()

    /** A settings change from the phone: persisted, pushed now (when linked) and reflected in the next frame. */
    fun updateSettings(transform: (HudSettings) -> HudSettings) {
        settings.write(transform(settings.read()))
        scope.launch { guarded("settings") { pushSettings(); broadcaster.markDirty() } }
    }

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
        if (s != LinkState.CONNECTED) newSession()
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
        link.send(Channels.SETTINGS, Wire.encode(StateFrameBuilder.settingsFor(settings.read(), active, _state.value.offset)))
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
            is GlassesEvent.Audio, GlassesEvent.ListenEnd -> Unit // voice: Phase 6
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
        val today = withContext(io) { sources.today() }
        val depth = withContext(io) { sources.queueDepth() }
        val health = withContext(io) { sources.aiHealth(now) }
        val s = _state.value // after the IO reads: the newest snapshot gets the next seq
        val frame = StateFrameBuilder.build(
            HubInputs(
                seq = ++seq, nowMs = now, link = s.link, activePauses = active, offset = s.offset, settings = settings.read(),
                glassesBatteryPct = s.glassesBatteryPct, nextCaptureAtMs = s.nextCaptureAtMs, queueDepth = depth,
                lastFrameAtMs = s.lastFrameAtMs, today = today, aiHealth = health, overlay = null, error = s.error,
            ),
        )
        set(markDirty = false) { it.copy(lastFrame = frame) }
        if (s.link == LinkState.CONNECTED) link.send(Channels.STATE, Wire.encode(frame))
    }

    companion object {
        const val TAG = "ContextHub"
        const val HELLO_REQUEST_MIN_INTERVAL_MS = 10_000L
    }
}
