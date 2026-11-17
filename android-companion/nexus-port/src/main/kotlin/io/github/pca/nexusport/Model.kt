package io.github.pca.nexusport

/** The HUD slots Nexus gives a plugin. Nexus has exactly ONE notice, ONE pin and ONE voice per plugin, so the handle registry is keyed by slot. */
enum class Slot(val code: Int) {
    NOTICE(1), PIN(2), ACTIVITY(3), SURFACE(4), SPEECH(5),

    /** The Nexus speech-to-text session of an assist-button ask (T5). Only a handle in the sink's registry: a trip stops the listening. */
    LISTEN(6);

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
    /** The show was stamped with a trip epoch older than the latest trip the bridge was told of: dropped bridge-side. */
    const val STALE = 10

    /**
     * Additive bit of the session-carrying show/speak results (v2 r4 P1-2): the hub call may have created a notice in the history (also when the result is [STALE] because the bridge hid it again right after the draw).
     * A result without it is a confirmed "nothing was created". The low 16 bits are the code ([code]).
     */
    const val CREATED_HISTORY = 1 shl 16

    fun code(raw: Int): Int = raw and (CREATED_HISTORY - 1)

    /** True unless the bridge CONFIRMED that nothing reached the history: the flag, a plain OK (an older bridge reports no flag) and ERROR (a call that threw or never answered: unknown) all count as created. */
    fun created(raw: Int): Boolean = raw and CREATED_HISTORY != 0 || code(raw) == OK || code(raw) == ERROR

    fun name(code: Int): String = when (code) {
        OK -> "OK"; BUSY -> "BUSY"; NOT_AVAILABLE -> "NOT_AVAILABLE"; NOT_REGISTERED -> "NOT_REGISTERED"; NOT_GRANTED -> "NOT_GRANTED"
        INVALID -> "INVALID"; RATE_LIMITED -> "RATE_LIMITED"; ERROR -> "ERROR"; DISABLED -> "DISABLED"; UNSUPPORTED -> "UNSUPPORTED"; STALE -> "STALE"
        else -> "UNKNOWN"
    }

    /** A refusal the sink accepts quietly: no retry, no user-visible failure, the item is simply not shown. */
    fun isQuietGiveUp(code: Int): Boolean = code != OK && code != ERROR && code != RATE_LIMITED

    /** A hide that failed in a way that may leave something on the glasses: keep the handle and try again on the next tick. */
    fun hideMustRetry(code: Int): Boolean = code == ERROR || code == RATE_LIMITED || code == BUSY
}

/** Feature bits PCA pushes with [NexusPort.setFeatures]. The bridge starts with 0 (all off). STT and ASSIST (the assist-button ask) are only set when the user switched both on. */
object NexusFeatures {
    const val NONE = 0
    const val TTS = 1
    const val STT = 2
    const val ASSIST = 4
    /** The four action-only skills (T6) may run. Without it the bridge answers every skill call with the domain code `disabled`, without calling PCA. */
    const val SKILLS = 8
    /** The bits this version understands; a mask with any other bit is rejected by the bridge. */
    const val KNOWN = TTS or STT or ASSIST or SKILLS
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
    /** A Nexus listening session that never ends is considered over after this long. */
    const val LISTEN_MAX_MS = 30_000L
    /** An ask whose answer never comes back through the sink is forgotten after this long. */
    const val ASK_MAX_MS = 60_000L
    /** The transcript handed to PCA's voice backend is cut to this many UTF-16 units. */
    const val ASK_TEXT_CHARS = 1_000
    /** Nexus kills a skill call after 15 s (Skills v1); PCA answers inside this, so a late answer is never needed. */
    const val SKILL_DEADLINE_MS = 15_000L
    const val SKILL_HOST_TIMEOUT_MS = 12_000L
    /** The hard timeout of ONE binder call from the phone to the bridge: a bridge that does not answer in time is unhealthy (trip, wipe debt, backoff). */
    const val CALL_TIMEOUT_MS = 2_000L
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
