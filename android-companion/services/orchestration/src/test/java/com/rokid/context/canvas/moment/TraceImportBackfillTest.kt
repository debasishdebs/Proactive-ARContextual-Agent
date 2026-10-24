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
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections

/**
 * Sensor backfill: importing a trace asks the coalesced [MomentBuildTrigger] to rebuild the clock hours the trace covers on the PHONE clock, so a Moment built BEFORE the trace
 * arrived gets its `moment_sensor` row without waiting for a frame or the 15-minute cadence (which only reaches the previous and the current hour). Fixture as `MomentSensorBuilderTest`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TraceImportBackfillTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val s3 = db.canvasSlice3Dao()
    private val md = db.momentDao()
    private val fx = MomentFixture(db)
    private val files = FencedFiles(db, ctx.filesDir)
    private val store = DeletionEpochStore.of(ctx)
    private val pulled = File(ctx.cacheDir, "pulled-backfill")
    private fun fence() = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)
    private val sensors = MomentSensors(s3, File(ctx.filesDir, FencedFiles.TRACES_DIR), epoch = store::read)
    private val builder = MomentBuilder(db, enabled = { true }, clock = { 5 }, sensors = sensors)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val testThread = Thread.currentThread()
    private val windows = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
    private val threads = Collections.synchronizedList(ArrayList<Thread>())
    private var enabled = true
    private val trigger = MomentBuildTrigger(
        build = builder::build,
        rebuild = { a, b -> windows += a to b; threads += Thread.currentThread(); builder.rebuildWindow(a, b) },
        enabled = { enabled }, scope = scope, debounceMs = 20,
    )
    private val hooked = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
    private fun importer(hook: (Long, Long) -> Unit = trigger::onTraceImported) = TraceImport(s3, files, ::fence, { s, e -> hooked += s to e; hook(s, e) }) { 5_000L }

    private val SEC = 1_700_000_003L
    private val id = MomentId.format(SEC)
    private val hour = Math.floorDiv(SEC * 1000, 3_600_000L) * 3_600_000L
    private val offset250 = PulledTraces.PhoneClock(phoneOffsetMs = 250, offsetUncertaintyMs = 50)

    @Before fun clean() = wipeAll()
    @After fun close() { scope.cancel(); db.close(); wipeAll() }
    private fun wipeAll() {
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; pulled.deleteRecursively()
    }
    private fun events() = listOf<TraceEvent>(TraceEvent.Ctx(1000, ContextState.REST)) +
        listOf(TraceEvent.Gyro(2800, 3000, 3f, 0f, 0f), TraceEvent.Gyro(2810, 3100, 0f, 4f, 0f), TraceEvent.Gyro(2820, 3200, 0f, 0f, 0f), TraceEvent.Gyro(2830, 3300, 0f, 0f, 1f)) + TraceEvent.Close(12_000, "stop")
    private fun trace(tid: String, rec: TraceStatus = TraceStatus.COMPLETE, clock: PulledTraces.PhoneClock = offset250) =
        PulledTraces.write(pulled, tid, store.read().installId, store.read().counter, rec, clock = clock, evs = events())
    private suspend fun rows() = md.sensorBetween(0, Long.MAX_VALUE / 2000)
    private suspend fun momentBuiltBeforeTheTrace() { fx.frame("f", SEC * 1000 + 100); builder.build(listOf(id)); assertTrue(rows().isEmpty()) }

    @Test fun importingATraceBackfillsTheSensorRowOfAMomentBuiltEarlierWithNoOtherTrigger() = runBlocking<Unit> {
        momentBuiltBeforeTheTrace()
        assertTrue(importer().import(trace("trace-aaaa-0001")) is TraceImportResult.Imported)
        trigger.flush()
        assertEquals(id, rows().single().momentId)
        assertEquals(listOf(hour to hour + 3_600_000L - 1), windows.toList())
        threads.forEach { assertNotEquals("the rebuild runs off the calling thread", testThread, it) }
    }

    @Test fun aPartialTraceBackfillsToo() = runBlocking<Unit> {
        momentBuiltBeforeTheTrace()
        val r = importer().import(trace("trace-aaaa-0002", TraceStatus.PARTIAL))
        assertEquals(TraceStatus.PARTIAL, (r as TraceImportResult.Imported).status)
        trigger.flush()
        assertEquals(1, rows().size)
    }

    @Test fun theHookGetsTheTracesPhoneClockInterval() = runBlocking<Unit> {
        importer { _, _ -> }.import(trace("trace-aaaa-0003"))
        val row = s3.trace("trace-aaaa-0003")!!
        assertEquals(listOf(row.phoneStartMs to row.phoneEndMs!!), hooked.toList())
    }

    @Test fun nothingIsTriggeredByARejectedAnUnchangedOrAnUnmappedTrace() = runBlocking<Unit> {
        val dir = trace("trace-aaaa-0004")
        importer { _, _ -> }.import(dir); hooked.clear()
        assertTrue(importer().import(dir) is TraceImportResult.Unchanged)
        assertTrue(importer().import(File(pulled, "nope")) is TraceImportResult.Rejected)
        assertTrue(importer().import(trace("trace-aaaa-0005", clock = PulledTraces.PhoneClock())) is TraceImportResult.Imported) // no phone clock: no hour is known
        assertTrue(hooked.isEmpty())
    }

    @Test fun aFailingHookNeverFailsTheImport() = runBlocking<Unit> {
        val r = importer { _, _ -> throw IllegalStateException("boom") }.import(trace("trace-aaaa-0006"))
        assertTrue("$r", r is TraceImportResult.Imported)
        assertTrue(s3.trace("trace-aaaa-0006") != null)
    }

    // ---- the trigger: hours, coalescing, switch, cap ----

    @Test fun aTraceOverSeveralHoursRebuildsEachHourOnceAndTwoImportsInOneHourCoalesce() = runBlocking<Unit> {
        val h = 3_600_000L
        trigger.onTraceImported(hour + 5, hour + 2 * h + 5); trigger.onTraceImported(hour + 10, hour + 20); trigger.flush()
        assertEquals(listOf(hour, hour + h, hour + 2 * h).map { it to it + h - 1 }, windows.toList().sortedBy { it.first })
    }

    @Test fun theStoreSwitchStopsItAndAnAbsurdSpanIsCapped() = runBlocking<Unit> {
        enabled = false; trigger.onTraceImported(hour, hour + 1); trigger.flush(); assertTrue(windows.isEmpty())
        enabled = true; trigger.onTraceImported(hour, hour + 1000L * 3_600_000L); trigger.flush()
        assertEquals(MomentBuildTrigger.MAX_TRACE_HOURS, windows.size)
        val last = hour + 1000L * 3_600_000L
        assertEquals("the hours nearest the end of the trace are kept (the newest data)", last, windows.maxOf { it.first })
        assertEquals(last - (MomentBuildTrigger.MAX_TRACE_HOURS - 1) * 3_600_000L, windows.minOf { it.first })
    }
}
