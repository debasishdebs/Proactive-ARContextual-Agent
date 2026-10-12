package com.rokid.context.phone.ui.status

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.GlassesStatus
import com.rokid.context.core.model.Health
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.NOW
import com.rokid.context.phone.ui.banner.BannerKind
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLineTest {
    private val running = FakeGlassesStatusSource.connected(Fixtures.stateFrame(nextAtMs = NOW + 252_000))

    @Test fun runningShowsCountdownAndIsLive() {
        assertEquals("Capturing · next 4:12", CaptureLine.text(running, pipelineEnabled = true, nowMs = NOW))
        assertTrue(CaptureLine.live(running, true))
        assertFalse(CaptureLine.paused(running, true))
    }

    @Test fun countdownRoundsUpAndHandlesPastAndHours() {
        assertEquals("0:01", CaptureLine.countdown(NOW + 1, NOW))
        assertEquals("now", CaptureLine.countdown(NOW - 5, NOW))
        assertEquals("1:02:03", CaptureLine.countdown(NOW + 3_723_000, NOW))
    }

    @Test fun pipelineOffOrPauseRecordsShowPaused() {
        assertEquals("Paused", CaptureLine.text(running, pipelineEnabled = false, nowMs = NOW))
        assertFalse(CaptureLine.live(running, false))
        val paused = FakeGlassesStatusSource.connected(Fixtures.stateFrame(capture = CaptureState.Paused(listOf(PauseRecord("p", PauseOrigin.GLASSES, 1)))))
        assertEquals("Paused", CaptureLine.text(paused, true, NOW))
        assertTrue(CaptureLine.paused(paused, true))
    }

    @Test fun offlineConnectingAndStaleFrames() {
        assertEquals("Glasses offline", CaptureLine.text(FakeGlassesStatusSource.OFFLINE, true, NOW))
        assertEquals("Connecting to glasses…", CaptureLine.text(GlassesStatus(LinkState.CONNECTING, null, 0, null, null), true, NOW))
        assertEquals("Glasses offline", CaptureLine.text(running.copy(link = LinkState.OFFLINE), true, NOW))
        assertFalse(CaptureLine.live(running.copy(link = LinkState.OFFLINE), true))
    }

    @Test fun deferredAndBlockedShowTheirReason() {
        val deferred = FakeGlassesStatusSource.connected(Fixtures.stateFrame(capture = CaptureState.Deferred("Low battery")))
        assertEquals("Deferred · Low battery", CaptureLine.text(deferred, true, NOW))
        val blocked = FakeGlassesStatusSource.connected(Fixtures.stateFrame(capture = CaptureState.Blocked("Camera busy")))
        assertEquals("Blocked · Camera busy", CaptureLine.text(blocked, true, NOW))
    }

    @Test fun statusUiPicksBatteryAndBanner() {
        val ui = StatusUi.build(running, pipelineEnabled = true, storagePct = 10, nowMs = NOW)
        assertEquals(82, ui.batteryPct)
        assertEquals(null, ui.banner)
        val blocked = FakeGlassesStatusSource.connected(Fixtures.stateFrame(ai = Health.Blocked("AI key rejected")))
        assertEquals(BannerKind.AI_BLOCKED, StatusUi.build(blocked, true, null, NOW).banner?.kind)
        assertEquals(BannerKind.GLASSES_DISCONNECTED, StatusUi.build(FakeGlassesStatusSource.OFFLINE, true, null, NOW).banner?.kind)
    }
}
