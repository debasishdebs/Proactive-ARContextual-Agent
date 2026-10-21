package com.rokid.context.phone.ui.photos

import com.rokid.context.phone.ui.Fixtures.TODAY
import com.rokid.context.phone.ui.Fixtures.ZONE
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.work.frames.recall.DescribeResult
import com.rokid.context.work.frames.recall.ImageSearchState
import com.rokid.context.work.frames.recall.PhotoHit
import com.rokid.context.work.frames.recall.PhotoSource
import com.rokid.context.work.frames.recall.RecallResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PhotoSearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private fun hit(id: String, described: Boolean = false, stored: Boolean = true) =
        PhotoHit(id, PhotoSource.IMAGE, 0.5, 1_000, 2_000, stored, described, null, null, null)
    private fun res(vararg h: PhotoHit) = RecallResult(h.toList(), emptyList(), ImageSearchState.RAN, h.size, false)

    private val searches = mutableListOf<String>()
    private var searchFn: suspend (String) -> RecallResult = { res(hit("a"), hit("b")) }
    private var reenrichFn: suspend (RecallResult) -> RecallResult = { it }
    private var describeFn: suspend (List<String>) -> DescribeResult = { DescribeResult.Saved(2, 1) }
    private var sceneFn: suspend (String) -> DescribeResult = { DescribeResult.Saved(2, 3) }
    private val changes = Channel<Unit>(Channel.UNLIMITED)

    private fun vm(initial: String? = null) = PhotoSearchViewModel(
        initial, { q -> searches += q; searchFn(q) }, { reenrichFn(it) }, { describeFn(it) }, { sceneFn(it) }, { ids -> ids.associateWith { "/p/$it.jpg" } },
        { ZONE }, { TODAY }, changes.receiveAsFlow(),
    )

    @Test fun typingNeverSearchesAndSubmitSearchesOnce() = runTest {
        val m = vm()
        m.onQueryChange("k"); m.onQueryChange("ke"); m.onQueryChange("keys")
        assertTrue(searches.isEmpty()); assertEquals("keys", m.state.value.query)
        m.submit()
        assertEquals(listOf("keys"), searches)
        assertEquals(listOf("a", "b"), m.state.value.tiles.map { it.frameId }); assertFalse(m.state.value.searching)
        assertEquals("/p/a.jpg", m.state.value.tiles[0].path)
    }

    @Test fun blankQueryDoesNotSearch() = runTest {
        val m = vm(); m.onQueryChange("   "); m.submit()
        assertTrue(searches.isEmpty())
    }

    @Test fun initialQuerySearchesOnce() = runTest {
        val m = vm("where are my keys")
        assertEquals(listOf("where are my keys"), searches)
        assertEquals("where are my keys", m.state.value.query)
        assertEquals(2, m.state.value.tiles.size)
    }

    @Test fun staleSearchCompletionDiscarded() = runTest {
        val first = CompletableDeferred<RecallResult>()
        searchFn = { q -> if (q == "old") first.await() else res(hit("new")) }
        val m = vm()
        m.onQueryChange("old"); m.submit()
        assertTrue(m.state.value.searching)
        m.onQueryChange("new"); m.submit()
        assertEquals(listOf("new"), m.state.value.tiles.map { it.frameId })
        first.complete(res(hit("stale")))
        assertEquals("the older completion changed nothing", listOf("new"), m.state.value.tiles.map { it.frameId })
        assertFalse(m.state.value.searching)
    }

    @Test fun forgetWhileVisibleRemovesTile() = runTest {
        val m = vm("keys")
        reenrichFn = { r -> res(*r.image.filter { it.frameId != "a" }.toTypedArray()) }
        changes.trySend(Unit)
        assertEquals(listOf("b"), m.state.value.tiles.map { it.frameId })
        assertEquals("a database change never rescans", 1, searches.size)
    }

    @Test fun returnFromForgetShowsNoStaleTile() = runTest {
        val m = vm("keys")
        reenrichFn = { r -> res(*r.image.filter { it.frameId != "b" }.toTypedArray()) }
        m.refresh() // the screen's ON_RESUME
        assertEquals(listOf("a"), m.state.value.tiles.map { it.frameId })
    }

    @Test fun describeSuccessUpdatesCheck() = runTest {
        val m = vm("keys")
        reenrichFn = { r -> res(*r.image.map { if (it.frameId == "a") it.copy(described = true) else it }.toTypedArray()) }
        m.describe("a")
        assertEquals("Described · 2 memories saved", m.state.value.status)
        assertTrue(m.state.value.tiles.first { it.frameId == "a" }.described)
        assertFalse(m.state.value.tiles.first { it.frameId == "b" }.described)
        assertFalse(m.state.value.describing)
    }

    @Test fun describingFlagHoldsWhileTheCallRuns() = runTest {
        val gate = CompletableDeferred<DescribeResult>()
        describeFn = { gate.await() }
        val m = vm("keys")
        m.describe("a")
        assertTrue(m.state.value.describing); assertNull(m.state.value.status)
        m.describe("b") // a second tap while running is ignored
        gate.complete(DescribeResult.Busy)
        assertFalse(m.state.value.describing)
        assertEquals("Already being described — check back in a minute", m.state.value.status)
    }

    @Test fun describeSceneUsesTheClusterAndFailureNeverThrows() = runTest {
        val scenes = mutableListOf<String>()
        sceneFn = { scenes += it; throw java.io.IOException("x") }
        val m = vm("keys")
        m.describeScene("c1")
        assertEquals(listOf("c1"), scenes)
        assertEquals("AI unavailable — try again later", m.state.value.status)
        assertFalse(m.state.value.describing)
    }

    @Test fun searchFailureShowsNoTilesNotACrash() = runTest {
        searchFn = { throw java.io.IOException("x") }
        val m = vm("keys")
        assertTrue(m.state.value.tiles.isEmpty()); assertFalse(m.state.value.searching)
    }
}
