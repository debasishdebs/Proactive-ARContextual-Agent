package com.rokid.context.glasses.hud

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fix wave H3: a ~600 ms mark in a HUD corner for every saved keyframe, when "Capture blink on HUD" is on. */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureBlinkTest {
    @Test fun showsOncePerKeyframeForAbout600ms() = runTest {
        val blink = CaptureBlink(backgroundScope, { true })
        assertFalse(blink.active.value)
        blink.onKeyframeSaved(); runCurrent()
        assertTrue(blink.active.value)
        advanceTimeBy(599); assertTrue(blink.active.value)
        advanceTimeBy(2); assertFalse(blink.active.value)
        assertEquals(1, blink.shown)
        blink.onKeyframeSaved(); advanceTimeBy(700)
        assertEquals(2, blink.shown); assertFalse(blink.active.value)
    }

    @Test fun anOlderTimerNeverCutsANewerBlinkShort() = runTest {
        val blink = CaptureBlink(backgroundScope, { true })
        blink.onKeyframeSaved(); advanceTimeBy(400)
        blink.onKeyframeSaved(); advanceTimeBy(400) // 800 ms after the first, 400 after the second
        assertTrue(blink.active.value)
        advanceTimeBy(300); assertFalse(blink.active.value)
    }

    @Test fun disabledSettingShowsNothing() = runTest {
        var on = false
        val blink = CaptureBlink(backgroundScope, { on })
        blink.onKeyframeSaved(); runCurrent()
        assertFalse(blink.active.value); assertEquals(0, blink.shown)
        on = true
        blink.onKeyframeSaved(); runCurrent()
        assertTrue(blink.active.value)
    }

    @Test fun neverThrowsSoItCannotBlockACapture() = runTest {
        val blink = CaptureBlink(backgroundScope, { error("settings unreadable") })
        blink.onKeyframeSaved() // must return normally
        runCurrent()
        assertFalse(blink.active.value)
    }

    @Test fun markIsPlainAscii() {
        assertTrue(CaptureStatusCopy.CUE.all { it.code in 32..126 })
        assertEquals(CaptureStatusCopy.CUE, captureCueMark(true)); assertEquals(null, captureCueMark(false))
    }
}
