package com.rokid.context.core.hud.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudCopyTest {
    /** Phase 3 carry: the first seconds after start are "connecting", never "offline". */
    @Test fun connectingSaysConnectingNotOffline() {
        assertEquals("connecting", HudCopy.phoneLink(HudConnection.CONNECTING))
        assertEquals("linked", HudCopy.phoneLink(HudConnection.LIVE))
        assertEquals("offline", HudCopy.phoneLink(HudConnection.OFFLINE))
        assertEquals("update needed", HudCopy.phoneLink(HudConnection.OUTDATED))
        assertEquals("Connecting to phone\u2026", HudCopy.askUnavailable(HudConnection.CONNECTING, null))
        assertEquals("Phone not connected", HudCopy.askUnavailable(HudConnection.OFFLINE, null))
        assertEquals("Update Proactive Context", HudCopy.askUnavailable(HudConnection.OUTDATED, null))
    }

    @Test fun whenLiveOnlyThePhonesReasonBlocksAsk() {
        assertNull(HudCopy.askUnavailable(HudConnection.LIVE, null))
        assertNull(HudCopy.askUnavailable(HudConnection.LIVE, "  "))
        assertEquals("Voice not supported on this phone", HudCopy.askUnavailable(HudConnection.LIVE, "Voice not supported on this phone"))
        assertEquals(60, HudCopy.askUnavailable(HudConnection.LIVE, "x".repeat(200))!!.length)
    }
}
