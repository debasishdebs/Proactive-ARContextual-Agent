package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.SEC
import com.rokid.context.memory.moment.MomentId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PHOTO scope: the forgotten photo's OWN second is fenced (so it cannot rebuild); neighbouring photos survive. Every other caller of `forgetMoment` is unchanged. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoScopeFenceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentForgetFixture(db)
    private val s3 = db.canvasSlice3Dao()
    @After fun close() = db.close()

    private suspend fun seed() { fx.frame("a", MS + 5); fx.memory("m1", "a"); fx.frame("n1", MS - 1000 * 30); fx.frame("n2", MS + 1000 * 30); fx.memory("m2", "n2") }

    @Test fun thePhotosOwnSecondIsFencedByATombstoneAndTheDurableRange() = runBlocking<Unit> {
        seed()
        fx.service.forgetMoment("a", fenceOwnSecond = true)
        assertEquals(listOf(fx.id), db.momentDao().tombstonedAmong(listOf(fx.id)))
        assertTrue(s3.momentFenced(MS, MS + 999)); assertFalse("the next second is not fenced", s3.momentFenced(MS + 1000, MS + 1000)); assertFalse(s3.momentFenced(MS - 1, MS - 1))
        assertEquals("only the one second", listOf(MS..(MS + 999)), s3.momentForgetRanges())
    }

    @Test fun neighbouringPhotosAndTheirMemoriesSurvive() = runBlocking<Unit> {
        seed()
        fx.service.forgetMoment("a", fenceOwnSecond = true)
        assertEquals(listOf("n1", "n2"), fx.frameIds()); assertEquals(listOf("m2"), fx.memoryIds())
    }

    @Test fun theCaptureTimeOfTheMetadataWinsOverTheRowTime() = runBlocking<Unit> {
        fx.frame("a", MS + 5, capturedAt = MS + 7000 + 3) // arrived late: captured 7 s after the row time
        fx.service.forgetMoment("a", fenceOwnSecond = true)
        assertTrue(MomentId.format(SEC + 7) in db.momentDao().tombstonedAmong(listOf(MomentId.format(SEC + 7))))
        assertTrue(s3.momentFenced(MS + 7000, MS + 7999))
    }

    @Test fun anExpiredPhotoStillKnownByItsCaptureTimeIsFencedToo() = runBlocking<Unit> {
        fx.frame("e", MS + 5, capturedAt = MS + 5); db.frameDao().deleteByIds(listOf("e"))
        fx.service.forgetMoment("e", fenceOwnSecond = true)
        assertTrue(s3.momentFenced(MS, MS + 999))
    }

    @Test fun withoutTheFlagTheForgetIsExactlyTodays() = runBlocking<Unit> {
        seed()
        fx.service.forgetMoment("a")
        assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(s3.momentForgetRanges().isEmpty())
    }

    @Test fun aPhotoThatDoesNotExistFencesNothing() = runBlocking<Unit> {
        fx.service.forgetMoment("ghost", fenceOwnSecond = true)
        assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(s3.momentForgetRanges().isEmpty())
    }

    @Test fun theFenceIsInTheSameTransactionAsTheDelete() = runBlocking<Unit> {
        seed()
        s3.testFaultAfterFenceMove = { throw IllegalStateException("boom") }
        runCatching { fx.service.forgetMoment("a", fenceOwnSecond = true) }
        s3.testFaultAfterFenceMove = null
        assertEquals(listOf("a", "n1", "n2"), fx.frameIds()); assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(s3.momentForgetRanges().isEmpty())
    }
}
