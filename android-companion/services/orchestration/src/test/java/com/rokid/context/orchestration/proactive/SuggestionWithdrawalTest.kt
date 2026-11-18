package com.rokid.context.orchestration.proactive

import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.Verbosity
import com.rokid.context.orchestration.nudge.NudgeHud
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Codex v3.1 r3 P1-2: driving closing and the quiet-hours boundary withdraw every displayed suggestion from the surfaces (HUD holder, Nexus: [ProactiveEnvironment.surfaces]) through process-wide listeners, without Home;
 * the phone feed observes [SuggestionGateEvents.closes] and clears. No timer runs unless a suggestion was displayed ([QuietBoundaryWatch.arm]).
 */
class SuggestionWithdrawalTest {
    private class Rec : SuggestionSurfaces { var all = 0; override fun withdrawAll() { all++ } }
    private val rec = Rec()
    private var savedSurfaces: SuggestionSurfaces = SuggestionSurfaces.None
    private var savedDispatch: (() -> Unit) -> Unit = { it() }
    private var t = 10_000_000_000L
    private val tracker = DrivingTracker { t }
    private val zone = ZoneId.of("Asia/Kolkata")

    @Before fun setUp() {
        savedSurfaces = ProactiveEnvironment.surfaces; ProactiveEnvironment.surfaces = rec
        savedDispatch = SuggestionGateEvents.dispatch; SuggestionGateEvents.dispatch = { it() }
        SuggestionWithdrawal.install()
    }
    @After fun tearDown() { SuggestionWithdrawal.uninstall(); SuggestionGateEvents.dispatch = savedDispatch; ProactiveEnvironment.surfaces = savedSurfaces }

    private fun notDriving() { repeat(70) { tracker.heartbeat(false); t += 10_000 }; tracker.calm(CalmSource.PHONE_STEPS, Calm.WALKING); tracker.calm(CalmSource.PHONE_ACTIVITY, Calm.STILL); assertEquals(DrivingState.NOT_DRIVING, tracker.assess().state) }

    @Test fun drivingStartingWithdrawsEverySurfaceAndTellsTheFeed() {
        notDriving()
        val before = SuggestionGateEvents.closes.value
        tracker.vehicle(0)
        assertEquals("surfaces withdrawn once", 1, rec.all); assertTrue("the feed's close event fired", SuggestionGateEvents.closes.value > before)
        assertEquals(DrivingState.DRIVING, tracker.assess().state)
        tracker.vehicle(0); assertEquals("already blocking: no second event", 1, rec.all)
    }

    @Test fun unknownAfterCalmEvidenceAgesOutAlsoWithdraws() {
        notDriving(); t += DrivingTracker.FRESH_MS + 1
        tracker.heartbeat(false) // the update that observes the state falling to UNKNOWN
        assertEquals(DrivingState.UNKNOWN, tracker.assess().state); assertEquals(1, rec.all)
    }

    @Test fun anUpdateThatStaysNotDrivingWithdrawsNothing() {
        notDriving(); tracker.heartbeat(false); tracker.calm(CalmSource.PHONE_STEPS, Calm.WALKING)
        assertEquals(0, rec.all)
    }

    @Test fun aThrowingListenerNeverBreaksTheTrackerOrTheOthers() {
        val bad: () -> Unit = { throw IllegalStateException("x") }
        SuggestionGateEvents.addWithdrawListener(bad)
        try { notDriving(); tracker.vehicle(0); assertEquals(1, rec.all) } finally { SuggestionGateEvents.removeWithdrawListener(bad) }
    }

    // ---- the quiet-hours boundary --------------------------------------------------------------------------------------------------------------------

    private class Sched { val scheduled = ArrayList<Pair<Long, () -> Unit>>(); var cancelled = 0
        fun schedule(d: Long, task: () -> Unit): AutoCloseable { scheduled += d to task; return AutoCloseable { cancelled++ } } }
    private fun at(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 18, h, m).atZone(zone).toInstant().toEpochMilli()
    private val night = QuietHours(22 * 60, 7 * 60)

    @Test fun theWatchFiresTheNextQuietStartAndWithdrawsEverySurface() {
        val s = Sched(); val w = QuietBoundaryWatch(s::schedule)
        w.arm(NudgeHud(night, Verbosity.MEDIUM, zone), at(21, 30)); assertTrue(w.isArmed())
        assertEquals("the delay runs to 22:00 plus a margin", 30 * 60_000L + 50L, s.scheduled.single().first)
        val before = SuggestionGateEvents.closes.value
        s.scheduled.single().second() // 22:00 crossed
        assertEquals(1, rec.all); assertTrue(SuggestionGateEvents.closes.value > before); assertFalse("one-shot: nothing re-arms itself", w.isArmed())
    }

    @Test fun noQuietHoursMeansNothingIsScheduledAndAnArmReplacesTheLastOne() {
        val s = Sched(); val w = QuietBoundaryWatch(s::schedule)
        w.arm(NudgeHud(null, Verbosity.MEDIUM, zone), at(12)); assertFalse(w.isArmed()); assertTrue(s.scheduled.isEmpty())
        w.arm(null, at(12)); assertFalse(w.isArmed())
        w.arm(NudgeHud(night, Verbosity.MEDIUM, zone), at(12)); w.arm(NudgeHud(night, Verbosity.MEDIUM, zone), at(13))
        assertEquals("the first arm was cancelled", 1, s.cancelled); assertEquals(2, s.scheduled.size)
        w.disarm(); assertEquals(2, s.cancelled); assertFalse(w.isArmed())
    }

    @Test fun aWindowThatIsOverMidnightStartsTodayWhenItIsStillAhead() {
        assertEquals(at(22), QuietBoundaryWatch.nextQuietStart(night, at(12), zone))
        assertEquals("after 22:00 the next start is tomorrow", at(22) + 24 * 3_600_000L, QuietBoundaryWatch.nextQuietStart(night, at(23), zone))
        assertNull(QuietBoundaryWatch.nextQuietStart(QuietHours(60, 60), at(12), zone)); assertNotNull(QuietBoundaryWatch.nextQuietStart(night, at(3), zone))
    }
}
