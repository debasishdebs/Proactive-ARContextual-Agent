package com.rokid.context.capture.replay

import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.GateOutcome
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.PoseSnapshot
import com.rokid.context.capture.trace.StartSnapshot
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceFixtures
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.session.BudgetTier

/**
 * Test-only trace builders. The expected values are computed here by plain arithmetic from the scheduler's documented rules (a brief check opens
 * `BRIEF_LEAD_MS` = 535 ms before it is due; the gap is `settledMs` doubled `stillStep` times, capped), never by calling the simulator, so a trace built here
 * is an independent oracle for it.
 */
object ReplayFixtures {
    const val START = 1_000L
    const val PRE_ROLL_END = START + 10_000L
    const val LEAD = 535L
    /** The first-frame start-up the fixture episodes measure (open +50 -> first frame +500). */
    const val STARTUP = 450L
    /** The brief-check lead: 535 ms until three start-ups are known, then their p95 (here always [STARTUP]). */
    fun leadAfter(closes: Int): Long = if (closes < 3) LEAD else STARTUP
    const val SETTLED = 5_000L
    const val STILL_CAP = 60_000L

    /** The glance block with a cap too high to ever hold a timer check, so the timer cadence is the pure `CameraPolicy` cadence. */
    val glance = GlanceParams(capPerMin = 1000, restCapPerMin = 1000)

    val snapshot: StartSnapshot = TraceFixtures.snapshot.copy(
        ring = emptyList(), lastKeyframeAt = START, keptAt = emptyList(), gateLastKeptAt = null, gateKeptAt = emptyList(), lastBriefAt = START, stillStep = 0,
        lastCheckHash = null, darkStreak = 0, bindBackoffUntil = null, coldStartGrace = false, bindFailures = 0, prevGyroMs = null, startups = emptyList(),
        pose = PoseSnapshot.ZERO, lastPhotoAttemptAtMs = START, planGen = 0, planHash = null, glance = glance, ctx = ContextState.UNKNOWN, resting = false,
        tier = BudgetTier.T0, sampleEvery = 1, exact = true,
    )

    fun manifest(snap: StartSnapshot = snapshot, endMs: Long = START + 600_000L): Manifest =
        TraceFixtures.manifest(snap = snap).copy(startElapsedMs = START, endElapsedMs = endMs, closeReason = "stop")

    /** The six events a recorder writes at the start time. */
    fun initial(
        ctxCap: Int? = null, hard: Boolean = false, wake: CameraWake = CameraWake.MOTION, settledMs: Long = SETTLED, hash: String = "0", mode: PlanMode = PlanMode.SETTINGS,
    ): List<TraceEvent> = listOf(
        TraceEvent.Ctx(START, ContextState.UNKNOWN),
        TraceEvent.Resting(START, false),
        TraceEvent.Profile(START, wake, settledMs, 15_000, null, STILL_CAP, 333, BudgetTier.T0, lowPower = false, heatSlow = false, quickLookMs = 3000, ctxCap = ctxCap, hard = hard),
        TraceEvent.GateParams(START, 10, 22, true),
        TraceEvent.Plan(START, 0, hash, mode),
    )

    /** One brief timer episode: open at [openAt], the first frame 500 ms later, the close 800 ms after the open. */
    fun timerEpisode(openAt: Long, cause: EpisodeCause = EpisodeCause.TIMER): List<TraceEvent> = listOf(
        TraceEvent.Episode(openAt, true, cause),
        TraceEvent.Episode(openAt + 800, false, cause, firstFrameAtMs = openAt + 500, openedAtMs = openAt + 50, outcome = EpisodeOutcome.OK),
    )

    /**
     * Oracle for a still scene with no motion and no frames kept: opens at `lastBrief + gap - lead` (see [leadAfter]), closes 800 ms later, the next check is due from the
     * close; [stillSteps] (when non-null) gives the back-off step recorded after the k-th close (a `bo` event at the close time).
     */
    class TimerOracle(val opens: List<Long>, val events: List<TraceEvent>)

    fun timerOracle(untilMs: Long, stillSteps: (Int) -> Int = { 0 }): TimerOracle {
        val opens = ArrayList<Long>(); val ev = ArrayList<TraceEvent>()
        var lastBrief = START; var step = 0; var k = 0
        while (true) {
            val gap = if (SETTLED >= STILL_CAP) SETTLED else minOf(SETTLED shl step, STILL_CAP)
            val open = lastBrief + gap - leadAfter(k)
            if (open + 800 > untilMs) break
            opens += open; ev += timerEpisode(open)
            lastBrief = open + 800
            k++
            val ns = stillSteps(k)
            if (ns != step) { step = ns; ev += TraceEvent.Backoff(lastBrief, ns) }
        }
        return TimerOracle(opens, ev)
    }

    /** A complete small trace: the initial events, the pre-roll end, the [body] events (time ordered), and the close at [endMs]. */
    fun trace(body: List<TraceEvent>, endMs: Long = START + 120_000L, initial: List<TraceEvent> = initial()): List<TraceEvent> {
        val all = ArrayList<TraceEvent>(initial)
        all += (body + TraceEvent.PreRollEnd(PRE_ROLL_END)).sortedBy { it.tMs }
        all += TraceEvent.Close(endMs, "stop")
        return all
    }

    /** Distinct 64-bit hashes: each pair differs in far more than the 10 bit new-scene threshold. */
    fun sceneHash(i: Int): Pair<Long, Long> {
        var x = 0x9E3779B97F4A7C15uL * (i + 1).toULong()
        fun next(): Long { x = x xor (x shl 13); x = x xor (x shr 7); x = x xor (x shl 17); return x.toLong() }
        return next() to next()
    }

    fun score(t: Long, seq: Long, scene: Int, blur: Float = 120f, motion: MotionState = MotionState.STILL, dwellArmed: Boolean = false, force: Boolean = false, cap: Int? = null, hard: Boolean = false, nowMs: Long = t) =
        sceneHash(scene).let { (d, p) -> TraceEvent.Score(t, seq, d, p, blur, passedBlur = blur >= 60f, motion = motion, dwellArmed = dwellArmed, force = force, cap = cap, hard = hard, nowMs = nowMs) }

    fun keep(t: Long, seq: Long, keptAt: Long = t) = TraceEvent.Gate(t, seq, GateOutcome.KEEP, "", encoded = true, keptAtMs = keptAt)
    fun skip(t: Long, seq: Long, reason: String) = TraceEvent.Gate(t, seq, GateOutcome.SKIP, reason)
}
