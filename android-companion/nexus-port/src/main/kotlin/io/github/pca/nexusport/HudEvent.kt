package io.github.pca.nexusport

/** What PCA wants on the glasses, before the gates. Every content event declares the memory/frame/mark ids its text was built from. */
sealed interface HudEvent {
    /** Memory ids, `frame:` cites and mark ids the text was derived from. All must be live (ProvenanceGate); empty = content-free. */
    val sources: Set<String>

    /** Proactive items (nudges, place cards) are dropped while PCA is paused; asked-for and state items are not. */
    val proactive: Boolean

    /** An id of PCA's own (nudge id, ask id) handed back with an action; never text. */
    val ref: String

    data class Nudge(override val ref: String, val title: String, val body: String, override val sources: Set<String>, val ttlMs: Long, val wake: Boolean = false) : HudEvent {
        override val proactive get() = true
    }

    data class PlaceCard(override val ref: String, val title: String, val body: String, override val sources: Set<String>, val ttlMs: Long, val wake: Boolean = false) : HudEvent {
        override val proactive get() = true
    }

    /** An answer to an Ask. [citations] are short labels for the footer. [speak]: also say it (only when the user switched Nexus TTS on). */
    data class AskAnswer(override val ref: String, val answer: String, val citations: List<String>, override val sources: Set<String>, val ttlMs: Long, val speak: Boolean = false) : HudEvent {
        override val proactive get() = false
    }

    /** A mark confirmation: [reasonToken] is a `MarkPhotoReason` token (`pause_zone`, ...), never a place name. */
    data class MarkConfirm(override val ref: String, val reasonToken: String, override val sources: Set<String>, val ttlMs: Long) : HudEvent {
        override val proactive get() = false
    }

    /** The capture state pin. Content-free: fixed text, never a place name. */
    data class CaptureState(val state: State) : HudEvent {
        enum class State { CAPTURING, PAUSED, STOPPED }
        override val sources: Set<String> get() = emptySet()
        override val proactive get() = false
        override val ref get() = "capture"
    }

    /** Kinds PCA 2.0 does not draw on Nexus (cut to 2.x): mapped to nothing and dropped as UNSUPPORTED. */
    data class Activity(override val ref: String, val title: String, override val sources: Set<String>) : HudEvent {
        override val proactive get() = false
    }

    data class SurfaceCard(override val ref: String, val title: String, override val sources: Set<String>) : HudEvent {
        override val proactive get() = false
    }
}

/** A mapped event: the HUD command and, for an answer, the line to speak. */
data class Mapped(val command: NexusCommand, val speech: String? = null)
