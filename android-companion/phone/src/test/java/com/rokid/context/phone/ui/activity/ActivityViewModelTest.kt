package com.rokid.context.phone.ui.activity

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.list.ListKind as LK
import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ActivityViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<ActivityViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private var zone: java.time.ZoneId = Fixtures.ZONE

    private fun vm(clock: kotlinx.coroutines.flow.Flow<Long>, initial: Period = Period.TODAY) = ActivityViewModel(
        initial, FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges, graph.marks,
        clock = clock, storagePct = flowOf(null), zone = { zone },
    ).also { vms += it }

    @Test fun switchingPeriodRequeriesTheWindow() = runBlocking {
        val tuesday = TODAY.plusDays(1)
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", Fixtures.at(tuesday, 9)))
        graph.frames.add(Fixtures.frame("f1", Fixtures.at(TODAY, 10)))
        val model = vm(flowOf(Fixtures.at(tuesday, 10)))
        val today = withTimeout(5_000) { model.state.first { it.today == tuesday && it.tiles.memories == 1 } }
        assertEquals(0, today.tiles.frames)
        assertEquals(listOf("Morning"), today.sections.map { it.label })
        model.selectPeriod(Period.WEEK)
        val week = withTimeout(5_000) { model.state.first { it.period == Period.WEEK && it.tiles.frames == 1 } }
        assertEquals(7, week.bars.size)
        assertEquals(1, week.bars[0].count)
        assertEquals(listOf("Today", "Yesterday"), week.dayRows.map { it.title })
        assertEquals("Week of 12 Oct", week.header)
    }

    /** A period's list resolves the ids of its kind itself (the route carries a key only): exactly what the card's count says, newest first. */
    @Test fun aPeriodsListResolvesTheIdsOfItsKind() = runBlocking {
        graph.frames.add(Fixtures.frame("f1", Fixtures.at(TODAY, 8)))
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", Fixtures.at(TODAY, 9)))
        graph.asks.upsert(Fixtures.ask("a1", Fixtures.at(TODAY, 9)))
        graph.asks.upsert(Fixtures.ask("a0", Fixtures.at(TODAY.minusDays(1), 9)))
        graph.nudges.addIgnore(Fixtures.nudge("n1", Fixtures.at(TODAY, 9)))
        val data = com.rokid.context.phone.ui.preview.RepoActivityData(graph.timeline, graph.asks, graph.nudges, graph.marks, { Fixtures.ZONE })
        fun ids(kind: LK, p: Period) = runBlocking { PeriodWindow.of(p, TODAY).millis(Fixtures.ZONE).let { r -> data.page(kind, r, com.rokid.context.memory.Cursor.head(r), 100).ids } }
        assertEquals(listOf("f1"), ids(LK.FRAMES, Period.TODAY)); assertEquals(listOf("m1"), ids(LK.MEMORIES, Period.TODAY))
        assertEquals(listOf("a1"), ids(LK.ANSWERS, Period.TODAY)); assertEquals(listOf("n1"), ids(LK.NUDGES, Period.TODAY))
        assertEquals("the week starts on Monday: Sunday's answer is not in it", listOf("a1"), ids(LK.ANSWERS, Period.WEEK))
    }

    @Test fun backlogMemoryFromAnEarlierDayPhotoKeepsItsPhoto() = runBlocking {
        // BUG-1 (device run 2026-10-14): photo captured yesterday, extracted today -> row showed "No photo".
        graph.frames.add(Fixtures.frame("old", Fixtures.at(TODAY.minusDays(1), 23, 10), path = "/old.jpg"))
        graph.memories.add(Fixtures.memory("m1", "Cable by the cushion", Fixtures.at(TODAY, 9), frameId = "old"))
        val model = vm(flowOf(Fixtures.at(TODAY, 10)))
        val s = withTimeout(5_000) { model.state.first { it.today == TODAY && it.tiles.memories == 1 } }
        assertEquals(0, s.tiles.frames)
        assertEquals("/old.jpg", s.sections.flatMap { it.rows }.single().framePath)
    }

    @Test fun todayRollsOverAtLocalMidnight() = runBlocking {
        graph.memories.add(Fixtures.memory("late", "Late snack", Fixtures.at(TODAY, 22)))
        val clock = MutableStateFlow(Fixtures.at(TODAY, 23, 59))
        val model = vm(clock)
        withTimeout(5_000) { model.state.first { it.today == TODAY && it.tiles.memories == 1 } }
        clock.value = Fixtures.at(TODAY.plusDays(1), 0, 1)
        val next = withTimeout(5_000) { model.state.first { it.today == TODAY.plusDays(1) } }
        assertEquals(0, next.tiles.memories)
    }

    @Test fun zoneChangeRegroupsTimelineWithoutDateFlip() = runBlocking {
        // 11:30 Kolkata (Morning) = 06:00 UTC (Morning too); pick 12:30 Kolkata = 07:00 UTC -> Afternoon vs Morning.
        val at = Fixtures.at(TODAY, 12, 30)
        graph.memories.add(Fixtures.memory("m1", "Lunch", at))
        val clock = MutableStateFlow(Fixtures.at(TODAY, 12, 31))
        val model = vm(clock)
        val first = withTimeout(5_000) { model.state.first { it.today == TODAY && it.tiles.memories == 1 } }
        assertEquals(listOf("Afternoon"), first.sections.map { it.label })
        zone = java.time.ZoneOffset.UTC
        clock.value = Fixtures.at(TODAY, 12, 32) // next tick notices the new zone; local date is still 12 Oct
        val next = withTimeout(5_000) { model.state.first { it.sections.map { s -> s.label } == listOf("Morning") } }
        assertEquals(TODAY, next.today)
    }

    @Test fun aQuickPauseThenResumeIsTwoRequests() {
        val slow = FakePipelineControl(publishAtOnce = false)
        val model = ActivityViewModel(
            Period.TODAY, FakeGlassesStatusSource(), slow, graph.timeline, graph.asks, graph.nudges, graph.marks,
            clock = flowOf(Fixtures.NOW), storagePct = flowOf(null), zone = { zone },
        ).also { vms += it }
        model.togglePause(); model.togglePause()
        assertEquals(listOf(false, true), slow.requests)
    }
}
