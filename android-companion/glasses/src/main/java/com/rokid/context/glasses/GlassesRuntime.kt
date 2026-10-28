package com.rokid.context.glasses

import com.rokid.context.glasses.log.withDiagFields
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import com.rokid.context.core.log.PcLog
import com.rokid.context.glasses.capture.diag.DiagLog
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
    /** Fix wave K4 / LGC3: installs the glasses diagnostic log (through the DiagLog shim; no filesystem work here); first, so every later log line lands there. */
    private val diagInstalled = DiagLog.install(app)
    /** Nothing launched here may crash the glasses app; only the exception's simple name is logged. */
    private val errors = CoroutineExceptionHandler { _, e -> PcLog.w(TAG, "runtime job failed: ${e.javaClass.simpleName}") }
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
        enabled = { settingsCache.capture().workRestEnabled },
        store = com.rokid.context.glasses.capture.session.PrefsWorkRestStore(app.getSharedPreferences("work_rest", Context.MODE_PRIVATE)),
        bootCount = { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) },
    )
    /** The capture service's latest status for the HUD marks (Cooling, capture degraded); idle until the service publishes. */
    val captureStatus = MutableStateFlow(SessionStatus(running = false, motion = MotionState.STILL, keyframesLast10Min = 0, restingUntilMs = null, reason = null))
    /** Smart mode is sampling (or about to): the next-capture countdown and the icon ring have nothing to count down to. */
    val smartCapture = MutableStateFlow(false)
    val buffer = OutboundBuffer(File(app.filesDir, "outbound"), log = { DiagLog.i(UPLINK_TAG, it) })
    /** Our HUD activity's lifecycle, for the capture fallback (spec §1.7: never reopened over another app). */
    val hudPresence = HudPresence()
    /** Screen-wake attribution counters (battery wave A3): display-on events, ours, and nudges held back while the screen was off. */
    val screenWakes = com.rokid.context.glasses.capture.diag.ScreenWakeStats(android.os.SystemClock::elapsedRealtime)
    val fallback = ForegroundFallback(hudPresence, onLaunch = { screenWakes.onOurUi() })
    /** About a second of "photo sent" after a frame is buffered or delivered (Glance check, icon ring pulse). */
    val photoCue = PhotoCue(scope)
    /** One wear state for the process, fed by the phone's `pc_wear` (see [WearDetector]); the sampling session gates on it. */
    val wear = com.rokid.context.glasses.capture.motion.WearDetector().apply { config = { com.rokid.context.glasses.capture.motion.WearParkConfig.of(settingsCache.read()) } }
    /** The phone's `pc_phone_ctx` (in use = screen on and unlocked), debounced; the sampling session gates on it. */
    val phoneUse = com.rokid.context.glasses.capture.session.PhoneUse()
    /** Wave B: the phone motion hub's state (`pc_motion_ctx`) with its TTL and the local walking veto (spec 2026-10-21 §9.1). */
    val contextGate = com.rokid.context.glasses.capture.session.ContextGate()
    @Volatile private var localMotion = MotionState.STILL
    @Volatile private var lastWalkAtMs: Long? = null
    /** The capture service reports the local classifier state (and null-resets it on stop) for the walking veto. */
    internal fun noteLocalMotion(s: MotionState, nowMs: Long) { localMotion = s; if (s == MotionState.WALKING) lastWalkAtMs = nowMs }
    internal fun resetLocalMotion() { localMotion = MotionState.STILL }
    /**
     * The context state to apply right now, computed on demand from the gate (TTL, apply flag, kill switch, local walking
     * veto), so it never outlives a message, a session close or a stopped capture service. Used by capture and nudges.
     */
    fun contextApplied(nowMs: Long = android.os.SystemClock.elapsedRealtime()): com.rokid.context.core.model.context.ContextState =
        contextGate.effective(nowMs, localMotion, lastWalkAtMs, settingsCache.readNormalized().motionSourcesOff)
    /**
     * Slice 3a: the capture plan (S59, S69). Built here so a persisted plan is loaded, and a TRIAL of another boot or past its limit is
     * reverted, before the capture service starts. The boot id is `Settings.Global.BOOT_COUNT` (persisted by the OS, changes per boot).
     */
    val plan = com.rokid.context.glasses.capture.plan.PlanController(
        com.rokid.context.glasses.capture.plan.PrefsPlanPersistence(app.getSharedPreferences(com.rokid.context.glasses.capture.plan.PrefsPlanPersistence.PREFS, Context.MODE_PRIVATE)),
        elapsed = android.os.SystemClock::elapsedRealtime,
        bootId = { com.rokid.context.capture.plan.BootId.of(android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1), System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()) },
        appliedState = { contextApplied() },
        send = { a -> controller?.sendPlanAck(a) },
        log = { DiagLog.i(CAPTURE_TAG, it) },
    )
    /**
     * Slice 3a Task 16: the IMU trace recorder. Records only while diagnostics are on with a saved epoch ([diagRecord]) or the phone's `pc_trace_ctl` says collect (default: never). Files go under
     * `files/imu/` (`traces/`, `imu_trace_tmp/`, `trace_epoch`); all file work runs on its own idle-blocked daemon thread (no timer, no wake-up).
     */
    private val traceRecorderFuture = java.util.concurrent.CompletableFuture<com.rokid.context.glasses.capture.trace.TraceRecorder>()
    /** The thread that built the recorder (a test pins that it is the writer, never the main looper). */
    @Volatile internal var recorderBuiltOn: Thread? = null
    private fun buildTraceRecorder(): com.rokid.context.glasses.capture.trace.TraceRecorder =
        com.rokid.context.glasses.capture.trace.TraceRecorder(
            root = File(app.filesDir, "imu"), clock = android.os.SystemClock::elapsedRealtime, wallClock = System::currentTimeMillis,
            bootId = { com.rokid.context.capture.trace.Manifest.bootIdOf(android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0).coerceAtLeast(0)) },
            io = { r -> traceWriter.execute(r) }, log = { DiagLog.i(CAPTURE_TAG, it) },
            diagEnabled = diagRecord::diagEnabled,
            pendingFile = File(app.filesDir, "trace_cleanup_pending"), // LGC8-min: the received-cleanup record, the single source of truth for `cleanupPending()`
            // LGC5: at process start, after the recovery (writer thread): restart count, flag state, epoch and how many killed traces were found. Reads no recorder (it may still be constructing).
            onRecovered = { r -> DiagLog.i(TRACE_TAG, com.rokid.context.glasses.capture.trace.TraceStateReporter.boot(restarts.closes, diagRecord.stateFor(r.epochSaved), r.epochSaved, r.found)) },
        )
    /**
     * The recorder is constructed ONLY on the writer thread (queued at process start, see `init`), never lazily on the caller: its construction queues the start-up recovery
     * (TraceStorage.recover), the class of the 2026-10-23 ANR. The main thread must not call this before [traceRecorderReady] (it throws instead of blocking); other threads wait for
     * the writer. Main-thread code uses [traceRecorderOrNull] / [traceRecorderReady].
     */
    val traceRecorder: com.rokid.context.glasses.capture.trace.TraceRecorder get() {
        traceRecorderFuture.getNow(null)?.let { return it }
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "the trace recorder is built on the writer thread: the main thread must not wait for it" }
        return try { traceRecorderFuture.get() } catch (e: java.util.concurrent.ExecutionException) { throw IllegalStateException("trace recorder construction failed", e.cause) }
    }
    /** The recorder once the writer has built it, else null. Never builds or waits. */
    val traceRecorderOrNull: com.rokid.context.glasses.capture.trace.TraceRecorder? get() = traceRecorderFuture.getNow(null)
    val traceRecorderReady: Boolean get() = traceRecorderFuture.isDone && !traceRecorderFuture.isCompletedExceptionally
    /** LGC5: where the capture session says why it offered no clean boundary (read by [traceState]). */
    val traceBoundary = com.rokid.context.glasses.capture.trace.TraceBoundaryReporter()
    /** LGC5: the `trace state:` line (tag [TRACE_TAG]); driven by [pollTraceState]. */
    val traceState: com.rokid.context.glasses.capture.trace.TraceStateReporter by lazy {
        com.rokid.context.glasses.capture.trace.TraceStateReporter(
            traceRecorder, traceBoundary, { diagRecord.state() }, android.os.SystemClock::elapsedRealtime,
            emit = { warn, line -> if (warn) DiagLog.w(TRACE_TAG, line) else DiagLog.i(TRACE_TAG, line) },
            pauseSource = { pauses.sourceLabel() },
        )
    }
    /** Prints the state line when the blocker changed or a minute passed. A no-op until the recorder exists (this never constructs it on the calling thread); memory reads only. */
    fun pollTraceState() { if (traceRecorderReady) traceState.poll() }
    /** v0.9.7 B: runs [r] on the trace writer thread (the sensor-recording batches are written there, ordered with the epoch wipe and the forget run). */
    fun traceWriterExecute(r: Runnable) { traceWriter.execute(r) }
    /** The single storage writer (idle-blocked daemon thread, no timer): the recorder's epoch wipe / recovery and the `diag_state` load and saves run on it, in order. */
    private val traceWriter: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "imu-trace").also { it.isDaemon = true } }
    }
    /**
     * LGC4: the persisted `diag_record` request (`files/diag_state`, path from `dataDir`, read on the writer) and `diagEnabled()`, the single trace-gate predicate. The request
     * only takes effect once the recorder's epoch is durable. When it goes off a running diagnostic trace closes. The glasses LOG policy does not follow it (see the log package policy constant).
     * Status read API for LGC6-min: `diagRecord.state().wire` and `traceRecorder.startBlocker()?.wire`.
     */
    val diagRecord: com.rokid.context.glasses.log.DiagRecord = com.rokid.context.glasses.log.DiagRecord(
        com.rokid.context.glasses.log.DiagStateFile.forApp(app),
        epochSaved = { traceRecorderOrNull?.epochSaved ?: false }, io = { r -> traceWriter.execute(r) }, log = { DiagLog.i(CAPTURE_TAG, it) },
    ).also { d -> d.onChanged = { traceRecorderOrNull?.onDiagChanged() } }
    /** Slice 3a Task 30: the persisted mark outbox (`mark_queue` prefs) and the tap that fills it. A mark never touches capture (S45). */
    val markOutbox: com.rokid.context.glasses.mark.MarkOutbox by lazy {
        com.rokid.context.glasses.mark.MarkOutbox(
            com.rokid.context.glasses.mark.PrefsMarkStore(app.getSharedPreferences(com.rokid.context.glasses.mark.PrefsMarkStore.PREFS, Context.MODE_PRIVATE)),
            wallClock = System::currentTimeMillis, newEpoch = { java.util.UUID.randomUUID().toString() },
        )
    }
    val markCue = com.rokid.context.glasses.mark.MarkCue(scope)
    val markTap: com.rokid.context.glasses.mark.MarkTap by lazy {
        com.rokid.context.glasses.mark.MarkTap(
            markOutbox, markCue, send = { m -> controller?.sendMark(m) ?: false }, enabled = { true },
            wallClock = System::currentTimeMillis, elapsed = android.os.SystemClock::elapsedRealtime,
            bootCount = { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1) },
            photo = markPhoto, scope = scope, sendPhotoResult = { r -> controller?.sendMarkPhoto(r) ?: false },
        )
    }
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
    val uplink = FrameUplink(scope, buffer, sendChunk = { c -> controller?.sendFrameChunk(c) ?: -1 }, canSend = ::canSend, log = { DiagLog.i(UPLINK_TAG, it) }, onBufferChanged = { controller?.sendHello() }, onDelivered = { photoCue.pulse() })

    /** Fix wave H4: last-alive heartbeat and the "restart gap" log (Rokid force-stops us on a scene change). */
    private val diagPrefs = app.getSharedPreferences("restart_diag", Context.MODE_PRIVATE)
    /** Fix wave H: full | icon | home, persisted on change; read at the next process start (see [restarts]). */
    val uiMode = UiModeStore(diagPrefs)
    val restarts = RestartDiagnostics(
        diagPrefs,
        uiMode = { uiMode.get() },
        installedAtMs = { runCatching { app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(0L) },
        lastExit = {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) null
            else runCatching {
                app.getSystemService(android.app.ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull()
                    ?.let { ExitFacts(it.reason == android.app.ApplicationExitInfo.REASON_USER_REQUESTED, it.description, it.timestamp) }
            }.getOrNull()
        },
        classify = { f, lastAlive, now -> ExitClassifier.classify(f, lastAlive, now) { pid -> ProcCaller.read(pid, f.timestampMs) } },
        log = { DiagLog.i(CAPTURE_TAG, it) },
    )
    /** Fix wave I: when a start goes straight to icon mode (after a kill, or on the phone's automatic reopen). */
    val startGate = AutoStartGate(takePending = { restarts.takeStartMinimised() }, uptimeMs = { android.os.SystemClock.uptimeMillis() })
    /** Fix wave I: the app is minimised but "display over other apps" is off, so no icon shows; reported to the phone's Diagnostics. */
    @Volatile var iconBlocked = false

    init {
        runCatching { restarts.onProcessStart() }
        scope.launch { while (true) { delay(HEARTBEAT_MS); runCatching { restarts.heartbeat() }; runCatching { pollTraceState() } } }
        // The icon ticks once a second: redraw it as the cue starts and ends, so a 1 s pulse is never missed.
        scope.launch { photoCue.active.collect { minimisedIcon.refresh() } }
        scope.launch { captureBlink.active.collect { minimisedIcon.refresh() } }
    }

    init {
        // LGC5: build the recorder on the WRITER thread (queued after the `diag_state` load): its recovery, the replayed cleanup and the `trace boot:` line then happen at process start, whatever the
        // capture mode, and the main thread never constructs it first. A later main-thread access finds it built or waits only for the (cheap) construction.
        traceWriter.execute {
            try { recorderBuildHook?.invoke(); recorderBuiltOn = Thread.currentThread(); traceRecorderFuture.complete(buildTraceRecorder()) }
            catch (t: Throwable) { traceRecorderFuture.completeExceptionally(t) }
        }
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
        log = { PcLog.i(TAG, "ptt: $it") },
    )

    /** Session open, not listening, and the phone is not showing Listening (flush resumes once the answer is up). */
    private fun canSend(): Boolean {
        val c = controller ?: return false
        return c.sessionOpen.value && !listening.value && c.frame.value?.overlay !is Overlay.Listening
    }

    fun attach(c: HudController) {
        controller = c
        c.onFrameAck = uplink::onAck
        c.onWear = { w -> if (w == null) wear.reset() else { wear.onRemote(w); PcLog.i(TAG, "wear=$w (from the phone)") } }
        c.onMotionContext = { m -> contextGate.onMessage(m, android.os.SystemClock.elapsedRealtime()) }
        c.onPhoneUse = { u -> if (u == null) phoneUse.reset() else phoneUse.onRemote(u, android.os.SystemClock.elapsedRealtime()) }
        // Hello goes out first; give the phone a moment to take the clock offset before frames follow.
        c.onSessionOpened = { scope.launch { delay(FLUSH_AFTER_HELLO_MS); uplink.kick() } }
        c.onPhoneSettings = { s -> plan.onPhoneSettings(s.planAware); ioScope.launch { applyPhoneSettings(s) } }
        c.onPhoneSettingsPush = { s -> diagRecord.onSettings(s.diagRecord) }
        c.onCapturePlan = { m -> plan.onMessage(m) }
        c.onTraceControl = { m -> markOutbox.onEpoch(m.installId, m.deletionCounter)
            // The arrival clocks are sampled HERE, before the control waits in the writer queue (a long recovery must not shift Forget's range or extend a collect window).
            val arrivedElapsed = android.os.SystemClock.elapsedRealtime(); val arrivedWall = System.currentTimeMillis()
            // The recorder exists only on the writer thread (queued behind its construction), so the control is handed over there: the caller (the link callback, possibly main) never waits for it.
            traceWriter.execute {
                traceRecorder.onControl(m, arrivedElapsed, arrivedWall); diagRecord.retryPersist()
                // LGC6-min: tell the phone the new state once the control's ordered work (fence, epoch save) has run on the writer, so its re-send ends at `enabled` without waiting for a frame's hello.
                traceWriter.execute { try { controller?.sendHello() } catch (e: Exception) { /* the next hello carries it */ } }
            }
        }
        c.onMarkAck = { a -> markOutbox.onAck(a) }
        c.onMarkPhotoRequest = { r -> markTap.onPhoneRequest(r) }
        c.onMarkPhotoSessionClosed = { markTap.onSessionClosed() }
        c.onMarksDue = { markTap.flush() }
        c.statusAck = { plan.onLinkSession(); plan.statusAck().takeIf { it.gen != 0L } }   // the caps announcement stays HudController's gen-0 default
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
        c.onPhoneUse = {}
        c.onMotionContext = {}
        c.onAutoOpen = {}
        c.onSessionOpened = {}
        c.onPhoneSettings = {}
        c.onCapturePlan = {}
        if (controller !== c) return
        ptt.cancel()
        ptt.onProblem = {} // only the current activity's handler: a newer activity's must survive an old detach
        controller = null
        attachJob?.cancel()
        bridge.release()
    }

    /** `pc_hello`: clock, battery, buffer size, local pauses, unconfirmed resumes, next capture (all glasses clock). */
    fun hello(nextCaptureAtMs: Long?): Hello = Hello(
        markPhotoRequest = true, // v0.9.7 slice D: this build answers pc_mark_photo_request
        glassesBatteryPct = batteryPct(),
        bufferSize = buffer.size,
        glassesNowMs = System.currentTimeMillis(),
        pauses = pauses.helloRecords(),
        nextCaptureAtMs = nextCaptureAtMs,
        resumedAtMs = pauses.resumedAtMs(),
        rokidCloses = restarts.closes,
        unverifiedCloses = restarts.unverifiedCloses,
        otherCloses = restarts.otherCloses,
        lastRestartGapSec = restarts.lastGapSec,
        userExitAtMs = restarts.userExitAtMs,
        iconBlocked = iconBlocked && uiMode.get() == UiModeStore.ICON,
    ).withDiagFields(diagRecord, if (traceRecorderReady) traceState else null) // LGC6-min: memory reads only, never builds the recorder on this thread

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

    /**
     * v0.9.6 slice C, the glasses half of the bounded exception "a mark touches capture": stores the photo taken for a mark WITHOUT the two `captureAllowed` checks of [bufferCaptured] (the user asked for
     * it explicitly, even while capture is paused). Only the mark photo calls this: it names its mark, it is one frame per mark, and every ordinary frame still goes through [bufferCaptured]. It takes the
     * same [captureGate], so a pause toggle that clears the buffer either runs before it (the photo stays) or after it (a pause after the mark drops its undelivered photo, as for any frame).
     */
    fun bufferMarkPhoto(frameId: String, keyframe: KeyframeOut, capturedAtMs: Long, luma: Int?, batteryPct: Int?, markRef: String): Boolean {
        val stored = synchronized(captureGate) { buffer.add(frameId, keyframe.jpeg, capturedAtMs, luma, keyframe.phash, batteryPct, keyframe = keyframe, markRef = markRef) }
        if (stored) { controller?.sendHello(); uplink.kick() } // no capture blink: the mark has its own cue
        return stored
    }

    /** What a mark needs from capture: the photo (through the running capture service) and the discard of an undelivered one. */
    val markPhoto: com.rokid.context.glasses.mark.MarkPhotoRunner = object : com.rokid.context.glasses.mark.MarkPhotoRunner {
        override suspend fun take(markRef: String): com.rokid.context.glasses.mark.MarkPhotoOutcome =
            CaptureService.liveInstance?.takeMarkPhoto(markRef)
                ?: com.rokid.context.glasses.mark.MarkPhotoOutcome.Failed(com.rokid.context.core.model.MarkPhotoReason.NO_CAPTURE_SERVICE)
        override fun discard(markRef: String) { synchronized(captureGate) { buffer.discardMarkPhoto(markRef) } }
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
        const val TRACE_TAG = "PcTrace"
        const val HEARTBEAT_MS = 30_000L
        const val FLUSH_AFTER_HELLO_MS = 500L
        @Volatile private var instance: GlassesRuntime? = null
        fun get(context: Context): GlassesRuntime =
            instance ?: synchronized(this) { instance ?: GlassesRuntime(context.applicationContext).also { instance = it } }

        /** Test seam: the CXR bridge (a fake in Robolectric tests). */
        @Volatile internal var bridgeFactory: (Context) -> GlassesBridge = { CxrBridge(it) }

        /** Test seam: runs on the writer thread just before the recorder is built (a test blocks it to prove the main thread neither builds nor waits for the recorder). */
        @VisibleForTesting
        @Volatile internal var recorderBuildHook: (() -> Unit)? = null

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
