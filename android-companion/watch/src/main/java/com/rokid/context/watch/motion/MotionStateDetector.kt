package com.rokid.context.watch.motion

// Provenance: ported from live-fitness-tracker watch/.../motion/MotionStateDetector.kt (copy, adapted: WatchState output, PCA package).

import com.rokid.context.core.model.context.WatchState
import kotlinx.serialization.Serializable

/** What the detector wants sent; the sender turns it into a WatchMotionMessage. */
data class MotionReport(val state: WatchState, val confidence: Float, val changedAtMs: Long)

enum class UserActivity { ASLEEP, EXERCISE, PASSIVE, UNKNOWN }

enum class ExerciseKind { RUN, WALK, BIKE, OTHER }

sealed interface MotionEvent {
    data class Activity(val activity: UserActivity, val exercise: ExerciseKind?, val changedAtMs: Long) : MotionEvent
    data class Steps(val count: Long, val startMs: Long, val endMs: Long) : MotionEvent
    data object Tick : MotionEvent
}

/** Persisted between process deaths. */
@Serializable
data class MotionMemory(
    val activity: UserActivity = UserActivity.UNKNOWN,
    val exercise: ExerciseKind? = null,
    val cadence: Double = 0.0, // steps/min of the latest Steps point
    val lastStepEndMs: Long = 0, // 0 = never saw steps
    val candidate: WatchState? = null,
    val candidateSinceMs: Long = 0,
    val reported: WatchState = WatchState.UNKNOWN,
    val reportedChangedAtMs: Long = 0,
    val reportedConfidence: Float = 0f,
    val lastSentMs: Long = 0,
    val lastSentState: WatchState = WatchState.UNKNOWN, // the state of the last successful send, for the status screen
    val unsent: Boolean = false,
    val pendingCount: Long = 0, // short step points (< MIN_SPAN_MS) summed until they span MIN_SPAN_MS
    val pendingStartMs: Long = 0,
    val pendingEndMs: Long = 0,
)

data class MotionStep(val memory: MotionMemory, val report: MotionReport?)

/** Pure reducer: passive activity, exercise type and step cadence to a held motion state. */
object MotionStateDetector {
    const val HOLD_MS = 30_000L
    const val STEP_STALE_MS = 120_000L
    const val REFRESH_MS = 600_000L
    const val WALK_CADENCE = 60.0
    const val RUN_CADENCE = 140.0
    const val MIN_SPAN_MS = 5_000L
    private const val PENDING_GAP_MS = 30_000L

    fun next(m: MotionMemory, e: MotionEvent, nowMs: Long): MotionStep {
        // 1. apply
        var mem = m
        var stepStart = 0L
        when (e) {
            is MotionEvent.Activity -> mem = mem.copy(activity = e.activity, exercise = e.exercise)
            is MotionEvent.Steps -> {
                if (e.endMs <= mem.lastStepEndMs) return MotionStep(m, null) // out of order or redelivered
                if (mem.pendingCount > 0 && e.startMs < mem.pendingEndMs) return MotionStep(m, null) // overlaps the pending sum
                val joins = mem.pendingCount > 0 && e.startMs - mem.pendingEndMs <= PENDING_GAP_MS
                stepStart = if (joins) mem.pendingStartMs else e.startMs
                val count = e.count + if (joins) mem.pendingCount else 0
                if (e.endMs - stepStart < MIN_SPAN_MS) {
                    return MotionStep(m.copy(pendingCount = count, pendingStartMs = stepStart, pendingEndMs = e.endMs), null)
                }
                mem = mem.copy(
                    cadence = count * 60_000.0 / (e.endMs - stepStart), lastStepEndMs = e.endMs,
                    pendingCount = 0, pendingStartMs = 0, pendingEndMs = 0,
                )
            }
            MotionEvent.Tick -> Unit
        }
        // 2. classify at the evidence time
        val t = if (e is MotionEvent.Steps) e.endMs else nowMs
        val (state, conf) = classify(mem, t)
        // 3. candidate
        if (state != mem.candidate) {
            val since = when {
                e is MotionEvent.Steps -> stepStart
                // only an activity-derived state may use the (possibly old) state-change time
                e is MotionEvent.Activity && (state == WatchState.ASLEEP || mem.activity == UserActivity.EXERCISE) -> e.changedAtMs
                // STILL from stale steps dates from the moment the steps went stale (not earlier than the activity change)
                state == WatchState.STILL && mem.lastStepEndMs != 0L && t - mem.lastStepEndMs > STEP_STALE_MS ->
                    minOf(nowMs, maxOf(mem.lastStepEndMs + STEP_STALE_MS, (e as? MotionEvent.Activity)?.changedAtMs ?: 0L))
                else -> t
            }
            mem = mem.copy(candidate = state, candidateSinceMs = since)
        }
        // 4. change report
        if (t - mem.candidateSinceMs >= HOLD_MS && state != mem.reported) {
            mem = mem.copy(
                reported = state, reportedChangedAtMs = mem.candidateSinceMs, reportedConfidence = conf, unsent = true,
            )
            return MotionStep(mem, MotionReport(state = state, confidence = conf, changedAtMs = mem.candidateSinceMs))
        }
        // 5. refresh and retry
        if (e is MotionEvent.Tick && mem.reported != WatchState.UNKNOWN && (mem.unsent || nowMs - mem.lastSentMs >= REFRESH_MS)) {
            return MotionStep(
                mem,
                MotionReport(state = mem.reported, confidence = mem.reportedConfidence, changedAtMs = mem.reportedChangedAtMs),
            )
        }
        return MotionStep(mem, null)
    }

    /** Folds a batch in order and keeps the last report; a Steps batch whose steps are already stale at [nowMs] is followed by a Tick, so a delayed batch can't leave a stale state. */
    fun fold(m: MotionMemory, events: List<MotionEvent>, nowMs: Long): MotionStep {
        var mem = m
        var report: MotionReport? = null
        for (e in events) {
            val step = next(mem, e, nowMs)
            mem = step.memory
            if (step.report != null) report = step.report
        }
        // The Tick's only job here is the stale-steps -> STILL transition; for fresh steps it would skip the hold.
        if (events.any { it is MotionEvent.Steps } && mem.lastStepEndMs != 0L && nowMs - mem.lastStepEndMs > STEP_STALE_MS) {
            val step = next(mem, MotionEvent.Tick, nowMs)
            mem = step.memory
            if (step.report != null) report = step.report
        }
        return MotionStep(mem, report)
    }

    /** When a pending candidate (different from the reported state) becomes reportable, or null. The shell sets a one-shot alarm for it. */
    fun holdDueAtMs(m: MotionMemory): Long? =
        m.candidate?.takeIf { it != m.reported }?.let { m.candidateSinceMs + HOLD_MS }

    fun markSent(m: MotionMemory, nowMs: Long): MotionMemory = m.copy(lastSentMs = nowMs, lastSentState = m.reported, unsent = false)

    private fun classify(m: MotionMemory, t: Long): Pair<WatchState, Float> = when {
        m.activity == UserActivity.ASLEEP -> WatchState.ASLEEP to 0.9f
        m.activity == UserActivity.EXERCISE -> when (m.exercise) {
            ExerciseKind.RUN -> WatchState.RUNNING to 0.9f
            ExerciseKind.WALK -> WatchState.WALKING to 0.9f
            ExerciseKind.BIKE -> WatchState.CYCLING to 0.9f
            else -> WatchState.EXERCISE to 0.8f
        }
        m.lastStepEndMs == 0L -> WatchState.UNKNOWN to 0f
        t - m.lastStepEndMs > STEP_STALE_MS -> WatchState.STILL to 0.6f
        m.cadence >= RUN_CADENCE -> WatchState.RUNNING to 0.8f
        m.cadence >= WALK_CADENCE -> WatchState.WALKING to 0.8f
        else -> WatchState.STILL to 0.6f
    }
}
