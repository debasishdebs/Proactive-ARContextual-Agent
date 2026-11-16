package com.rokid.context.orchestration

import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Ref
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Forget lifecycle M3 (T4): the lease side of the gate. A delete never waits for a lease, a lease waits only for a delete that HOLDS the gate, and a delete revokes the leases it reaches. */
class DataOpGateLeaseTest {
    private fun m(id: String) = Ref(Family.MEMORY, id)

    @Test fun aLeaseOpensAtOnceAndIsRegistered() = runBlocking<Unit> {
        val leases = Leases(); val gate = DataOpGate(leases = leases)
        val l = gate.lease(Leases.Kind.ASK, listOf(m("x")), 0, { 0 })
        assertEquals(1, leases.openCount); l.close(); assertEquals(0, leases.openCount)
    }

    @Test fun aDeleteNeverWaitsForALeaseAndRevokesTheOnesItReaches() = runBlocking<Unit> {
        val leases = Leases(); val gate = DataOpGate(leases = leases)
        val hit = gate.lease(Leases.Kind.ASK, listOf(m("x")), 0, { 0 }); val other = gate.lease(Leases.Kind.NUDGE, listOf(m("y")), 0, { 0 })
        withTimeout(5_000) { gate.delete { gate.leases.revoke(listOf(m("x"))) } } // returns although two leases are open (a lease is a flag, not a lock)
        assertTrue(hit.isRevoked); assertFalse(other.isRevoked)
        hit.close(); other.close()
    }

    @Test fun aLeaseWaitsWhileADeleteHoldsTheGateThenOpens() = runBlocking<Unit> {
        val leases = Leases(); val gate = DataOpGate(leases = leases); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await()
        val opening = async { gate.lease(Leases.Kind.ASK, emptyList(), 0, { 0 }) }
        delay(150)
        assertFalse("no lease while the delete holds the gate", opening.isCompleted); assertEquals(0, leases.openCount)
        release.complete(Unit); del.await()
        withTimeout(5_000) { opening.await() }.close()
    }

    @Test fun aLeaseThatWaitedTooLongFailsTypedAndRegistersNothing() = runBlocking<Unit> {
        val leases = Leases(); val gate = DataOpGate(waitMs = 120, leases = leases); val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await()
        val err = runCatching { gate.lease(Leases.Kind.ASK, emptyList(), 0, { 0 }) }.exceptionOrNull()
        assertTrue(err is DataOpGate.LeaseBlocked); assertEquals(0, leases.openCount)
        release.complete(Unit); del.await()
    }

    /** A canvas Save holds a write slot while a delete WAITS for it: the Save must still be able to open its lease (waiting only for a delete that holds the gate would deadlock them). */
    @Test fun aLeaseOpensWhileADeleteMerelyWaitsForAWrite() = runBlocking<Unit> {
        val leases = Leases(); val gate = DataOpGate(leases = leases); val inWrite = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val write = async { gate.write { inWrite.complete(Unit); release.await() } }
        inWrite.await()
        val del = async { gate.delete { } }
        delay(100) // the delete is parked behind the write
        withTimeout(5_000) { gate.lease(Leases.Kind.CANVAS_SAVE, emptyList(), 0, { 0 }) }.close()
        release.complete(Unit); write.await(); del.await()
    }
}
