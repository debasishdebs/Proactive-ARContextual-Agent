package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A1: `PauseDao.overlapping` covers closed and open intervals, half-open at both ends, and keeps `zone:` apart from a manual pause by the id. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PauseOverlapTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao get() = db.pauseDao()
    @After fun close() = db.close()

    @Test fun closedAndOpenIntervalsOverlapAHalfOpenRange() = runBlocking<Unit> {
        dao.insertInterval(PauseIntervalEntity("before", "PHONE", 0, 100))
        dao.insertInterval(PauseIntervalEntity("touchesStart", "PHONE", 50, 100)) // ends exactly at the range start: no overlap
        dao.insertInterval(PauseIntervalEntity("inside", "GLASSES", 120, 150))
        dao.insertInterval(PauseIntervalEntity("across", "PHONE", 90, 250))
        dao.insertInterval(PauseIntervalEntity("startsAtEnd", "PHONE", 200, 300)) // starts exactly at the range end: no overlap
        dao.insertInterval(PauseIntervalEntity("zone:home", "PHONE", 10, null)) // open: covers the future
        assertEquals(listOf("zone:home", "across", "inside"), dao.overlapping(100, 200).map { it.pauseId })
    }

    @Test fun zoneIntervalsAreToldApartByTheirIdPrefix() = runBlocking<Unit> {
        dao.insertInterval(PauseIntervalEntity("zone:a", "PHONE", 100, 200))
        dao.insertInterval(PauseIntervalEntity("manual", "PHONE", 100, 200))
        val rows = dao.overlapping(0, 1_000)
        assertEquals(setOf("zone:a"), rows.filter { it.pauseId.startsWith("zone:") }.map { it.pauseId }.toSet())
        assertEquals(setOf("manual"), rows.filterNot { it.pauseId.startsWith("zone:") }.map { it.pauseId }.toSet())
    }
}
