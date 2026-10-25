package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.db.DeletionEpoch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The photo Forget radius (default 10 s, a user setting): the window around a forgotten photo within which marks go with it, read at Forget time through ForgetRadius.ms(). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetRadiusTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }
    private val epoch = DeletionEpoch("i", 1, 0)
    private val fence = DaoFence(0, epoch, { 0 }, { epoch })
    private val photoAt = 100_000L
    @After fun close() { ForgetRadius.provider = { ForgetRadius.DEFAULT_MS }; db.close() }

    /** A photo at [photoAt] and marks 3, 7, 12 and 20 s before it. Returns the ids of the marks left after forgetting the photo. */
    private fun marksLeftAfterForget(radiusMs: Long?): Set<String> = runBlocking {
        if (radiusMs != null) ForgetRadius.provider = { radiusMs }
        db.frameDao().insertIgnore(FrameEntity("f1", photoAt, "/f1.jpg", "rokid_glasses"))
        db.memoryDao().insert(MemoryEntity("m1", "f1", "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))
        for ((id, secBefore) in listOf("m3" to 3, "m7" to 7, "m12" to 12, "m20" to 20)) {
            db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity(id, "MOMENT", "tap", "X", photoAt - secBefore * 1_000L, createdAt = 1), fence)
        }
        service.forgetMoment("f1")
        db.canvasSlice3Dao().labels().map { it.labelId }.toSet()
    }

    @Test fun theDefaultIs10SecondsAndIsTheOldConstant() {
        assertEquals(ForgetService.PHOTO_LINK_MS, ForgetRadius.ms()); assertEquals(10_000L, ForgetRadius.ms())
        assertEquals(setOf("m12", "m20"), marksLeftAfterForget(null))
    }

    @Test fun aSmallerRadiusRemovesLess() = assertEquals(setOf("m7", "m12", "m20"), marksLeftAfterForget(5_000))
    @Test fun aLargerRadiusRemovesMore() = assertEquals(setOf("m20"), marksLeftAfterForget(15_000))
    @Test fun the30SecondRadiusRemovesAll() = assertEquals(emptySet<String>(), marksLeftAfterForget(30_000))
    @Test fun aValueThatIsNoPresetFallsBackToTheDefault() {
        ForgetRadius.provider = { 1L }; assertEquals(10_000L, ForgetRadius.ms())
        ForgetRadius.provider = { 600_000L }; assertEquals(10_000L, ForgetRadius.ms())
        ForgetRadius.provider = { -5L }; assertEquals(10_000L, ForgetRadius.ms())
    }
}
