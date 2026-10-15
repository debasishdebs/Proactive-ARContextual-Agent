package com.rokid.context.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.rokid.context.core.model.Command
import com.rokid.context.core.model.Hello
import com.rokid.context.core.model.Overlay
import android.view.WindowManager
import com.rokid.context.glasses.capture.CaptureScheduler
import com.rokid.context.glasses.capture.CaptureService
import com.rokid.context.glasses.capture.ForegroundFallback
import com.rokid.context.glasses.overlay.IconRing
import com.rokid.context.glasses.overlay.IconState
import com.rokid.context.glasses.overlay.MinimisedIcon
import com.rokid.context.glasses.overlay.PhotoCue
import com.rokid.context.glasses.overlay.PopupOverlay
import com.rokid.context.glasses.capture.HudPresence
import com.rokid.context.glasses.capture.KeyframeOut
import com.rokid.context.glasses.capture.OutboundBuffer
import com.rokid.context.glasses.capture.SessionStatus
import com.rokid.context.glasses.capture.session.CameraOwner
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.FrameUplink
import com.rokid.context.glasses.link.GlassesBridge
import com.rokid.context.glasses.link.HudController
import com.rokid.context.glasses.link.SharedBridge
import com.rokid.context.core.model.HudSettings
import com.rokid.context.glasses.link.HudSettingsCache
import com.rokid.context.glasses.pause.GlassesPauses
import com.rokid.context.glasses.pause.MergeResult
import com.rokid.context.glasses.pause.QuietHoursClock
import com.rokid.context.glasses.pause.PauseStore
import com.rokid.context.glasses.pause.PauseToggle
import com.rokid.context.glasses.voice.AudioRecordSource
import com.rokid.context.glasses.voice.PcmSource
import com.rokid.context.glasses.voice.PttLink
import com.rokid.context.glasses.voice.PushToTalk
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-wide glasses state shared by [MainActivity] and the capture service: pause set, outbound buffer, uplink,
 * push-to-talk and the current [HudController]. A controller exists only while the activity does, i.e. while the CXR
 * session can be open (spec §1.7), so nothing is sent without one.
 */
class GlassesRuntime private constructor(private val app: Context) {
    /** Nothing launched here may crash the glasses app; only the exception's simple name is logged. */
    private val errors = CoroutineExceptionHandler { _, e -> Log.w(TAG, "runtime job failed: ${e.javaClass.simpleName}") }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errors)
    /** The persisted last `pc_settings` (quiet hours and their zone apply offline); HudController writes it on each push. */
    val settingsCache = HudSettingsCache(app.getSharedPreferences(HudSettingsCache.PREFS, Context.MODE_PRIVATE))
    @Volatile private var latestSettings: HudSettings? = null
    val pauses = GlassesPauses(
        PauseStore(File(app.filesDir, "pauses.preferences_pb"), ioScope),
        quiet = QuietHoursClock { latestSettings ?: settingsCache.read().also { latestSettings = it } },
    )
    /** One camera owner for the process: the sampling session and every Fixed capture take turns through it. */
    val cameraOwner = CameraOwner()
    /** One work/rest clock for the process (spec P1.5): leaving and re-entering the HUD or toggling the mode must not restart it. */
    val workRest = com.rokid.context.glasses.capture.session.WorkRest(
        android.os.SystemClock::elapsedRealtime, { settingsCache.capture().workMinutes }, { settingsCache.capture().restMinutes },
    )
    /** The capture service's latest status for the HUD marks (Cooling, capture degraded); idle until the service publishes. */
    val captureStatus = MutableStateFlow(SessionStatus(running = false, motion = MotionState.STILL, keyframesLast10Min = 0, restingUntilMs = null, reason = null))
    /** Smart mode is sampling (or about to): the next-capture countdown and the icon ring have nothing to count down to. */
    val smartCapture = MutableStateFlow(false)
    val buffer = OutboundBuffer(File(app.filesDir, "outbound"), log = { Log.i(UPLINK_TAG, it) })
    /** Our HUD activity's lifecycle, for the capture fallback (spec §1.7: never reopened over another app). */
    val hudPresence = HudPresence()
    val fallback = ForegroundFallback(hudPresence)
    /** About a second of "photo sent" after a frame is buffered or delivered (Glance check, icon ring pulse). */
    val photoCue = PhotoCue(scope)
    /** One wear state for the process, fed by the phone's `pc_wear` (see [WearDetector]); the sampling session gates on it. */
    val wear = com.rokid.context.glasses.capture.motion.WearDetector()
    /** Fix wave H3: a ~600 ms HUD mark per saved keyframe ("Capture blink on HUD", synced `captureCue`). */
    val captureBlink = com.rokid.context.glasses.hud.CaptureBlink(scope, { settingsCache.read().captureCue })
    /** The minimised icon (spec 2026-10-14 §3), one window per process; shown only while the keep-alive service runs. */
    val minimisedIcon: MinimisedIcon by lazy {
        MinimisedIcon(app, app.getSystemService(WindowManager::class.java)) {
            val scheduler = CaptureScheduler(app)
            val now = System.currentTimeMillis()
            IconState(
                running = CaptureService.liveInstance != null,
                fraction = if (smartCapture.value) null else IconRing.fraction(scheduler.nextTriggerAtMs(), scheduler.armedAtMs(), now),
                paused = pauses.records.value.any { it.isActiveAt(now) },
                cue = photoCue.active.value || captureBlink.active.value, // fix wave H3: the icon blinks too
            )
        }
    }
    /** The minimised pop-up (spec 2026-10-14 §4), one window per process. */
    val popup: PopupOverlay by lazy { PopupOverlay(app, app.getSystemService(WindowManager::class.java)) }
    /** Push-to-talk recording ([ptt] sets it); frames never flush while true (spec §1.8). */
    val listening = MutableStateFlow(false)
    /** The attached controller (tests check that exactly one is live). */
    @Volatile internal var controller: HudController? = null
        private set
    private var attachJob: Job? = null
    /** Process-wide: built once from the application context, never per activity (CXR-S cannot unsubscribe). */
    val bridge: SharedBridge by lazy { SharedBridge(bridgeFactory(app)) }
    val uplink = FrameUplink(scope, buffer, sendChunk = { c -> controller?.sendFrameChunk(c) ?: -1 }, canSend = ::canSend, log = { Log.i(UPLINK_TAG, it) }, onBufferChanged = { controller?.sendHello() }, onDelivered = { photoCue.pulse() })

    /** Fix wave H4: last-alive heartbeat and the "restart gap" log (Rokid force-stops us on a scene change). */
    private val diagPrefs = app.getSharedPreferences("restart_diag", Context.MODE_PRIVATE)
    /** Fix wave H: full | icon | home, persisted on change; read at the next process start (see [restarts]). */
    val uiMode = UiModeStore(diagPrefs)
    val restarts = RestartDiagnostics(
        diagPrefs,
        uiMode = { uiMode.get() },
        installedAtMs = { runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L) },
        lastExitWasUserRequested = {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) null
            else runCatching {
                app.getSystemService(android.app.ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull()?.reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED
            }.getOrNull()
        },
        log = { Log.i(CAPTURE_TAG, it) },
    )

    init {
        runCatching { restarts.onProcessStart() }
        scope.launch { while (true) { delay(HEARTBEAT_MS); runCatching { restarts.heartbeat() } } }
        // The icon ticks once a second: redraw it as the cue starts and ends, so a 1 s pulse is never missed.
        scope.launch { photoCue.active.collect { minimisedIcon.refresh() } }
        scope.launch { captureBlink.active.collect { minimisedIcon.refresh() } }
    }

    /** Tap = Ask (spec §5.1). Only an open session takes it; capture pauses don't matter (Ask is a user action). */
    val ptt = PushToTalk(
        link = object : PttLink {
            override fun start(): Boolean = controller?.takeIf { it.sessionOpen.value }?.send(Command.ListenStart) == true
            override fun audio(base64: String): Boolean = controller?.sendAudio(base64) == true
            // A closed session can't take the end of an utterance (whichever of the ptt thread and the cancel runs first).
            override fun end() { controller?.takeIf { it.sessionOpen.value }?.sendListenEnd() }
            override fun cancel() { controller?.send(Command.Cancel) }
        },
        recording = listening,
        hasPermission = { app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        sourceFactory = { pcmSourceFactory() },
        log = { Log.i(TAG, "ptt: $it") },
    )

    /** Session open, not listening, and the phone is not showing Listening (flush resumes once the answer is up). */
    private fun canSend(): Boolean {
        val c = controller ?: return false
        return c.sessionOpen.value && !listening.value && c.frame.value?.overlay !is Overlay.Listening
    }

    fun attach(c: HudController) {
        controller = c
        c.onFrameAck = uplink::onAck
        c.onWear = { w -> if (w == null) wear.reset() else { wear.onRemote(w); Log.i(TAG, "wear=$w (from the phone)") } }
        // Hello goes out first; give the phone a moment to take the clock offset before frames follow.
        c.onSessionOpened = { scope.launch { delay(FLUSH_AFTER_HELLO_MS); uplink.kick() } }
        c.onPhoneSettings = { s -> ioScope.launch { applyPhoneSettings(s) } }
        attachJob?.cancel()
        attachJob = scope.launch {
            launch {
                combine(c.frame, listening) { f, l -> l || f?.overlay is Overlay.Listening }
                    .distinctUntilChanged()
                    .collect { busy -> if (!busy) uplink.kick() }
            }
            // A closed session can't take the rest of an utterance: stop recording, no pc_listen_end.
            launch { c.sessionOpen.collect { open -> if (!open) ptt.cancel() } }
        }
    }

    /** [c]'s activity is gone: its callbacks go quiet; if it is current, the bridge stops calling it too. */
    fun detach(c: HudController) {
        c.onFrameAck = {}
        c.onWear = {}
        c.onSessionOpened = {}
        c.onPhoneSettings = {}
        if (controller !== c) return
        ptt.cancel()
        ptt.onProblem = {} // only the current activity's handler: a newer activity's must survive an old detach
        controller = null
        attachJob?.cancel()
        bridge.release()
    }

    /** `pc_hello`: clock, battery, buffer size, local pauses, unconfirmed resumes, next capture (all glasses clock). */
    fun hello(nextCaptureAtMs: Long?): Hello = Hello(
        glassesBatteryPct = batteryPct(),
        bufferSize = buffer.size,
        glassesNowMs = System.currentTimeMillis(),
        pauses = pauses.helloRecords(),
        nextCaptureAtMs = nextCaptureAtMs,
        resumedAtMs = pauses.resumedAtMs(),
        rokidCloses = restarts.closes,
        lastRestartGapSec = restarts.lastGapSec,
    )

    /** Serialises a captured frame's write with the pause toggle's buffer clear (spec §5.5). */
    private val captureGate = Any()

    /**
     * Stores a captured frame only while capture is allowed. Checked again under [captureGate], because the camera
     * window lasts seconds after CaptureService's first check: a double-tap pause clears under the same gate after
     * recording the pause, so either this sees the pause or the clear removes the frame. A pause that arrives during
     * the write (a phone merge) is caught by the second check.
     */
    fun bufferCaptured(frameId: String, jpeg: ByteArray, capturedAtMs: Long, luma: Int?, phash: String?, batteryPct: Int?): BufferResult =
        synchronized(captureGate) {
            if (!pauses.captureAllowed()) return BufferResult.PAUSED
            if (!buffer.add(frameId, jpeg, capturedAtMs, luma, phash, batteryPct)) return BufferResult.NOT_STORED
            if (!pauses.captureAllowed()) { buffer.remove(frameId); return BufferResult.PAUSED }
            BufferResult.STORED
        }

    /** A sampled keyframe: buffered with its full metadata so it reaches the phone (and survives a restart) intact. */
    fun bufferCaptured(frameId: String, keyframe: KeyframeOut, capturedAtMs: Long, luma: Int?, batteryPct: Int?): BufferResult =
        synchronized(captureGate) {
            if (!pauses.captureAllowed()) return BufferResult.PAUSED
            if (!buffer.add(frameId, keyframe.jpeg, capturedAtMs, luma, keyframe.phash, batteryPct, keyframe = keyframe)) return BufferResult.NOT_STORED
            if (!pauses.captureAllowed()) { buffer.remove(frameId); return BufferResult.PAUSED }
            BufferResult.STORED
        }

    /** A frame was buffered: cue it, report the new buffer size and flush if the session is open. */
    fun onFrameBuffered() {
        captureBlink.onKeyframeSaved() // first: nothing below may delay or break it
        photoCue.pulse()
        controller?.sendHello()
        uplink.kick()
    }

    /** The next capture is armed: tell the phone its new time (the hello from [onFrameBuffered] still has the old one). */
    fun onCaptureScheduled() {
        controller?.sendHello()
    }

    /** Double-tap pause/resume, applied here at once (spec §5.5); a pause drops the buffered frames (off Main). */
    suspend fun togglePause(): PauseToggle {
        val t = pauses.toggle()
        if (t is PauseToggle.Paused) withContext(Dispatchers.IO) { synchronized(captureGate) { buffer.clear() } }
        return t
    }

    /**
     * A phone push (on the IO scope): merges its pauses and, when it added a record active now, drops the buffered
     * frames under [captureGate], the same as a local pause (spec §5.5).
     */
    internal suspend fun applyPhoneSettings(s: HudSettings): MergeResult {
        latestSettings = s
        val r = pauses.mergeFromPhone(s)
        val now = System.currentTimeMillis()
        if (r.added.any { it.isActiveAt(now) }) withContext(Dispatchers.IO) { synchronized(captureGate) { buffer.clear() } }
        return r
    }

    fun batteryPct(): Int? = app.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    companion object {
        const val TAG = "PcGlasses"
        const val UPLINK_TAG = "PcUplink"
        const val CAPTURE_TAG = "PcCapture"
        const val HEARTBEAT_MS = 30_000L
        const val FLUSH_AFTER_HELLO_MS = 500L
        @Volatile private var instance: GlassesRuntime? = null
        fun get(context: Context): GlassesRuntime =
            instance ?: synchronized(this) { instance ?: GlassesRuntime(context.applicationContext).also { instance = it } }

        /** Test seam: the CXR bridge (a fake in Robolectric tests). */
        @Volatile internal var bridgeFactory: (Context) -> GlassesBridge = { CxrBridge(it) }

        /** Test seam: the glasses mic (a fake in Robolectric tests; preflight F23, same pattern as [bridgeFactory]). */
        @VisibleForTesting
        @Volatile internal var pcmSourceFactory: () -> PcmSource = { AudioRecordSource() }

        /** Drops the singleton and stops its jobs (so the next test's PauseStore owns its file alone). */
        internal fun resetForTests() = synchronized(this) {
            instance?.let { it.ptt.cancel(); it.scope.cancel(); it.ioScope.cancel(); it.minimisedIcon.hide(); it.popup.hide() }
            instance = null
        }
    }
}

/** [GlassesRuntime.bufferCaptured]: NOT_STORED = the write failed (e.g. storage full). */
enum class BufferResult { STORED, PAUSED, NOT_STORED }
