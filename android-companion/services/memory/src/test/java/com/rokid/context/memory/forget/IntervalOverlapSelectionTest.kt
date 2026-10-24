package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.CanvasTraceEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.TraceClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Spec 7.3: a trace is selected when `phoneStart - uncertainty <= to && phoneEnd + uncertainty >= from`, over ALL traces, with the uncertainty NOT capped; UNMAPPED is unbounded. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class IntervalOverlapSelectionTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao = db.canvasSlice3Dao()
    private val epoch = DeletionEpoch("i", 1, 0)
    private val fence = DaoFence(0, epoch, { 0 }, { epoch })
    @After fun close() = db.close()

    private fun t(id: String, ps: Long, pe: Long?, unc: Long, q: String = TraceClock.OFFSET_ESTIMATED, glassesStart: Long = 1) =
        CanvasTraceEntity(id, "IMU", "COMPLETE", "{}", 0, installId = "i", deletionCounter = 1, startedAtMs = glassesStart, endedAtMs = glassesStart + 1, createdAt = 1,
            phoneStartMs = ps, phoneEndMs = pe, clockQuality = q, uncertaintyMs = unc)

    @Test fun anAdjacentPartWhoseIntervalOverlapsIsSelectedAndOneThatDoesNotIsNot() {
        val adjacent = t("a", 10_000, 10_500, 3_000)           // reaches [7000, 13500]
        assertTrue(TraceClock.overlaps(adjacent, 13_000, 14_000))
        assertTrue("boundary is inclusive", TraceClock.overlaps(adjacent, 13_500, 14_000)); assertTrue(TraceClock.overlaps(adjacent, 6_000, 7_000))
        assertFalse(TraceClock.overlaps(adjacent, 13_501, 14_000)); assertFalse(TraceClock.overlaps(adjacent, 6_000, 6_999))
        assertFalse("without the uncertainty it is not adjacent", TraceClock.overlaps(t("b", 10_000, 10_500, 0), 13_000, 14_000))
    }

    @Test fun theUncertaintyIsNotCapped() {
        assertTrue(TraceClock.overlaps(t("a", 200_000, 200_500, 120_000), 80_000, 80_001)) // 120 s reaches back 120 s
        assertTrue(TraceClock.overlaps(t("a", 200_000, 200_500, 120_000), 320_500, 330_000))
        assertFalse(TraceClock.overlaps(t("a", 200_000, 200_500, 120_000), 320_501, 330_000))
    }

    @Test fun openEndedAndUnmappedAreHandledConservatively() {
        assertTrue(TraceClock.overlaps(t("open", 5_000, null, 0), 9_000_000, 9_000_001))
        assertFalse(TraceClock.overlaps(t("open", 5_000, null, 0), 1, 4_999))
        assertTrue(TraceClock.overlaps(t("u", 0, null, TraceClock.UNBOUNDED_MS, TraceClock.UNMAPPED), 77, 78))
        assertTrue("whatever the stored uncertainty, UNMAPPED is unbounded", TraceClock.overlaps(t("u", 0, 1, 0, TraceClock.UNMAPPED), 9_000_000, 9_000_001))
    }

    /** The scan is over ALL live traces, the glasses wall columns play no part: the near trace is not what selects the far one. */
    @Test fun forgetSelectsEveryOverlappingTraceNotOnlyTheNearOnes() = runBlocking {
        val rows = listOf(
            t("near", 10_000, 10_500, 0, glassesStart = 900_000),         // inside the range, glasses wall far away
            t("adjacent", 20_000, 20_500, 6_000, glassesStart = 1),       // reaches [14000, 26500]: overlaps only through its uncertainty
            t("far", 60_000, 60_500, 100, glassesStart = 10_500),         // glasses wall inside the range, phone interval far outside
            t("unmapped", 5, 6, TraceClock.UNBOUNDED_MS, TraceClock.UNMAPPED),
        )
        rows.forEach { assertTrue(dao.acceptTraceFenced(it.copy(), fence) || it.clockQuality == TraceClock.UNMAPPED) }
        val ids = dao.forgetRangeTx(9_000, 15_000, 7).tombstonedTraceIds.toSet()
        assertEquals(setOf("near", "adjacent", "unmapped"), ids)
        assertFalse(dao.trace("far")!!.tombstoned)
    }
}
