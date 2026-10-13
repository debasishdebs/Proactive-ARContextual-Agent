package com.rokid.context.core.model.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

enum class VadDecision { CONTINUE, END_OF_SPEECH, NO_SPEECH, MAX_REACHED }

/**
 * Energy VAD (ported from Live Fit `services/voice/EnergyVad`): ends [silenceMs] after speech goes quiet (spec §5.1:
 * 1.5 s), when nothing was said for [noSpeechMs], or at [maxMs]. Lives in `:core:model` because the glasses may depend
 * only on `:core:*` (ruling); plain JVM.
 */
class EnergyVad(
    private val silenceMs: Int = 1_500,
    private val noSpeechMs: Int = 5_000,
    private val maxMs: Int = 15_000,
    private val minSpeechRms: Double = 300.0,
) {
    private var totalMs = 0.0
    private var silentMs = 0.0
    private var heardSpeech = false

    fun reset() { totalMs = 0.0; silentMs = 0.0; heardSpeech = false }

    fun feed(pcm16le: ByteArray): VadDecision {
        val samples = pcm16le.size / 2
        if (samples == 0) return VadDecision.CONTINUE
        val buf = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        repeat(samples) { val s = buf.short.toDouble(); sum += s * s }
        val ms = samples * 1_000.0 / Pcm.SAMPLE_RATE
        totalMs += ms
        if (sqrt(sum / samples) >= minSpeechRms) { heardSpeech = true; silentMs = 0.0 } else if (heardSpeech) silentMs += ms
        return when {
            totalMs >= maxMs -> VadDecision.MAX_REACHED
            heardSpeech && silentMs >= silenceMs -> VadDecision.END_OF_SPEECH
            !heardSpeech && totalMs >= noSpeechMs -> VadDecision.NO_SPEECH
            else -> VadDecision.CONTINUE
        }
    }
}
