package com.rokid.context.voice

import com.rokid.context.voice.commands.MarkCommand
import com.rokid.context.voice.commands.MarkCommandParser

/**
 * v0.9.7 slice D, the ONE text-in/text-out backend of every surface. A surface turns its input into text and calls [Utterance.handle]; the answer is the same whoever asked:
 * - phone voice: phone STT, then [handle] (through [Utterance.asInterceptor], the seam of [VoicePipeline]);
 * - the Home chat window: no STT, [handle] directly;
 * - glasses voice: audio to the phone STT, then the same pipeline and the same call;
 * - glasses typed text does not exist yet; [GLASSES_TEXT] is reserved so it can join without a change here.
 * Adding a command means editing [MarkCommandParser] / [Utterance] and nothing else.
 *
 * Dependency direction: this lives in `:services:voice` (it needs only the parser and `:core:model`); the DB side is behind [UtteranceCommands], implemented in `:phone` over the mark recorder
 * (`:services:orchestration` does not depend on voice, and `:phone` depends on both).
 */
enum class UtteranceSource { GLASSES_VOICE, PHONE_VOICE, PHONE_CHAT, GLASSES_TEXT;
    /** The utterance was made on the glasses' side (it may join the wearer's tap mark; a photo is the glasses' own business). */
    val fromGlasses: Boolean get() = this == GLASSES_VOICE || this == GLASSES_TEXT
}

/** What [Utterance.handle] decided: not ours ([PassThrough]), consumed with a short fixed reply ([Handled], no model call) or only these clauses go on to Ask ([Ask]). */
sealed interface UtteranceResult {
    data object PassThrough : UtteranceResult
    /** [replyText] is fixed copy plus at most a lexicon word and a reason token's words: never any of the words the user said. */
    data class Handled(val replyText: String) : UtteranceResult
    data class Ask(val question: String) : UtteranceResult
}

/** The effects of the matched commands. Implemented over the mark recorder; must not block (the writes run on the implementation's own scope). */
interface UtteranceCommands {
    /** Records [commands]. Returns why a PHONE-origin mark has no photo right now (a `MarkPhotoReason` token), null when it has none to report (a photo was requested, or the mark is not phone-origin / was an undo). */
    fun apply(commands: List<MarkCommand>, source: UtteranceSource, nowMs: Long): String?
    /** The utterance was an ordinary question (no command): a glasses tap mark bound to this listen becomes `ask_tap`. */
    fun onAsk(source: UtteranceSource)
}

class Utterance(private val commands: UtteranceCommands, private val clock: () -> Long = System::currentTimeMillis) {
    /**
     * [genuineFinal] false (a timeout or error partial) is never a command. No command clause: [UtteranceResult.PassThrough] and the ask continues unchanged. Commands only: [UtteranceResult.Handled].
     * Commands plus other clauses: the commands are recorded and ONLY the unmatched clauses are returned for Ask. The transcript is never stored or logged here.
     */
    fun handle(text: String, source: UtteranceSource, genuineFinal: Boolean): UtteranceResult {
        if (!genuineFinal) return UtteranceResult.PassThrough
        val parsed = MarkCommandParser.parseUtterance(text)
        if (parsed.commands.isEmpty()) { commands.onAsk(source); return UtteranceResult.PassThrough }
        val noPhoto = commands.apply(parsed.commands, source, clock())
        val rest = parsed.askText
        return if (rest == null) UtteranceResult.Handled(reply(parsed.commands, noPhoto)) else UtteranceResult.Ask(rest)
    }

    /** The [VoicePipeline] seam over [handle] (the pipeline has no command logic of its own). */
    fun asInterceptor(): UtteranceInterceptor = UtteranceInterceptor { text, source, genuine ->
        when (val r = handle(text, if (source == VoiceSource.GLASSES) UtteranceSource.GLASSES_VOICE else UtteranceSource.PHONE_VOICE, genuine)) {
            UtteranceResult.PassThrough -> InterceptResult.PassThrough
            is UtteranceResult.Handled -> InterceptResult.Handled(r.replyText)
            is UtteranceResult.Ask -> InterceptResult.Ask(r.question)
        }
    }

    private fun reply(cmds: List<MarkCommand>, noPhoto: String?): String {
        val last = cmds.last()
        if (last is MarkCommand.Undo) return UNDONE
        val intent = cmds.firstNotNullOfOrNull { (it as? MarkCommand.Mark)?.intent ?: (it as? MarkCommand.Retro)?.intent }
        val base = if (intent != null) "marked · $intent" else MARKED
        return if (noPhoto == null) base else "$base (no photo: ${reasonWords(noPhoto)})"
    }

    /** Short words for the reply; a token this build does not know is shown as it is (a short code, never user text). */
    private fun reasonWords(token: String): String = when (token) {
        "camera_busy" -> "camera busy"
        "glasses_not_connected" -> "glasses not connected"
        "glasses_not_worn" -> "glasses not worn"
        "no_photo_support" -> "glasses can't"
        "photo_timeout" -> "no photo came"
        else -> token.replace('_', ' ')
    }

    companion object { const val MARKED = "✓ marked"; const val UNDONE = "mark undone" }
}
