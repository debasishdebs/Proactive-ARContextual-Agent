package com.rokid.context.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Release-only paths: the link probe and the "Copy frame id" button exist in debug builds and never in release. */
class DebugOnlyGatesTest {
    @Test fun linkProbeOnlyInDebug() {
        assertTrue(DebugOnlyGates.linkProbe(true))
        assertFalse(DebugOnlyGates.linkProbe(false))
    }

    @Test fun copyFrameIdOnlyInDebug() {
        assertTrue(DebugOnlyGates.copyFrameIdVisible(true))
        assertFalse(DebugOnlyGates.copyFrameIdVisible(false))
    }

    @Test fun gatesFollowTheRealBuildFlag() {
        assertEquals(com.rokid.context.BuildConfig.DEBUG, DebugOnlyGates.linkProbe(com.rokid.context.BuildConfig.DEBUG))
        assertEquals(com.rokid.context.BuildConfig.DEBUG, DebugOnlyGates.copyFrameIdVisible(com.rokid.context.BuildConfig.DEBUG))
    }
}
