package com.rokid.context.canvas.live

import com.rokid.context.core.net.ModelPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTapBoundsTest {
    private var now = 0L
    private fun tap(min: Int = 5, frames: Int = 10, photos: Int = 0, calls: Int = 200, p: ModelPrivacy = ModelPrivacy.AUTO) = LiveTapBounds(0, min, frames, photos, calls, p) { now }
    private fun LiveTapBounds.ok(calls: Int = 1) = beforeFrame(ModelPrivacy.AUTO, true, calls)

    @Test fun framesBoundEndsTheSession() {
        val t = tap(frames = 2)
        assertNull(t.ok()); assertNull(t.ok()); assertEquals(TapStop.FRAMES, t.ok())
        assertEquals(TapStop.FRAMES, t.stopped())
    }

    @Test fun durationBound() {
        val t = tap(min = 1)
        now = 59_000; t.heartbeat(); assertNull(t.ok())
        now = 60_000; t.heartbeat(); assertEquals(TapStop.DURATION, t.ok())
    }

    @Test fun callsBound() {
        val t = tap(calls = 3)
        assertNull(t.ok(2)); assertEquals(TapStop.CALLS, t.ok(2))
    }

    @Test fun photoCapDefaultsToOfflineOnlyAndNeverSendsUnderOfflineOnly() {
        assertFalse(tap().photoMaySend())
        val t = tap(photos = 1)
        assertTrue(t.photoMaySend()); t.photoSent(); assertFalse(t.photoMaySend())
        assertEquals(TapStop.PHOTO_CAP, t.stopped())
        val off = tap(photos = 5, p = ModelPrivacy.OFFLINE_ONLY)
        assertFalse(off.photoMaySend())
    }

    @Test fun privacyFlipZoneAndUnreadableFailClosed() {
        assertEquals(TapStop.PRIVACY_FLIP, tap().beforeFrame(ModelPrivacy.OFFLINE_ONLY, true, 1))
        assertEquals(TapStop.ZONE, tap().beforeFrame(ModelPrivacy.AUTO, false, 1))
        assertEquals(TapStop.UNREADABLE, tap().beforeFrame(null, true, 1))
        assertEquals(TapStop.UNREADABLE, tap().beforeFrame(ModelPrivacy.AUTO, null, 1))
    }

    @Test fun signalsStopAndFirstReasonSticks() {
        for ((sig, want) in listOf<Pair<(LiveTapBounds) -> Unit, TapStop>>(
            { t: LiveTapBounds -> t.onDeleteAll() } to TapStop.DELETE_ALL, { t: LiveTapBounds -> t.onForgetOverlap() } to TapStop.FORGET,
            { t: LiveTapBounds -> t.onHubStopped() } to TapStop.HUB_STOPPED, { t: LiveTapBounds -> t.onUserStop() } to TapStop.USER, { t: LiveTapBounds -> t.onZoneEntered() } to TapStop.ZONE,
        )) { val t = tap(); sig(t); assertEquals(want, t.ok()) }
        val t = tap(); t.onForgetOverlap(); t.onDeleteAll(); assertEquals(TapStop.FORGET, t.ok())
        assertFalse(t.photoMaySend())
    }

    @Test fun missedHeartbeatClosesThePage() {
        val t = tap()
        now = 31_000
        assertEquals(TapStop.PAGE_CLOSED, t.ok())
    }

    @Test fun badBoundsAreRejected() {
        for (f in listOf({ tap(min = 16) }, { tap(min = 0) }, { tap(frames = 51) }, { tap(frames = 5, photos = 6) }, { tap(calls = 201) })) {
            val e = runCatching { f() }.exceptionOrNull(); assertTrue(e is IllegalArgumentException)
        }
    }
}
