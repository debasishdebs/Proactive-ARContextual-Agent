package com.rokid.context.phone.hub

import android.Manifest
import android.app.Activity
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private val hook = HubResumeHook(voiceForeground = { voiceForeground++ })

    /** Carry (Phase 3 review): BLUETOOTH_CONNECT granted while the app is open (dialog closes, activity resumes) starts the hub. */
    @Test fun aLateBluetoothGrantStartsTheHubOnTheNextResume() {
        HubGraph.resetForTests(null)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        hook.onActivityResumed(activity)
        assertNull("no start without the permission", shadowOf(app).nextStartedService)

        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        hook.onActivityResumed(activity)
        assertEquals(ContextHubService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
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
        assertFalse("the hook doesn't build the hub", HubGraph.isBuilt)
    }
}
