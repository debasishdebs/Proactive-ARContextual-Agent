package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.ForgetTombstoneEntity
import com.rokid.context.memory.db.MomentTombstoneEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/** P1: the one-shot repair rebuilds the Moments (and Scenes) of days that have photo seconds with no stored Moment. No model, idempotent, fenced. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentRepairTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val moments = MomentBuilder(db, enabled = { true }, clock = { 5 })
    private val scenes = SceneBuilder(db, enabled = { true }, clock = { 5 })
    private val repair = MomentRepair(db, moments, scenes) { ZoneOffset.UTC }
    private val now = MS + 60_000
    @After fun close() = db.close()

    @Test fun rebuildsMissingMomentsAndScenesThenIsIdempotent() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("b", MS + 1500); fx.memory("m1", "a") // no stored Moment: an older retention pass dropped them
        assertTrue(db.momentDao().allMoments().isEmpty())
        val r = repair.run(now, days = 3)
        assertEquals(1, r.daysRepaired); assertEquals(2, r.missing); assertEquals(2, r.momentsStored); assertTrue(r.scenesStored >= 1)
        assertEquals(2, db.momentDao().allMoments().size)
        val before = fx.snapshot()
        val again = repair.run(now, days = 3)
        assertEquals("nothing is missing the second time", 0, again.daysRepaired); assertEquals(0, again.missing)
        assertEquals(before, fx.snapshot())
    }

    @Test fun neverResurrectsAForgottenSecond() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("t", MS + 3000)
        db.momentDao().insertTombstones(listOf(MomentTombstoneEntity(MomentId.format(SEC + 3), 1)))
        repair.run(now, days = 3)
        assertEquals(listOf(MomentId.format(SEC)), db.momentDao().allMoments().map { it.momentId })
    }

    @Test fun aDayWithNothingMissingIsLeftAlone() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        moments.build(listOf(MomentId.format(SEC)))
        val r = repair.run(now, days = 3)
        assertEquals(0, r.daysRepaired); assertEquals(0, r.momentsStored)
    }

    @Test fun aForgottenPhotoIsNotCounted() = runBlocking<Unit> {
        fx.frame("a", MS + 5); db.forgetDao().insertTombstone(ForgetTombstoneEntity("a", 1))
        assertEquals(0, repair.run(now, days = 3).missing)
    }
}
