package com.rokid.context.canvas.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.capture.trace.ClockMap
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.TraceClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Spec 7.2: rows imported before v10 carry only glasses wall times and the conservative UNMAPPED default. The first-run [TraceClockBackfill] maps them from their STORED manifests;
 * a row without a usable clock stays UNMAPPED (unbounded), a tombstoned row is never touched, and a second run changes nothing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TraceClockBackfillTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db = DatabaseFactory.inMemory(ctx)
    private val dao = db.canvasSlice3Dao()
    private val e = DeletionEpoch("i", 1, 0)
    private val fence = DaoFence(0, e, { 0 }, { e })
    private val W0 = 1_700_000_001_000L
    private val W1 = 1_700_000_012_010L
    @After fun close() = db.close()

    /** A pre-v10 row: glasses wall times, the manifest as stored, and the migration defaults (UNMAPPED, phoneStart 0, no phone end, uncertainty 0). */
    private suspend fun legacy(id: String, manifestJson: String, source: String = "IMU", start: Long = W0, end: Long? = W1) {
        assertTrue(dao.acceptTraceFenced(
            CanvasTraceEntity(id, source, "COMPLETE", manifestJson, 0, installId = "i", deletionCounter = 1, startedAtMs = start, endedAtMs = end, createdAt = 1,
                phoneStartMs = 0, phoneEndMs = null, clockQuality = TraceClock.UNMAPPED, uncertaintyMs = 0), fence))
    }
    private fun manifest(id: String, clock: PulledTraces.PhoneClock) = PulledTraces.manifest(id, "i", 1, clock = clock).encode()

    @Test fun aThreeSecondOffsetRowIsMappedFromItsStoredManifest() = runBlocking {
        legacy("trace-bbbb-0001", manifest("trace-bbbb-0001", PulledTraces.PhoneClock(phoneOffsetMs = 3_000, offsetUncertaintyMs = 50)))
        assertEquals(1, TraceClockBackfill(dao).run())
        val r = dao.trace("trace-bbbb-0001")!!
        assertEquals(W0 + 3_000, r.phoneStartMs); assertEquals(W1 + 3_000, r.phoneEndMs); assertEquals(TraceClock.OFFSET_ESTIMATED, r.clockQuality); assertEquals(60L, r.uncertaintyMs)
        assertEquals("the glasses wall times are untouched", W0, r.startedAtMs)
    }

    @Test fun aLongSampleAgeUncertaintyIsKeptUncapped() = runBlocking {
        val age = ClockMap.MAX_PHONE_AGE_MS
        val c = PulledTraces.PhoneClock(phoneOffsetMs = 3_000, offsetUncertaintyMs = 50, startPhoneWallMs = W0 + 3_000 - age, endPhoneWallMs = W1 + 3_000 - age, startPhoneAgeMs = age, endPhoneAgeMs = age)
        legacy("trace-bbbb-0002", manifest("trace-bbbb-0002", c))
        TraceClockBackfill(dao).run()
        val r = dao.trace("trace-bbbb-0002")!!
        assertEquals(ClockMap.of(PulledTraces.manifest("trace-bbbb-0002", "i", 1, clock = c)).uncertaintyMs, r.uncertaintyMs); assertTrue(r.uncertaintyMs >= 120_000)
    }

    @Test fun unmappedAndUnparseableRowsStayUnboundedAndAreNeverGuessed() = runBlocking {
        legacy("trace-bbbb-0003", manifest("trace-bbbb-0003", PulledTraces.PhoneClock())) // a manifest with no phone offset and no samples
        legacy("trace-bbbb-0004", "{}")                                                    // no usable manifest at all
        assertEquals(0, TraceClockBackfill(dao).run())
        listOf("trace-bbbb-0003", "trace-bbbb-0004").forEach { id ->
            val r = dao.trace(id)!!
            assertEquals(id, TraceClock.UNMAPPED, r.clockQuality) // unbounded by the quality, whatever the stored uncertainty
        }
        assertEquals("both are still selected by any range", 2, dao.forgetRangeTx(1, 2, 9).tombstonedTraceIds.size)
    }

    @Test fun aHubRowIsAlreadyOnThePhoneClock() = runBlocking {
        legacy("trace-bbbb-0005", "{}", source = "HUB", start = 7_000, end = 8_000)
        TraceClockBackfill(dao).run()
        val r = dao.trace("trace-bbbb-0005")!!
        assertEquals(7_000L, r.phoneStartMs); assertEquals(8_000L, r.phoneEndMs); assertEquals(TraceClock.EXACT, r.clockQuality); assertEquals(0L, r.uncertaintyMs)
    }

    @Test fun secondRunChangesNothingAndATombstonedRowIsSkipped() = runBlocking {
        legacy("trace-bbbb-0006", manifest("trace-bbbb-0006", PulledTraces.PhoneClock(phoneOffsetMs = 3_000, offsetUncertaintyMs = 50)))
        legacy("trace-bbbb-0007", manifest("trace-bbbb-0007", PulledTraces.PhoneClock(phoneOffsetMs = 3_000, offsetUncertaintyMs = 50)))
        dao.tombstoneTrace("trace-bbbb-0007", 5)
        assertEquals(1, TraceClockBackfill(dao).run()); val first = dao.trace("trace-bbbb-0006")!!
        assertEquals(0, TraceClockBackfill(dao).run()); assertEquals(first, dao.trace("trace-bbbb-0006"))
        assertEquals(TraceClock.UNMAPPED, dao.trace("trace-bbbb-0007")!!.clockQuality)
    }
}
