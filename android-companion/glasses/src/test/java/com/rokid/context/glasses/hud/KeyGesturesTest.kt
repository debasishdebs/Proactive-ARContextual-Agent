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
        assertEquals(listOf<PadInput>(PadInput.Tapped), k.onKeyUp(KeyGestures.KEY_ENTER, 50).inputs)
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
        assertFalse(k.onKeyUp(KeyGestures.KEY_VOLUME_UP, 0).consumed)
    }

    @Test fun repeatsAreSwallowed() = assertTrue(k.onKeyDown(KeyGestures.KEY_RIGHT, repeatCount = 1, uptimeMs = 0, wallMs = 0).let { it.consumed && it.inputs.isEmpty() })

    @Test fun aTapBreaksADoubleTapPair() {
        k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 0, 0)
        k.onKeyDown(KeyGestures.KEY_ENTER, 0, 20, 20)
        assertEquals(listOf<PadInput>(PadInput.Tapped), k.onKeyUp(KeyGestures.KEY_ENTER, 60).inputs)
        assertFalse(PadInput.DoubleTapped in k.onKeyDown(KeyGestures.KEY_NOTIFICATION, 0, 200, 200).inputs)
    }

    /** Phase 3 carry: a long press belongs to "Hi Rokid" (spec §3.5), so its key-up is not a tap. */
    @Test fun aLongPressIsNotATap() {
        k.onKeyDown(KeyGestures.KEY_ENTER, 0, uptimeMs = 1_000, wallMs = 1_000)
        assertEquals(emptyList(), k.onKeyUp(KeyGestures.KEY_ENTER, uptimeMs = 1_000 + KeyGestures.LONG_PRESS_MS).inputs)
    }

    @Test fun aHeldKeyThatRepeatsIsNotATap() {
        k.onKeyDown(KeyGestures.KEY_DPAD_CENTER, 0, 0, 0)
        assertTrue(k.onKeyDown(KeyGestures.KEY_DPAD_CENTER, repeatCount = 1, uptimeMs = 100, wallMs = 100).consumed)
        assertEquals(emptyList(), k.onKeyUp(KeyGestures.KEY_DPAD_CENTER, 200).inputs)
    }

    @Test fun aQuickTapAfterALongPressIsATapAgain() {
        k.onKeyDown(KeyGestures.KEY_ENTER, 0, 0, 0); k.onKeyUp(KeyGestures.KEY_ENTER, 900)
        k.onKeyDown(KeyGestures.KEY_ENTER, 0, 2_000, 2_000)
        assertEquals(listOf<PadInput>(PadInput.Tapped), k.onKeyUp(KeyGestures.KEY_ENTER, 2_120).inputs)
    }

    /** A key-up with no recorded down (the press began before an activity recreate, e.g. into Hi Rokid) is not a tap. */
    @Test fun anOrphanKeyUpIsNotATap() {
        val up = k.onKeyUp(KeyGestures.KEY_ENTER, 1_000)
        assertTrue(up.consumed)
        assertEquals(emptyList(), up.inputs)
    }

    @Test fun aPressJustUnderTheLongPressThresholdIsATap() {
        k.onKeyDown(KeyGestures.KEY_ENTER, 0, 1_000, 1_000)
        assertEquals(listOf<PadInput>(PadInput.Tapped), k.onKeyUp(KeyGestures.KEY_ENTER, 1_000 + KeyGestures.LONG_PRESS_MS - 1).inputs)
    }

    @Test fun aPressOfExactlyTheLongPressThresholdIsNotATap() {
        k.onKeyDown(KeyGestures.KEY_DPAD_CENTER, 0, 1_000, 1_000)
        assertEquals(emptyList(), k.onKeyUp(KeyGestures.KEY_DPAD_CENTER, 1_000 + KeyGestures.LONG_PRESS_MS).inputs)
    }
}
