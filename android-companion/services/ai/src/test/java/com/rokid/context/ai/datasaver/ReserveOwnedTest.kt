package com.rokid.context.ai.datasaver

import com.rokid.context.core.net.DataDecision
import com.rokid.context.core.net.DataGate
import com.rokid.context.core.net.DataKind
import com.rokid.context.core.net.DataReservation
import com.rokid.context.core.net.Skipped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Codex data saver r4 P2-4: a cancellation during the durable reservation must not leave an unsent call charged. */
class ReserveOwnedTest {
    private class Gate(val onReserve: () -> DataReservation?) : DataGate {
        val settled = java.util.concurrent.CopyOnWriteArrayList<List<Long>>()
        val reserves = AtomicInteger()
        override fun decide(kind: DataKind, reach: com.rokid.context.core.net.EndpointReach) = DataDecision(true, "ok")
        override fun skipped(kind: DataKind, what: Skipped, d: DataDecision) {}
        override fun costlyNow() = true
        override fun reserve(kind: DataKind, txBytes: Long, photos: Int): DataReservation? { reserves.incrementAndGet(); return onReserve() }
        override fun settle(r: DataReservation, txBytes: Long, rxBytes: Long, photos: Int) { settled += listOf(txBytes, rxBytes, photos.toLong()) }
    }

    private val token = DataReservation(DataKind.PHOTO, 5_000, 1, 7)

    @Test fun aCancellationDuringTheCommitReleasesTheCommittedToken() = runBlocking {
        val inReserve = CountDownLatch(1); val proceed = CountDownLatch(1)
        val gate = Gate { inReserve.countDown(); proceed.await(5, TimeUnit.SECONDS); token } // the commit succeeds, but only after the caller is cancelled
        val job = async(kotlinx.coroutines.Dispatchers.Default) { reserveOwned(gate, DataKind.PHOTO, 5_000, 1) }
        assertTrue(inReserve.await(5, TimeUnit.SECONDS))
        job.cancel()
        proceed.countDown()
        try { job.await(); fail("cancelled") } catch (_: CancellationException) { }
        assertEquals("the unsent call's count is given back (settled with zeros)", listOf(listOf(0L, 0L, 0L)), gate.settled.toList())
    }

    @Test fun anUncancelledReservationIsReturnedAndNotReleased() = runBlocking {
        val gate = Gate { token }
        assertEquals(token, reserveOwned(gate, DataKind.PHOTO, 5_000, 1))
        assertTrue(gate.settled.isEmpty())
    }

    @Test fun aNullTokenAndAFailedTokenAreNotReleased() = runBlocking {
        val none = Gate { null }
        assertNull(reserveOwned(none, DataKind.SMALL, 10, 0)); assertTrue(none.settled.isEmpty())
        val failed = Gate { DataReservation.FAILED }
        assertTrue(reserveOwned(failed, DataKind.SMALL, 10, 0)!!.failed); assertTrue(failed.settled.isEmpty())
    }

    @Test fun aThrowingGateFailsClosedAndReportsOnlyTheError() = runBlocking {
        val seen = AtomicInteger()
        val gate = Gate { throw IllegalStateException("disk") }
        val r = reserveOwned(gate, DataKind.PHOTO, 10, 1) { seen.incrementAndGet() }
        assertNotNull(r); assertTrue(r!!.failed); assertEquals(1, seen.get()); assertTrue(gate.settled.isEmpty())
    }
}
