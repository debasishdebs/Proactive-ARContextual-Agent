package com.rokid.context.glasses.capture

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** The CaptureService DEBUG gate: a release build ignores a skip-dedupe request. */
class SkipDedupeGateTest {
    @Test fun debugHonoursTheRequest() {
        assertTrue(CaptureService.skipDedupeHonoured(debugBuild = true, requested = true))
        assertFalse(CaptureService.skipDedupeHonoured(debugBuild = true, requested = false))
    }

    @Test fun releaseAlwaysDedupes() {
        assertFalse(CaptureService.skipDedupeHonoured(debugBuild = false, requested = true))
        assertFalse(CaptureService.skipDedupeHonoured(debugBuild = false, requested = false))
    }
}
