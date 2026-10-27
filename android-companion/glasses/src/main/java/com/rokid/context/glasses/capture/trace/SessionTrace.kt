package com.rokid.context.glasses.capture.trace

import com.rokid.context.capture.trace.EpisodeCause
import com.rokid.context.capture.trace.EpisodeOutcome
import com.rokid.context.capture.trace.StartSnapshot
import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.glasses.capture.keyframe.GateParams
import com.rokid.context.glasses.capture.session.BudgetTier

/**
 * The configuration a trace segment records at its start and must not change inside it (I4): a change of any field closes the trace
 * (`plan`, `plan_revert` or `settings`) and a new segment starts at the next clean boundary. [planHash] null and [planMode] SETTINGS = no plan.
 */
data class TraceInfo(
    val settingsHash: String,
    val planGen: Long,
    val planHash: String?,
    val planMode: PlanMode,
    val tier: BudgetTier,
) {
    /** The same plan (gen, hash, mode), whatever the settings. */
    fun samePlan(o: TraceInfo) = planGen == o.planGen && planHash == o.planHash && planMode == o.planMode
}

/** The effective capture profile and the inputs `onDwell`/`flushWindow` need, as the session decides with them (the `pf` event). */
data class TraceProfile(
    val wake: CameraWake, val settledMs: Long, val quietMs: Long, val walkMs: Long?, val stillCapMs: Long, val analysisMs: Long,
    val tier: BudgetTier, val lowPower: Boolean, val heatSlow: Boolean, val quickLookMs: Long, val ctxCap: Int?, val hard: Boolean,
)

/** Everything a recorder needs at a clean boundary: the replay snapshot, plus the values of the initial `cx rs pf gp pl` events. */
class TraceStart(val snapshot: StartSnapshot, val profile: TraceProfile, val gate: GateParams, val info: TraceInfo)

/**
 * What [com.rokid.context.glasses.capture.session.SamplingSession] reports to the IMU trace recorder. Every method returns at once when no
 * trace is recording (one volatile read), never blocks on I/O and never calls back into the session, the classifier or the gate (lock
 * order: session, classifier and gate, then recorder). Times that have their own meaning travel as fields; the recorder stamps `tMs` itself,
 * inside its append lock.
 */
interface SessionTrace {
    /** A trace is being recorded. */
    val recording: Boolean
    /** Collecting, eligible and no trace running: the session may offer a clean boundary. */
    val wantsStart: Boolean
    /** The downsampling factor a new trace would use (1 = exact). */
    val sampleEvery: Int

    /** Starts a trace at a clean boundary. False when it could not (not wanted any more, the writer is not ready). */
    fun start(s: TraceStart): Boolean
    /** Closes the running trace with a [com.rokid.context.capture.trace.CloseReason] (no-op when none runs). */
    fun close(reason: String)
    /** The effective configuration now: a change against the running trace closes it ([CloseReason.PLAN], `plan_revert` or `settings`). True when it closed one. */
    fun onConfig(info: TraceInfo): Boolean

    fun onContext(state: com.rokid.context.core.model.context.ContextState)
    fun onResting(on: Boolean)
    fun onProfile(p: TraceProfile)
    fun onGateParams(g: GateParams)
    fun onBlocked(reason: String?)
    fun onBackoff(step: Int)
    fun onDark(streak: Int)
    fun onEpisodeOpen(cause: EpisodeCause)
    fun onEpisodeClose(cause: EpisodeCause, firstFrameAtMs: Long?, openedAtMs: Long?, outcome: EpisodeOutcome)
    fun onScore(seq: Long, d: Long, p: Long, blur: Float, passedBlur: Boolean, motion: MotionState, dwellArmed: Boolean, force: Boolean, cap: Int?, hard: Boolean, nowMs: Long)
    fun onGateKeep(seq: Long, encoded: Boolean, keptAtMs: Long?)
    fun onGateSkip(seq: Long, reason: String)
    fun onFlush(reason: String, nowMs: Long)
}

/** The `settingsHash` of a trace: a hash prefix of the settings that shape capture. Any change closes the trace (`settings`); nothing else about the settings is stored. */
object TraceSettingsHash {
    fun of(s: com.rokid.context.core.model.HudSettings): String {
        val text = "${s.captureMode}|${s.quickLookMs}|${s.cameraWake}|${s.workMinutes}|${s.restMinutes}|${s.batteryTargetHours}|${s.pauseWhenNotWorn}|${s.pauseWhenPhoneInUse}|${s.workRestEnabled}" +
            // v0.9.7 A: edited classifier thresholds change behaviour, so they close the trace; with none the text (and every old trace hash) is unchanged
            com.rokid.context.core.model.signals.ClassifierThresholds.hash(s.classifier).let { if (it == "default") "" else "|cls=$it" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    }
}
