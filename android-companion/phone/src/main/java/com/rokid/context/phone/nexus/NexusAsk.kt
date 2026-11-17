package com.rokid.context.phone.nexus

import com.rokid.context.voice.AnswerResult
import io.github.pca.nexusport.AskRefusal
import io.github.pca.nexusport.HudEvent
import io.github.pca.nexusport.NexusAskHandler
import io.github.pca.nexusport.NexusGates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * v2.0 T5: the phone's side of the Nexus assist-button ask. The transcript goes through PCA's ONE text backend ([answerer]: `AppAnswerer` over `AskActions.askFrom`, so its question hygiene, Battery Saver,
 * `AskZoneFence` and leases all apply), and the answer leaves ONLY through [out] (`NexusHudSink.show(AskAnswer)`: provenance, zone fences, pause, not worn, fail closed). PCA push-to-talk stays the primary Ask path.
 * An ask inside a pause zone, while paused or while not worn is refused BEFORE the backend runs and again before delivery (the zone may start mid-answer): nothing is delivered then.
 * [speak] is the user's TTS switch. Logs: literal event names only; the transcript and the answer never reach a log.
 */
class PcaNexusAsk(
    private val scope: CoroutineScope,
    private val gates: NexusGates,
    private val answerer: suspend (question: String) -> AnswerResult,
    private val out: HudShow,
    /** The ids a stored ask's answer was built from; null = unreadable (the answer is not delivered). */
    private val askSources: suspend (askId: String) -> Set<String>?,
    private val popupMs: () -> Long,
    private val speak: () -> Boolean,
    private val log: (String) -> Unit = {},
    /** The phone may show the fixed refusal line on its own screen (never on Nexus). */
    private val onRefusedNotice: (String) -> Unit = {},
) : NexusAskHandler {
    private val lock = Any()
    private val jobs = HashMap<String, Job>()

    private fun refusal(): AskRefusal? = try {
        when {
            gates.zoneFenceBlocked() || gates.askFenceBlocked() -> AskRefusal.ZONE_PAUSED
            !gates.worn() -> AskRefusal.NOT_WORN
            gates.paused() -> AskRefusal.PAUSED
            else -> null
        }
    } catch (_: Exception) { AskRefusal.ZONE_PAUSED } // a gate that throws blocks

    override fun ask(askId: String, text: String) {
        refusal()?.let { refused(askId, it); return }
        val job = scope.launch {
            try {
                val r = answerer(text)
                // the zone, a pause or a lost link may have come while the model worked: the last check before anything is delivered
                refusal()?.let { refused(askId, it); return@launch }
                when (r) {
                    is AnswerResult.Answered -> {
                        val sources = if (r.stored) askSources(r.askId) else null
                        if (sources == null) { log("nexus ask answer skipped: sources unreadable"); return@launch }
                        out.show(HudEvent.AskAnswer(ref = askId, answer = r.text, citations = emptyList(), sources = sources, ttlMs = popupMs(), speak = speak()))
                    }
                    is AnswerResult.Failed -> out.show(HudEvent.AskAnswer(ref = askId, answer = r.hudText, citations = emptyList(), sources = emptySet(), ttlMs = popupMs(), speak = false)) // fixed copy only
                }
            } catch (c: CancellationException) { throw c } catch (e: Exception) { log("nexus ask failed (${e.javaClass.simpleName})") }
            finally { synchronized(lock) { jobs.remove(askId) } }
        }
        synchronized(lock) { jobs[askId] = job }
    }

    /** Also called by the sink on a trip, a newer ask or the switch going off: the work stops, nothing was (or will be) delivered. */
    override fun refused(askId: String, why: AskRefusal) {
        synchronized(lock) { jobs.remove(askId) }?.cancel()
        val reason = why.name
        log("nexus ask refused reason=$reason")
        try { onRefusedNotice(why.text) } catch (_: Exception) { }
    }
}
