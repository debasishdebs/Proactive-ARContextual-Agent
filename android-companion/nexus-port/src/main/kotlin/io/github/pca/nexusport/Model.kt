package io.github.pca.nexusport

/** The HUD slots Nexus gives a plugin. Nexus has exactly ONE notice, ONE pin and ONE voice per plugin, so the handle registry is keyed by slot. */
enum class Slot(val code: Int) {
    NOTICE(1), PIN(2), ACTIVITY(3), SURFACE(4), SPEECH(5);

    companion object {
        fun of(code: Int): Slot? = entries.firstOrNull { it.code == code }
    }
}

/** Result codes of every [NexusPort] call. Fixed tokens: they are what logs carry. */
object NexusResult {
    const val OK = 0
    /** SURFACE_BUSY: another surface holds the slot. */
    const val BUSY = 1
    /** CAPABILITY_NOT_AVAILABLE: the link is down or the hub lacks the capability. */
    const val NOT_AVAILABLE = 2
    /** NOT_REGISTERED: the hub has not registered the plugin (cold start, or Nexus not installed). */
    const val NOT_REGISTERED = 3
    /** CAPABILITY_NOT_GRANTED: the wearer did not approve it in Plugin access. */
    const val NOT_GRANTED = 4
    const val INVALID = 5
    const val RATE_LIMITED = 6
    /** The bridge is gone (dead binder) or threw. */
    const val ERROR = 7
    /** The bridge's own feature switch is off (speech). */
    const val DISABLED = 8
    /** A kind PCA 2.0 does not draw (activity, surface). */
    const val UNSUPPORTED = 9

    fun name(code: Int): String = when (code) {
        OK -> "OK"; BUSY -> "BUSY"; NOT_AVAILABLE -> "NOT_AVAILABLE"; NOT_REGISTERED -> "NOT_REGISTERED"; NOT_GRANTED -> "NOT_GRANTED"
        INVALID -> "INVALID"; RATE_LIMITED -> "RATE_LIMITED"; ERROR -> "ERROR"; DISABLED -> "DISABLED"; UNSUPPORTED -> "UNSUPPORTED"
        else -> "UNKNOWN"
    }

    /** A refusal the sink accepts quietly: no retry, no user-visible failure, the item is simply not shown. */
    fun isQuietGiveUp(code: Int): Boolean = code != OK && code != ERROR && code != RATE_LIMITED

    /** A hide that failed in a way that may leave something on the glasses: keep the handle and try again on the next tick. */
    fun hideMustRetry(code: Int): Boolean = code == ERROR || code == RATE_LIMITED || code == BUSY
}

/** Feature bits PCA pushes with [NexusPort.setFeatures]. The bridge starts with 0 (all off). STT and the assist takeover are reserved: nothing in 2.0 sets them. */
object NexusFeatures {
    const val NONE = 0
    const val TTS = 1
    const val STT = 2
    const val ASSIST = 4
    /** The bits this version understands; a mask with any other bit is rejected by the bridge. */
    const val KNOWN = TTS or STT or ASSIST
}

object NexusPortVersion { const val CURRENT = 1 }

/** The limits of what Nexus draws (PLUGIN_SDK notice and pin contracts at sdk-v0.27.0) with PCA's tighter clamps. */
object NexusLimits {
    const val NOTICE_TEXT_CHARS = 1024
    const val NOTICE_LINES = 16
    const val NOTICE_TITLE_CHARS = 32
    const val ACTION_LABEL_CHARS = 16
    const val MAX_ACTIONS = 3
    const val NOTICE_TTL_MIN_MS = 2_000L
    const val NOTICE_TTL_MAX_MS = 45_000L
    const val PIN_TITLE_CHARS = 24
    const val PIN_LINES = 2
    const val PIN_LINE_CHARS = 28
    const val PIN_TTL_MIN_MS = 10_000L
    const val PIN_TTL_MAX_MS = 30L * 60 * 1000
    const val SPEECH_CHARS = 1024
    /** A speech that never reports its end is considered over after this long. */
    const val SPEECH_MAX_MS = 60_000L
}

data class NexusAction(val id: String, val label: String)

/** One mapped HUD item, ready for the transport. Never logged. */
data class NexusCommand(
    val slot: Slot,
    val title: String,
    val text: String,
    val ttlMs: Long,
    val wake: Boolean = false,
    val actions: List<NexusAction> = emptyList(),
)

/** What closes handles: the five events of gate 4 plus the not-worn edge. */
enum class TripReason { ZONE_HOLD, FORGET, DELETE_ALL, PAUSE, MODE_OFF, NOT_WORN, LINK_LOST }

/** Why an item was not shown. Fixed tokens for logs. */
enum class DropReason { MODE_OFF, NOT_WORN, ZONE_HELD, PAUSED, PROVENANCE, GATE_ERROR, TRIPPED, UNSUPPORTED, TRANSPORT }

sealed interface Outcome {
    /** Shown. [spoken]: the speech line was sent too. */
    data class Shown(val spoken: Boolean = false) : Outcome
    data class Dropped(val reason: DropReason, val code: Int = NexusResult.OK) : Outcome
}
