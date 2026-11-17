package io.github.pca.nexusbridge

import io.github.pca.nexusport.AskFail
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
    /** The hub accepted the wipe requested with [generation] (additive; v2 r2 P1-3). */
    fun onForgetAcceptedAt(generation: Long) = Unit
    /** T5: a Nexus speech session opened or ended. */
    fun onListening(active: Boolean) = Unit
    /** T5: the one final transcript (never logged). */
    fun onAskTranscript(text: String) = Unit
    /** T5: the session ended with no transcript ([io.github.pca.nexusport.AskFail] code). */
    fun onAskFailed(reason: Int) = Unit
}

/**
 * The bridge's whole logic: a dumb renderer. It holds NO card text, no database and no log of content; it validates what the port hands it (a second line of defence behind the sink), draws it through
 * [NexusBusClient], and relays taps back. Voice is OFF until PCA sets the TTS bit with [setFeatures]; STT and the assist takeover have no code path here at all (T5). The initial feature mask is 0.
 */
class BridgeRenderer(
    private val bus: () -> NexusBusClient?,
    private val log: (String) -> Unit = {},
    /** Asks PCA's phone (synchronously) whether a Nexus speech session may open NOW. Default: no (fail closed). Called BEFORE any speech session is opened. */
    private val authorizeListen: () -> Boolean = { false },
    /** The longest the phone's `authorizeListen` binder call may take; no answer in time = no (fail closed). */
    private val authorizeTimeoutMs: Long = 2_000L,
) {
    @Volatile var events: BridgeEvents? = null
    @Volatile private var features = NexusFeatures.NONE
    /** The feature bits PCA last set (0 until it sets any). The skill router reads it. */
    val currentFeatures: Int get() = features
    private val authPool = java.util.concurrent.ThreadPoolExecutor(0, 4, 3, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue()) { r -> Thread(r, "pca-bridge-auth").also { it.isDaemon = true } }
    private val listenLock = Any()
    private var listenGen = 0
    private var finalSeen = false

    fun setFeatures(mask: Int): Int {
        if (mask and NexusFeatures.KNOWN.inv() != 0) return NexusResult.INVALID
        features = mask
        log("bridge features set tts=${mask and NexusFeatures.TTS != 0}")
        // The bridge also honours the switch on its own: all off hides everything, TTS off stops the voice.
        if (mask == NexusFeatures.NONE) hideAll() else if (mask and NexusFeatures.TTS == 0) bus()?.stopSpeech()
        if (!askOn(mask)) stopListening()
        applyTakeover()
        return NexusResult.OK
    }

    private fun askOn(mask: Int) = mask and NexusFeatures.STT != 0 && mask and NexusFeatures.ASSIST != 0

    /** The hub's takeover switch follows PCA's: ON only when the user switched the ask on, OFF otherwise (the hub's own default is ON once the wearer approves `assistant`). Also call on registration. */
    fun applyTakeover() {
        val r = bus()?.setAssistTakeover(askOn(features)) ?: return
        if (r != NexusResult.OK) log("bridge takeover refused code=${NexusResult.name(r)}")
    }

    // ---- T5: the assist-button ask. Nexus owns the microphone and the speech engine; the bridge only relays. ----

    /** The hub opened this plugin with `AI_ASSIST`. Opens ONE speech session, only when PCA switched the ask on and its callback is set. Never retries. */
    fun assistOpened() {
        if (!askOn(features)) { log("bridge assist ignored reason=OFF"); return }
        val ev = events
        if (ev == null) { log("bridge assist ignored reason=NO_PCA"); return }
        val b = bus()
        if (b == null) { log("bridge assist ignored reason=NO_BUS"); return }
        val gen: Int
        synchronized(listenLock) {
            gen = ++listenGen
            finalSeen = false
        }
        b.stopListening()      // never two sessions
        // PCA's gates (mode, worn, zone fences, PAUSE) answer BEFORE the microphone is opened; no answer in time, a throw or a no = no session.
        val allowed = authorizedInTime()
        val callbacks = object : ListenCallbacks {
            override fun onStarted() { if (current(gen)) ev.onListening(true) }
            override fun onFinal(text: String) {
                synchronized(listenLock) { if (gen != listenGen || finalSeen) return; finalSeen = true }
                ev.onAskTranscript(text)
            }
            override fun onStopped(reason: ListenStop) {
                val seen: Boolean
                synchronized(listenLock) { if (gen != listenGen) return; seen = finalSeen }
                log("bridge listening ended reason=${reason.name}")
                when {
                    reason == ListenStop.DENIED_BUSY -> ev.onAskFailed(AskFail.DENIED_BUSY)
                    reason == ListenStop.NO_SPEECH && !seen -> ev.onAskFailed(AskFail.NO_SPEECH)
                    reason == ListenStop.FAILED && !seen -> ev.onAskFailed(AskFail.OTHER)
                    else -> ev.onListening(false)
                }
            }
        }
        // COMPARE-AND-OPEN: the generation is checked and the session opened under the SAME lock a trip ([stopListening]) takes to bump it. A trip that completed first makes the compare fail (nothing opens);
        // a trip that comes later waits for the open and then stops it. No interleaving leaves a session open after a completed trip.
        var r = NexusResult.OK
        var opened = false
        synchronized(listenLock) {
            if (allowed && gen == listenGen) { r = b.startListening(callbacks); opened = true }
        }
        if (!opened) { log("bridge assist ignored reason=NOT_AUTHORIZED"); return }
        if (!current(gen)) { b.stopListening(); return }      // belt and braces: a trip that finished meanwhile still closes the session (idempotent)
        if (r != NexusResult.OK) {
            log("bridge listening refused code=${NexusResult.name(r)}")
            ev.onAskFailed(AskFail.OTHER)
        }
    }

    /** The phone's answer, in [authorizeTimeoutMs] at most: a call that hangs, throws or answers no is a no. */
    private fun authorizedInTime(): Boolean {
        val f = try { authPool.submit(java.util.concurrent.Callable { authorizeListen() }) } catch (_: java.util.concurrent.RejectedExecutionException) { return false }
        return try { f.get(authorizeTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) == true }
        catch (_: java.util.concurrent.TimeoutException) { f.cancel(true); log("bridge listen authorization timed out"); false }
        catch (_: java.util.concurrent.ExecutionException) { false }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
    }

    private fun current(gen: Int) = synchronized(listenLock) { gen == listenGen }

    /** PCA asked to stop (a trip), the ask was switched off, or PCA is gone: stops the Nexus session and ignores whatever it still reports. Idempotent. */
    fun stopListening(): Int {
        synchronized(listenLock) { listenGen++; finalSeen = true }
        return bus()?.stopListening() ?: NexusResult.OK
    }

    // ---- v2 r2 P1-1: trip epochs. The phone stamps every show and speech with its trip epoch and tells the bridge the latest trip; a late show after a trip is dropped here. ----

    private val latestTrip = java.util.concurrent.atomic.AtomicLong(0)

    /** A new phone connection starts a new epoch counter. */
    fun resetEpoch() { latestTrip.set(0) }

    /**
     * Records the latest trip epoch (monotonic max) BEFORE returning, so every later [showAt] / [speakAt] stamped with an older epoch is refused before anything is rendered or sent to Nexus, and invalidates the listening
     * generation: a speech session that is being opened (authorized, no handle on the phone yet) is stopped and nothing it reports is relayed (r3 P1-1, P1-4).
     */
    fun tripAt(epoch: Long): Int {
        latestTrip.accumulateAndGet(epoch) { a, b -> if (a > b) a else b }
        stopListening()
        return NexusResult.OK
    }

    private fun stale(epoch: Long) = epoch < latestTrip.get()

    /** [show] unless the phone tripped after this show was admitted; one that lands while the trip happens is hidden again. */
    fun showAt(epoch: Long, slot: Int, title: String?, text: String?, ttlMs: Long, wake: Boolean, ids: List<String>, labels: List<String>): Int {
        if (stale(epoch)) { log("bridge show stale"); return NexusResult.STALE }
        val r = show(slot, title, text, ttlMs, wake, ids, labels)
        if (r == NexusResult.OK && stale(epoch)) { hide(slot); log("bridge show stale"); return NexusResult.STALE }
        return r
    }

    fun speakAt(epoch: Long, text: String?): Int {
        if (stale(epoch)) { log("bridge show stale"); return NexusResult.STALE }
        val r = speak(text)
        if (r == NexusResult.OK && stale(epoch)) { stopSpeech(); log("bridge show stale"); return NexusResult.STALE }
        return r
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
        Slot.LISTEN -> stopListening()
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

    /** [forgetHistory] for the debt [generation]: the acknowledgement carries it back. */
    fun forgetHistoryAt(generation: Long): Int {
        val b = bus() ?: return quiet(NexusResult.NOT_REGISTERED)
        return b.forgetNotices { events?.onForgetAcceptedAt(generation) }
    }

    /** PCA's process died, or it asked for everything off. Never throws. */
    fun hideAll() {
        stopListening()
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
