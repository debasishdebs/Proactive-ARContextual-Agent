package com.rokid.context.voice.commands

// Adapted from Live Fit services/voice/src/main/kotlin/com/debasish/livefit/services/voice/VoiceCommandGroup.kt@4529735
// (Apache-2.0, same owner). Pure Kotlin, no Android.

/**
 * Voice command groups for a future Settings toggle list (no Settings switch exists yet in this build), in list order (adapted from Live Fit's VoiceCommandGroup).
 * Examples come from [MarkPhrases], so they cannot drift from the parser.
 */
enum class VoiceCommandGroup(val label: String, val examples: String, val toggleable: Boolean = true) {
    Mark("Mark a moment", quoted(MarkPhrases.MARK.take(3))),
    Retro("I missed that", quoted(MarkPhrases.RETRO)),
    Undo("Undo a mark", quoted(MarkPhrases.UNDO.take(2)));

    val blockedMessage: String get() = "'$label' is turned off"

    companion object {
        fun of(command: MarkCommand): VoiceCommandGroup = when (command) {
            is MarkCommand.Mark -> Mark
            is MarkCommand.Retro -> Retro
            MarkCommand.Undo -> Undo
        }

        fun isAllowed(command: MarkCommand, disabled: Set<VoiceCommandGroup>): Boolean = of(command).let { !it.toggleable || it !in disabled }

        /** [disabled] after the user turns [group] on or off; a locked group never changes. */
        fun withEnabled(disabled: Set<VoiceCommandGroup>, group: VoiceCommandGroup, enabled: Boolean): Set<VoiceCommandGroup> = when {
            !group.toggleable -> disabled
            enabled -> disabled - group
            else -> disabled + group
        }

        /** Persisted form of the turned-off groups (comma-separated names). */
        fun encode(disabled: Set<VoiceCommandGroup>): String = disabled.filter { it.toggleable }.joinToString(",") { it.name }

        fun decode(text: String): Set<VoiceCommandGroup> =
            text.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.filter { it.toggleable }.toSet()
    }
}

private fun quoted(phrases: List<String>): String = phrases.joinToString(", ") { "\"$it\"" }

/** Dispatcher front: runs commands whose group is on; a turned-off one only toasts [VoiceCommandGroup.blockedMessage]. */
class VoiceCommandGate(
    private val disabled: () -> Set<VoiceCommandGroup>,
    private val toast: (String) -> Unit,
    private val dispatch: suspend (MarkCommand) -> Unit,
) {
    suspend operator fun invoke(command: MarkCommand) {
        if (VoiceCommandGroup.isAllowed(command, disabled())) dispatch(command)
        else toast(VoiceCommandGroup.of(command).blockedMessage)
    }
}
