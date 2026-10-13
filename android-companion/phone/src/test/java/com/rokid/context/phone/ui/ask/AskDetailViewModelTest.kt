package com.rokid.context.phone.ui.ask

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AskDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val vms = mutableListOf<AskDetailViewModel>()

    @Before fun setUp() { graph = TestGraph.fresh(ctx) }
    @After fun tearDown() { vms.forEach { it.cancelScope() }; TestGraph.close(ctx) }

    private fun vm(id: String) = AskDetailViewModel(
        id, graph.asks, graph.proposals, graph.timeline,
        rater = { askId, up -> graph.asks.setRating(askId, if (up) 1 else -1); true }, zone = { Fixtures.ZONE },
    ).also { vms += it }

    @Test fun loadsSourcesAndProposalAndReflectsRating() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        graph.asks.upsert(Fixtures.ask("a1", Fixtures.NOW, sources = """["m1"]"""))
        graph.proposals.addIgnore(Fixtures.proposal("p1", "a1", state = "EXPIRED"))
        val model = vm("a1")
        val s = withTimeout(5_000) { model.state.first { it.found } }
        assertEquals(listOf("Keys on shelf"), s.sources.map { it.text })
        assertEquals("Expired", s.proposal?.state)
        model.rate(up = true)
        assertEquals(1, withTimeout(5_000) { model.state.first { it.rating == 1 } }.rating)
    }

    @Test fun unknownAskIdIsNotFound() = runBlocking {
        val s = withTimeout(5_000) { vm("missing").state.first { !it.loading } }
        assertFalse(s.found)
    }

    /** P6 T6 carry: source cards observe memory changes, so a Forget removes the card live. */
    @Test fun forgottenSourceDisappearsLive() = runBlocking {
        graph.memories.add(Fixtures.memory("m1", "Keys on shelf", 1))
        graph.memories.add(Fixtures.memory("m2", "Wallet in drawer", 2))
        graph.asks.upsert(Fixtures.ask("a1", Fixtures.NOW, sources = """["m1","m2"]"""))
        val model = vm("a1")
        assertEquals(2, withTimeout(5_000) { model.state.first { it.found } }.sources.size)
        graph.memories.deleteByIds(listOf("m1"))
        val s = withTimeout(5_000) { model.state.first { it.sources.size == 1 } }
        assertEquals(listOf("Wallet in drawer"), s.sources.map { it.text })
        assertEquals(1, s.missingSources)
    }
}
