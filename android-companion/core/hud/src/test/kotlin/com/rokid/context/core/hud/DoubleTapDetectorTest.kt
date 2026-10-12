package com.rokid.context.core.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DoubleTapDetectorTest {
    @Test fun twoNotificationKeysCloseTogetherAreADoubleTap() {
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(1_000), "one key alone is not a double-tap")
        assertTrue(d.onNotificationKey(1_150))
    }

    @Test fun keysFurtherApartThanTheWindowAreNot() {
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(1_000))
        assertFalse(d.onNotificationKey(1_000 + DoubleTapDetector.WINDOW_MS + 1))
        assertTrue(d.onNotificationKey(1_000 + DoubleTapDetector.WINDOW_MS + 100), "the late key starts a new pair")
    }

    @Test fun backIsADoubleTapOnItsOwn() = assertTrue(DoubleTapDetector().onBack(0))

    @Test fun oneGestureReportedTwiceFiresOnce() {
        val d = DoubleTapDetector()
        d.onNotificationKey(1_000); assertTrue(d.onNotificationKey(1_150))
        assertFalse(d.onBack(1_300), "firmware that also sends BACK for the same double-tap")
        assertFalse(d.onNotificationKey(1_400)); assertFalse(d.onNotificationKey(1_450))
        assertTrue(d.onBack(1_150 + DoubleTapDetector.SETTLE_MS + 500), "a later double-tap counts")
    }

    @Test fun backThenNotificationKeysFireOnce() {
        val d = DoubleTapDetector()
        assertTrue(d.onBack(1_000))
        assertFalse(d.onNotificationKey(1_100)); assertFalse(d.onNotificationKey(1_250))
    }

    @Test fun aSwipeOrTapBetweenTwoTouchesIsNotADoubleTap() {
        // Every touchpad gesture starts with key 83: a quick swipe then a touch must not close the app.
        val d = DoubleTapDetector()
        assertFalse(d.onNotificationKey(0)); d.onGestureKey()
        assertFalse(d.onNotificationKey(200), "the swipe's touch then the next touch")
        assertTrue(d.onNotificationKey(350), "a real double-tap right after still counts")
    }

    @Test fun aThirdKeyDoesNotPairWithTheSecond() {
        val d = DoubleTapDetector()
        d.onNotificationKey(0); assertTrue(d.onNotificationKey(150))
        assertFalse(d.onNotificationKey(DoubleTapDetector.SETTLE_MS + 200), "pair consumed; a new first key")
    }
}

