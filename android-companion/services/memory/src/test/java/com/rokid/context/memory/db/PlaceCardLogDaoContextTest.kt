package com.rokid.context.memory.db

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.place.CardDelivery
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The cases of `place/PlaceCardLogDaoTest` against context.db (v18), plus `deleteCardLogForPins` and the ABORT insert. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceCardLogDaoContextTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val dao = db.placeCardLogDao()
    @After fun close() = db.close()

    private fun row(id: String, at: Long, placeId: String = "wd:Q1", frame: String? = null, members: String? = null, card: String? = "c-$id") =
        PlaceCardLogEntity(id, at, placeId, "Site A", "PHOTO", members, frame, null, card, CardDelivery.PENDING)

    @Test fun framesAreDeletedOrMarkedGoneKeepingTheirLineage() = runBlocking {
        dao.put(row("a", 1, frame = "f1")); dao.put(row("b", 2, frame = "f2")); dao.put(row("c", 3, frame = null))
        assertEquals(1, dao.markCardLogPhotoGone(listOf("f1")))
        dao.cardLog("a")!!.let { assertEquals("f1", it.frameId); assertEquals(1, it.photoGone) }
        assertEquals(1, dao.deleteCardLogForFrames(listOf("f1")))
        assertNull(dao.cardLog("a"))
        assertEquals(1, dao.deleteCardLogForFrames(listOf("f2")))
        assertNull(dao.cardLog("b")); assertEquals(1, dao.cardLogCount())
        assertEquals(listOf("f1"), run { dao.put(row("d", 4, frame = "f1")); dao.cardLogFrameIds() })
    }

    @Test fun rangeAndAllAndOne() = runBlocking {
        (1..5).forEach { dao.put(row("r$it", it * 10L)) }
        assertEquals(3, dao.deleteCardLogBetween(20, 40)); assertEquals(listOf("r5", "r1"), dao.recentCardLog(10).map { it.id })
        assertEquals(1, dao.deleteCardLog("r1"))
        assertEquals(1, dao.deleteAllCardLog()); assertEquals(0, dao.cardLogCount())
    }

    @Test fun pinRemovalTakesItsRowsAndTheClustersThatListIt() = runBlocking {
        val p1 = "user:00000000-0000-0000-0000-000000000001"; val p2 = "user:00000000-0000-0000-0000-000000000002"; val p3 = "user:00000000-0000-0000-0000-000000000003"
        dao.put(row("single", 1, placeId = p1))
        dao.put(row("cluster12", 2, placeId = p1, members = "$p1,$p2"))
        dao.put(row("cluster23", 3, placeId = p2, members = "$p2,$p3"))
        dao.put(row("other", 4, placeId = p3))
        assertEquals(3, dao.deleteCardLogForPin(p2) + dao.deleteCardLogForPin(p1))
        assertEquals(listOf("other"), dao.recentCardLog(10).map { it.id })
    }

    @Test fun deleteCardLogForPinsDoesTheSameForSeveralPinsInOneCall() = runBlocking {
        val p1 = "user:00000000-0000-0000-0000-000000000001"; val p2 = "user:00000000-0000-0000-0000-000000000002"; val p3 = "user:00000000-0000-0000-0000-000000000003"
        dao.put(row("single", 1, placeId = p1))
        dao.put(row("cluster12", 2, placeId = p1, members = "$p1,$p2"))
        dao.put(row("cluster23", 3, placeId = p2, members = "$p2,$p3"))
        dao.put(row("other", 4, placeId = p3))
        assertEquals(3, dao.deleteCardLogForPins(listOf(p1, p2)))
        assertEquals(listOf("other"), dao.recentCardLog(10).map { it.id })
        assertEquals(0, dao.deleteCardLogForPins(emptyList()))
        assertEquals(1, dao.cardLogCount())
    }

    @Test fun prunesByAgeAndByCap() = runBlocking {
        (1..10).forEach { dao.put(row("p$it", it.toLong())) }
        assertEquals(2, dao.deleteCardLogBefore(3))
        assertEquals(5, dao.trimCardLogTo(3)); assertEquals(listOf("p10", "p9", "p8"), dao.recentCardLog(10).map { it.id })
        assertEquals(listOf("p10", "p9", "p8"), dao.cardLogSince(8).map { it.id })
    }

    @Test fun deliveryStates() = runBlocking {
        dao.put(row("a", 1)); dao.put(row("b", 2))
        assertEquals(1, dao.setCardDelivery("c-a", CardDelivery.SENT))
        assertEquals(0, dao.setCardDeliveryIfPending("c-a", CardDelivery.NOT_SENT_LINK))
        assertEquals(1, dao.setCardDeliveryIfPending("c-b", CardDelivery.NOT_SENT_LINK))
        assertEquals(CardDelivery.SENT, dao.cardLog("a")!!.delivery); assertEquals(CardDelivery.NOT_SENT_LINK, dao.cardLog("b")!!.delivery)
        assertEquals("b", dao.cardLogByCard("c-b")!!.id)
    }

    /** `put` is ABORT, not REPLACE: a second row with the same id fails and the first stays. */
    @Test fun putAbortsOnDuplicateId() = runBlocking {
        dao.put(row("a", 1))
        try { dao.put(row("a", 2)); fail("duplicate id must abort") } catch (e: SQLiteConstraintException) { /* expected */ }
        assertEquals(1L, dao.cardLog("a")!!.at)
    }
}
