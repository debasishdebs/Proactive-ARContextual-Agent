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

    private class Rig(val t: TestScope, val guards: Guards = Guards(), var on: Boolean = true, val standDown: StandDown = InMemoryStandDown(), var fullScreen: Boolean = false, initial: LinkState = LinkState.CONNECTED) {
        val state = MutableStateFlow(initial)
        var reopens = 0
        val logs = ArrayList<String>()
        val r = GlassesRelauncher(
            t.backgroundScope, state, { on }, guards, { reopens++; state.value = LinkState.CONNECTING }, { t.currentTime }, { logs += it },
            standDown = standDown, reopenIsFullScreen = { fullScreen },
        ).also { r -> r.start() }
        fun close() { state.value = LinkState.MINIMISED; state.value = LinkState.CLOSED_ON_GLASSES }
    }

    private fun TestScope.rig(g: Guards = Guards(), on: Boolean = true, standDown: StandDown = InMemoryStandDown(), fullScreen: Boolean = false, initial: LinkState = LinkState.CONNECTED) =
        Rig(this, g, on, standDown, fullScreen, initial).also { runCurrent() }

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

    /** Fix wave I: the reopen goes to icon mode, so music alone never blocks it; a (hypothetical) full-screen reopen still waits for it. */
    @Test fun mediaBlocksOnlyAFullScreenReopen() = runTest {
        val iconRig = rig(Guards().apply { media = false })
        iconRig.close(); runCurrent(); advanceTimeBy(8_100); assertEquals("icon-mode reopen proceeds while music plays", 1, iconRig.reopens)
        val full = rig(Guards().apply { media = false }, fullScreen = true)
        full.close(); runCurrent(); advanceTimeBy(60_000); assertEquals(0, full.reopens)
        full.guards.media = true; advanceTimeBy(10_100); assertEquals(1, full.reopens)
    }

    @Test fun neverWhenKnownNotWorn() = runTest {
        val g = Guards()
        val r = rig(g)
        r.close(); runCurrent()
        g.wornNow = false
        advanceTimeBy(60_000); assertEquals("known not worn blocks", 0, r.reopens)
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

    /** Fix wave I2: the stand-down survives a phone restart (a new relauncher on the same store). */
    @Test fun theStandDownSurvivesANewRelauncher() = runTest {
        val store = InMemoryStandDown()
        rig(standDown = store).r.onUserExit()
        val again = rig(standDown = store, initial = LinkState.OFFLINE) // a restarted phone starts offline
        again.close(); runCurrent(); advanceTimeBy(3_600_000)
        assertEquals("a restarted phone does not reopen what the wearer exited", 0, again.reopens)
        again.r.onExplicitOpen(); again.state.value = LinkState.CONNECTED; runCurrent()
        again.close(); runCurrent(); advanceTimeBy(8_100); assertEquals(1, again.reopens)
    }

    /** Fix wave I4: our own reopen that fails (the link is OFFLINE again) is retried by the relauncher with backoff and the cap, nothing else. */
    @Test fun aFailedReopenIsRetriedByTheRelauncherWithBackoffAndTheCap() = runTest {
        val r = rig()
        r.close(); runCurrent(); advanceTimeBy(8_100); assertEquals(1, r.reopens)
        r.state.value = LinkState.OFFLINE; runCurrent()
        advanceTimeBy(29_000); assertEquals(1, r.reopens)
        advanceTimeBy(1_500); assertEquals("second step", 2, r.reopens)
        r.state.value = LinkState.OFFLINE; runCurrent()
        advanceTimeBy(121_000); assertEquals("third step 2 min", 3, r.reopens)
        repeat(3) { r.state.value = LinkState.OFFLINE; runCurrent(); advanceTimeBy(600_100) } // 5 min then 10 min steps
        assertTrue("never more than 6 per hour", r.reopens <= 6)
        assertTrue(r.logs.any { it.contains("trying again later") })
    }

    @Test fun anOfflineLinkThatIsNotOurFailedReopenIsLeftAlone() = runTest {
        val r = rig()
        r.state.value = LinkState.OFFLINE; runCurrent(); advanceTimeBy(600_000)
        assertEquals(0, r.reopens)
        r.close(); runCurrent(); advanceTimeBy(8_100); assertEquals(1, r.reopens)
        r.state.value = LinkState.CONNECTED; runCurrent(); r.state.value = LinkState.OFFLINE; runCurrent() // it connected, then BT dropped much later
        advanceTimeBy(600_000); assertEquals(1, r.reopens)
    }

    @Test fun theAutoOpenWindowFollowsOurReopen() = runTest {
        val r = rig()
        assertFalse(r.r.reopenedRecently())
        r.close(); runCurrent(); advanceTimeBy(8_100)
        assertTrue(r.r.reopenedRecently())
        advanceTimeBy(GlassesRelauncher.AUTO_OPEN_WINDOW_MS + 1_000); assertFalse(r.r.reopenedRecently())
    }

    private val far = Long.MAX_VALUE / 2

    @Test fun theCallGuardFailsClosedWithoutThePhoneStatePermissionForAWhileAfterAScene() {
        val normal = android.media.AudioManager.MODE_NORMAL
        val idle = android.telephony.TelephonyManager.CALL_STATE_IDLE
        assertTrue(AndroidRelaunchGuards.callIdle(normal, idle, 0, far))
        assertFalse(AndroidRelaunchGuards.callIdle(normal, android.telephony.TelephonyManager.CALL_STATE_RINGING, 60_000, far))
        assertFalse(AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_IN_COMMUNICATION, idle, 60_000, far))
        assertFalse("unknown call state right after the scene counts as a call", AndroidRelaunchGuards.callIdle(normal, null, 5_000, far))
        assertTrue(AndroidRelaunchGuards.callIdle(normal, null, AndroidRelaunchGuards.UNKNOWN_CALL_HOLD_MS, far))
    }

    @Test fun notIdleWithinFifteenSecondsOfNonNormalMode() {
        val normal = android.media.AudioManager.MODE_NORMAL
        assertFalse(AndroidRelaunchGuards.callIdle(normal, null, far, AndroidRelaunchGuards.CALL_END_SETTLE_MS - 1))
        assertTrue(AndroidRelaunchGuards.callIdle(normal, null, far, AndroidRelaunchGuards.CALL_END_SETTLE_MS))
    }

    @Test fun idleAfterSettleAndHold() {
        val normal = android.media.AudioManager.MODE_NORMAL
        assertFalse("settled, but the 30 s hold after the scene is still running", AndroidRelaunchGuards.callIdle(normal, null, 29_999, 60_000))
        assertTrue(AndroidRelaunchGuards.callIdle(normal, null, 30_000, 15_000))
    }

    @Test fun ringtoneModeIsBusy() {
        assertFalse(AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_RINGTONE, null, far, far))
    }

    @Test fun everyDecisionLeavesACountsOnlyLine() = runTest {
        val g = Guards().apply { call = false }
        val r = rig(g)
        r.close(); runCurrent()
        assertTrue(r.logs.toString(), r.logs.any { it == "relaunch: link CONNECTED->CLOSED_ON_GLASSES" })
        assertTrue(r.logs.toString(), r.logs.any { it == "relaunch: attempt in 8 s (step 0)" })
        advanceTimeBy(8_100); assertTrue(r.logs.toString(), r.logs.any { it == "relaunch: waiting (call active)" })
    }
}

/** The watchdog's trigger: [GlassesRelauncher.attemptNow] reuses every guard, the back-off bookkeeping and the hourly cap. */
@OptIn(ExperimentalCoroutinesApi::class)
class GlassesRelauncherAttemptNowTest {
    private class G : RelaunchGuards { var call = true; var wornNow: Boolean? = null; override fun callIdle() = call; override fun mediaIdle() = true; override fun worn() = wornNow }
    private fun TestScope.make(g: G = G(), sd: StandDown = InMemoryStandDown(), max: Int = 6, on: () -> Boolean = { true }): Pair<GlassesRelauncher, IntArray> {
        val n = IntArray(1)
        val r = GlassesRelauncher(backgroundScope, kotlinx.coroutines.flow.MutableStateFlow(LinkState.CONNECTED), on, g, { n[0]++ }, { currentTime }, {}, standDown = sd, maxPerHour = max)
        return r to n
    }

    @Test fun launchesWhenEveryGuardPasses() = runTest { val (r, n) = make(); assertEquals(null, r.attemptNow()); assertEquals(1, n[0]) }
    @Test fun callActiveDoesNotLaunch() = runTest { val (r, n) = make(G().apply { call = false }); assertEquals("call active", r.attemptNow()); assertEquals(0, n[0]) }
    @Test fun standDownDoesNotLaunch() = runTest { val sd = InMemoryStandDown().apply { enter(0) }; val (r, n) = make(sd = sd); assertEquals("stood down", r.attemptNow()); assertEquals(0, n[0]) }
    @Test fun notWornDoesNotLaunch() = runTest { val (r, n) = make(G().apply { wornNow = false }); assertEquals("glasses not worn", r.attemptNow()); assertEquals(0, n[0]) }
    @Test fun settingOffDoesNotLaunch() = runTest { val (r, n) = make(on = { false }); assertEquals("setting off", r.attemptNow()); assertEquals(0, n[0]) }
    @Test fun hourlyCapStopsTheWatchdogToo() = runTest {
        val (r, n) = make(max = 2)
        assertEquals(null, r.attemptNow()); assertEquals(null, r.attemptNow()); assertEquals("hourly limit", r.attemptNow()); assertEquals(2, n[0])
        advanceTimeBy(3_600_100); assertEquals(null, r.attemptNow()); assertEquals(3, n[0])
    }
}
