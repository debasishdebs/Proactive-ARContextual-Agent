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
    private open class Guards : RelaunchGuards {
        var call = true; var media = true; var wornNow: Boolean? = null; var inputs: String? = null
        override fun callIdle() = call
        override fun callInputs() = inputs
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

    /** 2026-10-28 diagnostics: a long "call active" block says why (values only), when the inputs change, and when it clears, without growing the log. */
    @Test fun aLongBlockIsLoggedWithItsInputsRateLimitedAndTheClearIsLogged() = runTest {
        val g = Guards().apply { call = false; inputs = "audioMode=0 callState=unreadable sinceNormal=<15s sinceScene=<15s" }
        val r = rig(g)
        r.close(); runCurrent()
        advanceTimeBy(30 * 60_000L)
        val blocked = r.logs.filter { it.startsWith("relaunch: blocked") }
        assertTrue("the first line and a heartbeat every 5 min: $blocked", blocked.size in 5..8)
        assertTrue(blocked.first(), blocked.first().contains("(call active) audioMode=0 callState=unreadable sinceNormal=<15s sinceScene=<15s poll=alive for=0 s"))
        assertEquals("the existing waiting line is logged once, as before", 1, r.logs.count { it == "relaunch: waiting (call active)" })
        val before = blocked.size
        g.inputs = "audioMode=2 callState=unreadable sinceNormal=<15s sinceScene=>=30m"
        advanceTimeBy(60_000L)
        val after = r.logs.filter { it.startsWith("relaunch: blocked") }
        assertTrue("a changed input is logged once: ${after.drop(before)}", after.size - before in 1..2 && after.last().contains("audioMode=2"))
        g.call = true
        advanceTimeBy(10_100L)
        assertEquals(1, r.reopens)
        assertTrue(r.logs.any { it.startsWith("relaunch: block cleared (was call active, ") })
        assertTrue("nothing caller-identifying: values and buckets only", r.logs.none { Regex("\\d{7,}").containsMatchIn(it) })
    }

    @Test fun aFlappingInputCannotGrowTheLogFasterThanTheChangeFloor() = runTest {
        val g = Guards().apply { call = false }
        val r = rig(g)
        r.close(); runCurrent()
        var n = 0
        repeat(60) { g.inputs = "audioMode=${n++ % 3} callState=unreadable sinceNormal=<15s sinceScene=<15s"; advanceTimeBy(10_000L) } // 10 minutes of a flapping mode
        val blocked = r.logs.count { it.startsWith("relaunch: blocked") }
        assertTrue("at most one line per 30 s: $blocked", blocked <= 21)
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
        r.on = true; r.r.onPositiveSignal(); advanceTimeBy(8_100); assertEquals("switching it on is a positive signal", 1, r.reopens)
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

    /** Fix wave I4, v0.9.9: our own reopen that fails (the glasses are away) is retried on [BackgroundPolicy]'s ladder (5, 15, 30, 60 min) and the cap, nothing else. */
    @Test fun aFailedReopenIsRetriedOnTheBackgroundLadderAndTheCap() = runTest {
        val r = rig()
        r.close(); runCurrent(); advanceTimeBy(8_100); assertEquals(1, r.reopens)
        r.state.value = LinkState.OFFLINE; runCurrent()
        advanceTimeBy(299_000); assertEquals(1, r.reopens)
        advanceTimeBy(2_000); assertEquals("first retry after 5 min", 2, r.reopens)
        r.state.value = LinkState.OFFLINE; runCurrent()
        advanceTimeBy(14 * 60_000L); assertEquals(2, r.reopens)
        advanceTimeBy(61_000); assertEquals("second retry after 15 min", 3, r.reopens)
        r.state.value = LinkState.OFFLINE; runCurrent()
        advanceTimeBy(29 * 60_000L); assertEquals(3, r.reopens)
        advanceTimeBy(61_000); assertEquals("third retry after 30 min", 4, r.reopens)
        assertTrue(r.logs.any { it.contains("trying again later") })
        assertTrue("never more than 6 per hour", r.reopens <= 6)
    }

    @Test fun theLadderStartsAgainOnceTheGlassesAreConnected() = runTest {
        val r = rig()
        r.close(); runCurrent(); advanceTimeBy(8_100)
        r.state.value = LinkState.OFFLINE; runCurrent(); advanceTimeBy(301_000); assertEquals(2, r.reopens)
        r.state.value = LinkState.CONNECTED; runCurrent(); advanceTimeBy(70_000) // a stable session
        r.close(); runCurrent(); advanceTimeBy(8_100); assertEquals(3, r.reopens)
        r.state.value = LinkState.OFFLINE; runCurrent()
        advanceTimeBy(299_000); assertEquals(3, r.reopens); advanceTimeBy(2_000); assertEquals("5 min again, not 15", 4, r.reopens)
    }

    @Test fun aPositiveSignalStartsTheFailedReopenLadderAgain() = runTest {
        val r = rig()
        r.close(); runCurrent(); advanceTimeBy(8_100)
        r.state.value = LinkState.OFFLINE; runCurrent(); advanceTimeBy(301_000); assertEquals(2, r.reopens)
        r.r.onPositiveSignal()
        r.state.value = LinkState.OFFLINE; runCurrent()
        advanceTimeBy(299_000); assertEquals(2, r.reopens); advanceTimeBy(2_000); assertEquals(3, r.reopens)
    }

    /** v0.9.9: with the setting off the attempt loop ends at once, no 10 s poll for as long as the glasses app stays closed. */
    @Test fun withTheSettingOffNothingPollsAndNothingReopens() = runTest {
        val r = rig(on = false)
        r.close(); runCurrent(); advanceTimeBy(8_100)
        val logged = r.logs.size
        advanceTimeBy(3_600_000L); assertEquals(0, r.reopens); assertEquals("no poll, no heartbeat", logged, r.logs.size)
        assertTrue(r.logs.any { it.contains("waiting (setting off)") })
    }

    @Test fun theHourlyCapSleepsUntilItsOldestAttemptAgesOutInsteadOfPollingEveryTenSeconds() = runTest {
        val state = MutableStateFlow(LinkState.CONNECTED); var reopens = 0; val logs = ArrayList<String>()
        GlassesRelauncher(backgroundScope, state, { true }, Guards(), { reopens++; state.value = LinkState.CONNECTING }, { currentTime }, { logs += it }, stepsMs = listOf(1_000L), maxPerHour = 1).start()
        runCurrent()
        state.value = LinkState.CLOSED_ON_GLASSES; runCurrent(); advanceTimeBy(1_100); assertEquals(1, reopens)
        state.value = LinkState.MINIMISED; state.value = LinkState.CLOSED_ON_GLASSES; runCurrent(); advanceTimeBy(1_100)
        val logged = logs.size
        advanceTimeBy(3_000_000L); assertEquals(1, reopens); assertTrue("no 10 s polling: ${logs.size - logged} lines", logs.size - logged <= 2)
        advanceTimeBy(700_000L); assertEquals("goes ahead when the hour is over", 2, reopens)
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
        assertTrue("user ruling 2026-10-28: a VoIP mode is not a call", AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_IN_COMMUNICATION, idle, 60_000, far))
        assertFalse(AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_IN_CALL, idle, 60_000, far))
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

    /** User ruling 2026-10-28: only a real call blocks (IN_CALL, CALL_SCREENING, a readable non-idle call state); every other non-NORMAL mode does not. */
    @Test fun onlyARealCallModeIsBusy() {
        assertFalse(AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_IN_CALL, null, far, far))
        assertFalse(AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_CALL_SCREENING, null, far, far))
        assertFalse(AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_NORMAL, android.telephony.TelephonyManager.CALL_STATE_OFFHOOK, far, far))
        assertTrue(AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_IN_COMMUNICATION, null, far, far))
        assertFalse("a ringing call is a call", AndroidRelaunchGuards.callIdle(android.media.AudioManager.MODE_RINGTONE, null, far, far))
    }

    /** The relaunch against the real decision: a VoIP/meeting mode lets it proceed; a real call mode blocks until it ends. */
    @Test fun aVoipModeLetsTheRelaunchProceedAndACallModeBlocksIt() = runTest {
        var mode = android.media.AudioManager.MODE_IN_COMMUNICATION
        val g = object : Guards() { override fun callIdle() = AndroidRelaunchGuards.callIdle(mode, null, far, far) }
        val voip = rig(g)
        voip.close(); runCurrent(); advanceTimeBy(8_100); assertEquals("VoIP does not defer the reopen", 1, voip.reopens)
        mode = android.media.AudioManager.MODE_IN_CALL
        val call = rig(g)
        call.close(); runCurrent(); advanceTimeBy(60_000); assertEquals("a real call does", 0, call.reopens)
        mode = android.media.AudioManager.MODE_NORMAL
        advanceTimeBy(10_100); assertEquals(1, call.reopens)
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
