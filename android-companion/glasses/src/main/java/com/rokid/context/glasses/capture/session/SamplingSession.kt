package com.rokid.context.glasses.capture.session

import com.rokid.context.core.model.CaptureMode
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.FrameGuard
import com.rokid.context.glasses.capture.KeyframeOut
import com.rokid.context.glasses.capture.SessionStatus
import com.rokid.context.glasses.capture.keyframe.Candidate
import com.rokid.context.glasses.capture.keyframe.GateResult
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
import com.rokid.context.glasses.capture.keyframe.Nv21Frame
import com.rokid.context.glasses.capture.keyframe.Thumb
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
import kotlin.random.Random

/** One camera frame as the analyzer sees it. [copy] is valid only inside the callback (the ImageProxy closes after it). */
interface FrameSource { fun copy(): FrameCopy? }

/** The 160 px grey thumbnail (hashes, blur) and the 640x480 NV21 pixels kept in case this sample wins its window. */
class FrameCopy(val thumb: Thumb, val pixels: Nv21Frame)

/** The camera seam (CameraX in production). [open] binds and starts frames; the first frame comes about 300-535 ms later. */
interface SamplingCamera {
    /** False (or a throw) = the bind failed. Frames arrive on the camera's own thread. */
    suspend fun open(onFrame: (FrameSource) -> Unit): Boolean
    /** Unbinds. Safe to call twice, from any thread. */
    fun close()
}

/** [width] is the final width (640 after rotation); [scaled] says the frame did not come out 640 wide and was scaled. */
class EncodedKeyframe(val jpeg: ByteArray, val width: Int, val scaled: Boolean)

fun interface KeyframeEncoder { fun encode(frame: Nv21Frame): EncodedKeyframe? }

/**
 * The bound sampling session (spec P1.1, "Motion-driven camera"). A 250 ms tick plus every motion change evaluates
 * [CameraPolicy.decide] and opens or closes the camera through [owner]: open while moving and for H after, opening early on
 * deceleration; closed when settled, with a brief check (open, first usable frame, close) every 5 s (15 s after 10 quiet
 * minutes). Each analysed frame is copied into a [Candidate] before the camera's buffer is released; [KeyframeGate] picks
 * the keepers and the WINNING candidate's pixels are encoded (never the current frame). Rest and mode changes release the
 * camera first; rest runs [fixedCapture] once per minute. Heat polling is the owner's job ([HeatPoller]).
 *
 * Threading: the loop and policy run on [scope]; frames arrive on the camera thread. All state is under [lock].
 * [clock] is SystemClock.elapsedRealtime (the same clock as the classifiers, heat and work/rest).
 */
class SamplingSession(
    private val scope: CoroutineScope,
    private val camera: SamplingCamera,
    private val owner: CameraOwner,
    private val motion: MotionClassifier,
    private val wear: WearDetector,
    private val gate: KeyframeGate,
    private val heat: HeatGuard,
    private val workRest: WorkRest,
    private val encoder: KeyframeEncoder,
    /** A kept keyframe and its mean luma (0..255), for [com.rokid.context.glasses.GlassesRuntime.bufferCaptured]. */
    private val sink: (KeyframeOut, Int) -> Unit,
    private val clock: () -> Long,
    private val settings: () -> HudSettings,
    /** Android 12: a camera foreground service must start from a visible HUD. Checked until the session starts, then latched. */
    private val hudVisible: () -> Boolean = { true },
    private val capturePaused: () -> Boolean = { false },
    private val batteryPct: () -> Int = { -1 },
    private val charging: () -> Boolean = { false },
    private val batterySaver: () -> Boolean = { false },
    /** One Fixed-mode capture (through [owner]); the session calls it every 60 s while resting. */
    private val fixedCapture: suspend () -> Unit = {},
    /** Three failed binds: the owner runs Fixed 30 s for the rest of this session. */
    private val onFallback: () -> Unit = {},
    private val log: (String) -> Unit = {},
    private val newSessionId: () -> String = { Random.nextLong().toULong().toString(16).padStart(16, '0') },
) {
    private class Episode(val continuous0: Boolean) {
        @Volatile var continuous = continuous0
        val done = CompletableDeferred<Unit>()
        @Volatile var closing = false
        @Volatile var openedAt = 0L
        @Volatile var firstFrameAt = -1L
        @Volatile var lastAnalysedAt = 0L
        @Volatile var analysed = 0
        @Volatile var openFailed = false
        @Volatile var noFrames = false
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
    private var seq = 0L
    private var forceAnalyse = false
    private var forceBrief = false
    private var pendingDwellAt: Long? = null
    private var pendingNowAt: Long? = null
    private var nextRestCaptureAt: Long? = null
    private val keptAt = ArrayDeque<Long>()

    /** Frames that did not come out 640 px wide and were scaled (the encoder reports each). */
    @Volatile var scaledFrames = 0
        private set
    /** Samples whose copy took longer than [COPY_BUDGET_MS] (the thumbnail target is 30 ms). */
    @Volatile var slowCopies = 0
        private set
    @Volatile var camerasOpened = 0
        private set
    /** Frames copied and offered to the gate (for logs and tests). */
    @Volatile var analysedFrames = 0
        private set

    fun start() {
        synchronized(lock) {
            if (job != null || stopped) return
            sessionId = newSessionId()
            val now = clock()
            lastKeyframeAt = now
            lastBriefAt = now - DUE_NOW_MS // the first brief check is due at once, at any cadence
        }
        job = scope.launch {
            launch { motion.state.collect { kick.trySend(Unit) } }
            launch { motion.decelerating.collect { kick.trySend(Unit) } }
            launch { motion.onset.collect { onOnset() } }
            launch { motion.dwell.collect { onDwell() } }
            while (isActive) {
                val wait = tick()
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
        forceAnalyse = true // the immediate check at the first sign of movement
        forceBrief = true
        kick.trySend(Unit)
    }

    private fun onDwell() = synchronized(lock) {
        pendingDwellAt = clock()
        kick.trySend(Unit)
        Unit
    }

    /** One policy evaluation. Returns how long the loop may sleep (a brief check can be due before the next 250 ms). */
    private fun tick(): Long {
        val now = clock()
        wear.tick()
        val s = settings()
        var wait = TICK_MS
        var ep: Episode?
        var why: String?
        synchronized(lock) {
            why = blockReason(s, now)
            if (why == null) workRest.onSampling() else workRest.onPaused()
            if (why == null && workRest.resting.value != null) why = "rest"
            blocked = why
            ep = episode
            if (why != null) { pendingDwellAt = null; pendingNowAt = null; forceBrief = false }
        }
        if (why != null) {
            closeEpisode(ep)
            if (why == "rest") restTick(now) else nextRestCaptureAt = null
            publish(now, why)
            return wait
        }
        nextRestCaptureAt = null
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
                lowPower(), heat.level.value == HeatLevel.SLOW, now, lastBriefAt,
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
                    if (forced || (dueAt != null && now + BRIEF_LEAD_MS >= dueAt)) startEpisode(continuous = false, now)
                    else if (dueAt != null) wait = (dueAt - BRIEF_LEAD_MS - now).coerceIn(1L, TICK_MS)
                }
            }
            episode?.let { e ->
                if (e.openedAt > 0 && e.firstFrameAt < 0 && now - e.openedAt > NO_FRAME_MS) { e.noFrames = true; closeEpisode(e) }
                if (!e.continuous && e.firstFrameAt >= 0 && now - e.firstFrameAt > BRIEF_GIVE_UP_MS) closeEpisode(e)
            }
        }
        publish(now, null)
        return wait
    }

    /** The first reason sampling may not run now, in the order the HUD cares about; null = go. Caller holds [lock]. */
    private fun blockReason(s: HudSettings, now: Long): String? {
        if (stopped) return "stopped"
        if (s.captureMode != CaptureMode.SMART) return "mode"
        if (!started) {
            if (!hudVisible()) return "hud"
            started = true
        }
        if (capturePaused()) return "paused"
        if (s.pauseWhenNotWorn && !wear.worn.value) return "not_worn"
        if (heat.level.value == HeatLevel.STOP) return "heat"
        if (FrameGuard.lowBattery(batteryPct(), charging())) return "battery"
        return null
    }

    private fun lowPower() = batterySaver() || batteryPct() in 0..LOW_BATTERY_PCT

    /** While resting: one Fixed capture per minute, through the camera owner, after the session has released the camera. */
    private fun restTick(now: Long) {
        val next = nextRestCaptureAt ?: (now + REST_CAPTURE_MS).also { nextRestCaptureAt = it }
        if (now < next || fixedJob?.isActive == true) return
        nextRestCaptureAt = now + REST_CAPTURE_MS
        fixedJob = scope.launch {
            try { fixedCapture() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("rest capture failed: ${e.javaClass.simpleName}") }
        }
    }

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
    private fun startEpisode(continuous: Boolean, now: Long) {
        val ep = Episode(continuous)
        episode = ep
        forceBrief = false
        scope.launch {
            try {
                owner.acquire(OWNER) {
                    if (ep.closing) return@acquire
                    ep.openedAt = clock()
                    camerasOpened++
                    val ok = try {
                        camera.open { src -> onFrame(ep, src) }
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
            lastBriefAt = now
            if (ep.openFailed || ep.noFrames) { lastBriefAt = now - DUE_NOW_MS; onBindFailed(now) } // retry after the back-off, not on the 5 s beat
            if (!stopped) flushed = gate.flushWindow()
        }
        (flushed as? GateResult.Keep)?.let { emit(it, now) }
        kick.trySend(Unit)
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
        synchronized(lock) {
            if (ep !== episode || ep.closing || stopped) return
            if (ep.firstFrameAt < 0) { ep.firstFrameAt = now; bindFailures = 0 }
            val must = forceAnalyse || pendingDwellAt != null || pendingNowAt != null
            // The first frame, an onset, a dwell and "Capture now" are analysed at once (at most every FORCED_GAP_MS).
            val gap = now - ep.lastAnalysedAt
            if (ep.analysed > 0 && gap < (if (must) FORCED_GAP_MS else analysisIntervalMs)) return
            ep.lastAnalysedAt = now
            ep.analysed++
            forceAnalyse = false
            dwellArmed = pendingDwellAt != null
            forced = pendingNowAt != null
            sequence = ++seq
        }
        val copy = src.copy() ?: return
        analysedFrames++
        if (clock() - now > COPY_BUDGET_MS) slowCopies++
        val luma = meanLuma(copy.thumb)
        val cand = Candidate(sequence, copy.thumb, copy.pixels)
        // A dark frame is unusable like a blurred one: the camera stays open for the next.
        val result: GateResult? = if (FrameGuard.checkBatteryAndDark(batteryPct(), charging(), luma.toDouble()) != null) null
        else synchronized(lock) {
            if (ep !== episode || ep.closing || stopped) return
            gate.offer(cand, motion.state.value, dwellArmed, forced)
        }
        val usable = result != null && !(result is GateResult.Skip && result.reason == KeyframeGate.BLUR)
        if (result is GateResult.Keep) emit(result, now)
        synchronized(lock) {
            if (usable) {
                if (dwellArmed) pendingDwellAt = null
                if (forced) pendingNowAt = null
                if (!ep.continuous) closeEpisode(ep) // a brief check ends at its first usable frame
            }
        }
    }

    /** Encodes the WINNING candidate's pixels and hands the keyframe on. */
    private fun emit(keep: GateResult.Keep, now: Long) {
        val enc = encoder.encode(keep.candidate.pixels)
        if (enc == null) { log("encode failed for sample ${keep.candidate.seq}"); return }
        if (enc.scaled) { scaledFrames++; log("keyframe was not 640 wide: scaled (count $scaledFrames)") }
        val out = KeyframeOut(
            enc.jpeg, enc.width, motion.state.value, keep.dwell, keep.blur,
            hex(keep.dhash), hex(keep.phash), keep.candidate.seq, sessionId,
        )
        synchronized(lock) { lastKeyframeAt = now; keptAt.addLast(now) }
        log("keyframe seq=${out.sampleSeq} motion=${out.motion} dwell=${out.dwell} blur=${out.blur.toInt()} bytes=${out.jpeg.size}")
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
        const val ANALYSIS_MS = 333L
        /** Older than any brief-check cadence (5 s, 15 s, x2 for heat): a check anchored here is due now. */
        const val DUE_NOW_MS = 40_000L
        /** Cold camera start-up p95 (probe). A brief check opens this early so its first frame lands on the 5 s / 15 s beat. */
        const val BRIEF_LEAD_MS = 535L
        const val BRIEF_GIVE_UP_MS = 1_500L
        const val NO_FRAME_MS = 3_000L
        const val PENDING_MS = 3_000L
        const val REST_CAPTURE_MS = 60_000L
        const val TEN_MIN_MS = 10 * 60_000L
        const val LOW_BATTERY_PCT = 20
        const val MAX_BIND_FAILURES = 3
        const val BIND_BACKOFF_MS = 1_000L
        const val COPY_BUDGET_MS = 30L
        const val FORCED_GAP_MS = 120L
    }
}
