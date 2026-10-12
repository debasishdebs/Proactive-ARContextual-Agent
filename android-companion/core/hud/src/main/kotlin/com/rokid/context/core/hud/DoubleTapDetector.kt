package com.rokid.context.core.hud


/**
 * Turns the touchpad's double-tap into one event (spec §3.5). On the owner's glasses a double-tap arrives as two
 * KEYCODE_NOTIFICATION (83) presses ~150 ms apart; other firmware sends BACK. Two 83 presses within [WINDOW_MS], or
 * one BACK, is a double-tap; keys within [SETTLE_MS] of a fired double-tap are its tail (a firmware sending both).
 * A lone 83 press does nothing. Every touchpad gesture starts with an 83 press, so a swipe or tap key in between
 * ([onGestureKey]) breaks the pair: a quick swipe then a touch is not a double-tap.
 */
class DoubleTapDetector {
    private var firstMs: Long? = null
    private var firedMs: Long? = null

    /** A KEYCODE_NOTIFICATION key-down. True = a double-tap. */
    fun onNotificationKey(nowMs: Long): Boolean {
        if (settling(nowMs)) return false
        val first = firstMs
        if (first != null && nowMs - first <= WINDOW_MS) return fire(nowMs)
        firstMs = nowMs
        return false
    }

    /** A swipe or tap key: the last 83 press belonged to that gesture. */
    fun onGestureKey() { firstMs = null }

    /** A BACK press. True = a double-tap. */
    fun onBack(nowMs: Long): Boolean = if (settling(nowMs)) false else fire(nowMs)

    private fun settling(nowMs: Long) = firedMs?.let { nowMs - it < SETTLE_MS } == true
    private fun fire(nowMs: Long): Boolean { firstMs = null; firedMs = nowMs; return true }

    companion object {
        const val WINDOW_MS = 400L
        const val SETTLE_MS = 600L
    }
}

