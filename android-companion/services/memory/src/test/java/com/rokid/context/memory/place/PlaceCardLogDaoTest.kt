package com.rokid.context.memory.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceCardLogDaoTest {
    private val db = PlacesDatabase.inMemory(ApplicationProvider.getApplicationContext<Context>())
    private val dao = db.dao()
    @After fun close() = db.close()

    private fun row(id: String, at: Long, placeId: String = "wd:Q1", frame: String? = null, members: String? = null, card: String? = "c-$id") =
        PlaceCardLogEntity(id, at, placeId, "Site A", "PHOTO", members, frame, null, card, CardDelivery.PENDING)

    @Test fun framesAreDeletedOrUnlinked() = runBlocking {
        dao.putCardLog(row("a", 1, frame = "f1")); dao.putCardLog(row("b", 2, frame = "f2")); dao.putCardLog(row("c", 3, frame = null))
        assertEquals(1, dao.unlinkCardLogFrames(listOf("f1")))
        dao.cardLog("a")!!.let { assertNull(it.frameId); assertEquals(1, it.photoGone) }
        assertEquals(1, dao.deleteCardLogForFrames(listOf("f2")))
        assertNull(dao.cardLog("b")); assertEquals(2, dao.cardLogCount())
    }

    @Test fun rangeAndAllAndOne() = runBlocking {
        (1..5).forEach { dao.putCardLog(row("r$it", it * 10L)) }
        assertEquals(3, dao.deleteCardLogBetween(20, 40)); assertEquals(listOf("r5", "r1"), dao.recentCardLog(10).map { it.id })
        assertEquals(1, dao.deleteCardLog("r1"))
        assertEquals(1, dao.deleteAllCardLog()); assertEquals(0, dao.cardLogCount())
    }

    @Test fun pinRemovalTakesItsRowsAndTheClustersThatListIt() = runBlocking {
        val p1 = "user:00000000-0000-0000-0000-000000000001"; val p2 = "user:00000000-0000-0000-0000-000000000002"; val p3 = "user:00000000-0000-0000-0000-000000000003"
        dao.putCardLog(row("single", 1, placeId = p1))
        dao.putCardLog(row("cluster12", 2, placeId = p1, members = "$p1,$p2"))
        dao.putCardLog(row("cluster23", 3, placeId = p2, members = "$p2,$p3"))
        dao.putCardLog(row("other", 4, placeId = p3))
        assertEquals(3, dao.deleteCardLogForPin(p2) + dao.deleteCardLogForPin(p1)) // p2: cluster12 + cluster23; p1: single
        assertEquals(listOf("other"), dao.recentCardLog(10).map { it.id })
    }

    @Test fun prunesByAgeAndByCap() = runBlocking {
        (1..10).forEach { dao.putCardLog(row("p$it", it.toLong())) }
        assertEquals(2, dao.deleteCardLogBefore(3))
        assertEquals(5, dao.trimCardLogTo(3)); assertEquals(listOf("p10", "p9", "p8"), dao.recentCardLog(10).map { it.id })
    }

    @Test fun deliveryStates() = runBlocking {
        dao.putCardLog(row("a", 1)); dao.putCardLog(row("b", 2))
        assertEquals(1, dao.setCardDelivery("c-a", CardDelivery.SENT))
        assertEquals(0, dao.setCardDeliveryIfPending("c-a", CardDelivery.NOT_SENT_LINK)) // already SENT
        assertEquals(1, dao.setCardDeliveryIfPending("c-b", CardDelivery.NOT_SENT_LINK))
        assertEquals(CardDelivery.SENT, dao.cardLog("a")!!.delivery); assertEquals(CardDelivery.NOT_SENT_LINK, dao.cardLog("b")!!.delivery)
    }
}
