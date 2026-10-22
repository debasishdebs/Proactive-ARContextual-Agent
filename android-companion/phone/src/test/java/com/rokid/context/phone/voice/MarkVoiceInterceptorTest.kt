package com.rokid.context.phone.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.marks.MarkArrival
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.core.model.MarkMessage
import com.rokid.context.core.model.Overlay
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.memory.db.CanvasSlice3Dao
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DatabaseFactory
import com.rokid.context.memory.db.DeletionEpoch
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
import com.rokid.context.voice.VoiceSource
import com.rokid.context.voice.VoiceState
import com.rokid.context.voice.VoiceStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Real VoicePipeline + real MarkVoiceInterceptor + real MarkRecorder on an in-memory DB; only the recognizer and the answer service are spies. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MarkVoiceInterceptorTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val epoch = DeletionEpoch("inst", 0, 0)
    private lateinit var dao: CanvasSlice3Dao
    private val asked = ArrayList<String>()
    private val logs = ArrayList<String>()
    private var listener: SttListener? = null
    private val t0 = 1_000_000L
    private var now = t0

    @Before fun setUp() { MemoryGraph.resetForTests(DatabaseFactory.inMemory(ctx)); dao = MemoryGraph.get(ctx).db.canvasSlice3Dao() }
    @After fun tearDown() { MemoryGraph.get(ctx).db.close(); MemoryGraph.resetForTests(null) }

    private val engine = object : SpeechEngine {
        private fun s() = object : SttSession { override fun feed(pcm: ByteArray) = Unit; override fun end() = Unit; override fun cancel() = Unit }
        override fun startExternal(localeTag: String, listener: SttListener): SttSession { this@MarkVoiceInterceptorTest.listener = listener; return s() }
        override fun startPhoneMic(localeTag: String, listener: SttListener): SttSession { this@MarkVoiceInterceptorTest.listener = listener; return s() }
    }
    private val speaker = object : Speaker { override fun speak(id: String, text: String, onDone: () -> Unit) = false; override fun stop() = Unit }
    private val caps = MutableStateFlow(VoiceCapabilities(VoiceStatus.SUPPORTED, VoiceStatus.SUPPORTED, true))

    private class Rig(val recorder: MarkRecorder, val pipeline: VoicePipeline)
    private fun TestScope.rig(): Rig {
        val rec = MarkRecorder(dao, { DaoFence(0, epoch, { 0 }, { epoch }) }, { now }, { logs += it })
        val interceptor = MarkVoiceInterceptor({ rec }, backgroundScope, { now }, { logs += it })
        val p = VoicePipeline(
            backgroundScope, engine, caps, { SpeechLanguage.ENGLISH }, Answerer { q, _ -> asked += q; AnswerResult.Answered("a1", "ok") }, speaker, { false },
            log = { logs += it }, interceptor = interceptor,
        )
        runCurrent()
        return Rig(rec, p)
    }

    private suspend fun TestScope.listenAndSay(r: Rig, text: String) {
        r.recorder.onListenStart(now)
        r.pipeline.glassesListenStart(); runCurrent()
        listener!!.onFinal(SttResult.Text(text)); runCurrent()
        overlayRightAfter = r.pipeline.overlay.value // before any wait on the DB: runTest skips virtual time while the test thread waits
        r.recorder.awaitIdle()
    }
    private var overlayRightAfter: Overlay? = null

    @Test fun realPipelineMarkMakesNoAnswerCall() = runTest {
        val r = rig()
        listenAndSay(r, "mark that, I'm driving")
        assertTrue("a spy AnswerService gets zero calls", asked.isEmpty())
        assertEquals(Overlay.Toast("marked · driving"), overlayRightAfter)
        assertEquals(setOf("MOMENT", "INTENT"), dao.labels().map { it.kind }.toSet())
        assertEquals("driving", dao.labels().first { it.kind == "INTENT" }.name)
        advanceTimeBy(4_001)
        assertEquals(VoiceState.Idle, r.pipeline.state.value)
    }

    @Test fun plainMarkShowsCheck() = runTest {
        val r = rig()
        listenAndSay(r, "mark that")
        assertEquals(Overlay.Toast("✓ marked"), overlayRightAfter)
        assertTrue(asked.isEmpty())
    }

    @Test fun onlyUnmatchedClauseReachesAsk() = runTest {
        val r = rig()
        listenAndSay(r, "mark that and what's this building")
        assertEquals(listOf("what's this building"), asked)
        assertEquals(1, dao.labels().count { it.kind == "MOMENT" })
    }

    @Test fun anOrdinaryQuestionPassesThroughUnchangedAndMakesNoLabel() = runTest {
        val r = rig()
        listenAndSay(r, "what is on my list")
        assertEquals(listOf("what is on my list"), asked)
        assertTrue(dao.labels().isEmpty())
    }

    @Test fun aTapMarkThenAnOrdinaryQuestionBecomesAskTap() = runTest {
        val r = rig()
        r.recorder.onMark(MarkMessage(epoch = "e", seq = 1, atMs = 1, atElapsedMs = 1, listen = true), MarkArrival(now, true, now))
        listenAndSay(r, "where did I park")
        assertEquals(listOf("where did I park"), asked)
        assertEquals("ask_tap", dao.labels().single().source)
    }

    @Test fun aTimeoutPartialIsNeverACommand() = runTest {
        val r = rig()
        r.recorder.onListenStart(now)
        r.pipeline.glassesListenStart(); runCurrent()
        listener!!.onPartial("mark that")
        r.pipeline.glassesListenEnd()
        advanceTimeBy(6_001); runCurrent()
        r.recorder.awaitIdle()
        assertTrue(dao.labels().isEmpty())
        assertEquals("not genuine: it goes on as an ordinary ask", listOf("mark that"), asked)
    }

    @Test fun forgetNeverMatches() = runTest {
        val r = rig()
        listenAndSay(r, "forget that")
        assertEquals(listOf("forget that"), asked)
        assertTrue(dao.labels().isEmpty())
    }

    @Test fun overFiveHundredCharactersIsNeverACommand() = runTest {
        val r = rig()
        val long = "mark that " + "word ".repeat(100)
        listenAndSay(r, long)
        assertTrue(dao.labels().isEmpty())
        assertEquals(1, asked.size)
    }

    @Test fun undoVoiceCommandDeletesTheTapMarkOfThisListen() = runTest {
        val r = rig()
        r.recorder.onMark(MarkMessage(epoch = "e", seq = 1, atMs = 1, atElapsedMs = 1, listen = true), MarkArrival(now, true, now))
        listenAndSay(r, "undo mark")
        assertTrue(dao.labels().isEmpty())
        assertEquals(Overlay.Toast("mark undone"), overlayRightAfter)
        assertTrue(asked.isEmpty())
    }

    @Test fun transcriptNeverPersistedOrLogged() = runTest {
        val r = rig()
        val said = "mark that and what's this building nearby, I'm driving to the zebra"
        listenAndSay(r, said)
        val stored = dao.labels().joinToString(" ") { "${it.labelId} ${it.kind} ${it.source} ${it.name} ${it.provenance}" }
        val seen = (stored + " " + logs.joinToString(" ") + " " + (r.pipeline.overlay.value as? Overlay.Toast)?.text.orEmpty()).lowercase()
        listOf("building", "nearby", "zebra", "what's", "this").forEach { assertFalse("'$it' leaked: $seen", it in seen.split(Regex("[^a-z']+"))) }
    }
}
