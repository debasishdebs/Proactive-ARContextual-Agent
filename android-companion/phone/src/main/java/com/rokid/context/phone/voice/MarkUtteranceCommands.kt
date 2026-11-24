package com.rokid.context.phone.voice

import com.rokid.context.canvas.marks.MarkOrigin
import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.canvas.marks.VoiceMark
import com.rokid.context.orchestration.DataOpGate
import com.rokid.context.voice.CommandFailure
import com.rokid.context.voice.CommandOutcome
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
    override suspend fun apply(commands: List<MarkCommand>, source: UtteranceSource, nowMs: Long): CommandOutcome {
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
        // awaited: the reply must be true (an undo that waits out a running export and fails is reported, the mark and its photo stay)
        var cleanupPending = false
        val failure = try { cleanupPending = recorder.applyVoice(marks, origin, nowMs, reason); null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: DataOpGate.UploadInProgress) { log("mark apply refused (upload)"); CommandFailure.UPLOAD_BUSY }
        catch (e: DataOpGate.WriteInProgress) { log("mark apply refused (save)"); CommandFailure.SAVE_BUSY }
        catch (e: DataOpGate.ExportInProgress) { log("mark apply refused (export)"); CommandFailure.EXPORT_BUSY }
        catch (e: Exception) { log("mark apply failed (${e.javaClass.simpleName})"); CommandFailure.OTHER }
        return CommandOutcome(if (makesMark) reason else null, failure, cleanupPending)
    }

    override fun onAsk(source: UtteranceSource) {
        if (source.fromGlasses) launch("ask") { recorderOf().onAsk(true) }
    }

    private fun launch(what: String, block: suspend () -> Unit) {
        scope.launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("mark $what failed (${e.javaClass.simpleName})") } }
    }
}
