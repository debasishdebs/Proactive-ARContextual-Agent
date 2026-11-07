package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState

/** What a wake may consult (cheap reads): the battery exemption, the AutoStop minutes, link machinery allowed, the hub service up. */
data class WakeInputs(val exempt: Boolean, val minutes: Int, val linkAllowed: Boolean, val serviceRunning: Boolean)

/** What the live hub reports (cheap in-memory reads): running, link, wear, our app in front / last in front, an ask or a Run in flight. */
data class HubSignals(
    val hubRunning: Boolean, val link: LinkState, val worn: Boolean?, val inFront: Boolean, val lastFrontMs: Long, val busy: Boolean, val runActive: Boolean,
)

/**
 * A stop that is decided and committed (the mark, which already carries the reason and the cleared probe) but not yet executed: [AutoStopEvent.ConfirmStop]
 * runs it or cancels it, reverting the mark to the [prevNextWakeAtMs] / [prevProbe] it replaced.
 */
data class PendingStop(val reason: BackgroundPolicy.Away, val sinceMs: Long, val atMs: Long, val prevNextWakeAtMs: Long, val prevProbe: Boolean)

/** A wake whose start request is in flight; [controller] = the live controller claimed it (else a process start / the worker). */
data class WakeClaim(val via: String, val atMs: Long, val controller: Boolean)

/** The AutoStop state: [saved] is the persisted part (exactly what [AutoStopStore] holds after each step); the rest is the process's working memory. */
data class AutoStopState(
    val saved: AutoStopSnapshot = AutoStopSnapshot(),
    val wake: WakeInputs? = null,
    val hub: HubSignals? = null,
    /** When the current away window started (null = unknown: the next look starts it; present, or the hub not running). */
    val awaySince: Long? = null,
    val pendingStop: PendingStop? = null,
    val claim: WakeClaim? = null,
    /** The last save failed and none has succeeded since: the safe behaviour applies (see [AutoStopEngine.visible]). */
    val persistFailing: Boolean = false,
    /** When `persist_failed` was last logged (at most once a minute). */
    val persistLoggedAtMs: Long? = null,
)

sealed interface AutoStopEvent {
    /** The controller looks (a poke: link/wear change, app in front or left, a setting changed; or its timer). Null inputs are sampled when it is reduced. */
    data class Evaluate(val wake: WakeInputs? = null, val hub: HubSignals? = null) : AutoStopEvent
    /** The re-validation of a committed stop, from the inputs as they are AFTER the commit (sampled again). */
    data class ConfirmStop(val wake: WakeInputs? = null, val hub: HubSignals? = null) : AutoStopEvent
    /** A process start ([AutoStopWake]: Application.onCreate, the one-shot wake worker): a due wake may start the hub, an interrupted one is recovered. */
    data class ProcessStarted(val via: String, val wake: WakeInputs? = null) : AutoStopEvent
    /** Feedback of [AutoStopEffect.StartWake]: the start request was issued / the service is up after it. */
    data class WakeResult(val issued: Boolean, val serviceRunning: Boolean) : AutoStopEvent
    /** The hub service entered the foreground ([ContextHubService]): a wake in progress is complete. */
    object HubForeground : AutoStopEvent
    /** A user start (app opened, Reconnect tapped): the manual stop, the auto stop, the ladder, a pending probe and the away window are all over. */
    object UserStarted : AutoStopEvent
    /** The user stopped the hub service by hand. */
    object ManualStopped : AutoStopEvent
    /** The save of [event]'s transition failed ([attempt] retries so far): nothing of it happened. */
    data class PersistFailed(val event: AutoStopEvent, val attempt: Int) : AutoStopEvent
    /** A save succeeded again after a failure. */
    object PersistRecovered : AutoStopEvent
}

sealed interface AutoStopEffect {
    /** The status line: [reason] null = none. */
    data class Note(val reason: BackgroundPolicy.Away?, val sinceMs: Long = 0L) : AutoStopEffect
    data class StopHub(val reason: BackgroundPolicy.Away) : AutoStopEffect
    data class StartWake(val via: String, val controller: Boolean) : AutoStopEffect
    data class ScheduleWake(val delayMs: Long, val controller: Boolean) : AutoStopEffect
    /** A fixed log line ([Line]), with the wake entry point [via] where it applies. */
    data class Log(val line: Line, val via: String = "") : AutoStopEffect
    enum class Line { WAKE_ATTEMPT, WAKE_ALREADY_RUNNING, WAKE_REFUSED, WAKE_TIMED_OUT, PERSIST_FAILED }
}

/** Replies: the controller's next sleep (null = until the next poke), or a process start's claim. */
data class AutoStopSleep(val ms: Long?)
data class AutoStopClaimed(val claim: AutoStopWake.Claim)

private typealias T = Transition<AutoStopState, AutoStopEvent, AutoStopEffect>

/**
 * The PURE AutoStop reducer (Codex reviews 4 and 5): (state, event, now) -> (state', effects). No clock, no IO, no locks: [SerialStateMachine] runs it one event
 * at a time, commits [AutoStopState.saved] before the effects, and re-validates a stop with [AutoStopEvent.ConfirmStop] queued behind every input that arrived
 * during the commit. [AutoStopEffect.StopHub] is emitted ONLY by a confirm step that changes nothing persisted (so no commit, and no input, can come between the
 * decision over the latest inputs and the stop).
 */
object AutoStopMachine {
    private val MAX_RUNG = BackgroundPolicy.WAKE_LADDER_MS.last()
    const val CONTROLLER = "controller"
    /** A wake whose start was issued but whose service never reported the foreground is over after this; the ladder's next rung stands. */
    const val WAKE_START_TIMEOUT_MS = 2 * 60_000L
    /** Delays of the retries of an event whose save failed (bounded: after the last one the safe behaviour stays until a save succeeds). */
    val PERSIST_RETRY_MS: List<Long> = listOf(250L, 1_000L, 4_000L, 15_000L, 60_000L)
    const val PERSIST_LOG_EVERY_MS = 60_000L

    fun reduce(s: AutoStopState, e: AutoStopEvent, now: Long): T = when (e) {
        is AutoStopEvent.Evaluate -> evaluate(s.copy(wake = e.wake ?: s.wake, hub = e.hub ?: s.hub), now)
        is AutoStopEvent.ConfirmStop -> confirm(s.copy(wake = e.wake ?: s.wake, hub = e.hub ?: s.hub), now)
        is AutoStopEvent.ProcessStarted -> processStart(s.copy(wake = e.wake ?: s.wake), e.via, now)
        is AutoStopEvent.WakeResult -> wakeResult(s, e, now)
        AutoStopEvent.HubForeground -> if (s.saved.wakeStartedAtMs == 0L) T(s) else T(cleared(s), listOf(AutoStopEffect.Note(null)))
        // A user start: the full configured N applies again (the probe's synthetic away start goes with the probe).
        AutoStopEvent.UserStarted -> T(
            s.copy(
                saved = s.saved.copy(manualStop = false, autoStoppedAtMs = 0L, reason = null, awaySinceMs = 0L, wakeStep = 0, nextWakeAtMs = 0L, wakeProbe = false, wakeStartedAtMs = 0L),
                pendingStop = null, awaySince = null,
            ),
            listOf(AutoStopEffect.Note(null)),
        )
        AutoStopEvent.ManualStopped -> T(s.copy(saved = s.saved.copy(manualStop = true)))
        is AutoStopEvent.PersistFailed -> persistFailed(s, e, now)
        AutoStopEvent.PersistRecovered -> T(s.copy(persistFailing = false))
    }

    /** The status line for [s]: only a stop that really happened (not one being committed, not one a wake is ending). */
    fun note(s: AutoStopState): AutoStopEffect.Note {
        val why = s.saved.reason
        if (s.pendingStop != null || s.saved.autoStoppedAtMs == 0L || s.saved.wakeStartedAtMs != 0L || why == null) return AutoStopEffect.Note(null)
        return AutoStopEffect.Note(why, s.saved.awaySinceMs.takeIf { it > 0 } ?: s.saved.autoStoppedAtMs)
    }

    private fun cleared(x: AutoStopState) = x.copy(saved = x.saved.copy(autoStoppedAtMs = 0L, reason = null, awaySinceMs = 0L, wakeStartedAtMs = 0L))

    private fun evaluate(s0: AutoStopState, t: Long): T {
        val w = s0.wake; val h = s0.hub
        if (w == null || h == null) return T(s0, reply = AutoStopSleep(null))
        // The hub runs: a wake in progress reached it (the foreground report may still be queued).
        val s = if (h.hubRunning && s0.saved.wakeStartedAtMs != 0L) cleared(s0) else s0
        val fx = mutableListOf<AutoStopEffect>(note(s))
        if (!h.hubRunning) {
            var s1 = cancel(s).copy(awaySince = null) // the hub stopped some other way while a stop was being committed: that stop is void
            if (s1.saved.autoStoppedAtMs == 0L) return T(s1, fx, reply = AutoStopSleep(null))
            if (s1.saved.wakeStartedAtMs != 0L) {
                if (!wakeExpired(s1, t)) return T(s1, fx, reply = AutoStopSleep(wakeLeft(s1, t)))
                val (s2, tfx) = timedOut(s1, t); s1 = s2; fx += tfx
            }
            if (BackgroundPolicy.wakeDue(w.exempt, true, s1.saved.manualStop, w.minutes, false, w.linkAllowed, t, s1.saved.nextWakeAtMs)) return claim(s1, w, t, CONTROLLER, true, fx)
            // Waiting for the ladder: only with the exemption and the setting on is there anything to wait for.
            val wait = BackgroundPolicy.wakeAllowed(w.exempt, true, s1.saved.manualStop, w.minutes, false, w.linkAllowed)
            return T(s1, fx, reply = AutoStopSleep(if (wait) untilNextWake(s1, t) else null))
        }
        val away = BackgroundPolicy.away(h.link, h.worn) ?: return T(present(s), fx, reply = AutoStopSleep(null))
        val n = BackgroundPolicy.cleanAutoStopMin(w.minutes)
        val since = s.awaySince ?: if (s.saved.wakeProbe && n != BackgroundPolicy.AUTO_STOP_NEVER) t - maxOf(0L, n * 60_000L - BackgroundPolicy.WAKE_PROBE_MS) else t
        val d = decide(w, h, away, since, t)
        if (d.stop && d.reason != null) {
            // A stop already being committed: its confirm is queued; nothing more to decide.
            if (s.pendingStop != null) return T(s.copy(awaySince = since), fx, reply = AutoStopSleep(BackgroundPolicy.BUSY_RECHECK_MS))
            // The mark is the WHOLE persisted outcome of the stop (reason, since, next rung, probe used up): the confirm then commits nothing.
            val mark = s.saved.copy(autoStoppedAtMs = t, reason = d.reason, awaySinceMs = since, nextWakeAtMs = t + BackgroundPolicy.wakeDelayMs(s.saved.wakeStep), wakeProbe = false)
            val p = PendingStop(d.reason, since, t, s.saved.nextWakeAtMs, s.saved.wakeProbe)
            return T(s.copy(saved = mark, awaySince = since, pendingStop = p), fx, recheck = AutoStopEvent.ConfirmStop())
        }
        return T(cancel(s).copy(awaySince = since), fx, reply = AutoStopSleep(d.recheckInMs))
    }

    /**
     * The re-validation: the stop runs only if the policy, over the latest inputs (sampled for this very event, after every input queued during the mark's commit),
     * still says stop with the marked reason. Then nothing persisted changes, so no commit separates the decision from [AutoStopEffect.StopHub]. A changed reason
     * is re-marked and confirmed again; anything else cancels the stop and reverts the mark.
     */
    private fun confirm(s: AutoStopState, t: Long): T {
        val p = s.pendingStop; val w = s.wake; val h = s.hub
        if (p == null || w == null || h == null || !h.hubRunning) return evaluate(s, t)
        val away = BackgroundPolicy.away(h.link, h.worn) ?: return evaluate(s, t)
        val d = decide(w, h, away, p.sinceMs, t)
        if (!d.stop || d.reason == null) return evaluate(s, t)
        if (d.reason != s.saved.reason) return T(s.copy(saved = s.saved.copy(reason = d.reason), pendingStop = p.copy(reason = d.reason)), recheck = AutoStopEvent.ConfirmStop())
        val next = BackgroundPolicy.wakeDelayMs(s.saved.wakeStep)
        val s1 = s.copy(pendingStop = null, awaySince = null) // memory only: saved is untouched
        return T(s1, listOf(note(s1), AutoStopEffect.StopHub(d.reason), AutoStopEffect.ScheduleWake(next, true)), reply = AutoStopSleep(next))
    }

    private fun decide(w: WakeInputs, h: HubSignals, away: BackgroundPolicy.Away, since: Long, t: Long) =
        BackgroundPolicy.autoStop(BackgroundPolicy.AutoStopInput(t, w.minutes, since, away, h.lastFrontMs, h.inFront, h.busy, h.runActive))

    /** A pending stop is void: the mark it committed is reverted (only while it is still that mark). */
    private fun cancel(s: AutoStopState): AutoStopState {
        val p = s.pendingStop ?: return s
        if (s.saved.autoStoppedAtMs != p.atMs) return s.copy(pendingStop = null)
        return s.copy(pendingStop = null, saved = s.saved.copy(autoStoppedAtMs = 0L, reason = null, awaySinceMs = 0L, nextWakeAtMs = p.prevNextWakeAtMs, wakeProbe = p.prevProbe))
    }

    /** The glasses were seen present: no away window, no probe, the wake ladder starts again. */
    private fun present(s: AutoStopState): AutoStopState {
        val c = cancel(s)
        return c.copy(awaySince = null, saved = c.saved.copy(wakeProbe = false, wakeStep = 0, nextWakeAtMs = 0L))
    }

    private fun wakeExpired(s: AutoStopState, t: Long) = BackgroundPolicy.deadlinePassed(t, s.saved.wakeStartedAtMs + WAKE_START_TIMEOUT_MS, WAKE_START_TIMEOUT_MS)
    private fun wakeLeft(s: AutoStopState, t: Long) = BackgroundPolicy.remainingMs(t, s.saved.wakeStartedAtMs + WAKE_START_TIMEOUT_MS, WAKE_START_TIMEOUT_MS).coerceAtLeast(1_000L)

    /** A wake whose service never reached the foreground: it is over (no probe), still auto-stopped, and the next rung (already set by the claim) is armed. */
    private fun timedOut(s: AutoStopState, t: Long): Pair<AutoStopState, List<AutoStopEffect>> {
        val s1 = s.copy(saved = s.saved.copy(wakeStartedAtMs = 0L, wakeProbe = false))
        return s1 to listOf(AutoStopEffect.Log(AutoStopEffect.Line.WAKE_TIMED_OUT), AutoStopEffect.ScheduleWake(BackgroundPolicy.remainingMs(t, s1.saved.nextWakeAtMs, MAX_RUNG), false))
    }

    private fun processStart(s: AutoStopState, via: String, t: Long): T {
        val w = s.wake
        if (w == null || s.saved.autoStoppedAtMs == 0L || s.saved.manualStop) return T(s, reply = AutoStopClaimed(AutoStopWake.Claim.NONE))
        if (s.saved.wakeStartedAtMs != 0L) {
            // An interrupted wake (the process may have died in it): recover it at its timeout, or now.
            if (!wakeExpired(s, t)) return T(s, listOf(AutoStopEffect.ScheduleWake(wakeLeft(s, t), false)), reply = AutoStopClaimed(AutoStopWake.Claim.NONE))
            val (s1, fx) = timedOut(s, t)
            return T(s1, fx, reply = AutoStopClaimed(AutoStopWake.Claim.NOT_DUE))
        }
        return claim(s, w, t, via, false, emptyList())
    }

    /**
     * The one owner of "claim a wake" (worker, process start and the live controller all reach it only as events): check, consume the rung, save the probe,
     * then [AutoStopEffect.StartWake] once that is durable; its [AutoStopEvent.WakeResult] settles the request before any other event.
     */
    private fun claim(s: AutoStopState, w: WakeInputs, t: Long, via: String, controller: Boolean, fx: List<AutoStopEffect>): T {
        val c = WakeClaim(via, t, controller)
        if (s.saved.autoStoppedAtMs == 0L || s.saved.manualStop || s.saved.wakeStartedAtMs != 0L) return settle(s, c, AutoStopWake.Claim.NONE, fx)
        if (!BackgroundPolicy.wakeAllowed(w.exempt, true, false, w.minutes, w.serviceRunning, w.linkAllowed)) return settle(s, c, AutoStopWake.Claim.NONE, fx)
        if (!BackgroundPolicy.deadlinePassed(t, s.saved.nextWakeAtMs, MAX_RUNG)) return settle(s, c, AutoStopWake.Claim.NOT_DUE, fx)
        val step = s.saved.wakeStep + 1
        val s1 = s.copy(saved = s.saved.copy(wakeStep = step, nextWakeAtMs = t + BackgroundPolicy.wakeDelayMs(step), wakeProbe = true), claim = c)
        return T(s1, fx + AutoStopEffect.Log(AutoStopEffect.Line.WAKE_ATTEMPT, via) + AutoStopEffect.StartWake(via, controller))
    }

    /**
     * An issued request is NOT a start: the wake stays in progress (persisted) until [AutoStopEvent.HubForeground], or [WAKE_START_TIMEOUT_MS] re-arms the
     * ladder; a recovery wake is scheduled for that timeout in case this process dies meanwhile.
     */
    private fun wakeResult(s: AutoStopState, e: AutoStopEvent.WakeResult, t: Long): T {
        val c = s.claim ?: return T(s) // stale: nothing in flight
        val s0 = s.copy(claim = null)
        return when {
            e.issued -> settle(s0.copy(saved = s0.saved.copy(wakeStartedAtMs = t)), c, AutoStopWake.Claim.STARTED, listOf(AutoStopEffect.ScheduleWake(WAKE_START_TIMEOUT_MS, false)))
            e.serviceRunning -> settle(cleared(s0), c, AutoStopWake.Claim.ALREADY_RUNNING, listOf(AutoStopEffect.Log(AutoStopEffect.Line.WAKE_ALREADY_RUNNING, c.via)))
            // refused: the probe never runs; it only clears (a user start in between can never be undone by this)
            else -> settle(s0.copy(saved = s0.saved.copy(wakeProbe = false)), c, AutoStopWake.Claim.REFUSED, listOf(AutoStopEffect.Log(AutoStopEffect.Line.WAKE_REFUSED, c.via)))
        }
    }

    /** What the claimant does with the outcome: the controller sleeps / re-arms, a process start schedules the remaining time. */
    private fun settle(s: AutoStopState, c: WakeClaim, r: AutoStopWake.Claim, fx: List<AutoStopEffect>): T {
        if (!c.controller) {
            val sched = if (r == AutoStopWake.Claim.NOT_DUE || r == AutoStopWake.Claim.REFUSED)
                listOf(AutoStopEffect.ScheduleWake(BackgroundPolicy.remainingMs(c.atMs, s.saved.nextWakeAtMs, MAX_RUNG), false)) else emptyList()
            return T(s, fx + sched, reply = AutoStopClaimed(r))
        }
        return when (r) {
            AutoStopWake.Claim.STARTED, AutoStopWake.Claim.ALREADY_RUNNING -> T(s, fx + AutoStopEffect.Note(null), reply = AutoStopSleep(BackgroundPolicy.BUSY_RECHECK_MS))
            AutoStopWake.Claim.REFUSED -> untilNextWake(s, c.atMs).let { T(s, fx + AutoStopEffect.ScheduleWake(it, true), reply = AutoStopSleep(it)) }
            AutoStopWake.Claim.NOT_DUE -> T(s, fx, reply = AutoStopSleep(untilNextWake(s, c.atMs)))
            AutoStopWake.Claim.NONE -> T(s, fx + note(s), reply = AutoStopSleep(BackgroundPolicy.BUSY_RECHECK_MS))
        }
    }

    /**
     * A save failed: nothing of [AutoStopEvent.PersistFailed.event] happened (no stop, no start, no clear). The safe behaviour holds until a save succeeds (the hub is
     * never stopped on an unsaved decision, and [AutoStopEngine.visible] reports "keep running"); `persist_failed` is logged at most once a minute; the inputs
     * that are not looked at again anyway are retried on [PERSIST_RETRY_MS] (an evaluation is repeated by the controller's own recheck).
     */
    private fun persistFailed(s: AutoStopState, e: AutoStopEvent.PersistFailed, t: Long): T {
        val logNow = s.persistLoggedAtMs.let { it == null || t - it >= PERSIST_LOG_EVERY_MS || t < it }
        val s1 = s.copy(persistFailing = true, persistLoggedAtMs = if (logNow) t else s.persistLoggedAtMs)
        val fx = if (logNow) listOf(AutoStopEffect.Log(AutoStopEffect.Line.PERSIST_FAILED)) else emptyList()
        val retryable = when (e.event) {
            AutoStopEvent.UserStarted, AutoStopEvent.ManualStopped, AutoStopEvent.HubForeground, is AutoStopEvent.WakeResult, is AutoStopEvent.ProcessStarted, is AutoStopEvent.ConfirmStop -> true
            else -> false
        }
        // a retry looks at the inputs again (never at the ones the failed step was prepared with)
        val fresh = when (val ev = e.event) { is AutoStopEvent.ConfirmStop -> AutoStopEvent.ConfirmStop(); is AutoStopEvent.ProcessStarted -> ev.copy(wake = null); else -> ev }
        val retry = if (retryable && e.attempt < PERSIST_RETRY_MS.size) fresh else null
        val reply: Any? = when (e.event) {
            is AutoStopEvent.Evaluate, is AutoStopEvent.ConfirmStop -> AutoStopSleep(BackgroundPolicy.BUSY_RECHECK_MS)
            is AutoStopEvent.ProcessStarted -> AutoStopClaimed(AutoStopWake.Claim.NONE)
            else -> null
        }
        return T(s1, fx, reply = reply, retry = retry, retryAfterMs = if (retry != null) PERSIST_RETRY_MS[e.attempt] else 0L)
    }

    /** Until the stored next rung, at least [BackgroundPolicy.BUSY_RECHECK_MS]; a clock set back counts as due ([BackgroundPolicy.deadlinePassed]). */
    private fun untilNextWake(s: AutoStopState, t: Long): Long =
        BackgroundPolicy.remainingMs(t, s.saved.nextWakeAtMs, MAX_RUNG).coerceAtLeast(BackgroundPolicy.BUSY_RECHECK_MS)
}
