package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.AskEntity
import com.rokid.context.memory.db.CanvasLabelEntity
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.db.FrameEntity
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemorySourceEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Fate
import com.rokid.context.memory.forget.lifecycle.ForgetPlanner
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Policy
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionLimits
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
 * Activity pagination A8 (multi-select Forget, proxy ruling P2) end to end over the real executor and an in-memory database, `forgetV2` ON: the preview is the planner's plan, the delete does exactly what that plan named
 * (preview == delete), a changed set or a missing acknowledgement deletes NOTHING, a selection above the ceiling is refused, the leases are revoked, and with the flag OFF the feature does not exist.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SelectionForgetTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    private val dao get() = graph.db.forgetDao()

    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); ForgetV2.testOverride = true }
    @After fun tearDown() { ForgetV2.testOverride = null; Leases.shared.revokeAll(); graph.db.close(); MemoryGraph.resetForTests(null) }

    private fun mem(id: String, frameId: String?, text: String = "T-$id") = MemoryEntity(id, frameId, text, "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, false, text)
    private fun photo(id: String): File = File(ctx.filesDir, "rokid_archive/$id.jpg").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }

    private suspend fun world(): Map<String, File> {
        val files = listOf("f1", "f2", "f3").associateWith { photo(it) }
        files.forEach { (id, f) -> graph.db.frameDao().insertIgnore(FrameEntity(id, 10, f.absolutePath, "rokid_glasses")) }
        graph.db.memoryDao().insert(mem("m1", "f1")); graph.db.memoryDao().insert(mem("m2", "f2")); graph.db.memoryDao().insert(mem("m3", "f3"))
        graph.db.memoryDao().insert(MemoryEntity("d1", null, "D-d1", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1, true, "D-d1"))
        graph.db.forgetDao().insertEdges(listOf(MemorySourceEntity("d1", "m1")))
        graph.asks.add(AskEntity("ask_m1", 1, "q", "ANSWER-1", "[\"m1\"]", null, "phone", false))
        graph.asks.add(AskEntity("ask_m3", 1, "q", "ANSWER-3", "[\"m3\"]", null, "phone", false))
        val e = DeletionEpoch("i", 0, 0); val f = DaoFence(0, e, { 0 }, { e })
        graph.db.canvasSlice3Dao().insertLabelFenced(CanvasLabelEntity("l1", "MOMENT", "voice", "X", 20, createdAt = 1), f)
        return files
    }
    private val roots = setOf(Ref(Family.MEMORY, "m1"), Ref(Family.PHOTO, "f2"), Ref(Family.MARK, "l1"))

    @Test fun thePreviewIsThePlannersPlanAndTheDeleteDoesExactlyWhatItNamed() = runBlocking<Unit> {
        val files = world()
        val lease = Leases.shared.open(Leases.Kind.ASK, listOf(Ref(Family.MEMORY, "d1")), 0)
        val plan = ForgetPlanner(graph.db).plan(roots, Policy.User) // what the preview is made of
        val preview = ForgetActions.previewSelection(ctx, roots)!!
        assertEquals(plan.size, preview.nodes); assertEquals(3, preview.roots); assertTrue(preview.missing.isEmpty()); assertFalse(preview.needsAck); assertFalse(preview.tooLarge)
        assertEquals("the dependency preview names the derived memory and the answer", plan.refs(Family.ANSWER).size, preview.count(Family.ANSWER))
        assertTrue(preview.count(Family.ANSWER) >= 1 && preview.count(Family.MEMORY) >= 2)
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetSelectionOutcome(ctx, roots, preview.token(false)))
        // delete == preview: every ERASE node is gone, every REBUILD node's text is gone, every answer the plan reached is withdrawn, nothing outside the plan changed
        plan.refs(Family.MEMORY, Fate.ERASE).forEach { assertTrue("$it erased", dao.memories(listOf(it)).isEmpty()) }
        plan.refs(Family.MEMORY, Fate.REBUILD).forEach { id -> assertTrue("$id invalidated", dao.memories(listOf(id)).all { it.reviewState == ReviewState.INVALIDATED }) }
        plan.refs(Family.ANSWER).forEach { id -> assertTrue("$id withdrawn", graph.asks.byId(id)!!.let { it.redacted || it.answer != "ANSWER-1" }) }
        assertTrue(dao.memories(listOf("m1", "m2")).isEmpty()); assertNull(dao.frame("f2")); assertFalse(files.getValue("f2").exists()); assertFalse(files.getValue("f1").exists())
        assertTrue("the mark is gone", graph.db.momentDao().marksByIds(listOf("l1")).isEmpty())
        assertNotNull("an unselected photo and memory are untouched", dao.frame("f3")); assertEquals("T-m3", dao.memories(listOf("m3")).single().text)
        assertEquals("ANSWER-3", graph.asks.byId("ask_m3")!!.answer); assertTrue(files.getValue("f3").exists())
        assertTrue("the read lease the plan reached was revoked", lease.isRevoked)
        lease.close()
    }

    @Test fun aSetThatChangedSinceThePreviewDeletesNothingAndAFreshPreviewConfirms() = runBlocking<Unit> {
        world()
        val preview = ForgetActions.previewSelection(ctx, roots)!!
        graph.asks.add(AskEntity("ask_new", 2, "q", "NEW-ANSWER", "[\"m1\"]", null, "phone", false)) // a new answer now depends on a selected fact
        assertEquals(ForgetActions.Outcome.CONTENT_CHANGED, ForgetActions.forgetSelectionOutcome(ctx, roots, preview.token(false)))
        assertEquals("nothing was deleted", "T-m1", dao.memories(listOf("m1")).single().text); assertEquals("NEW-ANSWER", graph.asks.byId("ask_new")!!.answer)
        val again = ForgetActions.previewSelection(ctx, roots)!!
        assertTrue(again.planHash != preview.planHash)
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetSelectionOutcome(ctx, roots, again.token(false)))
        assertTrue(dao.memories(listOf("m1")).isEmpty())
    }

    @Test fun aLargeSelectionNeedsTheAcknowledgementAndWithoutItNothingIsDeleted() = runBlocking<Unit> {
        val many = (1..SelectionLimits.ACK_ABOVE + 1).map { "b$it" }
        many.forEach { graph.db.memoryDao().insert(mem(it, null)) }
        val big = many.map { Ref(Family.MEMORY, it) }.toSet()
        val preview = ForgetActions.previewSelection(ctx, big)!!
        assertTrue(preview.needsAck); assertTrue(preview.ackHash.endsWith("+ack")); assertEquals(preview.planHash, preview.token(false)); assertEquals(preview.ackHash, preview.token(true))
        assertEquals("the plain token does not carry the acknowledgement", ForgetActions.Outcome.CONTENT_CHANGED, ForgetActions.forgetSelectionOutcome(ctx, big, preview.token(false)))
        assertEquals(many.size, dao.memories(many).size)
        assertEquals(ForgetActions.Outcome.DONE, ForgetActions.forgetSelectionOutcome(ctx, big, preview.token(true)))
        assertTrue(dao.memories(many).isEmpty())
    }

    @Test fun exactlyTheAckThresholdNeedsNoAcknowledgement() = runBlocking<Unit> {
        val few = (1..SelectionLimits.ACK_ABOVE).map { "c$it" }
        few.forEach { graph.db.memoryDao().insert(mem(it, null)) }
        val preview = ForgetActions.previewSelection(ctx, few.map { Ref(Family.MEMORY, it) }.toSet())!!
        assertFalse(preview.needsAck)
    }

    @Test fun aSelectionAboveTheCeilingIsRefusedBeforeAnythingIsPlannedOrDeleted() = runBlocking<Unit> {
        world()
        val huge = (1..SelectionLimits.REFUSE_ABOVE + 1).map { Ref(Family.MEMORY, "ghost$it") }.toSet() + Ref(Family.MEMORY, "m3")
        val preview = ForgetActions.previewSelection(ctx, huge)!!
        assertTrue(preview.tooLarge)
        assertEquals(ForgetActions.Outcome.TOO_LARGE, ForgetActions.forgetSelectionOutcome(ctx, huge, "anything"))
        assertEquals("T-m3", dao.memories(listOf("m3")).single().text)
    }

    @Test fun rootsThatAreAlreadyGoneAreReportedMissingNotFailed() = runBlocking<Unit> {
        world()
        val preview = ForgetActions.previewSelection(ctx, roots + Ref(Family.MEMORY, "forgotten-elsewhere"))!!
        assertEquals(setOf(Ref(Family.MEMORY, "forgotten-elsewhere")), preview.missing)
    }

    @Test fun withTheFlagOffThereIsNoPreviewAndNoDelete() = runBlocking<Unit> {
        world(); ForgetV2.testOverride = false
        assertNull(ForgetActions.previewSelection(ctx, roots))
        assertEquals(ForgetActions.Outcome.FAILED, ForgetActions.forgetSelectionOutcome(ctx, roots, "x"))
        assertEquals("T-m1", dao.memories(listOf("m1")).single().text); assertNotNull(dao.frame("f2"))
    }
}
