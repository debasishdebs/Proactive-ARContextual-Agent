package com.rokid.context.memory.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentLocationDaoTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val dao = db.momentLocationDao()
    @After fun close() = db.close()

    private fun row(id: String, atMs: Long = 1000, kind: String = "FRAME", anchor: String = "f-$id", lat: Double = 1.0, source: String = "ZONE_STREAM") =
        MomentLocationEntity(id, atMs, kind, anchor, lat, 2.0, 5.0, 100, source, null, null, 9)

    @Test fun insertIfAbsentFirstWriterWins() = runBlocking {
        assertTrue(dao.insertIfAbsent(row("m1", lat = 1.0)) > 0)
        assertEquals(-1L, dao.insertIfAbsent(row("m1", lat = 7.0, source = "MEDIA_BACKFILL")))
        val got = dao.of(listOf("m1")).single()
        assertEquals(1.0, got.lat, 0.0); assertEquals("ZONE_STREAM", got.source)
        assertEquals(1, dao.count())
    }

    @Test fun ofReturnsOnlyAskedIds() = runBlocking {
        dao.insertIfAbsent(row("m1")); dao.insertIfAbsent(row("m2")); dao.insertIfAbsent(row("m3"))
        assertEquals(setOf("m1", "m3"), dao.of(listOf("m1", "m3", "nope")).map { it.momentId }.toSet())
        assertEquals(0, dao.of(emptyList()).size)
    }

    @Test fun deleteForAnchors() = runBlocking {
        dao.insertIfAbsent(row("m1", anchor = "a1")); dao.insertIfAbsent(row("m2", anchor = "a2")); dao.insertIfAbsent(row("m3", kind = "MARK", anchor = "a3"))
        assertEquals(2, dao.deleteForAnchors(listOf("a1", "a3", "zz")))
        assertEquals(listOf("m2"), dao.of(listOf("m1", "m2", "m3")).map { it.momentId })
    }

    @Test fun deleteForAnchorsAndMomentsAreChunkedPast999Ids() = runBlocking {
        val ids = (1..1200).map { "m%04d".format(it) }
        ids.forEach { dao.insertIfAbsent(row(it, anchor = "a$it")) }
        assertEquals(1200, dao.deleteForAnchors(ids.map { "a$it" }))
        ids.forEach { dao.insertIfAbsent(row(it, anchor = "a$it")) }
        assertEquals(1200, dao.deleteForMoments(ids))
        assertEquals(0, dao.count())
    }

    @Test fun deleteForMoments() = runBlocking {
        dao.insertIfAbsent(row("m1")); dao.insertIfAbsent(row("m2"))
        assertEquals(1, dao.deleteForMoments(listOf("m2", "gone")))
        assertEquals(listOf("m1"), dao.of(listOf("m1", "m2")).map { it.momentId })
    }

    @Test fun deleteBetweenInclusive() = runBlocking {
        listOf(99L, 100L, 150L, 200L, 201L).forEach { dao.insertIfAbsent(row("m$it", atMs = it)) }
        assertEquals(3, dao.deleteBetween(100, 200))
        assertEquals(setOf("m99", "m201"), dao.of(listOf("m99", "m100", "m150", "m200", "m201")).map { it.momentId }.toSet())
    }

    @Test fun deleteAllMomentLocations() = runBlocking {
        dao.insertIfAbsent(row("m1")); dao.insertIfAbsent(row("m2"))
        assertEquals(2, dao.deleteAllMomentLocations()); assertEquals(0, dao.count())
    }
}
