package com.rokid.context.phone.capture

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.QuietHours
import com.rokid.context.phone.hub.HubPauses
import com.rokid.context.phone.hub.testDb
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Final review I2: "Capture now" reads the same pause rules as the hub (QuietWindow occurrences + resumed intervals). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StoredPausesTest {
    private val db = testDb(ApplicationProvider.getApplicationContext())
    private val zone = ZoneId.systemDefault()
    private val quiet = QuietHours(22 * 60, 7 * 60)
    private var t = at(2026, 10, 13, 23, 0)
    private val hub = HubPauses(db.pauseDao(), quietHours = { quiet }, zone = { zone }) { t }
    private val paused = PhoneCaptureControl.storedPauses({ db.pauseDao() }, { quiet }, { zone }) { t }
    @After fun close() = db.close()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun aResumedQuietHoursOccurrenceDoesNotBlockCaptureNow() = runTest {
        val id = hub.active().single().pauseId
        t += 1_000
        hub.resume(setOf(id))
        t += 60_000
        assertTrue(hub.active().isEmpty())
        assertFalse(paused())
    }

    @Test fun insideAnOccurrenceNotYetMintedIsPaused() = runTest {
        assertTrue(paused())
        assertTrue("read-only: nothing minted", db.pauseDao().records().isEmpty())
    }

    @Test fun aLiveQuietRecordIsPausedAndOutsideTheWindowIsNot() = runTest {
        hub.active()
        assertTrue(paused())
        t = at(2026, 10, 14, 7, 0)
        assertFalse(paused())
        assertEquals(1, db.pauseDao().records().size) // still not expired by the read
    }
}
