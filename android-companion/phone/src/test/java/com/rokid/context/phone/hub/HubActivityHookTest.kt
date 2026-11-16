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

    /** Review hub-wp2 r5: the internally launched Hi Rokid AuthActivity resuming is not the wearer opening the app. */
    @Test fun anInternalAuthActivityResumeIsNotAUserForeground() {
        HubGraph.resetForTests(null)
        val front = mutableListOf<Int>()
        val h = HubResumeHook(voiceForeground = { _, _ -> voiceForeground++ }, front = { front += 1 })
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val auth = Robolectric.buildActivity(com.rokid.context.link.AuthActivity::class.java).get()
        h.onActivityResumed(auth)
        assertEquals(0, voiceForeground); assertTrue(front.isEmpty())
        assertNull("no hub start", shadowOf(app).nextStartedService)
        h.onActivityResumed(Robolectric.buildActivity(Activity::class.java).setup().get())
        assertEquals("a user activity still counts", 1, voiceForeground)
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

        assertTrue(ContextHubService.stopsOnBackgroundStart(inFront = false, needed = false))
        assertFalse(ContextHubService.stopsOnBackgroundStart(inFront = true, needed = false))
        assertFalse(ContextHubService.stopsOnBackgroundStart(inFront = false, needed = true))
    }

    /** v0.9.9: the watchdog's (background) start happens only while something needs the hub; a tapped Reconnect (force) and a resumed activity always start it. */
    @Test fun aBackgroundStartNeedsAReasonButForceAndTheFrontDoNot() {
        HubGraph.resetForTests(null)
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val before = ContextHubService.hubNeededNow
        try {
            ContextHubService.hubNeededNow = { false }
            ContextHubService.ensureRunning(app)
            assertNull("nothing needs the hub: no start", shadowOf(app).nextStartedService)
            ContextHubService.ensureRunning(app, force = true)
            val forced = shadowOf(app).nextStartedService
            assertEquals(ContextHubService::class.java.name, forced?.component?.className)
            assertTrue(forced!!.getBooleanExtra(ContextHubService.EXTRA_FORCE, false))
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            hook.onActivityResumed(activity)
            assertEquals("a resumed activity starts it as before", ContextHubService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
            ContextHubService.hubNeededNow = { true }
            ContextHubService.ensureRunning(app)
            assertEquals(ContextHubService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        } finally { ContextHubService.hubNeededNow = before }
    }

    /** v0.9.9: leaving the front stops the hub unless something needs it (the hook's keepRunning is now "hub needed"). */
    @Test fun leavingTheFrontStopsTheHubOnlyWhenNothingNeedsIt() {
        var needed = false; var stops = 0
        val h = HubResumeHook(voiceForeground = { _, _ -> }, keepRunning = { needed }, stopHub = { stops++ })
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        h.onActivityStarted(a); h.onActivityStopped(a); assertEquals(1, stops)
        needed = true; h.onActivityStarted(a); h.onActivityStopped(a); assertEquals("a context source or the glasses link keeps it", 1, stops)
    }

    @Test fun theAppInFrontIsAPositiveSignalEvenWithNoHubService() {
        var positives = 0
        val h = HubResumeHook(voiceForeground = { _, _ -> }, positive = { positives++ })
        h.onActivityResumed(Robolectric.buildActivity(Activity::class.java).setup().get())
        assertEquals(1, positives)
    }
}
