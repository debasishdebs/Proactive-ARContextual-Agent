package com.rokid.context.phone.ui.shell

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.core.ui.theme.PcTheme
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.onboarding.OnboardingStore
import com.rokid.context.phone.ui.settings.DiagnosticsActions
import com.rokid.context.phone.ui.settings.QueueActions
import com.rokid.context.phone.voice.PipelinePhoneVoice
import com.rokid.context.ui.queues.QueueSnapshot
import com.rokid.context.voice.AnswerResult
import com.rokid.context.voice.Answerer
import com.rokid.context.voice.SpeechEngine
import com.rokid.context.voice.SpeechLanguage
import com.rokid.context.voice.Speaker
import com.rokid.context.voice.SttListener
import com.rokid.context.voice.SttResult
import com.rokid.context.voice.SttSession
import com.rokid.context.voice.VoiceCapabilities
import com.rokid.context.voice.VoicePipeline
import com.rokid.context.voice.VoiceSettings
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device bug (devfix-cancel): back on Home while the phone ask was LISTENING moved the task to the background, the
 * recognizer kept going, and the ask was answered and spoken. Leaving the foreground (Home's lifecycle ON_STOP)
 * cancels a LISTENING phone ask; a THINKING one still completes. Real [VoicePipeline] + [PipelinePhoneVoice].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeForegroundTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var nav: NavHostController

    private var listener: SttListener? = null
    private val sessionCancels = AtomicInteger()
    private val engine = object : SpeechEngine {
        private fun session() = object : SttSession { override fun feed(pcm: ByteArray) = Unit; override fun end() = Unit; override fun cancel() { sessionCancels.incrementAndGet() } }
        override fun startExternal(localeTag: String, listener: SttListener): SttSession { this@HomeForegroundTest.listener = listener; return session() }
        override fun startPhoneMic(localeTag: String, listener: SttListener): SttSession { this@HomeForegroundTest.listener = listener; return session() }
    }
    private val spoken = AtomicInteger()
    private val speaker = object : Speaker { override fun speak(id: String, text: String, onDone: () -> Unit): Boolean { spoken.incrementAndGet(); return false }; override fun stop() = Unit }
    private val answered = AtomicInteger()
    private var answer: suspend () -> AnswerResult = { AnswerResult.Answered("a1", "On the shelf.") }
    private lateinit var pipeline: VoicePipeline
    private lateinit var phone: PipelinePhoneVoice

    @Before fun setUp() {
        MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx))
        val caps = MutableStateFlow(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, true))
        pipeline = VoicePipeline(scope, engine, caps, { SpeechLanguage.ENGLISH }, Answerer { _, _ -> answered.incrementAndGet(); answer() }, speaker, { false })
        phone = PipelinePhoneVoice(pipeline, MutableStateFlow<VoiceSettings?>(VoiceSettings(phoneMic = true)), scope)
        val deps = PhoneDeps(
            memory = MemoryGraph.get(ctx), glasses = FakeGlassesStatusSource(), pipeline = FakePipelineControl(), onboarding = OnboardingStore(ctx),
            askService = AskService { AskOutcome.Failed("offline in tests") }, rateAsk = { _, _ -> true },
            diagnostics = object : DiagnosticsActions {
                override suspend fun summarizeNow() = "ok"; override suspend fun captureNow() = "ok"; override suspend fun reprocessOldPhotos() = "ok"
            },
            queues = object : QueueActions {
                override suspend fun snapshot() = QueueSnapshot(0, 0, 0, 0, 0, 24, 0, 0, 0, "none")
                override suspend fun queueExplicitWrite(text: String) = "ok"; override suspend fun queueReadContext(query: String) = "ok"; override suspend fun drainNow() = "ok"
            },
            settings = com.rokid.context.phone.ui.settings.TestSettings.deps(ctx),
            storagePct = flowOf(10), clock = flowOf(Fixtures.NOW), zone = { Fixtures.ZONE },
            voice = phone,
        )
        compose.setContent { nav = rememberNavController(); PcTheme { AppShell(deps, Routes.HOME, nav) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Ask about your day").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() { scope.cancel(); MemoryGraph.resetForTests(null) }

    /** The phone mic is listening to "What..." (the device repro). */
    private fun listen() {
        compose.runOnIdle { phone.start() }
        compose.runOnIdle { listener!!.onPartial("What") }
        compose.runOnIdle { assertTrue(phone.state.value.listening) }
    }

    /** The system stops the activity (back on the root, home, app switch, screen off). */
    private fun stopActivity() = compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)

    @Test fun onStopWhileListeningCancelsTheAskSoNothingIsAnsweredOrSpoken() {
        listen()
        stopActivity()
        assertFalse(phone.state.value.listening)
        assertEquals(1, sessionCancels.get())
        listener!!.onFinal(SttResult.Text("What did I see")) // a late final from the recognizer is ignored
        compose.runOnIdle { assertEquals(0, answered.get()); assertEquals(0, spoken.get()) }
    }

    @Test fun onStopWhileThinkingLetsTheAskFinish() {
        val reply = CompletableDeferred<AnswerResult>()
        answer = { reply.await() }
        listen()
        compose.runOnIdle { listener!!.onFinal(SttResult.Text("What did I see")) }
        compose.runOnIdle { assertTrue(phone.state.value.thinking) }
        stopActivity()
        assertEquals(0, sessionCancels.get())
        reply.complete(AnswerResult.Answered("a1", "On the shelf."))
        compose.runOnIdle { assertEquals(1, answered.get()); assertEquals(1, spoken.get()) }
    }

    /** The device path: back on the root Home entry backgrounds the task (it is not finished), then the system stops it. */
    @Test fun backOnHomeWhileListeningCancelsTheAsk() {
        listen()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(Routes.HOME, nav.currentDestination?.route) } // nothing to pop: Home stays composed
        stopActivity()
        assertFalse(phone.state.value.listening)
        listener!!.onFinal(SttResult.Text("What did I see"))
        compose.runOnIdle { assertEquals(0, answered.get()); assertEquals(0, spoken.get()) }
    }
}
