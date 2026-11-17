package io.github.pca.nexusport

/** A transport that behaves like Nexus: one notice, one pin, one voice. Scripted failures per operation. */
internal class FakeNexusTransport : NexusTransport {
    val live = linkedSetOf<Slot>()
    var now: () -> Long = { 0L }
    private val expiry = HashMap<Slot, Long>()
    /** Nexus drops a notice or pin on its own TTL; the fake does the same when told the time moved. */
    fun expire(at: Long) { for ((s, e) in expiry.toMap()) if (at >= e) { live -= s; expiry -= s } }
    val calls = mutableListOf<String>()
    val shown = mutableListOf<NexusCommand>()
    val spoken = mutableListOf<String>()
    var features = 0
    var forgets = 0
    var events: NexusEvents? = null
    var showResult = NexusResult.OK
    var hideResult = NexusResult.OK
    var speakResult = NexusResult.OK

    override fun setEvents(events: NexusEvents?): Int { this.events = events; return NexusResult.OK }
    override fun setFeatures(mask: Int): Int { calls += "features"; features = mask; return NexusResult.OK }
    override fun show(cmd: NexusCommand): Int {
        calls += "show:${cmd.slot.name}"
        if (showResult == NexusResult.OK) { live += cmd.slot; shown += cmd; expiry[cmd.slot] = now() + cmd.ttlMs }
        return showResult
    }
    override fun hide(slot: Slot): Int {
        calls += "hide:${slot.name}"
        if (hideResult == NexusResult.OK) live -= slot
        return hideResult
    }
    override fun speak(text: String): Int {
        calls += "speak"
        if (speakResult == NexusResult.OK) { live += Slot.SPEECH; spoken += text; expiry[Slot.SPEECH] = now() + NexusLimits.SPEECH_MAX_MS }
        return speakResult
    }
    override fun stopSpeech(): Int {
        calls += "stop"
        if (hideResult == NexusResult.OK) live -= Slot.SPEECH
        return hideResult
    }
    override fun forgetHistory(): Int { calls += "forget"; forgets++; return NexusResult.OK }
    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
}

/** Gates the tests flip. Each can throw on demand (fail closed). */
class FakeGates : NexusGates {
    var liveIds: Set<String>? = null          // null = every id is live
    var zone = false
    var ask = false
    var paused = false
    var worn = true
    var throwOn: String? = null               // "sources" | "zone" | "ask" | "paused" | "worn"
    var sourcesGate: (suspend () -> Unit)? = null
    var provenanceCalls = 0

    private fun maybeThrow(name: String) { if (throwOn == name) throw IllegalStateException("gate $name") }
    override suspend fun sourcesLive(ids: Set<String>): Boolean { provenanceCalls++; maybeThrow("sources"); sourcesGate?.invoke(); return liveIds?.containsAll(ids) ?: true }
    override fun zoneFenceBlocked(): Boolean { maybeThrow("zone"); return zone }
    override fun askFenceBlocked(): Boolean { maybeThrow("ask"); return ask }
    override fun paused(): Boolean { maybeThrow("paused"); return paused }
    override fun worn(): Boolean { maybeThrow("worn"); return worn }
}

class FakeActions : NexusActionHandler {
    val marks = mutableListOf<String>()
    val mores = mutableListOf<String>()
    override fun mark(ref: String) { marks += ref }
    override fun more(ref: String) { mores += ref }
}

internal val ON = NexusConfig(enabled = true)
internal val ON_TTS = NexusConfig(enabled = true, ttsEnabled = true)

internal fun nudge(ref: String = "n1", src: Set<String> = setOf("m1"), body: String = "body", ttl: Long = 8_000) = HudEvent.Nudge(ref, "Title", body, src, ttl)
internal fun answer(ref: String = "a1", src: Set<String> = setOf("m1"), speak: Boolean = false) = HudEvent.AskAnswer(ref, "The answer", listOf("Mon note"), src, 10_000, speak)
