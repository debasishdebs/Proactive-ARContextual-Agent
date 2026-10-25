package com.rokid.context.canvas.moment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.moment.NoPhotoReason
import com.rokid.context.orchestration.forget.ForgetStartup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The purge is wired to the start path every launch runs (`MainActivity` -> `ForgetStartup.run`), and it runs once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InvalidMomentPurgeStartupTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); MomentTriggers.resetForTests() }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null); MomentTriggers.resetForTests() }

    @Test fun theFirstStartAfterTheUpgradeDeletesInvalidMomentsOnceAndTheNextStartLeavesNewOnesAlone() = runBlocking<Unit> {
        val md = db.momentDao()
        val plain = MomentId.format(MomentFixture.SEC)
        md.replace(MomentEntity(plain, 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN), emptyList())
        ForgetStartup.run(ctx) {}
        assertEquals(emptyList<String>(), md.allMoments().map { it.momentId })
        assertNotNull(db.canvasSlice3Dao().meta(InvalidMomentPurge.KEY))
        val later = MomentId.format(MomentFixture.SEC + 9)
        md.replace(MomentEntity(later, 1, 1, "h", 1, MomentType.MARK, NoPhotoReason.UNKNOWN), emptyList())
        ForgetStartup.run(ctx) {}
        assertEquals(listOf(later), md.allMoments().map { it.momentId })
    }
}
