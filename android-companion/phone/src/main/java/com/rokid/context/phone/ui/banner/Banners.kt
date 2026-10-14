package com.rokid.context.phone.ui.banner

import com.rokid.context.core.model.CaptureState
import com.rokid.context.core.model.LinkState
import com.rokid.context.core.model.PauseRecord
import com.rokid.context.core.ui.components.BannerTone

enum class IndexState {
    NONE, NEEDED, RUNNING,
    /** Auto-rebuild stopped after repeated canary drift (Task 11); only the user's Rebuild resumes it. */
    UNSTABLE;

    companion object {
        fun of(h: com.rokid.context.memory.IndexHealth): IndexState = when (h) {
            com.rokid.context.memory.IndexHealth.NONE -> NONE
            com.rokid.context.memory.IndexHealth.NEEDED -> NEEDED
            com.rokid.context.memory.IndexHealth.RUNNING -> RUNNING
        }

        /**
         * [unstable] = `PrefsIndexClock.observeUnstable()`; it wins, because no build runs while it holds. Without an EMBED
         * model ([embedBound] false) there is nothing to rebuild, so it is not shown (the next index run clears the streak).
         */
        fun of(h: com.rokid.context.memory.IndexHealth, unstable: Boolean, embedBound: Boolean): IndexState =
            if (unstable && embedBound) UNSTABLE else of(h)
    }
}
enum class BannerKind { AI_BLOCKED, BATTERY_SAVER, GLASSES_DISCONNECTED, PROTOCOL_MISMATCH, CAPTURE_DEFERRED, CAPTURE_PAUSED, INDEX, STORAGE }
enum class BannerAction { FIX_AI, RECONNECT, RESUME, REBUILD_INDEX }

/** Everything the banner needs; [aiBlockedText] is the hub's short `Health.Blocked` text, never an exception message. */
data class BannerInputs(
    val aiBlockedText: String? = null,
    val link: LinkState = LinkState.CONNECTED,
    val capture: CaptureState? = null,
    val pipelineEnabled: Boolean = true,
    val index: IndexState = IndexState.NONE,
    val storagePct: Int? = null,
    /** Phase 7: the active pause records, and when the last Resume finished (both phone clock). */
    val pauses: List<PauseRecord> = emptyList(),
    val lastResumeAtMs: Long? = null,
    val nowMs: Long = 0,
    /** G7: Android Battery Saver is on, so asks are paused. */
    val batterySaver: Boolean = false,
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
    /** "Still paused on glasses - Resume?" shows for 10 minutes after a Resume. */
    const val STILL_PAUSED_MS = 10 * 60_000L

    fun resolve(i: BannerInputs): BannerSpec? = candidates(i).firstOrNull()

    fun candidates(i: BannerInputs): List<BannerSpec> = listOfNotNull(
        i.aiBlockedText?.let { BannerSpec(BannerKind.AI_BLOCKED, BannerTone.CORAL, "AI unavailable · ${short(it)}", "Fix now", BannerAction.FIX_AI) },
        if (i.batterySaver) BannerSpec(BannerKind.BATTERY_SAVER, BannerTone.AMBER, com.rokid.context.orchestration.AskActions.BATTERY_SAVER) else null,
        when (i.link) {
            LinkState.OFFLINE -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Glasses disconnected", "Reconnect", BannerAction.RECONNECT)
            LinkState.CLOSED_ON_GLASSES -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Closed on glasses \u00b7 capture stopped", "Open on glasses", BannerAction.RECONNECT)
            LinkState.NOT_AUTHORIZED -> BannerSpec(BannerKind.GLASSES_DISCONNECTED, BannerTone.SKY, "Glasses not authorized", "Reconnect", BannerAction.RECONNECT)
            else -> null
        },
        if (i.link == LinkState.VERSION_MISMATCH) BannerSpec(BannerKind.PROTOCOL_MISMATCH, BannerTone.CORAL, "Update Proactive Context on your glasses") else null,
        when (val c = i.capture) {
            is CaptureState.Deferred -> BannerSpec(BannerKind.CAPTURE_DEFERRED, BannerTone.AMBER, "Capture deferred · ${short(c.reason)}")
            is CaptureState.Blocked -> BannerSpec(BannerKind.CAPTURE_DEFERRED, BannerTone.AMBER, "Capture blocked · ${short(c.reason)}")
            else -> null
        },
        if (!i.pipelineEnabled || i.capture is CaptureState.Paused || i.pauses.isNotEmpty()) {
            // Phase 7: Resume resumes the ids shown now. Still paused soon after a Resume = the glasses hold a pause the
            // phone didn't know when it resumed (R-11); the second Resume names the now-visible ids.
            val recent = i.lastResumeAtMs?.let { i.nowMs - it in 0..STILL_PAUSED_MS } == true
            val title = if (recent) "Still paused on glasses \u2014 Resume?" else "Capture paused"
            BannerSpec(BannerKind.CAPTURE_PAUSED, BannerTone.SLATE, title, "Resume", BannerAction.RESUME)
        } else null,
        when (i.index) {
            IndexState.NEEDED -> BannerSpec(BannerKind.INDEX, BannerTone.SKY, "Memory index needs a rebuild")
            IndexState.RUNNING -> BannerSpec(BannerKind.INDEX, BannerTone.SKY, "Rebuilding memory index…")
            IndexState.UNSTABLE -> BannerSpec(BannerKind.INDEX, BannerTone.AMBER, "Memory index unstable", "Rebuild", BannerAction.REBUILD_INDEX)
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
