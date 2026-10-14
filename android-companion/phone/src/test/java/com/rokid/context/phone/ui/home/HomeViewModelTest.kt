package com.rokid.context.phone.ui.home

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.PauseOrigin
import com.rokid.context.core.model.PauseRecord
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
import com.rokid.context.voice.PhoneVoiceState
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNull
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

    @Test fun photosTodayUpdatesLive() = runBlocking {
        graph.frames.add(Fixtures.frame("y", Fixtures.at(Fixtures.TODAY.minusDays(1), 23, 50))) // yesterday: not counted
        graph.frames.add(Fixtures.frame("t1", Fixtures.at(Fixtures.TODAY, 0, 5)))
        assertEquals(1, withTimeout(5_000) { vm.state.first { it.photosToday == 1 } }.photosToday)
        graph.frames.add(Fixtures.frame("t2", Fixtures.at(Fixtures.TODAY, 9, 55)))
        assertEquals(2, withTimeout(5_000) { vm.state.first { it.photosToday == 2 } }.photosToday)
    }

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

    /** Final review I1: hub Paused frame + pipeline on → "Paused on glasses"; the pipeline is left on. */
    @Test fun hubPauseWithThePipelineOnIsPausedOnGlasses() = runBlocking {
        glasses.emit(FakeGlassesStatusSource.connected(Fixtures.stateFrame(capture = CaptureState.Paused(listOf(PauseRecord("g", PauseOrigin.GLASSES, 1))))))
        val s = withTimeout(5_000) { vm.state.first { it.status.pausedOnGlasses } }.status
        assertEquals("Paused on glasses", s.captureText)
        assertTrue(s.pipelineEnabled)
        assertTrue(pipeline.enabled.value)
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

    @Test fun cancellingAnAskResetsTheAskingFlag() = runBlocking {
        outcome = { throw kotlinx.coroutines.CancellationException("stopped") }
        vm.ask("milk")
        val s = withTimeout(5_000) { vm.state.first { !it.asking && it.question == "milk" } }
        assertFalse(s.asking)
        assertEquals(listOf("milk"), questions)
    }

    /** Phase 4 carry: two quick taps are a pause and a resume, even before the first one is persisted. */
    @Test fun aQuickPauseThenResumeIsTwoRequests() {
        val slow = FakePipelineControl(publishAtOnce = false)
        val v = HomeViewModel(glasses, slow, graph.timeline, graph.asks, graph.nudges, AskService { AskOutcome.Failed("x") },
            clock = flowOf(Fixtures.NOW), storagePct = flowOf(10), zone = { Fixtures.ZONE })
        try {
            v.togglePause(); v.togglePause()
            assertEquals(listOf(false, true), slow.requests)
        } finally { v.cancelScope() }
    }

    private fun voiceVm(voice: FakePhoneVoice, pipeline: FakePipelineControl = this.pipeline) = HomeViewModel(
        glasses, pipeline, graph.timeline, graph.asks, graph.nudges, AskService { AskOutcome.Failed("x") },
        clock = flowOf(Fixtures.NOW), storagePct = flowOf(10), zone = { Fixtures.ZONE }, voice = voice,
    )

    @Test fun theMicStartsThenSendsAndAVoiceAnswerShowsInline() = runBlocking {
        val voice = FakePhoneVoice()
        val v = voiceVm(voice)
        try {
            assertTrue(withTimeout(5_000) { v.state.first { it.voice.enabled } }.voice.enabled)
            v.onMic()
            assertTrue(withTimeout(5_000) { v.state.first { it.voice.listening } }.voice.listening)
            v.onMic()
            assertEquals(listOf("start", "stop"), voice.calls)
            graph.asks.upsert(Fixtures.ask("a7", Fixtures.NOW, question = "where are my keys", answer = "On the shelf."))
            voice.state.value = voice.state.value.copy(thinking = false, lastAskId = "a7")
            assertEquals("On the shelf.", withTimeout(5_000) { v.state.first { it.lastAnswer?.id == "a7" } }.lastAnswer?.answer)
        } finally { v.cancelScope() }
    }

    /** Ask is a user action: voice works while the pipeline is paused. */
    @Test fun phoneVoiceWorksWhileThePipelineIsPaused() = runBlocking {
        val voice = FakePhoneVoice()
        val v = voiceVm(voice, FakePipelineControl(initial = false))
        try {
            withTimeout(5_000) { v.state.first { it.voice.enabled } }
            v.onMic()
            assertEquals(listOf("start"), voice.calls)
        } finally { v.cancelScope() }
    }

    /** Fix 1 #1: leaving Home cancels a phone ask still being spoken... */
    @Test fun leavingHomeCancelsAListen() = runBlocking {
        val voice = FakePhoneVoice()
        val v = voiceVm(voice)
        try {
            v.onMic(); v.onLeave()
            assertEquals(listOf("start", "cancel"), voice.calls)
        } finally { v.cancelScope() }
    }

    /** ...but one already thinking keeps going: its answer is saved and spoken as usual. */
    @Test fun leavingHomeLetsAThinkingAskFinish() = runBlocking {
        val voice = FakePhoneVoice(PhoneVoiceState(status = VoiceStatus.SUPPORTED, thinking = true))
        val v = voiceVm(voice)
        try {
            v.onLeave()
            assertEquals(emptyList<String>(), voice.calls)
        } finally { v.cancelScope() }
    }

    /** Fix 1 #3: while thinking the mic cancels. */
    @Test fun theMicCancelsWhileThinking() = runBlocking {
        val voice = FakePhoneVoice(PhoneVoiceState(status = VoiceStatus.SUPPORTED, thinking = true))
        val v = voiceVm(voice)
        try { v.onMic(); assertEquals(listOf("cancel"), voice.calls) } finally { v.cancelScope() }
    }

    /** Fix 1 #6: no ask starts while the mic is off, while settings load, or without the permission. */
    @Test fun theMicStartsNothingWhenOffLoadingOrWithoutPermission() = runBlocking {
        for (s in listOf(
            PhoneVoiceState(status = VoiceStatus.SUPPORTED, micOff = true),
            PhoneVoiceState(status = VoiceStatus.SUPPORTED, micOff = true, settingsLoading = true),
            PhoneVoiceState(status = VoiceStatus.NEEDS_MIC_PERMISSION),
        )) {
            val voice = FakePhoneVoice(s)
            val v = voiceVm(voice)
            try { v.onMic(); assertEquals("for $s", emptyList<String>(), voice.calls) } finally { v.cancelScope() }
        }
    }

    /** Fix 1 #4: a permanent denial shows "Open app settings" until the permission is back. */
    @Test fun aPermanentDenialBlocksUntilThePermissionIsBack() = runBlocking {
        val voice = FakePhoneVoice(PhoneVoiceState(status = VoiceStatus.NEEDS_MIC_PERMISSION))
        val v = voiceVm(voice)
        try {
            v.onMicDenied(permanent = true)
            assertTrue(withTimeout(5_000) { v.state.first { it.voice.blocked } }.voice.blocked)
            voice.state.value = PhoneVoiceState(status = VoiceStatus.SUPPORTED)
            withTimeout(5_000) { v.state.first { it.voice.enabled && !it.voice.needsPermission } }
            voice.state.value = PhoneVoiceState(status = VoiceStatus.NEEDS_MIC_PERMISSION)
            assertFalse(withTimeout(5_000) { v.state.first { it.voice.needsPermission } }.voice.blocked)
        } finally { v.cancelScope() }
    }

    /** Fix 1 #5: an answer from before this screen opened never shows on re-entry. */
    @Test fun anOldVoiceAnswerIsNotShownOnReentry() = runBlocking {
        graph.asks.upsert(Fixtures.ask("old", Fixtures.NOW, answer = "Old answer."))
        val voice = FakePhoneVoice(PhoneVoiceState(status = VoiceStatus.SUPPORTED, lastAskId = "old"))
        val v = voiceVm(voice)
        try {
            assertNull(withTimeoutOrNull(1_500) { v.state.first { it.lastAnswer != null } })
        } finally { v.cancelScope() }
    }
}
