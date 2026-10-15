package com.rokid.context.core.hud

/**
 * The double-tap gate (spec 2026-10-14 §2, fix wave H): Idle; [Armed] after the first double-tap; [Minimising] after the
 * second (a short window in which a third one exits the app). Times are on one clock, the caller's.
 */
sealed interface ExitGate {
    data object Idle : ExitGate
    data class Armed(val untilMs: Long) : ExitGate
    data class Minimising(val untilMs: Long) : ExitGate
}

/** What the page double-tap does once the gate decides. */
enum class PageAction { MINIMISE, TOGGLE_PAUSE, EXIT }

/**
 * Pure gate: a double-tap on a page arms a [WINDOW_MS] window; a second one inside it starts a [MINIMISE_WINDOW_MS]
 * window ("Minimising... double-tap again to EXIT"); a third one inside that exits the app completely; that window
 * ending minimises. The first window ending with no second double-tap toggles pause (today's double-tap, [WINDOW_MS]
 * later). Any other input cancels it.
 */
object DoubleTapExit {
    const val WINDOW_MS = 3_000L
    const val MINIMISE_WINDOW_MS = 1_500L
    const val ARMED_TEXT = "Double-tap again to minimise"
    const val MINIMISING_TEXT = "Minimising... double-tap again to EXIT"

    /** Returns the new gate and the action to run now (null = none yet). */
    fun onDoubleTap(gate: ExitGate, nowMs: Long): Pair<ExitGate, PageAction?> = when {
        gate is ExitGate.Minimising && nowMs < gate.untilMs -> ExitGate.Idle to PageAction.EXIT
        gate is ExitGate.Armed && nowMs < gate.untilMs -> ExitGate.Minimising(nowMs + MINIMISE_WINDOW_MS) to null
        else -> ExitGate.Armed(nowMs + WINDOW_MS) to null
    }

    fun onTick(gate: ExitGate, nowMs: Long): Pair<ExitGate, PageAction?> = when {
        gate is ExitGate.Minimising && nowMs >= gate.untilMs -> ExitGate.Idle to PageAction.MINIMISE
        gate is ExitGate.Armed && nowMs >= gate.untilMs -> ExitGate.Idle to PageAction.TOGGLE_PAUSE
        else -> gate to null
    }

    /** A tap or swipe while armed: no pause, no minimise; the input is then handled normally. */
    fun onOtherInput(@Suppress("UNUSED_PARAMETER") gate: ExitGate): ExitGate = ExitGate.Idle

    /** The toast for the gate's state; null when idle. */
    fun countdownText(gate: ExitGate, @Suppress("UNUSED_PARAMETER") nowMs: Long): String? = when (gate) {
        is ExitGate.Armed -> ARMED_TEXT
        is ExitGate.Minimising -> MINIMISING_TEXT
        ExitGate.Idle -> null
    }

    fun isGateText(text: String?): Boolean = text == ARMED_TEXT || text == MINIMISING_TEXT
}
