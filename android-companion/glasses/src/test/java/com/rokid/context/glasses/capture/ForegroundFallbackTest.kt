package com.rokid.context.glasses.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.glasses.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Spec §1.7: the camera fallback brings our HUD forward only while the wearer is in it, never over another app. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForegroundFallbackTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 1_000_000L
    private val logs = mutableListOf<String>()
    private val presence = HudPresence { now }
    private val fallback = ForegroundFallback(presence, { now }, log = { logs += it })
    private fun started() = shadowOf(app).nextStartedActivity

    @Test fun whileTheHudIsStartedTheFallbackOpensIt() {
        presence.onStarted()
        assertTrue(fallback.maybeLaunch(app))
        val i = started()
        assertEquals(MainActivity::class.java.name, i.component?.className)
        assertEquals(MainActivity.ACTION_CAPTURE_NOW, i.action)
    }

    @Test fun afterTheWearerExitsAFailedWindowDoesNotOpenTheHud() {
        presence.onStarted()
        presence.onStopped(finishing = true)
        assertFalse(fallback.maybeLaunch(app))
        assertNull(started())
        assertEquals(1, logs.size)
    }

    @Test fun backgroundedWithinSixtySecondsStillOpensItButNotLater() {
        presence.onStarted()
        presence.onStopped(finishing = false)
        now += 59_000
        assertTrue(fallback.maybeLaunch(app))
        assertEquals(MainActivity::class.java.name, started().component?.className)
        now += 30_000 // past the 20 s cooldown, now 89 s since the HUD left the foreground
        assertFalse(fallback.maybeLaunch(app))
        assertNull(started())
    }

    @Test fun neverStartedInThisProcessDoesNotOpenIt() {
        assertFalse(fallback.maybeLaunch(app))
        assertNull(started())
    }

    @Test fun theCooldownStillApplies() {
        presence.onStarted()
        assertTrue(fallback.maybeLaunch(app)); started()
        now += 19_000
        assertFalse(fallback.maybeLaunch(app))
        assertNull(started())
    }
}
