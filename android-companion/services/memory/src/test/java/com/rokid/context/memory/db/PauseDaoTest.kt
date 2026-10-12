package com.rokid.context.memory.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PauseDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val dao get() = db.pauseDao()
    @After fun close() = db.close()

    @Test fun coveringIsHalfOpenAndAnOpenIntervalCoversTheFuture() = runTest {
        dao.insertInterval(PauseIntervalEntity("a", "PHONE", startMs = 100, effectiveEndMs = 200))
        dao.insertInterval(PauseIntervalEntity("b", "GLASSES", startMs = 500, effectiveEndMs = null))
        assertEquals(1, dao.countCovering(100)); assertEquals(1, dao.countCovering(199)); assertEquals(0, dao.countCovering(200))
        assertEquals(0, dao.countCovering(499)); assertEquals(1, dao.countCovering(10_000))
    }

    @Test fun closingOnlyEndsAnOpenInterval() = runTest {
        dao.insertInterval(PauseIntervalEntity("a", "PHONE", 100, null))
        dao.closeInterval("a", 300)
        dao.closeInterval("a", 900)
        assertEquals(300L, dao.intervals().single().effectiveEndMs)
    }

    @Test fun duplicateIntervalInsertIsIgnored() = runTest {
        assertTrue(dao.insertInterval(PauseIntervalEntity("a", "PHONE", 100, null)) > 0)
        assertEquals(-1L, dao.insertInterval(PauseIntervalEntity("a", "PHONE", 999, null)))
        assertEquals(100L, dao.intervals().single().startMs)
    }

    @Test fun pruneKeepsOpenAndRecentIntervals() = runTest {
        dao.insertInterval(PauseIntervalEntity("old", "PHONE", 1, 50))
        dao.insertInterval(PauseIntervalEntity("recent", "PHONE", 1, 500))
        dao.insertInterval(PauseIntervalEntity("open", "PHONE", 1, null))
        dao.pruneEndedBefore(100)
        assertEquals(setOf("recent", "open"), dao.intervals().map { it.pauseId }.toSet())
    }

    @Test fun recordsUpsertAndDelete() = runTest {
        dao.upsertRecord(PauseRecordEntity("r1", "GLASSES", 10, null))
        dao.upsertRecord(PauseRecordEntity("r1", "GLASSES", 10, 99))
        assertEquals(99L, dao.records().single().untilMs)
        dao.deleteRecords(listOf("r1"))
        assertTrue(dao.records().isEmpty())
    }
}
