package com.rokid.context.phone.hub

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
