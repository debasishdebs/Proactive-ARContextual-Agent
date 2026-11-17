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
    fun reset() { lastCard = null; lastNudge = null; lastAsk = null; wantedPin = null }

    companion object {
        const val POLL_MS = 15_000L
        /** Well before the pin's 30 min TTL. */
        const val PIN_REFRESH_MS = 10 * 60_000L
    }
}

/** Test seam so a fake can report a live pin without a sink. */
internal fun Set<Slot>.hasPin(): Boolean = Slot.PIN in this
