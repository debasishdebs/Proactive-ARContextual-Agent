package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Forget racing a build, both orders, on the real Room transaction (no fake): a rebuild can never resurrect a forgotten second. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentForgetRacingBuildTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val builder = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    private val lite = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao()))
    @After fun close() = db.close()

    private fun id(i: Int) = MomentId.format(SEC + i)
    private suspend fun seed(i: Int) { fx.frame("f$i", MS + i * 1000L + 5); fx.memory("m$i", "f$i"); fx.mark("L$i", MS + i * 1000L + 500) }
    private suspend fun gone(i: Int): Boolean = db.momentDao().moment(id(i)) == null && db.momentDao().parts(id(i)).isEmpty() &&
        db.frameDao().byIds(listOf("f$i")).isEmpty() && db.canvasSlice3Dao().label("L$i") == null

    @Test fun buildThenForgetLeavesNothing() = runBlocking<Unit> {
        seed(0); builder.build(listOf(id(0))); assertTrue(db.momentDao().moment(id(0)) != null)
        forget.forgetMoments(listOf(id(0)))
        assertTrue(gone(0))
    }

    @Test fun forgetThenBuildIsRefusedAndAStaleStoredRowCannotComeBack() = runBlocking<Unit> {
        seed(0); forget.forgetMoments(listOf(id(0)))
        fx.frame("late", MS + 7) // a source that slipped in after the Forget (a defective admission): the fence still refuses the build
        val r = builder.build(listOf(id(0)))
        assertEquals(1, r.refused); assertEquals(0, r.stored); assertNull(db.momentDao().moment(id(0)))
        assertTrue("Moment-lite's derived path is untouched and reads the stores", lite.resolve(listOf(id(0))).isNotEmpty())
    }

    @Test fun concurrentBuildAndForgetEndEmptyWhateverTheInterleaving() = runBlocking<Unit> {
        repeat(25) { i ->
            seed(i)
            withContext(Dispatchers.Default) { listOf(async { builder.build(listOf(id(i))) }, async { forget.forgetMoments(listOf(id(i))) }, async { builder.build(listOf(id(i))) }).awaitAll() }
            assertTrue("second $i", gone(i))
        }
    }

    @Test fun concurrentRangeForgetAndWindowRebuildEndEmpty() = runBlocking<Unit> {
        (0 until 6).forEach { seed(it) }
        withContext(Dispatchers.Default) { listOf(async { builder.rebuildWindow(MS, MS + 6000) }, async { forget.forgetRange(MS, MS + 5999) }, async { builder.rebuildWindow(MS, MS + 6000) }).awaitAll() }
        (0 until 6).forEach { assertTrue("second $it", gone(it)) }
    }

    @Test fun withTheSwitchOffForgetStillRemovesAnExistingProjection() = runBlocking<Unit> {
        seed(0); builder.build(listOf(id(0)))
        val off = MomentBuilder(db, enabled = { false })
        assertEquals(MomentBuilder.Report.NONE, off.build(listOf(id(0))))
        forget.forgetMoments(listOf(id(0)))
        assertTrue(gone(0))
    }
}
