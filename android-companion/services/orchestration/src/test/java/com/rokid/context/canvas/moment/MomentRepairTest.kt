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
        moments.build(listOf(MomentId.format(SEC))); scenes.rebuildWindow(MS, MS + 999) // the Moment and its Scene both exist
        val r = repair.run(now, days = 3)
        assertEquals(0, r.daysRepaired); assertEquals(0, r.momentsStored); assertEquals(0, r.scenesMissing)
    }

    @Test fun aForgottenPhotoIsNotCounted() = runBlocking<Unit> {
        fx.frame("a", MS + 5); db.forgetDao().insertTombstone(ForgetTombstoneEntity("a", 1))
        assertEquals(0, repair.run(now, days = 3).missing)
    }

    /** Retention r1 finding 6: a Moment that exists keeps its stored content; only the missing second is built. */
    @Test fun repairNeverOverwritesAMomentThatIsRetained() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a")
        moments.build(listOf(MomentId.format(SEC)))
        val kept = db.momentDao().moment(MomentId.format(SEC))!!.copy(text = "RETAINED TEXT, its source fact expired")
        db.momentDao().replace(kept, db.momentDao().parts(kept.momentId))
        fx.frame("b", MS + 3000) // the missing second
        val r = repair.run(now, days = 3)
        assertEquals(1, r.missing); assertEquals(1, r.momentsStored)
        assertEquals("RETAINED TEXT, its source fact expired", db.momentDao().moment(MomentId.format(SEC))!!.text)
        assertTrue(db.momentDao().moment(MomentId.format(SEC + 3)) != null)
    }

    /** Finding 7: a Scene gap is repaired on its own (Moments all present), and a run whose Scene pass failed is resumed by the next one. */
    @Test fun sceneGapsAreRepairedIndependentlyAndAFailedScenePassIsResumed() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.frame("b", MS + 2000)
        var failing = true
        val flaky = SceneBuilder(db, enabled = { if (failing) throw IllegalStateException("scene pass failed") else true }, clock = { 5 })
        val r1 = runCatching { MomentRepair(db, moments, flaky) { ZoneOffset.UTC }.run(now, days = 3) }
        assertTrue("the Scene pass failed after the Moment pass committed", r1.isFailure)
        assertEquals(2, db.momentDao().allMoments().size); assertEquals(0, db.sceneDao().sceneCount())
        failing = false
        val r2 = MomentRepair(db, moments, flaky) { ZoneOffset.UTC }.run(now, days = 3)
        assertEquals("no Moment gap any more", 0, r2.missing); assertEquals(0, r2.momentsStored)
        assertEquals(2, r2.scenesMissing); assertTrue(r2.scenesStored >= 1); assertTrue(db.sceneDao().sceneCount() >= 1)
        assertEquals("then nothing is left", 0, repair.run(now, days = 3).scenesMissing)
    }

    /** Finding 8: the work is chunked (builds of a few seconds, Scenes one clock hour at a time) and the result is the same. */
    @Test fun chunkedRepairBuildsEverySecondAndEveryHour() = runBlocking<Unit> {
        for (i in 0 until 5) fx.frame("f$i", MS + i * 1000L + 5)
        fx.frame("h2", MS + 3_600_000L + 5) // the next clock hour
        val small = MomentRepair(db, moments, scenes, chunkSeconds = 2) { ZoneOffset.UTC }
        val r = small.run(now + 2 * 3_600_000L, days = 3)
        assertEquals(6, r.missing); assertEquals(6, r.momentsStored); assertEquals(6, db.momentDao().allMoments().size)
        assertEquals(6, r.scenesMissing); assertTrue("one Scene pass per hour", r.scenesStored >= 2)
        assertEquals(0, db.momentDao().momentIdsWithoutScene(MomentId.format(SEC - 10), MomentId.format(SEC + 10_000)).size)
    }
}
