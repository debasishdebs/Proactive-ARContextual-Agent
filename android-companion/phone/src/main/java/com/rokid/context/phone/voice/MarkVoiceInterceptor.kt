package com.rokid.context.phone.voice

import com.rokid.context.canvas.marks.MarkRecorder
import com.rokid.context.canvas.marks.VoiceMark
import com.rokid.context.voice.InterceptResult
import com.rokid.context.voice.UtteranceInterceptor
import com.rokid.context.voice.VoiceSource
import com.rokid.context.voice.commands.MarkCommand
import com.rokid.context.voice.commands.MarkCommandParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Slice 3a Task 31 (S54, S63, S85): the voice seam in front of Ask. It runs on the final transcript BEFORE `Thinking` is published:
 * - not a genuine final (a timeout or error partial): never a command, passes through untouched;
 * - no command clause: passes through unchanged (a bound tap mark becomes `ask_tap`);
 * - commands only: [InterceptResult.Handled], so there is NO model call, with the HUD text "marked - label" / "✓ marked" / "mark undone";
 * - commands plus other clauses: the commands are recorded and ONLY the unmatched clauses go on to Ask.
 * The decision is made here synchronously from the parse; the DB writes run on [scope] through the recorder (serialised). The transcript is
 * never stored or logged: the recorder sees only [VoiceMark]s (a lexicon word at most). Voice marks are always on in this build.
 */
class MarkVoiceInterceptor(
    private val recorderOf: () -> MarkRecorder,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : UtteranceInterceptor {
    override fun intercept(text: String, source: VoiceSource, genuineFinal: Boolean): InterceptResult {
        if (!genuineFinal) return InterceptResult.PassThrough
        val parsed = MarkCommandParser.parseUtterance(text)
        val glasses = source == VoiceSource.GLASSES
        if (parsed.commands.isEmpty()) {
            launch("ask") { recorderOf().onAsk(glasses) }
            return InterceptResult.PassThrough
        }
        val marks = parsed.commands.map {
            when (it) {
                is MarkCommand.Mark -> VoiceMark.Mark(it.intent)
                is MarkCommand.Retro -> VoiceMark.Retro(it.intent)
                MarkCommand.Undo -> VoiceMark.Undo
            }
        }
        val now = clock()
        launch("apply") { recorderOf().applyVoice(marks, glasses, now) }
        val rest = parsed.askText
        return if (rest == null) InterceptResult.Handled(hud(parsed.commands)) else InterceptResult.Ask(rest)
    }

    /** Fixed copy plus a lexicon word: never any of the words the wearer said beyond that label. */
    private fun hud(commands: List<MarkCommand>): String {
        val last = commands.last()
        if (last is MarkCommand.Undo) return UNDONE
        val intent = commands.firstNotNullOfOrNull { (it as? MarkCommand.Mark)?.intent ?: (it as? MarkCommand.Retro)?.intent }
        return if (intent != null) "marked · $intent" else MARKED
    }

    private fun launch(what: String, block: suspend () -> Unit) {
        scope.launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { log("mark $what failed (${e.javaClass.simpleName})") } }
    }

    companion object { const val MARKED = "✓ marked"; const val UNDONE = "mark undone" }
}
