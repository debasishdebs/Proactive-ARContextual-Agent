package com.rokid.context.memory.moment

import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.SceneEntity
import com.rokid.context.memory.db.ScenePartEntity
import com.rokid.context.memory.db.ScenePartKind
import com.rokid.context.memory.db.SceneRule
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.memory.forget.MomentForgetFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * `moment_sensor` (Room v12): the per-second sensor features of a Moment. The rows are deleted with their Moment, with the trace they came from, by every Forget that selects by time and by
 * Delete all; any change of the rows hides the derived fields of the Moment and of its Scenes in the same transaction; the audit stays content-free.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentSensorStoreTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private var now = 10_000L
    private val audit = ObjectAudit(db.objectDao()) { now++ }
    private val md = db.momentDao()
    private val s3 = db.canvasSlice3Dao()
    private val base = 1_761_000_000L
    private fun id(i: Int) = MomentId.format(base + i)
    private val epoch = DeletionEpoch("install-a", 3, 1_000)
    private fun fence() = DaoFence(5, epoch, { 5 }, { epoch })
    @After fun close() = db.close()

    private fun row(i: Int, trace: String = "t1", rev: Int = 1, gyroMax: Double = 7.31, unc: Long = 40L) =
        MomentSensorEntity(id(i), base + i, trace, rev, TraceClock.OFFSET_ESTIMATED, unc, gyroMax, 0.5, 0.6, 0.25, 0.125, 1.5, 0.75, "WALKING", "REST", 50, 40)
    private fun moment(i: Int) = MomentEntity(id(i), 1, 1, "h", 1)
    private fun trace(tid: String, startMs: Long, endMs: Long, unc: Long = 40L, manifest: String = "{}") =
        CanvasTraceEntity(tid, "IMU", "COMPLETE", manifest, 5, false, 1, null, epoch.installId, epoch.counter, true, startMs, endMs, 1, startMs, endMs, TraceClock.OFFSET_ESTIMATED, unc)
    private fun scene(sid: String, from: Int, to: Int) = SceneEntity(sid, 1, 0L, (base + from) * 1000, (base + to) * 1000 + 999, SceneRule.FRAME_CHANGE, null, null, null, 1, 1L)

    /** Moments 0..2 with sensor rows from t1 (0,1) and t2 (2), a Scene holding all three, and fresh derived fields on every object. */
    private suspend fun seed() {
        (0..2).forEach { md.replace(moment(it), emptyList()) }
        db.sceneDao().replace(scene("S${id(0)}", 0, 2), (0..2).map { ScenePartEntity("S${id(0)}", ScenePartKind.MOMENT, id(it), "r", (base + it) * 1000) })
        md.replaceSensor(id(0), listOf(row(0)), 1, "build"); md.replaceSensor(id(1), listOf(row(1)), 1, "build"); md.replaceSensor(id(2), listOf(row(2, "t2")), 1, "build")
        (0..2).forEach { i -> DerivedField.ALL.forEach { audit.writeDerived(moment(i), it, "caption", null, 1) } }
        DerivedField.ALL.forEach { audit.writeDerived(scene("S${id(0)}", 0, 2), it, "scene caption", null, 1) }
    }
    private suspend fun fresh(i: Int) = audit.readDerived(moment(i), DerivedField.SUMMARY) != null
    private suspend fun sceneFresh() = audit.readDerived(scene("S${id(0)}", 0, 2), DerivedField.SUMMARY) != null
    private suspend fun sensorMoments() = md.sensorBetween(0, Long.MAX_VALUE / 2000).map { it.momentId }

    @Test fun aSecondRowIsStoredAndReadBackByMomentAndBetweenSeconds() = runBlocking<Unit> {
        md.replace(moment(0), emptyList())
        assertTrue(md.replaceSensor(id(0), listOf(row(0)), 1, "build"))
        assertEquals(row(0), md.sensorOf(listOf(id(0))).single())
        assertEquals(1, md.sensorBetween(base, base).size); assertEquals(0, md.sensorBetween(base + 1, base + 9).size)
    }

    @Test fun writingTheSameRowsAgainChangesNothingAndWritesNoAudit() = runBlocking<Unit> {
        seed()
        val before = db.objectDao().auditCount()
        assertFalse(md.replaceSensor(id(0), listOf(row(0)), 99, "build"))
        assertEquals(before, db.objectDao().auditCount()); assertTrue(fresh(0))
    }

    @Test fun aChangedRowHidesTheMomentsAndItsScenesDerivedFieldsInTheSameTransaction() = runBlocking<Unit> {
        seed()
        val seqBefore = db.objectDao().inputSeq("MOMENT", id(0))
        assertTrue(md.replaceSensor(id(0), listOf(row(0, gyroMax = 9.0)), 50, "build"))
        assertFalse("the Moment's derived fields are hidden", fresh(0)); assertFalse("and its Scene's", sceneFresh())
        assertTrue("an unrelated Moment keeps its fields", fresh(1))
        assertTrue("the input revision moved, so a derive that started earlier is refused", db.objectDao().inputSeq("MOMENT", id(0)) > seqBefore)
    }

    @Test fun addingOrDroppingASecondAlsoHides() = runBlocking<Unit> {
        seed()
        assertTrue(md.replaceSensor(id(1), emptyList(), 50, "build")); assertFalse(fresh(1)); assertTrue(md.sensorOf(listOf(id(1))).isEmpty())
        assertFalse(md.replaceSensor(id(1), emptyList(), 51, "build"))
    }

    @Test fun theHidingRollsBackWithAFailedTransaction() = runBlocking<Unit> {
        seed()
        try { db.withTransaction { md.replaceSensor(id(0), listOf(row(0, gyroMax = 9.0)), 50, "build"); error("boom") } } catch (_: IllegalStateException) { }
        assertEquals(7.31, md.sensorOf(listOf(id(0))).single().gyroMax, 0.0); assertTrue(fresh(0)); assertTrue(sceneFresh())
    }

    @Test fun aSensorRowForAMomentThatDoesNotExistIsNotWritten() = runBlocking<Unit> {
        assertFalse(md.replaceSensor(id(5), listOf(row(5)), 1, "build")); assertEquals(0, md.sensorCount())
    }

    @Test fun removingAMomentDeletesItsSensorRowsAndOnlyThose() = runBlocking<Unit> {
        seed()
        md.remove(listOf(id(1)))
        assertEquals(listOf(id(0), id(2)), sensorMoments())
    }

    @Test fun dropBetweenAndDropHoldingRemoveTheSensorRowsOfTheMomentsTheyDrop() = runBlocking<Unit> {
        seed()
        md.dropBetween((base + 2) * 1000, (base + 2) * 1000 + 999)
        assertEquals(listOf(id(0), id(1)), sensorMoments())
    }

    @Test fun tombstoningATraceDeletesItsRowsInEveryMomentAndHidesTheirFields() = runBlocking<Unit> {
        seed()
        s3.acceptTraceFenced(trace("t1", base * 1000, (base + 1) * 1000), fence())
        s3.tombstoneTrace("t1", 20_000)
        assertEquals("t2's row stays", listOf(id(2)), sensorMoments())
        assertFalse(fresh(0)); assertFalse(fresh(1)); assertTrue("Moment 2 was not built from t1", fresh(2)); assertFalse("the Scene held two of them", sceneFresh())
    }

    @Test fun aRangeForgetSelectsTracesByUncappedUncertaintyAndTakesTheirRowsOutsideTheRangeToo() = runBlocking<Unit> {
        seed()
        // t1 sits a day away on the phone clock but carries 2 days of uncertainty: it overlaps the range, so it is tombstoned, and its row for second 1 goes although second 1 is outside the range
        s3.acceptTraceFenced(trace("t1", (base + 86_400) * 1000, (base + 86_460) * 1000, unc = 2L * 86_400 * 1000), fence())
        s3.acceptTraceFenced(trace("t2", (base + 400_000) * 1000, (base + 400_100) * 1000, unc = 40), fence())
        s3.forgetRangeTx(base * 1000, base * 1000 + 999, 30_000)
        assertEquals(listOf(id(2)), sensorMoments())
    }

    @Test fun aRangeForgetAlsoDeletesRowsOfTheRangeWhoseTraceRowIsNotThere() = runBlocking<Unit> {
        seed()
        s3.forgetRangeTx((base + 1) * 1000, (base + 1) * 1000 + 999, 30_000) // no canvas_trace rows at all: the rows are found by their second
        assertEquals(listOf(id(0), id(2)), sensorMoments())
        assertFalse(fresh(1)); assertFalse(sceneFresh())
    }

    @Test fun aRangeForgetOfSeveralSecondsTakesEverySecondOfTheRangeAndNoOther() = runBlocking<Unit> {
        seed()
        (3..5).forEach { md.replace(moment(it), emptyList()); md.replaceSensor(id(it), listOf(row(it, "t3")), 1, "build") }
        s3.forgetRangeTx((base + 1) * 1000 + 500, (base + 4) * 1000 + 100, 30_000)
        assertEquals(listOf(id(0), id(5)), sensorMoments())
    }

    @Test fun reImportingATraceWithOtherContentDropsItsOldRows() = runBlocking<Unit> {
        seed()
        s3.acceptTraceFenced(trace("t1", base * 1000, (base + 1) * 1000, manifest = "{\"a\":1}"), fence())
        assertEquals(3, md.sensorCount())
        s3.acceptTraceFenced(trace("t1", base * 1000, (base + 1) * 1000, manifest = "{\"a\":2}"), fence())
        assertEquals(listOf(id(2)), sensorMoments()); assertFalse(fresh(0))
    }

    @Test fun reImportingTheSameContentKeepsTheRows() = runBlocking<Unit> {
        seed()
        s3.acceptTraceFenced(trace("t1", base * 1000, (base + 1) * 1000, manifest = "{\"a\":1}"), fence())
        s3.acceptTraceFenced(trace("t1", base * 1000, (base + 1) * 1000, manifest = "{\"a\":1}"), fence())
        assertEquals(3, md.sensorCount()); assertTrue(fresh(0))
    }

    @Test fun deleteAllEmptiesTheTable() = runBlocking<Unit> {
        seed()
        db.wipeDao().wipeMemory(40_000)
        assertEquals(0, md.sensorCount())
    }

    @Test fun momentForgetByIdAndByRangeTakeTheSensorRowsWithTheMoment() = runBlocking<Unit> {
        val fx = MomentForgetFixture(db)
        val sec = MomentForgetFixture.SEC
        val at = listOf(0L, 30L, 60L) // a Forget of one photo also takes everything within 10 s of it, so the neighbours are far apart
        at.forEach { d -> fx.frame("f$d", (sec + d) * 1000 + 5); val m = MomentId.format(sec + d); md.replace(MomentEntity(m, 1, 1, "h", 1), emptyList()); md.replaceSensor(m, listOf(row(0).copy(momentId = m, phoneSecond = sec + d)), 1, "build") }
        fx.forget.forgetMoments(listOf(MomentId.format(sec)))
        assertEquals(listOf(MomentId.format(sec + 30), MomentId.format(sec + 60)), sensorMoments())
        fx.forget.forgetRange((sec + 30) * 1000, (sec + 31) * 1000)
        assertEquals(listOf(MomentId.format(sec + 60)), sensorMoments())
    }

    @Test fun theAuditNamesNoSensorValueAndNoTrace() = runBlocking<Unit> {
        seed()
        md.replaceSensor(id(0), listOf(row(0, gyroMax = 9.123)), 50, "build")
        s3.acceptTraceFenced(trace("t2", base * 1000, (base + 2) * 1000), fence()); s3.tombstoneTrace("t2", 60_000)
        val rows = db.objectDao().auditByOp("INVALIDATED") + db.objectDao().auditByOp("DERIVED_STALE")
        assertTrue(rows.isNotEmpty())
        rows.forEach { r ->
            assertTrue(r.refKind in setOf(null, "SENSOR", "MOMENT", "FIELD")); assertTrue("${r.refKind} ${r.refId}", r.refKind != "SENSOR" || r.refId == null)
            listOf("9.123", "7.31", "WALKING", "REST", "t1", "t2").forEach { v -> assertFalse("$v in $r", r.toString().contains(v)) }
        }
        assertNotNull(rows.firstOrNull { it.refKind == "SENSOR" }); assertNull(rows.firstOrNull { it.refId?.startsWith("t") == true })
    }
}
