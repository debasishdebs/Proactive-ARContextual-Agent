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
    /** Uses a microphone (v0.9.8 slice D): the surface the `mic` device capability can switch off. Typed text has no mic. */
    val usesMic: Boolean get() = this == GLASSES_VOICE || this == PHONE_VOICE
}

/** What [Utterance.handle] decided: not ours ([PassThrough]), consumed with a short fixed reply ([Handled], no model call) or only these clauses go on to Ask ([Ask]). */
sealed interface UtteranceResult {
    data object PassThrough : UtteranceResult
    /** [replyText] is fixed copy plus at most a lexicon word and a reason token's words: never any of the words the user said. */
    data class Handled(val replyText: String) : UtteranceResult
    data class Ask(val question: String) : UtteranceResult
}

/** Why a command did not take effect (the reply says so; nothing is silently lost). */
enum class CommandFailure { EXPORT_BUSY, SAVE_BUSY, OTHER }
data class CommandOutcome(val noPhotoReason: String? = null, val failure: CommandFailure? = null)

/** The effects of the matched commands. Implemented over the mark recorder; must not block (the writes run on the implementation's own scope). */
interface UtteranceCommands {
    /**
     * Records [commands] and WAITS for the result (an undo waits for its removal, which may wait for a running export): the reply must be true. [CommandOutcome.noPhotoReason] says why a PHONE-origin mark
     * has no photo right now (a `MarkPhotoReason` token), null when it has none to report; [CommandOutcome.failure] is set when a write did not happen.
     */
    suspend fun apply(commands: List<MarkCommand>, source: UtteranceSource, nowMs: Long): CommandOutcome
    /** The utterance was an ordinary question (no command): a glasses tap mark bound to this listen becomes `ask_tap`. */
    fun onAsk(source: UtteranceSource)
}

class Utterance(
    private val commands: UtteranceCommands,
    private val clock: () -> Long = System::currentTimeMillis,
    /** The user's device capabilities (v0.9.8 slice D); default = everything on. */
    private val caps: () -> com.rokid.context.core.model.DeviceCapabilities = { com.rokid.context.core.model.DeviceCapabilities.ALL },
) {
    /**
     * [genuineFinal] false (a timeout or error partial) is never a command. No command clause: [UtteranceResult.PassThrough] and the ask continues unchanged. Commands only: [UtteranceResult.Handled].
     * Commands plus other clauses: the commands are recorded and ONLY the unmatched clauses are returned for Ask. The transcript is never stored or logged here.
     */
    suspend fun handle(text: String, source: UtteranceSource, genuineFinal: Boolean): UtteranceResult {
        // a voice surface whose mic was switched off in Settings (mid-listen): nothing is recorded and nothing is asked; the reply says why. Typed text has no mic.
        if (source.usesMic && !caps().micOn(source.fromGlasses)) return UtteranceResult.Handled(if (source.fromGlasses) VoiceCopy.GLASSES_MIC_OFF else VoiceCopy.PHONE_MIC_OFF)
        if (!genuineFinal) return UtteranceResult.PassThrough
        val parsed = MarkCommandParser.parseUtterance(text)
        if (parsed.commands.isEmpty()) { commands.onAsk(source); return UtteranceResult.PassThrough }
        val out = commands.apply(parsed.commands, source, clock())
        out.failure?.let { return UtteranceResult.Handled(failureReply(parsed.commands, it)) } // a failed write is never confirmed, and nothing else is asked
        val rest = parsed.askText
        return if (rest == null) UtteranceResult.Handled(reply(parsed.commands, out.noPhotoReason)) else UtteranceResult.Ask(rest)
    }

    /** The [VoicePipeline] seam over [handle] (the pipeline has no command logic of its own). */
    fun asInterceptor(): UtteranceInterceptor = UtteranceInterceptor { text, source, genuine ->
        when (val r = handle(text, if (source == VoiceSource.GLASSES) UtteranceSource.GLASSES_VOICE else UtteranceSource.PHONE_VOICE, genuine)) {
            UtteranceResult.PassThrough -> InterceptResult.PassThrough
            is UtteranceResult.Handled -> InterceptResult.Handled(r.replyText)
            is UtteranceResult.Ask -> InterceptResult.Ask(r.question)
        }
    }

    private fun failureReply(cmds: List<MarkCommand>, f: CommandFailure): String =
        if (cmds.none { it is MarkCommand.Undo }) COULDNT_MARK
        else when (f) {
            CommandFailure.EXPORT_BUSY -> "Couldn't undo: an export is running. Try again in a minute."
            CommandFailure.SAVE_BUSY -> "Couldn't undo: a save is running. Try again in a minute."
            CommandFailure.OTHER -> COULDNT_UNDO
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

    companion object { const val MARKED = "✓ marked"; const val UNDONE = "mark undone"; const val COULDNT_UNDO = "Couldn't undo the mark. Try again."; const val COULDNT_MARK = "Couldn't mark this moment. Try again." }
}
