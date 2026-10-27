package com.rokid.context.capture.trace

import com.rokid.context.core.model.CameraWake
import com.rokid.context.core.model.MotionState
import com.rokid.context.core.model.PlanMode
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.glasses.capture.motion.GlanceParams
import com.rokid.context.glasses.capture.session.BudgetTier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** Trace schema version carried by every manifest and by every chunk header (`PCT1`). A change to any record, field order or bound bumps it. */
const val TRACE_SCHEMA = 1

private val TOKEN = Regex("[A-Za-z0-9_:.-]{0,32}")
private val HASH = Regex("[0-9a-f]{1,64}")
private val ID = Regex("[A-Za-z0-9_-]{1,64}")
private val ID_OR_EMPTY = Regex("[A-Za-z0-9_-]{0,64}")
internal fun ms(v: Long, what: String) { require(v in 0..TraceBounds.MAX_MS) { "$what out of range" } }
internal fun token(v: String, what: String) { require(TOKEN.matches(v)) { "$what is not a token" } }
internal fun hash(v: String, what: String) { require(HASH.matches(v)) { "$what is not lower-case hex" } }
internal fun id(v: String, what: String, allowEmpty: Boolean = false) {
    require((if (allowEmpty) ID_OR_EMPTY else ID).matches(v)) { "$what is not an id" }
}
internal fun finite(f: Float, what: String) { require(f.isFinite()) { "$what is not finite" } }

enum class EpisodeCause { TIMER, DWELL, ONSET, NOW, RESV, STREAM }
enum class GateOutcome { KEEP, SKIP }

/** How a camera episode ended (`ep` close). Wire: lower case. Drives `lastBriefAt` and the bind back-off in the scheduler (SamplingSession.endEpisode). */
enum class EpisodeOutcome { OK, OPEN_FAILED, NO_FRAMES, BUSY, BIND, COLD }

/**
 * FROZEN (trace schema 1). Why a trace ended (`cl` reason and `Manifest.closeReason`). Decision (I4): a plan activation or revert, or a settings
 * edit, CLOSES the current trace (reason [PLAN], [PLAN_REVERT], [SETTINGS]) and the recorder starts a new segment with a fresh clean start
 * (new snapshot, `pl`/`gp` events at the new start). So a trace never changes plan or settings by itself; the validator refuses a `pl` or
 * `gp` event after the start and a `pf` whose quickLookMs changes mid-trace. (`pf` may still change for heat or battery.)
 */
object CloseReason {
    const val STOP = "stop"
    const val PAUSE = "pause"
    const val UNPLUG = "unplug"
    const val EPOCH = "epoch"
    const val CAP = "cap"
    const val REBOOT = "reboot"
    const val PLAN = "plan"
    const val PLAN_REVERT = "plan_revert"
    const val SETTINGS = "settings"
    val ALL: Set<String> = linkedSetOf(STOP, PAUSE, UNPLUG, EPOCH, CAP, REBOOT, PLAN, PLAN_REVERT, SETTINGS)
}

/** The five reasons a gate SKIP can carry (KeyframeGate: BLUR, DUPLICATE, THROTTLED, WINDOW, CAPPED). */
val GATE_SKIP_REASONS: Set<String> = linkedSetOf("BLUR", "DUPLICATE", "THROTTLED", "WINDOW", "CAPPED")

/**
 * FROZEN (trace schema 1). One recorded event. Every `tMs` is the glasses `SystemClock.elapsedRealtime()` at the moment the recorder
 * appended (delivered) the event; the recorder appends from a single writer, so `tMs` is non-decreasing over the whole trace (the decoder
 * refuses a regression). Sensor events additionally carry their own sensor time `sMs` (see [Gyro]), which is NOT ordered.
 *
 * Recorder obligation (Task 16, M6): take `elapsedRealtime` INSIDE the append lock, so the stamp order equals the append order across the
 * sensor thread, the camera analysis executor (score listener), the session tick and the link thread. A time that has its own meaning
 * (the gate's `now`, a sensor timestamp, the first frame, the camera open) belongs in a field, never in `tMs`. Records hold numbers, enum names and hash hex only: no audio, photo, text, location or device name (sensor features only).
 *
 * Wire form (see [EventCodec]): one canonical JSON array per event, `["<tag>", tMs, fields in the order declared here]`.
 * Floats travel as their IEEE-754 bits (a signed 32-bit integer), 64-bit hashes as 16 lower-case hex digits, booleans as `true`/`false`.
 */
sealed interface TraceEvent {
    val tMs: Long

    /**
     * `g`: gyroscope, rad/s. [tMs] is the delivery (append) time; [sMs] is the sensor event time in ms (`SensorEvent.timestamp / 1_000_000`),
     * exactly the value the classifier integrates `dt` from (SensorFeed -> MotionClassifier.onGyro). Batched delivery (STILL 0.5 s, LOW 1 s)
     * gives many samples nearly equal `tMs` and distinct `sMs`; `sMs` is per sensor and may be out of order across sensors and across a
     * batch boundary, so it is NOT checked for order (the classifier uses max()). Replay feeds `sMs` to the classifier and `tMs` to the
     * scheduler for the interleaving against ticks, scores and episodes.
     */
    data class Gyro(override val tMs: Long, val sMs: Long, val x: Float, val y: Float, val z: Float) : TraceEvent {
        init { ms(tMs, "tMs"); ms(sMs, "sMs"); finite(x, "x"); finite(y, "y"); finite(z, "z") }
    }

    /** `a`: linear acceleration, m/s^2. [sMs] as for [Gyro]. */
    data class Accel(override val tMs: Long, val sMs: Long, val x: Float, val y: Float, val z: Float) : TraceEvent {
        init { ms(tMs, "tMs"); ms(sMs, "sMs"); finite(x, "x"); finite(y, "y"); finite(z, "z") }
    }

    /** `cx`: the context state the glasses applied (from the phone's `pc_motion_ctx`). */
    data class Ctx(override val tMs: Long, val state: ContextState) : TraceEvent { init { ms(tMs, "tMs") } }

    /** `rs`: WorkRest resting on or off. */
    data class Resting(override val tMs: Long, val on: Boolean) : TraceEvent { init { ms(tMs, "tMs") } }

    /**
     * `pf`: the effective [com.rokid.context.glasses.capture.session.CaptureProfile] inputs after plan, heat and battery rules. [walkMs] null = no
     * walk check. [quickLookMs] is the settings Quick look (`CameraPolicy.holdMs` input). It must not change inside one trace: a settings edit
     * closes the trace ([CloseReason.SETTINGS]).
     *
     * This is the EFFECTIVE profile the session decides with, i.e. AFTER `CaptureProfile.forRest()` when resting (the `rs` event is a separate fact for
     * `onDwell`/cap choice; replay must NOT apply `forRest` again to a `pf`). [ctxCap] (null = none) and [hard] are the effective context cap and hard flag
     * (`ActiveConfig.contextCap(state)` after the plan table), so `capNow = min(glance cap for rest/normal, ctxCap)` and `hardNow` for `onDwell`,
     * `timerHoldUntil` and `flushWindow` (which have no `sc`) are replayable from `snapshot.glance` + `rs` + `pf` without resolving the plan JSON.
     * The recorder appends a new `pf` whenever any of these change (context, rest, heat, battery).
     */
    data class Profile(
        override val tMs: Long, val wake: CameraWake, val settledMs: Long, val quietMs: Long, val walkMs: Long?, val stillCapMs: Long,
        val analysisMs: Long, val tier: BudgetTier, val lowPower: Boolean, val heatSlow: Boolean, val quickLookMs: Long,
        val ctxCap: Int?, val hard: Boolean,
    ) : TraceEvent {
        init {
            ms(tMs, "tMs"); ms(settledMs, "settledMs"); ms(quietMs, "quietMs"); walkMs?.let { ms(it, "walkMs") }; ms(stillCapMs, "stillCapMs"); ms(analysisMs, "analysisMs")
            ms(quickLookMs, "quickLookMs"); ctxCap?.let { require(it in 0..100_000) { "ctxCap" } }
        }
    }

    /** `gp`: the keyframe gate parameters in force. */
    data class GateParams(override val tMs: Long, val hamming: Int, val bigChange: Int, val ringAll: Boolean) : TraceEvent {
        init { ms(tMs, "tMs"); require(hamming in 0..64 && bigChange in 0..64) { "gate params out of range" } }
    }

    /** `pl`: the capture plan in force (gen 0 and hash "0" with mode SETTINGS = no plan). */
    data class Plan(override val tMs: Long, val gen: Long, val configHash: String, val mode: PlanMode) : TraceEvent {
        init { ms(tMs, "tMs"); ms(gen, "gen"); hash(configHash, "configHash") }
    }

    /**
     * `ep`: a camera episode opens (decision) or closes. On an open, [firstFrameAtMs], [openedAtMs] and [outcome] are null. On a close:
     * [firstFrameAtMs] (elapsed) is set once a frame arrived; [openedAtMs] is when the camera actually opened (null if it never did), the
     * start-up sample is `firstFrameAtMs - openedAtMs` (the brief lead p95 ring); [outcome] drives `lastBriefAt` and the busy/bind/cold back-offs.
     */
    data class Episode(
        override val tMs: Long, val open: Boolean, val cause: EpisodeCause, val firstFrameAtMs: Long? = null, val openedAtMs: Long? = null, val outcome: EpisodeOutcome? = null,
    ) : TraceEvent {
        init {
            ms(tMs, "tMs"); firstFrameAtMs?.let { ms(it, "firstFrameAtMs") }; openedAtMs?.let { ms(it, "openedAtMs") }
            if (open) require(firstFrameAtMs == null && openedAtMs == null && outcome == null) { "an open carries no close fields" } else require(outcome != null) { "a close needs an outcome" }
        }
    }

    /**
     * `sc`: one scored frame (blur failures included). [d] and [p] are the 64-bit dHash and pHash; [seq] is the session frame number.
     * [cap] and [hard] are the rate cap handed to `KeyframeGate.offerScored(..., cap, hard, nowMs)` (null = uncapped), so replay needs no
     * context or plan re-derivation. [tMs] is the append time; [nowMs] is the session clock the gate saw (`clock()` at the offer, passed to
     * `offerScored(..., nowMs)`), so replay reproduces rate-cap, throttle and window boundaries exactly. `captureNow` has no event of its own: the
     * request is the `ep` open with cause `now` (when the camera was closed) and the frame it produces is the `sc` with [force] true.
     */
    data class Score(
        override val tMs: Long, val seq: Long, val d: Long, val p: Long, val blur: Float, val passedBlur: Boolean, val motion: MotionState,
        val dwellArmed: Boolean, val force: Boolean, val cap: Int?, val hard: Boolean, val nowMs: Long,
    ) : TraceEvent {
        init { ms(tMs, "tMs"); ms(seq, "seq"); ms(nowMs, "nowMs"); finite(blur, "blur"); cap?.let { require(it in 0..100_000) { "cap" } } }
    }

    /**
     * `gt`: what the gate decided for [seq]: KEEP (reason "") or SKIP (reason one of [GATE_SKIP_REASONS]). The recorder appends it AFTER the encode attempt:
     * [encoded] is false only for a KEEP whose JPEG encode failed (the gate recorded the keep but the session's `lastKeyframeAt`/`keptAt` were NOT updated);
     * it is true for every other event. [keptAtMs] is the session bookkeeping time of a successful keep (`SamplingSession.emit`: `lastKeyframeAt = now`,
     * `keptAt.addLast(now)` and `motion.markPose()` all at this one instant, which is the frame-entry time or, for a flushed winner, the episode-end time):
     * non-null exactly when the outcome is KEEP and [encoded]. It drives the glance-admission keep count and the pose-reset instant (novelty).
     */
    data class Gate(override val tMs: Long, val seq: Long, val outcome: GateOutcome, val reason: String, val encoded: Boolean = true, val keptAtMs: Long? = null) : TraceEvent {
        init {
            ms(tMs, "tMs"); ms(seq, "seq"); token(reason, "reason")
            require(encoded || outcome == GateOutcome.KEEP) { "only a keep can fail to encode" }
            keptAtMs?.let { ms(it, "keptAtMs") }
            require((keptAtMs != null) == (outcome == GateOutcome.KEEP && encoded)) { "keptAtMs is set exactly for an encoded keep" }
            when (outcome) {
                GateOutcome.KEEP -> require(reason.isEmpty()) { "a keep has no reason" }
                GateOutcome.SKIP -> require(reason in GATE_SKIP_REASONS) { "unknown skip reason" }
            }
        }
    }

    /**
     * `fl`: the gate window was flushed ([reason] is a token such as `window`, `timer`, `stop`). [nowMs] is the clock `KeyframeGate.flushWindow()` itself read
     * (`clock()`), the decision time replay must pass to the gate: the append time [tMs] can be later and would flip a throttle boundary.
     */
    data class Flush(override val tMs: Long, val reason: String, val nowMs: Long = tMs) : TraceEvent { init { ms(tMs, "tMs"); token(reason, "reason"); ms(nowMs, "nowMs") } }

    /** `bo`: the still-scene back-off step changed. */
    data class Backoff(override val tMs: Long, val stillStep: Int) : TraceEvent { init { ms(tMs, "tMs"); require(stillStep in 0..32) { "stillStep" } } }

    /** `bl`: capture is blocked (pause, wear gate, camera busy ...); null = unblocked. */
    data class Blocked(override val tMs: Long, val reason: String?) : TraceEvent { init { ms(tMs, "tMs"); reason?.let { token(it, "reason") } } }

    /**
     * `dk`: the consecutive unusable (dark, no copy, blurred-and-settled) brief checks count changed ([streak], 0 after a usable brief frame).
     * `quiet = streak >= 3` is a `CameraPolicy.decide` input; dark frames never reach the gate, so no `sc` records them.
     * The live counter is unbounded but only `>= 3` matters, so the recorder CLAMPS to [MAX_DARK_STREAK] ([clampDarkStreak]) here and in
     * `StartSnapshot.darkStreak`; an unclamped value is refused.
     */
    data class DarkStreak(override val tMs: Long, val streak: Int) : TraceEvent { init { ms(tMs, "tMs"); require(streak in 0..MAX_DARK_STREAK) { "streak" } } }

    /** `pr`: the 10 s pre-roll is over ([TraceBounds.PRE_ROLL_MS] after the start); the simulator discards decisions before it. */
    data class PreRollEnd(override val tMs: Long) : TraceEvent { init { ms(tMs, "tMs") } }

    /** `cl`: the trace ends ([reason] is a non-empty token; the validator requires one of [CloseReason.ALL] and the manifest's closeReason). The last event of a trace. */
    data class Close(override val tMs: Long, val reason: String) : TraceEvent { init { ms(tMs, "tMs"); token(reason, "reason"); require(reason.isNotEmpty()) { "reason" } } }
}

const val MAX_DARK_STREAK = 1_000

/** The recorder's clamp for the unbounded dark-streak counter (only >= 3 matters). */
fun clampDarkStreak(live: Int): Int = live.coerceIn(0, MAX_DARK_STREAK)

/** `sampleEvery` applies only to `g` and `a`; every other event is always recorded. */
val TraceEvent.isSensorSample: Boolean get() = this is TraceEvent.Gyro || this is TraceEvent.Accel

/**
 * The 25 Hz (S66) downsampler: keeps every n-th gyro and every n-th accel sample (separate counters, the first of each is kept) and ALWAYS
 * keeps every other event. [sampleEvery] 1 keeps everything (an exact trace).
 */
class Downsampler(private val sampleEvery: Int) {
    init { require(sampleEvery in 1..StartSnapshot.MAX_SAMPLE_EVERY) { "sampleEvery" } }
    private var g = 0
    private var a = 0
    fun keep(e: TraceEvent): Boolean = when (e) {
        is TraceEvent.Gyro -> g++ % sampleEvery == 0
        is TraceEvent.Accel -> a++ % sampleEvery == 0
        else -> true
    }
}

/** One keyframe-ring entry: the dHash and pHash of a kept frame. */
data class RingHash(val d: Long, val p: Long)

/** The classifier pose accumulated since the last mark (`MotionClassifier.markPose`): feeds `GlanceAdmission` novelty until the next keep. [quietSinceMs] null = -1 in the classifier. */
data class PoseSnapshot(
    val rx: Float, val ry: Float, val rz: Float, val walked: Boolean, val qx: Float, val qy: Float, val qz: Float, val quietSinceMs: Long?,
) {
    init { listOf(rx, ry, rz, qx, qy, qz).forEach { finite(it, "pose") }; quietSinceMs?.let { ms(it, "quietSinceMs") } }
    companion object { val ZERO = PoseSnapshot(0f, 0f, 0f, false, 0f, 0f, 0f, null) }
}

/**
 * FROZEN (trace schema 1). The state the recorder captures at the clean boundary, so an exact replay starts from the same state.
 * Clean boundary (S65): the recorder starts only when the detector is QUIET, no camera episode is open and the gate window is empty;
 * the first 10 s are a pre-roll ([TraceBounds.PRE_ROLL_MS]) that refills the classifier windows. Capture is also UNBLOCKED at the start (no pause, wear gate or
 * camera-busy block): the recorder starts only then, so there is no initial `bl`; a later block or unblock is a `bl` event. The recorder must also append `Ctx`,
 * `Resting`, `Profile`, `GateParams` and `Plan` events at the start time (`tMs == startElapsedMs`, checked by [TraceAssembler]), so a replay needs
 * nothing but this snapshot and the events. All times are glasses elapsed ms of the trace's boot.
 *
 * Which values (pinned, C2): [lastKeyframeAt] and [keptAt] are the SESSION's values (`SamplingSession.lastKeyframeAt`, `keptAt`: the 10 min list of
 * emitted keyframes, trimmed to the last minute here); [gateLastKeptAt] and [gateKeptAt] are the GATE's (`GateStore.lastKeptAt`, `KeyframeGate.keptTimes`).
 * They differ after an encode failure, a forced keep or a session restart, so both are recorded. The scheduler start state follows
 * `SamplingSession`: [lastBriefAt], [stillStep], [lastCheckHash], [darkStreak], [bindBackoffUntil], [coldStartGrace], the brief-lead ring [startups]
 * (the last 20 `firstFrameAt - openedAt` ms) and [lastPhotoAttemptAtMs] (the S83/S70 reserved attempt).
 */
data class StartSnapshot(
    /** Hash prefix of the effective settings (hex). */
    val settingsHash: String,
    /** The keyframe ring, newest last, at most 5. */
    val ring: List<RingHash>,
    /** Session `lastKeyframeAt` (elapsed), or null before any. */
    val lastKeyframeAt: Long?,
    /** Session `keptAt` within the last rolling minute, ascending, at most 64. */
    val keptAt: List<Long>,
    /** Gate store `lastKeptAt`, or null (0 in the store). */
    val gateLastKeptAt: Long?,
    /** Gate `keptTimes` within the last rolling minute, ascending, at most 64. */
    val gateKeptAt: List<Long>,
    /** `SamplingSession.lastBriefAt` (it decides the first timer open), or null. */
    val lastBriefAt: Long?,
    /** The still-scene back-off step (0..32). */
    val stillStep: Int,
    /** `lastCheckHash` (dHash, pHash of the previous brief check), or null. */
    val lastCheckHash: RingHash?,
    /** Consecutive unusable brief checks (quiet when >= 3), clamped to [MAX_DARK_STREAK] by the recorder ([clampDarkStreak]). */
    val darkStreak: Int,
    /** `bindBackoffUntil`, or null when none is pending. */
    val bindBackoffUntil: Long?,
    val coldStartGrace: Boolean,
    /** `bindFailures` (consecutive; the session latches the Fixed fallback at its limit), 0..[MAX_BIND_FAILURES]. */
    val bindFailures: Int,
    /** The classifier's previous gyro sensor time (`lastGyroMs`, the `dt` source for the first sample after the start), or null before any. */
    val prevGyroMs: Long?,
    /** The brief-lead ring, oldest first, at most [MAX_STARTUPS]. */
    val startups: List<Long>,
    val pose: PoseSnapshot,
    val lastPhotoAttemptAtMs: Long?,
    val planGen: Long,
    /** The plan `configHash` in force, or null for settings. */
    val planHash: String?,
    val glance: GlanceParams,
    val ctx: ContextState,
    val resting: Boolean,
    val tier: BudgetTier,
    /** 1 = every sensor sample is recorded; n > 1 = every n-th sample (S66, see [Downsampler]). */
    val sampleEvery: Int,
    /** `sampleEvery == 1`. Only an exact trace may be parity or publish evidence. */
    val exact: Boolean,
    /**
     * v0.9.7 A, additive and optional (TRACE_SCHEMA stays 1): the classifier threshold OVERRIDES in force (canonical, see [com.rokid.context.core.model.signals.ClassifierThresholds.clean]);
     * empty = the defaults, which is every older trace. Replay restores them so a simulated classifier is the one that ran.
     */
    val classifier: Map<String, Double> = emptyMap(),
) {
    init {
        require(classifier == com.rokid.context.core.model.signals.ClassifierThresholds.clean(classifier)) { "classifier not canonical" }
        hash(settingsHash, "settingsHash")
        require(ring.size <= MAX_RING) { "ring too long" }
        lastKeyframeAt?.let { ms(it, "lastKeyframeAt") }
        require(keptAt.size <= MAX_KEPT) { "keptAt too long" }
        keptAt.forEach { ms(it, "keptAt") }
        require(keptAt.zipWithNext().all { (a, b) -> a <= b }) { "keptAt not ascending" }
        gateLastKeptAt?.let { ms(it, "gateLastKeptAt") }
        require(gateKeptAt.size <= MAX_KEPT) { "gateKeptAt too long" }
        gateKeptAt.forEach { ms(it, "gateKeptAt") }
        require(gateKeptAt.zipWithNext().all { (a, b) -> a <= b }) { "gateKeptAt not ascending" }
        lastBriefAt?.let { ms(it, "lastBriefAt") }
        require(stillStep in 0..32) { "stillStep" }
        require(darkStreak in 0..MAX_DARK_STREAK) { "darkStreak" }
        bindBackoffUntil?.let { ms(it, "bindBackoffUntil") }
        require(bindFailures in 0..MAX_BIND_FAILURES) { "bindFailures" }
        prevGyroMs?.let { ms(it, "prevGyroMs") }
        require(startups.size <= MAX_STARTUPS) { "startups too long" }
        startups.forEach { ms(it, "startups") }
        lastPhotoAttemptAtMs?.let { ms(it, "lastPhotoAttemptAtMs") }
        ms(planGen, "planGen"); planHash?.let { hash(it, "planHash") }
        listOf(glance.onsetRadS, glance.releaseRadS, glance.minBoutRad, glance.deadbandRadS, glance.noveltyMinRad, glance.bigTurnRad).forEach { finite(it, "glance") }
        listOf(glance.bridgeMs, glance.minBoutMs, glance.settleMs, glance.maxDtMs, glance.noveltyWindowMs).forEach { ms(it, "glance") }
        require(glance.capPerMin in 0..100_000 && glance.restCapPerMin in 0..100_000) { "glance cap" }
        require(sampleEvery in 1..MAX_SAMPLE_EVERY) { "sampleEvery" }
        require(exact == (sampleEvery == 1)) { "exact must equal sampleEvery == 1" }
    }

    companion object { const val MAX_RING = 5; const val MAX_KEPT = 64; const val MAX_STARTUPS = 20; const val MAX_BIND_FAILURES = 100; const val MAX_SAMPLE_EVERY = 16 }
}

/**
 * FROZEN (trace schema 1). Canonical one-line encoding of an event and its strict inverse. Tags and field order:
 * The field list of every tag is [FIELDS] (frozen, tested), and the tag list is [TAGS] (16 tags). Floats are IEEE bits, 64-bit hashes hex16,
 * enum values their Kotlin names (upper case) except `ep` cause and outcome and `gt` outcome (lower case).
 */
object EventCodec {
    /** The frozen tag list, in declaration order. */
    val TAGS: List<String> = listOf("g", "a", "cx", "rs", "pf", "gp", "pl", "ep", "sc", "gt", "fl", "bo", "bl", "dk", "pr", "cl")

    /** The frozen field names per tag (after the tag and `tMs`), in wire order. */
    val FIELDS: Map<String, List<String>> = linkedMapOf(
        "g" to listOf("sMs", "x", "y", "z"), "a" to listOf("sMs", "x", "y", "z"), "cx" to listOf("state"), "rs" to listOf("on"),
        "pf" to listOf("wake", "settledMs", "quietMs", "walkMs", "stillCapMs", "analysisMs", "tier", "lowPower", "heatSlow", "quickLookMs", "ctxCap", "hard"),
        "gp" to listOf("hamming", "bigChange", "ringAll"), "pl" to listOf("gen", "configHash", "mode"),
        "ep" to listOf("open", "cause", "firstFrameAtMs", "openedAtMs", "outcome"),
        "sc" to listOf("seq", "d", "p", "blur", "passedBlur", "motion", "dwellArmed", "force", "cap", "hard", "nowMs"),
        "gt" to listOf("seq", "outcome", "reason", "encoded", "keptAtMs"), "fl" to listOf("reason", "nowMs"), "bo" to listOf("stillStep"), "bl" to listOf("reason"),
        "dk" to listOf("streak"), "pr" to emptyList(), "cl" to listOf("reason"),
    )

    fun tag(e: TraceEvent): String = when (e) {
        is TraceEvent.Gyro -> "g"; is TraceEvent.Accel -> "a"; is TraceEvent.Ctx -> "cx"; is TraceEvent.Resting -> "rs"; is TraceEvent.Profile -> "pf"
        is TraceEvent.GateParams -> "gp"; is TraceEvent.Plan -> "pl"; is TraceEvent.Episode -> "ep"; is TraceEvent.Score -> "sc"; is TraceEvent.Gate -> "gt"
        is TraceEvent.Flush -> "fl"; is TraceEvent.Backoff -> "bo"; is TraceEvent.Blocked -> "bl"; is TraceEvent.DarkStreak -> "dk"
        is TraceEvent.PreRollEnd -> "pr"; is TraceEvent.Close -> "cl"
    }

    /** `-?(0|[1-9][0-9]{0,15})` (the canonical integer text), without a regex: the decode runs per field of every event. */
    private fun isInt(s: String): Boolean {
        var i = if (s.isNotEmpty() && s[0] == '-') 1 else 0
        val digits = s.length - i
        if (digits < 1 || digits > 16) return false
        if (s[i] == '0') return digits == 1
        while (i < s.length) { if (s[i] !in '0'..'9') return false; i++ }
        return true
    }
    /** `[0-9a-f]{16}` without a regex. */
    private fun isHex16(s: String): Boolean {
        if (s.length != 16) return false
        for (c in s) if (c !in '0'..'9' && c !in 'a'..'f') return false
        return true
    }
    private fun bad(): Nothing = throw TraceFormatException("bad_event")

    fun encode(e: TraceEvent): String {
        val sb = StringBuilder(64)
        fun head(tag: String) { sb.append("[\"").append(tag).append("\",").append(e.tMs) }
        fun num(v: Long) { sb.append(',').append(v) }
        fun bits(f: Float) { sb.append(',').append(f.toRawBits()) }
        fun str(v: String) { sb.append(",\"").append(v).append('"') }
        fun flag(v: Boolean) { sb.append(',').append(v) }
        fun nul(v: Long?) { if (v == null) sb.append(",null") else num(v) }
        fun hex(v: Long) { str(java.lang.Long.toHexString(v).padStart(16, '0')) }
        when (e) {
            is TraceEvent.Gyro -> { head("g"); num(e.sMs); bits(e.x); bits(e.y); bits(e.z) }
            is TraceEvent.Accel -> { head("a"); num(e.sMs); bits(e.x); bits(e.y); bits(e.z) }
            is TraceEvent.Ctx -> { head("cx"); str(e.state.name) }
            is TraceEvent.Resting -> { head("rs"); flag(e.on) }
            is TraceEvent.Profile -> {
                head("pf"); str(e.wake.name); num(e.settledMs); num(e.quietMs); nul(e.walkMs); num(e.stillCapMs); num(e.analysisMs)
                str(e.tier.name); flag(e.lowPower); flag(e.heatSlow); num(e.quickLookMs); nul(e.ctxCap?.toLong()); flag(e.hard)
            }
            is TraceEvent.GateParams -> { head("gp"); num(e.hamming.toLong()); num(e.bigChange.toLong()); flag(e.ringAll) }
            is TraceEvent.Plan -> { head("pl"); num(e.gen); str(e.configHash); str(e.mode.name) }
            is TraceEvent.Episode -> { head("ep"); flag(e.open); str(e.cause.name.lowercase()); nul(e.firstFrameAtMs); nul(e.openedAtMs)
                if (e.outcome == null) sb.append(",null") else str(e.outcome.name.lowercase())
            }
            is TraceEvent.Score -> {
                head("sc"); num(e.seq); hex(e.d); hex(e.p); bits(e.blur); flag(e.passedBlur); str(e.motion.name); flag(e.dwellArmed); flag(e.force); nul(e.cap?.toLong()); flag(e.hard); num(e.nowMs)
            }
            is TraceEvent.Gate -> { head("gt"); num(e.seq); str(e.outcome.name.lowercase()); str(e.reason); flag(e.encoded); nul(e.keptAtMs) }
            is TraceEvent.Flush -> { head("fl"); str(e.reason); num(e.nowMs) }
            is TraceEvent.Backoff -> { head("bo"); num(e.stillStep.toLong()) }
            is TraceEvent.Blocked -> { head("bl"); if (e.reason == null) sb.append(",null") else str(e.reason) }
            is TraceEvent.DarkStreak -> { head("dk"); num(e.streak.toLong()) }
            is TraceEvent.PreRollEnd -> head("pr")
            is TraceEvent.Close -> { head("cl"); str(e.reason) }
        }
        return sb.append(']').toString()
    }

    /** Strict: only a line that re-encodes to exactly itself is accepted (no whitespace, `+1`, `-0`, `1.0` or differing escapes). Throws [TraceFormatException]. */
    fun decode(line: String): TraceEvent {
        decodeCount.incrementAndGet()
        if (line.length > TraceBounds.MAX_LINE_CHARS) bad()
        val e = try { parse(line) } catch (x: TraceFormatException) { throw x } catch (x: Exception) { bad() }
        if (encode(e) != line) bad()
        return e
    }

    /** Strict [decode] calls so far in this process (a diagnostic: a test asserts that crash recovery, which must only scan, decodes none). */
    val decodeCount = java.util.concurrent.atomic.AtomicLong()

    private val NAMES_CTX = ContextState.values().map { it.name }.toSet()
    private val NAMES_WAKE = CameraWake.values().map { it.name }.toSet()
    private val NAMES_TIER = BudgetTier.values().map { it.name }.toSet()
    private val NAMES_PLAN = PlanMode.values().map { it.name }.toSet()
    private val NAMES_MOTION = MotionState.values().map { it.name }.toSet()
    private val NAMES_CAUSE = EpisodeCause.values().map { it.name.lowercase() }.toSet()
    private val NAMES_OUTCOME = EpisodeOutcome.values().map { it.name.lowercase() }.toSet()
    private val NAMES_GATE = GateOutcome.values().map { it.name.lowercase() }.toSet()

    /** A cursor over one line `text[pos, end)` for [scanLine]: every read validates one field exactly as [parse] plus the event constructors do, and builds nothing. */
    private class Scan(val text: String, var pos: Int, val end: Int) {
        fun comma() { if (pos >= end - 1 || text[pos] != ',') throw TraceFormatException("bad_event"); pos++ }
        private fun isNull(): Boolean = if (text.startsWith("null", pos) && endsField(pos + 4)) { pos += 4; true } else false
        /** A canonical integer (`-?(0|[1-9][0-9]{0,15})`, and not `-0`, which no encoder writes). */
        fun long(): Long {
            val neg = pos < end && text[pos] == '-'
            if (neg) pos++
            val ds = pos
            var v = 0L
            while (pos < end && text[pos] in '0'..'9') { v = v * 10 + (text[pos] - '0'); pos++ }
            val n = pos - ds
            if (n < 1 || n > 16 || (n > 1 && text[ds] == '0') || (neg && v == 0L)) throw TraceFormatException("bad_event")
            if (pos >= end || (text[pos] != ',' && text[pos] != ']')) throw TraceFormatException("bad_event")
            return if (neg) -v else v
        }
        fun ms(): Long = long().also { if (it !in 0..TraceBounds.MAX_MS) throw TraceFormatException("bad_event") }
        fun msOrNull(): Long? = if (isNull()) null else ms()
        fun intIn(lo: Int, hi: Int): Int = long().also { if (it < lo || it > hi) throw TraceFormatException("bad_event") }.toInt()
        fun intInOrNull(lo: Int, hi: Int): Int? = if (isNull()) null else intIn(lo, hi)
        fun float() { val f = Float.fromBits(intIn(Int.MIN_VALUE, Int.MAX_VALUE)); if (!f.isFinite()) throw TraceFormatException("bad_event") }
        fun bool(): Boolean = when {
            text.startsWith("true", pos) && endsField(pos + 4) -> { pos += 4; true }
            text.startsWith("false", pos) && endsField(pos + 5) -> { pos += 5; false }
            else -> throw TraceFormatException("bad_event")
        }
        private fun endsField(at: Int) = at < end && (text[at] == ',' || text[at] == ']')
        /** A quoted string with no escape and no control character (so it re-encodes to itself); returns its text. */
        fun str(): String {
            if (pos >= end || text[pos] != '"') throw TraceFormatException("bad_event")
            val s = pos + 1
            var i = s
            while (i < end && text[i] != '"') { val c = text[i]; if (c == '\\' || c < ' ') throw TraceFormatException("bad_event"); i++ }
            if (i >= end || !endsField(i + 1)) throw TraceFormatException("bad_event")
            pos = i + 1
            return text.substring(s, i)
        }
        fun enum(names: Set<String>): String = str().also { if (it !in names) throw TraceFormatException("bad_event") }
        fun enumOrNull(names: Set<String>): String? = if (isNull()) null else enum(names)
        fun hex16() { if (!isHex16(str())) throw TraceFormatException("bad_event") }
        /** `[A-Za-z0-9_:.-]{0,32}` (or 1..32) by character checks, no regex. */
        fun token(allowEmpty: Boolean = true): String = str().also { s ->
            if (s.length > 32 || (!allowEmpty && s.isEmpty())) throw TraceFormatException("bad_event")
            for (ch in s) if (ch !in 'a'..'z' && ch !in 'A'..'Z' && ch !in '0'..'9' && ch != '_' && ch != ':' && ch != '.' && ch != '-') throw TraceFormatException("bad_event")
        }
        fun tokenOrNull(): String? = if (isNull()) null else token()
        /** `[0-9a-f]{1,64}` by character checks, no regex. */
        fun hash(): String = str().also { s ->
            if (s.isEmpty() || s.length > 64) throw TraceFormatException("bad_event")
            for (ch in s) if (ch !in '0'..'9' && ch !in 'a'..'f') throw TraceFormatException("bad_event")
        }
    }

    /**
     * Validates the event line `text[from, to)` in place and returns its `tMs`: the same grammar, field count, canonical values, enum names and bounds that
     * [decode] enforces (it does not build the event, use a regex per field, parse a JSON tree or re-encode; the cross-field rules of `ep` and `gt` are checked too).
     * Throws [TraceFormatException] (`bad_event`). For a trace this process wrote (crash recovery) where the chunk checksum already vouches for the bytes: [decode] stays the importer.
     */
    fun scanLine(text: String, from: Int, to: Int): Long {
        if (to - from < 8 || to - from > TraceBounds.MAX_LINE_CHARS || text[from] != '[' || text[from + 1] != '"' || text[to - 1] != ']') bad()
        var i = from + 2
        val tagStart = i
        while (i < to && text[i] != '"') i++
        if (i >= to - 1 || text[i + 1] != ',') bad()
        val tl = i - tagStart
        val tag = TAGS.firstOrNull { it.length == tl && text.startsWith(it, tagStart) } ?: bad()
        val c = Scan(text, i + 2, to)
        val t = c.ms()
        fun more() = c.comma()
        when (tag) {
            "g", "a" -> { more(); c.ms(); more(); c.float(); more(); c.float(); more(); c.float() }
            "cx" -> { more(); c.enum(NAMES_CTX) }
            "rs" -> { more(); c.bool() }
            "pf" -> {
                more(); c.enum(NAMES_WAKE); more(); c.ms(); more(); c.ms(); more(); c.msOrNull(); more(); c.ms(); more(); c.ms()
                more(); c.enum(NAMES_TIER); more(); c.bool(); more(); c.bool(); more(); c.ms(); more(); c.intInOrNull(0, 100_000); more(); c.bool()
            }
            "gp" -> { more(); c.intIn(0, 64); more(); c.intIn(0, 64); more(); c.bool() }
            "pl" -> { more(); c.ms(); more(); c.hash(); more(); c.enum(NAMES_PLAN) }
            "ep" -> {
                more(); val open = c.bool(); more(); c.enum(NAMES_CAUSE); more(); val ff = c.msOrNull(); more(); val op = c.msOrNull(); more(); val out = c.enumOrNull(NAMES_OUTCOME)
                if (open) { if (ff != null || op != null || out != null) bad() } else if (out == null) bad()
            }
            "sc" -> {
                more(); c.ms(); more(); c.hex16(); more(); c.hex16(); more(); c.float(); more(); c.bool(); more(); c.enum(NAMES_MOTION); more(); c.bool(); more(); c.bool()
                more(); c.intInOrNull(0, 100_000); more(); c.bool(); more(); c.ms()
            }
            "gt" -> {
                more(); c.ms(); more(); val out = c.enum(NAMES_GATE); more(); val reason = c.token(); more(); val encoded = c.bool(); more(); val kept = c.msOrNull()
                val keep = out == "keep"
                if (!encoded && !keep) bad()
                if ((kept != null) != (keep && encoded)) bad()
                if (keep) { if (reason.isNotEmpty()) bad() } else if (reason !in GATE_SKIP_REASONS) bad()
            }
            "fl" -> { more(); c.token(); more(); c.ms() }
            "bo" -> { more(); c.intIn(0, 32) }
            "bl" -> { more(); c.tokenOrNull() }
            "dk" -> { more(); c.intIn(0, MAX_DARK_STREAK) }
            "pr" -> {}
            "cl" -> { more(); c.token(allowEmpty = false) }
        }
        if (c.pos != to - 1) bad() // exactly the closing bracket is left: no missing or extra field
        return t
    }

    private fun parse(line: String): TraceEvent {
        val a = kotlinx.serialization.json.Json.parseToJsonElement(line) as? JsonArray ?: bad()
        if (a.size < 2) bad()
        var i = 0
        fun next(): JsonElement = if (i < a.size) a[i++] else bad()
        fun prim(): JsonPrimitive = next() as? JsonPrimitive ?: bad()
        fun long(): Long { val p = prim(); if (p is JsonNull || p.isString || !isInt(p.content)) bad(); return p.content.toLong() }
        fun int(): Int = long().also { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) bad() }.toInt()
        fun bool(): Boolean { val p = prim(); if (p is JsonNull || p.isString) bad(); return when (p.content) { "true" -> true; "false" -> false; else -> bad() } }
        fun str(): String { val p = prim(); if (p is JsonNull || !p.isString) bad(); return p.content }
        fun strOrNull(): String? { val p = prim(); return if (p is JsonNull) null else { if (!p.isString) bad(); p.content } }
        fun longOrNull(): Long? { val p = prim(); return if (p is JsonNull) null else { if (p.isString || !isInt(p.content)) bad(); p.content.toLong() } }
        fun float(): Float = Float.fromBits(int())
        fun hex(): Long { val s = str(); if (!isHex16(s)) bad(); return java.lang.Long.parseUnsignedLong(s, 16) }
        fun <T : Enum<T>> en(values: Array<T>, lower: Boolean = false): T { val s = str(); return values.firstOrNull { (if (lower) it.name.lowercase() else it.name) == s } ?: bad() }
        fun <T : Enum<T>> enOrNull(values: Array<T>): T? { val p = prim(); if (p is JsonNull) return null; if (!p.isString) bad(); return values.firstOrNull { it.name.lowercase() == p.content } ?: bad() }
        val tag = str()
        val t = long()
        val ev: TraceEvent = when (tag) {
            "g" -> TraceEvent.Gyro(t, long(), float(), float(), float())
            "a" -> TraceEvent.Accel(t, long(), float(), float(), float())
            "cx" -> TraceEvent.Ctx(t, en(ContextState.values()))
            "rs" -> TraceEvent.Resting(t, bool())
            "pf" -> TraceEvent.Profile(t, en(CameraWake.values()), long(), long(), longOrNull(), long(), long(), en(BudgetTier.values()), bool(), bool(), long(), longOrNull()?.also { if (it !in 0..100_000) bad() }?.toInt(), bool())
            "gp" -> TraceEvent.GateParams(t, int(), int(), bool())
            "pl" -> TraceEvent.Plan(t, long(), str(), en(PlanMode.values()))
            "ep" -> TraceEvent.Episode(t, bool(), en(EpisodeCause.values(), lower = true), longOrNull(), longOrNull(), enOrNull(EpisodeOutcome.values()))
            "sc" -> TraceEvent.Score(t, long(), hex(), hex(), float(), bool(), en(MotionState.values()), bool(), bool(), longOrNull()?.also { if (it !in 0..100_000) bad() }?.toInt(), bool(), long())
            "gt" -> TraceEvent.Gate(t, long(), en(GateOutcome.values(), lower = true), str(), bool(), longOrNull())
            "fl" -> TraceEvent.Flush(t, str(), long())
            "bo" -> TraceEvent.Backoff(t, int())
            "bl" -> TraceEvent.Blocked(t, strOrNull())
            "dk" -> TraceEvent.DarkStreak(t, int())
            "pr" -> TraceEvent.PreRollEnd(t)
            "cl" -> TraceEvent.Close(t, str())
            else -> bad()
        }
        if (i != a.size) bad()
        return ev
    }
}
