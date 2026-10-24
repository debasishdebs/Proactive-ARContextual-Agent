package com.rokid.context.canvas.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.moment.MomentFixture.Companion.MS
import com.rokid.context.canvas.moment.MomentFixture.Companion.SEC
import com.rokid.context.memory.ProposalRepository
import com.rokid.context.memory.db.CleanupTarget
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.forget.ForgetService
import com.rokid.context.memory.forget.MomentForget
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The activation switch: ON by default (pinned), and a kill switch only: OFF writes nothing while Forget and Delete all still behave. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MomentStoreSwitchTest {
    private val db = DatabaseFactory.inMemory(ApplicationProvider.getApplicationContext())
    private val fx = MomentFixture(db)
    private val id = MomentId.format(SEC)
    private val forget = MomentForget(db, ForgetService(db, ProposalRepository(db.proposalDao(), db.actionDao(), db)) { 1_000 }) { 1_000 }
    @After fun close() = db.close()

    @Test fun theDefaultIsOn() {
        assertTrue(MomentStoreSwitch.DEFAULT_ENABLED)
        runBlocking { fx.frame("a", MS + 5); assertEquals(1, MomentBuilder(db).build(listOf(id)).stored) } // a default-constructed builder writes
    }

    @Test fun switchedOffNothingIsWritten() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a")
        val off = MomentBuilder(db, enabled = { false })
        assertEquals(MomentBuilder.Report.NONE, off.build(listOf(id))); assertEquals(MomentBuilder.Report.NONE, off.rebuildWindow(MS, MS + 9999))
        assertTrue(db.momentDao().allMoments().isEmpty()); assertEquals(0, db.momentDao().tombstoneCount())
    }

    @Test fun switchedOffForgetStillCascadesFencesAndQueuesCleanup() = runBlocking<Unit> {
        fx.frame("a", MS + 5); fx.memory("m1", "a")
        MomentBuilder(db, enabled = { true }).build(listOf(id)); val off = MomentBuilder(db, enabled = { false })
        forget.forgetMoments(listOf(id))
        assertTrue(db.frameDao().all().isEmpty()); assertTrue(db.momentDao().allMoments().isEmpty()); assertEquals(1, db.momentDao().tombstoneCount())
        assertTrue(db.momentDao().pendingOutbox().any { it.target == CleanupTarget.PHONE_FILE })
        assertEquals(MomentBuilder.Report.NONE, off.build(listOf(id)))
    }

    @Test fun switchedOffDeleteAllStillWipesTheProjectionAndKeepsUnackedCleanup() = runBlocking<Unit> {
        fx.frame("a", MS + 5)
        MomentBuilder(db, enabled = { true }).build(listOf(id)); forget.forgetMoments(listOf(id)); MomentBuilder(db, enabled = { true }).build(listOf(MomentId.format(SEC + 1)))
        val pending = db.momentDao().pendingOutbox().size
        db.wipeDao().wipeMemory(9)
        assertTrue(db.momentDao().allMoments().isEmpty()); assertEquals(0, db.momentDao().tombstoneCount()); assertEquals(pending + 1, db.momentDao().pendingOutbox().size)
        assertEquals(MomentBuilder.Report.NONE, MomentBuilder(db, enabled = { false }).build(listOf(id)))
    }
}
