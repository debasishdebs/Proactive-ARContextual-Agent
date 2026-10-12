package com.rokid.context.phone.ui.banner

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.ui.components.BannerTone

enum class IndexState { NONE, NEEDED, RUNNING }
enum class BannerKind { AI_BLOCKED, GLASSES_DISCONNECTED, PROTOCOL_MISMATCH, CAPTURE_DEFERRED, CAPTURE_PAUSED, INDEX, STORAGE }
enum class BannerAction { FIX_AI, RECONNECT, RESUME }

/** Everything the banner needs; [aiBlockedText] is the hub's short `Health.Blocked` text, never an exception message. */
data class BannerInputs(
    val aiBlockedText: String? = null,
    val link: LinkState = LinkState.CONNECTED,
    val capture: CaptureState? = null,
    val pipelineEnabled: Boolean = true,
    val index: IndexState = IndexState.NONE,
    val storagePct: Int? = null,
)

data class BannerSpec(
    val kind: BannerKind,
    val tone: BannerTone,
    val title: String,
    val actionLabel: String? = null,
    val action: BannerAction? = null,
)

/** spec §2.2: one banner at a time, highest severity first. */
object BannerResolver {
    private const val MAX_TEXT = 60

    fun resolve(i: BannerInputs): BannerSpec? = candidates(i).firstOrNull()

    fun candidates(i: BannerInputs): List<BannerSpec> = listOfNotNull(
        i.aiBlockedText?.let { BannerSpec(BannerKind.AI_BLOCKED, BannerTone.CORAL, "AI unavailable · ${short(it)}", "Fix now", BannerAction.FIX_AI) },
        when (i.link) {
            LinkState.OFFLINE -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Glasses disconnected", "Reconnect", BannerAction.RECONNECT)
            LinkState.CLOSED_ON_GLASSES -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Proactive Context is closed on the glasses", "Open on glasses", BannerAction.RECONNECT)
            LinkState.NOT_AUTHORIZED -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Glasses not authorized", "Reconnect", BannerAction.RECONNECT)
            else -> null
        },
        if (i.link == LinkState.VERSION_MISMATCH) BannerSpec(BannerKind.PROTOCOL_MISMATCH, BannerTone.CORAL, "Update Proactive Context on your glasses") else null,
        when (val c = i.capture) {
            is CaptureState.Deferred -> BannerSpec(BannerKind.CAPTURE_DEFERRED, BannerTone.AMBER, "Capture deferred · ${short(c.reason)}")
            is CaptureState.Blocked -> BannerSpec(BannerKind.CAPTURE_DEFERRED, BannerTone.AMBER, "Capture blocked · ${short(c.reason)}")
            else -> null
        },
        if (!i.pipelineEnabled || i.capture is CaptureState.Paused) {
            BannerSpec(BannerKind.CAPTURE_PAUSED, BannerTone.SLATE, "Capture paused", "Resume", BannerAction.RESUME)
        } else null,
        when (i.index) {
            IndexState.NEEDED -> BannerSpec(BannerKind.INDEX, BannerTone.SKY, "Memory index needs a rebuild")
            IndexState.RUNNING -> BannerSpec(BannerKind.INDEX, BannerTone.SKY, "Rebuilding memory index…")
            IndexState.NONE -> null
        },
        i.storagePct?.let { p ->
            when {
                p >= 90 -> BannerSpec(BannerKind.STORAGE, BannerTone.CORAL, "Memory storage $p% full")
                p >= 70 -> BannerSpec(BannerKind.STORAGE, BannerTone.AMBER, "Memory storage $p% full")
                else -> null
            }
        },
    )

    private fun short(text: String): String {
        val t = text.replace('\n', ' ').trim()
        return if (t.length <= MAX_TEXT) t else t.take(MAX_TEXT - 1).trimEnd() + "…"
    }
}
