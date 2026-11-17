package io.github.pca.nexusport

/**
 * What the sink asks before anything leaves for Nexus. PCA's phone app implements it over the real gates (ProvenanceGate, ZoneFence, AskZoneFence, the pause state, the wear signal); the sink never sees
 * Room or a database. Every method may throw: the sink treats a throw as "blocked" (fail closed).
 */
interface NexusGates {
    /** ProvenanceGate: true only when EVERY id (memory id, `frame:` cite, mark id) is still live (not forgotten, tombstoned or wiped). Any doubt = false. */
    suspend fun sourcesLive(ids: Set<String>): Boolean

    /** The Place Guide / pause-zone fence (`ZoneFence.blocked()`): held, or its second opinion says a zone is approached, entered, recorded or unreadable. */
    fun zoneFenceBlocked(): Boolean

    /** Ask's own zone fence (`AskZoneFence.blocked()`), which also closes at startup until the controller judged the zones. */
    fun askFenceBlocked(): Boolean

    /** Capture is paused by the user, quiet hours or auto-stop. */
    fun paused(): Boolean

    /** The glasses are worn. False or unknown = false (fail closed). */
    fun worn(): Boolean
}

/**
 * The user's opt-in switches (Developer/Settings). Everything is OFF by default. [sttEnabled] + [assistTakeover] together turn on the assist-button ask (T5: Nexus speech to text, ruling B-Q4: OFF until the user
 * agrees); PCA's own push-to-talk stays the Ask input path either way. [skillsEnabled] lets the four action skills (T6) run.
 */
data class NexusConfig(
    val enabled: Boolean = false,
    val ttsEnabled: Boolean = false,
    val sttEnabled: Boolean = false,
    val assistTakeover: Boolean = false,
    val skillsEnabled: Boolean = false,
) {
    /** True only when the assist button may open a Nexus speech session: the mode, speech to text and the takeover are all on. */
    fun askEnabled(): Boolean = enabled && sttEnabled && assistTakeover

    /** The bits pushed to the bridge. */
    fun featureMask(): Int {
        var m = NexusFeatures.NONE
        if (enabled && ttsEnabled) m = m or NexusFeatures.TTS
        if (askEnabled()) m = m or NexusFeatures.STT or NexusFeatures.ASSIST
        if (enabled && skillsEnabled) m = m or NexusFeatures.SKILLS
        return m
    }
}

/** What a tap on a notice action does in PCA. Gets PCA's own [ref], never card text. Called at most once per handle and action. */
interface NexusActionHandler {
    /** Mark: save a mark for the item. PCA's own mark path applies its refusals. */
    fun mark(ref: String)

    /** More: open the phone app on the item. */
    fun more(ref: String)
}
