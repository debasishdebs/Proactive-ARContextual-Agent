package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.MomentForgetFixture.Companion.MS
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PHOTO scope is exactly today's Forget (review fix, user ruling: only that photo goes): NO fence of the photo's second, siblings and later captures in that second stay possible; the frame tombstone keeps the photo itself from returning. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoScopeNoFenceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentForgetFixture(db)
    private val engine = ScopedForget(db, fx.service) { 1_000 }
    private val s3 = db.canvasSlice3Dao()
    @After fun close() = db.close()

    @Test fun aPhotoForgetWritesNoMomentTombstoneAndNoRangeFence() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a")
        engine.forget(engine.plan(ForgetTarget.Photo("a"), ForgetScope.PHOTO))
        assertEquals(0, db.momentDao().tombstoneCount()); assertTrue(s3.momentForgetRanges().isEmpty()); assertFalse(s3.momentFenced(MS, MS + 999))
    }

    @Test fun aSiblingPhotoOfTheSameSecondSurvivesAndALaterCaptureOfThatSecondIsNotRefused() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("sib", MS + 600)
        engine.forget(engine.plan(ForgetTarget.Photo("a"), ForgetScope.PHOTO))
        assertEquals(listOf("sib"), fx.frameIds()); assertFalse("a mark photo or sibling arriving later is not fenced", s3.momentFenced(MS + 700, MS + 700))
    }

    @Test fun theForgottenPhotoItselfIsStillTombstonedSoItCannotReturn() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        engine.forget(engine.plan(ForgetTarget.Photo("a"), ForgetScope.PHOTO))
        assertTrue("a" in db.forgetDao().tombstoned(listOf("a")))
    }
}
