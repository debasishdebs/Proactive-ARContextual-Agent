package com.rokid.context.phone.voice

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.memory.db.DaoFence
import com.rokid.context.memory.db.DeletionEpoch
import com.rokid.context.memory.MemoryGraph
import com.rokid.context.orchestration.AskOutcome
import com.rokid.context.phone.ui.FakePipelineControl
import com.rokid.context.phone.ui.Fixtures
import com.rokid.context.phone.ui.MainDispatcherRule
import com.rokid.context.phone.ui.TestGraph
import com.rokid.context.phone.ui.cancelScope
import com.rokid.context.phone.ui.glasses.FakeGlassesStatusSource
import com.rokid.context.phone.ui.home.AskService
import com.rokid.context.phone.ui.home.HomeViewModel
import com.rokid.context.voice.InterceptResult
import com.rokid.context.voice.Utterance
import com.rokid.context.voice.VoiceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * v0.9.7 slice D, the acceptance of "one backend, different interfaces": the SAME table of utterances goes through (a) the voice seam (what the VoicePipeline calls for phone and glasses voice) and
 * (b) the Home chat window (the real [HomeViewModel]), over the same real recorder and database, and every row ends the same: the same reply or the same text that goes on to Ask, and the same
 * stored marks. The source scan fails if a surface grows command parsing of its own.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class UtteranceSurfacesTest {
    @get:Rule val main = MainDispatcherRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val epoch = DeletionEpoch("inst", 0, 0)
    private lateinit var graph: MemoryGraph
    private lateinit var rec: MarkRecorder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val now = 1_761_000_000_000L
    private val table = listOf("mark this moment", "mark this as important", "mark that, I'm driving", "undo", "undo mark", "undo the mark", "what is this", "mark this moment and what is this")

    @Before fun setUp() {
        graph = TestGraph.fresh(ctx)
        rec = MarkRecorder(graph.db.canvasSlice3Dao(), { DaoFence(0, epoch, { 0 }, { epoch }) }, { now }, phonePhoto = object : com.rokid.context.canvas.marks.PhonePhotoRequester {
            override fun reasonNow(): String? = null
            override suspend fun request(markRef: String) = true
        })
    }
    @After fun tearDown() { TestGraph.close(ctx) }

    private data class Outcome(val reply: String?, val asked: String?, val marks: List<String>)

    private fun utterance() = Utterance(MarkUtteranceCommands({ rec }, scope), { now })

    private suspend fun marks() = graph.db.canvasSlice3Dao().labels().map { "${it.kind}:${it.name}:${it.photo}" }.sorted()
    private suspend fun reset() { rec.awaitIdle(); graph.db.canvasSlice3Dao().labels().forEach { graph.db.canvasSlice3Dao().deleteLabel(it.labelId) } }

    private suspend fun viaVoice(text: String, source: VoiceSource): Outcome {
        val r = utterance().asInterceptor().intercept(text, source, true)
        rec.awaitIdle()
        return when (r) {
            InterceptResult.PassThrough -> Outcome(null, text, marks())
            is InterceptResult.Handled -> Outcome(r.hud, null, marks())
            is InterceptResult.Ask -> Outcome(null, r.question, marks())
        }
    }

    private suspend fun viaChat(text: String): Outcome {
        val asked = ArrayList<String>()
        val vm = HomeViewModel(FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges, AskService { q -> asked += q; AskOutcome.Failed("x") },
            clock = flowOf(Fixtures.NOW), storagePct = flowOf(10), zone = { Fixtures.ZONE }, utterance = utterance())
        try {
            vm.ask(text)
            val s = withTimeout(5_000) { vm.state.first { !it.asking && (it.reply != null || it.askError != null) } }
            rec.awaitIdle()
            return Outcome(s.reply, asked.singleOrNull(), marks())
        } finally { vm.cancelScope() }
    }

    @Test fun theChatPathAndBothVoicePathsAnswerEveryRowTheSame() = runBlocking<Unit> {
        for (text in table) {
            val chat = viaChat(text); reset()
            val phone = viaVoice(text, VoiceSource.PHONE); reset()
            val glasses = viaVoice(text, VoiceSource.GLASSES); reset()
            assertEquals("'$text': chat vs phone voice", phone, chat)
            assertEquals("'$text': chat vs glasses voice (the glasses keep their own tap-mark join, the reply and the ask text are the same)", glasses.copy(marks = chat.marks), chat)
        }
    }

    @Test fun theTableHasTheExpectedAnswers() = runBlocking<Unit> {
        val expect = mapOf(
            "mark this moment" to Outcome(Utterance.MARKED, null, listOf("MOMENT:MOMENT:pending")),
            "mark this as important" to Outcome(Utterance.MARKED, null, listOf("MOMENT:MOMENT:pending")), // "important" is not an intent word: a plain mark everywhere
            "mark that, I'm driving" to Outcome("marked \u00b7 driving", null, listOf("INTENT:driving:pending", "MOMENT:MOMENT:pending")),
            "undo" to Outcome(null, "undo", emptyList()), // the bare word is not in the lexicon (S85): it is a question everywhere
            "undo mark" to Outcome(Utterance.UNDONE, null, emptyList()),
            "undo the mark" to Outcome(Utterance.UNDONE, null, emptyList()),
            "what is this" to Outcome(null, "what is this", emptyList()),
            "mark this moment and what is this" to Outcome(null, "what is this", listOf("MOMENT:MOMENT:pending")),
        )
        for ((text, want) in expect) { assertEquals("chat '$text'", want, viaChat(text)); reset() }
    }

    @Test fun markThenUndoFromTheChatLeavesNothing() = runBlocking<Unit> {
        viaChat("mark this moment"); assertEquals(1, marks().size)
        assertEquals(Utterance.UNDONE, viaChat("undo mark").reply)
        assertTrue(marks().isEmpty())
    }

    /**
     * Review fix: an undo is awaited and tells the truth. Here the gate refuses the removal (the recorder's photo forget throws what a delete does while an export holds the gate): nothing is deleted
     * and nothing is confirmed, on every surface, and the same words work once it is free.
     */
    @Test fun anUndoRefusedByTheGateIsReportedNotConfirmedAndNothingIsLostOnEverySurface() = runBlocking<Unit> {
        val busy = "Couldn't undo: an export is running. Try again in a minute."
        for (surface in listOf("chat", "phone", "glasses")) {
            val at = now + 1_000L * surface.length
            rec = MarkRecorder(graph.db.canvasSlice3Dao(), { DaoFence(0, epoch, { 0 }, { epoch }) }, { at }, forgetPhoto = { if (exportRuns) throw com.rokid.context.orchestration.DataOpGate.ExportInProgress() else true },
                phonePhoto = object : com.rokid.context.canvas.marks.PhonePhotoRequester { override fun reasonNow(): String? = null; override suspend fun request(markRef: String) = true })
            exportRuns = false
            val u = Utterance(MarkUtteranceCommands({ rec }, scope), { at })
            u.asInterceptor().intercept("mark this moment", VoiceSource.PHONE, true); rec.awaitIdle()
            val id = graph.db.canvasSlice3Dao().labels().single().labelId
            val meta = com.rokid.context.memory.db.FrameMetaEntity("p$at", "w_p$at", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = at + 500, markRef = id.removePrefix("mk:"))
            com.rokid.context.memory.FrameIntake(graph.db).commit(com.rokid.context.memory.db.FrameEntity("p$at", at + 500, "/p/p$at.jpg", "rokid_glasses"), com.rokid.context.memory.FrameRows(meta, com.rokid.context.memory.db.FrameStateEntity("p$at", "PENDING", 0, null, null, null, 1)))
            val before = marks()
            exportRuns = true
            val reply = when (surface) {
                "chat" -> viaChatWith(u, "undo the mark").reply
                "phone" -> (u.asInterceptor().intercept("undo the mark", VoiceSource.PHONE, true) as InterceptResult.Handled).hud
                else -> (u.asInterceptor().intercept("undo the mark", VoiceSource.GLASSES, true) as InterceptResult.Handled).hud
            }
            assertEquals("$surface: the failure is the reply", busy, reply)
            assertEquals("$surface: the mark and its rows stay", before, marks())
            exportRuns = false
            assertEquals("$surface: the same words work once it is free", Utterance.UNDONE, viaChatWith(u, "undo the mark").reply)
            assertTrue(marks().isEmpty()); reset()
        }
    }
    private var exportRuns = false

    @Test fun anUndoThatFailsForAnyOtherReasonSaysSoAndLosesNothing() = runBlocking<Unit> {
        rec = MarkRecorder(graph.db.canvasSlice3Dao(), { DaoFence(0, epoch, { 0 }, { epoch }) }, { now }, forgetPhoto = { throw IllegalStateException("db") },
            phonePhoto = object : com.rokid.context.canvas.marks.PhonePhotoRequester { override fun reasonNow(): String? = null; override suspend fun request(markRef: String) = true })
        val u = Utterance(MarkUtteranceCommands({ rec }, scope), { now })
        u.asInterceptor().intercept("mark this moment", VoiceSource.PHONE, true); rec.awaitIdle()
        val id = graph.db.canvasSlice3Dao().labels().single().labelId
        val meta = com.rokid.context.memory.db.FrameMetaEntity("pf", "w_pf", null, "STILL", false, null, null, null, null, null, null, null, null, null, 1, capturedAtMs = now + 500, markRef = id.removePrefix("mk:"))
        com.rokid.context.memory.FrameIntake(graph.db).commit(com.rokid.context.memory.db.FrameEntity("pf", now + 500, "/p/pf.jpg", "rokid_glasses"), com.rokid.context.memory.FrameRows(meta, com.rokid.context.memory.db.FrameStateEntity("pf", "PENDING", 0, null, null, null, 1)))
        val before = marks()
        assertEquals(Utterance.COULDNT_UNDO, viaChatWith(u, "undo the mark").reply)
        assertEquals(before, marks())
    }

    private suspend fun viaChatWith(u: Utterance, text: String): Outcome {
        val vm = HomeViewModel(FakeGlassesStatusSource(), FakePipelineControl(), graph.timeline, graph.asks, graph.nudges, AskService { AskOutcome.Failed("x") },
            clock = flowOf(Fixtures.NOW), storagePct = flowOf(10), zone = { Fixtures.ZONE }, utterance = u)
        try {
            vm.ask(text)
            val s = withTimeout(5_000) { vm.state.first { !it.asking && (it.reply != null || it.askError != null) } }
            return Outcome(s.reply, null, marks())
        } finally { vm.cancelScope() }
    }

    @Test fun noSurfaceHasCommandParsingOfItsOwn() {
        val root = File("..")
        fun src(p: String) = File(root, p).readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.replace(Regex("(^|\\s)//.*$"), "") }
        val surfaces = listOf(
            "phone/src/main/java/com/rokid/context/phone/ui/home/HomeViewModel.kt",
            "phone/src/main/java/com/rokid/context/phone/ui/home/AskService.kt",
            "services/voice/src/main/kotlin/com/rokid/context/voice/VoicePipeline.kt",
        )
        val forbidden = listOf("MarkCommandParser", "MarkCommand", "parseUtterance", "MarkPhrases", "MarkRecorder", "VoiceMark", "EnglishClauses", "startsWith(\"mark", "contains(\"mark", "\"undo\"")
        for (p in surfaces) for (w in forbidden) assertTrue("$p must not contain command logic ($w)", !src(p).contains(w))
        assertTrue("the chat calls the shared backend", src(surfaces[0]).contains("utterance?.handle(") && src(surfaces[0]).contains("UtteranceSource.PHONE_CHAT"))
        assertTrue("the pipeline goes through its interceptor seam", src(surfaces[2]).contains("interceptor.intercept("))
        // the parser is reached from ONE place outside its own package: the shared entry
        val users = listOf("glasses", "phone", "core", "services").flatMap { m ->
            File(root, m).walkTopDown().onEnter { it.name != "build" && it.name != "cpp" && it.name != "test" && it.name != "androidTest" }
                .filter { it.isFile && it.extension == "kt" && it.path.replace('\\', '/').contains("/src/main/") && !it.path.contains("/voice/commands/") && it.readText().contains("MarkCommandParser") }
                .map { it.name }.toList()
        }
        assertEquals("only Utterance parses commands", listOf("Utterance.kt"), users)
    }
}
