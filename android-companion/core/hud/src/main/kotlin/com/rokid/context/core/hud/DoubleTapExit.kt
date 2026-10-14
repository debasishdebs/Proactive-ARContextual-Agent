package com.rokid.context.core.hud

/** The double-tap exit gate (spec 2026-10-14 §2): Idle, or Armed until [Armed.untilMs] (one clock, the caller's). */
sealed interface ExitGate {
    data object Idle : ExitGate
    data class Armed(val untilMs: Long) : ExitGate
}

/** What the page double-tap does once the gate decides. */
enum class PageAction { MINIMISE, TOGGLE_PAUSE }

/**
 * Pure gate: a double-tap on a page arms a [WINDOW_MS] window; a second double-tap inside it minimises; the window
 * ending with no second one toggles pause (today's double-tap, [WINDOW_MS] later). Any other input cancels it.
 */
object DoubleTapExit {
    const val WINDOW_MS = 3_000L
    const val COUNTDOWN_PREFIX = "Double-tap again to exit \u00b7 "

    /** Returns the new gate and the action to run now (null = none yet). */
    fun onDoubleTap(gate: ExitGate, nowMs: Long): Pair<ExitGate, PageAction?> = when {
        gate is ExitGate.Armed && nowMs < gate.untilMs -> ExitGate.Idle to PageAction.MINIMISE
        else -> ExitGate.Armed(nowMs + WINDOW_MS) to null
    }

    fun onTick(gate: ExitGate, nowMs: Long): Pair<ExitGate, PageAction?> = when {
        gate is ExitGate.Armed && nowMs >= gate.untilMs -> ExitGate.Idle to PageAction.TOGGLE_PAUSE
        else -> gate to null
    }

    /** A tap or swipe while armed: no pause; the input is then handled normally. */
    fun onOtherInput(@Suppress("UNUSED_PARAMETER") gate: ExitGate): ExitGate = ExitGate.Idle

    /** "Double-tap again to exit · N", N the whole seconds left (3, 2, 1); null when not armed. */
    fun countdownText(gate: ExitGate, nowMs: Long): String? {
        if (gate !is ExitGate.Armed) return null
        val left = (gate.untilMs - nowMs).coerceAtLeast(1)
        return COUNTDOWN_PREFIX + ((left + 999) / 1000)
    }
}
