package com.rokid.context.memory.forget.lifecycle

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.MomentTombstoneEntity
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.moment.MomentType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The derived-signal try-it (v1.2 row 2): `Lease.verify` never looked at TRACE or MOMENT inputs, so a lease of kind [Leases.Kind.TRY_IT] is verified with `verifyReadable`, which also checks them
 * (and the trace revision the numbers were read under). The other kinds' `verify` is unchanged, and the new DAO reads are read-only.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TryItLeaseTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val leases = Leases()
    private val md = db.momentDao()
    private val s3 = db.canvasSlice3Dao()
    private val base = 1_761_000_000L
    private val epoch = DeletionEpoch("install-a", 3, 1_000)
    private fun fence() = DaoFence(5, epoch, { 5 }, { epoch })
    private fun id(i: Int) = MomentId.format(base + i)
    @After fun close() = db.close()

    private fun trace(tid: String, rev: Int = 1) = CanvasTraceEntity(tid, "IMU", "COMPLETE", "{}", 5, false, rev, null, epoch.installId, epoch.counter, true, base * 1000, (base + 5) * 1000, 1, base * 1000, (base + 5) * 1000, TraceClock.OFFSET_ESTIMATED, 40)
    private fun row(i: Int, t: String = "t1", rev: Int = 1) = MomentSensorEntity(id(i), base + i, t, rev, TraceClock.OFFSET_ESTIMATED, 40, 1.0, 0.5, 0.6, 0.25, 0.125, 1.5, 0.75, "WALKING", "REST", 50, 40)
    private suspend fun seed() { s3.acceptTraceFenced(trace("t1"), fence()); md.replace(MomentEntity(id(0), 1, 1, "h", 1, MomentType.FRAME), emptyList()); md.replaceSensor(id(0), listOf(row(0)), 1, "build") }
    private fun lease(vararg r: Ref) = leases.open(Leases.Kind.TRY_IT, r.toList(), 0)
    private val t1 = Ref(Family.TRACE, "t1")
    private fun mo(i: Int) = Ref(Family.MOMENT, id(i))

    @Test fun aLiveTraceAndMomentVerify() = runBlocking {
        seed()
        assertTrue(lease(t1, mo(0)).verifyReadable(db, mapOf("t1" to 1)))
    }

    @Test fun aTombstonedTraceFailsAndPlainVerifyDoesNotSeeIt() = runBlocking {
        seed()
        val l = lease(t1, mo(0))
        s3.forgetRangeTx(base * 1000, (base + 5) * 1000, 99)
        assertTrue("verify() has never looked at traces", l.verify(db, true))
        assertFalse(l.verifyReadable(db, mapOf("t1" to 1)))
        assertTrue(l.isRevoked)
    }

    @Test fun aReimportedTraceWithAnotherRevisionFails() = runBlocking {
        seed()
        assertFalse(lease(t1).verifyReadable(db, mapOf("t1" to 2)))
        s3.acceptTraceFenced(trace("t1", rev = 2), fence())
        assertFalse("the numbers were read under revision 1", lease(t1).verifyReadable(db, mapOf("t1" to 1)))
        assertTrue(lease(t1).verifyReadable(db, mapOf("t1" to 2)))
    }

    @Test fun aMissingOrTombstonedMomentFails() = runBlocking {
        seed()
        assertFalse(lease(mo(7)).verifyReadable(db))
        val l = lease(mo(0))
        md.insertTombstones(listOf(MomentTombstoneEntity(id(0), 5)))
        assertFalse(l.verifyReadable(db))
        seed2()
    }
    private suspend fun seed2() { md.remove(listOf(id(0))); assertFalse(lease(mo(0)).verifyReadable(db)) }

    @Test fun aWipeAndARevocationFailBoth() = runBlocking {
        seed()
        var gen = 5L
        val l = leases.open(Leases.Kind.TRY_IT, listOf(t1), 5) { gen }
        gen = 6
        assertFalse(l.verifyReadable(db))
        val r = lease(t1); leases.revoke(listOf(t1)); assertFalse(r.verifyReadable(db))
    }

    @Test fun theNewReadsAreReadOnlyAndAnswerTheLatestRecording() = runBlocking {
        seed()
        md.replace(MomentEntity(id(3), 1, 1, "h", 1, MomentType.FRAME), emptyList()); md.replaceSensor(id(3), listOf(row(3)), 1, "build")
        val before = md.sensorCount()
        assertEquals(base + 3, md.latestSensorSecond(0)); assertEquals(null, md.latestSensorSecond(base + 4))
        assertEquals(base, md.firstSensorSecondOf("t1", 1)); assertEquals(base + 3, md.lastSensorSecondOf("t1", 1)); assertEquals(2, md.sensorSecondCountOf("t1", 1)); assertEquals(0, md.sensorSecondCountOf("t1", 2))
        assertEquals(before, md.sensorCount())
    }
}
