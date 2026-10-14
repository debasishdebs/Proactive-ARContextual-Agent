package com.rokid.context.phone.hub

import android.Manifest
import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubActivityHookTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var voiceForeground = 0
    private val resumes = mutableListOf<Long>()
    private val hook = HubResumeHook(voiceForeground = { _, resume -> voiceForeground++; resumes += resume })

    /** Carry (Phase 3 review): BLUETOOTH_CONNECT granted while the app is open (dialog closes, activity resumes) starts the hub. */
    @Test fun aLateBluetoothGrantStartsTheHubOnTheNextResume() {
        HubGraph.resetForTests(null)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        hook.onActivityResumed(activity)
        assertNull("no start without the permission", shadowOf(app).nextStartedService)

        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        hook.onActivityResumed(activity)
        val started = shadowOf(app).nextStartedService
        assertEquals(ContextHubService::class.java.name, started?.component?.className)
        assertTrue("a resumed activity's start is in front", ContextHubService.startedInFront(started))
        assertFalse("the hook doesn't build the hub", HubGraph.isBuilt)
    }

    /**
     * Fix round 1 (review I1): the first resume of a cold process comes before the service's onCreate (isRunning() is
     * false), and with BLUETOOTH_CONNECT denied the hub never runs. Voice is re-checked on every resume anyway.
     */
    @Test fun everyResumeRechecksVoiceEvenBeforeTheHubRuns() {
        HubGraph.resetForTests(null)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        hook.onActivityResumed(activity)
        assertFalse(ContextHubService.isRunning())
        assertEquals(1, voiceForeground)
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        hook.onActivityResumed(activity)
        assertEquals(2, voiceForeground)
        assertEquals("each resume has its own number", listOf(1L, 2L), resumes)
        assertFalse("the hook doesn't build the hub", HubGraph.isBuilt)
    }

    /**
     * Nearby devices page: with "Keep running when nearby" OFF the hub stops once the last activity leaves the front,
     * even while connected. ON (the default) keeps today's behaviour. A sticky background restart then stops too.
     */
    @Test fun switchOffStopsServiceInBackground() {
        HubGraph.resetForTests(null)
        var keep = false
        var stops = 0
        val hook = HubResumeHook(voiceForeground = { _, _ -> }, keepRunning = { keep }, stopHub = { stops++ })
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val b = Robolectric.buildActivity(Activity::class.java).setup().get()
        hook.onActivityStarted(a); hook.onActivityStarted(b)
        hook.onActivityStopped(a)
        assertEquals("another activity is still in front", 0, stops)
        hook.onActivityStopped(b)
        assertEquals("backgrounded with the switch off", 1, stops)

        keep = true
        hook.onActivityStarted(a); hook.onActivityStopped(a)
        assertEquals("switch on: keeps running in the background", 1, stops)

        assertTrue(ContextHubService.stopsOnBackgroundStart(inFront = false, keepRunning = false))
        assertFalse(ContextHubService.stopsOnBackgroundStart(inFront = true, keepRunning = false))
        assertFalse(ContextHubService.stopsOnBackgroundStart(inFront = false, keepRunning = true))
    }
}
