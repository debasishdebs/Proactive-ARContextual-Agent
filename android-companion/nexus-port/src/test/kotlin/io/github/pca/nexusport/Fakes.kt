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
    val epochs = mutableListOf<Long>()
    val forgetGens = mutableListOf<Long>()
    val trippedEpochs = mutableListOf<Long>()
    var forgetResult = NexusResult.OK
    override fun show(cmd: NexusCommand, epoch: Long): Int {
        epochs += epoch
        calls += "show:${cmd.slot.name}"
        if (showResult == NexusResult.OK) { live += cmd.slot; shown += cmd; expiry[cmd.slot] = now() + cmd.ttlMs }
        return showResult
    }
    override fun hide(slot: Slot): Int {
        calls += "hide:${slot.name}"
        if (hideResult == NexusResult.OK) live -= slot
        return hideResult
    }
    override fun speak(text: String, epoch: Long): Int {
        calls += "speak"
        if (speakResult == NexusResult.OK) { live += Slot.SPEECH; spoken += text; expiry[Slot.SPEECH] = now() + NexusLimits.SPEECH_MAX_MS }
        return speakResult
    }
    override fun stopSpeech(): Int {
        calls += "stop"
        if (hideResult == NexusResult.OK) live -= Slot.SPEECH
        return hideResult
    }
    var onForget: (() -> Unit)? = null
    override fun forgetHistory(generation: Long): Int { calls += "forget"; forgets++; forgetGens += generation; onForget?.let { onForget = null; it() }; return forgetResult }
    override fun tripped(epoch: Long): Int { trippedEpochs += epoch; return NexusResult.OK }
    var skillHost = false
    override fun stopListening(): Int {
        calls += "stopListen"
        if (hideResult == NexusResult.OK) live -= Slot.LISTEN
        return hideResult
    }
    override fun setSkillHost(on: Boolean): Int { calls += "skillHost:$on"; skillHost = on; return NexusResult.OK }
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

    var passOverride: (() -> NexusPass)? = null
    override fun pass(): NexusPass = passOverride?.invoke() ?: super.pass()

    private fun maybeThrow(name: String) { if (throwOn == name) throw IllegalStateException("gate $name") }
    override suspend fun sourcesLive(ids: Set<String>): Boolean { provenanceCalls++; maybeThrow("sources"); sourcesGate?.invoke(); return liveIds?.containsAll(ids) ?: true }
    override fun zoneFenceBlocked(): Boolean { maybeThrow("zone"); return zone }
    override fun askFenceBlocked(): Boolean { maybeThrow("ask"); return ask }
    override fun paused(): Boolean { maybeThrow("paused"); return paused }
    override fun worn(): Boolean { maybeThrow("worn"); return worn }
}

/** The phone's ask handler: records what it was asked and what it was told was cancelled. */
class FakeAsks : NexusAskHandler {
    val asked = mutableListOf<Pair<String, String>>()
    val refused = mutableListOf<Pair<String, AskRefusal>>()
    override fun ask(askId: String, text: String) { asked += askId to text }
    override fun refused(askId: String, why: AskRefusal) { refused += askId to why }
}

/** The phone's skill provider: scripted results, optionally slow. */
class FakeSkills : NexusSkillProvider {
    val calls = mutableListOf<SkillOp>()
    var result: (SkillOp) -> SkillResult = { SkillResult.Done(SkillState.CAPTURING) }
    var hang = false
    var boom = false
    override suspend fun invoke(op: SkillOp): SkillResult {
        calls += op
        if (boom) throw IllegalStateException("provider")
        if (hang) kotlinx.coroutines.awaitCancellation()
        return result(op)
    }
}

class FakeActions : NexusActionHandler {
    val marks = mutableListOf<String>()
    val mores = mutableListOf<String>()
    override fun mark(ref: String) { marks += ref }
    override fun more(ref: String) { mores += ref }
}

internal val ON = NexusConfig(enabled = true)
internal val ON_ASK = NexusConfig(enabled = true, sttEnabled = true, assistTakeover = true)
internal val ON_SKILLS = NexusConfig(enabled = true, skillsEnabled = true)
internal val ON_TTS = NexusConfig(enabled = true, ttsEnabled = true)

internal fun nudge(ref: String = "n1", src: Set<String> = setOf("m1"), body: String = "body", ttl: Long = 8_000) = HudEvent.Nudge(ref, "Title", body, src, ttl)
internal fun answer(ref: String = "a1", src: Set<String> = setOf("m1"), speak: Boolean = false) = HudEvent.AskAnswer(ref, "The answer", listOf("Mon note"), src, 10_000, speak)
