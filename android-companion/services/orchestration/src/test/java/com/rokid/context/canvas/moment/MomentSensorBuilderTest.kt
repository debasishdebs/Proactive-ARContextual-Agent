package com.rokid.context.canvas.moment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.trace.FencedFiles
import com.rokid.context.canvas.trace.PulledTraces
import com.rokid.context.canvas.trace.TraceImport
import com.rokid.context.canvas.trace.TraceImportResult
import com.rokid.context.capture.trace.TraceEvent
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.memory.db.MomentSensorEntity
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.memory.moment.DerivedField
import com.rokid.context.memory.moment.ObjectAudit
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.sqrt

/**
 * Sensor features per Moment second: a Moment built from a photo carries the numbers of the imported trace for ITS phone second, through the trace's own clock map; a second the clock
 * cannot place (unmapped, quarantined, too uncertain) gets no row; a trace that arrives later backfills on the next build; everything is deterministic and idempotent.
 * Fixture: glasses elapsed 1000..12000, phone offset +250 ms, so elapsed 2800 lies in the phone second [SEC].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentSensorBuilderTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val s3 = db.canvasSlice3Dao()
    private val md = db.momentDao()
    private val fx = MomentFixture(db)
    private val files = FencedFiles(db, ctx.filesDir)
    private val store = DeletionEpochStore.of(ctx)
    private val pulled = File(ctx.cacheDir, "pulled-sensor")
    private val tracesRoot = File(ctx.filesDir, FencedFiles.TRACES_DIR)
    private fun fence() = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)
    private val importer get() = TraceImport(s3, files, ::fence) { 5_000L }
    private var now = 100L
    private val sensors get() = MomentSensors(s3, tracesRoot, epoch = store::read)
    private val builder get() = MomentBuilder(db, enabled = { true }, clock = { now++ }, sensors = sensors)
    private val audit = ObjectAudit(db.objectDao()) { now++ }

    private val SEC = 1_700_000_003L
    private val id = MomentId.format(SEC)
    private val offset250 = PulledTraces.PhoneClock(phoneOffsetMs = 250, offsetUncertaintyMs = 50)

    @Before fun clean() = wipeAll()
    @After fun close() { db.close(); wipeAll() }
    private fun wipeAll() {
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; pulled.deleteRecursively()
    }

    /** Ctx REST, then four gyro samples (3, 4, 0, 1 rad/s, 100 ms apart in sensor time) delivered at elapsed 2800..2830, then the fixture's closing events. */
    private fun events(extraGyro: Int = 0): List<TraceEvent> = listOf<TraceEvent>(TraceEvent.Ctx(1000, ContextState.REST)) +
        listOf(TraceEvent.Gyro(2800, 3000, 3f, 0f, 0f), TraceEvent.Gyro(2810, 3100, 0f, 4f, 0f), TraceEvent.Gyro(2820, 3200, 0f, 0f, 0f), TraceEvent.Gyro(2830, 3300, 0f, 0f, 1f)) +
        (0 until extraGyro).map { TraceEvent.Gyro(2840L + it, 3400L + it * 10, 0f, 0f, 0f) } + TraceEvent.Close(12_000, "stop")

    private suspend fun import(tid: String = "trace-aaaa-0001", clock: PulledTraces.PhoneClock = offset250, evs: List<TraceEvent> = events(), rec: TraceStatus = TraceStatus.COMPLETE) {
        val r = importer.import(PulledTraces.write(pulled, tid, store.read().installId, store.read().counter, rec, clock = clock, evs = evs))
        assertTrue("$r", r is TraceImportResult.Imported)
    }
    private suspend fun photo() = fx.frame("f", SEC * 1000 + 100)
    private suspend fun rows() = md.sensorBetween(0, Long.MAX_VALUE / 2000)

    @Test fun aMomentGetsTheFeaturesOfItsPhoneSecondWithTheTraceAndTheClockQualityCarriedOver() = runBlocking<Unit> {
        photo(); import()
        builder.build(listOf(id))
        val r = rows().single()
        assertEquals(id, r.momentId); assertEquals(SEC, r.phoneSecond); assertEquals("trace-aaaa-0001", r.traceId); assertEquals(1, r.traceRevision)
        assertEquals(TraceClock.OFFSET_ESTIMATED, r.clockQuality); assertEquals(60L, r.uncertaintyMs) // 50 ms offset uncertainty + 10 ms glasses drift
        assertEquals(4.0, r.gyroMax, 0.0); assertEquals(2.0, r.gyroMean, 1e-6); assertEquals(sqrt(6.5), r.gyroRms, 1e-6); assertEquals(0.75, r.aboveOnFrac, 1e-9); assertEquals(0.5, r.rotRad, 1e-6)
        assertEquals("HEAD_MOVING", r.motionState); assertEquals("REST", r.contextCode); assertEquals(4, r.nGyro); assertEquals(0, r.nAcc)
    }

    @Test fun aSecondWithNoPhotoIsNotAMomentSoItHasNoRow() = runBlocking<Unit> {
        import(); builder.build(listOf(id))
        assertTrue(rows().isEmpty())
    }

    @Test fun aMarkMomentGetsSensorRowsOnlyWhenATraceCoversItsSecond() = runBlocking<Unit> {
        fx.mark("mk:1:1", SEC * 1000 + 100, "tap")
        val far = MomentId.format(SEC + 500); fx.mark("mk:1:2", (SEC + 500) * 1000 + 100, "voice")
        import()
        builder.build(listOf(id, far))
        assertEquals(com.rokid.context.memory.moment.MomentType.MARK, md.moment(id)!!.momentType)
        assertEquals("the covered second has its row", listOf(id), rows().map { it.momentId })
        assertEquals("the uncovered MARK Moment exists with no sensor row", com.rokid.context.memory.moment.MomentType.MARK, md.moment(far)!!.momentType)
    }

    @Test fun aSecondOfATraceWithNoPhoneClockGetsNoRow() = runBlocking<Unit> {
        photo(); import(clock = PulledTraces.PhoneClock())
        assertEquals(TraceClock.UNMAPPED, s3.trace("trace-aaaa-0001")!!.clockQuality)
        builder.build(listOf(id)); assertTrue(rows().isEmpty())
    }

    @Test fun aSecondOfAQuarantinedTraceGetsNoRow() = runBlocking<Unit> {
        s3.forgetRangeTx(1_700_000_000_000L, 1_700_000_020_000L, 1) // the unmapped trace's coarse evidence overlaps a Forget range: stored QUARANTINED
        photo(); import(clock = PulledTraces.PhoneClock())
        assertEquals(TraceClock.QUARANTINED, s3.trace("trace-aaaa-0001")!!.clockQuality)
        builder.build(listOf(id)); assertTrue(rows().isEmpty())
    }

    @Test fun aTraceUncertainBeyondTheToleranceGivesNoRowAndOneAtTheToleranceDoes() = runBlocking<Unit> {
        photo(); import("trace-aaaa-0001", PulledTraces.PhoneClock(phoneOffsetMs = 250, offsetUncertaintyMs = 491)) // 491 + 10 drift = 501 ms
        builder.build(listOf(id)); assertTrue(rows().isEmpty())
        import("trace-aaaa-0002", PulledTraces.PhoneClock(phoneOffsetMs = 250, offsetUncertaintyMs = 490)) // exactly 500 ms
        builder.build(listOf(id)); assertEquals("trace-aaaa-0002", rows().single().traceId)
    }

    /** A stored trace row whose bookkeeping says something else than what the import wrote (a legacy row, a hub row, another install): the row, not the files, decides. */
    private suspend fun rowSays(set: String) {
        photo(); import(); db.openHelper.writableDatabase.execSQL("UPDATE canvas_trace SET $set WHERE traceId = 'trace-aaaa-0001'")
        builder.build(listOf(id))
    }

    @Test fun aRowMarkedUnmappedNeverGivesASecondWhateverItsUncertaintyField() = runBlocking<Unit> { rowSays("clockQuality = 'UNMAPPED', uncertaintyMs = 10"); assertTrue(rows().isEmpty()) }
    @Test fun aRowMarkedQuarantinedNeverGivesASecondWhateverItsUncertaintyField() = runBlocking<Unit> { rowSays("clockQuality = 'QUARANTINED', uncertaintyMs = 10"); assertTrue(rows().isEmpty()) }
    @Test fun aHubTraceNeverGivesASecond() = runBlocking<Unit> { rowSays("source = 'HUB'"); assertTrue(rows().isEmpty()) }
    @Test fun aTraceOfAnotherInstallOrAnOlderEpochGivesNoSecond() = runBlocking<Unit> {
        rowSays("deletionCounter = deletionCounter + 1"); assertTrue(rows().isEmpty())
        db.openHelper.writableDatabase.execSQL("UPDATE canvas_trace SET deletionCounter = deletionCounter - 1, installId = 'other'"); builder.build(listOf(id)); assertTrue(rows().isEmpty())
    }

    @Test fun aNewMomentsAuditStaysJustBuiltEvenWhenItHasASensorRow() = runBlocking<Unit> {
        photo(); import(); builder.build(listOf(id))
        assertEquals(1, rows().size)
        assertEquals(listOf("BUILT"), db.objectDao().audit("MOMENT", id).map { it.op })
    }

    @Test fun theSecondOutsideTheTracesPhoneIntervalGetsNoRow() = runBlocking<Unit> {
        fx.frame("far", (SEC + 3_600) * 1000 + 100); import()
        builder.build(listOf(MomentId.format(SEC + 3_600))); assertTrue(rows().isEmpty())
    }

    @Test fun rebuildingChangesNothingAndWritesNoAudit() = runBlocking<Unit> {
        photo(); import(); builder.build(listOf(id))
        val before = rows(); val auditBefore = db.objectDao().auditCount()
        builder.build(listOf(id)); builder.rebuildWindow(SEC * 1000 - 5_000, SEC * 1000 + 5_000)
        assertEquals(before, rows()); assertEquals(auditBefore, db.objectDao().auditCount())
    }

    @Test fun aTraceImportedAfterTheMomentWasBuiltBackfillsOnTheNextBuildAndHidesTheDerivedFields() = runBlocking<Unit> {
        photo(); builder.build(listOf(id)); assertTrue(rows().isEmpty())
        val m = md.moment(id)!!
        DerivedField.ALL.forEach { audit.writeDerived(m, it, "a cat", null, 1) }
        import()
        assertTrue("importing alone changes nothing", rows().isEmpty())
        builder.rebuildWindow(SEC * 1000 - 10_000, SEC * 1000 + 10_000)
        assertEquals(1, rows().size)
        assertNull("the Moment's fields are hidden because its inputs changed", audit.readDerived(m, DerivedField.SUMMARY))
    }

    @Test fun reImportingATraceWithOtherContentReplacesTheRowsWithTheNewRevision() = runBlocking<Unit> {
        photo(); import(); builder.build(listOf(id)); assertEquals(1, rows().single().traceRevision)
        import(rec = TraceStatus.PARTIAL, evs = events(extraGyro = 2)) // other content: the import itself drops the old numbers
        assertTrue(rows().isEmpty())
        builder.build(listOf(id))
        val r = rows().single(); assertEquals(2, r.traceRevision); assertEquals(6, r.nGyro)
    }

    @Test fun twoTracesOverTheSameSecondKeepTheOneWithMoreSamplesThenTheSmallerId() = runBlocking<Unit> {
        photo(); import("trace-aaaa-0002"); import("trace-aaaa-0003", evs = events(extraGyro = 2))
        builder.build(listOf(id)); assertEquals("most samples wins even with the larger id", "trace-aaaa-0003", rows().single().traceId)
        import("trace-aaaa-0001", evs = events(extraGyro = 2)); builder.build(listOf(id))
        assertEquals("a tie goes to the smaller trace id", "trace-aaaa-0001", rows().single().traceId)
    }

    @Test fun oneSensorsObjectReadsATraceOnceAndNeverServesAReplacedOne() = runBlocking<Unit> {
        photo(); import()
        val reads = ArrayList<String>()
        val s = MomentSensors(s3, tracesRoot, epoch = store::read).also { it.testOnFileRead = { n -> reads += n } }
        val b = MomentBuilder(db, enabled = { true }, clock = { now++ }, sensors = s)
        b.build(listOf(id)); val first = reads.size; b.build(listOf(id))
        assertTrue(first > 0); assertEquals("the second build reuses what was computed", first, reads.size)
        import(rec = TraceStatus.PARTIAL, evs = events(extraGyro = 2)); b.build(listOf(id))
        assertEquals("a replaced trace is read again", 6, rows().single().nGyro); assertEquals(2, rows().single().traceRevision)
    }

    @Test fun aTraceTombstonedWhileTheBuildWasReadingItFillsNoRow() = runBlocking<Unit> {
        photo(); import()
        val b = builder
        b.testAfterSensorLoad = { s3.tombstoneTrace("trace-aaaa-0001", 9_000) }
        b.build(listOf(id))
        assertTrue(rows().isEmpty())
    }

    @Test fun aTraceReplacedWhileTheBuildWasReadingItFillsNoRowFromTheOldContent() = runBlocking<Unit> {
        photo(); import()
        val b = builder
        b.testAfterSensorLoad = { import(rec = TraceStatus.PARTIAL, evs = events(extraGyro = 2)) }
        b.build(listOf(id))
        assertTrue("the old content's numbers are refused; the next build uses the new revision", rows().isEmpty())
        builder.build(listOf(id)); assertEquals(2, rows().single().traceRevision)
    }

    @Test fun theTraceFilesAreReadOffTheCallingThread() = runBlocking<Unit> {
        photo(); import()
        val io = newSingleThreadContext("sensor-io-test")
        val seen = ArrayList<String>()
        try {
            val s = MomentSensors(s3, tracesRoot, epoch = store::read, io = io).also { it.testOnFileRead = { seen += Thread.currentThread().name } }
            MomentBuilder(db, enabled = { true }, clock = { now++ }, sensors = s).build(listOf(id))
        } finally { io.close() }
        assertTrue(seen.isNotEmpty()); assertTrue(seen.toString(), seen.all { it.startsWith("sensor-io-test") })
        assertEquals(1, rows().size)
    }

    @Test fun anUnreadableTraceFileGivesNoRowAndDoesNotBreakTheBuild() = runBlocking<Unit> {
        photo(); import()
        File(tracesRoot, "trace-aaaa-0001/chunk-0000.json").writeText("garbage")
        val r = builder.build(listOf(id)); assertEquals(1, r.stored); assertTrue(rows().isEmpty())
    }

    @Test fun theRebuildWindowPathFillsRowsToo() = runBlocking<Unit> {
        photo(); import()
        builder.rebuildWindow(SEC * 1000 - 1_000, SEC * 1000 + 1_000)
        assertEquals(1, rows().size)
    }

    @Test fun withoutASensorSourceTheBuilderTouchesNoSensorRows() = runBlocking<Unit> {
        photo(); import(); builder.build(listOf(id)); assertEquals(1, rows().size)
        MomentBuilder(db, enabled = { true }, clock = { now++ }).build(listOf(id))
        assertEquals("rows stay when the builder has no sensor source", 1, rows().size)
    }

    @Test fun aMomentThatNoLongerQualifiesTakesItsRowWithIt() = runBlocking<Unit> {
        photo(); import(); builder.build(listOf(id)); assertEquals(1, rows().size)
        db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("f", 1))
        builder.build(listOf(id)); assertTrue(rows().isEmpty())
    }
}
