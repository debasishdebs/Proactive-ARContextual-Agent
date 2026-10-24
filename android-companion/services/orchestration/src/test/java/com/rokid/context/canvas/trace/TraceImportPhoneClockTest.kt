package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.trace.ClockMap
import com.rokid.context.capture.trace.TraceStatus
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpochStore
import com.rokid.context.memory.db.TraceClock
import com.rokid.context.orchestration.MemoryWipe
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

/**
 * Spec 7.2/7.3 (privacy): an imported trace is stored with its interval on the PHONE clock, and Forget and admission select it by interval overlap with the uncertainty NOT
 * capped. The glasses wall times stay in `startedAtMs`/`endedAtMs` and are never compared with a phone range. Fixture: glasses wall 1_700_000_001_000 .. 1_700_000_012_010.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TraceImportPhoneClockTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val dao = db.canvasSlice3Dao()
    private val files = FencedFiles(db, ctx.filesDir)
    private val store = DeletionEpochStore.of(ctx)
    private val pulled = File(ctx.cacheDir, "pulled-clock")
    private fun realFence() = DaoFence(MemoryWipe.generation.get(), store.read(), MemoryWipe.generation::get, store::read)
    private val importer get() = TraceImport(dao, files, ::realFence) { 5_000L }
    private val W0 = 1_700_000_001_000L
    private val W1 = 1_700_000_012_010L

    @Before fun clean() = wipeAll()
    @After fun close() { db.close(); wipeAll() }
    private fun wipeAll() {
        ctx.getSharedPreferences(DeletionEpochStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FencedFiles.DELETE_ALL_DIRS.forEach { File(ctx.filesDir, it).deleteRecursively() }; pulled.deleteRecursively()
    }
    private fun write(id: String, clock: PulledTraces.PhoneClock, rec: TraceStatus = TraceStatus.COMPLETE) =
        PulledTraces.write(pulled, id, store.read().installId, store.read().counter, rec, clock = clock)
    private suspend fun import(id: String, clock: PulledTraces.PhoneClock): CanvasTraceEntity {
        assertTrue(importer.import(write(id, clock)) is TraceImportResult.Imported)
        return dao.trace(id)!!
    }
    private val offset3s = PulledTraces.PhoneClock(phoneOffsetMs = 3_000, offsetUncertaintyMs = 50)

    @Test fun anImportedTraceStoresItsPhoneClockInterval() = runBlocking {
        val row = import("trace-aaaa-0001", offset3s)
        assertEquals("glasses wall stays in startedAtMs", W0, row.startedAtMs); assertEquals(W1, row.endedAtMs)
        assertEquals(W0 + 3_000, row.phoneStartMs); assertEquals(W1 + 3_000, row.phoneEndMs)
        assertEquals(TraceClock.OFFSET_ESTIMATED, row.clockQuality)
        assertEquals("offset uncertainty plus the 10 ms glasses drift", 60L, row.uncertaintyMs)
    }

    @Test fun theUncertaintyIsTheClockMapsAndIsNeverCapped() = runBlocking {
        // phone samples taken 120 s before they were delivered (the oldest ClockMap still trusts): the age is part of the uncertainty
        val age = ClockMap.MAX_PHONE_AGE_MS
        val clock = PulledTraces.PhoneClock(
            phoneOffsetMs = 3_000, offsetUncertaintyMs = 50, startPhoneWallMs = W0 + 3_000 - age, endPhoneWallMs = W1 + 3_000 - age, startPhoneAgeMs = age, endPhoneAgeMs = age,
        )
        val row = import("trace-aaaa-0002", clock)
        assertEquals(TraceClock.OFFSET_ESTIMATED, row.clockQuality)
        assertEquals(ClockMap.of(PulledTraces.manifest("trace-aaaa-0002", "i", 1, clock = clock)).uncertaintyMs, row.uncertaintyMs)
        assertTrue("sample age counts: ${row.uncertaintyMs}", row.uncertaintyMs >= 120_000)
        assertEquals(W0 + 3_000, row.phoneStartMs)
    }

    @Test fun aTraceWithNoPhoneMappingIsUnmappedAndUnbounded() = runBlocking {
        val row = import("trace-aaaa-0003", PulledTraces.PhoneClock())
        assertEquals(TraceClock.UNMAPPED, row.clockQuality); assertEquals(TraceClock.UNBOUNDED_MS, row.uncertaintyMs)
        assertEquals("fallback: the glasses wall times, kept as coarse evidence only", W0, row.phoneStartMs); assertEquals(W1, row.phoneEndMs)
    }

    @Test fun aGlassesWallJumpIsUnmapped() = runBlocking {
        // phone samples whose offset moved by 5 s between start and end: ClockMap.phoneJump, no phone time
        val clock = PulledTraces.PhoneClock(phoneOffsetMs = 3_000, offsetUncertaintyMs = 50, startPhoneWallMs = W0 + 3_000, endPhoneWallMs = W1 + 8_000, startPhoneAgeMs = 0, endPhoneAgeMs = 0)
        assertEquals(TraceClock.UNMAPPED, import("trace-aaaa-0004", clock).clockQuality)
    }

    /** Admission is by the CORRECTED interval: a range that overlaps the trace only after the 3 s offset refuses it; one that overlaps only its glasses wall time does not. */
    @Test fun importAdmissionUsesThePhoneInterval() = runBlocking {
        // phone interval [W0+3000-60, W1+3000+60]; a range just after the glasses end and before the phone end
        dao.forgetRangeTx(W1 + 1_000, W1 + 2_000, 1)
        val r = importer.import(write("trace-aaaa-0005", offset3s))
        assertTrue("overlaps after correction: $r", r is TraceImportResult.Rejected)
        assertNull(dao.trace("trace-aaaa-0005"))
        // only the glasses wall time overlaps this range (it ends before the phone start minus the uncertainty)
        dao.forgetRangeTx(W0 - 2_000, W0 + 500, 2)
        assertTrue(importer.import(write("trace-aaaa-0006", PulledTraces.PhoneClock(phoneOffsetMs = 30_000, offsetUncertaintyMs = 50))) is TraceImportResult.Imported)
    }

    @Test fun aRangeForgetDeletesATraceThatOverlapsOnlyAfterTheOffsetCorrection() = runBlocking {
        import("trace-aaaa-0007", offset3s)
        // glasses wall ends at W1; the phone interval ends at W1 + 3000 (+60): this range is after the glasses end
        val out = dao.forgetRangeTx(W1 + 1_000, W1 + 2_000, 9)
        assertEquals(listOf("trace-aaaa-0007"), out.tombstonedTraceIds)
        assertTrue(dao.trace("trace-aaaa-0007")!!.tombstoned)
    }

    @Test fun aRangeThatOnlyTheGlassesWallTimeTouchesLeavesTheTrace() = runBlocking {
        import("trace-aaaa-0008", offset3s)
        // glasses start W0; the phone start is W0 + 3000 - 60: this range ends before it
        val out = dao.forgetRangeTx(W0 - 5_000, W0 + 2_000, 9)
        assertTrue(out.tombstonedTraceIds.isEmpty()); assertFalse(dao.trace("trace-aaaa-0008")!!.tombstoned)
    }

    @Test fun anUnmappedTraceIsDeletedByEveryRangeForget() = runBlocking {
        import("trace-aaaa-0009", PulledTraces.PhoneClock())
        assertEquals(listOf("trace-aaaa-0009"), dao.forgetRangeTx(5, 6, 9).tombstonedTraceIds)
    }

    @Test fun aLongSampleAgeWidensTheForgetBeyondFiveSeconds() = runBlocking {
        val age = ClockMap.MAX_PHONE_AGE_MS
        val clock = PulledTraces.PhoneClock(phoneOffsetMs = 3_000, offsetUncertaintyMs = 50, startPhoneWallMs = W0 + 3_000 - age, endPhoneWallMs = W1 + 3_000 - age, startPhoneAgeMs = age, endPhoneAgeMs = age)
        import("trace-aaaa-0010", clock)
        // 100 s after the phone end: only the uncapped 120 s uncertainty reaches it
        assertEquals(listOf("trace-aaaa-0010"), dao.forgetRangeTx(W1 + 3_000 + 100_000, W1 + 3_000 + 101_000, 9).tombstonedTraceIds)
    }
}
