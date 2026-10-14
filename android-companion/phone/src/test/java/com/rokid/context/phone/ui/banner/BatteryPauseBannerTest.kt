package com.rokid.context.phone.ui.banner

import com.rokid.context.memory.IndexHealth
import org.junit.Assert.assertEquals
import org.junit.Test

/** Gaps G6: the user sees why on-device search is paused, in the existing index banner slot (no raw text). */
class BatteryPauseBannerTest {
    @Test fun bannerShowsBatteryPauseReason() {
        val state = IndexState.of(IndexHealth.NONE, unstable = false, embedBound = true, batteryPaused = true)
        assertEquals(IndexState.BATTERY_PAUSED, state)
        val b = BannerResolver.resolve(BannerInputs(index = state))!!
        assertEquals(BannerKind.INDEX, b.kind)
        assertEquals("On-device search paused to save battery", b.title)
        assertEquals(null, b.action)
        assertEquals("unstable still wins", IndexState.UNSTABLE, IndexState.of(IndexHealth.NONE, unstable = true, embedBound = true, batteryPaused = true))
        assertEquals("no EMBED model: nothing paused", IndexState.NONE, IndexState.of(IndexHealth.NONE, unstable = false, embedBound = false, batteryPaused = true))
        assertEquals(IndexState.RUNNING, IndexState.of(IndexHealth.RUNNING, unstable = false, embedBound = true, batteryPaused = false))
    }
}
