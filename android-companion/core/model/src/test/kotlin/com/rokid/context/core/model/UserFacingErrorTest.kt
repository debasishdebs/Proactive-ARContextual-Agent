package com.rokid.context.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserFacingErrorTest {
    /** Spec §6.3 / §3.6: titles double as HUD strings; nothing that looks like exception text. */
    @Test fun everyPresetIsShortHumanText() {
        for (e in UserFacingErrors.all) {
            assertTrue(e.title.length <= 40, e.title)
            assertTrue(e.hint.length <= 80, e.hint)
            for (bad in listOf("Exception", "exception", "null", "http", "Error:", "{")) {
                assertFalse(bad in e.title || bad in e.hint, "'$bad' in ${e.line}")
            }
        }
    }

    @Test fun storageFullWordingIsExact() = assertEquals("Phone storage full", UserFacingErrors.StorageFull.title)

    @Test fun lineJoinsTitleAndHint() =
        assertEquals("Glasses offline · Bring your glasses near the phone.", UserFacingErrors.GlassesOffline.line)

    @Test fun linkStatesMapToTheirBanner() {
        assertEquals(UserFacingErrors.GlassesOffline, UserFacingErrors.forLink(LinkState.OFFLINE))
        assertEquals(UserFacingErrors.ClosedOnGlasses, UserFacingErrors.forLink(LinkState.CLOSED_ON_GLASSES))
        assertEquals(UserFacingErrors.NotAuthorized, UserFacingErrors.forLink(LinkState.NOT_AUTHORIZED))
        assertEquals(UserFacingErrors.VersionMismatch, UserFacingErrors.forLink(LinkState.VERSION_MISMATCH))
        assertEquals(ErrorAction.RECONNECT, UserFacingErrors.forLink(LinkState.OFFLINE)!!.action)
        // Transient states have no banner: CXR resumes a BT pause by itself (~2 s, phase 0).
        for (s in listOf(LinkState.CONNECTED, LinkState.CONNECTING, LinkState.PAUSED_BT, LinkState.MINIMISED)) assertNull(UserFacingErrors.forLink(s), "$s")
    }

    /** Minimised Option A: frames still flow on the background link, so only CONNECTED and MINIMISED carry data. */
    @Test fun onlyConnectedAndMinimisedCarryData() {
        assertEquals(setOf(LinkState.CONNECTED, LinkState.MINIMISED), LinkState.entries.filter { it.carriesData }.toSet())
    }

    /** Phase 8 C3: a closed glasses app is force-stopped, so capture stops until it is reopened. */
    @Test fun closedOnGlassesHintSaysCaptureStops() =
        assertEquals("Capture stops until you open Proactive Context on your glasses.", UserFacingErrors.ClosedOnGlasses.hint)
}
