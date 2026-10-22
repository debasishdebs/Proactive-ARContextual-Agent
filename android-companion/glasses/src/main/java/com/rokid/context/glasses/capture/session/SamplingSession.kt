package com.rokid.context.glasses.capture.session

import com.rokid.context.capture.plan.reservedBoundMs
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.FrameGuard
import com.rokid.context.glasses.capture.KeyframeOut
import com.rokid.context.glasses.capture.SessionStatus
import com.rokid.context.glasses.capture.keyframe.Candidate
import com.rokid.context.glasses.capture.keyframe.GateResult
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
import com.rokid.context.glasses.capture.keyframe.Nv21Frame
import com.rokid.context.glasses.capture.keyframe.Thumb
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.motion.WearDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/** One camera frame as the analyzer sees it. [copy] is valid only inside the callback (the ImageProxy closes after it). */
interface FrameSource { fun copy(): FrameCopy? }

/** The 160 px grey thumbnail (hashes, blur) and the 640x480 NV21 pixels kept in case this sample wins its window. */
class FrameCopy(val thumb: Thumb, val pixels: Nv21Frame)

/** The camera seam (CameraX in production). [open] binds and starts frames; the first frame comes about 300-535 ms later. */
interface SamplingCamera {
    /** False (or a throw) = the bind failed. Frames arrive on the camera's own thread. */
    suspend fun open(onFrame: (FrameSource) -> Unit): Boolean
    /** True when the last [open] failed because another app holds the camera (in use / too many cameras): not a bind fault. */
    val busy: Boolean get() = false
    /** Unbinds. Safe to call twice, from any thread. */
    fun close()
}

/** [width] x [height] is the final size after rotation (long side at most 640, never upscaled). */
class EncodedKeyframe(val jpeg: ByteArray, val width: Int, val height: Int)

fun interface KeyframeEncoder { fun encode(frame: Nv21Frame): EncodedKeyframe? }

/**
 * The bound sampling session (spec P1.1, "Motion-driven camera"). A 250 ms tick plus every motion change evaluates
 * [CameraPolicy.decide] and opens or closes the camera through [owner]: open while moving and for H after, opening early on
 * deceleration; closed when settled, with a brief check (open, first usable frame, close) every 5 s (15 s after 10 quiet
 * minutes). Each analysed frame is copied into a [Candidate] before the camera's buffer is released; [KeyframeGate] picks
 * the keepers and the WINNING candidate's pixels are encoded (never the current frame). Rest and mode changes release the
 * camera first; rest keeps glance capture at [GlanceParams.restCapPerMin] a minute. Heat polling is the owner's job ([HeatPoller]).
 *
 * Threading: the loop and policy run on [scope]; frames arrive on the camera thread. All state is under [lock].
 * [clock] is SystemClock.elapsedRealtime (the same clock as the classifiers, heat and work/rest).
 */
class SamplingSession(
    private val scope: CoroutineScope,
    private val camera: SamplingCamera,
    private val owner: CameraOwner,
    private val motion: MotionClassifier,
    /** Gates on the real `pc_wear` state only (unknown = worn); the Android proximity sensor never reaches it. */
    private val wear: WearDetector,
    private val gate: KeyframeGate,
    private val heat: HeatGuard,
    private val workRest: WorkRest,
    private val encoder: KeyframeEncoder,
    /** A kept keyframe and its mean luma (0..255), for [com.rokid.context.glasses.GlassesRuntime.bufferCaptured]. */
    private val sink: (KeyframeOut, Int) -> Unit,
    private val clock: () -> Long,
    private val settings: () -> HudSettings,
    private val capturePaused: () -> Boolean = { false },
    private val batteryPct: () -> Int = { -1 },
    private val charging: () -> Boolean = { false },
    private val batterySaver: () -> Boolean = { false },
    private val profile: () -> CaptureProfile = { CaptureProfile.DEFAULT },
    private val phoneInUse: () -> Boolean = { false },
    /** Unused since wave A3 rest (rest now keeps glance capture); kept for call-site compatibility. */
    private val fixedCapture: suspend () -> Unit = {},
    /** Three failed binds: the owner runs Fixed 30 s for the rest of this session. */
    private val onFallback: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val newSessionId: () -> String = { Random.nextLong().toULong().toString(16).padStart(16, '0') },
    /** Where a flushed keyframe is encoded (the service passes Dispatchers.Default so the main thread never encodes). */
    private val workContext: CoroutineContext = EmptyCoroutineContext,
    /** Glance novelty window, turn thresholds and keep caps (one instance shared with the [MotionClassifier]). */
    private val glance: GlanceParams = GlanceParams(),
    /** The glance tunables in force now (slice 3a: a plan swaps them at a safe boundary); defaults to the fixed [glance]. */
    private val glanceNow: () -> GlanceParams = { glance },
    /**
     * Wave B context cap (keyframes per minute) from the motion hub, or null for none. When non-null it is a hard cap
     * (no dwell bonus: [hardNow]) and [capNow] is the smaller of it and the A3 cap. Forced keeps (a manual "Capture now")
     * stay exempt: [KeyframeGate.offer] never applies the cap to them.
     */
    private val contextCap: () -> Int? = { null },
    /** The context state applied to capture now: it picks the bound of the reserved timer photo attempt (S83). */
    private val appliedState: () -> ContextState = { ContextState.UNKNOWN },
    /** Called (under [lock], keep it cheap) each time a glance or a frame is refused by the hard ceiling (2 x the cap): the auto-revert counts them. */
    private val onCeilingHit: (Long) -> Unit = {},
) {
    private class Episode(val continuous0: Boolean, val why: String) {
        @Volatile var continuous = continuous0
        val done = CompletableDeferred<Unit>()
        @Volatile var closing = false
        @Volatile var openedAt = 0L
        @Volatile var firstFrameAt = -1L
        @Volatile var lastAnalysedAt = 0L
        @Volatile var analysed = 0
        @Volatile var openFailed = false
        @Volatile var noFrames = false
        @Volatile var frames = 0
        @Volatile var lastFrameAt = 0L
        @Volatile var lastUnusable = false
    }

    private val lock = Any()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow(SessionStatus(false, MotionState.STILL, 0, null, "idle"))
    val status: StateFlow<SessionStatus> = _status

    private var job: Job? = null
    private var fixedJob: Job? = null
    private var episode: Episode? = null
    private var stopped = false
    private var started = false
    private var degraded = false
    private var sessionId = ""
    private var blocked: String? = "idle"

    private var wasMoving = false
    private var motionEndAt: Long? = null
    private var lastKeyframeAt = 0L
    private var lastBriefAt = 0L
    private var analysisIntervalMs = ANALYSIS_MS
    private var bindFailures = 0
    private var bindBackoffUntil = 0L
    /** The first failed bind after start (the previous process may not have released the camera yet) is retried at once and not counted. */
    private var coldStartGrace = true
    // Still-scene backoff of the brief checks (guarded by [lock]): the step doubles the gap while consecutive timer checks find the same scene.
    private var stillStepV = 0
    private var lastCheckHash: Pair<Long, Long>? = null
    private var lastProfile = CaptureProfile.DEFAULT
    private var lastLowPower = false
    private var wasBlocked = true
    private var seq = 0L
    private var forceAnalyse = false
    private var forceBrief = false
    private var pendingDwellAt: Long? = null
    private var pendingNowAt: Long? = null
    private val keptAt = ArrayDeque<Long>()
    // Glance counters since the last [diagSnapshot] (guarded by [lock]): opens skipped as not novel / over the cap.
    private var novelSkipAccum = 0
    private var cappedAccum = 0
    /** In a rest span the cap is [GlanceParams.restCapPerMin] (set by [tick]). Guarded by [lock]. */
    private var resting = false
    /** [cc] is the context cap read once per decision, so the cap and the hard flag always agree. */
    private fun capNow(cc: Int?): Int {
        val g = glanceNow()
        val a3 = if (resting) g.restCapPerMin else g.capPerMin
        return minOf(a3, cc ?: Int.MAX_VALUE)
    }
    /** Hard cap (no dwell bonus) while resting or while a context cap is active. */
    private fun hardNow(cc: Int?) = resting || cc != null
    // Camera-open time since the last [diagSnapshot] read (guarded by [lock]), for the "camera-open share" heartbeat field.
    private var openAccumMs = 0L
    private var shareFromAt = 0L
    private var analysedAtSnapshot = 0
    private var opensAccum = 0
    private val wakeAccum = linkedMapOf("timer" to 0, "dwell" to 0, "onset" to 0, "now" to 0, "stream" to 0, "resv" to 0)
    /**
     * S83 reserved timer photo attempt: the start of the last episode that delivered a first frame (a continuous one counts until it ends).
     * A failed open does not move it. While eligible, [tick] starts a brief `resv` episode once [reservedBoundMs] has passed since it.
     */
    private var lastPhotoAttemptAtMs = 0L

    /** Samples whose copy took longer than [COPY_BUDGET_MS] (the thumbnail target is 30 ms). */
    private val slowCount = AtomicInteger()
    val slowCopies: Int get() = slowCount.get()
    private val openedCount = AtomicInteger()
    val camerasOpened: Int get() = openedCount.get()
    /** Frames copied and offered to the gate (for logs and tests). */
    private val analysedCount = AtomicInteger()
    val analysedFrames: Int get() = analysedCount.get()

    /** A camera episode (brief check or open camera) is running or about to: not a safe moment to swap the plan. */
    fun episodeOpen(): Boolean = synchronized(lock) { episode != null }

    val stillStep: Int get() = synchronized(lock) { stillStepV }

    /** The current gap between brief checks of a still scene (after the backoff), for the heartbeat. */
    fun stillGapMs(): Long = synchronized(lock) { CameraPolicy.stillGap(backoffBase(), stillStepV, lastProfile.stillCapMs) }

    /** The gap the policy starts from before doubling. Caller holds [lock]. */
    private fun backoffBase(): Long {
        val low = if (lastLowPower) 10_000L else 0L
        return if (lastProfile.wake == CameraWake.SETTLE) maxOf(low, lastProfile.settledCheckMs)
        else if (lastLowPower) low else lastProfile.settledCheckMs
    }

    /** True while the session loop runs (the watchdog reports "alive" only then). */
    val alive: Boolean get() = job?.isActive == true && !stopped

    // Measured camera start-up (open -> first frame), the lead of a brief check. Guarded by [lock].
    private val startups = ArrayDeque<Long>()
    private var darkStreak = 0
    private var busyCount = 0

    fun start() {
        synchronized(lock) {
            if (job != null || stopped) return
            sessionId = newSessionId()
            val now = clock()
            lastKeyframeAt = now
            lastPhotoAttemptAtMs = now
            motion.markPose()
            shareFromAt = now // the first heartbeat snapshot spans the session, not the whole clock
            lastBriefAt = now - DUE_NOW_MS // the first brief check is due at once, at any cadence
        }
        job = scope.launch {
            launch { motion.state.collect { kick.trySend(Unit) } }
            launch { motion.decelerating.collect { kick.trySend(Unit) } }
            launch { motion.onset.collect { onOnset() } }
            launch { motion.dwell.collect { onDwell() } }
            while (isActive) {
                val wait = try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("tick failed: ${e.javaClass.simpleName}"); TICK_MS }
                withTimeoutOrNull(wait) { kick.receive() }
            }
        }
    }

    /** Ends the session: the camera is released (through [owner]) and nothing is sampled until a new session starts. */
    fun stop(reason: String) {
        val ep: Episode?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            blocked = reason
            pendingDwellAt = null; pendingNowAt = null; forceBrief = false
            ep = episode
        }
        closeEpisode(ep)
        fixedJob?.cancel()
        job?.cancel()
        publish(clock(), reason)
        log("session stopped: $reason")
    }

    /** "Capture now": the next usable frame becomes a keyframe, bypassing the change test. False when not sampling. */
    fun captureNow(): Boolean = synchronized(lock) {
        if (stopped || !started || blocked != null) return false
        pendingNowAt = clock()
        kick.trySend(Unit)
        true
    }

    private fun onOnset() = synchronized(lock) {
        // Micro-motion flips STILL -> HEAD_MOVING every few seconds: in SETTLE it must not defeat the still-scene backoff (a real look resets it in onDwell).
        if (profile().wake == CameraWake.MOTION) stillStepV = 0
        if (!resting && profile().wake == CameraWake.MOTION) { // never an immediate open in rest
            forceAnalyse = true // the immediate check at the first sign of movement
            forceBrief = true
        }
        kick.trySend(Unit)
    }

    private fun onDwell() = synchronized(lock) {
        val now = clock()
        stillStepV = 0
        // Spec A3 section 4: a glance opens the camera only when it can matter (free IMU tests, before any open).
        val k = keptAt.count { now - it < 60_000L }
        val turned = motion.rotationSinceMarkRad()
        val walked = motion.walkedSinceMark()
        val cc = contextCap()
        val cap = capNow(cc)
        when (GlanceAdmission.decide(now - lastKeyframeAt, k, cap, hardNow(cc), turned, walked, glanceNow())) {
            GlanceAdmission.Decision.NOVEL_SKIP -> novelSkipAccum++
            GlanceAdmission.Decision.CAPPED -> { cappedAccum++; if (k.toLong() >= 2L * cap) onCeilingHit(now) }
            GlanceAdmission.Decision.OPEN -> {
                pendingDwellAt = now
                kick.trySend(Unit)
            }
        }
        Unit
    }

    /** One policy evaluation. Returns how long the loop may sleep (a brief check can be due before the next 250 ms). */
    private fun tick(): Long {
        val now = clock()
        wear.tick()
        val s = settings()
        val base = profile()
        var prof = base
        var wait = TICK_MS
        var ep: Episode?
        var why: String?
        synchronized(lock) {
            why = blockReason(s, base)
            if (why == null) workRest.onSampling() else workRest.onPaused()
            val nowResting = why == null && workRest.resting.value != null
            if (nowResting != resting) stillStepV = 0 // the profile changes
            resting = nowResting
            if (resting) prof = base.forRest()
            blocked = why
            if (wasBlocked && why == null) stillStepV = 0
            wasBlocked = why != null
            lastProfile = prof
            lastLowPower = lowPower()
            ep = episode
            if (why != null) { pendingDwellAt = null; pendingNowAt = null; forceBrief = false }
        }
        if (why != null) {
            closeEpisode(ep)
            publish(now, why)
            return wait
        }
        val d: CameraPolicy.Decision
        synchronized(lock) {
            val st = motion.state.value
            val moving = st == MotionState.HEAD_MOVING || st == MotionState.WALKING
            if (moving) motionEndAt = null else if (wasMoving) motionEndAt = now
            wasMoving = moving
            pendingDwellAt?.let { if (now - it > PENDING_MS) pendingDwellAt = null }
            pendingNowAt?.let { if (now - it > PENDING_MS) pendingNowAt = null }
            d = CameraPolicy.decide(
                st, motion.decelerating.value, motionEndAt?.let { now - it }, now - lastKeyframeAt, s.quickLookMs,
                lowPower(), heat.level.value == HeatLevel.SLOW, now, lastBriefAt, quiet = darkStreak >= DARK_BACKOFF_AFTER, profile = prof, stillStep = stillStepV,
            )
            analysisIntervalMs = d.analysisIntervalMs
            val cur = episode
            val canBind = now >= bindBackoffUntil
            if (d.open) {
                forceBrief = false // an open camera is the immediate check
                if (cur == null) { if (canBind) startEpisode(continuous = true, now) }
                else if (!cur.continuous) cur.continuous = true // a brief check in flight becomes the open camera
            } else {
                if (cur != null && cur.continuous) closeEpisode(cur)
                else if (cur == null && canBind) {
                    val dueAt = d.nextBriefCheckAtMs
                    val forced = forceBrief || pendingDwellAt != null || pendingNowAt != null
                    val lead = briefLeadMs()
                    val timerDue = dueAt != null && now + lead >= dueAt
                    // A timer check whose frame the cap would drop anyway does not power up the camera (battery wave A3, task 9).
                    // It opens at the END of the hold, not a start-up lead early: the frame must land after the oldest keep has aged out.
                    val hold = if (!forced && timerDue) gate.timerHoldUntil(capNow(contextCap()), now)?.takeIf { it > now } else null
                    // S83: the reserved timer photo attempt. No gap, hold, guard or novelty rule delays it past the bound; it is the cause only when
                    // the ordinary timer would not have opened now (a timer that is due, and not held, keeps its own cause). Only the gate's dedupe,
                    // blur and the hard ceiling still apply to its frame. Camera busy / bind back-off (canBind) and every block above still hold.
                    val resv = !forced && now - lastPhotoAttemptAtMs >= reservedBoundMs(appliedState(), resting) && (!timerDue || hold != null)
                    if (hold != null && !resv) wait = (hold - now).coerceIn(HOLD_MIN_WAIT_MS, TICK_MS) // never the 1 ms floor while a hold is live
                    else if (forced || timerDue || resv) startEpisode(continuous = false, now, resv = resv)
                    else if (dueAt != null) wait = (dueAt - lead - now).coerceIn(1L, TICK_MS)
                }
            }
            episode?.let { e ->
                if (e.openedAt > 0 && e.firstFrameAt < 0 && now - e.openedAt > NO_FRAME_MS) { e.noFrames = true; closeEpisode(e) }
                if (!e.continuous && e.firstFrameAt >= 0 && now - e.firstFrameAt > BRIEF_GIVE_UP_MS) closeEpisode(e)
                if (!e.closing && e.firstFrameAt >= 0 && now - e.lastFrameAt > STALL_MS) { e.noFrames = true; log("camera frames stalled"); closeEpisode(e) }
            }
        }
        publish(now, null)
        return wait
    }

    /** The first reason sampling may not run now, in the order the HUD cares about; null = go. Caller holds [lock]. */
    private fun blockReason(s: HudSettings, prof: CaptureProfile): String? {
        if (stopped) return "stopped"
        if (s.captureMode != CaptureMode.SMART) return "mode"
        started = true
        if (capturePaused()) return "paused"
        if (s.pauseWhenNotWorn && !wear.worn.value) return "not_worn" // only a real pc_wear=false makes it false
        if (s.pauseWhenNotWorn && wear.parked.value) return "parked"
        if (s.pauseWhenPhoneInUse && phoneInUse()) return "phone_in_use"
        if (heat.level.value == HeatLevel.STOP) return "heat"
        if (FrameGuard.lowBattery(batteryPct(), charging())) return "battery"
        if (prof.paused) return "battery_budget"
        return null
    }

    private fun lowPower() = batterySaver() || batteryPct() in 0..LOW_BATTERY_PCT

    private fun publish(now: Long, why: String?) {
        val running = why == null && started && !stopped
        val until = workRest.resting.value
        val count = synchronized(lock) {
            while (keptAt.isNotEmpty() && now - keptAt.first() > TEN_MIN_MS) keptAt.removeFirst()
            keptAt.size
        }
        _status.value = SessionStatus(running, motion.state.value, count, until, why, cooling = heat.level.value == HeatLevel.STOP, degraded = degraded)
    }

    // ---- camera episodes ----

    /** Caller holds [lock]. */
    private fun startEpisode(continuous: Boolean, now: Long, resv: Boolean = false) {
        val why = when {
            continuous -> "stream"
            pendingNowAt != null -> "now"
            pendingDwellAt != null -> "dwell"
            forceBrief -> "onset"
            resv -> "resv"
            else -> "timer"
        }
        val ep = Episode(continuous, why)
        episode = ep
        forceBrief = false
        scope.launch {
            try {
                owner.acquire(OWNER) {
                    if (ep.closing) return@acquire
                    ep.openedAt = clock()
                    openedCount.incrementAndGet()
                    synchronized(lock) { opensAccum++; wakeAccum[ep.why] = (wakeAccum[ep.why] ?: 0) + 1 }
                    val ok = try {
                        withTimeoutOrNull(OPEN_TIMEOUT_MS) { camera.open { src -> onFrame(ep, src) } }
                            ?: false.also { log("camera open timed out") }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("camera open failed: ${e.javaClass.simpleName}")
                        false
                    }
                    if (!ok) { ep.openFailed = true; runCatching { camera.close() }; return@acquire }
                    try { ep.done.await() } finally { camera.close() }
                }
            } finally {
                endEpisode(ep)
            }
        }
    }

    private fun closeEpisode(ep: Episode?) {
        if (ep == null) return
        ep.closing = true
        ep.done.complete(Unit)
    }

    private fun endEpisode(ep: Episode) {
        val now = clock()
        var flushed: GateResult? = null
        synchronized(lock) {
            if (episode === ep) episode = null
            if (ep.continuous) lastCheckHash = null
            if (ep.continuous && ep.firstFrameAt >= 0) lastPhotoAttemptAtMs = maxOf(lastPhotoAttemptAtMs, now) // an open camera satisfies the bound while it is open
            if (ep.openedAt > 0) openAccumMs += (now - maxOf(ep.openedAt, shareFromAt)).coerceAtLeast(0)
            lastBriefAt = now
            if (ep.openFailed || ep.noFrames) {
                lastBriefAt = now - DUE_NOW_MS // retry after the back-off, not on the 5 s beat
                if (coldStartGrace) {
                    coldStartGrace = false
                    bindBackoffUntil = now + COLD_START_RETRY_MS
                    log("camera not ready on the first bind: retrying in $COLD_START_RETRY_MS ms (not counted)")
                } else if (camera.busy) onBusy(now) else onBindFailed(now)
            }
            if (!stopped) { val cc = contextCap(); flushed = gate.flushWindow(capNow(cc), hardNow(cc)) }
        }
        (flushed as? GateResult.Keep)?.let { k ->
            scope.launch(workContext) { try { emit(k, now) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("emit failed: ${e.javaClass.simpleName}") } }
        }
        kick.trySend(Unit)
    }

    /** Another app holds the camera: wait and retry, never a bind failure (it must not latch the Fixed fallback). Caller holds [lock]. */
    private fun onBusy(now: Long) {
        busyCount++
        bindBackoffUntil = now + BUSY_BACKOFF_MS
        log("camera busy ($busyCount): retrying in ${BUSY_BACKOFF_MS / 1000} s")
    }

    /** The lead of a brief check: the rolling p95 of the measured start-up, clamped; 535 ms until enough samples. Caller holds [lock]. */
    private fun briefLeadMs(): Long {
        if (startups.size < MIN_LEAD_SAMPLES) return BRIEF_LEAD_MS
        return percentile(0.95).coerceIn(MIN_LEAD_MS, MAX_LEAD_MS)
    }

    private fun percentile(q: Double): Long {
        val sorted = startups.sorted()
        return sorted[(Math.ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    }

    /** Keyframes in the last minute, camera-open ms, the span and the analysed-frame delta since the previous snapshot; resets the accumulators. */
    fun diagSnapshot(): DiagStats = synchronized(lock) {
        val now = clock()
        val kf = keptAt.count { now - it <= 60_000L }
        var open = openAccumMs
        episode?.let { if (it.openedAt > 0) open += (now - maxOf(it.openedAt, shareFromAt)).coerceAtLeast(0) }
        val span = (now - shareFromAt).coerceAtLeast(1)
        val analysed = analysedCount.get()
        val delta = analysed - analysedAtSnapshot
        analysedAtSnapshot = analysed
        openAccumMs = 0; shareFromAt = now
        val opens = opensAccum; val wakes = LinkedHashMap(wakeAccum)
        opensAccum = 0; wakeAccum.keys.forEach { wakeAccum[it] = 0 }
        val gl = motion.takeGlanceCounts()
        val (same, gateCapped) = gate.takeSkipCounts()
        val novel = novelSkipAccum; val capped = cappedAccum + gateCapped
        novelSkipAccum = 0; cappedAccum = 0
        DiagStats(kf, open, span, delta, opens, wakes, gl.glances, gl.micro, novel, capped, same)
    }

    /** "startup n=.. p50=.. p95=.. lead=.. ms" for the status heartbeat. */
    fun startupStats(): String = synchronized(lock) {
        if (startups.isEmpty()) "startup n=0 lead=$BRIEF_LEAD_MS ms"
        else "startup n=${startups.size} p50=${percentile(0.5)} p95=${percentile(0.95)} lead=${briefLeadMs()} ms busy=$busyCount"
    }

    /** Caller holds [lock]. */
    private fun onBindFailed(now: Long) {
        bindFailures++
        bindBackoffUntil = now + BIND_BACKOFF_MS
        log("camera bind failed ($bindFailures of $MAX_BIND_FAILURES)")
        if (bindFailures >= MAX_BIND_FAILURES && !stopped) {
            degraded = true
            stopped = true
            blocked = "bind"
            job?.cancel()
            fixedJob?.cancel()
            _status.value = SessionStatus(false, motion.state.value, 0, workRest.resting.value, "bind", cooling = false, degraded = true)
            scope.launch { onFallback() }
        }
    }

    // ---- frames (camera thread) ----

    private fun onFrame(ep: Episode, src: FrameSource) {
        val now = clock()
        var dwellArmed: Boolean
        var forced: Boolean
        val sequence: Long
        val sampleMotion: MotionState
        var h: Pair<Long, Long>? = null
        synchronized(lock) {
            if (ep !== episode || ep.closing || stopped) return
            ep.frames++
            ep.lastFrameAt = now
            if (ep.firstFrameAt < 0) {
                ep.firstFrameAt = now; bindFailures = 0; coldStartGrace = false
                lastPhotoAttemptAtMs = maxOf(lastPhotoAttemptAtMs, if (ep.openedAt > 0) ep.openedAt else now)
                if (ep.openedAt > 0) { startups.addLast(now - ep.openedAt); while (startups.size > STARTUP_WINDOW) startups.removeFirst() }
            }
            val must = forceAnalyse || pendingDwellAt != null || pendingNowAt != null
            // The first frame, an onset, a dwell and "Capture now" are analysed at once (at most every FORCED_GAP_MS);
            // so is the frame after an unusable one (exposure still settling).
            val gap = now - ep.lastAnalysedAt
            if (ep.analysed > 0 && gap < (if (must || ep.lastUnusable) FORCED_GAP_MS else analysisIntervalMs)) return
            ep.lastAnalysedAt = now
            ep.analysed++
            forceAnalyse = false
            dwellArmed = pendingDwellAt != null
            forced = pendingNowAt != null
            sequence = ++seq
            sampleMotion = motion.state.value // K2: the label is the state at SAMPLE time, not when the winner is encoded
        }
        val copy = src.copy()
        if (copy == null) { synchronized(lock) { if (ep === episode) unusable(ep, now) }; return }
        analysedCount.incrementAndGet()
        if (clock() - now > COPY_BUDGET_MS) slowCount.incrementAndGet()
        val luma = meanLuma(copy.thumb)
        val cand = Candidate(sequence, copy.thumb, copy.pixels)
        // A dark frame is unusable like a blurred one: the camera stays open for the next.
        val result: GateResult? = if (FrameGuard.checkBatteryAndDark(batteryPct(), charging(), luma.toDouble()) != null) null
        else synchronized(lock) {
            if (ep !== episode || ep.closing || stopped) return
            val cc = contextCap()
            gate.offer(cand, sampleMotion, dwellArmed, forced, capNow(cc), hardNow(cc)).also {
                h = gate.lastHashes()
                if (!forced && it is GateResult.Skip && it.reason == KeyframeGate.CAPPED && keptAt.count { k -> now - k < 60_000L }.toLong() >= 2L * capNow(cc)) onCeilingHit(now)
            }
        }
        val usable = result != null && !(result is GateResult.Skip && result.reason == KeyframeGate.BLUR)
        if (result is GateResult.Keep) emit(result, now)
        synchronized(lock) {
            if (usable) {
                if (!ep.continuous) updateBackoff(ep, h, result, sampleMotion)
                ep.lastUnusable = false
                if (dwellArmed) pendingDwellAt = null
                if (forced) pendingNowAt = null
                if (!ep.continuous) { darkStreak = 0; closeEpisode(ep) } // a brief check ends at its first usable frame
            } else unusable(ep, now)
        }
    }

    /**
     * Still-scene backoff after a usable brief-check frame: a keep or a changed scene resets it; an unchanged scene on a
     * timer check while STILL doubles the next gap (until the cap). Caller holds [lock].
     */
    private fun updateBackoff(ep: Episode, h: Pair<Long, Long>?, result: GateResult?, sampleMotion: MotionState) {
        val prev = lastCheckHash
        if (result is GateResult.Skip && result.reason == KeyframeGate.CAPPED) { /* a capped frame is no evidence either way */ }
        else if (result is GateResult.Keep) stillStepV = 0
        else if ((ep.why == "timer" || ep.why == "resv") && sampleMotion == MotionState.STILL && h != null && prev != null) {
            val same = minOf(java.lang.Long.bitCount(h.first xor prev.first), java.lang.Long.bitCount(h.second xor prev.second)) <= gate.hammingNow()
            if (!same) stillStepV = 0
            else if ((backoffBase() shl stillStepV) < lastProfile.stillCapMs) stillStepV++
        }
        if (h != null) lastCheckHash = h
    }

    /**
     * A frame that cannot be judged (dark, blurred, no pixels). A brief check does not hold the camera open for a better
     * one: once the exposure has had a moment to settle (a few frames or SETTLE_MS) it ends, usable or not, and a run of
     * such checks backs the cadence off. Caller holds [lock].
     */
    private fun unusable(ep: Episode, now: Long) {
        ep.lastUnusable = true
        if (!ep.continuous && !ep.closing && (ep.frames >= SETTLE_FRAMES || now - ep.firstFrameAt >= SETTLE_MS)) {
            darkStreak++
            closeEpisode(ep)
        }
    }

    /** Encodes the WINNING candidate's pixels and hands the keyframe on. */
    private fun emit(keep: GateResult.Keep, now: Long) {
        val enc = encoder.encode(keep.candidate.pixels)
        if (enc == null) { log("encode failed for sample ${keep.candidate.seq}"); return }
        val out = KeyframeOut(
            enc.jpeg, enc.width, keep.motion, keep.dwell, keep.blur,
            hex(keep.dhash), hex(keep.phash), keep.candidate.seq, sessionId,
        )
        synchronized(lock) { lastKeyframeAt = now; motion.markPose(); keptAt.addLast(now) }
        val px = keep.candidate.pixels
        val srcW = YuvCopy.rotatedWidth(px.w, px.h, px.rotation); val srcH = YuvCopy.rotatedHeight(px.w, px.h, px.rotation)
        log("keyframe seq=${out.sampleSeq} src=${srcW}x$srcH out=${enc.width}x${enc.height} motion=${out.motion} dwell=${out.dwell} blur=${out.blur.toInt()} bytes=${out.jpeg.size}")
        sink(out, meanLuma(keep.candidate.thumb))
    }

    private fun meanLuma(t: Thumb): Int {
        var sum = 0L
        var n = 0
        var i = 0
        while (i < t.luma.size) { sum += t.luma[i].toInt() and 0xFF; n++; i += 4 }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    private fun hex(v: Long) = v.toULong().toString(16).padStart(16, '0')

    companion object {
        const val OWNER = "session"
        const val TICK_MS = 250L
        private const val HOLD_MIN_WAIT_MS = 50L
        const val ANALYSIS_MS = 333L
        /** Older than any brief-check cadence (5 s, 15 s, x2 for heat): a check anchored here is due now. */
        const val DUE_NOW_MS = 40_000L
        /** Cold camera start-up p95 (probe). A brief check opens this early so its first frame lands on the 5 s / 15 s beat. */
        const val BRIEF_LEAD_MS = 535L
        const val BRIEF_GIVE_UP_MS = 1_500L
        /** A brief check ends on a frame it cannot use after this many frames or ms of exposure settling. */
        const val SETTLE_FRAMES = 3
        const val SETTLE_MS = 200L
        /** Brief checks in a row that found nothing usable before the cadence drops to the quiet 15 s. */
        const val DARK_BACKOFF_AFTER = 3
        const val STALL_MS = 3_000L
        const val OPEN_TIMEOUT_MS = 3_000L
        const val BUSY_BACKOFF_MS = 30_000L
        const val STARTUP_WINDOW = 20
        const val MIN_LEAD_SAMPLES = 3
        const val MIN_LEAD_MS = 250L
        const val MAX_LEAD_MS = 1_500L
        const val NO_FRAME_MS = 3_000L
        const val PENDING_MS = 3_000L
        const val TEN_MIN_MS = 10 * 60_000L
        const val LOW_BATTERY_PCT = 20
        const val MAX_BIND_FAILURES = 3
        const val BIND_BACKOFF_MS = 1_000L
        const val COLD_START_RETRY_MS = 500L
        const val COPY_BUDGET_MS = 30L
        const val FORCED_GAP_MS = 120L
    }
}

/** One heartbeat interval of camera counters: keyframes in the last minute, camera-open ms, the span they cover, and frames analysed in it. */
data class DiagStats(val kf1m: Int, val openMs: Long, val spanMs: Long, val analysed: Int, val opens: Int = 0, val wakes: Map<String, Int> = emptyMap(),
    /** Battery wave A3: glances and micro bouts, opens skipped as not novel or over the cap, and same-scene (duplicate) skips, since the last snapshot. */
    val glances: Int = 0, val micro: Int = 0, val novelSkips: Int = 0, val capped: Int = 0, val same: Int = 0,
)
