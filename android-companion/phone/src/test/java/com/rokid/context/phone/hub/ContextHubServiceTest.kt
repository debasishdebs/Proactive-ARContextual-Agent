package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ContextHubServiceTest {
    /** Fix round 1: a service destroyed without having started never builds the hub (or a CxrGlassesLink) just to close it. */
    @Test fun destroyWithoutStartLeavesTheHubUnbuilt() {
        HubGraph.resetForTests(null)
        Robolectric.buildService(ContextHubService::class.java).destroy()
        assertFalse(HubGraph.isBuilt)
    }

    /** Android 14+ needs a runtime prerequisite for a connectedDevice FGS; BLUETOOTH_CONNECT exists from API 31. */
    @Test fun startsOnlyWithTheBluetoothPrerequisite() {
        assertTrue(ContextHubService.canStart(sdk = 30, bluetoothGranted = false))
        assertFalse(ContextHubService.canStart(sdk = 34, bluetoothGranted = false))
        assertTrue(ContextHubService.canStart(sdk = 37, bluetoothGranted = true))
    }

    @Test fun startsOnce() {
        assertTrue(ContextHubService.shouldStart(running = false, canStart = true))
        assertFalse(ContextHubService.shouldStart(running = true, canStart = true))
        assertFalse(ContextHubService.shouldStart(running = false, canStart = false))
    }

    @Test fun notificationTextIsShortAndHuman() {
        fun text(l: LinkState) = ContextHubService.notificationText(GlassesStatus(l, null, 0, null, null))
        assertEquals("Glasses connected", text(LinkState.CONNECTED))
        assertEquals("Connecting to glasses", text(LinkState.CONNECTING))
        assertEquals("Reconnecting to glasses", text(LinkState.PAUSED_BT))
        assertEquals("Glasses offline", text(LinkState.OFFLINE))
        assertEquals("Closed on glasses", text(LinkState.CLOSED_ON_GLASSES))
        assertEquals("Glasses not authorized", text(LinkState.NOT_AUTHORIZED))
        assertEquals("Update glasses app", text(LinkState.VERSION_MISMATCH))
    }

    /** A locked phone still hears glasses asks: the hub's FGS also holds the microphone type once RECORD_AUDIO is granted. */
    @Test fun foregroundTypesAddTheMicOnlyWhenGranted() {
        val device = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        assertEquals(device, ContextHubService.foregroundTypes(sdk = 37, micGranted = false))
        assertEquals(device or mic, ContextHubService.foregroundTypes(sdk = 37, micGranted = true))
        assertEquals(device, ContextHubService.foregroundTypes(sdk = 29, micGranted = true))
    }
}
