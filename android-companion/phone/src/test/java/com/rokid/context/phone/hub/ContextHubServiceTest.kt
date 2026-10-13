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

    private val device = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

    /** A locked phone still hears glasses asks: the hub's FGS also holds the microphone type once RECORD_AUDIO is granted. */
    @Test fun foregroundTypesAddTheMicOnlyWhenGranted() {
        assertEquals(device, ContextHubService.foregroundTypes(sdk = 37, micGranted = false, inFront = true))
        assertEquals(device or mic, ContextHubService.foregroundTypes(sdk = 37, micGranted = true, inFront = true))
        assertEquals(device, ContextHubService.foregroundTypes(sdk = 29, micGranted = true, inFront = true))
    }

    /**
     * Fix round 1 (review minor 1): a start from the background (a sticky restart) never asks for the mic. On 34+ it
     * would throw; on 30-33 it would start without while-in-use mic access. The next resume re-enters with it.
     */
    @Test fun aBackgroundStartTakesConnectedDeviceOnlyOnEveryApiLevel() {
        assertEquals(device, ContextHubService.foregroundTypes(sdk = 34, micGranted = true, inFront = false))
        assertEquals(device, ContextHubService.foregroundTypes(sdk = 30, micGranted = true, inFront = false))
        assertEquals(device, ContextHubService.foregroundTypes(sdk = 33, micGranted = true, inFront = false))
        assertEquals(device or mic, ContextHubService.foregroundTypes(sdk = 30, micGranted = true, inFront = true))
        assertEquals(device or mic, ContextHubService.foregroundTypes(sdk = 34, micGranted = true, inFront = true))
    }

    /** The fallback (review minor 1): a refused mic type retries with connectedDevice; a refused connectedDevice does not retry. */
    @Test fun aRefusedMicTypeFallsBackToConnectedDeviceOnly() {
        val tried = mutableListOf<Int>()
        assertEquals(device or mic, ContextHubService.enterWithFallback(device or mic) { tried += it })
        assertEquals(listOf(device or mic), tried)
        tried.clear()
        assertEquals(device, ContextHubService.enterWithFallback(device or mic) { tried += it; if (it and mic != 0) throw SecurityException("mic") })
        assertEquals(listOf(device or mic, device), tried)
        tried.clear()
        assertEquals(null, ContextHubService.enterWithFallback(device) { tried += it; throw IllegalStateException("no") })
        assertEquals(listOf(device), tried)
        tried.clear()
        assertEquals(null, ContextHubService.enterWithFallback(device or mic) { tried += it; throw SecurityException("no") })
        assertEquals(listOf(device or mic, device), tried)
    }

    /** Review minor 2: a resume re-enters the foreground only when the wanted types differ from the current ones. */
    @Test fun theForegroundIsReenteredOnlyWhenTheTypesChange() {
        assertFalse(ContextHubService.needsReentry(current = device or mic, wanted = device or mic))
        assertTrue(ContextHubService.needsReentry(current = device, wanted = device or mic))
        assertTrue(ContextHubService.needsReentry(current = device or mic, wanted = device))
        assertTrue(ContextHubService.needsReentry(current = null, wanted = device))
    }
}
