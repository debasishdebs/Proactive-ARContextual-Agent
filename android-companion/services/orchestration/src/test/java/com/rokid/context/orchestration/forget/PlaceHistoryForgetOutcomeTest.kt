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
import com.rokid.context.memory.db.PlaceCardLogEntity
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryExport
import com.rokid.context.work.frames.place.PlaceGraph
import com.rokid.context.work.frames.place.PlaceGuideSettings
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

/**
 * v18 (CH 8, r4 P1-5): the place card history lives in `context.db`, so a Forget or Delete all deletes its rows INSIDE its own transaction and the history can never make either of them pending. The pin cascade
 * of a Forget (a pin goes with its Moment) deletes the pin's rows through [PlaceGraph.savePins] before the pin store is written.
 */
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
    private val log get() = graph.db.placeCardLogDao()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        DataOpGate.shared = DataOpGate(waitMs = 200); MemoryExport.productionBuild = { false } // only the history could fail a Forget here
        prefs.edit().clear().commit()
        ctx.getSharedPreferences(com.rokid.context.memory.place.UserPinStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        PlaceGraph.installForTests(pack); PlaceGraph.registerListeners()
    }

    @After fun tearDown() {
        MemoryExport.productionBuild = MemoryExport.defaultProductionBuild; DataOpGate.shared = original
        PlaceGraph.installForTests(null); pdb.close(); prefs.edit().clear().commit(); graph.db.close(); MemoryGraph.resetForTests(null)
    }

    private suspend fun world() {
        val f = File(ctx.filesDir, "rokid_archive/a1.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity("a1", ms + 5, f.absolutePath, "rokid_glasses", phash = "pa1"))
        graph.db.frameMetaDao().upsert(FrameMetaEntity("a1", null, null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = ms + 5))
        graph.db.memoryDao().insert(MemoryEntity("ma1", "a1", "text", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, ms + 5, false, "text"))
        val mid = MomentId.format(sec)
        graph.db.momentDao().replace(MomentEntity(mid, 1, 1, "h", 1, MomentType.FRAME), listOf(MomentPartEntity(mid, MomentPartKind.FRAME, "a1", "r", 1)))
        assertTrue(PlaceGraph.savePins(ctx, listOf(UserPlace(pinId, "Pin", PlaceKinds.MONUMENT, 7.9, 80.7, momentId = mid))))
        log.put(PlaceCardLogEntity("r1", 1L, pinId, "T", "PIN", null, null, null, null, CardDelivery.PENDING))
        log.put(PlaceCardLogEntity("r2", ms + 5, "wd:Q1", "T", "PHOTO", null, "a1", null, null, CardDelivery.SENT))
    }

    @Test fun anOrdinaryForgetThatTakesAPinWithItsMomentIsDoneAndItsHistoryRowsAreGone() = runBlocking<Unit> {
        world()
        assertEquals("history is never pending", ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.PHOTO, confirmedPins = setOf(pinId)))
        assertEquals("the pin's own row and the photo's row are both gone", 0, log.cardLogCount())
        assertTrue(PlaceGraph.pins(ctx).pins().none { it.id == pinId })
    }

    @Test fun aForgetThatKeepsThePinOnlyDeletesTheRowOfThePhoto() = runBlocking<Unit> {
        world()
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetScopedOutcome(ctx, ForgetTarget.Photo("a1"), ForgetScope.PHOTO))
        assertEquals(listOf("r1"), log.recentCardLog(10).map { it.id })
    }

    @Test fun deleteAllDeletesTheHistoryAndIsDone() = runBlocking<Unit> {
        world()
        assertEquals(ForgetActions.Outcome.DONE, com.rokid.context.orchestration.MemoryWipe.deleteAllOutcome(ctx))
        assertEquals(0, log.cardLogCount())
    }
}
