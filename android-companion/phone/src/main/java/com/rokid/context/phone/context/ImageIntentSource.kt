package com.rokid.context.phone.context

import com.rokid.context.ai.contract.IntentOutput
import com.rokid.context.core.model.context.ContextDecision
import com.rokid.context.core.model.context.ContextReason
import com.rokid.context.core.model.context.ContextState
import com.rokid.context.core.model.context.Evidence
import com.rokid.context.work.frames.KeyframeGraph
import com.rokid.context.work.frames.intent.IntentSampler
import java.util.concurrent.atomic.AtomicInteger

/**
 * `image_intent`: the opt-in (default off) driver-vs-passenger photo classifier, spec §6.5/§7.3 as amended and ruled 2026-10-22.
 * `start()` installs an [IntentSampler] in [KeyframeGraph.intentSampler] (frames poke it), `stop()` removes and closes it, which
 * cancels a call in flight. The sampler runs only inside a vehicle episode a listed Bluetooth device started ([episode]); its result
 * is recorded on the photo, counted, and fed to fusion as `Evidence.Intent` stamped with the photo's capture time. Fusion treats it as a
 * vote only: it never starts a vehicle, caps REST or pauses or throttles capture. [sinkFor] is taken inside `start()` (per-run generation).
 */
class ImageIntentSource(
    private val sinkFor: (String) -> EvidenceSink,
    private val decision: () -> ContextDecision,
    private val stats: ContextHubStats,
    private val enabled: () -> Boolean,
    private val create: (episode: () -> Boolean, onResult: (String, Long, IntentOutput) -> Unit, stats: (Boolean?) -> Unit, enabled: () -> Boolean) -> IntentSampler,
    private val install: (IntentSampler?) -> Unit = { KeyframeGraph.intentSampler = it },
    private val installed: () -> IntentSampler? = { KeyframeGraph.intentSampler },
) : ContextSource {
    override val id = ID
    private val count = AtomicInteger()
    override val events: Int get() = count.get()
    private var current: IntentSampler? = null

    override fun available() = true

    @Synchronized
    override fun start(): Boolean {
        stopLocked()
        val sink = sinkFor(ID)
        val sampler = create(
            { episode(decision()) },
            { _, capturedAtMs, out ->
                count.incrementAndGet(); stats.onIntentResult(out.label, out.confidence)
                sink.accept(Evidence.Intent(capturedAtMs, out.label, out.confidence))
            },
            { ok -> if (ok == null) stats.onIntentSkip() else stats.onIntent(ok) },
            enabled,
        )
        current = sampler; install(sampler)
        return true
    }

    @Synchronized override fun stop() { stopLocked() }

    private fun stopLocked() {
        val s = current ?: return
        current = null
        if (installed() === s) install(null)
        s.close()
        stats.clearIntentLast()
    }

    companion object {
        const val ID = "image_intent"

        /**
         * A vehicle state while a listed Bluetooth device ("My car") is connected now; a watch/activity ride, an unlisted device or
         * images alone are not one. The result is a vote: it may flip DRIVING <-> PASSENGER, which only changes nudge suppression
         * (PASSENGER and DRIVING both keep capture unchanged), never capture.
         */
        fun episode(d: ContextDecision): Boolean =
            (d.state == ContextState.VEHICLE_DRIVING || d.state == ContextState.VEHICLE_PASSENGER) && ContextReason.MY_CAR_BT in d.reasons
    }
}
