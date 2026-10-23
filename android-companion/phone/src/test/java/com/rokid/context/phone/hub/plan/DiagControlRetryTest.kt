package com.rokid.context.phone.hub.plan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagControlRetryTest {
    private class Fake : GlassesDiagStatusSource {
        val s = MutableStateFlow(DiagFlagState.UNKNOWN)
        override val state get() = s
        var invalidated = 0
        override fun invalidate() { invalidated++; s.value = DiagFlagState.UNKNOWN }
    }

    private fun TestScope.make(st: Fake, wanted: () -> Boolean = { true }, times: MutableList<Long>) =
        DiagControlRetry(backgroundScope, st, wanted, resend = { times += testScheduler.currentTime })

    @Test fun PhoneRetriesControlUntilStatusEnabled() = runTest {
        val st = Fake(); val times = mutableListOf<Long>()
        val r = make(st, times = times)
        r.restart(); runCurrent()
        advanceTimeBy(5_001); assertEquals("first re-send after 5 s", 1, times.size)
        st.s.value = DiagFlagState.ARMED_NO_EPOCH
        advanceTimeBy(10_000); assertEquals(2, times.size)
        st.s.value = DiagFlagState.ENABLED
        advanceTimeBy(600_000); assertEquals("no re-send once enabled", 2, times.size)
    }

    @Test fun NoRetryWhenStatusAlreadyEnabledOrDisabled() = runTest {
        val st = Fake(); val times = mutableListOf<Long>()
        val r = make(st, times = times)
        st.s.value = DiagFlagState.ENABLED; r.restart(); advanceTimeBy(600_000); assertEquals(0, times.size)
        st.s.value = DiagFlagState.DISABLED; r.restart(); advanceTimeBy(600_000); assertEquals(0, times.size)
    }

    @Test fun BackoffIsBoundedFiveToSixtySecondsFakeClock() = runTest {
        val st = Fake(); val times = mutableListOf<Long>()
        make(st, times = times).restart()
        advanceTimeBy(3_000_000)
        val gaps = times.zipWithNext { a, b -> b - a }
        assertEquals(5_000L, times.first())
        assertEquals(listOf(10_000L, 20_000L, 40_000L), gaps.take(3))
        assertTrue("gaps never exceed 60 s", gaps.all { it <= 60_000L })
        assertEquals("steady state is 60 s", 60_000L, gaps.last())
    }

    @Test fun NoRetryStormTotalIsCappedPerSchedule() = runTest {
        val st = Fake(); val times = mutableListOf<Long>()
        make(st, times = times).restart()
        advanceTimeBy(10_000_000)
        assertEquals(DiagControlRetry.MAX_ATTEMPTS, times.size)
        assertTrue("a rapid restart storm does not multiply the schedule", run {
            val t2 = mutableListOf<Long>(); val r = make(st, times = t2)
            repeat(50) { r.restart() }
            advanceTimeBy(10_000_000); t2.size <= DiagControlRetry.MAX_ATTEMPTS
        })
    }

    @Test fun RestartResetsTheScheduleAndInvalidatesOnNewSession() = runTest {
        val st = Fake(); val times = mutableListOf<Long>()
        val r = make(st, times = times)
        r.restart(); advanceTimeBy(5_001 + 10_001); assertEquals(2, times.size)
        st.s.value = DiagFlagState.ENABLED // a stale value from the previous glasses session
        r.restart(newSession = true); runCurrent()
        assertEquals(1, st.invalidated)
        assertEquals(DiagFlagState.UNKNOWN, st.s.value)
        val before = testScheduler.currentTime
        advanceTimeBy(5_001); assertEquals("schedule restarted at 5 s", before + 5_000, times.last())
    }

    @Test fun StopsWhenDeveloperModeGoesOff() = runTest {
        val st = Fake(); val times = mutableListOf<Long>(); var on = true
        val r = make(st, wanted = { on }, times = times)
        r.restart(); advanceTimeBy(5_001); assertEquals(1, times.size)
        on = false; advanceTimeBy(600_000); assertEquals(1, times.size)
        r.restart(); advanceTimeBy(600_000); assertEquals("off: restart schedules nothing", 1, times.size)
    }

    @Test fun UnknownProductionDefaultStaysBoundedAndInertSource() = runTest {
        val times = mutableListOf<Long>()
        DiagControlRetry(backgroundScope, UnknownGlassesDiagStatus, { true }, resend = { times += 1 }).restart()
        advanceTimeBy(10_000_000)
        assertEquals(DiagControlRetry.MAX_ATTEMPTS, times.size)
    }

    @Test fun UpdateMapsWireNames() {
        val m = MutableGlassesDiagStatus()
        m.update("armed_no_epoch"); assertEquals(DiagFlagState.ARMED_NO_EPOCH, m.state.value)
        m.update("enabled"); assertEquals(DiagFlagState.ENABLED, m.state.value)
        m.update("bogus"); assertEquals(DiagFlagState.UNKNOWN, m.state.value)
        m.update("disabled"); assertEquals(DiagFlagState.DISABLED, m.state.value)
        m.update("unprovisioned"); assertEquals(DiagFlagState.UNPROVISIONED, m.state.value)
        m.invalidate(); assertEquals(DiagFlagState.UNKNOWN, m.state.value)
    }
}
