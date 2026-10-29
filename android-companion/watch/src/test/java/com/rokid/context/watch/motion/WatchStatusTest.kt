package com.rokid.context.watch.motion

import com.rokid.context.core.model.context.WatchState
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchStatusTest {
    private val sent = MotionMemory(reported = WatchState.WALKING, lastSentMs = 1_000)

    @Test fun linkStatus() {
        assertEquals(LinkStatus.OFF, WatchStatus.link(false, true, sent))
        assertEquals(LinkStatus.PERMISSION_NEEDED, WatchStatus.link(true, false, sent))
        assertEquals(LinkStatus.WAITING, WatchStatus.link(true, true, MotionMemory()))
        assertEquals(LinkStatus.PHONE_NOT_REACHABLE, WatchStatus.link(true, true, sent.copy(unsent = true)))
        assertEquals(LinkStatus.SENDING, WatchStatus.link(true, true, sent))
    }

    @Test fun sentAgo() {
        assertEquals("not sent yet", WatchStatus.sentAgo(0, 5))
        assertEquals("sent just now", WatchStatus.sentAgo(1_000, 30_000))
        assertEquals("sent 2 min ago", WatchStatus.sentAgo(0 + 1, 150_000))
    }

    @Test fun everyStateHasALabel() = WatchState.values().forEach { assert(WatchStatus.stateLabel(it).isNotBlank()) }
}
