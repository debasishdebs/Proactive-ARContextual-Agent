package com.rokid.context.phone.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.LinkState
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.banner.BannerAction
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var graph: MemoryGraph
    private val glasses = FakeGlassesStatusSource()
    private val pipeline = FakePipelineControl()
    private val questions = mutableListOf<String>()
    private var outcome: suspend (String) -> AskOutcome = { AskOutcome.Failed("AI unavailable — check Settings › Model endpoints") }
    private lateinit var vm: HomeViewModel

    @Before fun setUp() {
        graph = TestGraph.fresh(ctx)
        vm = HomeViewModel(
            glasses, pipeline, graph.timeline, graph.asks, graph.nudges, AskService { q -> questions += q; outcome(q) },
            clock = flowOf(Fixtures.NOW), storagePct = flowOf(10), zone = { Fixtures.ZONE },
        )
    }

    @After fun tearDown() { vm.cancelScope(); TestGraph.close(ctx) }

    @Test fun feedMergesAsksAndNudgesNewestFirst() = runBlocking {
        graph.asks.upsert(Fixtures.ask("a1", Fixtures.at(Fixtures.TODAY, 8)))
        graph.nudges.addIgnore(Fixtures.nudge("n1", Fixtures.at(Fixtures.TODAY, 9)))
        val s = withTimeout(5_000) { vm.state.first { it.feed.size == 2 } }
        assertEquals(listOf("n1", "a1"), s.feed.map { it.id })
        assertEquals("Glasses offline", s.status.captureText)
    }

    @Test fun askSuccessClearsTheQuestionAndShowsTheAnswerInline() = runBlocking {
        outcome = { q -> graph.asks.upsert(Fixtures.ask("a9", Fixtures.NOW, question = q, answer = "On the shelf.")); AskOutcome.Answered("a9") }
        vm.onQuestionChange("where are my keys")
        vm.ask()
        val s = withTimeout(5_000) { vm.state.first { it.lastAnswer?.id == "a9" } }
        assertEquals("", s.question)
        assertFalse(s.asking)
        assertEquals("On the shelf.", s.lastAnswer?.answer)
        assertEquals(listOf("where are my keys"), questions)
    }

    @Test fun askFailureShowsTheServiceMessageAndBlankIsIgnored() = runBlocking {
        vm.ask("   ")
        assertTrue(questions.isEmpty())
        vm.ask("milk")
        val s = withTimeout(5_000) { vm.state.first { it.askError != null } }
        assertEquals("AI unavailable — check Settings › Model endpoints", s.askError)
        assertEquals("milk", s.question)
    }

    @Test fun pauseAndBannerActionsReachTheirTargets() = runBlocking {
        vm.togglePause()
        assertFalse(pipeline.enabled.value)
        assertTrue(withTimeout(5_000) { vm.state.first { it.status.paused } }.status.paused)
        vm.onBannerAction(BannerAction.RESUME)
        assertTrue(pipeline.enabled.value)
        vm.onBannerAction(BannerAction.RECONNECT)
        assertEquals(1, glasses.reconnectRequests)
        assertEquals(LinkState.OFFLINE, glasses.status.value.link)
    }
}
