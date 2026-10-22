package com.rokid.context.memory.forget

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Wave 3 Task 3 (S1): a `frame:<id>` photo cite is live exactly while the moment's `frame_meta` row exists. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ForgetFenceTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())

    @After fun close() = db.close()

    private suspend fun meta(id: String) =
        db.frameMetaDao().upsert(FrameMetaEntity(id, null, null, "STILL", false, null, null, null, 640, 0.3f, null, null, null, "s1", 1))

    @Test fun frameCiteLiveWhileMetaExists() = runBlocking {
        meta("f1")
        db.memoryDao().insert(MemoryEntity("m1", null, "t", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "t"))
        assertTrue(ForgetFence.allLive(db, setOf("${PHOTO_CITE_PREFIX}f1")))
        assertTrue("memory ids still checked as before", ForgetFence.allLive(db, setOf("m1", "${PHOTO_CITE_PREFIX}f1")))
        assertFalse(ForgetFence.allLive(db, setOf("m1", "missing")))
    }

    @Test fun frameCiteDeadAfterForget() = runBlocking {
        meta("f1")
        val service = ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }
        service.forgetMoment("f1")
        assertFalse(ForgetFence.allLive(db, setOf("${PHOTO_CITE_PREFIX}f1")))
        assertFalse("a cite to a frame that never existed is dead", ForgetFence.allLive(db, setOf("${PHOTO_CITE_PREFIX}ghost")))
    }
}
