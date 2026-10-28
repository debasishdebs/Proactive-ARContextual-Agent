package com.rokid.context.voice

import com.rokid.context.voice.commands.MarkCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** v0.9.7 slice D: the ONE text backend. Same text, same result, whatever the source; the effects are behind [UtteranceCommands]. */
class UtteranceTest {
    private class Fake(var noPhoto: String? = null) : UtteranceCommands {
        val applied = ArrayList<Triple<List<MarkCommand>, UtteranceSource, Long>>(); val asks = ArrayList<UtteranceSource>()
        override fun apply(commands: List<MarkCommand>, source: UtteranceSource, nowMs: Long): String? { applied += Triple(commands, source, nowMs); return noPhoto }
        override fun onAsk(source: UtteranceSource) { asks += source }
    }

    private fun u(f: Fake) = Utterance(f, { 7L })

    @Test fun everySourceGetsTheSameAnswerForTheSameText() {
        for (text in listOf("mark this moment", "mark this as important", "undo mark", "undo the mark", "what is this", "mark this moment and what is this")) {
            val results = UtteranceSource.entries.map { u(Fake()).handle(text, it, true) }
            assertEquals(1, results.toSet().size, "'$text' answers differently by source: $results")
        }
    }

    @Test fun aCommandOnlyTextIsHandledWithAShortFixedReplyAndNeverTheWords() {
        assertEquals(UtteranceResult.Handled(Utterance.MARKED), u(Fake()).handle("mark this moment", UtteranceSource.PHONE_CHAT, true))
        assertEquals(UtteranceResult.Handled(Utterance.UNDONE), u(Fake()).handle("undo mark", UtteranceSource.PHONE_CHAT, true))
        val r = u(Fake()).handle("mark that, I'm driving", UtteranceSource.PHONE_VOICE, true) as UtteranceResult.Handled
        assertEquals("marked · driving", r.replyText)
    }

    @Test fun aMarkWithNoPhotoSaysWhy() {
        val r = u(Fake(noPhoto = "camera_busy")).handle("mark this moment", UtteranceSource.PHONE_CHAT, true) as UtteranceResult.Handled
        assertEquals("✓ marked (no photo: camera busy)", r.replyText)
        assertEquals(UtteranceResult.Handled(Utterance.MARKED), u(Fake()).handle("mark this moment", UtteranceSource.GLASSES_VOICE, true), "a glasses source reports no reason")
    }

    @Test fun anUndoNeverSaysNoPhoto() {
        assertEquals(UtteranceResult.Handled(Utterance.UNDONE), u(Fake(noPhoto = "camera_busy")).handle("undo mark", UtteranceSource.PHONE_CHAT, true))
    }

    @Test fun onlyTheUnmatchedClausesGoOnToAsk() {
        val f = Fake()
        assertEquals(UtteranceResult.Ask("what is this"), u(f).handle("mark this moment and what is this", UtteranceSource.PHONE_CHAT, true))
        assertEquals(1, f.applied.size)
    }

    @Test fun aQuestionPassesThroughAndATimeoutPartialIsNeverACommand() {
        val f = Fake()
        assertEquals(UtteranceResult.PassThrough, u(f).handle("what is this", UtteranceSource.GLASSES_VOICE, true))
        assertEquals(listOf(UtteranceSource.GLASSES_VOICE), f.asks)
        assertEquals(UtteranceResult.PassThrough, u(f).handle("mark this moment", UtteranceSource.PHONE_VOICE, false))
        assertTrue(f.applied.isEmpty())
    }

    @Test fun theSourceAndTheClockReachTheEffects() {
        val f = Fake()
        u(f).handle("mark this moment", UtteranceSource.PHONE_CHAT, true)
        assertEquals(UtteranceSource.PHONE_CHAT, f.applied.single().second); assertEquals(7L, f.applied.single().third)
    }

    @Test fun thePipelineSeamMapsTheVoiceSourcesAndTheResults() {
        val f = Fake()
        val i = u(f).asInterceptor()
        assertEquals(InterceptResult.Handled(Utterance.MARKED), i.intercept("mark this moment", VoiceSource.GLASSES, true))
        assertEquals(UtteranceSource.GLASSES_VOICE, f.applied.last().second)
        assertEquals(InterceptResult.Ask("what is this"), i.intercept("mark this moment and what is this", VoiceSource.PHONE, true))
        assertEquals(UtteranceSource.PHONE_VOICE, f.applied.last().second)
        assertEquals(InterceptResult.PassThrough, i.intercept("what is this", VoiceSource.PHONE, true))
    }
}
