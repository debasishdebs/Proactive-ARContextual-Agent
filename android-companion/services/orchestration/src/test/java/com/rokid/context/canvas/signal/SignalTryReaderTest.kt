package com.rokid.context.canvas.signal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.provenance.ProvenanceGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v1.2 row 2 (T3): the try-it reader. Zone filter contract, latest-recording source, caps, ids only, and the Forget / Delete all races ending in Forgotten. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SignalTryReaderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val md = db.momentDao()
    private val s3 = db.canvasSlice3Dao()
    private val base = 1_761_000_000L
    private val epoch = DeletionEpoch("install-a", 3, 1_000)
    private fun fence() = DaoFence(5, epoch, { 5 }, { epoch })
    private val leases = Leases()
    private val gate = DataOpGate(waitMs = 100, leases = leases)
    private var gen = 5L
    private var zone: (Long) -> ZoneStatus = { ZoneStatus.CLEAR }
    private fun reader() = SignalTryReader(db, gate, ProvenanceGate(db), { zone(it) }, { gen }, { base + 5_000 })
    @After fun close() = db.close()

    private fun id(i: Int) = MomentId.format(base + i)
    private fun trace(tid: String, rev: Int = 1) = CanvasTraceEntity(tid, "IMU", "COMPLETE", "{}", 5, false, rev, null, epoch.installId, epoch.counter, true, base * 1000, (base + 4000) * 1000, 1, base * 1000, (base + 4000) * 1000, TraceClock.OFFSET_ESTIMATED, 40)
    private fun row(i: Int, t: String = "t1", rev: Int = 1) = MomentSensorEntity(id(i), base + i, t, rev, TraceClock.OFFSET_ESTIMATED, 40, 1.0 + i, 0.5, 0.6, 0.25, 0.125, 1.5, 0.75, "WALKING", "REST", 50, 40)
    private suspend fun frame(f: String) {
        db.frameDao().insertIgnore(FrameEntity(f, 1, "/$f.jpg", "rokid_glasses"))
        db.frameMetaDao().upsert(FrameMetaEntity(f, null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1))
    }
    /** Seconds [secs] get a sensor row of [t]; the ones in [photos] also a Moment holding the frame `f<i>`. */
    private suspend fun seed(secs: IntRange, photos: Set<Int> = emptySet(), t: String = "t1", rev: Int = 1) {
        s3.acceptTraceFenced(trace(t, rev), fence())
        for (i in secs) {
            md.replace(MomentEntity(id(i), 1, 1, "h", 1, MomentType.FRAME), if (i in photos) listOf(MomentPartEntity(id(i), MomentPartKind.FRAME, "f$i", "r", (base + i) * 1000)) else emptyList())
            if (i in photos) frame("f$i")
            md.replaceSensor(id(i), listOf(row(i, t, rev)), 1, "build")
        }
    }

    @Test fun clearSecondsGiveRowsMomentIdsAndNoFrameIds() = runBlocking {
        seed(0..99, photos = setOf(10, 20, 30))
        val r = reader().read(60) { it } as SignalTryReader.Outcome.Done
        val s = r.snapshot
        assertEquals(base + 99, s.toS); assertEquals(base + 40, s.fromS); assertEquals(60, s.rows.size)
        assertEquals(listOf(id(10), id(20), id(30)).filter { MomentId.parse(it)!! >= s.fromS }, s.moments.map { it.id })
        assertEquals(100, s.coverageSeconds); assertEquals(base, s.coverageFromS); assertEquals(base + 99, s.coverageToS); assertEquals(base * 1000, s.recordedFromMs)
        assertEquals(0, leases.openCount)
    }

    @Test fun momentsAreCappedAtFiftyNewest() = runBlocking {
        seed(0..119, photos = (0..119).toSet())
        val s = (reader().read(120) { it } as SignalTryReader.Outcome.Done).snapshot
        assertEquals(50, s.moments.size); assertTrue(s.momentsTruncated); assertEquals(id(119), s.moments.last().id); assertEquals(id(70), s.moments.first().id)
    }

    @Test fun aMomentWhoseOnlyPhotoWasForgottenIsDropped() = runBlocking {
        seed(0..59, photos = setOf(5, 6))
        db.forgetDao().insertTombstone(ForgetTombstoneEntity("f5", 1))
        assertEquals(listOf(id(6)), (reader().read(60) { it } as SignalTryReader.Outcome.Done).snapshot.moments.map { it.id })
    }

    @Test fun unknownZoneHistoryIsRefusedAndNothingLeaks() = runBlocking {
        seed(0..59, photos = setOf(5))
        zone = { ZoneStatus.UNKNOWN }
        var computed = false
        val u = reader().read(60) { computed = true; it } as SignalTryReader.Outcome.Unavailable
        assertEquals("no_zone_history", u.reason); assertFalse("the evaluator is never called", computed)
        assertEquals(60, u.snapshot!!.zoneUnknown); assertTrue(u.snapshot!!.rows.isEmpty()); assertTrue("no Moment is listed from a zone-unknown second", u.snapshot!!.moments.isEmpty())
        assertEquals("the production default is the same refusal", "no_zone_history", ((SignalTryReader(db, gate, ProvenanceGate(db), generation = { gen }, nowS = { base + 5_000 }).read(60) { it }) as SignalTryReader.Outcome.Unavailable).reason)
    }

    @Test fun heldAndUnknownSecondsAreExcludedBeforeEvaluation() = runBlocking {
        seed(0..59, photos = setOf(5, 6, 7))
        zone = { s -> when (s - base) { in 0..9L -> ZoneStatus.HELD; in 10..19L -> ZoneStatus.UNKNOWN; 6L -> ZoneStatus.CLEAR; else -> ZoneStatus.CLEAR } }
        val s = (reader().read(60) { it } as SignalTryReader.Outcome.Done).snapshot
        assertEquals(40, s.rows.size); assertEquals(10, s.zoneHeld); assertEquals(10, s.zoneUnknown)
        assertTrue(s.rows.none { it.second - base < 20 }); assertTrue("Moments in excluded seconds are not listed", s.moments.isEmpty())
        zone = { ZoneStatus.HELD }
        assertEquals("zone_held", (reader().read(60) { it } as SignalTryReader.Outcome.Unavailable).reason)
    }

    @Test fun onlyTheNewestRecordingIsUsed() = runBlocking {
        seed(0..29, t = "old"); seed(20..59, t = "new")
        val s = (reader().read(60) { it } as SignalTryReader.Outcome.Done).snapshot
        assertEquals(40, s.rows.size); assertEquals(base + 20, s.rows.minOf { it.second }); assertEquals(40, s.coverageSeconds)
    }

    @Test fun noRowsInSevenDaysIsNoSensorData() = runBlocking {
        assertEquals("no_sensor_data", (reader().read(60) { it } as SignalTryReader.Outcome.Unavailable).reason)
        seed(0..59)
        val late = SignalTryReader(db, gate, ProvenanceGate(db), { ZoneStatus.CLEAR }, { gen }, { base + 8L * 24 * 3600 })
        assertEquals("no_sensor_data", (late.read(60) { it } as SignalTryReader.Outcome.Unavailable).reason)
    }

    @Test fun aForgetOfTheTraceDuringTheEvaluationShowsNothing() = runBlocking {
        seed(0..59, photos = setOf(5))
        val r = reader().read(60) { runBlocking { s3.forgetRangeTx(base * 1000, (base + 4000) * 1000, 9) }; 1 }
        assertEquals(SignalTryReader.Outcome.Forgotten, r); assertEquals(0, leases.openCount)
    }

    @Test fun aMomentForgetOfARowOwnerDuringTheEvaluationShowsNothing() = runBlocking {
        seed(0..59)   // no photos: the Moments are leased only as owners of the evaluated rows
        assertEquals(SignalTryReader.Outcome.Forgotten, reader().read(60) { leases.revoke(listOf(Ref(Family.MOMENT, id(30)))); 1 })
        assertEquals(SignalTryReader.Outcome.Forgotten, reader().read(60) { runBlocking { md.deleteMoments(listOf(id(31))) }; 1 })
        assertEquals(0, leases.openCount)
    }

    @Test fun aRevokedLeaseAndADeleteAllDuringTheEvaluationShowNothing() = runBlocking {
        seed(0..59, photos = setOf(5))
        assertEquals(SignalTryReader.Outcome.Forgotten, reader().read(60) { leases.revoke(listOf(Ref(Family.MOMENT, id(5)))); 1 })
        assertEquals(SignalTryReader.Outcome.Forgotten, reader().read(60) { leases.revoke(listOf(Ref(Family.PHOTO, "f5"))); 1 })
        assertEquals(SignalTryReader.Outcome.Forgotten, reader().read(60) { gen++; 1 })
        assertEquals(SignalTryReader.Outcome.Forgotten, reader().read(60) { leases.revokeAll(); 1 })
        assertEquals(0, leases.openCount)
    }

    @Test fun aReimportDuringTheEvaluationShowsNothing() = runBlocking {
        seed(0..59)
        assertEquals(SignalTryReader.Outcome.Forgotten, reader().read(60) { runBlocking { s3.acceptTraceFenced(trace("t1", 2), fence()) }; 1 })
    }

    @Test fun aRunningDeleteIsBusy() = runBlocking {
        seed(0..59)
        val inside = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val del = async { gate.delete { inside.complete(Unit); release.await() } }
        inside.await()
        assertEquals(SignalTryReader.Outcome.Busy, reader().read(60) { 1 })
        release.complete(Unit); del.await()
    }

    @Test fun readingWritesNothing() = runBlocking {
        seed(0..59, photos = setOf(5))
        val before = listOf(md.sensorCount(), md.allMoments().size, db.objectDao().auditCount(), s3.liveTraces().size)
        reader().read(60) { it }
        assertEquals(before, listOf(md.sensorCount(), md.allMoments().size, db.objectDao().auditCount(), s3.liveTraces().size))
    }
}
