package com.rokid.context.glasses.hud

import com.rokid.context.core.hud.Swipe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyGesturesTest {
    private val k = KeyGestures()

    @Test fun enterKeyUpIsATap() {
        assertTrue(k.onKeyDown(KeyGestures.KEY_ENTER, 0, 0, 0).consumed)
        assertEquals(listOf<PadInput>(PadInput.Tapped), k.onKeyUp(KeyGestures.KEY_ENTER).inputs)
    }

    /** Device-verified: a double-tap is two KEYCODE_NOTIFICATION presses ~150 ms apart. */
    @Test fun twoNotificationKeysQuicklyAreADoubleTap() {
        assertTrue(k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, uptimeMs = 0, wallMs = 0).inputs.isEmpty())
        assertEquals(listOf<PadInput>(PadInput.DoubleTapped), k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, uptimeMs = 150, wallMs = 150).inputs)
    }

    @Test fun backIsADoubleTapOnOtherFirmware() = assertEquals(listOf<PadInput>(PadInput.DoubleTapped), k.onBack(wallMs = 0))

    @Test fun shortSwipeRightThenTwinIsOneForwardSwipe() {
        k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 0, 0)
        assertTrue(k.onKeyDown(KeyGestures.KEY_RIGHT, 0, 10, 10).inputs.isEmpty())
        assertEquals(listOf<PadInput>(PadInput.Swiped(Swipe(forward = true, long = false))), k.onKeyDown(KeyGestures.KEY_DOWN, 0, 50, 50).inputs)
    }

    @Test fun twoStepsAreALongSwipe() {
        k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 0, 0)
        k.onKeyDown(KeyGestures.KEY_LEFT, 0, 10, 10)
        k.onKeyDown(KeyGestures.KEY_LEFT, 0, 70, 70)
        assertEquals(listOf<PadInput>(PadInput.Swiped(Swipe(forward = false, long = true))), k.onKeyDown(KeyGestures.KEY_UP, 0, 110, 110).inputs)
    }

    @Test fun theTimerClosesALoneStep() {
        k.onKeyDown(KeyGestures.KEY_RIGHT, 0, 0, 0)
        assertEquals(150L, k.deadlineMs)
        assertEquals(listOf<PadInput>(PadInput.Swiped(Swipe(forward = true, long = false))), k.onTimer(150))
    }

    /** A quick swipe then a touch is not a double-tap: the swipe key breaks the 83 pair. */
    @Test fun aSwipeBreaksADoubleTapPair() {
        k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 0, 0)
        k.onKeyDown(KeyGestures.KEY_RIGHT, 0, 20, 20)
        k.onKeyDown(KeyGestures.KEY_DOWN, 0, 50, 50)
        assertFalse(PadInput.DoubleTapped in k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 200, 200).inputs)
    }

    @Test fun otherKeysAreNotConsumed() {
        assertFalse(k.onKeyDown(KeyGestures.KEY_VOLUME_UP, 0, 0, 0).consumed)
        assertFalse(k.onKeyUp(KeyGestures.KEY_VOLUME_UP).consumed)
    }

    @Test fun repeatsAreSwallowed() = assertTrue(k.onKeyDown(KeyGestures.KEY_RIGHT, repeatCount = 1, uptimeMs = 0, wallMs = 0).let { it.consumed && it.inputs.isEmpty() })
}
