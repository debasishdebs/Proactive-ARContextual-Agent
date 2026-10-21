package com.rokid.context.glasses.capture.diag

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenWakeStatsTest {
    private var now = 0L
    private val s = ScreenWakeStats { now }

    @Test fun screenOnWithoutOurUiIsNotOurs() { now = 5_000; s.onScreenOn(); assertEquals(Triple(1, 0, 0), s.take()) }
    @Test fun screenOnSoonAfterOurUiIsOurs() { now = 0; s.onOurUi(); now = 1_500; s.onScreenOn(); assertEquals(Triple(1, 1, 0), s.take()) }
    @Test fun screenOnLongAfterOurUiIsNotOurs() { now = 0; s.onOurUi(); now = 2_500; s.onScreenOn(); assertEquals(Triple(1, 0, 0), s.take()) }
    @Test fun takeResets() { s.onScreenOn(); s.onNudgeSkipped(); s.take(); assertEquals(Triple(0, 0, 0), s.take()) }
    @Test fun nudgeSkipsAreCounted() { s.onNudgeSkipped(); s.onNudgeSkipped(); assertEquals(Triple(0, 0, 2), s.take()) }
}
