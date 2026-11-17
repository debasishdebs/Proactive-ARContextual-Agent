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
import com.rokid.context.orchestration.MemoryWipe
import com.rokid.context.provenance.ProvenanceGate.Reason
import com.rokid.context.provenance.ProvenanceGate.Verdict
import com.rokid.context.provenance.ProvenanceGate.Zone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Ratchet migration golden table: the sites that moved from their own raw check (wipe-generation compare, `forgetDao().tombstoned`) to [ProvenanceGate]
 * (ExtractSave, QueueDrainWorker, ClusterSave.liveFrames, PipelineRecords.existing, Intent/PlaceSamplers, ResummarizeWorker, OrchestrationScheduler, QueueRuntimeStore,
 * LiveExtract, CanvasBackendImpl) decide exactly as the expression they replaced, on every row of the table, and a failing read still throws (never "live").
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RatchetMigrationGoldenTest {
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

    /** The replaced expression of ExtractSave / QueueDrainWorker / PipelineRecords / the samplers / ClusterSave.liveFrames. */
    private suspend fun legacyTombstoned(ids: Set<String>) = ids.toList().chunked(500).flatMap { db.forgetDao().tombstoned(it) }.toSet()

    @Test fun tombstonedPhotoSameDecision() = runBlocking<Unit> {
        frame("live"); frame("forgotten"); db.forgetDao().insertTombstone(ForgetTombstoneEntity("forgotten", 1))
        for (ids in listOf(setOf("live"), setOf("forgotten"), setOf("live", "forgotten"), emptySet(), setOf("never-seen"))) {
            assertEquals("$ids", legacyTombstoned(ids), gate.goneFrames(ids, requirePresent = false))
            assertEquals("$ids", legacyTombstoned(ids).isNotEmpty(), gate.goneFrames(ids, requirePresent = false).isNotEmpty())
        }
    }

    @Test fun expiredPhotoIsLiveForDerivedTextLikeBefore() = runBlocking<Unit> {
        frame("fx"); db.frameDao().deleteByIds(listOf("fx")) // JPEG row expired, metadata stays; no tombstone
        assertEquals(emptySet<String>(), legacyTombstoned(setOf("fx")))
        assertEquals(emptySet<String>(), gate.goneFrames(setOf("fx"), requirePresent = false))
    }

    @Test fun moreThanOneChunkOfIdsIsStillAllChecked() = runBlocking<Unit> {
        frame("late"); db.forgetDao().insertTombstone(ForgetTombstoneEntity("late", 1))
        val ids = (1..1200).map { "x$it" }.toSet() + "late"
        assertEquals(setOf("late"), gate.goneFrames(ids, requirePresent = false))
        assertEquals(legacyTombstoned(ids), gate.goneFrames(ids, requirePresent = false))
    }

    @Test fun wipedGenerationSameDecision() {
        for ((read, now) in listOf(1L to 1L, 1L to 2L, 5L to 4L)) {
            val legacy = now != read
            assertEquals(legacy, ProvenanceGate(db, currentGeneration = { now }).wiped(read))
        }
        val g = MemoryWipe.generation.get()
        assertFalse(ProvenanceGate.isWiped(g))
        MemoryWipe.generation.incrementAndGet()
        assertTrue(ProvenanceGate.isWiped(g))
        assertEquals(MemoryWipe.generation.get() != g, ProvenanceGate.isWiped(g))
    }

    @Test fun theRemainingRowsOfTheGoldenTableAreDeniedByTheSameGate() = runBlocking<Unit> {
        db.memoryDao().insert(mem("inv", ReviewState.INVALIDATED))
        assertEquals(Reason.MEMORY_GONE, (gate.check(PayloadSources.memories(listOf("inv")), 1) as Verdict.Denied).reason)
        assertEquals(Reason.MARK_GONE, (gate.check(PayloadSources.stored(emptyList(), emptyList(), listOf("m")), 1) as Verdict.Denied).reason)
        frame("f1"); zone = true
        assertEquals(Reason.ZONE_BLOCKED, (gate.check(PayloadSources.frames(listOf("f1")), 1, Zone.ENFORCE) as Verdict.Denied).reason)
        zone = false
        val leases = Leases(); val lease = leases.open(Leases.Kind.ASK, Leases.photos(listOf("f1")), 1) { gen }
        leases.revoke(Leases.photos(listOf("f1")))
        assertEquals(Reason.LEASE_REVOKED, (gate.check(PayloadSources.frames(listOf("f1")), 1, lease = lease) as Verdict.Denied).reason)
        gen = 2
        assertEquals(Reason.WIPED, (gate.check(PayloadSources.NONE, 1) as Verdict.Denied).reason)
    }

    @Test fun aFailingReadStillThrowsLikeTheRawQueryDid() = runBlocking<Unit> {
        frame("f1")
        db.openHelper.writableDatabase.execSQL("DROP TABLE forget_tombstone") // the read now fails
        try { legacyTombstoned(setOf("f1")); fail("the raw query throws when its table is unreadable") } catch (_: Exception) {}
        try { gate.goneFrames(setOf("f1"), requirePresent = false); fail("the migrated read must throw too, never answer 'live'") } catch (_: Exception) {}
    }
}
