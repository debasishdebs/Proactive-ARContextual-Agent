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

    /** R-65: one action, "Pause capture" while running and "Resume capture" while paused. */
    @Test fun notificationActionFollowsPauseState() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val paused = ContextHubService.buildNotification(ctx, "Glasses connected", paused = true)
        assertEquals(1, paused.actions.size)
        assertEquals("Resume capture", paused.actions.single().title.toString())
        val running = ContextHubService.buildNotification(ctx, "Glasses connected", paused = false)
        assertEquals("Pause capture", running.actions.single().title.toString())
        assertEquals(ContextHubService.ACTION_RESUME, org.robolectric.Shadows.shadowOf(paused.actions.single().actionIntent).savedIntent.action)
        assertEquals(ContextHubService.ACTION_PAUSE, org.robolectric.Shadows.shadowOf(running.actions.single().actionIntent).savedIntent.action)
    }

    @Test fun notificationTextIsShortAndHuman() {
        fun text(l: LinkState) = ContextHubService.notificationText(GlassesStatus(l, null, 0, null, null))
        // Glasses as a nearby device: while linked (in front or minimised) the FGS says so and keeps the link alive.
        assertEquals("Connected to glasses", text(LinkState.CONNECTED))
        assertEquals("Connected to glasses", text(LinkState.MINIMISED))
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

    /** Fix round 2: the voice gate learns whether the FGS holds the microphone type. */
    @Test fun micHeldOnlyWithTheMicrophoneType() {
        assertFalse(ContextHubService.holdsMic(null))
        assertFalse(ContextHubService.holdsMic(device))
        assertTrue(ContextHubService.holdsMic(device or mic))
    }

    /** Fix round 2 ruling: after a refused mic type, the mic is retried at most once per resume. */
    @Test fun aRefusedMicIsRetriedOncePerResume() {
        val retry = MicRetry()
        assertEquals(device or mic, retry.toTry(device or mic, resume = 1))
        retry.entered(tried = device or mic, entered = device)
        assertEquals("same resume (Settings recheck, language change)", device, retry.toTry(device or mic, resume = null))
        assertEquals(device, retry.toTry(device or mic, resume = 1))
        assertEquals("the next resume tries again", device or mic, retry.toTry(device or mic, resume = 2))
        retry.entered(tried = device or mic, entered = device or mic)
        assertEquals(device or mic, retry.toTry(device or mic, resume = null))
        retry.entered(tried = device or mic, entered = null)
        assertEquals(device, retry.toTry(device or mic, resume = 2))
    }

    /** Review minor: whether a start came from the front travels in its intent, so no static flag can leak. */
    @Test fun aStartIsInFrontOnlyWhenItsIntentSaysSo() {
        assertFalse("a sticky restart has no intent", ContextHubService.startedInFront(null))
        assertFalse(ContextHubService.startedInFront(android.content.Intent()))
        assertTrue(ContextHubService.startedInFront(android.content.Intent().putExtra(ContextHubService.EXTRA_IN_FRONT, true)))
    }
}
