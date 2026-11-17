package com.rokid.context.phone.nexus

import com.rokid.context.voice.AnswerResult
import com.rokid.context.voice.CaptureControl
import com.rokid.context.voice.CaptureResult
import com.rokid.context.voice.UtteranceSource
import io.github.pca.nexusport.AskRefusal
import io.github.pca.nexusport.DropReason
import io.github.pca.nexusport.HudEvent
import io.github.pca.nexusport.Outcome
import io.github.pca.nexusport.SkillCodes
import io.github.pca.nexusport.SkillOp
import io.github.pca.nexusport.SkillResult
import io.github.pca.nexusport.SkillState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.0 T5/T6 phone side: the ask handler and the skill provider, over fakes (no Android). */
@OptIn(ExperimentalCoroutinesApi::class)
class NexusAskSkillsTest {
    private var zone = false; private var askFence = false; private var paused = false; private var worn = true
    private val gates = PcaNexusGates({ true }, { zone }, { askFence }, { paused }, { worn })
    private val logs = mutableListOf<String>()
    private val shown = mutableListOf<HudEvent>()
    private val refusedNotices = mutableListOf<String>()
    private var answered = 0
    private var tts = false
    private var answer: AnswerResult = AnswerResult.Answered("ask1", "SECRET-ANSWER-TEXT", stored = true)
    private var zoneAppearsDuringAnswer = false
    private var sources: Set<String>? = setOf("m1")

    private fun TestScope.ask() = PcaNexusAsk(
        backgroundScope, gates,
        answerer = { _ -> answered++; if (zoneAppearsDuringAnswer) zone = true; answer },
        out = HudShow { e -> shown += e; Outcome.Shown(false) },
        askSources = { sources }, popupMs = { 6_000 }, speak = { tts }, log = { logs += it }, onRefusedNotice = { refusedNotices += it },
    )

    @Test fun anAskInAZoneWhilePausedOrNotWornIsRefusedBeforeTheBackendAndNothingIsDelivered() = runTest {
        val a = ask()
        zone = true; a.ask("a1", "SECRET-QUESTION"); zone = false
        askFence = true; a.ask("a2", "SECRET-QUESTION"); askFence = false
        paused = true; a.ask("a3", "SECRET-QUESTION"); paused = false
        worn = false; a.ask("a4", "SECRET-QUESTION"); worn = true
        runCurrent()
        assertEquals(0, answered); assertTrue(shown.isEmpty())
        assertEquals(listOf(AskRefusal.ZONE_PAUSED, AskRefusal.ZONE_PAUSED, AskRefusal.PAUSED, AskRefusal.NOT_WORN).map { it.text }, refusedNotices)
    }

    @Test fun aThrowingGateRefusesTheAsk() = runTest {
        val a = PcaNexusAsk(backgroundScope, PcaNexusGates({ true }, { throw IllegalStateException() }, { false }, { false }, { true }), { answered++; answer }, { shown += it; null }, { sources }, { 1 }, { false }, { logs += it })
        a.ask("a1", "q"); runCurrent()
        assertEquals(0, answered); assertTrue(shown.isEmpty())
    }

    @Test fun theAnswerGoesOnlyThroughTheSinkWithTheAskIdItsSourcesAndTheTtsSwitch() = runTest {
        val a = ask()
        a.ask("a1", "SECRET-QUESTION"); runCurrent()
        assertTrue("logs=$logs notices=$refusedNotices answered=$answered", shown.isNotEmpty())
        val e = shown.single() as HudEvent.AskAnswer
        assertEquals("a1", e.ref); assertEquals("SECRET-ANSWER-TEXT", e.answer); assertEquals(setOf("m1"), e.sources); assertFalse(e.speak); assertEquals(6_000, e.ttlMs)
        tts = true; a.ask("a2", "q"); runCurrent()
        assertTrue((shown.last() as HudEvent.AskAnswer).speak)
    }

    @Test fun aZoneThatStartsWhileTheModelWorksDeliversNothing() = runTest {
        zoneAppearsDuringAnswer = true
        ask().ask("a1", "q"); runCurrent()
        assertEquals(1, answered); assertTrue(shown.isEmpty())
        assertEquals(listOf(AskRefusal.ZONE_PAUSED.text), refusedNotices)
    }

    @Test fun anUnreadableStoredAskIsNotDeliveredAndAFailureShowsFixedCopyOnly() = runTest {
        sources = null; ask().ask("a1", "q"); runCurrent(); assertTrue(shown.isEmpty())
        sources = setOf("m1"); answer = AnswerResult.Answered("a", "off notice", stored = false); ask().ask("a2", "q"); runCurrent(); assertTrue(shown.isEmpty())
        answer = AnswerResult.Failed("AI unavailable", "phone text"); ask().ask("a3", "q"); runCurrent()
        val e = shown.single() as HudEvent.AskAnswer
        assertEquals("AI unavailable", e.answer); assertTrue(e.sources.isEmpty()); assertFalse(e.speak)
    }

    @Test fun theSinkRefusingAMidAskCancelsTheWorkAndNothingIsDelivered() = runTest {
        val a = ask()
        a.ask("a1", "q"); a.refused("a1", AskRefusal.CANCELLED); runCurrent()
        assertTrue(shown.isEmpty())
    }

    @Test fun noTranscriptOrAnswerTextReachesTheLogs() = runTest {
        val a = ask()
        a.ask("a1", "SECRET-QUESTION"); zone = true; a.ask("a2", "SECRET-QUESTION"); zone = false; answer = AnswerResult.Failed("x", "y")
        a.ask("a3", "SECRET-QUESTION"); runCurrent()
        assertTrue(logs.none { "SECRET" in it })
    }

    // ---- skills ----

    private var userPaused = false; private var zonePaused = false; private var stopped = false
    private var marks = 0; private var resumed = 0; private var pausedCalls = 0
    private var resumeResult: CaptureResult = CaptureResult.Done
    private val capture = object : CaptureControl {
        override suspend fun pause(source: UtteranceSource): CaptureResult { pausedCalls++; userPaused = true; return CaptureResult.Done }
        override suspend fun resume(source: UtteranceSource): CaptureResult { resumed++; userPaused = false; return resumeResult }
    }
    private fun skills() = PcaNexusSkills(gates, capture, { zonePaused }, { userPaused || zonePaused }, { stopped }, { marks++ }, { logs += it })

    @Test fun statusReportsTheFourStatesAndNeverAnythingElse() = runTest {
        val s = skills()
        assertEquals(SkillResult.Done(SkillState.CAPTURING), s.invoke(SkillOp.STATUS))
        userPaused = true; assertEquals(SkillResult.Done(SkillState.PAUSED), s.invoke(SkillOp.STATUS))
        zonePaused = true; assertEquals(SkillResult.Done(SkillState.ZONE_PAUSED), s.invoke(SkillOp.STATUS))
        stopped = true; assertEquals(SkillResult.Done(SkillState.STOPPED), s.invoke(SkillOp.STATUS))
    }

    @Test fun pauseCaptureIsANormalUserPause() = runTest {
        val r = skills().invoke(SkillOp.PAUSE_CAPTURE)
        assertEquals(1, pausedCalls); assertEquals(SkillResult.Done(SkillState.PAUSED), r)
    }

    @Test fun resumeRefusesDuringAZonePauseAndNeverReachesTheControl() = runTest {
        val s = skills()
        zonePaused = true; assertEquals(SkillResult.Refused(SkillCodes.PAUSE_ZONE), s.invoke(SkillOp.RESUME_CAPTURE))
        zonePaused = false; zone = true; assertEquals(SkillResult.Refused(SkillCodes.PAUSE_ZONE), s.invoke(SkillOp.RESUME_CAPTURE))
        zone = false; askFence = true; assertEquals(SkillResult.Refused(SkillCodes.PAUSE_ZONE), s.invoke(SkillOp.RESUME_CAPTURE))
        assertEquals(0, resumed)
    }

    @Test fun resumeEndsAUserPauseOnly() = runTest {
        userPaused = true
        assertEquals(SkillResult.Done(SkillState.CAPTURING), skills().invoke(SkillOp.RESUME_CAPTURE)); assertEquals(1, resumed)
        resumeResult = CaptureResult.Said(com.rokid.context.phone.voice.HubCaptureControl.ZONE_REFUSAL)
        assertEquals(SkillResult.Refused(SkillCodes.PAUSE_ZONE), skills().invoke(SkillOp.RESUME_CAPTURE))
        resumeResult = CaptureResult.Failed
        assertEquals(SkillResult.Refused(SkillCodes.FAILED), skills().invoke(SkillOp.RESUME_CAPTURE))
    }

    @Test fun markMomentUsesTheMarkPathAndKeepsItsRefusals() = runTest {
        val s = skills()
        assertEquals(SkillResult.Done(SkillState.CAPTURING), s.invoke(SkillOp.MARK_MOMENT)); assertEquals(1, marks)
        zone = true; assertEquals(SkillResult.Refused(SkillCodes.PAUSE_ZONE), s.invoke(SkillOp.MARK_MOMENT)); zone = false
        worn = false; assertEquals(SkillResult.Refused(SkillCodes.NOT_WORN), s.invoke(SkillOp.MARK_MOMENT)); worn = true
        userPaused = true; assertEquals(SkillResult.Refused(SkillCodes.PAUSED), s.invoke(SkillOp.MARK_MOMENT))
        assertEquals(1, marks)
    }

    @Test fun aFailingMarkIsAFixedCodeAndNeverALoggedMessage() = runTest {
        val s = PcaNexusSkills(gates, capture, { false }, { false }, { false }, { throw IllegalStateException("SECRET-PLACE") }, { logs += it })
        assertEquals(SkillResult.Refused(SkillCodes.FAILED), s.invoke(SkillOp.MARK_MOMENT))
        assertTrue(logs.none { "SECRET" in it })
    }
}
