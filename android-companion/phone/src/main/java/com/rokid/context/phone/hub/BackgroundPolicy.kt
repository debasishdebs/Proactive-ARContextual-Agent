package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState

/**
 * v0.9.9 battery fix (2026-10-30): the ONE pure policy for what the phone may do in the background about the glasses.
 *
 * Link machinery = the watchdog's reconnect and hub-restart ticks, the relauncher's attempts and every automatic connect. It runs only
 * while the glasses link is enabled in settings ("Keep running when nearby", [KeepRunningNearby]) AND a pair exists ([com.rokid.context.link.CxrGlassesLink.isPaired]):
 *  - [Gate.NO_PAIR] / [Gate.DISABLED]: nothing at all (a tapped Reconnect still works, it never goes through this gate);
 *  - [Gate.ACTIVE]: the glasses are there (a data link, or their app was closed): today's cadence;
 *  - [Gate.AWAY]: enabled and paired, but the glasses are not there: attempts back off [BACKOFF_MS] (5, 15, 30, then 60 min cap), and
 *    [Backoff] resets on any positive signal (the link came up, a wear event, the app in front, a tapped Reconnect). The status row keeps saying
 *    "Glasses not connected" with the last-heard age whatever the back-off.
 * The hub FOREGROUND service ([hubNeeded]) stays only while the glasses link is allowed (and the user has not stopped the hub by hand, and it did not stop
 * itself, see [autoStop]) or something is in progress (an ask, or a Reconnect the user tapped in the last [USER_ASK_HOLD_MS]). A context source alone never keeps it.
 */
object BackgroundPolicy {
    enum class Gate { NO_PAIR, DISABLED, ACTIVE, AWAY }

    /** Back-off between attempts while enabled and away: 5, 15, 30, then 60 min for ever. */
    val BACKOFF_MS: List<Long> = listOf(5L, 15L, 30L, 60L).map { it * 60_000L }

    /** A tapped Reconnect keeps the hub wanted for this long (the attempt, its retries and the first hello). */
    const val USER_ASK_HOLD_MS = 10 * 60_000L

    /** With no pair or the setting off, the watchdog loop only re-reads its inputs this often (no radio, no log). */
    const val IDLE_RECHECK_MS = 30 * 60_000L

    /** The glasses link may run automatic work: enabled in settings and a pair exists. */
    fun linkMachineryAllowed(enabled: Boolean, paired: Boolean): Boolean = enabled && paired

    fun gate(enabled: Boolean, paired: Boolean, link: LinkState): Gate = when {
        !paired -> Gate.NO_PAIR
        !enabled -> Gate.DISABLED
        // the glasses are reachable: a data link, or only their app was closed / is the wrong version
        link == LinkState.CONNECTED || link == LinkState.MINIMISED || link == LinkState.CLOSED_ON_GLASSES || link == LinkState.VERSION_MISMATCH -> Gate.ACTIVE
        else -> Gate.AWAY // OFFLINE, PAUSED_BT, NOT_AUTHORIZED, CONNECTING
    }

    /** Immutable back-off state: [step] attempts made since the last reset; none is due before [nextAtMs]. */
    data class Backoff(val step: Int = 0, val nextAtMs: Long = 0L) {
        /** Persisted wall-clock deadline: a clock set back (the deadline lies further ahead than the longest step) must not block for ever. */
        fun due(nowMs: Long): Boolean = deadlinePassed(nowMs, nextAtMs, BACKOFF_MS.last())
    }

    /**
     * A persisted wall-clock deadline is reached at [deadlineMs], or at once when it lies more than [maxDelayMs] ahead: no deadline is ever set further
     * than that, so the clock was set back (as for the hub's last start, a set-back clock must not block for ever).
     */
    fun deadlinePassed(nowMs: Long, deadlineMs: Long, maxDelayMs: Long): Boolean = nowMs >= deadlineMs || deadlineMs - nowMs > maxDelayMs

    /** How long until [deadlineMs] ([deadlinePassed] = 0), never more than [maxDelayMs]. */
    fun remainingMs(nowMs: Long, deadlineMs: Long, maxDelayMs: Long): Long = if (deadlinePassed(nowMs, deadlineMs, maxDelayMs)) 0L else deadlineMs - nowMs

    fun delayMs(step: Int): Long = BACKOFF_MS[step.coerceIn(0, BACKOFF_MS.lastIndex)]

    /** An attempt was made at [nowMs]: the next one waits [delayMs] of the step just used. */
    fun attempted(b: Backoff, nowMs: Long): Backoff = Backoff(step = b.step + 1, nextAtMs = nowMs + delayMs(b.step))

    /** A positive signal: the next attempt is due at once and the ladder starts again. */
    fun reset(): Backoff = Backoff()

    /**
     * What may keep the hub foreground service (v0.9.9 AutoStop, user ruling 2026-11-07). A context source (the watch included) no longer keeps it alone:
     * "Watch signals pause while the glasses are off". [activeWork]: an ask in progress or a recent tapped Reconnect (a user start, it also beats [manualStop]).
     * [autoStopped]: the hub stopped itself (glasses away for the configured time) and nothing has started it since. [manualStop]: the user stopped it by hand;
     * it stays stopped until the user starts it again.
     */
    data class Needs(val enabled: Boolean, val paired: Boolean, val activeWork: Boolean, val autoStopped: Boolean = false, val manualStop: Boolean = false)

    fun hubNeeded(n: Needs): Boolean = n.activeWork || (!n.manualStop && !n.autoStopped && linkMachineryAllowed(n.enabled, n.paired))

    /** The phone-side context source ids (ContextHub runs inside the hub): watch_app, activity, steps, airplane, car, speed, image intent (photo). Switching one on wakes the hub restart ladder; it does NOT keep the hub alive. */
    val CONTEXT_SOURCE_IDS: List<String> = listOf("watch_app", "phone_activity", "phone_steps", "phone_airplane", "phone_car", "phone_speed", "image_intent")

    /** How long the watchdog loop sleeps before its next tick. [nowMs] and [backoff] matter only while away. */
    fun sleepMs(gate: Gate, periodMs: Long, backoff: Backoff, nowMs: Long): Long = when (gate) {
        Gate.NO_PAIR, Gate.DISABLED -> IDLE_RECHECK_MS
        Gate.ACTIVE -> periodMs
        Gate.AWAY -> maxOf(periodMs, remainingMs(nowMs, backoff.nextAtMs, BACKOFF_MS.last()))
    }

    // ---- AutoStop (user ruling 2026-11-07): the hub stops by itself while the glasses are away ----

    /** N: minutes the glasses may be off-head or disconnected before the hub stops (default). The one named constant; Settings > Capture can change it. */
    const val AUTO_STOP_DEFAULT_MIN = 20
    const val AUTO_STOP_MIN_MIN = 5
    const val AUTO_STOP_MAX_MIN = 120
    /** Stored value meaning "Never": keep the old behaviour (the hub stays while the glasses link is allowed). */
    const val AUTO_STOP_NEVER = 0
    /** Settings choices, minutes; [AUTO_STOP_NEVER] last. */
    val AUTO_STOP_PRESETS: List<Int> = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, AUTO_STOP_NEVER)

    /** A stored value: 0 stays Never, anything else is held to 5..120 (a bad or hand-edited pref cannot give a 1 min or a 3 day timer). */
    fun cleanAutoStopMin(v: Int): Int = if (v == AUTO_STOP_NEVER) AUTO_STOP_NEVER else v.coerceIn(AUTO_STOP_MIN_MIN, AUTO_STOP_MAX_MIN)

    /** M: how long an app foreground or a tapped Reconnect keeps the hub (same as [USER_ASK_HOLD_MS]). */
    const val FRONT_HOLD_MS = USER_ASK_HOLD_MS
    /** While something is in flight (an ask, a run) the decision is looked at again this often. */
    const val BUSY_RECHECK_MS = 60_000L

    /** Why the glasses are away; the log token and the banner follow it. */
    enum class Away(val token: String, val word: String) {
        NOT_WORN("auto_stop_not_worn", "not worn"),
        DISCONNECTED("auto_stop_disconnected", "not connected"),
    }

    /**
     * Away = the glasses link does not reach the glasses ([gate] AWAY: offline, paused, not authorized, connecting) or the wear signal says off-head.
     * Link up with the wear signal unknown counts as present (the "up within the last N min" rule: it turns into away the moment the link drops).
     * A link whose glasses app was closed ([LinkState.CLOSED_ON_GLASSES], VERSION_MISMATCH) still reaches the glasses: present unless worn == false.
     */
    fun away(link: LinkState, worn: Boolean?): Away? = when {
        link == LinkState.OFFLINE || link == LinkState.PAUSED_BT || link == LinkState.NOT_AUTHORIZED || link == LinkState.CONNECTING -> Away.DISCONNECTED
        worn == false -> Away.NOT_WORN
        else -> null
    }

    /**
     * [awaySinceMs]: when the glasses last became away (null = present); [lastFrontMs]: when our app was last in front (0 = never); [inFront] now;
     * [busy]: an ask in flight or a tapped Reconnect inside its hold; [runActive]: a user-started capture/Run (a collect session) is active.
     */
    data class AutoStopInput(
        val nowMs: Long, val minutes: Int, val awaySinceMs: Long?, val away: Away?, val lastFrontMs: Long, val inFront: Boolean, val busy: Boolean, val runActive: Boolean,
    )

    /** [stop]: stop the hub now, with [reason]. Otherwise [recheckInMs] (null = nothing to wait for: only an input change matters). */
    data class AutoStopDecision(val stop: Boolean, val reason: Away? = null, val recheckInMs: Long? = null)

    fun autoStop(i: AutoStopInput): AutoStopDecision {
        if (i.minutes == AUTO_STOP_NEVER || i.away == null || i.awaySinceMs == null) return AutoStopDecision(false)
        if (i.inFront) return AutoStopDecision(false, recheckInMs = FRONT_HOLD_MS)
        if (i.busy || i.runActive) return AutoStopDecision(false, recheckInMs = BUSY_RECHECK_MS)
        val dueAt = maxOf(i.awaySinceMs + cleanAutoStopMin(i.minutes) * 60_000L, if (i.lastFrontMs > 0) i.lastFrontMs + FRONT_HOLD_MS else 0L)
        return if (i.nowMs >= dueAt) AutoStopDecision(true, i.away) else AutoStopDecision(false, recheckInMs = dueAt - i.nowMs)
    }

    // ---- Waking a hub that stopped itself ----

    /** With the battery-optimization exemption a background start is allowed: the next wake attempts wait 60, 120, then 240 min (never a loop). */
    val WAKE_LADDER_MS: List<Long> = listOf(60L, 120L, 240L).map { it * 60_000L }
    fun wakeDelayMs(step: Int): Long = WAKE_LADDER_MS[step.coerceIn(0, WAKE_LADDER_MS.lastIndex)]

    /** A woken hub looks for the glasses this long (instead of the full N min) before it stops again. */
    const val WAKE_PROBE_MS = 5 * 60_000L

    /** [exempt]: the app is on the battery-optimization allowlist. A wake needs it, a hub that stopped itself, no manual stop, the setting not Never, and its ladder due ([deadlinePassed]: a clock set back does not block it for ever). */
    fun wakeDue(exempt: Boolean, autoStopped: Boolean, manualStop: Boolean, minutes: Int, hubRunning: Boolean, linkAllowed: Boolean, nowMs: Long, nextWakeAtMs: Long): Boolean =
        wakeAllowed(exempt, autoStopped, manualStop, minutes, hubRunning, linkAllowed) && deadlinePassed(nowMs, nextWakeAtMs, WAKE_LADDER_MS.last())

    /** [wakeDue] without its time: a wake may come at all (so it is worth waiting or scheduling for). */
    fun wakeAllowed(exempt: Boolean, autoStopped: Boolean, manualStop: Boolean, minutes: Int, hubRunning: Boolean, linkAllowed: Boolean): Boolean =
        exempt && autoStopped && !manualStop && minutes != AUTO_STOP_NEVER && !hubRunning && linkAllowed

    /** The status line after the hub stopped itself; null when it did not. [time] is a local HH:mm. */
    fun stoppedNote(reason: Away, sinceText: String): String = "Stopped: glasses ${reason.word} since $sinceText. Tap to start"
}
