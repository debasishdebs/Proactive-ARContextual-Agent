package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The projection is rebuildable: build, wipe it, rebuild, and it is equal to what a fresh recompute stores. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentRebuildEqualityTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val builder = MomentBuilder(db, enabled = { true }, clock = { 5 })
    @After fun close() = db.close()

    private suspend fun seed() {
        fx.frame("a", MS + 5); fx.frame("b", MS + 1500); fx.frame("c", MS + 9000)
        fx.memory("m1", "a"); fx.memory("s1", "b", derived = true); fx.mark("L1", MS + 500); fx.mark("L9", MS + 9500); fx.ask("k1", "c")
    }

    @Test fun buildWipeRebuildIsEqual() = runBlocking<Unit> {
        seed()
        builder.rebuildWindow(MS, MS + 20_000)
        val first = fx.snapshot()
        assertTrue(first.isNotEmpty())
        db.momentDao().remove(db.momentDao().allMoments().map { it.momentId })
        assertTrue(fx.snapshot().isEmpty())
        builder.rebuildWindow(MS, MS + 20_000)
        assertEquals(first, fx.snapshot())
    }

    @Test fun rebuildingTwiceIsIdempotent() = runBlocking<Unit> {
        seed()
        builder.rebuildWindow(MS, MS + 20_000); val first = fx.snapshot()
        builder.rebuildWindow(MS, MS + 20_000)
        assertEquals(first, fx.snapshot())
    }

    @Test fun aChangedSourceChangesTheStoredHashAndARebuildCatchesUp() = runBlocking<Unit> {
        seed(); builder.rebuildWindow(MS, MS + 20_000)
        val before = db.momentDao().moment(MomentId.format(SEC))!!.contentHash
        fx.memory("m2", "a")
        builder.rebuildWindow(MS, MS + 20_000)
        val after = db.momentDao().moment(MomentId.format(SEC))!!
        assertTrue(before != after.contentHash); assertEquals(MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao())).resolve(listOf(after.momentId)).single().contentHash, after.contentHash)
    }

    @Test fun aWindowRebuildDropsStoredMomentsThatNoLongerQualify() = runBlocking<Unit> {
        seed(); builder.rebuildWindow(MS, MS + 20_000)
        db.forgetDao().insertTombstone(com.rokid.context.memory.db.ForgetTombstoneEntity("c", 1)) // the photo of second +9 is forgotten
        builder.rebuildWindow(MS, MS + 20_000)
        assertEquals(setOf(MomentId.format(SEC), MomentId.format(SEC + 1)), db.momentDao().allMoments().map { it.momentId }.toSet())
    }
}
