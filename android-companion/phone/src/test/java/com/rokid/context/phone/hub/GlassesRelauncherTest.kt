package com.rokid.context.phone.hub

import com.rokid.context.core.model.LinkState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix wave H2: the phone reopens the glasses app after Rokid closes it, only when every guard allows. */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesRelauncherTest {
    private class Guards : RelaunchGuards {
        var call = true; var media = true; var wornNow: Boolean? = null
        override fun callIdle() = call
        override fun mediaIdle() = media
        override fun worn() = wornNow
    }

    private class Rig(val t: TestScope, val guards: Guards = Guards(), var on: Boolean = true) {
        val state = MutableStateFlow(LinkState.CONNECTED)
        var reopens = 0
        val logs = ArrayList<String>()
        val r = GlassesRelauncher(t.backgroundScope, state, { on }, guards, { reopens++; state.value = LinkState.CONNECTING }, { t.currentTime }, { logs += it })
            .also { r -> r.start() }
        fun close() { state.value = LinkState.MINIMISED; state.value = LinkState.CLOSED_ON_GLASSES }
    }

    private fun TestScope.rig(g: Guards = Guards(), on: Boolean = true) = Rig(this, g, on).also { runCurrent() }

    @Test fun reopensEightSecondsAfterTheClose() = runTest {
        val r = rig()
        r.close(); runCurrent()
        advanceTimeBy(7_900); assertEquals(0, r.reopens)
        advanceTimeBy(200); assertEquals(1, r.reopens)
    }

    @Test fun neverWhileACallIsActiveAndGoesAheadOnceItEnds() = runTest {
        val g = Guards().apply { call = false }
        val r = rig(g)
        r.close(); runCurrent()
        advanceTimeBy(120_000); assertEquals("a call blocks every attempt", 0, r.reopens)
        g.call = true
        advanceTimeBy(10_100); assertEquals("the attempt goes ahead when the call ended", 1, r.reopens)
    }

    @Test fun neverWhileMediaPlaysOrWhenKnownNotWorn() = runTest {
        val g = Guards().apply { media = false }
        val r = rig(g)
        r.close(); runCurrent(); advanceTimeBy(60_000); assertEquals(0, r.reopens)
        g.media = true; g.wornNow = false
        advanceTimeBy(30_000); assertEquals("known not worn blocks", 0, r.reopens)
        g.wornNow = true
        advanceTimeBy(10_100); assertEquals(1, r.reopens)
    }

    @Test fun unknownWearIsNotABlocker() = runTest {
        val r = rig(Guards().apply { wornNow = null })
        r.close(); runCurrent(); advanceTimeBy(8_100); assertEquals(1, r.reopens)
    }

    @Test fun settingOffNeverReopensAndTurningItOnDoes() = runTest {
        val r = rig(on = false)
        r.close(); runCurrent(); advanceTimeBy(60_000); assertEquals(0, r.reopens)
        r.on = true; advanceTimeBy(10_100); assertEquals(1, r.reopens)
    }

    @Test fun backoffGrowsForQuickRepeatsAndResetsAfterAStableSession() = runTest {
        val r = rig()
        r.close(); runCurrent(); advanceTimeBy(8_100); assertEquals(1, r.reopens)
        r.state.value = LinkState.CONNECTED // up for only a moment
        r.close(); runCurrent()
        advanceTimeBy(29_000); assertEquals("second step is 30 s", 1, r.reopens)
        advanceTimeBy(1_500); assertEquals(2, r.reopens)
        r.state.value = LinkState.CONNECTED
        advanceTimeBy(70_000) // a stable session
        r.close(); runCurrent(); advanceTimeBy(8_100); assertEquals("back to 8 s", 3, r.reopens)
    }

    @Test fun atMostSixPerHour() = runTest {
        val r = rig()
        repeat(6) {
            r.state.value = LinkState.CONNECTED; advanceTimeBy(70_000)
            r.close(); runCurrent(); advanceTimeBy(8_100)
        }
        assertEquals(6, r.reopens)
        r.state.value = LinkState.CONNECTED; advanceTimeBy(70_000)
        r.close(); runCurrent(); advanceTimeBy(60_000)
        assertEquals("the seventh waits for the hour", 6, r.reopens)
        advanceTimeBy(3_600_000)
        assertEquals(7, r.reopens)
    }

    /** (D): a user exit stands the phone down with no timer; an explicit open ends it at once. */
    @Test fun userExitStandsDownUntilAnExplicitOpen() = runTest {
        val r = rig()
        r.r.onUserExit()
        r.close(); runCurrent(); advanceTimeBy(3_600_000)
        assertEquals(0, r.reopens); assertTrue(r.r.standingDown)
        r.r.onExplicitOpen(); assertFalse(r.r.standingDown)
        r.state.value = LinkState.CONNECTED; runCurrent()
        r.close(); runCurrent(); advanceTimeBy(8_100); assertEquals(1, r.reopens)
    }

    @Test fun aSessionConnectingAgainEndsTheStandDown() = runTest {
        val r = rig()
        r.r.onUserExit(); r.close(); runCurrent()
        r.state.value = LinkState.CONNECTED; runCurrent()
        assertFalse(r.r.standingDown)
    }

    @Test fun aLeftBackgroundJobIsCancelledWhenTheLinkMoves() = runTest {
        val r = rig(Guards().apply { call = false })
        r.close(); runCurrent(); advanceTimeBy(20_000)
        r.state.value = LinkState.CONNECTED; runCurrent()
        r.guards.call = true; advanceTimeBy(60_000)
        assertEquals(0, r.reopens)
    }
}
