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
 * The hub FOREGROUND service ([hubNeeded]) stays only while the glasses link is active, a phone-side context source is on, or something is in
 * progress (an ask, or a Reconnect the user tapped in the last [USER_ASK_HOLD_MS]).
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
        fun due(nowMs: Long): Boolean = nowMs >= nextAtMs
    }

    fun delayMs(step: Int): Long = BACKOFF_MS[step.coerceIn(0, BACKOFF_MS.lastIndex)]

    /** An attempt was made at [nowMs]: the next one waits [delayMs] of the step just used. */
    fun attempted(b: Backoff, nowMs: Long): Backoff = Backoff(step = b.step + 1, nextAtMs = nowMs + delayMs(b.step))

    /** A positive signal: the next attempt is due at once and the ladder starts again. */
    fun reset(): Backoff = Backoff()

    /** What may keep the hub foreground service. [contextSourceOn]: any phone-side source the user switched on; [activeWork]: an ask in progress or a recent tapped Reconnect. */
    data class Needs(val enabled: Boolean, val paired: Boolean, val contextSourceOn: Boolean, val activeWork: Boolean)

    fun hubNeeded(n: Needs): Boolean = linkMachineryAllowed(n.enabled, n.paired) || n.contextSourceOn || n.activeWork

    /** The source ids whose switch keeps the hub (ContextHub runs inside it): watch_app, activity, steps, airplane, car, speed, image intent (photo). */
    val CONTEXT_SOURCE_IDS: List<String> = listOf("watch_app", "phone_activity", "phone_steps", "phone_airplane", "phone_car", "phone_speed", "image_intent")

    /** How long the watchdog loop sleeps before its next tick. [nowMs] and [backoff] matter only while away. */
    fun sleepMs(gate: Gate, periodMs: Long, backoff: Backoff, nowMs: Long): Long = when (gate) {
        Gate.NO_PAIR, Gate.DISABLED -> IDLE_RECHECK_MS
        Gate.ACTIVE -> periodMs
        Gate.AWAY -> maxOf(periodMs, backoff.nextAtMs - nowMs)
    }
}
