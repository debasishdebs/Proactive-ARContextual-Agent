package io.github.pca.nexusbridge

import io.github.pca.nexusport.NexusAction
import io.github.pca.nexusport.NexusFeatures
import io.github.pca.nexusport.NexusLimits
import io.github.pca.nexusport.NexusResult
import io.github.pca.nexusport.Slot

/** Bridge -> PCA events: ids and codes only, never text. */
interface BridgeEvents {
    fun onAction(slot: Int, actionId: String)
    fun onSpeechDone()
    fun onForgetAccepted()
}

/**
 * The bridge's whole logic: a dumb renderer. It holds NO card text, no database and no log of content; it validates what the port hands it (a second line of defence behind the sink), draws it through
 * [NexusBusClient], and relays taps back. Voice is OFF until PCA sets the TTS bit with [setFeatures]; STT and the assist takeover have no code path here at all (T5). The initial feature mask is 0.
 */
class BridgeRenderer(private val bus: () -> NexusBusClient?, private val log: (String) -> Unit = {}) {
    @Volatile var events: BridgeEvents? = null
    @Volatile private var features = NexusFeatures.NONE

    fun setFeatures(mask: Int): Int {
        if (mask and NexusFeatures.KNOWN.inv() != 0) return NexusResult.INVALID
        features = mask
        log("bridge features set tts=${mask and NexusFeatures.TTS != 0}")
        // The bridge also honours the switch on its own: all off hides everything, TTS off stops the voice.
        if (mask == NexusFeatures.NONE) hideAll() else if (mask and NexusFeatures.TTS == 0) bus()?.stopSpeech()
        return NexusResult.OK
    }

    fun show(slot: Int, title: String?, text: String?, ttlMs: Long, wake: Boolean, ids: List<String>, labels: List<String>): Int {
        val s = Slot.of(slot) ?: return NexusResult.INVALID
        if (title == null || text == null || ids.size != labels.size || ids.size > NexusLimits.MAX_ACTIONS) return NexusResult.INVALID
        val b = bus() ?: return quiet(NexusResult.NOT_REGISTERED)
        val r = when (s) {
            Slot.NOTICE -> {
                if (title.length > NexusLimits.NOTICE_TITLE_CHARS || text.length > NexusLimits.NOTICE_TEXT_CHARS || text.count { it == '\n' } >= NexusLimits.NOTICE_LINES) return NexusResult.INVALID
                if (ttlMs !in NexusLimits.NOTICE_TTL_MIN_MS..NexusLimits.NOTICE_TTL_MAX_MS) return NexusResult.INVALID
                if (labels.any { it.length > NexusLimits.ACTION_LABEL_CHARS }) return NexusResult.INVALID
                b.showNotice(title, text, ttlMs, wake, ids.zip(labels).map { NexusAction(it.first, it.second) })
            }
            Slot.PIN -> {
                val lines = text.split('\n')
                if (title.length > NexusLimits.PIN_TITLE_CHARS || lines.size > NexusLimits.PIN_LINES || lines.any { it.length > NexusLimits.PIN_LINE_CHARS }) return NexusResult.INVALID
                b.showPin(title, lines, ttlMs.coerceIn(NexusLimits.PIN_TTL_MIN_MS, NexusLimits.PIN_TTL_MAX_MS))
            }
            else -> return NexusResult.UNSUPPORTED
        }
        return quiet(r)
    }

    fun hide(slot: Int): Int = when (Slot.of(slot)) {
        Slot.NOTICE -> bus()?.hideNotice() ?: NexusResult.OK
        Slot.PIN -> bus()?.hidePin() ?: NexusResult.OK
        Slot.SPEECH -> bus()?.stopSpeech() ?: NexusResult.OK
        Slot.ACTIVITY, Slot.SURFACE -> NexusResult.OK
        null -> NexusResult.INVALID
    }

    fun speak(text: String?): Int {
        if (features and NexusFeatures.TTS == 0) return NexusResult.DISABLED
        if (text.isNullOrBlank() || text.length > NexusLimits.SPEECH_CHARS) return NexusResult.INVALID
        val b = bus() ?: return quiet(NexusResult.NOT_REGISTERED)
        return quiet(b.speak(text))
    }

    fun stopSpeech(): Int = bus()?.stopSpeech() ?: NexusResult.OK

    fun forgetHistory(): Int {
        val b = bus() ?: return quiet(NexusResult.NOT_REGISTERED)
        return b.forgetNotices { events?.onForgetAccepted() }
    }

    /** PCA's process died, or it asked for everything off. Never throws. */
    fun hideAll() {
        val b = bus() ?: return
        b.hideNotice(); b.hidePin(); b.stopSpeech()
        log("bridge hid all")
    }

    fun noticeAction(id: String) { events?.onAction(Slot.NOTICE.code, id) }
    fun speechDone() { events?.onSpeechDone() }

    private fun quiet(code: Int): Int {
        if (code != NexusResult.OK) log("bridge call refused code=${NexusResult.name(code)}")
        return code
    }
}
