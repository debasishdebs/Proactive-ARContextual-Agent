package com.rokid.context.phone.hub

import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Test

class HubStateTest {
    @Test fun fieldsMapIntoTheContractStatus() = assertEquals(
        GlassesStatus(LinkState.CONNECTED, glassesBatteryPct = 70, bufferedFrames = 3, lastFrameAtMs = 9, frame = null),
        HubState(link = LinkState.CONNECTED, glassesBatteryPct = 70, bufferedFrames = 3, lastFrameAtMs = 9).toGlassesStatus(),
    )

    @Test fun aMismatchOverridesConnected() =
        assertEquals(LinkState.VERSION_MISMATCH, HubState(link = LinkState.CONNECTED, versionMismatch = true).toGlassesStatus().link)

    @Test fun aStaleMismatchIsHiddenOnceTheLinkIsDown() =
        assertEquals(LinkState.OFFLINE, HubState(link = LinkState.OFFLINE, versionMismatch = true).toGlassesStatus().link)
}
