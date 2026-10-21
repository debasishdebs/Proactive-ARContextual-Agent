package com.rokid.context.phone.hub

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextHubServiceLocationTest {
    private val DEV = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val MIC = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val LOC = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test fun locationOnlyWhenWantedInFrontAndPermitted() {
        for (sdk in listOf(31, 35)) {
            assertEquals(DEV or LOC, ContextHubService.withLocation(DEV, want = true, inFront = true, permitted = true, sdk = sdk))
            assertEquals(DEV, ContextHubService.withLocation(DEV, want = false, inFront = true, permitted = true, sdk = sdk))
            assertEquals(DEV, ContextHubService.withLocation(DEV, want = true, inFront = false, permitted = true, sdk = sdk)) // background suspicion
            assertEquals(DEV, ContextHubService.withLocation(DEV, want = true, inFront = true, permitted = false, sdk = sdk))
        }
    }

    @Test fun locationIsDroppedWhenNoLongerWanted() {
        assertEquals(DEV or MIC, ContextHubService.withLocation(DEV or MIC or LOC, want = false, inFront = false, permitted = true, sdk = 35))
    }

    @Test fun microphoneIsKeptWhenLocationIsAdded() {
        assertEquals(DEV or MIC or LOC, ContextHubService.withLocation(DEV or MIC, want = true, inFront = true, permitted = true, sdk = 34))
    }

    @Test fun refusedLocationKeepsTheMicrophone() {
        val tried = ArrayList<Int>()
        val entered = ContextHubService.enterWithFallback(DEV or MIC or LOC) { t -> tried += t; if (t and LOC != 0) error("refused") }
        assertEquals(DEV or MIC, entered); assertEquals(listOf(DEV or MIC or LOC, DEV or MIC), tried)
    }

    @Test fun refusedMicrophoneKeepsLocation() {
        val entered = ContextHubService.enterWithFallback(DEV or MIC or LOC) { t -> if (t and MIC != 0) error("refused") }
        assertEquals(DEV or LOC, entered)
    }

    @Test fun refusedEverythingFallsToDeviceAndDeviceRefusedIsNull() {
        assertEquals(DEV, ContextHubService.enterWithFallback(DEV or MIC or LOC) { t -> if (t != DEV) error("x") })
        assertNull(ContextHubService.enterWithFallback(DEV or MIC) { error("x") })
        assertNull(ContextHubService.enterWithFallback(DEV) { error("x") })
    }

    @Test fun existingMicOnlyFallbackIsUnchanged() {
        assertEquals(DEV, ContextHubService.enterWithFallback(DEV or MIC) { t -> if (t and MIC != 0) error("x") })
        assertEquals(DEV or MIC, ContextHubService.enterWithFallback(DEV or MIC) { })
    }

    @Test fun holdsLocation() {
        assertTrue(ContextHubService.holdsLocation(DEV or LOC)); assertFalse(ContextHubService.holdsLocation(DEV)); assertFalse(ContextHubService.holdsLocation(null))
    }

    @Test fun reentryRulesForRefresh() {
        // a refresh that only adds/removes the location bit keeps everything else as currently held
        assertEquals(DEV or MIC or LOC, ContextHubService.withLocation(DEV or MIC, true, true, true, 35))
        assertTrue(ContextHubService.needsReentry(DEV or MIC, DEV or MIC or LOC))
        assertFalse(ContextHubService.needsReentry(DEV or MIC, DEV or MIC))
    }

    @Test fun wantLocationDefaultsToFalse() {
        assertFalse(ContextHubService.wantLocation())
    }
}
