package com.rokid.context.glasses.overlay

import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.hud.render.HudConnection
import com.rokid.context.core.hud.render.HudModel
import com.rokid.context.core.hud.render.HudNav
import com.rokid.context.core.hud.render.HudRoot
import com.rokid.context.core.hud.render.HudView
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.FrameAck
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.HudPage
import com.rokid.context.core.model.HudSettings
import com.rokid.context.core.model.IconCorner
import com.rokid.context.core.model.StateFrame
import com.rokid.context.glasses.GlassesRuntime
import com.rokid.context.glasses.capture.OutboundBuffer
import com.rokid.context.glasses.link.FrameUplink
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** "Photo sent" cue: a frame delivered (or buffered) shows a ~1 s check on the Glance timer and pulses the icon ring. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoCueTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val now = 1_793_000_000_000L
    private var icon: MinimisedIcon? = null

    @After fun tearDown() {
        icon?.hide()
        ShadowSettings.setCanDrawOverlays(false)
        GlassesRuntime.resetForTests()
    }

    /** One buffered frame, an uplink whose phone acks it at once, and the cue it pulses on delivery. */
    private fun TestScope.deliverOne(cue: PhotoCue): List<String> {
        val delivered = mutableListOf<String>()
        val b = OutboundBuffer(File(tmp.root, "out"), clock = { now }, elapsed = { 0L }, bootId = { "" })
        b.add("g1", ByteArray(100) { (it % 7).toByte() }, now, null, null, 80)
        lateinit var u: FrameUplink
        u = FrameUplink(this, b, sendChunk = { c ->
            if (c.meta.chunkIdx == c.meta.chunkCount - 1) u.onAck(FrameAck(frameId = c.meta.frameId, ok = true))
            0
        }, canSend = { true }, onDelivered = { delivered += it; cue.pulse() })
        u.kick()
        runCurrent()
        return delivered
    }

    private fun model(cue: Boolean) = HudModel(
        view = HudView(HudPage.GLANCE, null),
        frame = StateFrame(seq = 1, capture = CaptureState.Running, intervalSec = 300, phoneLinked = true, aiHealth = Health.Ok),
        settings = HudSettings(), connection = HudConnection.LIVE, batteryPct = 82, paused = false,
        nextCaptureAtMs = now + 300_000, nowMs = now, nav = HudNav(), confirmHighlightYes = true, photoCue = cue,
    )

    @Test fun deliveredFrameShowsBriefCueOnGlance() {
        var shown = false
        runTest {
            val cue = PhotoCue(this)
            assertFalse(cue.active.value)
            assertEquals(listOf("g1"), deliverOne(cue))
            shown = cue.active.value
        }
        assertTrue(shown, "a delivered frame turns the cue on")
        compose.setContent { HudRoot(model(cue = shown)) }
        compose.onNodeWithText(PhotoCue.CHECK).assertExists()
        compose.onNodeWithText("5:00").assertExists() // the countdown is still there, next to the check
        assertEquals("\u2713", PhotoCue.CHECK)
    }

    @Test fun glanceHasNoCheckWithoutTheCue() {
        compose.setContent { HudRoot(model(cue = false)) }
        compose.onNodeWithText(PhotoCue.CHECK).assertDoesNotExist()
    }

    @Test fun minimisedIconPulsesOnDelivery() = runTest {
        ShadowSettings.setCanDrawOverlays(true)
        val cue = PhotoCue(this)
        val i = MinimisedIcon(app, app.getSystemService(WindowManager::class.java)) { IconState(running = true, fraction = 0.5f, paused = false, cue = cue.active.value) }
        icon = i
        assertTrue(i.show(IconCorner.TOP_RIGHT))
        val view = assertNotNull(i.view)
        assertFalse(view.pulse)
        assertEquals(180f, view.sweepDegrees, 1f)
        deliverOne(cue)
        i.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.pulse, "delivery pulses the ring")
        assertEquals(360f, view.sweepDegrees, "a pulse lights the whole ring")
        advanceTimeBy(PhotoCue.DURATION_MS + 50); runCurrent()
        i.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(view.pulse)
        assertEquals(180f, view.sweepDegrees, 1f)
    }

    @Test fun cueClearsAfterAboutOneSecond() = runTest {
        assertEquals(1_000L, PhotoCue.DURATION_MS)
        val cue = PhotoCue(this)
        cue.pulse()
        assertTrue(cue.active.value)
        advanceTimeBy(900); runCurrent()
        assertTrue(cue.active.value, "still on at 0.9 s")
        advanceTimeBy(150); runCurrent()
        assertFalse(cue.active.value, "off by 1.05 s")
        // A second frame inside the window extends it: the first timer must not cut the second cue short.
        cue.pulse()
        advanceTimeBy(800); runCurrent()
        cue.pulse()
        advanceTimeBy(300); runCurrent()
        assertTrue(cue.active.value, "the second pulse's second has not run out")
        advanceTimeBy(750); runCurrent()
        assertFalse(cue.active.value)
    }

    @Test fun bufferedFrameCuesThroughTheRuntime() {
        val runtime = GlassesRuntime.get(app)
        assertFalse(runtime.photoCue.active.value)
        runtime.onFrameBuffered()
        assertTrue(runtime.photoCue.active.value)
    }
}
