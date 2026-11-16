package com.rokid.context.memory.forget.lifecycle

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.GoldenWorld
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Forget lifecycle M3 (T4): the lease registry and its commit. A revoked lease never writes, a lease survives a Forget that does not reach its inputs, a Forget reaches inputs the flow never named through the plan's
 * dependents, a late input meets an earlier Forget, and Room's `withTransaction` serialises a writer before another transaction's first read (the design's "transaction mode assumption", section 6).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LeasesTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val leases = Leases()
    @After fun close() = db.close()

    private fun mem(id: String) = MemoryEntity(id, null, "text $id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "text $id")
    private fun m(id: String) = Ref(Family.MEMORY, id)
    private fun p(id: String) = Ref(Family.PHOTO, id)
    private suspend fun rowIds() = db.memoryDao().all().map { it.id }

    @Test fun aRevokeReachesOnlyTheLeasesWhoseInputsItMeets() {
        val a = leases.open(Leases.Kind.ASK, listOf(m("x")), 0); val b = leases.open(Leases.Kind.NUDGE, listOf(m("y"), p("f")), 0); val c = leases.open(Leases.Kind.DERIVE, emptyList(), 0)
        assertEquals(1, leases.revoke(listOf(m("x"), m("z"))))
        assertTrue(a.isRevoked); assertFalse(b.isRevoked); assertFalse(c.isRevoked)
        assertEquals(1, leases.revoke(listOf(p("f")))); assertTrue(b.isRevoked); assertFalse(c.isRevoked)
        assertEquals("revokeAll reaches every open lease", 3, leases.revokeAll()); assertTrue(c.isRevoked)
    }

    @Test fun aRevokedLeaseCommitsNothingAndALiveOneCommits() = runBlocking {
        val live = leases.open(Leases.Kind.ASK, emptyList(), 0); val dead = leases.open(Leases.Kind.ASK, listOf(m("x")), 0)
        leases.revoke(listOf(m("x")))
        var ran = false
        assertEquals(Leases.Commit.Fenced, dead.commit(db) { ran = true; db.memoryDao().insert(mem("derived-dead")) })
        assertFalse("the block of a revoked lease does not even run", ran)
        assertTrue(live.commit(db) { db.memoryDao().insert(mem("derived-live")) } is Leases.Commit.Committed)
        assertEquals(listOf("derived-live"), rowIds())
    }

    @Test fun aWipeMovesTheGenerationAndFencesTheCommit() = runBlocking {
        var gen = 5L
        val l = leases.open(Leases.Kind.CANVAS_RUN, emptyList(), 5) { gen }
        gen = 6
        assertEquals(Leases.Commit.Fenced, l.commit(db) { db.memoryDao().insert(mem("late")) })
        assertEquals(emptyList<String>(), rowIds())
    }

    @Test fun theLiveChecksFenceATombstonedPhotoAnInvalidatedMemoryAndAMissingMark() = runBlocking {
        db.memoryDao().insert(mem("m1"))
        val photo = leases.open(Leases.Kind.CANVAS_RUN, listOf(p("f1")), 0); val memory = leases.open(Leases.Kind.CANVAS_RUN, listOf(m("m1")), 0); val mark = leases.open(Leases.Kind.CANVAS_RUN, listOf(Ref(Family.MARK, "mk")), 0)
        assertTrue("all live: commits", leases.open(Leases.Kind.CANVAS_RUN, listOf(p("f1"), m("m1")), 0).commit(db) { 1 } is Leases.Commit.Committed)
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1)); db.forgetDao().invalidate(listOf("m1"))
        assertEquals(Leases.Commit.Fenced, photo.commit(db) { 1 }); assertEquals(Leases.Commit.Fenced, memory.commit(db) { 1 }); assertEquals(Leases.Commit.Fenced, mark.commit(db) { 1 })
        assertTrue("checkLive = false leaves the flow's own fences to it", leases.open(Leases.Kind.ASK, listOf(m("m1")), 0).commit(db, checkLive = false) { 1 } is Leases.Commit.Committed)
    }

    @Test fun aLateInputMeetsAForgetThatRanAfterTheLeaseOpened() {
        val l = leases.open(Leases.Kind.ASK, emptyList(), 0)
        leases.revoke(listOf(m("gone")))
        l.extend(listOf(m("other"))); assertFalse("an unrelated input stays", l.isRevoked)
        l.extend(listOf(m("gone"))); assertTrue("an input the earlier Forget reached revokes it", l.isRevoked)
        val after = leases.open(Leases.Kind.ASK, emptyList(), 0); after.extend(listOf(m("gone"))); assertFalse("a lease opened after the Forget is not touched by it", after.isRevoked)
        val wiped = leases.open(Leases.Kind.ASK, emptyList(), 0); leases.revokeAll(); val late = leases.open(Leases.Kind.ASK, emptyList(), 0)
        wiped.extend(listOf(m("q"))); assertTrue(wiped.isRevoked); late.extend(listOf(m("q"))); assertFalse(late.isRevoked)
    }

    @Test fun aClosedLeaseIsForgottenAndNeverRevoked() {
        val l = leases.open(Leases.Kind.NUDGE, listOf(m("x")), 0); assertEquals(1, leases.openCount)
        l.close(); l.close(); assertEquals(0, leases.openCount); assertEquals(0, leases.revoke(listOf(m("x")))); assertFalse(l.isRevoked)
    }

    /** Leak 5: a flow reads, a Forget commits during its (suspended) model call, the flow then commits derived content. The plan reaches a DERIVED input the flow named only as a source. */
    @Test fun aForgetDuringASuspendedModelCallRevokesTheLeaseAndNoDerivedRowIsWritten() = runBlocking {
        val w = GoldenWorld(db); w.seed()
        val lease = leases.open(Leases.Kind.ASK, listOf(m("mD"), p("fFar")), 0) // the flow read only mD (derived from mA) and the far photo
        val model = CompletableDeferred<Unit>()
        val flow = launch(Dispatchers.IO) { model.await() }
        // the Forget: plan, revoke, then the real delete (what ForgetActions does under the gate)
        leases.revoke(ForgetPlanner(db).plan(setOf(m("mA")), Policy.User).nodes.keys)
        w.service.forgetFact("mA")
        model.complete(Unit); flow.join()
        assertTrue("the plan reached mD although the flow never named mA", lease.isRevoked)
        assertEquals(Leases.Commit.Fenced, lease.commit(db) { db.memoryDao().insert(mem("answer-from-forgotten")) })
        assertFalse("no derived row from the forgotten data", rowIds().contains("answer-from-forgotten"))
        val unrelated = leases.open(Leases.Kind.ASK, listOf(m("mFar")), 0)
        assertTrue("a lease on data the Forget did not reach still commits", unrelated.commit(db) { 1 } is Leases.Commit.Committed)
    }

    /** A commit that ran BEFORE the revocation is in the database: the Forget's own plan reads it (through its recorded dependency) and the delete removes it. */
    @Test fun aCommitBeforeTheRevocationIsSeenByTheForgetsPlan() = runBlocking {
        val w = GoldenWorld(db); w.seed()
        val lease = leases.open(Leases.Kind.ASK, listOf(m("mA")), 0)
        assertTrue(lease.commit(db, checkLive = false) { db.askDao().upsert(com.rokid.context.memory.db.AskEntity("askDuring", 2, "q", "answer", "[\"mA\"]", null, "test", false)) } is Leases.Commit.Committed)
        val plan = ForgetPlanner(db).plan(setOf(m("mA")), Policy.User)
        assertTrue("the answer written just before is in the plan", Ref(Family.ANSWER, "askDuring") in plan.nodes)
    }

    /** Design section 6: the re-plan inside the executor's `withTransaction` must read AFTER the write lock is held, so it sees every earlier committed lease write. */
    @Test fun roomWithTransactionTakesTheWriteLockBeforeTheFirstRead() = runBlocking {
        val inA = CompletableDeferred<Unit>(); val releaseA = CompletableDeferred<Unit>()
        val a = launch(Dispatchers.IO) { db.withTransaction { inA.complete(Unit); releaseA.await(); db.memoryDao().insert(mem("written-by-a")) } } // holds the transaction before it has written anything
        inA.await()
        var bSaw: List<String>? = null
        val b = launch(Dispatchers.IO) { db.withTransaction { bSaw = db.memoryDao().all().map { it.id } } }
        delay(400)
        assertNull("the second transaction has not read while the first holds the lock", bSaw)
        releaseA.complete(Unit); a.join(); b.join()
        assertEquals("it read after the first committed, so it saw its row", listOf("written-by-a"), bSaw)
    }
}
