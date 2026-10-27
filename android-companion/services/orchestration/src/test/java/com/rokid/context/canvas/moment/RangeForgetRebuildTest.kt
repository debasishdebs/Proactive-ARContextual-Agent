package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.CanvasSlice3Forget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 review fix A (R7): the canvas time-range Forget deletes every Moment of the range with its photos and memories and fences the seconds, so a rebuild AFTER it brings nothing back. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RangeForgetRebuildTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val builder = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val forget = CanvasSlice3Forget(db) { 1_000 }
    private val lite = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao()))
    @After fun close() = db.close()

    private fun id(i: Int) = MomentId.format(SEC + i)
    private suspend fun seed(i: Int) { fx.frame("f$i", MS + i * 1000L + 5); fx.memory("m$i", "f$i"); fx.mark("L$i", MS + i * 1000L + 500) }

    @Test fun aRebuildAfterARangeForgetBringsNothingBack() = runBlocking<Unit> {
        (0 until 3).forEach { seed(it) }
        builder.rebuildWindow(MS, MS + 3000)
        assertEquals(3, db.momentDao().allMoments().size)
        forget.forgetRange(MS, MS + 2999)
        assertTrue("no photo survives", db.frameDao().all().isEmpty())
        assertTrue("no memory survives", db.forgetDao().memories(listOf("m0", "m1", "m2")).all { it.reviewState == com.rokid.context.memory.db.ReviewState.INVALIDATED })
        val r = builder.rebuildWindow(MS, MS + 3000)
        assertEquals(0, r.stored)
        (0 until 3).forEach { assertEquals(null, db.momentDao().moment(id(it))) }
        assertTrue(db.momentDao().allMoments().isEmpty())
        assertTrue(db.canvasSlice3Dao().labels().isEmpty())
        assertTrue("the resolver finds nothing in the range", lite.resolve((0 until 3).map { id(it) }).isEmpty())
    }

    @Test fun theSecondsAreFencedSoALateSourceIsRefused() = runBlocking<Unit> {
        seed(0); builder.build(listOf(id(0)))
        forget.forgetRange(MS, MS + 999)
        fx.frame("late", MS + 7)
        assertEquals(1, builder.build(listOf(id(0))).refused)
        assertEquals(null, db.momentDao().moment(id(0)))
    }

    @Test fun aNeighbourOutsideTheRangeIsKept() = runBlocking<Unit> {
        seed(0); seed(30); builder.rebuildWindow(MS, MS + 31_000) // 30 s away: outside the 10 s photo link radius
        forget.forgetRange(MS, MS + 999)
        assertEquals(listOf(id(30)), db.momentDao().allMoments().map { it.momentId })
    }
}
