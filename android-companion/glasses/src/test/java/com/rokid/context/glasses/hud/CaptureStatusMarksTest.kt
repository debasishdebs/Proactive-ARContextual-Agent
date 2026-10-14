package com.rokid.context.glasses.hud

import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureStatusMarksTest {
    @Test fun marksFollowTheFlags() {
        assertEquals(emptyList(), captureStatusMarks(cooling = false, degraded = false))
        assertEquals(listOf(CaptureStatusCopy.COOLING), captureStatusMarks(cooling = true, degraded = false))
        assertEquals(listOf(CaptureStatusCopy.COOLING, CaptureStatusCopy.DEGRADED), captureStatusMarks(true, true))
    }
}
