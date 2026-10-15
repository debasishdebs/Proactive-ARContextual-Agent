package com.rokid.context.work.budget

import com.rokid.context.model.CallClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CallBudgetTest {
    private fun TestScope.budget(period: () -> Long = { 10_000 }) = CallBudget({ currentTime }, period)

    /** Starts a call that records the virtual time it ran at. */
    private fun TestScope.call(b: CallBudget, c: CallClass, log: MutableList<Pair<String, Long>>, tag: String = c.name) =
        launch { b.withPermit(c) { log += tag to currentTime } }

    @Test fun ratePerPeriod() = runTest {
        val b = budget(); val log = mutableListOf<Pair<String, Long>>()
        repeat(2) { call(b, CallClass.EXTRACT, log) }; runCurrent()
        assertEquals(listOf(0L, 0L), log.map { it.second })
        call(b, CallClass.EXTRACT, log); runCurrent()
        assertEquals(2, log.size)
        advanceTimeBy(9_999); runCurrent(); assertEquals(2, log.size)
        advanceTimeBy(1); runCurrent(); assertEquals(10_000L, log[2].second)
    }

    @Test fun burstOfTwo() = runTest {
        val b = budget(); val log = mutableListOf<Pair<String, Long>>()
        advanceTimeBy(100_000) // idle: banking must stop at the burst
        repeat(4) { call(b, CallClass.EXTRACT, log) }; runCurrent()
        assertEquals(2, log.size)
    }

    @Test fun userBypassCounts() = runTest {
        val b = budget(); val log = mutableListOf<Pair<String, Long>>()
        repeat(4) { call(b, CallClass.USER, log) }; runCurrent()
        assertEquals(4, log.size) // never waits
        assertEquals(4, b.stats.value.callsLastHour[CallClass.USER])
        call(b, CallClass.EXTRACT, log); runCurrent()
        assertEquals(4, log.size) // the bucket is in debt: 2 banked - 4 = -2
        advanceTimeBy(30_000); runCurrent()
        assertEquals(30_000L, log[4].second) // needs three mints to reach +1
    }

    @Test fun oneInFiveReservedForNudge() = runTest {
        val b = budget { 1_000 }; val log = mutableListOf<Pair<String, Long>>()
        // Mints 1..5 happen at 1..5 s; the 5th is reserved. Drain 2 banked + 4 ordinary with EXTRACT.
        repeat(2) { call(b, CallClass.EXTRACT, log) }; runCurrent()
        repeat(4) { call(b, CallClass.EXTRACT, log) }
        advanceTimeBy(4_000); runCurrent()
        assertEquals(6, log.size)
        // The 5th mint is held back from extraction...
        val ext = call(b, CallClass.EXTRACT, log, "late"); advanceTimeBy(1_000); runCurrent()
        assertFalse(log.any { it.first == "late" && it.second == 5_000L })
        // ...but a nudge takes it at once.
        call(b, CallClass.NUDGE, log, "nudge"); runCurrent()
        assertTrue(log.any { it.first == "nudge" && it.second == 5_000L })
        ext.cancel()
    }

    @Test fun reservationReleasedAfter60s() = runTest {
        val b = CallBudget({ currentTime }, { 1_000 }, burst = 1) // the reservation fills the bucket, so nothing else mints
        val log = mutableListOf<Pair<String, Long>>()
        repeat(5) { call(b, CallClass.EXTRACT, log) } // 1 banked + mints at 1..4 s
        advanceTimeBy(4_000); runCurrent()
        assertEquals(5, log.size)
        call(b, CallClass.EXTRACT, log, "late"); advanceTimeBy(30_000); runCurrent()
        assertFalse(log.any { it.first == "late" })
        advanceTimeBy(31_000); runCurrent() // reserved at 5 s, released at 65 s
        assertEquals(65_000L, log.first { it.first == "late" }.second)
    }

    @Test fun nudgeBorrowsNextExtractToken() = runTest {
        val b = budget(); val log = mutableListOf<Pair<String, Long>>()
        repeat(2) { call(b, CallClass.EXTRACT, log) }; runCurrent() // bucket empty, no reservation yet
        call(b, CallClass.EXTRACT, log, "extract"); call(b, CallClass.NUDGE, log, "nudge"); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(10_000L, log.first { it.first == "nudge" }.second) // the nudge got the next token
        assertFalse(log.any { it.first == "extract" })                  // extraction is delayed by one token
        advanceTimeBy(10_000); runCurrent()
        assertEquals(20_000L, log.first { it.first == "extract" }.second)
    }

    @Test fun captionQueueNeverTakesNudgeToken() = runTest {
        val b = budget { 1_000 }; val log = mutableListOf<Pair<String, Long>>()
        repeat(2) { call(b, CallClass.EXTRACT, log) }
        repeat(4) { call(b, CallClass.EXTRACT, log) }
        advanceTimeBy(4_000); runCurrent()
        call(b, CallClass.CAPTION_QUEUE, log, "cap"); call(b, CallClass.BACKFILL, log, "back")
        advanceTimeBy(1_000); runCurrent() // reserved token minted at 5 s
        assertTrue(log.none { it.first == "cap" || it.first == "back" })
        call(b, CallClass.NUDGE, log, "nudge"); runCurrent()
        assertEquals(5_000L, log.first { it.first == "nudge" }.second)
        advanceTimeBy(1_000); runCurrent() // ordinary token: extract waiters (none) first, then the queue in order
        assertEquals(6_000L, log.first { it.first == "cap" }.second)
    }

    @Test fun transportErrorRefunds() = runTest {
        val b = budget(); val log = mutableListOf<Pair<String, Long>>()
        repeat(2) {
            try { b.withPermit(CallClass.EXTRACT) { throw IOException("down") } } catch (_: IOException) {}
        }
        repeat(2) { call(b, CallClass.EXTRACT, log) }; runCurrent()
        assertEquals(2, log.size) // both tokens came back
        // A model/validation error keeps the token spent.
        try { b.withPermit(CallClass.EXTRACT) { throw IllegalStateException("bad json") } } catch (_: IllegalStateException) {}
        call(b, CallClass.EXTRACT, log); runCurrent()
        assertEquals(2, log.size)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, log.size)
    }

    @Test fun periodSettingApplies() = runTest {
        var period = 12_000L
        val b = budget { period }; val log = mutableListOf<Pair<String, Long>>()
        repeat(3) { call(b, CallClass.EXTRACT, log) }; runCurrent()
        assertEquals(2, log.size)
        period = 15_000
        advanceTimeBy(14_999); runCurrent(); assertEquals(2, log.size)
        advanceTimeBy(1); runCurrent(); assertEquals(15_000L, log[2].second)
        period = 10_000
        call(b, CallClass.EXTRACT, log); advanceTimeBy(10_000); runCurrent()
        assertEquals(25_000L, log[3].second)
    }

    @Test fun userDebtFloorsAtMinusBurst() = runTest {
        val b = budget(); val log = mutableListOf<Pair<String, Long>>()
        repeat(10) { call(b, CallClass.USER, log) }; runCurrent()
        assertEquals(10, log.size)
        assertEquals(10, b.stats.value.callsLastHour[CallClass.USER]) // all counted in stats
        call(b, CallClass.EXTRACT, log, "x"); runCurrent()
        advanceTimeBy(30_000); runCurrent() // floor is -2: three mints reach +1, not eight
        assertEquals(30_000L, log.first { it.first == "x" }.second)
    }

    @Test fun callerTimeoutDoesNotRefund() = runTest {
        val b = budget(); val log = mutableListOf<Pair<String, Long>>()
        repeat(2) {
            try { withTimeout(1_000) { b.withPermit(CallClass.EXTRACT) { delay(5_000) } } } catch (_: TimeoutCancellationException) {}
        }
        call(b, CallClass.EXTRACT, log); runCurrent()
        assertEquals(0, log.size) // both tokens stayed spent
    }

    @Test fun refundNeverExceedsBurst() = runTest {
        val b = budget(); val log = mutableListOf<Pair<String, Long>>()
        // Hold a call open, let the bucket refill to burst meanwhile, then refund it: still only 2 banked.
        val held = launch { try { b.withPermit(CallClass.EXTRACT) { delay(100_000); throw IOException("late") } } catch (_: IOException) {} }
        runCurrent(); advanceTimeBy(50_000); runCurrent(); held.cancel()
        advanceTimeBy(60_000); runCurrent()
        repeat(3) { call(b, CallClass.EXTRACT, log) }; runCurrent()
        assertEquals(2, log.size)
    }
}
