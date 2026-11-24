package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.FrameMetaEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.ForgetScope
import com.rokid.context.memory.forget.ForgetTarget
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.place.CardDelivery
import com.rokid.context.memory.place.PlaceCardLogEntity
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDao
import com.rokid.context.memory.place.PlacesDatabase
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryExport
import com.rokid.context.work.frames.place.PlaceCardHistory
import com.rokid.context.work.frames.place.PlaceGraph
import com.rokid.context.work.frames.place.PlaceGuideSettings
import com.rokid.context.work.frames.place.PrefsHistoryOwed
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Codex place r3 P1-1 / P2-3: an ordinary Forget re-checks its place-history obligations AFTER the pin cascade, and only a deletion THIS Forget attempted makes it report cleanup pending. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceHistoryForgetOutcomeTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val original = DataOpGate.shared
    private val sec = 1_761_000_000L
    private val ms = sec * 1000
    private val pinId = "user:00000000-0000-0000-0000-000000000001"
    private val pdb = PlacesDatabase.inMemory(ctx)
    private val pack = PlacePack(pdb, null)
    private val prefs get() = ctx.getSharedPreferences(PlaceGuideSettings.PREFS, Context.MODE_PRIVATE)

    /** Fails the history deletions while the flags are set. */
    private class Flaky(private val real: PlacesDao) : PlacesDao by real {
        @Volatile var failPin = false
        @Volatile var failFrames = false
        override suspend fun deleteCardLogForPin(pinId: String): Int { if (failPin) throw IllegalStateException("db"); return real.deleteCardLogForPin(pinId) }
        override suspend fun deleteCardLogForFrames(frameIds: List<String>): Int { if (failFrames) throw IllegalStateException("db"); return real.deleteCardLogForFrames(frameIds) }
    }
    private lateinit var dao: Flaky

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        DataOpGate.shared = DataOpGate(waitMs = 200); MemoryExport.productionBuild = { false } // only the history can fail a Forget here
        prefs.edit().clear().commit(); PrefsHistoryOwed.clearMemory()
        ctx.getSharedPreferences(com.rokid.context.memory.place.UserPinStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        dao = Flaky(pack.cardLogDao)
        PlaceGraph.installForTests(pack); PlaceGraph.attach(ctx)
        PlaceGraph.installHistoryForTests(PlaceCardHistory(dao, owed = PrefsHistoryOwed(prefs), pinActive = { id -> PlaceGraph.pins(ctx).pins().any { it.id == id } }))
        PlaceGraph.registerListeners()
    }

    @After fun tearDown() {
        MemoryExport.productionBuild = MemoryExport.defaultProductionBuild; DataOpGate.shared = original
        PlaceGraph.installForTests(null); pdb.close(); prefs.edit().clear().commit(); PrefsHistoryOwed.clearMemory(); graph.db.close(); MemoryGraph.resetForTests(null)
    }

    private suspend fun world() {
        val f = File(ctx.filesDir, "rokid_archive/a1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("a1", ms + 5, f.absolutePath, "rokid_glasses", phash = "pa1"))
        graph.db.frameMetaDao().upsert(FrameMetaEntity("a1", null, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = ms + 5))
        graph.db.memoryDao().insert(MemoryEntity("ma1", "a1", "text", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, ms + 5, false, "text"))
        val mid = MomentId.format(sec)
        graph.db.momentDao().replace(MomentEntity(mid, 1, 1, "h", 1, MomentType.FRAME), listOf(MomentPartEntity(mid, MomentPartKind.FRAME, "a1", "r", 1)))
        assertTrue(PlaceGraph.savePins(ctx, listOf(UserPlace(pinId, "Pin", PlaceKinds.MONUMENT, 7.9, 80.7, momentId = mid))))
        pack.cardLogDao.putCardLog(PlaceCardLogEntity("r1", 1L, pinId, "T", "PIN", null, null, null, null, CardDelivery.PENDING))
    }

    @Test fun anOrdinaryForgetWhosePinCascadeCannotDeleteItsHistoryRowsIsNotDone() = runBlocking<Unit> {
        world(); dao.failPin = true
        val o = ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.PHOTO, confirmedPins = setOf(pinId))
        assertEquals("the pin went with its Moment; its history row may remain", ForgetActions.Outcome.CACHE_CLEANUP_FAILED, o)
        assertTrue(PlaceGraph.historyOwed(ctx)); assertEquals(1, pack.cardLogDao.cardLogCount())
        dao.failPin = false; assertTrue(PlaceGraph.settleHistory(ctx)); assertEquals(0, pack.cardLogDao.cardLogCount())
    }

    @Test fun theSameForgetIsDoneWhenTheCascadeHistoryDeletionWorks() = runBlocking<Unit> {
        world()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.PHOTO, confirmedPins = setOf(pinId)))
        assertEquals(0, pack.cardLogDao.cardLogCount()); assertFalse(PlaceGraph.historyOwed(ctx))
    }

    @Test fun anUnrelatedForgetIsNotFailedForeverByAnEarlierPermanentlyFailingToken() = runBlocking<Unit> {
        world(); dao.failFrames = true
        // an earlier operation left a frame deletion owed that never succeeds
        try { PlaceGraph.history(ctx).onFramesForgotten(listOf("old")) } catch (_: com.rokid.context.work.frames.place.HistoryDeletionPending) { }
        assertTrue(PlaceGraph.historyOwed(ctx))
        graph.db.memoryDao().insert(MemoryEntity("mx", null, "other", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, ms + 9, false, "other"))
        assertEquals("a fact Forget does not touch that token", ForgetActions.Outcome.DONE, ForgetActions.forgetFactOutcome(ctx, "mx"))
        assertTrue("still owed: startup retries it", PlaceGraph.historyOwed(ctx))
        // the Forget whose own deletion fails does report it
        assertEquals(ForgetActions.Outcome.CACHE_CLEANUP_FAILED, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.PHOTO))
        dao.failFrames = false; assertTrue(PlaceGraph.settleHistory(ctx))
    }
}
