package com.rokid.context.phone.voice

import com.rokid.context.canvas.marks.MarkOrigin
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.canvas.marks.VoiceMark
import com.rokid.context.voice.UtteranceCommands
import com.rokid.context.voice.UtteranceSource
import com.rokid.context.voice.commands.MarkCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v0.9.7 slice D: the effects behind `Utterance` (the shared text backend), over the one [MarkRecorder]. It only maps a command to a [VoiceMark] and an origin; it parses nothing.
 * The decision (reply, photo reason) is made here synchronously; the DB writes run on [scope] through the recorder (serialised). The transcript never gets here: the recorder sees only
 * [VoiceMark]s (a lexicon word at most). Voice marks are always on in this build.
 */
class MarkUtteranceCommands(
    private val recorderOf: () -> MarkRecorder,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : UtteranceCommands {
    override fun apply(commands: List<MarkCommand>, source: UtteranceSource, nowMs: Long): String? {
        val marks = commands.map {
            when (it) {
                is MarkCommand.Mark -> VoiceMark.Mark(it.intent)
                is MarkCommand.Retro -> VoiceMark.Retro(it.intent)
                MarkCommand.Undo -> VoiceMark.Undo
            }
        }
        val origin = when (source) {
            UtteranceSource.GLASSES_VOICE, UtteranceSource.GLASSES_TEXT -> MarkOrigin.GLASSES_VOICE
            UtteranceSource.PHONE_VOICE -> MarkOrigin.PHONE_VOICE
            UtteranceSource.PHONE_CHAT -> MarkOrigin.PHONE_CHAT
        }
        val recorder = recorderOf()
        // only a phone-origin mark that is not retro and not an undo-only utterance reports a photo reason
        val makesMark = commands.any { it is MarkCommand.Mark }
        val reason = if (!origin.glasses) recorder.phonePhotoDecision() else null
        launch("apply") { recorder.applyVoice(marks, origin, nowMs, reason) }
        return if (makesMark) reason else null
    }

    override fun onAsk(source: UtteranceSource) {
        if (source.fromGlasses) launch("ask") { recorderOf().onAsk(true) }
    }

    private fun launch(what: String, block: suspend () -> Unit) {
        scope.launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("mark $what failed (${e.javaClass.simpleName})") } }
    }
}
