package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState

/** What a wake may consult (cheap reads): the battery exemption, the AutoStop minutes, link machinery allowed, the hub service up. */
data class WakeInputs(val exempt: Boolean, val minutes: Int, val linkAllowed: Boolean, val serviceRunning: Boolean)

/** What the live hub reports (cheap in-memory reads): running, link, wear, our app in front / last in front, an ask or a Run in flight. */
data class HubSignals(
    val hubRunning: Boolean, val link: LinkState, val worn: Boolean?, val inFront: Boolean, val lastFrontMs: Long, val busy: Boolean, val runActive: Boolean,
)

/** A stop that is decided and committed (the mark) but not yet executed: [AutoStopEvent.ConfirmStop] runs it or cancels it, reverting the mark. */
data class PendingStop(val reason: BackgroundPolicy.Away, val sinceMs: Long, val atMs: Long, val prevNextWakeAtMs: Long)

/** A wake whose start is in flight; [controller] = the live controller claimed it (else a process start / the worker). */
data class WakeClaim(val via: String, val atMs: Long, val controller: Boolean)

/** The AutoStop state: [saved] is the persisted part (exactly what [AutoStopStore] holds after each step); the rest is the process's working memory. */
data class AutoStopState(
    val saved: AutoStopSnapshot = AutoStopSnapshot(),
    val wake: WakeInputs? = null,
    val hub: HubSignals? = null,
    /** When the current away window started (null = present, or the hub not running). */
    val awaySince: Long? = null,
    val pendingStop: PendingStop? = null,
    val claim: WakeClaim? = null,
)

sealed interface AutoStopEvent {
    /** The controller looks (a poke: link/wear change, app in front or left, a setting changed; or its timer). Null inputs are sampled when it is reduced. */
    data class Evaluate(val wake: WakeInputs? = null, val hub: HubSignals? = null) : AutoStopEvent
    /** The re-validation of a committed stop, from the inputs as they are AFTER the commit (sampled again). */
    data class ConfirmStop(val wake: WakeInputs? = null, val hub: HubSignals? = null) : AutoStopEvent
    /** A process start ([AutoStopWake]: Application.onCreate, the one-shot wake worker): a due wake may start the hub. */
    data class ProcessStarted(val via: String, val wake: WakeInputs? = null) : AutoStopEvent
    /** Feedback of [AutoStopEffect.StartWake]: the start was issued / the service is up after it. */
    data class WakeResult(val issued: Boolean, val serviceRunning: Boolean) : AutoStopEvent
    /** A user start (app opened, Reconnect tapped): the manual stop, the auto stop, the ladder and a pending probe are all over. */
    object UserStarted : AutoStopEvent
    /** The user stopped the hub service by hand. */
    object ManualStopped : AutoStopEvent
}

sealed interface AutoStopEffect {
    /** The status line: [reason] null = none. */
    data class Note(val reason: BackgroundPolicy.Away?, val sinceMs: Long = 0L) : AutoStopEffect
    data class StopHub(val reason: BackgroundPolicy.Away) : AutoStopEffect
    data class StartWake(val via: String, val controller: Boolean) : AutoStopEffect
    data class ScheduleWake(val delayMs: Long, val controller: Boolean) : AutoStopEffect
    /** A fixed log line ([Line]) about a wake [via] an entry point. */
    data class Log(val line: Line, val via: String = "") : AutoStopEffect
    enum class Line { WAKE_ATTEMPT, WAKE_ALREADY_RUNNING, WAKE_REFUSED }
}

/** Replies: the controller's next sleep (null = until the next poke), or a process start's claim. */
data class AutoStopSleep(val ms: Long?)
data class AutoStopClaimed(val claim: AutoStopWake.Claim)

/**
 * The PURE AutoStop reducer (Codex review 4): (state, event, now) -> (state', effects). No clock, no IO, no locks: [SerialStateMachine] runs it one event at a time,
 * commits [AutoStopState.saved] before the effects, and re-validates a stop with [AutoStopEvent.ConfirmStop] queued behind every input that arrived during the commit.
 */
private typealias T = Transition<AutoStopState, AutoStopEvent, AutoStopEffect>

object AutoStopMachine {
    private val MAX_RUNG = BackgroundPolicy.WAKE_LADDER_MS.last()
    const val CONTROLLER = "controller"

    fun reduce(s: AutoStopState, e: AutoStopEvent, now: Long): T = when (e) {
        is AutoStopEvent.Evaluate -> evaluate(s.copy(wake = e.wake ?: s.wake, hub = e.hub ?: s.hub), now)
        is AutoStopEvent.ConfirmStop -> confirm(s.copy(wake = e.wake ?: s.wake, hub = e.hub ?: s.hub), now)
        is AutoStopEvent.ProcessStarted -> processStart(s.copy(wake = e.wake ?: s.wake), e.via, now)
        is AutoStopEvent.WakeResult -> wakeResult(s, e)
        AutoStopEvent.UserStarted -> T(
            s.copy(saved = s.saved.copy(manualStop = false, autoStoppedAtMs = 0L, reason = null, awaySinceMs = 0L, wakeStep = 0, nextWakeAtMs = 0L, wakeProbe = false), pendingStop = null),
            listOf(AutoStopEffect.Note(null)),
        )
        AutoStopEvent.ManualStopped -> T(s.copy(saved = s.saved.copy(manualStop = true)))
    }

    /** The status line for [s]: only a stop that really happened (not one being committed). */
    fun note(s: AutoStopState): AutoStopEffect.Note {
        val why = s.saved.reason
        if (s.pendingStop != null || s.saved.autoStoppedAtMs == 0L || why == null) return AutoStopEffect.Note(null)
        return AutoStopEffect.Note(why, s.saved.awaySinceMs.takeIf { it > 0 } ?: s.saved.autoStoppedAtMs)
    }

    private fun evaluate(s: AutoStopState, t: Long): T {
        val w = s.wake; val h = s.hub
        if (w == null || h == null) return T(s, reply = AutoStopSleep(null))
        val fx = mutableListOf<AutoStopEffect>(note(s))
        if (!h.hubRunning) {
            val s1 = cancel(s).copy(awaySince = null) // the hub stopped some other way while a stop was being committed: that stop is void
            if (s1.saved.autoStoppedAtMs == 0L) return T(s1, fx, reply = AutoStopSleep(null))
            if (BackgroundPolicy.wakeDue(w.exempt, true, s1.saved.manualStop, w.minutes, false, w.linkAllowed, t, s1.saved.nextWakeAtMs)) return claim(s1, w, t, CONTROLLER, true, fx)
            // Waiting for the ladder: only with the exemption and the setting on is there anything to wait for.
            val wait = BackgroundPolicy.wakeAllowed(w.exempt, true, s1.saved.manualStop, w.minutes, false, w.linkAllowed)
            return T(s1, fx, reply = AutoStopSleep(if (wait) untilNextWake(s1, t) else null))
        }
        val away = BackgroundPolicy.away(h.link, h.worn) ?: return T(present(s), fx, reply = AutoStopSleep(null))
        val n = BackgroundPolicy.cleanAutoStopMin(w.minutes)
        val since = s.awaySince ?: if (s.saved.wakeProbe && n != BackgroundPolicy.AUTO_STOP_NEVER) t - maxOf(0L, n * 60_000L - BackgroundPolicy.WAKE_PROBE_MS) else t
        val d = decide(s, w, h, away, since, t)
        if (d.stop && d.reason != null) {
            // A stop already being committed: its confirm is queued; nothing more to decide.
            if (s.pendingStop != null) return T(s.copy(awaySince = since), fx, reply = AutoStopSleep(BackgroundPolicy.BUSY_RECHECK_MS))
            val mark = s.saved.copy(autoStoppedAtMs = t, reason = d.reason, awaySinceMs = since, nextWakeAtMs = t + BackgroundPolicy.wakeDelayMs(s.saved.wakeStep))
            return T(s.copy(saved = mark, awaySince = since, pendingStop = PendingStop(d.reason, since, t, s.saved.nextWakeAtMs)), fx, recheck = AutoStopEvent.ConfirmStop())
        }
        return T(cancel(s).copy(awaySince = since), fx, reply = AutoStopSleep(d.recheckInMs))
    }

    /** The re-validation: the stop runs only if the policy, from the latest inputs, still says stop; otherwise it is cancelled and the mark reverted. */
    private fun confirm(s: AutoStopState, t: Long): T {
        val p = s.pendingStop; val w = s.wake; val h = s.hub
        if (p == null || w == null || h == null || !h.hubRunning) return evaluate(s, t)
        val away = BackgroundPolicy.away(h.link, h.worn) ?: return evaluate(s, t)
        val d = decide(s, w, h, away, p.sinceMs, t)
        if (!d.stop || d.reason == null) return evaluate(s, t)
        val next = BackgroundPolicy.wakeDelayMs(s.saved.wakeStep)
        val s1 = s.copy(saved = s.saved.copy(reason = d.reason, wakeProbe = false), pendingStop = null, awaySince = null)
        return T(s1, listOf(note(s1), AutoStopEffect.StopHub(d.reason), AutoStopEffect.ScheduleWake(next, true)), reply = AutoStopSleep(next))
    }

    private fun decide(s: AutoStopState, w: WakeInputs, h: HubSignals, away: BackgroundPolicy.Away, since: Long, t: Long) =
        BackgroundPolicy.autoStop(BackgroundPolicy.AutoStopInput(t, w.minutes, since, away, h.lastFrontMs, h.inFront, h.busy, h.runActive))

    /** A pending stop is void: the mark it committed is reverted (only while it is still that mark). */
    private fun cancel(s: AutoStopState): AutoStopState {
        val p = s.pendingStop ?: return s
        if (s.saved.autoStoppedAtMs != p.atMs) return s.copy(pendingStop = null)
        return s.copy(pendingStop = null, saved = s.saved.copy(autoStoppedAtMs = 0L, reason = null, awaySinceMs = 0L, nextWakeAtMs = p.prevNextWakeAtMs))
    }

    /** The glasses were seen present: no away window, no probe, the wake ladder starts again. */
    private fun present(s: AutoStopState): AutoStopState {
        val c = cancel(s)
        return c.copy(awaySince = null, saved = c.saved.copy(wakeProbe = false, wakeStep = 0, nextWakeAtMs = 0L))
    }

    private fun processStart(s: AutoStopState, via: String, t: Long): T {
        val w = s.wake
        if (w == null || s.saved.autoStoppedAtMs == 0L || s.saved.manualStop) return T(s, reply = AutoStopClaimed(AutoStopWake.Claim.NONE))
        return claim(s, w, t, via, false, emptyList())
    }

    /**
     * The one owner of "claim a wake" (worker, process start and the live controller all reach it only as events): check, consume the rung, save the probe,
     * then [AutoStopEffect.StartWake] once that is durable; its [AutoStopEvent.WakeResult] settles it before any other event.
     */
    private fun claim(s: AutoStopState, w: WakeInputs, t: Long, via: String, controller: Boolean, fx: List<AutoStopEffect>): T {
        if (s.saved.autoStoppedAtMs == 0L || s.saved.manualStop) return settle(s, WakeClaim(via, t, controller), AutoStopWake.Claim.NONE, fx)
        if (!BackgroundPolicy.wakeAllowed(w.exempt, true, false, w.minutes, w.serviceRunning, w.linkAllowed)) return settle(s, WakeClaim(via, t, controller), AutoStopWake.Claim.NONE, fx)
        if (!BackgroundPolicy.deadlinePassed(t, s.saved.nextWakeAtMs, MAX_RUNG)) return settle(s, WakeClaim(via, t, controller), AutoStopWake.Claim.NOT_DUE, fx)
        val step = s.saved.wakeStep + 1
        val s1 = s.copy(saved = s.saved.copy(wakeStep = step, nextWakeAtMs = t + BackgroundPolicy.wakeDelayMs(step), wakeProbe = true), claim = WakeClaim(via, t, controller))
        return T(s1, fx + AutoStopEffect.Log(AutoStopEffect.Line.WAKE_ATTEMPT, via) + AutoStopEffect.StartWake(via, controller))
    }

    private fun wakeResult(s: AutoStopState, e: AutoStopEvent.WakeResult): T {
        val c = s.claim ?: return T(s) // stale: nothing in flight
        val s0 = s.copy(claim = null)
        fun cleared(x: AutoStopState) = x.copy(saved = x.saved.copy(autoStoppedAtMs = 0L, reason = null, awaySinceMs = 0L))
        return when {
            e.issued -> settle(cleared(s0), c, AutoStopWake.Claim.STARTED, emptyList())
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

    /** Until the stored next rung, at least [BackgroundPolicy.BUSY_RECHECK_MS]; a clock set back counts as due ([BackgroundPolicy.deadlinePassed]). */
    private fun untilNextWake(s: AutoStopState, t: Long): Long =
        BackgroundPolicy.remainingMs(t, s.saved.nextWakeAtMs, MAX_RUNG).coerceAtLeast(BackgroundPolicy.BUSY_RECHECK_MS)
}
