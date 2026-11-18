package com.rokid.context.phone.nexus

import com.rokid.context.core.model.Overlay
import com.rokid.context.work.frames.place.PlaceCard
import com.rokid.context.work.frames.place.PlaceConfidence
import io.github.pca.nexusport.HudEvent
import io.github.pca.nexusport.Slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A stored nudge that the hub would pop up (the ones PCA itself draws as a HEADS UP toast), with the ids its text was built from. */
data class NexusNudge(val id: String, val text: String, val sources: Set<String>)

/**
 * Feeds the EXISTING PCA flows (place card, nudge, answer, mark saved, capture state) to the Nexus provider, as [HudEvent]s, and only while the user chose Nexus as the HUD host (the provider drops
 * everything else). Nothing here decides what is allowed: every item goes through the sink's gates. An item that cannot name its sources is not sent (an answer whose stored ask is unreadable).
 * One item per card instance (a repeated emission of the same card id is ignored). Logs: literal event names only.
 */
class NexusFeeder(
    private val scope: CoroutineScope,
    private val out: HudShow,
    /** `HudSettings.popupMs()`: how long a card stays (the sink clamps it to what Nexus allows). */
    private val popupMs: () -> Long,
    /** The ids an answer was built from (memory ids, `frame:` cites); null = unreadable (the answer is not sent). */
    private val askSources: suspend (String) -> Set<String>?,
    private val pinLive: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    /**
     * v3.1 r1: asked at the suggestion's OWN publish point (in the queued coroutine, after the delivery left its fences): every gate again (driving incl. UNKNOWN, master and the three switches, pause, zone, quiet hours with
     * a fresh clock, worn, still stored and not withdrawn). False = dropped. Production: `ProactiveDelivery.publishable`. The default drops (fail closed).
     */
    private val suggestionGate: suspend (id: String) -> Boolean = { false },
    /** Takes one suggestion off Nexus by ref (the provider's withdraw); used when a withdrawal landed while the coroutine was between its gate and the show. */
    private val withdrawOut: (id: String) -> Unit = {},
) {
    private var lastCard: String? = null
    private var lastNudge: String? = null
    private var lastAsk: String? = null
    private var wantedPin: HudEvent.CaptureState.State? = null
    private var pinSentAt = 0L

    private fun send(event: HudEvent) {
        scope.launch {
            try { out.show(event) } catch (c: CancellationException) { throw c } catch (_: Exception) { log("nexus feed failed") }
        }
    }

    fun placeCard(c: PlaceCard?) {
        if (c == null) return
        if (c.cardId.isNotEmpty() && c.cardId == lastCard) return
        lastCard = c.cardId
        val note = if (c.confidence == PlaceConfidence.NEARBY) "Nearby, not checked against your photo" else null
        val body = listOfNotNull(c.line, c.sourceLabel.takeIf { it.isNotBlank() }, note).joinToString("\n")
        send(HudEvent.PlaceCard(ref = c.cardId.ifEmpty { "place" }, title = c.title, body = body, sources = setOf("frame:" + c.frameId), ttlMs = popupMs()))
    }

    fun nudge(n: NexusNudge?) {
        if (n == null || n.id == lastNudge) return
        lastNudge = n.id
        send(HudEvent.Nudge(ref = n.id, title = "Heads up", body = n.text, sources = n.sources, ttlMs = popupMs()))
    }

    private val suggestionIds = java.util.LinkedHashSet<String>()
    /** Suggestions whose coroutine is queued or running (the intent is registered BEFORE the launch) and the ones withdrawn meanwhile. Guarded by [suggestionIds]. */
    private val inFlight = HashMap<String, kotlinx.coroutines.Job?>()
    private val withdrawnIds = java.util.LinkedHashSet<String>()

    /**
     * A delivered suggestion (v3.1), as a Nexus notice through the sink's gates (it re-runs sources live, zone fences, pause and not-worn). Returns false when it was already sent. Title is a fixed word; the body is
     * the template line and the sources label. The delivery leaves its fences before the coroutine runs, so the coroutine asks [suggestionGate] at its own publish point and a withdrawal ([cancelSuggestions]) that lands
     * before the coroutine has a handle still wins (the intent is registered first). Logs: literal only.
     */
    fun suggestion(c: com.rokid.context.orchestration.proactive.DeliveredCard): Boolean {
        synchronized(suggestionIds) {
            if (!suggestionIds.add(c.id)) return false
            while (suggestionIds.size > 16) suggestionIds.remove(suggestionIds.first())
            withdrawnIds.remove(c.id); inFlight[c.id] = null // the intent first
        }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                if (cancelled(c.id)) return@launch
                if (!suggestionGate(c.id)) { log("nexus suggestion dropped"); return@launch }
                if (cancelled(c.id)) return@launch
                out.show(HudEvent.Nudge(ref = c.id, title = "Suggestion", body = "${c.title}: ${c.line}\n${c.sourceLabel}", sources = c.sources, ttlMs = popupMs()))
                if (cancelled(c.id)) withdrawOut(c.id) // a withdrawal landed between the last check and the show: take it off again
            } catch (e: CancellationException) { throw e } catch (_: Exception) { log("nexus feed failed") }
        }
        job.invokeOnCompletion { synchronized(suggestionIds) { inFlight.remove(c.id) } } // also for a job cancelled before it ever started
        synchronized(suggestionIds) { if (c.id in withdrawnIds) job.cancel() else inFlight[c.id] = job }
        job.start()
        return true
    }

    private fun cancelled(id: String) = synchronized(suggestionIds) { id in withdrawnIds }

    /** Withdraws queued or running suggestions: marks the intent (also for a coroutine that has no handle yet), cancels what has one. The provider's own withdraw of what was already shown stays with the caller. */
    fun cancelSuggestions(ids: Collection<String>) {
        val jobs = synchronized(suggestionIds) { ids.forEach { withdrawnIds.add(it) }; while (withdrawnIds.size > 64) withdrawnIds.remove(withdrawnIds.first()); ids.mapNotNull { inFlight[it] } }
        jobs.forEach { it.cancel() }
    }

    /** The ids of the suggestions sent (bounded), for a withdrawal of all of them. */
    fun suggestionRefs(): List<String> = synchronized(suggestionIds) { suggestionIds.toList() + inFlight.keys }

    /** The answer card on PCA's voice overlay. Only an answer of a stored ask (it has an ask id to read its sources from) is sent. */
    fun answer(o: Overlay?) {
        val a = o as? Overlay.Answer ?: return
        val id = a.askId ?: return
        if (id == lastAsk || a.text.isBlank()) return
        lastAsk = id
        scope.launch {
            try {
                val sources = askSources(id)
                if (sources == null) { log("nexus answer skipped: sources unreadable"); return@launch }
                out.show(HudEvent.AskAnswer(ref = id, answer = a.text, citations = emptyList(), sources = sources, ttlMs = popupMs(), speak = false))
            } catch (c: CancellationException) { throw c } catch (_: Exception) { log("nexus feed failed") }
        }
    }

    /** A mark was saved (the glasses' tap, accepted by the recorder). Content-free: a fixed token, no place or time. */
    fun markSaved(ref: String) {
        send(HudEvent.MarkConfirm(ref = ref, reasonToken = "ok", sources = emptySet(), ttlMs = popupMs()))
    }

    /** The capture state for the status pin; sent on change and refreshed before its TTL by [pollPin]. */
    fun capture(state: HudEvent.CaptureState.State) {
        wantedPin = state
        pinSentAt = clock()
        send(HudEvent.CaptureState(state))
    }

    /** Re-sends the pin when it is gone (a trip withdrew it, a gate reopened) or old. Cheap and idempotent. */
    fun pollPin() {
        val s = wantedPin ?: return
        if (pinLive() && clock() - pinSentAt < PIN_REFRESH_MS) return
        pinSentAt = clock()
        send(HudEvent.CaptureState(s))
    }

    /** The poll loop; cancel it with the scope. */
    fun startPolling(everyMs: Long = POLL_MS) = scope.launch { while (isActive) { delay(everyMs); pollPin() } }

    /** The host was switched away or the hub stopped: forget what was sent, so a later switch back shows the next card. */
    fun reset() { lastCard = null; lastNudge = null; lastAsk = null; wantedPin = null; val all = synchronized(suggestionIds) { (suggestionIds.toList() + inFlight.keys).distinct().also { suggestionIds.clear() } }; cancelSuggestions(all) }

    companion object {
        const val POLL_MS = 15_000L
        /** Well before the pin's 30 min TTL. */
        const val PIN_REFRESH_MS = 10 * 60_000L
    }
}

/** Test seam so a fake can report a live pin without a sink. */
internal fun Set<Slot>.hasPin(): Boolean = Slot.PIN in this
