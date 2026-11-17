package com.rokid.context.phone.ui.list.paging

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionLimits
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.list.ListKind
import com.rokid.context.phone.ui.preview.RepoActivityData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A7 (multi-select, ruling P2): the selection is a SET of refs (not positions), scoped to one kind, and hour and day resolve a kind-scoped id set with an ids-only query, beyond any page or cap. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SelectionModelTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() = TestGraph.close(ctx)

    private fun m(id: String) = Ref(Family.MEMORY, id)

    @Test fun aSelectionHasSetSemanticsAndNeverHoldsPositions() {
        val s = Selection().enter(m("a")).enter(m("a")).toggle(m("b")).addAll(listOf(m("b"), m("c")))
        assertEquals(setOf(m("a"), m("b"), m("c")), s.refs); assertEquals(3, s.size); assertTrue(s.active)
        assertEquals("toggling an element twice is a no-op", s.refs, s.toggle(m("a")).toggle(m("a")).refs)
        assertEquals(setOf(m("b"), m("c")), s.toggle(m("a")).refs)
        assertTrue("an empty selection stays in select mode until the user clears it", s.prune(s.refs).active)
        assertEquals(setOf(m("c")), s.prune(listOf(m("a"), m("b"), m("zzz"))).refs)
        assertEquals(Selection(), s.clear())
    }

    @Test fun theSelectionSurvivesProcessDeathThroughItsStringForm() {
        val s = Selection().addAll(listOf(m("a"), Ref(Family.PHOTO, "f:1"), Ref(Family.MARK, "l")))
        assertEquals(s, Selection.decode(s.encode()))
        assertEquals(Selection(), Selection.decode(null)); assertEquals(Selection(), Selection.decode(""))
        assertEquals("a garbled line is ignored, not trusted", setOf(m("a")), Selection.decode("1\nmemory:a\nnope\nunknownfamily:x").refs)
        assertTrue(Selection().addAll(listOf(m("a"))).encode().startsWith("1\n")); assertFalse(Selection.decode("0\nmemory:a").active)
    }

    @Test fun refsAreScopedToTheKindsFamilyAndOnlyForgettableKindsSelect() {
        assertEquals(setOf(Ref(Family.PHOTO, "f1")), SelectionKinds.refs(ListKind.FRAMES, listOf("f1", "f1")))
        assertEquals(Family.MEMORY, SelectionKinds.familyOf(ListKind.MOMENTS)); assertEquals(Family.MEMORY, SelectionKinds.familyOf(ListKind.MEMORIES))
        assertEquals(Family.MARK, SelectionKinds.familyOf(ListKind.MARKS))
        assertNull("no single-item answer Forget", SelectionKinds.familyOf(ListKind.ANSWERS)); assertFalse(SelectionKinds.supports(ListKind.NUDGES))
        assertEquals(emptySet<Ref>(), SelectionKinds.refs(ListKind.ANSWERS, listOf("a")))
        assertNull(SelectionKinds.ref(ListKind.NUDGES, "n"))
    }

    @Test fun theCeilingDisablesForgetBeforeAnythingIsPlanned() {
        val ok = Selection().addAll((1..SelectionLimits.REFUSE_ABOVE).map { m("m$it") }); assertFalse(ok.overCeiling)
        assertTrue(ok.enter(m("one more")).overCeiling)
    }

    private fun resolver(kind: ListKind) = SelectionResolver(kind, RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE }), { Fixtures.ZONE })

    private suspend fun seed(prefix: String, hour: Int, n: Int) { for (i in 1..n) graph.db.memoryDao().insert(Fixtures.memory("$prefix$i", "text", Fixtures.at(TODAY, hour, i % 60))) }

    @Test fun selectThisDayResolvesEveryMemoryOfTheDayBeyondAPageAndTheOldCap() = runBlocking<Unit> {
        seed("a", 9, 700); seed("b", 10, 500)                                     // 1,200 in the day: past the old 1,000 cap and 12 pages
        graph.db.memoryDao().insert(Fixtures.memory("yesterday", "x", Fixtures.at(TODAY.minusDays(1), 23)))
        graph.db.memoryDao().insert(Fixtures.memory("hidden", "x", Fixtures.at(TODAY, 11), review = com.rokid.context.memory.db.ReviewState.INVALIDATED))
        val r = resolver(ListKind.MOMENTS).day(TODAY) as Resolved.Ids
        assertEquals(1_200, r.refs.size)
        assertTrue("one family only", r.refs.all { it.family == Family.MEMORY })
        assertFalse(m("yesterday") in r.refs); assertFalse("only visible memories, as the day count", m("hidden") in r.refs)
    }

    @Test fun selectThisHourIsThatHoursMomentsOnlyAndKindScopedAcrossOtherKinds() = runBlocking<Unit> {
        seed("a", 9, 30); seed("b", 10, 40)
        graph.db.frameDao().insertIgnore(Fixtures.frame("photo", Fixtures.at(TODAY, 10, 5))) // a photo and an ask in the same hour are NOT selected with the memories
        graph.asks.upsert(Fixtures.ask("ask", Fixtures.at(TODAY, 10, 6)))
        val z = Fixtures.ZONE
        val slot = DayHourTree.slots(TODAY, z).first { it.label == "10:00" }
        val hour = HourNode(slot.startMs, slot.endMs, slot.label, 40, emptyList())
        val r = resolver(ListKind.MOMENTS).hour(TODAY, hour) as Resolved.Ids
        assertEquals((1..40).map { m("b$it") }.toSet(), r.refs)
        // the same hour for the Frames kind would be photos: a different resolver, a different family, never mixed
        val photos = resolver(ListKind.FRAMES).day(TODAY) as Resolved.Ids
        assertEquals(setOf(Ref(Family.PHOTO, "photo")), photos.refs)
        assertEquals("answers have no Forget: nothing is selected", emptySet<Ref>(), (resolver(ListKind.ANSWERS).day(TODAY) as Resolved.Ids).refs)
    }

    @Test fun theEarlierPhotosGroupResolvesThePhotosTakenBeforeTheDay() = runBlocking<Unit> {
        graph.db.frameDao().insertIgnore(Fixtures.frame("old", Fixtures.at(TODAY.minusDays(2), 8)))
        graph.db.memoryDao().insert(Fixtures.memory("late", "x", Fixtures.at(TODAY, 9), frameId = "old"))
        graph.db.memoryDao().insert(Fixtures.memory("now", "x", Fixtures.at(TODAY, 9, 10)))
        val dayStart = DayHourTree.slots(TODAY, Fixtures.ZONE).first().startMs
        val earlier = HourNode(Long.MIN_VALUE, dayStart, "Earlier photos", 1, emptyList(), earlier = true)
        assertEquals(setOf(m("late")), (resolver(ListKind.MOMENTS).hour(TODAY, earlier) as Resolved.Ids).refs)
    }

    @Test fun aDayAboveTheForgetCeilingSelectsNothingAndSaysSo() = runBlocking<Unit> {
        seed("a", 9, SelectionLimits.REFUSE_ABOVE + 1)
        assertEquals(Resolved.TooMany, resolver(ListKind.MOMENTS).day(TODAY))
    }

    @Test fun marksResolveFromTheirOwnTableOnly() = runBlocking<Unit> {
        val s3 = graph.db.canvasSlice3Dao(); val e = com.rokid.context.memory.db.DeletionEpoch("i", 0, 0); val f = com.rokid.context.memory.db.DaoFence(0, e, { 0 }, { e })
        s3.insertLabelFenced(com.rokid.context.memory.db.CanvasLabelEntity("mk1", "MOMENT", "voice", "X", Fixtures.at(TODAY, 9), createdAt = 1), f)
        seed("a", 9, 3)
        assertEquals(setOf(Ref(Family.MARK, "mk1")), (resolver(ListKind.MARKS).day(TODAY) as Resolved.Ids).refs)
    }
}
