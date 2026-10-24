package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.orchestration.MemoryWipe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * A trace with no phone mapping (UNMAPPED) is admitted by its COARSE evidence (glasses wall +-24 h and the import receipt time) against the live Forget ranges: disjoint = admitted as
 * UNMAPPED; overlapping = QUARANTINED (stored, hidden from every reader, selected by every later Forget, promoted when remapped), never refused.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TraceQuarantineTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val dao = db.canvasSlice3Dao()
    private val files = FencedFiles(db, ctx.filesDir)
    private val store = DeletionEpochStore.of(ctx)
    private val pulled = File(ctx.cacheDir, "pulled-q")
    private fun fence() = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)
    private val importer get() = TraceImport(dao, files, ::fence) { 5_000L }
    private val W0 = 1_700_000_001_000L
    private val day = TraceClock.COARSE_UNCERTAINTY_MS

    @Before fun clean() = wipeAll()
    @After fun close() { db.close(); wipeAll() }
    private fun wipeAll() {
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; pulled.deleteRecursively()
    }
    private fun write(id: String, clock: PulledTraces.PhoneClock = PulledTraces.PhoneClock()) = PulledTraces.write(pulled, id, store.read().installId, store.read().counter, TraceStatus.COMPLETE, clock = clock)
    private suspend fun import(id: String, clock: PulledTraces.PhoneClock = PulledTraces.PhoneClock()) = importer.import(write(id, clock))

    @Test fun anUnmappedImportWithAnUnrelatedRangeIsAdmittedAsUnmappedAndVisible() = runBlocking<Unit> {
        dao.forgetRangeTx(10, 20, 1) // a Forget of 1970: disjoint from the glasses wall +-24 h and from the receipt time
        assertTrue(import("trace-cccc-0001") is TraceImportResult.Imported)
        assertEquals(TraceClock.UNMAPPED, dao.trace("trace-cccc-0001")!!.clockQuality)
        assertEquals(listOf("trace-cccc-0001"), dao.liveTraces().map { it.traceId }); assertEquals("trace-cccc-0001", dao.readableTrace("trace-cccc-0001")?.traceId)
    }

    @Test fun anOverlappingRangeQuarantinesInsteadOfRefusing() = runBlocking<Unit> {
        dao.forgetRangeTx(W0 + day - 1_000, W0 + day + 500, 1) // inside the +24 h coarse reach, nowhere near the glasses wall itself
        assertTrue("stored, not refused", import("trace-cccc-0002") is TraceImportResult.Imported)
        assertEquals(TraceClock.QUARANTINED, dao.trace("trace-cccc-0002")!!.clockQuality)
    }

    @Test fun theReceiptTimeAloneQuarantinesToo() = runBlocking<Unit> {
        dao.forgetRangeTx(4_000, 6_000, 1) // the import receipt time is 5_000
        import("trace-cccc-0003"); assertEquals(TraceClock.QUARANTINED, dao.trace("trace-cccc-0003")!!.clockQuality)
    }

    @Test fun aQuarantinedTraceIsHiddenFromEveryReader() = runBlocking<Unit> {
        dao.forgetRangeTx(W0 + 1_000, W0 + 2_000, 1); import("trace-cccc-0004")
        assertEquals(TraceClock.QUARANTINED, dao.trace("trace-cccc-0004")!!.clockQuality)
        assertTrue("trace list (TraceRoutes)", dao.liveTraces().isEmpty())
        assertNull("one trace (TraceRoutes, PlanEvidenceGates)", dao.readableTrace("trace-cccc-0004"))
        assertNull("replay and sweep (StoredReplayTraces)", StoredReplayTraces(dao, File(ctx.filesDir, FencedFiles.TRACES_DIR)).load("trace-cccc-0004"))
        assertEquals("only Forget and the backfill still see it", listOf("trace-cccc-0004"), dao.forgettableTraces().map { it.traceId })
    }

    @Test fun aLaterOverlappingForgetDeletesAQuarantinedTraceAndSoDoesAnyRange() = runBlocking<Unit> {
        dao.forgetRangeTx(W0 + 1_000, W0 + 2_000, 1); import("trace-cccc-0005")
        val out = dao.forgetRangeTx(W0 + 1_500, W0 + 1_600, 2)
        assertEquals(listOf("trace-cccc-0005"), out.tombstonedTraceIds); assertTrue(dao.trace("trace-cccc-0005")!!.tombstoned)
    }

    @Test fun deleteAllWipesAQuarantinedTrace() = runBlocking<Unit> {
        dao.forgetRangeTx(W0 + 1_000, W0 + 2_000, 1); import("trace-cccc-0006")
        db.wipeDao().wipeMemory(); assertNull(dao.trace("trace-cccc-0006"))
    }

    @Test fun aRemappedImportPromotesTheTraceToANormalOne() = runBlocking<Unit> {
        dao.forgetRangeTx(W0 + 1_000, W0 + 2_000, 1); import("trace-cccc-0007")
        assertEquals(TraceClock.QUARANTINED, dao.trace("trace-cccc-0007")!!.clockQuality)
        // the same trace again, now with a phone offset that places it far from the Forget: mapped and visible
        assertTrue(import("trace-cccc-0007", PulledTraces.PhoneClock(phoneOffsetMs = 3_600_000, offsetUncertaintyMs = 50)) is TraceImportResult.Imported)
        assertEquals(TraceClock.OFFSET_ESTIMATED, dao.trace("trace-cccc-0007")!!.clockQuality); assertEquals(1, dao.liveTraces().size)
    }

    @Test fun aMappedTraceThatOverlapsARangeIsStillRefused() = runBlocking<Unit> {
        dao.forgetRangeTx(W0 + 2_000, W0 + 9_000, 1)
        assertFalse(import("trace-cccc-0008", PulledTraces.PhoneClock(phoneOffsetMs = 0, offsetUncertaintyMs = 50)) is TraceImportResult.Imported)
        assertNull(dao.trace("trace-cccc-0008"))
    }
}
