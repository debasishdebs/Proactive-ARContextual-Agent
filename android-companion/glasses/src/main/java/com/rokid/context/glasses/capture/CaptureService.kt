package com.rokid.context.glasses.capture

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.rokid.context.core.model.context.ContextPolicies
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.MotionHintSender
import com.rokid.context.glasses.capture.diag.DiagFlags
import com.rokid.context.glasses.capture.diag.DiagLog
import com.rokid.context.glasses.capture.diag.StatusLogThrottle
import androidx.camera.core.ImageProxy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.BufferResult
import com.rokid.context.glasses.BuildConfig
import com.rokid.context.glasses.GlassesRuntime
import com.rokid.context.glasses.capture.keyframe.Candidate
import com.rokid.context.glasses.capture.keyframe.GateResult
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
import com.rokid.context.glasses.capture.keyframe.PrefsGateStore
import com.rokid.context.glasses.capture.motion.AndroidSensorFeed
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.session.movementSignals
import com.rokid.context.glasses.capture.motion.SensorFeed
import com.rokid.context.glasses.capture.session.AndroidKeyframeEncoder
import com.rokid.context.glasses.capture.session.BatteryBudget
import com.rokid.context.glasses.capture.session.CaptureProfile
import com.rokid.context.glasses.capture.session.SensorRatePolicy
import com.rokid.context.glasses.capture.session.DiagStats
import com.rokid.context.glasses.capture.diag.Heartbeat
import com.rokid.context.glasses.capture.motion.SensorRate
import com.rokid.context.glasses.capture.session.CameraXBind
import com.rokid.context.glasses.capture.session.CameraXSamplingCamera
import com.rokid.context.glasses.capture.session.HeatGuard
import com.rokid.context.glasses.capture.session.HeatLevel
import com.rokid.context.glasses.capture.session.HeatPoller
import com.rokid.context.glasses.capture.session.HeatReading
import com.rokid.context.glasses.capture.session.ImageProxyFrame
import com.rokid.context.glasses.capture.session.SamplingSession
import com.rokid.context.glasses.capture.session.WorkRest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The capture owner (spec §3.1, P1.1): a foreground camera service. In Smart mode it runs a [SamplingSession] (motion-driven
 * camera, keyframes with metadata) while the HUD keep-alive is up; the AlarmManager chain is then only a watchdog. In Fixed
 * mode (and for rest captures, "Capture now" while the session cannot sample, the 30 s fallback and a background wake) one
 * frame is taken per [captureNow] through the same [KeyframeGate]; [FrameGuard] keeps the battery and dark skips. Every
 * camera use goes through `GlassesRuntime.cameraOwner`, so a mode switch releases the session before a Fixed capture runs.
 * Local pauses are checked first (fail-closed). Logs carry counts and states only (tag PcCapture).
 */
class CaptureService : LifecycleService() {
    /** A throw in a launched job must never crash the process on Main; only the exception's simple name is logged. */
    private val errors = CoroutineExceptionHandler { _, e -> DiagLog.w(TAG, "capture job failed: ${e.javaClass.simpleName}") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + errors)
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val scheduler by lazy { CaptureScheduler(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val runtime by lazy { GlassesRuntime.get(this) }
    private val inFlight = AtomicBoolean(false)
    /** The one gate for Smart and Fixed: its last-5 ring and last keep time survive a service restart. */
    private val gate by lazy { KeyframeGate(PrefsGateStore(prefs), SystemClock::elapsedRealtime) }
    private val heat = HeatGuard(::readHeat, SystemClock::elapsedRealtime)
    private val heatPoller = HeatPoller(heat, SystemClock::elapsedRealtime)
    private class Smart(val session: SamplingSession, val feed: SensorFeed, val motion: MotionClassifier) { var rateJob: Job? = null }
    private var smart: Smart? = null
    /** Wave B: elapsedRealtime of the last second the local classifier said WALKING (the gate's 15 s veto window). */
    @Volatile private var lastWalkAt: Long? = null
    private var hintSender: MotionHintSender? = null

    /**
     * The context state applied to capture right now: the phone hub's message after its TTL and the unconditional local
     * walking veto. Evaluated per call so a step never waits for the next supervisor tick.
     */
    private fun appliedContext(): ContextState {
        val now = SystemClock.elapsedRealtime()
        val local = smart?.motion?.state?.value ?: MotionState.STILL
        if (local == MotionState.WALKING) lastWalkAt = now
        val a = runtime.contextGate.effective(now, local, lastWalkAt, runtime.settingsCache.readNormalized().motionSourcesOff)
        runtime.setContextApplied(a)
        return a
    }
    private val budget = BatteryBudget(SystemClock::elapsedRealtime).also { it.onChange = { l -> DiagLog.i(TAG, l) } }
    private val ratePolicy = SensorRatePolicy()
    private var lastUpBytes = 0L
    private var lastUpAt = -1L
    private var supervisor: Job? = null
    private var lastMode: CaptureMode? = null
    /** Three failed binds: Fixed 30 s, until the mode setting changes, the service restarts or the watchdog retries Smart (5 min). */
    private val degradedLatch = DegradedLatch(SystemClock::elapsedRealtime, DEGRADED_RETRY_MS)
    private val degraded: Boolean get() = degradedLatch.tripped
    private val statusThrottle = StatusLogThrottle()
    private var fixedSeq = 0L
    @Volatile private var lastStartId = 0
    @Volatile private var keepAliveMode = false
    /** 1 while a Smart session is held, else 0 (a test seam: a restart must never leave two). */
    internal val smartSessionCount: Int get() = if (smart != null) 1 else 0
    @Volatile internal var capturesStarted = 0
        private set
    @Volatile internal var lastOutcome: Outcome? = null
        private set

    internal enum class Outcome { BUFFERED, SKIPPED, FAILED }

    private val screenOnReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { runtime.screenWakes.onScreenOn() }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON)) }
        lastUpBytes = runtime.uplink.bytesSent; lastUpAt = SystemClock.elapsedRealtime() // the first heartbeat must not count pre-restart bytes
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onDestroy() {
        if (liveInstance === this) liveInstance = null
        runCatching { unregisterReceiver(screenOnReceiver) }
        stopSmart("destroyed")
        cameraExecutor.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action == ACTION_KEEP_ALIVE) {
            // Started while the HUD is in front: this foreground service keeps while-in-use camera access after the
            // app goes to the background, as long as nothing restarts it from the background (alarms come in-process).
            val started = startForegroundOrStop(start = { startForeground(NOTIFICATION_ID, notification()) }) { stopSelf(startId) }
            if (started) { keepAliveMode = true; liveInstance = this; startSupervisor(); DiagLog.i(TAG, "keep-alive started") }
            return START_NOT_STICKY
        }
        if (action != ACTION_SCHEDULED && action != ACTION_NOW) { if (!keepAliveMode) stopSelf(startId); return START_NOT_STICKY }
        lastStartId = startId
        val started = startForegroundOrStop(start = { startForeground(NOTIFICATION_ID, notification()) }) {
            scheduleNextCapture(failed = true)
            if (!keepAliveMode) stopSelf(startId)
        }
        if (!started) return START_NOT_STICKY
        // ACTION_NOW is the user's "Capture now" (forced); ACTION_SCHEDULED is the alarm (never forced).
        captureNow(BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_SKIP_DEDUPE, false) == true, userRequest = action == ACTION_NOW)
        return START_NOT_STICKY
    }

    // ---- Smart mode ----

    /** Once a second while the keep-alive is up: quiet hours, the 30 s heat poll (STOP included), mode changes, the status. */
    private fun startSupervisor() {
        if (supervisor?.isActive == true) return
        supervisor = scope.launch {
            var tick = 0
            while (isActive) {
              try {
                // Smart sampling needs the pause set loaded and quiet hours turned into pauses (it never goes through captureNow).
                // Off Main, so a holder of the pause lock never waits on the main thread.
                if (runtime.settingsCache.capture().captureMode == CaptureMode.SMART) {
                    try { withContext(Dispatchers.Default) { runtime.pauses.ensureLoaded() } } catch (e: CancellationException) { throw e } catch (e: Exception) { DiagLog.w(TAG, "pause load failed: ${e.javaClass.simpleName}") }
                }
                smart?.let { sm -> hintSender?.onSecond(sm.motion.state.value, SystemClock.elapsedRealtime()) }
                appliedContext()
                if (heatPoller.tick()) logHeat()
                reconcile()
                // Own guard: a throw here must not stop the status or the tick.
                try {
                    if (tick % 60 == 0) budget.onSample(batteryPercent(), isCharging(), runtime.settingsCache.readNormalized().batteryTargetHours)
                    applyRate()
                } catch (e: CancellationException) { throw e } catch (e: Exception) { DiagLog.w(TAG, "budget/rate update failed: ${e.javaClass.simpleName}") }
                publishStatus(tick++)
              } catch (e: CancellationException) { throw e } catch (e: Exception) { DiagLog.w(TAG, "supervisor tick failed: ${e.javaClass.simpleName}") }
                delay(SUPERVISE_MS)
            }
        }
    }

    /** Smart configured and the HUD keep-alive up: a session runs. Anything else: none, and the Fixed chain takes over. */
    private fun reconcile() {
        val s = runtime.settingsCache.capture()
        if (lastMode != s.captureMode) {
            if (lastMode != null) { degradedLatch.reset(); scheduleNextCapture(failed = false) } // a switch re-arms the alarm for the new mode
            lastMode = s.captureMode
        }
        if (degradedLatch.retryDue()) { DiagLog.i(TAG, "watchdog: retrying Smart after the camera fallback"); scheduleNextCapture(failed = false) }
        val wantSmart = s.captureMode == CaptureMode.SMART && keepAliveMode && !degraded
        if (wantSmart && smart == null) startSmart()
        else if (!wantSmart && smart != null) stopSmart(if (degraded) "bind" else "mode")
    }

    /** Feed `setRate` is @Synchronized and idempotent, so the supervisor and the rateJob may both call this. */
    private fun applyRate(state: MotionState? = null) {
        smart?.let { it.feed.setRate(ratePolicy.update(runtime.captureStatus.value.reason, SystemClock.elapsedRealtime(), state ?: it.motion.state.value)) }
    }

    private fun startSmart() {
        val clock: () -> Long = SystemClock::elapsedRealtime
        val glance = GlanceParams() // one instance: the classifier's detector and the session's novelty / cap rules
        val motion = MotionClassifier(clock, glance)
        ratePolicy.reset()
        val wear = runtime.wear
        wear.resetStillRun() // a still run from before a session restart must not park the glasses at once
        val session = SamplingSession(
            scope = scope, camera = CameraXSamplingCamera(this, this, cameraExecutor), owner = runtime.cameraOwner, motion = motion,
            wear = wear, gate = gate, heat = heat, workRest = runtime.workRest, encoder = AndroidKeyframeEncoder, sink = ::onKeyframe, clock = clock,
            settings = { runtime.settingsCache.readNormalized() },
            capturePaused = { !runtime.pauses.captureAllowed() },
            batteryPct = ::batteryPercent, charging = ::isCharging, batterySaver = ::isBatterySaverOn,
            profile = { val s = runtime.settingsCache.readNormalized(); CaptureProfile.of(s.cameraWake, budget.tier.value).withContext(ContextPolicies.of(appliedContext())) },
            // A hard cap (no dwell bonus); a manual "Capture now" is forced and stays exempt (see SamplingSession.contextCap).
            contextCap = { ContextPolicies.of(appliedContext()).capPerMin },
            phoneInUse = { runtime.phoneUse.active(SystemClock.elapsedRealtime()) },
            fixedCapture = { fixedOneShot() }, onFallback = ::onSessionFallback, log = { DiagLog.i(TAG, it) }, workContext = Dispatchers.Default, glance = glance,
        )
        hintSender = MotionHintSender(clock) { h -> runtime.controller?.sendMotionHint(h) == true }
        val verbose = DiagFlags.verboseFeatures(filesDir)
        DiagLog.i(TAG, "diag verbose features=${if (verbose) "on" else "off"}")
        val feed = AndroidSensorFeed(this, verbose, label = { runtime.settingsCache.readNormalized().motionLabel }, csvDir = filesDir).also { it.start(motion, wear) }
        val sm = Smart(session, feed, motion)
        smart = sm
        // Motion leaving STILL must restore FULL at once, not at the next supervisor tick.
        sm.rateJob = scope.launch {
            motion.movementSignals().collect { // the collected state, not state.value: a glance may already be over
                try { applyRate(it) } catch (e: CancellationException) { throw e } catch (e: Exception) { DiagLog.w(TAG, "rate update failed: ${e.javaClass.simpleName}") }
            }
        }
        runtime.smartCapture.value = true
        session.start()
        scheduleNextCapture(failed = false) // the watchdog
        DiagLog.i(TAG, "smart session started")
    }

    /** Releases the camera (through the owner) and the sensors. Idempotent. */
    private fun stopSmart(reason: String) {
        val cur = smart ?: return
        smart = null
        hintSender = null
        cur.rateJob?.cancel()
        cur.session.stop(reason)
        runtime.workRest.onPaused() // leaving counts as a pause: only one of R minutes or more restarts the work clock
        cur.feed.stop()
        runtime.smartCapture.value = false
    }

    private fun onSessionFallback() {
        DiagLog.w(TAG, "capture degraded: three camera binds failed, Fixed ${CaptureCadence.FALLBACK_INTERVAL_SEC} s for this session")
        degradedLatch.trip()
        stopSmart("bind")
        scheduleNextCapture(failed = false)
    }

    /** A kept keyframe from the session (camera thread): buffer it with its metadata, then flush on the main thread. */
    private fun onKeyframe(k: KeyframeOut, luma: Int) {
        val nowMs = System.currentTimeMillis()
        val frameId = newFrameId(nowMs)
        when (runtime.bufferCaptured(frameId, k, nowMs, luma, batteryPercent().takeIf { it in 0..100 })) {
            BufferResult.STORED -> scope.launch { runtime.onFrameBuffered(); runtime.fallback.onCaptureSucceeded() }
            BufferResult.PAUSED -> DiagLog.i(TAG, "keyframe dropped: paused")
            BufferResult.NOT_STORED -> DiagLog.w(TAG, "keyframe not buffered")
        }
    }

    private fun newFrameId(nowMs: Long) = "g${nowMs}_${Random.nextInt(0x1000000).toString(16).padStart(6, '0')}"

    private fun publishStatus(tick: Int) {
        val st = smart?.session?.status?.value
        val why = st?.reason ?: if (degraded) "bind" else if (runtime.settingsCache.capture().captureMode == CaptureMode.SMART) "idle" else "fixed"
        val shown = (st ?: SessionStatus(false, MotionState.STILL, 0, null, why)).copy(cooling = heat.level.value == HeatLevel.STOP, degraded = degraded)
        runtime.captureStatus.value = shown
        // Counts and states only. One line when it changes, and a heartbeat every minute.
        val line = "status running=${shown.running} reason=${shown.reason} motion=${shown.motion} keyframes10m=${shown.keyframesLast10Min} resting=${shown.restingUntilMs != null} heat=${heat.level.value} degraded=$degraded ${smart?.session?.startupStats() ?: ""}"
        // Fix wave K3/K4: the heartbeat (every minute) adds what the retest could not show: ui mode, rate, camera share, battery temp, wear, restart gap.
        if (tick % 60 == 0) {
            val now = SystemClock.elapsedRealtime()
            val settings = runtime.settingsCache.readNormalized()
            val upTotal = runtime.uplink.bytesSent
            val upDelta = (upTotal - lastUpBytes).coerceAtLeast(0)
            val upSpan = if (lastUpAt < 0) 60_000L else now - lastUpAt
            lastUpBytes = upTotal; lastUpAt = now
            val (scrOn, scrOurs, nudgeOff) = runtime.screenWakes.take()
            DiagLog.i(TAG, Heartbeat(
                running = shown.running, reason = shown.reason.toString(), uiMode = runtime.uiMode.get(),
                wake = settings.cameraWake.name, tier = budget.tier.value.name, targetH = settings.batteryTargetHours,
                batt = batteryPercent(), charging = isCharging(), drainPctPerMin = budget.drainPctPerMin, projectedHours = budget.projectedHours,
                stats = smart?.session?.diagSnapshot() ?: DiagStats(0, 0, 0, 0), upBytes = upDelta, upSpanMs = upSpan,
                heat = heat.level.value.toString(), batteryC = heat.lastReading?.batteryTempC,
                wear = runtime.wear.worn.value, known = runtime.wear.known, parked = runtime.wear.parked.value,
                sensors = ((smart?.feed as? AndroidSensorFeed)?.rate ?: SensorRate.FULL).name.lowercase(), phoneUse = runtime.phoneUse.active(now),
                restartGapSec = runtime.restarts.lastGapSec, stillGapMs = smart?.session?.stillGapMs() ?: 0L,
                scrOn = scrOn, scrOurs = scrOurs, nudgeOff = nudgeOff, csvFail = (smart?.feed as? AndroidSensorFeed)?.csvFailures ?: 0,
                ctx = runtime.contextGate.raw()?.name ?: "none", ctxAgeS = runtime.contextGate.ageMs(now)?.div(1000),
                ctxApplied = runtime.contextApplied().name, ctxVeto = runtime.contextGate.takeVetoes(),
            ).format())
        }
        val key = "${shown.running}|${shown.reason}|${shown.restingUntilMs != null}|${heat.level.value}|$degraded"
        if (statusThrottle.shouldLog(key, line, tick, force = tick % 60 == 0)) DiagLog.i(TAG, line)
    }

    /** The raw reading, headroom NaN included: Diagnostics must show that this HAL gives none. */
    private fun logHeat() {
        val r = heat.lastReading ?: return
        DiagLog.i(TAG, "heat level=${heat.level.value} thermal=${r.thermalStatus} headroom=${r.headroom} batteryC=${r.batteryTempC} lastFiniteHeadroom=${heat.lastHeadroom}")
    }

    private fun readHeat(): HeatReading {
        val pm = getSystemService(PowerManager::class.java)
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm?.currentThermalStatus ?: 0 else 0
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { pm?.getThermalHeadroom(30) ?: Float.NaN }.getOrDefault(Float.NaN) else Float.NaN
        val raw = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return HeatReading(status, headroom, if (raw == Int.MIN_VALUE) Float.NaN else raw / 10f)
    }

    // ---- entry points ----

    /**
     * The alarm: in Smart mode only a watchdog (restart a session that is not running, re-arm); otherwise a Fixed capture.
     * False when a Fixed capture is already in flight.
     */
    internal fun onAlarm(): Boolean {
        if (runtime.settingsCache.capture().captureMode == CaptureMode.SMART && !degraded && keepAliveMode) {
            startSupervisor() // restarts a supervisor that died
            if (smart?.session?.alive == false) stopSmart("dead") // a session whose loop died is replaced by reconcile()
            reconcile()
            scheduleNextCapture(failed = false)
            DiagLog.i(TAG, "watchdog: smart session ${if (smart?.session?.alive == true) "alive" else "not running"}")
            return true
        }
        return captureNow(false)
    }

    /**
     * "Capture now" (and every Fixed wake). Smart with a sampling session: forces one keyframe past the change test.
     * Otherwise one Fixed capture in this (already foreground) service; False when one is already in flight (it schedules
     * the next). Outside keep-alive mode the service stops after the capture, as before.
     */
    internal fun captureNow(skipDedupe: Boolean, userRequest: Boolean = false): Boolean {
        smart?.let { if (it.session.captureNow()) { capturesStarted++; return true } }
        if (!inFlight.compareAndSet(false, true)) return false
        capturesStarted++
        scope.launch {
            var failed = false
            try {
                runtime.pauses.ensureLoaded()
                val outcome = runtime.cameraOwner.acquire("fixed") { captureOnce(gateForce(skipDedupe, userRequest)) }
                lastOutcome = outcome
                failed = outcome == Outcome.FAILED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                lastOutcome = Outcome.FAILED
                DiagLog.w(TAG, "capture failed: ${e.javaClass.simpleName}")
            } finally {
                scheduleNextCapture(failed)
                inFlight.set(false)
                if (!keepAliveMode) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // The latest start, so a start that arrived during this capture is not cut off early.
                    stopSelfResult(lastStartId)
                }
            }
        }
        return true
    }

    /** A rest-period capture for the session: one Fixed capture through the owner, without touching the alarm or the service. */
    private suspend fun fixedOneShot() {
        if (!inFlight.compareAndSet(false, true)) return
        try {
            runtime.pauses.ensureLoaded()
            lastOutcome = runtime.cameraOwner.acquire("fixed") { captureOnce(false) }
        } finally {
            inFlight.set(false)
        }
    }

    /** The wearer exited the app: leave keep-alive mode and stop (after a capture in flight, which then stops it). */
    internal fun endKeepAlive() {
        keepAliveMode = false
        if (liveInstance === this) liveInstance = null
        supervisor?.cancel()
        stopSmart("exit")
        runtime.captureStatus.value = SessionStatus(false, MotionState.STILL, 0, null, "exit")
        if (!inFlight.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        DiagLog.i(TAG, "keep-alive stopped")
    }

    internal val captureInFlight: Boolean get() = inFlight.get()

    /** Fixed: the settings interval (30 s when degraded). Smart: only the watchdog. */
    private fun scheduleNextCapture(failed: Boolean) {
        val s = runtime.settingsCache.capture()
        val delayMs = if (s.captureMode == CaptureMode.SMART && !degraded) CaptureCadence.WATCHDOG_MS
        else CaptureCadence.nextDelayMs(if (degraded) CaptureCadence.FALLBACK_INTERVAL_SEC else s.captureIntervalSec, batteryPercent(), isBatterySaverOn(), failed)
        scheduler.scheduleNext(delayMs)
        runtime.onCaptureScheduled()
    }

    // ---- the Fixed one-shot ----

    /** [force]: past the change test and the throttle (a user request or the debug skip); the alarm never forces. */
    private suspend fun captureOnce(force: Boolean): Outcome {
        if (!runtime.pauses.captureAllowed()) { DiagLog.i(TAG, "capture skipped: paused"); return Outcome.SKIPPED }
        if (isBatterySaverOn()) { DiagLog.i(TAG, "capture skipped: battery saver"); return Outcome.SKIPPED }
        if (heat.level.value == HeatLevel.STOP) { DiagLog.i(TAG, "capture skipped: heat"); return Outcome.SKIPPED }
        // Low battery skips before the camera opens; FrameGuard checks again per frame. Unknown battery fails open.
        if (FrameGuard.lowBattery(batteryPercent(), isCharging())) { DiagLog.i(TAG, "capture skipped: ${SkipReason.LOW_BATTERY}"); return Outcome.SKIPPED }
        val provider = CameraXBind.provider(this) ?: return Outcome.FAILED
        val analysis = CameraXBind.analysis()
        val result = CompletableDeferred<Outcome>()
        val analyzed = AtomicInteger(0)
        var lastAnalyzeAtMs = 0L
        analysis.setAnalyzer(cameraExecutor) { image ->
            try {
                if (result.isCompleted) return@setAnalyzer
                // Paused mid-window: stop now so the camera unbinds promptly (spec §5.5).
                if (!runtime.pauses.captureAllowed()) { DiagLog.i(TAG, "capture skipped: paused"); result.complete(Outcome.SKIPPED); return@setAnalyzer }
                analyzed.incrementAndGet()
                val now = System.currentTimeMillis()
                if (now - lastAnalyzeAtMs < ANALYZE_INTERVAL_MS) return@setAnalyzer
                lastAnalyzeAtMs = now
                evaluate(image, now, force)?.let { result.complete(it) }
            } catch (e: Exception) {
                DiagLog.w(TAG, "analyze failed: ${e.javaClass.simpleName}")
                result.complete(Outcome.FAILED)
            } finally {
                image.close()
            }
        }
        val bound = CameraXBind.bind(this, provider, analysis, cameraExecutor)
        if (bound == null) { DiagLog.w(TAG, "camera bind failed"); return Outcome.FAILED }
        return bound.use {
            withTimeoutOrNull(WINDOW_MS) { result.await() } ?: run {
                if (analyzed.get() == 0 || isBackgroundRestricted()) runtime.fallback.maybeLaunch(this)
                Outcome.FAILED
            }
        }
    }

    /** Null = this camera frame is unusable (blurred); the analyzer tries the next one. */
    private fun evaluate(image: ImageProxy, nowMs: Long, force: Boolean): Outcome? {
        val copy = ImageProxyFrame(image).copy() ?: return null
        var sum = 0L
        for (i in copy.thumb.luma.indices step 4) sum += copy.thumb.luma[i].toInt() and 0xFF
        val luma = (sum / ((copy.thumb.luma.size + 3) / 4).coerceAtLeast(1)).toInt()
        val battery = batteryPercent()
        FrameGuard.checkBatteryAndDark(battery, isCharging(), luma.toDouble())?.let { DiagLog.i(TAG, "capture skipped: $it"); return Outcome.SKIPPED }
        // One frame, a still scene as far as the gate goes; the user's "Capture now" and the debug skip force it past the change test.
        val keep = when (val r = gate.offer(Candidate(++fixedSeq, copy.thumb, copy.pixels), MotionState.STILL, dwellArmed = false, force = force)) {
            is GateResult.Keep -> r
            is GateResult.Skip -> { if (r.reason == KeyframeGate.BLUR) return null; DiagLog.i(TAG, "capture skipped: ${r.reason}"); return Outcome.SKIPPED }
        }
        val enc = AndroidKeyframeEncoder.encode(keep.candidate.pixels) ?: return null
        val out = KeyframeOut(
            enc.jpeg, enc.width, MotionState.UNKNOWN, dwell = false, blur = keep.blur,
            dhash = hex(keep.dhash), phash = hex(keep.phash), sampleSeq = keep.candidate.seq, sessionId = FIXED_SESSION,
        )
        val frameId = newFrameId(nowMs)
        when (runtime.bufferCaptured(frameId, out, nowMs, luma, battery.takeIf { it in 0..100 })) {
            BufferResult.STORED -> Unit
            BufferResult.PAUSED -> { DiagLog.i(TAG, "capture skipped: paused"); return Outcome.SKIPPED }
            // OutboundBuffer logged the exception class; the capture fails and retries in 15 s (CaptureCadence.RETRY_MS).
            BufferResult.NOT_STORED -> { DiagLog.w(TAG, "capture failed: frame $frameId not buffered"); return Outcome.FAILED }
        }
        DiagLog.i(TAG, "buffered $frameId (${out.jpeg.size / 1024} KB, w=${out.width}) buffer=${runtime.buffer.size}")
        runtime.onFrameBuffered()
        runtime.fallback.onCaptureSucceeded()
        return Outcome.BUFFERED
    }

    private fun hex(v: Long) = v.toULong().toString(16).padStart(16, '0')

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.presence_video_online)
        .setContentTitle("Proactive Context")
        .setContentText("Capturing")
        .setOngoing(true)
        .build()

    private fun isBackgroundRestricted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return activityManager?.isBackgroundRestricted == true
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    private fun isBatterySaverOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager?.isPowerSaveMode == true
    }

    private fun isCharging(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    companion object {
        const val TAG = "PcCapture"
        const val ACTION_SCHEDULED = "com.rokid.context.glasses.action.CAPTURE_SCHEDULED"
        const val ACTION_NOW = "com.rokid.context.glasses.action.CAPTURE_SERVICE_NOW"
        const val ACTION_KEEP_ALIVE = "com.rokid.context.glasses.action.CAPTURE_KEEP_ALIVE"
        /** Debug builds only: ignore dedupe and throttle so adb checks can capture a static scene. */
        const val EXTRA_SKIP_DEDUPE = "skip_dedupe"
        private const val DEGRADED_RETRY_MS = 5 * 60_000L
        private const val CHANNEL_ID = "pc_capture"
        private const val NOTIFICATION_ID = 30_102
        private const val PREFS = "capture_guard"
        private const val FIXED_SESSION = "fixed"
        private const val SUPERVISE_MS = 1_000L
        private const val WINDOW_MS = 8_000L
        private const val ANALYZE_INTERVAL_MS = 1_000L

        /** The running keep-alive service in this process, if any; alarms and capture-now go to it in-process. */
        @Volatile internal var liveInstance: CaptureService? = null

        /** Call only while the HUD activity is visible: a foreground start from the background loses the camera. */
        fun startKeepAlive(context: Context) {
            if (liveInstance != null) return
            runCatching { context.startForegroundService(Intent(context, CaptureService::class.java).setAction(ACTION_KEEP_ALIVE)) }
                .onFailure { DiagLog.w(TAG, "keep-alive start refused: ${it.javaClass.simpleName}") }
        }

        fun stopKeepAlive(context: Context) {
            liveInstance?.endKeepAlive()
        }

        fun captureNow(context: Context, skipDedupe: Boolean = false) {
            liveInstance?.let { if (!it.captureNow(BuildConfig.DEBUG && skipDedupe, userRequest = true)) DiagLog.i(TAG, "capture already in flight"); return }
            context.startForegroundService(Intent(context, CaptureService::class.java).setAction(ACTION_NOW).putExtra(EXTRA_SKIP_DEDUPE, skipDedupe))
        }
    }
}

/** What forces a Fixed capture past the gate: the debug-only skip or the user's own "Capture now". Alarms and rest captures never do. */
internal fun gateForce(skipDedupe: Boolean, userRequest: Boolean): Boolean = skipDedupe || userRequest

/** The Smart-to-Fixed fallback after failed binds, and when the watchdog may try Smart again ([retryAfterMs] later). */
internal class DegradedLatch(private val clock: () -> Long, private val retryAfterMs: Long) {
    @Volatile private var at: Long? = null
    val tripped: Boolean get() = at != null
    fun trip() { at = clock() }
    fun reset() { at = null }
    /** True once, [retryAfterMs] after the trip: the latch is cleared. */
    fun retryDue(): Boolean {
        val t = at ?: return false
        if (clock() - t < retryAfterMs) return false
        at = null
        return true
    }
}
