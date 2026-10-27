package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MomentTombstoneEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** v0.9.7 review fix B (R12/R13): the list API, run resolution and the Activity honour Moment tombstones and the Moment Forget range fence: a purged Moment never reappears from the sources that survive. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TombstonedReadersTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val production = MomentResolver(DaoMomentSource(db.canvasDao(), db.canvasSlice3Dao(), db.momentDao())) // as CanvasBackendImpl builds it
    @After fun close() = db.close()

    private fun id(i: Int) = MomentId.format(SEC + i)
    private suspend fun seed(i: Int, photo: Boolean = true) { if (photo) { fx.frame("f$i", MS + i * 1000L + 5); fx.memory("m$i", "f$i") }; fx.mark("L$i", MS + i * 1000L + 500) }
    private suspend fun tombstone(i: Int) = db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(id(i), 1)))
    private suspend fun listing() = production.window(MS, MS + 10_000, 50).map { it.id }

    @Test fun theListingSkipsATombstonedSecondButKeepsItsNeighbour() = runBlocking<Unit> {
        seed(0); seed(1); tombstone(0)
        assertEquals(listOf(id(1)), listing())
    }

    @Test fun aRunResolutionOfATombstonedSecondFindsNothing() = runBlocking<Unit> {
        seed(0); seed(1); tombstone(0)
        assertEquals(listOf(id(1)), production.resolve(listOf(id(0), id(1))).map { it.id })
    }

    @Test fun aBareMarkInATombstonedSecondIsNotTurnedIntoAMomentOfAnyKind() = runBlocking<Unit> {
        seed(0, photo = false); tombstone(0)
        assertTrue(listing().isEmpty()); assertTrue(production.resolve(listOf(id(0))).isEmpty())
        val row = db.momentDao().marksIn(MS, MS + 999).first().single()
        assertEquals("the Activity still lists the mark", "L0", row.labelId); assertNull("with no Moment", row.momentId)
    }

    @Test fun theMomentForgetRangeFenceHidesSecondsToo() = runBlocking<Unit> {
        seed(0); seed(1); db.canvasSlice3Dao().addMomentForgetRange(MS, MS + 999)
        assertEquals(listOf(id(1)), listing()); assertEquals(listOf(id(1)), production.resolve(listOf(id(0), id(1))).map { it.id })
    }

    @Test fun withoutAFenceTheSameSecondsAreListed() = runBlocking<Unit> {
        seed(0); seed(1)
        assertEquals(listOf(id(0), id(1)), listing())
    }
}
