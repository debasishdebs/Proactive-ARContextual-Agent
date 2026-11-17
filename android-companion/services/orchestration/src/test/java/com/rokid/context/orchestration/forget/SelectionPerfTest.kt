package com.rokid.context.orchestration.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.MemoryEntity
import com.rokid.context.memory.db.MemoryTier
import com.rokid.context.memory.db.ReviewState
import com.rokid.context.memory.forget.lifecycle.Family
import com.rokid.context.memory.forget.lifecycle.ForgetV2
import com.rokid.context.memory.forget.lifecycle.Leases
import com.rokid.context.memory.forget.lifecycle.Ref
import com.rokid.context.memory.forget.lifecycle.SelectionLimits
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A9: timings of the multi-select Forget at the ceiling (the numbers go into the worn-test list; Robolectric on a laptop is NOT a phone, so the bounds here only catch a regression of an order of magnitude).
 * The test prints one `SELECTION_PERF` line per measurement.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SelectionPerfTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val graph get() = MemoryGraph.get(ctx)
    @Before fun setUp() { WorkManagerTestInitHelper.initializeTestWorkManager(ctx); MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); ForgetV2.testOverride = true }
    @After fun tearDown() { ForgetV2.testOverride = null; Leases.shared.revokeAll(); graph.db.close(); MemoryGraph.resetForTests(null) }

    private inline fun <T> timed(label: String, block: () -> T): Pair<T, Long> { val t = System.nanoTime(); val r = block(); val ms = (System.nanoTime() - t) / 1_000_000; println("SELECTION_PERF $label ms=$ms"); return r to ms }

    @Test fun planningAndForgettingTheCeilingStaysInTheSameOrderOfMagnitude() = runBlocking<Unit> {
        val n = SelectionLimits.REFUSE_ABOVE
        val ids = (1..n).map { "p$it" }
        graph.db.memoryDao().let { d -> ids.forEachIndexed { i, id -> d.insert(MemoryEntity(id, null, "T-$id", "note", "", MemoryTier.SHORT, 0.8, ReviewState.OK, false, 1_000L + i, false, "T-$id")) } }
        val day = graph.db.memoryViewDao()
        val (listed, idsMs) = timed("ids-only 5001 limit over ${n} memories") { day.visibleIdsIn(0, Long.MAX_VALUE, n + 1) }
        assertEquals(n, listed.size); assertTrue("an ids-only day read is cheap", idsMs < 5_000)
        val roots = ids.map { Ref(Family.MEMORY, it) }.toSet()
        val (preview, planMs) = timed("preview of $n roots") { ForgetActions.previewSelection(ctx, roots)!! }
        assertEquals(n, preview.roots); assertTrue(preview.needsAck); assertTrue(!preview.tooLarge); assertTrue("planning the ceiling is bounded", planMs < 60_000)
        val (out, forgetMs) = timed("forget of $n roots") { ForgetActions.forgetSelectionOutcome(ctx, roots, preview.token(true)) }
        assertEquals(ForgetActions.Outcome.DONE, out); assertTrue(forgetMs < 120_000)
        assertEquals(0, graph.db.forgetDao().memories(ids.take(900)).size)
    }
}
