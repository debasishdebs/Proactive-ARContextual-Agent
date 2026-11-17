package com.rokid.context.provenance

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.model.PayloadSources
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.provenance.ProvenanceGate.Commit
import com.rokid.context.provenance.ProvenanceGate.Reason
import com.rokid.context.provenance.ProvenanceGate.Verdict
import com.rokid.context.provenance.ProvenanceGate.Zone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The photo-derived text choke point (spec 2026-11-16 A2): one rule set over real Room SQL, fail closed. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProvenanceGateTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private var gen = 1L
    private var zone = false
    private val gate = ProvenanceGate(db, zoneBlocked = { zone }, currentGeneration = { gen })
    @After fun close() = db.close()

    private fun mem(id: String, review: ReviewState = ReviewState.OK) = MemoryEntity(id, null, "t", "note", "", MemoryTier.SHORT, 0.8, review, false, 1, false, "t")
    private suspend fun frame(id: String) {
        db.frameDao().insertIgnore(FrameEntity(id, 1, "/$id.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1))
    }
    private fun frames(vararg ids: String) = PayloadSources.frames(ids.toList())
    private fun denied(v: Verdict) = (v as Verdict.Denied)

    @Test fun aLivePhotoAndMemoryPass() = runBlocking<Unit> {
        frame("f1"); db.memoryDao().insert(mem("m1"))
        assertEquals(Verdict.Live, gate.check(PayloadSources.stored(listOf("f1"), listOf("m1", "frame:f1")), 1, requirePresent = true))
    }

    @Test fun aTombstonedPhotoIsDeniedForSendAndWrite() = runBlocking<Unit> {
        frame("f1"); db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1))
        assertEquals(Reason.PHOTO_GONE, denied(gate.check(frames("f1"), 1)).reason)
        assertEquals(setOf("f1"), denied(gate.check(frames("f1"), 1, requirePresent = true)).ids)
    }

    @Test fun aForgottenMemoryAGoneCiteAndADeletedMarkAreDenied() = runBlocking<Unit> {
        db.memoryDao().insert(mem("inv", ReviewState.INVALIDATED))
        assertEquals(Reason.MEMORY_GONE, denied(gate.check(PayloadSources.memories(listOf("inv")), 1)).reason)
        assertEquals(Reason.MEMORY_GONE, denied(gate.check(PayloadSources.memories(listOf("frame:gone")), 1)).reason)
        assertEquals(Reason.MEMORY_GONE, denied(gate.check(PayloadSources.memories(listOf("never-existed")), 1)).reason)
        assertEquals(Reason.MARK_GONE, denied(gate.check(PayloadSources.stored(emptyList(), emptyList(), listOf("mark-x")), 1)).reason)
    }

    @Test fun deleteAllDeniesEvenWithNoIds() = runBlocking<Unit> {
        gen = 2
        assertEquals(Reason.WIPED, denied(gate.check(PayloadSources.stored(emptyList(), emptyList()), 1)).reason)
    }

    @Test fun anExpiredPhotoStaysLiveForItsDerivedText() = runBlocking<Unit> {
        frame("fx"); db.frameDao().deleteByIds(listOf("fx")) // the JPEG row expired, the metadata stayed
        db.memoryDao().insert(mem("m1"))
        assertEquals(Verdict.Live, gate.check(PayloadSources.stored(listOf("fx"), listOf("m1", "frame:fx")), 1))
        assertEquals("but its bytes are not sendable", Reason.PHOTO_GONE, denied(gate.check(frames("fx"), 1, requirePresent = true)).reason)
    }

    @Test fun theZoneRefusesFreshPhotoTextOnlyWhenEnforced() = runBlocking<Unit> {
        frame("f1"); zone = true
        assertEquals(Reason.ZONE_BLOCKED, denied(gate.check(frames("f1"), 1, Zone.ENFORCE)).reason)
        assertEquals(Verdict.Live, gate.check(frames("f1"), 1, Zone.IGNORE))
    }

    @Test fun aRevokedLeaseIsDenied() = runBlocking<Unit> {
        frame("f1")
        val leases = Leases(); val lease = leases.open(Leases.Kind.ASK, Leases.photos(listOf("f1")), 1) { gen }
        assertEquals(Verdict.Live, gate.check(frames("f1"), 1, lease = lease))
        leases.revoke(Leases.photos(listOf("f1")))
        assertEquals(Reason.LEASE_REVOKED, denied(gate.check(frames("f1"), 1, lease = lease)).reason)
    }

    @Test fun aDatabaseErrorFailsClosed() = runBlocking<Unit> {
        val broken = ProvenanceGate(db, currentGeneration = { error("generation unreadable") })
        assertEquals(Reason.UNCHECKABLE, denied(broken.check(frames("f1"), 1)).reason)
        val noRead = ProvenanceGate(db, zoneBlocked = { error("zone unreadable") }, currentGeneration = { 0 })
        assertEquals(Reason.UNCHECKABLE, denied(noRead.check(frames("f1"), 0, Zone.ENFORCE)).reason)
    }

    @Test fun commitRunsTheBlockOnlyWhenLiveAndWritesNothingOtherwise() = runBlocking<Unit> {
        frame("f1")
        var ran = 0
        assertTrue(gate.commit(frames("f1"), 1) { ran++; db.memoryDao().insert(mem("new")) } is Commit.Committed)
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f1", 1))
        val r = gate.commit(frames("f1"), 1) { ran++; db.memoryDao().insert(mem("leak")) }
        assertEquals(Reason.PHOTO_GONE, (r as Commit.Refused).denied.reason)
        assertEquals(1, ran)
        assertEquals(listOf("new"), db.forgetDao().memories(listOf("new", "leak")).map { it.id })
    }

    @Test fun theRoutersLivenessIsTheSameRuleSet() = runBlocking<Unit> {
        frame("f1"); frame("f2"); db.forgetDao().insertTombstone(ForgetTombstoneEntity("f2", 1)); db.memoryDao().insert(mem("ok")); db.memoryDao().insert(mem("inv", ReviewState.INVALIDATED))
        val l = gate.photoLiveness(1, gate = { DataOpGate() })
        assertEquals(setOf("f2", "f3"), l.gone(setOf("f1", "f2", "f3")).ids)
        assertEquals(setOf("inv"), l.goneMemories(setOf("ok", "inv")))
        gen = 2
        assertTrue(l.gone(setOf("f1")).wiped)
        assertEquals(setOf("ok"), l.goneMemories(setOf("ok")))
    }

    @Test fun theZoneAndAClosedAttemptDropTheUploadAskedBeforeAndAfterTheReads() = runBlocking<Unit> {
        frame("f1"); zone = true
        assertEquals(setOf("f1"), gate.photoLiveness(1, Zone.ENFORCE, gate = { DataOpGate() }).gone(setOf("f1")).ids)
        zone = false
        assertTrue(gate.photoLiveness(1, Zone.ENFORCE, gate = { DataOpGate() }).gone(setOf("f1")).ids.isEmpty())
    }

    @Test fun liveFilterKeepsOnlyLiveItems() = runBlocking<Unit> {
        db.memoryDao().insert(mem("a")); db.memoryDao().insert(mem("b", ReviewState.INVALIDATED))
        assertEquals(listOf("a"), gate.liveFilter(listOf("a", "b")) { listOf(it) })
        assertEquals(listOf("a"), gate.liveFilter(listOf("a")) { listOf(it) })
    }
}
