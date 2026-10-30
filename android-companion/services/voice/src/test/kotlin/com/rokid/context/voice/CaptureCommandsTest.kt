package com.rokid.context.voice

import com.rokid.context.core.model.DeviceCapabilities
import com.rokid.context.voice.commands.CaptureCommand
import com.rokid.context.voice.commands.CaptureCommandParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureCommandsTest {
    private class Fake(var result: CaptureResult = CaptureResult.Done, var boom: Boolean = false) : CaptureControl {
        val calls = ArrayList<Pair<String, UtteranceSource>>()
        override suspend fun pause(source: UtteranceSource): CaptureResult { calls += "pause" to source; if (boom) error("x"); return result }
        override suspend fun resume(source: UtteranceSource): CaptureResult { calls += "resume" to source; if (boom) error("x"); return result }
    }
    private class NoMarks : UtteranceCommands {
        var applied = 0; var asks = 0
        override suspend fun apply(commands: List<com.rokid.context.voice.commands.MarkCommand>, source: UtteranceSource, nowMs: Long): CommandOutcome { applied++; return CommandOutcome() }
        override fun onAsk(source: UtteranceSource) { asks++ }
    }
    private fun u(c: Fake?, caps: DeviceCapabilities = DeviceCapabilities.ALL) = Utterance(NoMarks(), { 1L }, { caps }, c)

    @Test fun pausePhrases() {
        for (p in listOf("pause capture", "Pause capturing", "stop capturing.", "pause the camera", "please pause capture", "ok, pause capture", "pause capture please"))
            assertEquals(CaptureCommand.PAUSE, CaptureCommandParser.parse(p), p)
    }

    @Test fun resumePhrases() {
        for (p in listOf("resume capture", "resume capturing", "Start capturing", "unpause", "hey unpause!"))
            assertEquals(CaptureCommand.RESUME, CaptureCommandParser.parse(p), p)
    }

    @Test fun nonPhrases() {
        for (p in listOf("why did you pause", "why did you pause capture", "pause capture?", "can you pause capture", "pause capture and mark this", "pause", "resume", "stop", "what does pause capture do",
            "I will pause capture later", "pause the camera when I leave", "", "start capturing photos of the garden"))
            assertNull(CaptureCommandParser.parse(p), p)
    }

    @Test fun everySurfaceReachesTheSameEntry() = kotlinx.coroutines.runBlocking<Unit> {
        for (s in UtteranceSource.entries) {
            val f = Fake()
            assertEquals(UtteranceResult.Handled(Utterance.CAPTURE_PAUSED), u(f).handle("pause capture", s, true))
            assertEquals(UtteranceResult.Handled(Utterance.CAPTURE_RESUMED), u(f).handle("unpause", s, true))
            assertEquals(listOf("pause" to s, "resume" to s), f.calls)
        }
    }

    @Test fun repliesAreTruthful() = kotlinx.coroutines.runBlocking<Unit> {
        val f = Fake(CaptureResult.AlreadyInState)
        assertEquals(UtteranceResult.Handled(Utterance.ALREADY_PAUSED), u(f).handle("pause capture", UtteranceSource.PHONE_CHAT, true))
        assertEquals(UtteranceResult.Handled(Utterance.ALREADY_CAPTURING), u(f).handle("resume capture", UtteranceSource.PHONE_CHAT, true))
        f.result = CaptureResult.Failed
        assertEquals(UtteranceResult.Handled(Utterance.COULDNT_PAUSE), u(f).handle("pause capture", UtteranceSource.PHONE_CHAT, true))
        assertEquals(UtteranceResult.Handled(Utterance.COULDNT_RESUME), u(f).handle("resume capture", UtteranceSource.PHONE_CHAT, true))
        f.boom = true
        assertEquals(UtteranceResult.Handled(Utterance.COULDNT_PAUSE), u(f).handle("pause capture", UtteranceSource.PHONE_VOICE, true))
    }

    @Test fun aSaidReplyIsPassedThroughVerbatim() = kotlinx.coroutines.runBlocking<Unit> {
        val f = Fake(CaptureResult.Said(Utterance.RESUMED_PHONE_ONLY))
        assertEquals(UtteranceResult.Handled("Your phone pause was cleared; the glasses will follow when they reconnect"), u(f).handle("resume capture", UtteranceSource.PHONE_CHAT, true))
        assertEquals("Paused on the phone; the glasses will follow when they reconnect", Utterance.PAUSED_PHONE_ONLY)
    }

    @Test fun questionsAndPartialsNeverReachTheEntry() = kotlinx.coroutines.runBlocking<Unit> {
        val f = Fake()
        assertEquals(UtteranceResult.PassThrough, u(f).handle("why did you pause", UtteranceSource.PHONE_CHAT, true))
        assertEquals(UtteranceResult.PassThrough, u(f).handle("pause capture", UtteranceSource.PHONE_VOICE, false))
        assertEquals(emptyList(), f.calls)
    }

    @Test fun withoutAnEntryTheWordsGoToAsk() = kotlinx.coroutines.runBlocking<Unit> {
        assertEquals(UtteranceResult.PassThrough, u(null).handle("pause capture", UtteranceSource.PHONE_CHAT, true))
    }

    @Test fun aMicOffSurfaceIsBlockedBeforeAnyCommand() = kotlinx.coroutines.runBlocking<Unit> {
        val f = Fake()
        val caps = DeviceCapabilities.ALL.with(DeviceCapabilities.GLASSES, DeviceCapabilities.MIC, false)
        assertEquals(UtteranceResult.Handled(VoiceCopy.GLASSES_MIC_OFF), u(f, caps).handle("pause capture", UtteranceSource.GLASSES_VOICE, true))
        assertEquals(emptyList(), f.calls)
        assertEquals(UtteranceResult.Handled(Utterance.CAPTURE_PAUSED), u(f, caps).handle("pause capture", UtteranceSource.PHONE_CHAT, true))
    }
}
