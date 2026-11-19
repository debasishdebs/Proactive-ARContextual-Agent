package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentPartKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Slice 1.4.2 (Pin to Moment): the lookup the pin screen and the link sweep stand on. A Moment is "alive" only while a stored photo of it exists and its second is not tombstoned, so EVERY Forget
 * path (single, range, scene span, retention expiry, Delete all) makes `photoMomentsOf` stop returning it, and the pin's link is then cleared by the sweep (`PinMomentLinksTest`).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PinMomentLookupTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentForgetFixture(db)
    private val md = db.momentDao()
    private val second2 = MomentForgetFixture.SEC + 100
    private val id2 = com.rokid.context.memory.moment.MomentId.format(second2)
    @After fun close() = db.close()

    private suspend fun two() {
        fx.frame("a", MomentForgetFixture.MS + 5); fx.project(fx.id, MomentPartKind.FRAME to "a")
        fx.frame("b", second2 * 1000 + 5); fx.project(id2, MomentPartKind.FRAME to "b")
    }
    private suspend fun alive() = md.photoMomentsOf(listOf(fx.id, id2)).map { it.momentId }.sorted()

    @Test fun recentPhotoMomentsAreNewestFirstWithTheirPhotoPathAndLimited() = runBlocking<Unit> {
        two()
        assertEquals(listOf(id2, fx.id), md.recentPhotoMoments(20).map { it.momentId })
        assertEquals(listOf("/p/b.jpg"), md.recentPhotoMoments(1).map { it.path })
        assertEquals("a Moment without a photo (a mark) is not listed", listOf(id2, fx.id), run {
            val markOnly = com.rokid.context.memory.moment.MomentId.format(second2 + 1); fx.project(markOnly, MomentPartKind.MARK to "L"); md.recentPhotoMoments(20).map { it.momentId }
        })
    }

    @Test fun forgettingThePhotoOfAMomentEndsItsLifeAndOnlyItsOwn() = runBlocking<Unit> {
        two(); assertEquals(listOf(fx.id, id2).sorted(), alive())
        fx.service.forgetMoment("a")
        assertEquals(listOf(id2), alive())
    }

    @Test fun aMomentForgetBySecondAndByRangeAndATombstoneEndItToo() = runBlocking<Unit> {
        two()
        fx.forget.forgetMoments(listOf(fx.id)); assertEquals(listOf(id2), alive())
        fx.forget.forgetRange(second2 * 1000, second2 * 1000 + 999); assertTrue(alive().isEmpty())
    }

    @Test fun aTombstonedSecondIsNeverAliveEvenIfAStalePartRemains() = runBlocking<Unit> {
        two()
        md.insertTombstones(listOf(com.rokid.context.memory.db.MomentTombstoneEntity(fx.id, 1)))
        assertEquals(listOf(id2), alive()); assertEquals(listOf(id2), md.recentPhotoMoments(20).map { it.momentId })
    }

    @Test fun retentionExpiryOfThePhotoEndsTheMoment() = runBlocking<Unit> {
        two(); fx.service.expirePhotos(listOf("b"))
        assertEquals(listOf(fx.id), alive())
    }

    @Test fun deleteAllEndsEveryMoment() = runBlocking<Unit> {
        two()
        db.wipeDao().wipeMemory()
        assertTrue(alive().isEmpty()); assertTrue(md.recentPhotoMoments(20).isEmpty())
    }

    @Test fun aDayListsItsPhotoMomentsNewestFirstPagedAndNeverTheForgottenOnes() = runBlocking<Unit> {
        two()
        val day1 = MomentForgetFixture.SEC - 10; val day2 = second2 + 10 // id range of "a day" around both
        val lo = com.rokid.context.memory.moment.MomentId.format(day1); val hi = com.rokid.context.memory.moment.MomentId.format(day2)
        assertEquals(listOf(id2, fx.id), md.photoMomentsBetween(lo, hi, 30, 0).map { it.momentId })
        assertEquals("paged", listOf(fx.id), md.photoMomentsBetween(lo, hi, 1, 1).map { it.momentId })
        assertTrue("outside the day", md.photoMomentsBetween(com.rokid.context.memory.moment.MomentId.format(second2 + 50), com.rokid.context.memory.moment.MomentId.format(second2 + 60), 30, 0).isEmpty())
        fx.service.forgetMoment("a")
        assertEquals("a forgotten Moment is not listed", listOf(id2), md.photoMomentsBetween(lo, hi, 30, 0).map { it.momentId })
        md.insertTombstones(listOf(com.rokid.context.memory.db.MomentTombstoneEntity(id2, 1)))
        assertTrue(md.photoMomentsBetween(lo, hi, 30, 0).isEmpty())
    }
}
