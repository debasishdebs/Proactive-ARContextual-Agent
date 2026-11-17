package io.github.pca.nexusport

/**
 * The plan's mapping table (spec 2026-11-17 section 3): nudge and place card -> notice (Dismiss / Mark / More), ask answer -> notice (+ optional speech), capture state -> pin,
 * mark confirm -> notice. Activity and surface kinds map to null (cut to 2.x). Pure: no gate, no I/O. Every text is cut to what Nexus draws.
 */
object HudMapper {
    const val DISMISS = "dismiss"
    const val MARK = "mark"
    const val MORE = "more"
    val NOTICE_ACTIONS = listOf(NexusAction(DISMISS, "Dismiss"), NexusAction(MARK, "Mark"), NexusAction(MORE, "More"))

    fun map(e: HudEvent): Mapped? = when (e) {
        is HudEvent.Nudge -> Mapped(notice(e.title, e.body, null, e.ttlMs, e.wake, NOTICE_ACTIONS))
        is HudEvent.PlaceCard -> Mapped(notice(e.title, e.body, null, e.ttlMs, e.wake, NOTICE_ACTIONS))
        is HudEvent.AskAnswer -> Mapped(
            notice("Answer", e.answer, footer(e.citations), e.ttlMs, false, listOf(NexusAction(DISMISS, "Dismiss"), NexusAction(MORE, "More"))),
            speech = if (e.speak) clipText(e.answer, NexusLimits.SPEECH_CHARS).takeIf { it.isNotBlank() } else null,
        )
        is HudEvent.MarkConfirm -> Mapped(notice("Marked", markLine(e.reasonToken), null, e.ttlMs, false, listOf(NexusAction(DISMISS, "Dismiss"))))
        is HudEvent.AskNotice -> Mapped(notice("Ask", askNoticeLine(e.kind), null, ASK_NOTICE_TTL_MS, false, listOf(NexusAction(DISMISS, "Dismiss"))))
        is HudEvent.CaptureState -> Mapped(pin(e.state))
        is HudEvent.Activity, is HudEvent.SurfaceCard -> null
    }

    const val ASK_NOTICE_TTL_MS = 4_000L

    private fun askNoticeLine(k: HudEvent.AskNotice.Kind): String = when (k) {
        HudEvent.AskNotice.Kind.BUSY -> "Microphone busy. Try again."
        HudEvent.AskNotice.Kind.NO_SPEECH -> "Didn't catch that."
        HudEvent.AskNotice.Kind.UNAVAILABLE -> "Voice ask unavailable."
    }

    fun clampNoticeTtl(ms: Long): Long = ms.coerceIn(NexusLimits.NOTICE_TTL_MIN_MS, NexusLimits.NOTICE_TTL_MAX_MS)

    private fun notice(title: String, body: String, footer: String?, ttlMs: Long, wake: Boolean, actions: List<NexusAction>): NexusCommand {
        val foot = footer?.let { "\n" + clipText(it, NexusLimits.NOTICE_TEXT_CHARS / 4) }.orEmpty()
        val room = (NexusLimits.NOTICE_TEXT_CHARS - foot.length).coerceAtLeast(0)
        val text = clipLines(clipText(body, room), NexusLimits.NOTICE_LINES - (if (foot.isEmpty()) 0 else 1)) + foot
        return NexusCommand(
            slot = Slot.NOTICE,
            title = clipText(title, NexusLimits.NOTICE_TITLE_CHARS),
            text = clipText(text, NexusLimits.NOTICE_TEXT_CHARS),
            ttlMs = clampNoticeTtl(ttlMs),
            wake = wake,
            actions = actions.take(NexusLimits.MAX_ACTIONS).map { NexusAction(it.id, clipText(it.label, NexusLimits.ACTION_LABEL_CHARS)) },
        )
    }

    private fun pin(s: HudEvent.CaptureState.State): NexusCommand {
        val line = when (s) {
            HudEvent.CaptureState.State.CAPTURING -> "Capturing"
            HudEvent.CaptureState.State.PAUSED -> "Paused"
            HudEvent.CaptureState.State.STOPPED -> "Stopped"
        }
        return NexusCommand(Slot.PIN, clipText("PCA", NexusLimits.PIN_TITLE_CHARS), clipText(line, NexusLimits.PIN_LINE_CHARS), NexusLimits.PIN_TTL_MAX_MS)
    }

    private fun footer(citations: List<String>): String? = citations.map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Sources: ")

    /** The reason tokens are fixed words; an unknown token becomes a generic line, never the token itself. */
    private fun markLine(token: String): String = when (token) {
        "pause_zone" -> "Not saved: pause zone"
        "paused" -> "Not saved: paused"
        else -> "Marked"
    }

    /** Cuts to [max] UTF-16 units without splitting a surrogate pair; adds an ellipsis when it cut. */
    internal fun clipText(s: String, max: Int): String {
        val t = s.trim()
        if (t.length <= max) return t
        if (max <= 0) return ""
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(t[end - 1])) end--
        return t.substring(0, end).trimEnd() + "…"
    }

    internal fun clipLines(s: String, max: Int): String {
        val lines = s.split('\n')
        return if (lines.size <= max) s else lines.take(max).joinToString("\n")
    }
}
