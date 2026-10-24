package com.rokid.context.canvas.moment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Scene rebuild cadence: a stored "times a day" value with a default (no settings screen yet), run by the Moment cadence worker. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SceneCadenceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val db get() = MemoryGraph.get(ctx).db
    private val hour = 3_600_000L

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); MomentTriggers.resetForTests(); SceneTriggers.resetForTests()
    }
    @After fun tearDown() { db.close(); MemoryGraph.resetForTests(null); MomentTriggers.resetForTests(); SceneTriggers.resetForTests() }

    private fun cadence() = SceneCadence(db, SceneBuilder(db, enabled = { true }))

    @Test fun theDefaultIsEveryWorkerTickAndAStoredValueIsClampedAndKept() = runBlocking<Unit> {
        val c = cadence()
        assertEquals(SceneCadence.DEFAULT_PER_DAY, c.perDay())
        c.setPerDay(4); assertEquals(4, cadence().perDay())
        c.setPerDay(0); assertEquals(1, c.perDay()); c.setPerDay(1000); assertEquals(SceneCadence.MAX_PER_DAY, c.perDay())
    }

    @Test fun itRunsTheFirstTimeThenOnlyWhenTheIntervalHasPassed() = runBlocking<Unit> {
        val c = cadence(); c.setPerDay(24) // every hour
        val t0 = 1_761_000_000_000L
        assertNotNull(c.runIfDue(t0))
        assertNull(c.runIfDue(t0 + 30 * 60_000L))
        assertNull(c.runIfDue(t0 + hour - 5 * 60_000L))
        assertNotNull(c.runIfDue(t0 + hour - 60_000L)) // a little early still counts (worker jitter)
        assertNull(c.runIfDue(t0 + hour))
    }

    @Test fun aClockThatWentBackwardsRunsAgainInsteadOfWaitingForever() = runBlocking<Unit> {
        val c = cadence(); c.setPerDay(1)
        assertNotNull(c.runIfDue(1_761_000_000_000L)); assertNotNull(c.runIfDue(1_760_000_000_000L))
    }

    @Test fun theCadenceWorkerBuildsTheScenesOfTheRecentHours() = runBlocking<Unit> {
        val before = (System.currentTimeMillis() / hour) * hour - 120_000L // 2 minutes before this hour began: always in the previous hour, which the cadence rebuilds
        val fx = MomentFixture(db); fx.frame("w1", before); fx.frame("w2", before + 1_000)
        TestListenableWorkerBuilder<MomentCadenceWorker>(ctx).build().doWork()
        assertEquals(2, db.momentDao().allMoments().size)
        assertEquals(1, db.sceneDao().sceneCount())
    }
}
