package com.rokid.context.core.hud


/**
 * Turns the touchpad's double-tap into one event (spec §3.5). On the owner's glasses a double-tap arrives as two
 * KEYCODE_NOTIFICATION (83) presses ~150 ms apart; other firmware sends BACK. Two 83 presses within [WINDOW_MS], or
 * one BACK, is a double-tap; keys within [SETTLE_MS] of a fired double-tap are its tail (a firmware sending both).
 * While the exit gate is armed (`armed`), only the other key kind is a tail: a quick second double-tap of the same
 * kind is the 4-tap that minimises (final review I1), and must not be swallowed.
 * A lone 83 press does nothing. Every touchpad gesture starts with an 83 press, so a swipe or tap key in between
 * ([onGestureKey]) breaks the pair: a quick swipe then a touch is not a double-tap.
 */
class DoubleTapDetector {
    private var firstMs: Long? = null
    private var firedMs: Long? = null
    private var firedBack = false

    /** A KEYCODE_NOTIFICATION key-down. True = a double-tap. */
    fun onNotificationKey(nowMs: Long, armed: Boolean = false): Boolean {
        if (settling(nowMs, back = false, armed)) return false
        val first = firstMs
        if (first != null && nowMs - first <= WINDOW_MS) return fire(nowMs, back = false)
        firstMs = nowMs
        return false
    }

    /** A swipe or tap key: the last 83 press belonged to that gesture. */
    fun onGestureKey() { firstMs = null }

    /** A BACK press. True = a double-tap. */
    fun onBack(nowMs: Long, armed: Boolean = false): Boolean = if (settling(nowMs, back = true, armed)) false else fire(nowMs, back = true)

    private fun settling(nowMs: Long, back: Boolean, armed: Boolean) =
        !(armed && back == firedBack) && firedMs?.let { nowMs - it < SETTLE_MS } == true
    private fun fire(nowMs: Long, back: Boolean): Boolean { firstMs = null; firedMs = nowMs; firedBack = back; return true }

    companion object {
        const val WINDOW_MS = 400L
        const val SETTLE_MS = 600L
    }
}

