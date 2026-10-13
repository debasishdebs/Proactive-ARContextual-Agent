package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.model.QuietHours
import com.rokid.context.core.model.QuietWindow
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HubPausesTest {
    private val db = testDb(ApplicationProvider.getApplicationContext())
    private var t = 1_793_000_000_000L
    private val pauses = HubPauses(db.pauseDao()) { t }
    @After fun close() = db.close()

    @Test fun indefinitePauseStaysUntilResumedByName() = runTest {
        pauses.add(PauseRecord("g1", PauseOrigin.GLASSES, createdAtMs = t, untilMs = null))
        t += 86_400_000
        assertEquals(listOf("g1"), pauses.active().map { it.pauseId })
        pauses.resume(setOf("other"))
        assertEquals(1, pauses.active().size)
        pauses.resume(setOf("g1"))
        assertTrue(pauses.active().isEmpty())
    }

    @Test fun timedPauseExpiresAndKeepsItsInterval() = runTest {
        val start = t
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, start, start + 1_800_000))
        assertTrue(pauses.pausedAt(start + 60_000))
        t = start + 1_800_000
        assertTrue(pauses.active().isEmpty())
        assertTrue("closed interval is retained", pauses.pausedAt(start + 1_799_999))
        assertFalse(pauses.pausedAt(start + 1_800_000))
    }

    @Test fun resumeClosesTheIntervalAtResumeTime() = runTest {
        pauses.add(PauseRecord("g1", PauseOrigin.GLASSES, t, null))
        t += 5_000
        pauses.resume(setOf("g1"))
        assertTrue(pauses.pausedAt(t - 1))
        assertFalse(pauses.pausedAt(t))
    }

    @Test fun unionAddsOnlyNewRecords() = runTest {
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, null))
        pauses.union(listOf(PauseRecord("p1", PauseOrigin.PHONE, t, null), PauseRecord("g2", PauseOrigin.GLASSES, t, null)))
        assertEquals(setOf("p1", "g2"), pauses.active().map { it.pauseId }.toSet())
    }

    @Test fun anAlreadyExpiredRecordOnlyAddsHistory() = runTest {
        pauses.union(listOf(PauseRecord("old", PauseOrigin.GLASSES, t - 10_000, t - 5_000)))
        assertTrue(pauses.active().isEmpty())
        assertTrue(pauses.pausedAt(t - 7_000))
        assertFalse(pauses.pausedAt(t - 5_000))
    }

    @Test fun historyOlderThanSevenDaysIsPruned() = runTest {
        pauses.add(PauseRecord("p", PauseOrigin.PHONE, t, t + 1_000))
        t += 8 * 86_400_000L
        pauses.active()
        pauses.pruneHistory()
        assertFalse(pauses.pausedAt(t - 8 * 86_400_000L + 500))
    }

    @Test fun aStaleTimedCopyNeverShortensAnIndefinitePause() = runTest {
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, null))
        pauses.union(listOf(PauseRecord("p1", PauseOrigin.PHONE, t, t + 1_000)))
        t += 5_000
        assertEquals(listOf(null), pauses.active().map { it.untilMs })
    }

    @Test fun mergeKeepsTheLaterEnd() = runTest {
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, t + 60_000))
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, t + 10_000))
        assertEquals(listOf(t + 60_000), pauses.active().map { it.untilMs })
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, null))
        assertEquals(listOf(null), pauses.active().map { it.untilMs })
    }

    @Test fun aResumedPauseIsNeverResurrected() = runTest {
        pauses.add(PauseRecord("g1", PauseOrigin.GLASSES, t, null))
        t += 1_000
        pauses.resume(setOf("g1"))
        pauses.union(listOf(PauseRecord("g1", PauseOrigin.GLASSES, t - 1_000, null)))
        assertTrue(pauses.active().isEmpty())
        assertFalse(pauses.pausedAt(t + 10))
    }

    @Test fun orphanOpenIntervalsAreClosedByPrune() = runTest {
        db.pauseDao().insertInterval(com.rokid.context.memory.db.PauseIntervalEntity("orphan", "PHONE", t - 1_000, null))
        assertTrue(pauses.pausedAt(t))
        pauses.pruneHistory()
        assertFalse(pauses.pausedAt(t))
    }

    // ---- Phase 7 A1: quiet hours, resumed ids, atomic expiry ----

    private var quiet: QuietHours? = null
    private var zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val qPauses = HubPauses(db.pauseDao(), quietHours = { quiet }, zone = { zone }, now = { t })
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, z: ZoneId = zone) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(z).toInstant().toEpochMilli()

    @Test fun quietHoursCreatesOneRecordPerOccurrence() = runTest {
        quiet = QuietHours(22 * 60, 7 * 60)
        t = at(2026, 10, 13, 23, 0)
        val first = qPauses.active()
        t += 60_000
        val again = qPauses.active()
        assertEquals(1, first.size)
        assertEquals(first.map { it.pauseId }, again.map { it.pauseId })
        assertEquals(PauseOrigin.QUIET_HOURS, first.single().origin)
        assertEquals(at(2026, 10, 13, 22, 0), first.single().createdAtMs)
        t = at(2026, 10, 14, 12, 0)
        assertTrue(qPauses.active().isEmpty())
        t = at(2026, 10, 14, 23, 0)
        val next = qPauses.active()
        assertEquals(1, next.size)
        assertNotEquals(first.single().pauseId, next.single().pauseId)
    }

    @Test fun resumedQuietOccurrenceIsNotRecreated() = runTest {
        quiet = QuietHours(22 * 60, 7 * 60)
        t = at(2026, 10, 13, 23, 0)
        val id = qPauses.active().single().pauseId
        t += 1_000
        qPauses.resume(setOf(id))
        t += 60_000
        assertTrue(qPauses.active().isEmpty())
    }

    @Test fun quietWindowOverMidnight() = runTest {
        quiet = QuietHours(22 * 60, 7 * 60)
        t = at(2026, 10, 13, 23, 30)
        assertEquals(at(2026, 10, 14, 7, 0), qPauses.active().single().untilMs)
        val r = QuietWindow.occurrenceAt(quiet!!, at(2026, 10, 14, 6, 59), zone)!!
        assertEquals(at(2026, 10, 13, 22, 0), r.first)
        assertEquals(at(2026, 10, 14, 7, 0), r.last + 1)
        assertNull(QuietWindow.occurrenceAt(quiet!!, at(2026, 10, 14, 7, 0), zone))
    }

    @Test fun quietWindowOnSpringForward() = runTest {
        // Europe/Berlin: 2026-03-29 02:00 -> 03:00. 02:30 does not exist.
        val q = QuietHours(2 * 60 + 30, 3 * 60 + 30)
        val inside = at(2026, 3, 29, 3, 10)
        val r = QuietWindow.occurrenceAt(q, inside, zone)
        assertNotNull(r)
        assertTrue("non-empty, never negative", r!!.last >= r.first)
        assertTrue(inside in r)
        assertEquals(at(2026, 3, 29, 3, 30), r.last + 1)
    }

    @Test fun quietStartEqualsEndNeverPauses() = runTest {
        quiet = QuietHours(9 * 60, 9 * 60)
        t = at(2026, 10, 13, 0, 0)
        repeat(96) {
            assertTrue(qPauses.active().isEmpty())
            t += 15 * 60_000
        }
    }

    @Test fun zoneChangeMidWindowKeepsExistingRecord() = runTest {
        quiet = QuietHours(22 * 60, 7 * 60)
        t = at(2026, 10, 13, 23, 30)
        val rec = qPauses.active().single()
        zone = ZoneId.of("Asia/Kolkata") // 23:30 Berlin = 03:00 Kolkata, still inside 22-07 there
        val after = qPauses.active()
        assertEquals(listOf(rec), after)
    }

    @Test fun expiryIsAtomic() = runTest {
        pauses.add(PauseRecord("p1", PauseOrigin.PHONE, t, t + 1_000))
        t += 5_000
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_del BEFORE DELETE ON pause_record BEGIN SELECT RAISE(ABORT, 'boom'); END",
        )
        val failed = runCatching { pauses.active() }
        assertTrue(failed.isFailure)
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_del")
        assertEquals(listOf("p1"), db.pauseDao().records().map { it.pauseId })
        assertNull("the close rolled back with the delete", db.pauseDao().intervalFor("p1")!!.effectiveEndMs)
    }

    @Test fun recentlyResumedIdsExcludesLiveAndOld() = runTest {
        val day = 86_400_000L
        pauses.add(PauseRecord("old", PauseOrigin.GLASSES, t, null))
        pauses.add(PauseRecord("recent", PauseOrigin.GLASSES, t, null))
        pauses.add(PauseRecord("live", PauseOrigin.PHONE, t, null))
        pauses.resume(setOf("old"))
        t += 8 * day - 3_600_000
        pauses.resume(setOf("recent"))
        t += 3_600_000
        assertEquals(listOf("recent"), pauses.recentlyResumedIds())
    }

    // ---- Fix round 1 ----

    /** I2: a resumed id stays a tombstone for 90 days, past the 7-day history prune; a late echo never re-adds it. */
    @Test fun resumedIdIsNotReinsertedAfterTheHistoryPrune() = runTest {
        pauses.add(PauseRecord("g1", PauseOrigin.GLASSES, t, null))
        t += 1_000
        pauses.resume(setOf("g1"))
        t += 8 * 86_400_000L
        pauses.pruneHistory()
        assertFalse("history no longer covers the old interval", pauses.pausedAt(t - 8 * 86_400_000L - 500))
        pauses.union(listOf(PauseRecord("g1", PauseOrigin.GLASSES, t - 8 * 86_400_000L - 1_000, null)))
        assertTrue(pauses.active().isEmpty())
        assertFalse(pauses.pausedAt(t))
        t += 90 * 86_400_000L
        pauses.pruneHistory()
        assertNull("gone after 90 days", db.pauseDao().intervalFor("g1"))
    }

    /** M1: an occurrence overlapping a handled one (here: the start moved inside the window) is not re-created. */
    @Test fun movedStartInsideAResumedWindowIsNotRecreated() = runTest {
        quiet = QuietHours(22 * 60, 7 * 60)
        t = at(2026, 10, 13, 23, 0)
        qPauses.resume(setOf(qPauses.active().single().pauseId))
        quiet = QuietHours(22 * 60 + 15, 7 * 60)
        t += 60_000
        assertTrue(qPauses.active().isEmpty())
    }

    /** M12: out-of-range minutes never crash; they are no window. */
    @Test fun outOfRangeQuietMinutesAreNoWindow() = runTest {
        assertNull(QuietWindow.occurrenceAt(QuietHours(-15, 7 * 60), t, zone))
        assertNull(QuietWindow.occurrenceAt(QuietHours(22 * 60, 1_440), t, zone))
        quiet = QuietHours(22 * 60, 99_999)
        assertTrue(qPauses.active().isEmpty())
    }
}
