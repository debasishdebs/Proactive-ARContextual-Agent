package com.rokid.context.capture.replay

import com.rokid.context.capture.plan.reservedBoundMs
import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.Exactness
import com.rokid.context.capture.trace.GateOutcome
import com.rokid.context.capture.trace.Inexact
import com.rokid.context.capture.trace.Manifest
import com.rokid.context.capture.trace.ReplayRule
import com.rokid.context.capture.trace.ReplayTally
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState
import com.rokid.context.glasses.capture.keyframe.Candidate
import com.rokid.context.glasses.capture.keyframe.GateParams
import com.rokid.context.glasses.capture.keyframe.GateResult
import com.rokid.context.glasses.capture.keyframe.GateStore
import com.rokid.context.glasses.capture.keyframe.KeyframeGate
import com.rokid.context.glasses.capture.keyframe.Nv21Frame
import com.rokid.context.glasses.capture.keyframe.Thumb
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.motion.MotionClassifier
import com.rokid.context.glasses.capture.session.CameraPolicy
import com.rokid.context.glasses.capture.session.CaptureProfile
import com.rokid.context.glasses.capture.session.GlanceAdmission

/**
 * A counterfactual config to replay the same recording under (Task 21 builds the full one from a `CapturePlan`). [configHash] is the identity the result
 * carries; [glance] and [gate], when set, replace what the recording was made with. Without a candidate a replay is the BASE: the recorded config, checked
 * against the recording.
 */
class ReplayCandidate(val configHash: String, val glance: GlanceParams? = null, val gate: GateParams? = null)

/** A camera open the replay decided (after the pre-roll). */
data class ReplayOpen(val atMs: Long, val cause: EpisodeCause)

/** One place where the replay and the recording disagree. [kind]: `open_missed`, `open_extra`, `gate`, `gate_missing`, `gate_extra`. */
data class ReplayMismatch(val kind: String, val atMs: Long, val detail: String)

/**
 * The outcome of one replay. [configHash] identifies the config that was replayed: the recorded plan's hash for a BASE replay (`"0"` = settings, no plan),
 * the candidate's for a counterfactual one. [compared] is false for a candidate: nothing is checked against the recording, so [passed], [failed] and [inexact]
 * stay 0 and only [opens], [gates], [keeps] and [skips] mean anything.
 *
 * The verdict counts windows (see [CaptureSimulator]): [passed] and [failed] are the exact windows, [inexact] the windows excluded from pass/fail
 * (a `captureNow` request, a bind failure or back-off, or an async dwell/onset episode), and [inexactReasons] how many windows each reason touched. This
 * model is not on the wire.
 */
data class ReplayResult(
    val configHash: String,
    val compared: Boolean,
    val opens: List<ReplayOpen>,
    val gates: List<TraceEvent.Gate>,
    val keeps: Int,
    val skips: Int,
    val passed: Int,
    val failed: Int,
    val inexact: Int,
    val inexactReasons: Map<Inexact, Int>,
    /** The first [CaptureSimulator.MAX_MISMATCHES] disagreements; [mismatchCount] is the total. */
    val mismatches: List<ReplayMismatch>,
    val mismatchCount: Int,
) {
    /** True when no window had to be excluded. */
    val exact: Boolean get() = inexact == 0
}

/**
 * Replays a recorded IMU trace (frozen schema 1) through the real scheduler code and compares it with what was recorded.
 *
 * The recorded events drive the clock: sensor events feed [MotionClassifier] (sensor time `sMs`, as the live classifier saw it), and the replay runs the
 * session tick every 250 ms of event time (and at once after anything that kicks the live loop), calling [CameraPolicy.decide], [GlanceAdmission.decide] and
 * [KeyframeGate] (`offerScored`, `flushWindow`, `timerHoldUntil`) with the recorded `Profile`, `Backoff`, `Blocked`, `Ctx`, `Resting` and `DarkStreak`
 * inputs and the snapshot's start state. The tick body (the loop around those calls: brief lead, hold, reserved attempt) lives in `SamplingSession`, which
 * is a glasses class, so it is mirrored here; Task 21's bit-identical session test is what keeps the two together.
 *
 * Replay is APPROXIMATE (controller ruling), in these ways:
 * - Episodes follow the recording (an `ep` open or close is a fact the scheduler state adopts), and the replay only decides OPENS: whenever no episode is
 *   open it asks the scheduler "would you open now, and why". A decision matches a recorded open when the cause is equal and the times differ by at most
 *   [ReplayRule.OPEN_TOLERANCE_MS] (a tick is not recorded).
 * - Gates are replayed from fully recorded inputs and must be EQUAL ([ReplayRule.gatesMatch]). The session bookkeeping times (`gt.keptAtMs`, `gt.encoded`)
 *   are recorded facts the replay adopts for a keep it reproduced, not something it can derive (the frame-entry time and the encoder are not in the trace).
 * - The trace is cut into windows at every recorded open (a window runs from one recorded open to the next, both ends included). A window that contains a
 *   `captureNow` request, a bind failure or back-off, or an async dwell/onset episode ([Exactness.classify]) is INEXACT: excluded from pass/fail and counted.
 * - Everything before `PreRollEnd` warms the state up and is discarded.
 *
 * Assumption about the recorder (Task 16), pinned by the tests here: one `gt` follows every `sc` for the seq the gate judged, and a window or flush keep appends
 * a `gt` for the WINNER's seq (the candidate the gate returned, which can be an earlier frame); a flush that keeps nothing appends none.
 *
 * Input is consumed once, event by event; memory is bounded by the open window, the open decisions and the results (not by the trace length).
 */
object CaptureSimulator {
    const val TICK_MS = 250L
    const val MAX_MISMATCHES = 64

    /** A BASE replay (no [candidate]) of the recorded config, or a counterfactual one. `IllegalArgumentException("approximate trace")` for a trace that is not exact. */
    fun replay(events: Sequence<TraceEvent>, m: Manifest, candidate: ReplayCandidate? = null): ReplayResult {
        require(m.snapshot.exact) { "approximate trace" }
        val run = Run(m, candidate)
        for (e in events) run.accept(e)
        return run.finish()
    }

    // Constants of SamplingSession (glasses), which this mirrors.
    private const val BRIEF_LEAD_MS = 535L
    private const val MIN_LEAD_SAMPLES = 3
    private const val MIN_LEAD_MS = 250L
    private const val MAX_LEAD_MS = 1_500L
    private const val STARTUP_WINDOW = 20
    private const val DUE_NOW_MS = 40_000L
    private const val PENDING_MS = 3_000L
    private const val HOLD_MIN_WAIT_MS = 50L
    private const val DARK_BACKOFF_AFTER = 3
    private const val BUSY_BACKOFF_MS = 30_000L
    private const val BIND_BACKOFF_MS = 1_000L
    private const val COLD_START_RETRY_MS = 500L
    private const val MAX_BIND_FAILURES = 3
    private const val TEN_MIN_MS = 10 * 60_000L
    private const val DECISION_HOLD_MS = 1_000L
    private const val GATE_PAIR_MS = 2_000L

    /** The replay's gate store: the last five keyframes and the last keep time, seeded from the snapshot. */
    private class Store : GateStore {
        private val ring = ArrayDeque<Pair<Long, Long>>()
        private var last = 0L
        var frozen = false
        override fun lastKeyframes(): List<Pair<Long, Long>> = ring.toList()
        override fun push(d: Long, p: Long) { if (frozen) return; ring.addLast(d to p); while (ring.size > 5) ring.removeFirst() }
        override fun lastKeptAt(): Long = last
        override fun setLastKeptAt(t: Long) { if (!frozen) last = t }
        fun seed(r: List<Pair<Long, Long>>, lastKept: Long) { ring.clear(); ring.addAll(r.takeLast(5)); last = lastKept }
    }

    private class Win(val startMs: Long, val carryFailures: Int, val carryBackoff: Long?) {
        /** Only the events [Exactness.classify] reads: episodes and forced scores. */
        val flagged = ArrayList<TraceEvent>()
        var endMs = -1L
        var extra = 0
        var missed = 0
        var gateBad = 0
    }

    private class RecOpen(val ev: TraceEvent.Episode, val win: Win)
    private class RepOpen(val open: ReplayOpen, val win: Win)
    private class Expected(val seq: Long, val gate: TraceEvent.Gate, val slot: Int, val nowMs: Long, val win: Win)

    private class Run(val m: Manifest, val cand: ReplayCandidate?) {
        val snap = m.snapshot
        val start = m.startElapsedMs
        val base = cand == null
        var now = start
        var gateNow = start
        var gateParams: GateParams = cand?.gate ?: GateParams()
        val glance: GlanceParams = cand?.glance ?: snap.glance
        val store = Store()
        val gate = KeyframeGate(store, { gateNow }, { gateParams })
        val cls = MotionClassifier({ now }, glance)

        // recorded inputs
        var pf: TraceEvent.Profile = CaptureProfile.of(CameraWake.MOTION, snap.tier).let {
            TraceEvent.Profile(start, it.wake, it.settledCheckMs, it.quietCheckMs, it.walkCheckMs, it.stillCapMs, it.analysisMs, snap.tier, false, false, 3000, null, false)
        }
        var ctx = snap.ctx
        var resting = snap.resting
        var blocked: String? = null
        var stillStep = snap.stillStep
        var darkStreak = snap.darkStreak
        var recordedHash: String? = null

        // scheduler state (SamplingSession)
        var lastBriefAt = snap.lastBriefAt ?: (start - DUE_NOW_MS)
        var lastKeyframeAt = snap.lastKeyframeAt ?: start
        var lastPhotoAttemptAt = snap.lastPhotoAttemptAtMs ?: start
        var bindFailures = snap.bindFailures
        var bindBackoffUntil = snap.bindBackoffUntil ?: 0L
        var coldStartGrace = snap.coldStartGrace
        var stopped = false
        val startups = ArrayDeque(snap.startups)
        val keptAt = ArrayDeque(snap.keptAt)
        var wasMoving = false
        var motionEndAt: Long? = null
        var pendingDwellAt: Long? = null
        var pendingNowAt: Long? = null
        var forceBrief = false
        var episodeOpen = false
        var epContinuous = false
        var suppressUntil = 0L
        var nextTickAt = start
        var dwellFired = false
        var onsetFired = false

        // comparison state
        val compared = base
        var preRoll = true
        val tally = ReplayTally()
        val reasonCounts = java.util.EnumMap<Inexact, Int>(Inexact::class.java)
        val opens = ArrayList<ReplayOpen>()
        val gates = ArrayList<TraceEvent.Gate>()
        var keeps = 0
        var skips = 0
        val mismatches = ArrayList<ReplayMismatch>()
        var mismatchCount = 0
        var cur = newWin(start)
        val closing = ArrayDeque<Win>()
        val awaiting = ArrayList<RecOpen>()
        val pendingReplayed = ArrayList<RepOpen>()
        val expected = ArrayList<Expected>()

        init {
            cls.restoreForReplay(snap.prevGyroMs, snap.pose.rx, snap.pose.ry, snap.pose.rz, snap.pose.walked, snap.pose.qx, snap.pose.qy, snap.pose.qz, snap.pose.quietSinceMs)
            cls.dwellListener = { dwellFired = true }
            cls.onsetListener = { onsetFired = true }
            store.frozen = true
            val dummy = Candidate(0, Thumb(0, 0, ByteArray(0)), Nv21Frame(0, 0, 0, ByteArray(0)))
            snap.gateKeptAt.forEach { gate.recordKeep(dummy, 0, 0, it) }
            store.frozen = false
            store.seed(snap.ring.map { it.d to it.p }, snap.gateLastKeptAt ?: 0L)
        }

        fun newWin(t: Long) = Win(t, bindFailures, bindBackoffUntil.takeIf { it > t })

        fun mismatch(kind: String, at: Long, detail: String) {
            mismatchCount++
            if (mismatches.size < MAX_MISMATCHES) mismatches += ReplayMismatch(kind, at, detail)
        }

        // ---- the event loop ----

        fun accept(e: TraceEvent) {
            advanceTo(e.tMs)
            if (e.tMs > now) now = e.tMs
            when (e) {
                is TraceEvent.Gyro -> { val s = cls.state.value; val d = cls.decelerating.value; cls.onGyro(e.x, e.y, e.z, e.sMs); afterSensor(e.tMs, s, d) }
                is TraceEvent.Accel -> { val s = cls.state.value; val d = cls.decelerating.value; cls.onLinearAccel(e.x, e.y, e.z, e.sMs); afterSensor(e.tMs, s, d) }
                is TraceEvent.Ctx -> ctx = e.state
                is TraceEvent.Resting -> resting = e.on
                is TraceEvent.Profile -> pf = e
                is TraceEvent.GateParams -> if (cand?.gate == null) gateParams = GateParams(e.hamming, e.bigChange, e.ringAll)
                is TraceEvent.Plan -> recordedHash = e.configHash
                is TraceEvent.Backoff -> { stillStep = e.stillStep; kick(e.tMs) }
                is TraceEvent.Blocked -> { blocked = e.reason; kick(e.tMs) }
                is TraceEvent.DarkStreak -> darkStreak = e.streak
                is TraceEvent.Episode -> if (e.open) onRecordedOpen(e) else onRecordedClose(e)
                is TraceEvent.Score -> onScore(e)
                is TraceEvent.Gate -> onRecordedGate(e)
                is TraceEvent.Flush -> onFlush(e)
                is TraceEvent.PreRollEnd -> onPreRollEnd(e)
                is TraceEvent.Close -> Unit
            }
        }

        fun advanceTo(t: Long) {
            while (nextTickAt < t) {
                val at = nextTickAt
                val wait = tick(at)
                nextTickAt = at + wait.coerceAtLeast(1L)
                settle(at)
            }
        }

        /** Something the live loop is kicked by (state change, onset, dwell, episode end, block change): the next tick runs now. */
        fun kick(t: Long) { if (t < nextTickAt) nextTickAt = t }

        fun afterSensor(t: Long, s0: MotionState, d0: Boolean) {
            var kicked = cls.state.value != s0 || cls.decelerating.value != d0
            if (onsetFired) { onsetFired = false; onOnset(); kicked = true }
            if (dwellFired) { dwellFired = false; onDwell(t); kicked = true }
            if (kicked) kick(t)
        }

        // ---- glance admission (SamplingSession.onOnset / onDwell) ----

        fun capNowFor(cc: Int?): Int = minOf(if (resting) glance.restCapPerMin else glance.capPerMin, cc ?: Int.MAX_VALUE)

        fun onOnset() {
            if (!resting && pf.wake == CameraWake.MOTION) forceBrief = true   // the immediate check at the first sign of movement (stillStep resets come as `bo`)
        }

        fun onDwell(t: Long) {
            val k = keptAt.count { t - it < 60_000L }
            val d = GlanceAdmission.decide(t - lastKeyframeAt, k, capNowFor(pf.ctxCap), pf.hard, cls.rotationSinceMarkRad(), cls.walkedSinceMark(), glance)
            if (d == GlanceAdmission.Decision.OPEN) pendingDwellAt = t
        }

        // ---- the tick (SamplingSession.tick) ----

        fun briefLeadMs(): Long {
            if (startups.size < MIN_LEAD_SAMPLES) return BRIEF_LEAD_MS
            val sorted = startups.sorted()
            return sorted[(Math.ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)].coerceIn(MIN_LEAD_MS, MAX_LEAD_MS)
        }

        fun tick(t: Long): Long {
            now = t
            var wait = TICK_MS
            if (!compared) awaiting.removeAll { it.ev.tMs + ReplayRule.OPEN_TOLERANCE_MS < t }   // a candidate's recorded opens wait only one tolerance for the replay's own decision
            if (stopped) return wait
            if (blocked != null) { pendingDwellAt = null; pendingNowAt = null; forceBrief = false; return wait }
            val st = cls.state.value
            val moving = st == MotionState.HEAD_MOVING || st == MotionState.WALKING
            if (moving) motionEndAt = null else if (wasMoving) motionEndAt = t
            wasMoving = moving
            pendingDwellAt?.let { if (t - it > PENDING_MS) pendingDwellAt = null }
            pendingNowAt?.let { if (t - it > PENDING_MS) pendingNowAt = null }
            val prof = CaptureProfile(pf.wake, pf.settledMs, pf.quietMs, pf.walkMs, pf.analysisMs, false, pf.stillCapMs)
            val d = CameraPolicy.decide(
                st, cls.decelerating.value, motionEndAt?.let { t - it }, t - lastKeyframeAt, pf.quickLookMs.toInt(), pf.lowPower, pf.heatSlow, t, lastBriefAt,
                quiet = darkStreak >= DARK_BACKOFF_AFTER, profile = prof, stillStep = stillStep,
            )
            val canBind = t >= bindBackoffUntil
            // A replayed decision is only taken while no episode is open, or while a recorded open is still waiting for its (possibly later) twin.
            val decidable = canBind && (!episodeOpen || awaiting.isNotEmpty()) && t >= suppressUntil
            if (d.open) {
                forceBrief = false
                if (!episodeOpen || awaiting.isNotEmpty()) { if (decidable) decide(t, EpisodeCause.STREAM) } else if (!epContinuous) epContinuous = true
            } else if (decidable && !(episodeOpen && epContinuous && awaiting.isEmpty())) {
                val dueAt = d.nextBriefCheckAtMs
                val forced = forceBrief || pendingDwellAt != null || pendingNowAt != null
                val lead = briefLeadMs()
                val timerDue = dueAt != null && t + lead >= dueAt
                val hold = if (!forced && timerDue) gate.timerHoldUntil(capNowFor(pf.ctxCap), t)?.takeIf { it > t } else null
                val resv = !forced && t - lastPhotoAttemptAt >= reservedBoundMs(ctx, resting) && (!timerDue || hold != null)
                if (hold != null && !resv) wait = (hold - t).coerceIn(HOLD_MIN_WAIT_MS, TICK_MS)
                else if (forced || timerDue || resv) {
                    decide(t, when { pendingNowAt != null -> EpisodeCause.NOW; pendingDwellAt != null -> EpisodeCause.DWELL; forceBrief -> EpisodeCause.ONSET; resv -> EpisodeCause.RESV; else -> EpisodeCause.TIMER })
                } else if (dueAt != null) wait = (dueAt - lead - t).coerceIn(1L, TICK_MS)
            }
            return wait
        }

        /** The scheduler would open the camera now, for [cause]. */
        fun decide(t: Long, cause: EpisodeCause) {
            forceBrief = false
            suppressUntil = t + DECISION_HOLD_MS
            if (preRoll) return
            val ro = ReplayOpen(t, cause)
            opens += ro
            // A candidate keeps no verdict, but a recorded open at the same instant is the SAME decision (not a pre-emption of it): consume it, so the candidate's opens are counted.
            if (!compared) { awaiting.firstOrNull { ReplayRule.opensMatch(it.ev.tMs, it.ev.cause, t, cause) }?.let { awaiting.remove(it) }; return }
            val rec = awaiting.firstOrNull { ReplayRule.opensMatch(it.ev.tMs, it.ev.cause, t, cause) }
            if (rec != null) awaiting.remove(rec) else pendingReplayed += RepOpen(ro, cur)
        }

        // ---- recorded episodes ----

        fun onRecordedOpen(e: TraceEvent.Episode) {
            episodeOpen = true
            epContinuous = e.cause == EpisodeCause.STREAM
            suppressUntil = 0L
            if (preRoll) { forceBrief = false; return }
            cur.flagged += e
            cur.endMs = e.tMs
            if (!compared) { forceBrief = false; awaiting += RecOpen(e, cur); return }
            val twin = pendingReplayed.firstOrNull { ReplayRule.opensMatch(e.tMs, e.cause, it.open.atMs, it.open.cause) }
            if (twin != null) pendingReplayed.remove(twin) else awaiting += RecOpen(e, cur)
            closing += cur
            cur = newWin(e.tMs).also { it.flagged += e }
        }

        fun onRecordedClose(e: TraceEvent.Episode) {
            val wasContinuous = epContinuous || e.cause == EpisodeCause.STREAM
            episodeOpen = false
            epContinuous = false
            if (!preRoll) cur.flagged += e
            val first = e.firstFrameAtMs
            if (first != null) {
                bindFailures = 0; coldStartGrace = false
                val opened = e.openedAtMs
                lastPhotoAttemptAt = maxOf(lastPhotoAttemptAt, if (opened != null && opened > 0) opened else first)
                if (opened != null && opened > 0) { startups.addLast(first - opened); while (startups.size > STARTUP_WINDOW) startups.removeFirst() }
                if (wasContinuous) lastPhotoAttemptAt = maxOf(lastPhotoAttemptAt, e.tMs)
            }
            lastBriefAt = e.tMs
            val outcome = e.outcome
            if (outcome != null && outcome != EpisodeOutcome.OK) {
                lastBriefAt = e.tMs - DUE_NOW_MS                       // retry after the back-off, not on the 5 s beat
                val asCold = outcome == EpisodeOutcome.COLD || ((outcome == EpisodeOutcome.OPEN_FAILED || outcome == EpisodeOutcome.NO_FRAMES) && coldStartGrace)
                when {
                    asCold -> { coldStartGrace = false; bindBackoffUntil = e.tMs + COLD_START_RETRY_MS }
                    outcome == EpisodeOutcome.BUSY -> bindBackoffUntil = e.tMs + BUSY_BACKOFF_MS
                    else -> {
                        bindFailures++
                        bindBackoffUntil = e.tMs + BIND_BACKOFF_MS
                        if (bindFailures >= MAX_BIND_FAILURES) stopped = true
                    }
                }
            }
            kick(e.tMs)
        }

        // ---- frames and the gate ----

        fun gateOf(r: GateResult, offeredSeq: Long): Pair<Long, TraceEvent.Gate> = when (r) {
            is GateResult.Keep -> r.candidate.seq to TraceEvent.Gate(now, r.candidate.seq, GateOutcome.KEEP, "", encoded = true, keptAtMs = gateNow)
            is GateResult.Skip -> offeredSeq to TraceEvent.Gate(now, offeredSeq, GateOutcome.SKIP, r.reason)
        }

        fun record(seq: Long, g: TraceEvent.Gate) {
            if (g.outcome == GateOutcome.KEEP) keeps++ else skips++
            val slot = gates.size
            gates += g
            if (compared) expected += Expected(seq, g, slot, gateNow, cur)
        }

        fun onScore(e: TraceEvent.Score) {
            if (e.force && !preRoll) cur.flagged += e
            gateNow = e.nowMs
            val cap = if (base) e.cap ?: Int.MAX_VALUE else capNowFor(pf.ctxCap)
            val hard = if (base) e.hard else pf.hard
            val r = gate.offerScored(e.seq, e.d, e.p, e.blur, e.motion, e.dwellArmed, e.force, cap, hard, e.nowMs)
            if (e.dwellArmed && e.passedBlur) pendingDwellAt = null
            if (e.force) pendingNowAt = null
            val (seq, g) = gateOf(r, e.seq)
            if (!base && r is GateResult.Keep) bookKeep(e.nowMs)
            if (!preRoll) record(seq, g)
        }

        fun onFlush(e: TraceEvent.Flush) {
            gateNow = e.nowMs
            val r = gate.flushWindow(capNowFor(pf.ctxCap), pf.hard)
            if (r is GateResult.Keep) {
                if (!base) bookKeep(e.nowMs)
                if (!preRoll) { val (seq, g) = gateOf(r, r.candidate.seq); record(seq, g) }
            }
        }

        fun onRecordedGate(e: TraceEvent.Gate) {
            if (base && e.outcome == GateOutcome.KEEP && e.encoded && e.keptAtMs != null) bookKeep(e.keptAtMs)
            if (preRoll || !compared) return
            val x = expected.firstOrNull { it.seq == e.seq }
            if (x == null) { cur.gateBad++; mismatch("gate_extra", e.tMs, "recorded gt seq=${e.seq} has no replayed gate"); return }
            expected.remove(x)
            val rep = if (x.gate.outcome == GateOutcome.KEEP) x.gate.copy(tMs = e.tMs, encoded = e.encoded, keptAtMs = if (e.encoded) (e.keptAtMs ?: x.nowMs) else null) else x.gate.copy(tMs = e.tMs)
            gates[x.slot] = rep
            if (!ReplayRule.gatesMatch(e, rep)) {
                x.win.gateBad++
                mismatch("gate", e.tMs, "seq=${e.seq} recorded ${e.outcome}/${e.reason} replayed ${rep.outcome}/${rep.reason}")
            }
        }

        /** The session's keep bookkeeping (`SamplingSession.emit`): the last keyframe time, the keep list and the pose reset. */
        fun bookKeep(t: Long) {
            lastKeyframeAt = t
            keptAt.addLast(t)
            while (keptAt.isNotEmpty() && t - keptAt.first() > TEN_MIN_MS) keptAt.removeFirst()
            cls.markPose()
        }

        fun onPreRollEnd(e: TraceEvent.PreRollEnd) {
            preRoll = false
            awaiting.clear(); pendingReplayed.clear(); expected.clear(); closing.clear()
            cur = newWin(e.tMs)
        }

        // ---- verdicts ----

        /** Expires what can no longer match at [t] and settles the windows whose recorded open has had its tolerance. */
        fun settle(t: Long) {
            val it1 = pendingReplayed.iterator()
            while (it1.hasNext()) {
                val p = it1.next()
                if (p.open.atMs + ReplayRule.OPEN_TOLERANCE_MS < t) {
                    it1.remove(); p.win.extra++
                    mismatch("open_extra", p.open.atMs, "replay would open (${p.open.cause}) with no recorded open")
                }
            }
            val it2 = awaiting.iterator()
            while (it2.hasNext()) {
                val a = it2.next()
                if (a.ev.tMs + ReplayRule.OPEN_TOLERANCE_MS < t) {
                    it2.remove(); a.win.missed++; forceBrief = false
                    mismatch("open_missed", a.ev.tMs, "recorded open (${a.ev.cause}) was not recomputed")
                }
            }
            val it3 = expected.iterator()
            while (it3.hasNext()) {
                val x = it3.next()
                if (x.nowMs + GATE_PAIR_MS < t) { it3.remove(); x.win.gateBad++; mismatch("gate_missing", x.nowMs, "replayed gate seq=${x.seq} has no recorded gt") }
            }
            while (closing.isNotEmpty() && closing.first().endMs + ReplayRule.OPEN_TOLERANCE_MS < t) finalize(closing.removeFirst())
        }

        fun finalize(w: Win) {
            if (!compared) return
            val carry = snap.copy(bindFailures = w.carryFailures, bindBackoffUntil = w.carryBackoff)
            val reasons = Exactness.classify(w.flagged, carry)
            reasons.forEach { reasonCounts.merge(it, 1, Int::plus) }
            tally.record(reasons, w.extra == 0 && w.missed == 0 && w.gateBad == 0)
        }

        fun finish(): ReplayResult {
            if (compared && !preRoll) {
                // what falls inside the last tolerance of the trace cannot be judged: its twin would have come after the close
                pendingReplayed.removeAll { it.open.atMs + ReplayRule.OPEN_TOLERANCE_MS >= now }
                awaiting.removeAll { it.ev.tMs + ReplayRule.OPEN_TOLERANCE_MS >= now }
                expected.removeAll { it.nowMs + GATE_PAIR_MS >= now }
                settle(now + 1)
                while (closing.isNotEmpty()) finalize(closing.removeFirst())
                finalize(cur)
            }
            val hash = cand?.configHash ?: recordedHash ?: snap.planHash ?: "0"
            return ReplayResult(
                hash, compared, opens, gates, keeps, skips, tally.passed, tally.failed, tally.inexact, LinkedHashMap(reasonCounts), mismatches, mismatchCount,
            )
        }
    }
}
