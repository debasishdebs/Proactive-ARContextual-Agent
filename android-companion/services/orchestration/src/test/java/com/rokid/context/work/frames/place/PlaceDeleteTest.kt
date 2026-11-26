package com.rokid.context.work.frames.place

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.MomentEntity
import com.rokid.context.memory.db.MomentPartEntity
import com.rokid.context.memory.db.MomentPartKind
import com.rokid.context.memory.db.PlaceLinkEntity
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.moment.MomentId
import com.rokid.context.memory.moment.MomentType
import com.rokid.context.memory.place.PlaceKinds
import com.rokid.context.memory.place.PlacePack
import com.rokid.context.memory.place.PlacesDatabase
import com.rokid.context.memory.place.UserPlace
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.orchestration.MemoryExport
import com.rokid.context.orchestration.forget.ForgetActions
import com.rokid.context.orchestration.forget.ForgetActions.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Place object S3, mode (b) "the place and its N Moments" over the real multi-select Forget and an in-memory database. Moments: A (the pin's reference: photo, memory, mark), B (a card link of the pin, and the reference
 * Moment of a SECOND pin), C (unrelated). Mode (b) forgets A and B, names and deletes the second pin, removes the links and the pin; C stays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaceDeleteTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val original = DataOpGate.shared
    private val secA = 1_761_000_000L; private val secB = secA + 100; private val secC = secA + 200
    private val idA = MomentId.format(secA); private val idB = MomentId.format(secB); private val idC = MomentId.format(secC)
    private val pin1 = "user:00000000-0000-0000-0000-000000000001"
    private val pin2 = "user:00000000-0000-0000-0000-000000000002"
    private val pdb = PlacesDatabase.inMemory(ctx)
    private val pack = PlacePack(pdb, null)
    private val pinPrefs get() = ctx.getSharedPreferences(com.rokid.context.memory.place.UserPinStore.PREFS, Context.MODE_PRIVATE)

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        DataOpGate.shared = DataOpGate(waitMs = 200); MemoryExport.productionBuild = { false }
        ForgetV2.testOverride = true
        pinPrefs.edit().clear().commit()
        PlaceGraph.installForTests(pack); PlaceGraph.registerListeners()
    }

    @After fun tearDown() {
        ForgetV2.testOverride = null; MemoryExport.productionBuild = MemoryExport.defaultProductionBuild; DataOpGate.shared = original
        PlaceGraph.installForTests(null); pdb.close(); pinPrefs.edit().clear().commit(); graph.db.close(); MemoryGraph.resetForTests(null)
    }

    private suspend fun photo(id: String, sec: Long, offset: Long = 5): File {
        val f = File(ctx.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        graph.db.frameDao().insertIgnore(FrameEntity(id, sec * 1000 + offset, f.absolutePath, "rokid_glasses", phash = "p$id"))
        return f
    }
    private suspend fun moment(id: String, frameId: String) =
        graph.db.momentDao().replace(MomentEntity(id, 1, 1, "h", 1, MomentType.FRAME), listOf(MomentPartEntity(id, MomentPartKind.FRAME, frameId, "r", 1)))

    private val files = HashMap<String, File>()
    private suspend fun world(pin2Ref: String? = idB) {
        files["a1"] = photo("a1", secA); files["b1"] = photo("b1", secB); files["c1"] = photo("c1", secC)
        graph.db.memoryDao().insert(MemoryEntity("ma1", "a1", "Looked at A", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "Looked at A"))
        graph.db.memoryDao().insert(MemoryEntity("mc1", "c1", "C text", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, "C text"))
        moment(idA, "a1"); moment(idB, "b1"); moment(idC, "c1")
        val e = DeletionEpoch("i", 0, 0); val f = DaoFence(0, e, { 0 }, { e })
        graph.db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity("lA", "MOMENT", "voice", "X", secA * 1000 + 9, frameId = "a1", createdAt = 1), f)
        graph.db.placeLinkDao().insertIfAbsent(PlaceLinkEntity(pin1, idB, secB * 1000, "b1", "CARD", 1))
        assertTrue(PlaceGraph.savePins(ctx, listOf(
            UserPlace(pin1, "Antera", PlaceKinds.MONUMENT, 7.9, 80.7, momentId = idA),
            UserPlace(pin2, "Gate", PlaceKinds.MONUMENT, 7.8, 80.6, momentId = pin2Ref),
            UserPlace("user:00000000-0000-0000-0000-000000000003", "Other", PlaceKinds.MONUMENT, 7.7, 80.5, momentId = idC))))
    }
    private fun names() = PlaceGraph.pins(ctx).pins().map { it.name }
    private suspend fun del(id: String) = PlaceGraph.pins(ctx).pins().let { l -> PlaceGraph.savePins(ctx, l.filter { it.id != id }) }

    @Test fun momentsOfAPlaceAreItsReferenceMomentUnionItsCardLinks() = runBlocking<Unit> {
        world()
        assertEquals(listOf(idA, idB), PlaceMoments.of(ctx, pin1))
        assertEquals("a link to the reference Moment is one Moment", listOf(idB), PlaceMoments.union(idB, listOf(idB)))
        assertEquals(emptyList<String>(), PlaceMoments.of(ctx, "user:unknown"))
    }

    @Test fun modeBForgetsPhotosMemoriesMarksLinksAndThePinAndNamesTheOtherPin() = runBlocking<Unit> {
        world()
        val p = PlaceDelete.preview(ctx, pin1)!!
        assertEquals(2, p.moments); assertEquals(2, p.photos); assertTrue(p.memories >= 1); assertEquals(1, p.marks)
        assertEquals("the other pin whose reference Moment is in the set is named, this pin is not", listOf("Gate"), p.otherPinNames)
        val r = PlaceDelete.run(ctx, p, false) { del(it) }
        assertEquals(Outcome.DONE, r.outcome); assertTrue(r.pinDeleted)
        val dao = graph.db.forgetDao()
        assertNull(dao.frame("a1")); assertNull(dao.frame("b1")); assertFalse(files.getValue("a1").exists()); assertFalse(files.getValue("b1").exists())
        assertTrue(dao.memories(listOf("ma1")).isEmpty()); assertTrue(graph.db.momentDao().marksByIds(listOf("lA")).isEmpty())
        assertEquals("the link rows are gone", 0, graph.db.placeLinkDao().count())
        assertEquals("only the unrelated pin is left", listOf("Other"), names())
        assertNotNull("an unrelated Moment's photo and memory stay", dao.frame("c1")); assertEquals(1, dao.memories(listOf("mc1")).size); assertTrue(files.getValue("c1").exists())
    }

    @Test fun aFailureAfterTheCommitKeepsThePinAndTheNextDeleteFindsNoMoments() = runBlocking<Unit> {
        world(pin2Ref = null)
        // pin 1 is linked to B by a card only (no reference Moment), so the 1.4.2 cascade does not take it: only mode (a) can
        assertTrue(PlaceGraph.savePins(ctx, PlaceGraph.pins(ctx).pins().map { if (it.id == pin1) it.copy(momentId = null) else it }))
        val p = PlaceDelete.preview(ctx, pin1)!!
        assertEquals(1, p.moments)
        val r = PlaceDelete.run(ctx, p, false) { throw IllegalStateException("crash") }
        assertTrue("the Forget committed", r.outcome.committed); assertFalse(r.pinDeleted)
        assertTrue("the pin stays", names().contains("Antera")); assertNull(graph.db.forgetDao().frame("b1"))
        assertNull("no Moments left: the cascade is not offered, mode (a) still works", PlaceDelete.preview(ctx, pin1))
        assertTrue(del(pin1)); assertFalse(names().contains("Antera"))
    }

    @Test fun contentChangedAfterThePreviewDeletesNothing() = runBlocking<Unit> {
        world()
        val p = PlaceDelete.preview(ctx, pin1)!!
        graph.db.memoryDao().insert(MemoryEntity("ma2", "a1", "new note", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 2, false, "new note")) // the plan changed since the preview
        var called = false
        val r = PlaceDelete.run(ctx, p, false) { called = true; true }
        assertEquals(Outcome.CONTENT_CHANGED, r.outcome); assertFalse(called); assertFalse(r.pinDeleted)
        assertNotNull(graph.db.forgetDao().frame("a1")); assertNotNull(graph.db.forgetDao().frame("b1")); assertEquals(3, names().size)
        assertEquals("a fresh preview confirms", Outcome.DONE, PlaceDelete.run(ctx, PlaceDelete.preview(ctx, pin1)!!, false) { del(it) }.outcome)
    }

    @Test fun aSelectionAboveTwoHundredNeedsTheAcknowledgement() = runBlocking<Unit> {
        world(pin2Ref = null)
        (1..210).forEach { photo("x$it", secA, 100L + it) }
        val p = PlaceDelete.preview(ctx, pin1)!!
        assertTrue(p.needsAck); assertFalse(p.tooLarge)
        val no = PlaceDelete.run(ctx, p, false) { del(it) }
        assertEquals(Outcome.CONTENT_CHANGED, no.outcome); assertNotNull(graph.db.forgetDao().frame("x1")); assertTrue(names().contains("Antera"))
        val yes = PlaceDelete.run(ctx, p, true) { del(it) }
        assertEquals(Outcome.DONE, yes.outcome); assertNull(graph.db.forgetDao().frame("x1")); assertFalse(names().contains("Antera"))
    }

    @Test fun aSelectionAboveFiveThousandIsRefusedAndNothingIsDeleted() = runBlocking<Unit> {
        world(pin2Ref = null)
        val dao = graph.db.frameDao()
        (1..5001).forEach { dao.insertIgnore(FrameEntity("y$it", secA * 1000 + 10 + (it % 900), "/none/y$it.jpg", "rokid_glasses", phash = "py$it")) }
        val p = PlaceDelete.preview(ctx, pin1)!!
        assertTrue(p.tooLarge)
        val r = PlaceDelete.run(ctx, p, true) { del(it) }
        assertEquals(Outcome.TOO_LARGE, r.outcome); assertNotNull(graph.db.forgetDao().frame("a1")); assertTrue(names().contains("Antera"))
    }

    @Test fun withForgetV2OffTheChoiceIsHiddenAndNothingRuns() = runBlocking<Unit> {
        world()
        val p = PlaceDelete.preview(ctx, pin1)!!
        ForgetV2.testOverride = false
        assertNull(PlaceDelete.preview(ctx, pin1))
        assertEquals(Outcome.FAILED, PlaceDelete.run(ctx, p, false) { del(it) }.outcome)
        assertNotNull(graph.db.forgetDao().frame("a1")); assertEquals(3, names().size)
    }

    @Test fun aPlaceWithNoMomentsHasNoCascade() = runBlocking<Unit> {
        world()
        assertTrue(PlaceGraph.savePins(ctx, PlaceGraph.pins(ctx).pins() + UserPlace("user:00000000-0000-0000-0000-000000000009", "Empty", PlaceKinds.MONUMENT, 7.6, 80.4)))
        assertNull(PlaceDelete.preview(ctx, "user:00000000-0000-0000-0000-000000000009"))
    }

    @Test fun modeAKeepsEveryMomentAndRemovesItsLinks() = runBlocking<Unit> {
        world()
        assertTrue(del(pin1))
        assertEquals(0, graph.db.placeLinkDao().count())
        val dao = graph.db.forgetDao()
        assertNotNull(dao.frame("a1")); assertNotNull(dao.frame("b1")); assertNotNull(dao.frame("c1")); assertEquals(1, dao.memories(listOf("ma1")).size)
        assertEquals(1, graph.db.momentDao().marksByIds(listOf("lA")).size)
    }
}
