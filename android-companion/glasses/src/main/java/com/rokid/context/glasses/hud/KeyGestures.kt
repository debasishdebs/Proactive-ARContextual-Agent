package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.DoubleTapDetector
import com.rokid.context.core.hud.Swipe
import com.rokid.context.core.hud.SwipeClassifier
import com.rokid.context.core.hud.SwipeKey

sealed interface PadInput {
    data class Swiped(val swipe: Swipe) : PadInput
    data object Tapped : PadInput
    data object DoubleTapped : PadInput
}

/** [consumed] = the activity must not pass the key on (83 would move us to the back). */
data class KeyResult(val consumed: Boolean, val inputs: List<PadInput> = emptyList())

/**
 * Rokid touchpad keys → pad inputs (spec §3.5), ported from Live Fit `MainActivity.dispatchKeyEvent`. Every gesture
 * starts with KEYCODE_NOTIFICATION (83); DPAD keys are swipe steps ([SwipeClassifier], event uptime); two 83 presses or
 * one BACK are a double-tap ([DoubleTapDetector], wall clock); ENTER / DPAD_CENTER key-up is a tap. Long-press belongs
 * to Hi Rokid and vertical swipes are only the twin of a horizontal one.
 */
class KeyGestures(
    private val swipes: SwipeClassifier = SwipeClassifier(),
    private val doubleTap: DoubleTapDetector = DoubleTapDetector(),
) {
    /** When [onTimer] must run (uptime ms) to close an open swipe; null = none open. */
    val deadlineMs: Long? get() = swipes.deadlineMs

    fun onKeyDown(keyCode: Int, repeatCount: Int, uptimeMs: Long, wallMs: Long): KeyResult {
        val first = repeatCount == 0
        if (keyCode == KEY_NOTIFICATION) {
            if (!first) return KeyResult(true)
            val out = mutableListOf<PadInput>()
            swipes.onTouch(uptimeMs)?.let { out += PadInput.Swiped(it) } // a new touch closes the previous swipe
            if (doubleTap.onNotificationKey(wallMs)) out += PadInput.DoubleTapped
            return KeyResult(true, out)
        }
        val tapKey = keyCode == KEY_ENTER || keyCode == KEY_DPAD_CENTER
        if (tapKey && first) doubleTap.onGestureKey() // a tap breaks a 83 pair, like a swipe
        val key = swipeKey(keyCode) ?: return KeyResult(tapKey)
        if (!first) return KeyResult(true)
        doubleTap.onGestureKey()
        return KeyResult(true, listOfNotNull(swipes.onKey(key, uptimeMs)?.let { PadInput.Swiped(it) }))
    }

    fun onKeyUp(keyCode: Int): KeyResult = when (keyCode) {
        KEY_ENTER, KEY_DPAD_CENTER -> KeyResult(true, listOf(PadInput.Tapped))
        KEY_NOTIFICATION, KEY_RIGHT, KEY_LEFT, KEY_DOWN, KEY_UP -> KeyResult(true)
        else -> KeyResult(false)
    }

    fun onBack(wallMs: Long): List<PadInput> = if (doubleTap.onBack(wallMs)) listOf(PadInput.DoubleTapped) else emptyList()

    fun onTimer(uptimeMs: Long): List<PadInput> = listOfNotNull(swipes.onTimer(uptimeMs)?.let { PadInput.Swiped(it) })

    private fun swipeKey(code: Int): SwipeKey? = when (code) {
        KEY_RIGHT -> SwipeKey.Right
        KEY_LEFT -> SwipeKey.Left
        KEY_DOWN -> SwipeKey.Down
        KEY_UP -> SwipeKey.Up
        else -> null
    }

    companion object {
        // android.view.KeyEvent values, repeated so this class stays plain JVM code.
        const val KEY_NOTIFICATION = 83
        const val KEY_RIGHT = 22
        const val KEY_LEFT = 21
        const val KEY_DOWN = 20
        const val KEY_UP = 19
        const val KEY_ENTER = 66
        const val KEY_DPAD_CENTER = 23
        /** KEYCODE_VOLUME_UP: a key we leave to the system. */
        const val KEY_VOLUME_UP = 24
    }
}
