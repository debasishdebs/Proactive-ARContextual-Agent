package com.rokid.context.glasses

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.Channels
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.IconCorner
import com.rokid.context.core.model.Wire
import com.rokid.context.glasses.capture.CaptureService
import com.rokid.context.glasses.link.CxrBridge
import com.rokid.context.glasses.link.FakeBridge
import com.rokid.context.glasses.overlay.IconRing
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 2026-10-14 §3: minimise = moveTaskToBack + one overlay icon window (with the permission, while capture runs). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MinimiseTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val bridge = FakeBridge()
    private val runtime get() = GlassesRuntime.get(app)
    private var keepAlive: ServiceController<CaptureService>? = null
    private val wm get() = app.getSystemService(WindowManager::class.java)

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        GlassesRuntime.bridgeFactory = { bridge }
        ShadowSettings.setCanDrawOverlays(true)
    }

    @After fun tearDown() {
        keepAlive?.destroy()
        ShadowSettings.setCanDrawOverlays(false)
        GlassesRuntime.resetForTests()
        GlassesRuntime.bridgeFactory = { CxrBridge(it) }
    }

    private fun startKeepAlive() {
        val c = Robolectric.buildService(CaptureService::class.java, Intent(app, CaptureService::class.java).setAction(CaptureService.ACTION_KEEP_ALIVE))
        keepAlive = c
        c.create().startCommand(0, 1)
        assertNotNull(CaptureService.liveInstance)
    }

    private fun overlayWindows(): List<WindowManager.LayoutParams> =
        Shadow.extract<ShadowWindowManagerImpl>(wm).views.mapNotNull { it.layoutParams as? WindowManager.LayoutParams }
            .filter { it.type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun minimiseMovesTaskBackAndShowsIconWhenPermitted() {
        startKeepAlive()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            bridge.phone(Channels.SETTINGS, Wire.encode(HudSettings(iconCorner = IconCorner.BOTTOM_LEFT)))
            s.onActivity { it.minimise() }
            s.onActivity { assertEquals(1, it.tasksMovedBack); assertTrue(it.minimised) }
            val w = overlayWindows().single()
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            assertEquals(flags, w.flags and flags, "the icon never takes input")
            assertEquals(android.view.Gravity.BOTTOM or android.view.Gravity.START, w.gravity)
            assertFalse(runtime.hudPresence.fallbackAllowed(), "capture fallback never pulls a minimised HUD forward")
        }
        assertTrue(overlayWindows().isEmpty(), "closing the HUD removes the icon")
    }

    @Test fun noPermissionNoWindowShowsToast() {
        ShadowSettings.setCanDrawOverlays(false)
        startKeepAlive()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { it.minimise() }
            s.onActivity { assertEquals(1, it.tasksMovedBack) }
            assertTrue(overlayWindows().isEmpty())
            assertEquals(MainActivity.NO_OVERLAY_TOAST, ShadowToast.getTextOfLatestToast())
            assertEquals("Allow 'display over other apps' for the icon", MainActivity.NO_OVERLAY_TOAST)
        }
    }

    @Test fun noIconWhileCaptureIsNotRunning() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { it.minimise() }
            assertTrue(overlayWindows().isEmpty())
        }
    }

    @Test fun ringFractionFollowsNextCapture() {
        assertEquals(1f, IconRing.fraction(nextAtMs = 10_000, armedAtMs = 0, nowMs = 0))
        assertEquals(0.25f, IconRing.fraction(nextAtMs = 10_000, armedAtMs = 0, nowMs = 7_500))
        assertEquals(0f, IconRing.fraction(nextAtMs = 10_000, armedAtMs = 0, nowMs = 12_000))
        assertNull(IconRing.fraction(null, 0, 0))
        val now = System.currentTimeMillis()
        app.getSharedPreferences("capture_scheduler", 0).edit()
            .putLong("next_trigger_at_ms", now + 150_000).putLong("armed_at_ms", now - 150_000).commit()
        startKeepAlive()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { it.minimise() }
            val view = assertNotNull(runtime.minimisedIcon.view)
            assertEquals(180f, view.sweepDegrees, 3f) // half the wait left: half the ring
            assertFalse(view.paused)
        }
    }

    @Test fun pausedShowsGlyphNoRing() {
        startKeepAlive()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            runBlocking { runtime.pauses.ensureLoaded(); runtime.togglePause() }
            s.onActivity { it.minimise() }
            advance(1_100)
            val view = assertNotNull(runtime.minimisedIcon.view)
            assertTrue(view.paused)
            assertEquals(0f, view.sweepDegrees)
        }
    }

    @Test fun iconWindowIsSingleAcrossRecreate() {
        startKeepAlive()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { it.minimise() }
            s.onActivity { it.minimise() } // a second minimise moves the same window
            assertEquals(1, overlayWindows().size)
            s.recreate() // the activity starts again: the full HUD is back, no icon
            assertTrue(overlayWindows().isEmpty())
            s.onActivity { it.minimise() }
            assertEquals(1, overlayWindows().size)
            advance(3_000)
            assertEquals(1, overlayWindows().size, "ticks never add windows")
        }
    }

    @Test fun theIconHidesWhenCaptureStops() {
        startKeepAlive()
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { it.minimise() }
            assertEquals(1, overlayWindows().size)
            keepAlive?.destroy(); keepAlive = null
            advance(1_100)
            assertTrue(overlayWindows().isEmpty())
        }
    }

    /** The controller's device check: appops has no effect while the permission is undeclared. */
    @Test fun manifestDeclaresSystemAlertWindow() {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(Manifest.permission.SYSTEM_ALERT_WINDOW in info.requestedPermissions.orEmpty())
    }
}
